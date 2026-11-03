package net.hawyn.ablefisp.patch

/** EFISP-local recovery. Never edits the separately signed parent ABL.
 * Native initialization, healthy boot, warning continuations and manual commands
 * remain native. Only proved blocking edges request a fresh, frame-safe main.
 */
internal object FastbootRecoveryPatcher {
    data class Result(val bytes: ByteArray, val patches: List<PatchMatch>, val notes: List<String>,
        val metadata: Map<String, Any>)
    private data class Change(val name: String, val rva: Long, val bytes: ByteArray)

    fun remove(input: ByteArray): ByteArray = FastbootRecoverySections.remove(input).bytes

    fun patch(input: ByteArray): Result {
        // Parsing and owned-receipt restoration are not optional matching.
        // A malformed image/receipt must not become a guessed native view.
        val removed = FastbootRecoverySections.remove(input)
        val image = PeImage.parse(removed.bytes)
        val skipped = arrayListOf<String>()
        fun <T> optional(name: String, fallback: T, analyze: () -> T): T = try {
            analyze()
        } catch (failure: PatchException) {
            skipped += "$name 未完整匹配，已保留该项原生逻辑：${failure.message.orEmpty()}"
            fallback
        }
        val key = optional("音量下 Fastboot 资格策略", SemanticPlan(emptyList(), emptyList())) {
            FastbootKeyPolicyPatcher.analyze(image)
        }
        val facts = try {
            FastbootRecoveryFacts.analyze(image).also { facts ->
                if (!facts.prerequisites.earlyJumpProven)
                    throw PatchException("Fastboot 恢复入口 ABI 尚未完整证明：${facts.prerequisites.note}")
                if (!facts.entryPrefix.tailRestoresEntryFrame || !facts.frame.returnRestoresFrame)
                    throw PatchException("EFI/main 原生栈恢复关系未匹配。")
            }
        } catch (failure: PatchException) {
            return keyOnly(image, key, skipped + "可选故障恢复模块未完整匹配，保留既有 KBL 和已证明的按键策略：${failure.message.orEmpty()}")
        }
        val discoveredSlots = optional<FastbootSlotRecoveryFacts.Plan?>("自动切槽恢复", null) {
            FastbootSlotRecoveryFacts.analyze(image)
        }
        val emptyFailures = AutoBootFailureFacts.Facts(emptyList(), emptyList(), emptyList(), emptyList())
        var failures = optional("其他阻断故障恢复", emptyFailures) { AutoBootFailureFacts.analyze(image, facts) }
        if (failures.safeWaitResumes.filter { it.cleanupRva != 0L }
                .map { it.eventSlotRva to it.cleanupRva }.distinct().size > 1) {
            skipped += "回调同步恢复存在多个未统一的活动事件，已保留此组原生逻辑。"
            failures = emptyFailures
        }
        val slots = discoveredSlots?.let { plan ->
            val usable = plan.automaticCallSites.filter { site ->
                val supported = !site.requiresDeferredCallback || site.safeWaitResumeRvas.isNotEmpty() &&
                    site.safeWaitResumeRvas.all { pc -> failures.safeWaitResumes.any {
                        it.hookRva == pc && it.cleanupRva != 0L && it.cleanupEvidence.isNotEmpty()
                    } }
                if (!supported) skipped += "自动切槽回调 ${site.callRva.toString(16)} 缺少同步清理关系，已保留该调用。"
                supported
            }
            plan.copy(automaticCallSites = usable)
        }
        return try {
            val installed = install(image, facts, key, slots, failures)
            installed.copy(notes = installed.notes + skipped,
                metadata = installed.metadata + mapOf("recoveryApplied" to true, "optionalFeatureSkips" to skipped))
        } catch (failure: PatchException) {
            if (!failure.message.orEmpty().startsWith("Fastboot recovery：")) throw failure
            // Installation is transactional in new byte arrays. A missing
            // optional ABI/edge leaves the already installed KBL untouched.
            // Runtime integrity/check failures are not caught here.
            keyOnly(image, key, skipped + "可选故障恢复代码关系未完整匹配，保留既有 KBL 和已证明的按键策略：${failure.message.orEmpty()}")
        }
    }

    /** Independent volume-key edits do not require a new recovery module. */
    private fun keyOnly(image: PeImage, key: SemanticPlan, notes: List<String>): Result {
        val output = image.bytes.copyOf()
        val occupied = hashSetOf<Int>()
        val matches = key.edits.map { edit ->
            val at = image.rvaToOffset(edit.rva, edit.replacement.size) ?: fail("按键策略的实际映射")
            for (p in at until at + edit.replacement.size) if (!occupied.add(p)) fail("按键策略范围重叠")
            val before = output.copyOfRange(at, at + edit.replacement.size)
            edit.replacement.copyInto(output, at)
            PatchMatch(edit.name, edit.rva, at, before.hex(), edit.replacement.hex(), before.contentEquals(edit.replacement))
        }
        return Result(output, matches, key.notes + notes,
            mapOf("recoveryApplied" to false, "keyOnly" to key.edits.isNotEmpty(), "optionalFeatureSkips" to notes,
                "autoSlotSites" to emptyList<Long>(), "defaultSlotSites" to emptyList<Long>(), "manualCommandsPreserved" to true))
    }

    internal fun install(image: PeImage, facts: FastbootRecoveryFacts.Facts, key: SemanticPlan,
                         slots: FastbootSlotRecoveryFacts.Plan?, failures: AutoBootFailureFacts.Facts): Result {
        require(facts.prerequisites.earlyJumpProven)
        val scopePrefix=facts.scopePrefix?:fail("原生 Fastboot scope 前缀尚未完整证明")
        if(!scopePrefix.noExternalEntryProven || scopePrefix.startRva!=facts.fastbootSetupRva ||
            scopePrefix.originalWords.isEmpty() || scopePrefix.resumeRva!=scopePrefix.loggerCallRva+4 ||
            scopePrefix.startRva+scopePrefix.originalWords.size*4L!=scopePrefix.resumeRva ||
            scopePrefix.originalWords.withIndex().any { (n,w) -> image.word(scopePrefix.startRva+n*4L)!=w })
            fail("原生 Fastboot scope 前缀范围或入边证据")
        val eventCleanups=failures.safeWaitResumes.filter { it.cleanupRva!=0L }
            .map { it.eventSlotRva to it.cleanupRva }.distinct()
        if(eventCleanups.size>1)fail("同步恢复的多个原生活动事件尚未统一建模")
        val eventCleanup=eventCleanups.singleOrNull()
        val module = FastbootRecoveryModule.bytes
        check(module.sha256() == FastbootRecoveryModule.SHA256)
        // Capacity comes from the number and exact lengths of emitted stubs.
        // A dry run supplies a real RVA; the second pass must have identical size.
        fun generate(rx: Long, rw: Long): Pair<ByteArray, List<Change>> {
            val code = Code(rx, module)
            val changes = ArrayList<Change>()
            val displacedWrappedCalls=HashSet<Long>()
            fun edit(name: String, rva: Long, bytes: ByteArray) {
                val raw = image.rvaToOffset(rva, bytes.size) ?: fail("$name 的原映射")
                require(raw >= image.headersSize)
                val existing = changes.firstOrNull { it.rva == rva }
                if (existing != null) {
                    if (!existing.bytes.contentEquals(bytes)) fail("$name 与 ${existing.name} 挂接冲突")
                    return
                }
                if (changes.any { rva < it.rva + it.bytes.size && it.rva < rva + bytes.size }) fail("$name 挂接范围重叠")
                changes += Change(name, rva, bytes)
            }
            fun word(name: String, pc: Long, value: Int) = edit(name, pc, words(value))
            fun hook(name: String, pc: Long, body: Code.() -> Unit) {
                code.align(); val stub = code.pc; code.body()
                word(name, pc, A64.branch(pc, stub))
            }
            fun request(reason: Int, site: Long) {
                code.immediate(0, reason.toLong())
                code.immediate(1, Long.MIN_VALUE or 21L) // EFI_ABORTED, blocking edge only.
                code.address(2, site)
                code.branch(rx + FastbootRecoveryModule.REQUEST_OFFSET)
            }
            fun replay(pc: Long) {
                val original=image.word(pc)?:fail("原指令")
                val target=A64.blTarget(pc,original)
                when(target) {
                    facts.continueFlow.authTargetRva -> { code.branch(rx+FastbootRecoveryModule.AUTH_OFFSET,true);displacedWrappedCalls+=pc }
                    facts.continueFlow.bootLinuxTargetRva -> { code.branch(rx+FastbootRecoveryModule.BOOTLINUX_OFFSET,true);displacedWrappedCalls+=pc }
                    else -> code.replay(pc,original)
                }
                code.branch(pc+4)
            }

            // AddressOfEntryPoint and its BTI/PAC landing stay at their original RVA.
            val prefix = facts.entryPrefix
            code.align();val thunk=code.pc
            prefix.replayWords.forEachIndexed { n,w -> code.replay(image.entryRva+n*4L,w) }
            code.branch(prefix.resumeRva)
            // The stable module slot only redirects. Relocated native prefixes
            // grow in the dynamically sized RX mapping, not a fixed-size buffer.
            code.overwrite(FastbootRecoveryModule.ORIGINAL_ENTRY_OFFSET.toInt(),
                words(A64.branch(rx+FastbootRecoveryModule.ORIGINAL_ENTRY_OFFSET,thunk)))
            word("EFISP 返回边界隔离", prefix.hookRva, A64.branch(prefix.hookRva, rx + FastbootRecoveryModule.ENTRY_OFFSET))

            key.edits.forEach { edit(it.name, it.rva, it.replacement) }

            val checkpoint = facts.prerequisites.checkpointRva
            hook("阻断引导后的原生 Fastboot 入口", checkpoint) {
                save(); branch(rx + FastbootRecoveryModule.FORCED_OFFSET, true)
                val normal = placeholder()
                restore()
                facts.prerequisites.materializableGlobalRegisters.toSortedMap().forEach { (reg, rva) -> address(reg, rva) }
                facts.prerequisites.materializableConstants.toSortedMap().forEach { (reg, value) -> immediate(reg, value) }
                branch(facts.prerequisites.recoveryEntryRva)
                val fallback = pc; replace(normal, cbz(normal, fallback, 0))
                restore(); replay(checkpoint)
            }
            facts.prerequisites.preparationRoute?.let { route ->
                if(route.entryRva!=facts.prerequisites.recoveryEntryRva ||
                    image.word(route.successGateRva)!=route.originalSuccessWord ||
                    route.statusRegister !in 0..30 || route.originalSuccessWord and 31!=route.statusRegister ||
                    route.originalSuccessWord and 0x7e000000!=0x34000000)
                    fail("原生 Fastboot preparation 成功边界")
                hook("原生 Fastboot 协议准备成功后恢复", route.successGateRva) {
                    save(); branch(rx+FastbootRecoveryModule.FORCED_OFFSET,true)
                    val ordinary=placeholder()
                    if(route.originalSuccessWord<0)scalar(true,9,route.statusRegister*8)
                    else emit(0xb9400000.toInt() or ((route.statusRegister*2) shl 10) or (31 shl 5) or 9)
                    val failed=placeholder()
                    restore()
                    route.afterPreparationGlobalRegisters.toSortedMap().forEach { (reg,rva) -> address(reg,rva) }
                    route.afterPreparationConstants.toSortedMap().forEach { (reg,value) -> immediate(reg,value) }
                    branch(facts.fastbootSetupRva)
                    val native=pc
                    replace(ordinary,cbz(ordinary,native,0))
                    replace(failed,cbz(failed,native,9,true))
                    restore();replay(route.successGateRva)
                }
            }
            hook("进入原生 Fastboot 后结束启动故障作用域", facts.fastbootSetupRva) {
                save()
                address(9,rw+FastbootRecoveryModule.STATE_BOOT_SCOPE_OFFSET)
                emit(0xf900013f.toInt()) // str xzr,[x9]; no helper call before native launch marker.
                restore()
                scopePrefix.originalWords.forEachIndexed { n,w -> this.replay(scopePrefix.startRva+n*4L,w) }
                // The real logger BL/RET has completed. Restore the native
                // observable LR before resuming its original instruction stream.
                address(30,scopePrefix.resumeRva)
                branch(scopePrefix.resumeRva)
            }
            for(n in 1 until scopePrefix.originalWords.size)
                word("原生 Fastboot scope 参数前缀重定位",scopePrefix.startRva+n*4L,0xd503201f.toInt())
            // A blocked shared GetActiveSlot can naturally return an error and
            // mark recovery after the early checkpoint. The native key reader
            // may overwrite its request byte. This late selector uses the full
            // original native frame and preserves the complete normal branch.
            hook("原生模式选择保留已请求的故障恢复", facts.normalBootRva) {
                save(); branch(rx + FastbootRecoveryModule.FORCED_OFFSET, true)
                val normal = placeholder()
                restore(); branch(facts.fastbootGateRva)
                val native = pc; replace(normal, cbz(normal,native,0))
                restore(); replay(facts.normalBootRva)
            }
            // Try native Fastboot even if CardInfo reports flashless/no support.
            // A failed native initialization is caught by the entry wrapper.
            word("CardInfo 错误仍尝试原生 Fastboot", facts.fastbootGateRva, 0xd503201f.toInt())
            facts.fatalSpinRvas.forEach { pc -> hook("CardInfo fatal 自旋恢复", pc) { request(4, pc) } }

            slots?.automaticCallSites.orEmpty().forEach { site ->
                if (!site.hookBeforeNativeEntry || !site.requiresRecoveryRequest || image.word(site.callRva) != site.originalWord)
                    fail("自动切槽原生变更边界")
                if(site.requiresDeferredCallback) {
                    val resume=site.callbackNaturalResumeRva
                    if(resume!=site.callRva+4 || site.callbackResultDeadRva==null ||
                        site.callbackEntryRva==null || site.callbackRegistrationRva==null ||
                        site.callbackDispatchCallRva==null || site.callbackFunctionPointerRvas.isEmpty() ||
                        site.safeWaitResumeRvas.isEmpty() || site.safeWaitResumeRvas.any { p ->
                            failures.safeWaitResumes.none { it.hookRva==p && it.cleanupRva!=0L && it.cleanupEvidence.isNotEmpty() }
                        }) fail("自动切槽 callback 的结果失活、返回及同步事件清理证据")
                    hook("自动切槽回调延迟转 EFISP Fastboot：${site.kind}",site.callRva) {
                        save();immediate(0,2);immediate(1,Long.MIN_VALUE or 21L);address(2,site.callRva)
                        branch(rx+FastbootRecoveryModule.DEFER_OFFSET,true)
                        restore();branch(requireNotNull(resume))
                    }
                } else hook("自动切槽转 EFISP Fastboot：${site.kind}", site.callRva) { request(2, site.callRva) }
            }

            // Manual set_active retains all native shared getters/commits. The
            // existing relocated VA slot remains in the original PE relocation table.
            if (slots != null) {
            var manualEntries = 0
            for (pc in slots.commandTableStartRva until slots.commandTableEndRva step 16) {
                val at = image.rvaToOffset(pc, 16) ?: fail("set_active 原命令表")
                val nameValue = image.bytes.u64(at)
                val nameRva = listOf(nameValue - image.imageBase, nameValue).distinct().firstOrNull { image.ascii(it) == "set_active" }
                if (nameRva == null) continue
                val native = image.bytes.u64(at + 8)
                val relative = native == slots.manualSetActiveEntryRva
                if (!relative && native != image.imageBase + slots.manualSetActiveEntryRva) fail("set_active handler VA")
                edit("保留手动 set_active 作用域", pc + 8,
                    qword(rx + FastbootRecoveryModule.SET_ACTIVE_OFFSET + if (relative) 0 else image.imageBase))
                ++manualEntries
            }
            if (manualEntries != 1) fail("set_active 命令记录不唯一")
            slots.defaultInitializationSites.forEach { site ->
                if (!site.clearOutputWhenBlocked || site.outputRegisterAtDecision !in 0..30 || site.outputBytes <= 0)
                    fail("默认切槽输出缓冲区关系")
                hook("无 active 槽时保留人工选择", site.decisionRva) {
                    save(); branch(rx + FastbootRecoveryModule.MANUAL_ALLOWED_OFFSET, true)
                    val allowed = placeholder()
                    immediate(0, 6); immediate(1, site.nativeErrorValue); address(2, site.decisionRva)
                    branch(rx + FastbootRecoveryModule.MARK_ONLY_OFFSET, true)
                    scalar(true, 9, site.outputRegisterAtDecision * 8)
                    // Exact proven suffix capacity; never clear an assumed struct.
                    immediate(10, site.outputBytes.toLong())
                    val clear = pc
                    emit(0x3800153f) // strb wzr,[x9],#1
                    emit(0xf100054a.toInt()) // subs x10,x10,#1
                    val again = pc
                    emit(0x54000001 or ((((clear-again) shr 2).toInt() and 0x7ffff) shl 5))
                    restore(); branch(site.nativeErrorBranchRva)
                    val manual = pc; replace(allowed, cbz(allowed, manual, 0, true))
                    restore(); replay(site.decisionRva)
                }
            }
            }

            // Native auth success and benign reports return unchanged. A manual
            // continue failure still sends its original FAIL and stays Fastboot.
            val authCalls = image.instructions().filter { (pc,w) -> A64.blTarget(pc,w) == facts.continueFlow.authTargetRva }.toList()
            if (authCalls.none { it.first == facts.continueFlow.authCallRva }) fail("原生 Continue auth 调用")
            authCalls.filter { it.first !in displacedWrappedCalls }.forEach { (pc,_) -> word("原生引导认证调用边界", pc,
                A64.branch(pc, rx + FastbootRecoveryModule.AUTH_OFFSET) or 0x80000000.toInt()) }
            val linuxCalls = image.instructions().filter { (pc,w) -> A64.blTarget(pc,w) == facts.continueFlow.bootLinuxTargetRva }.toList()
            if (linuxCalls.none { it.first == facts.continueFlow.bootLinuxCallRva }) fail("原生 BootLinux 调用")
            linuxCalls.filter { it.first !in displacedWrappedCalls }.forEach { (pc,_) -> word("内核交接前清除故障计数", pc,
                A64.branch(pc, rx + FastbootRecoveryModule.BOOTLINUX_OFFSET) or 0x80000000.toInt()) }

            failures.sites.distinctBy { it.existingPolicyStubRva ?: it.hookRva }.forEach { site ->
                if (!site.knownManualExclusion) fail("故障分支与手动命令区分")
                if (site.executionContext == AutoBootFailureFacts.ExecutionContext.CALLBACK) {
                    if (!site.callbackReturnAbiProven) fail("UEFI callback 尾部栈/寄存器恢复")
                    hook("启动警告致命回调延迟恢复", site.hookRva) {
                        save(); branch(rx + FastbootRecoveryModule.BOOT_SCOPE_OFFSET, true)
                        val manual = placeholder()
                        immediate(0, 3); immediate(1, Long.MIN_VALUE or 21L); address(2, site.hookRva)
                        branch(rx + FastbootRecoveryModule.DEFER_OFFSET, true)
                        restore(); emit(0xd65f03c0.toInt())
                        val native = pc; replace(manual, cbz(manual, native, 0))
                        restore(); replay(site.hookRva)
                    }
                } else {
                    // The existing accepted hash-error path must still RET.
                    // Intercept only its proven fatal tail, after that decision.
                    val pc = site.existingPolicyStubRva?.let { stub ->
                        (stub until stub + 32 step 4).singleOrNull { p -> image.word(p)?.let { A64.bTarget(p,it) == site.targetCallee } == true }
                            ?: fail("原有可继续引导策略的 fatal 尾部")
                    } ?: site.hookRva
                    hook("阻断启动的原生故障转 Fastboot：${site.kind}", pc) { request(3, pc) }
                }
            }
            failures.safeWaitResumes.distinctBy { it.hookRva }.forEach { poll ->
                if (image.word(poll.hookRva) != poll.originalWord) fail("回调后同步恢复检查点")
                hook("UEFI 回调返回后的同步故障检查", poll.hookRva) {
                    save();branch(rx+FastbootRecoveryModule.PENDING_VALUE_OFFSET,true)
                    val ordinary=placeholder()
                    if(poll.cleanupRva!=0L) {
                        if(poll.cleanupEvidence.isEmpty() || !image.isCode(poll.cleanupRva) || !image.isWritable(poll.eventSlotRva,8))
                            fail("同步恢复的原生事件清理关系")
                        address(9,poll.eventSlotRva);emit(0xf9400120.toInt()) // ldr x0,[x9]
                        val closed=placeholder()
                        branch(poll.cleanupRva,true)
                        val done=pc;replace(closed,cbz(closed,done,0))
                    }
                    // A live callback has returned to its dispatcher before
                    // this point. Close its still-active timer before unwinding.
                    branch(rx+FastbootRecoveryModule.PENDING_OFFSET,true)
                    val native=pc;replace(ordinary,cbz(ordinary,native,0))
                    restore();replay(poll.hookRva)
                }
            }

            val config = FastbootRecoveryModule.CONFIG_OFFSET.toInt()
            listOf(rx, rw, facts.entryRva, facts.requestFlagRva ?: 0L, facts.continueFlow.flagRva,
                facts.continueFlow.bootLinuxTargetRva, slots?.manualSetActiveEntryRva ?: 0L, facts.canaryGlobalRva,
                3L, 100_000L, FastbootRecoveryModule.STATE_BYTES.toLong(), 1L, facts.canaryWidth.toLong(),
                facts.continueFlow.authTargetRva,eventCleanup?.first?:0L,eventCleanup?.second?:0L)
                .forEachIndexed { n,v -> code.overwrite(config+n*8, qword(v)) }
            return code.bytes() to changes
        }

        // Predicting the appended RVA uses the same PE section alignment rules.
        val pe = image.bytes.u32(0x3c).asBoundedInt("PE header")
        val sectionAlign = maxOf(4096L, image.bytes.u32(pe + 24 + 32))
        val rxRva = align(maxOf(image.bytes.u32(pe + 24 + 56), image.sections.maxOf {
            it.rva + maxOf(it.virtualSize, it.rawSize.toLong()) }), sectionAlign)
        val (dryCode, dryChanges) = generate(rxRva, 0L)
        val edits = dryChanges.map { change ->
            val raw = requireNotNull(image.rvaToOffset(change.rva, change.bytes.size))
            FastbootRecoverySections.Edit(raw, image.bytes.copyOfRange(raw,raw+change.bytes.size), change.bytes)
        }
        val receiptSize = FastbootRecoverySections.receiptBytes(image, edits).size
        val needed = dryCode.size.toLong() + receiptSize + FastbootRecoverySections.FOOTER_BYTES
        if (needed > Int.MAX_VALUE) fail("恢复映射实际长度")
        val layout = FastbootRecoverySections.append(image, needed.toInt(), FastbootRecoveryModule.STATE_BYTES)
        check(layout.rx.rva == rxRva)
        val (finalCode, finalChanges) = generate(layout.rx.rva, layout.rw.rva)
        check(finalCode.size == dryCode.size && finalChanges.size == dryChanges.size)
        finalCode.copyInto(layout.output, layout.rx.raw)
        finalChanges.forEach { it.bytes.copyInto(layout.output, requireNotNull(image.rvaToOffset(it.rva,it.bytes.size))) }
        FastbootRecoverySections.writeReceipt(layout, image, edits, finalCode.size)
        val result = PeImage.parse(layout.output)
        check(result.entryRva == image.entryRva && result.sections.take(image.sections.size) == image.sections)
        check(FastbootRecoverySections.remove(layout.output).bytes.contentEquals(image.bytes))
        return Result(layout.output, finalChanges.map { c ->
            val at = requireNotNull(image.rvaToOffset(c.rva,c.bytes.size))
            PatchMatch(c.name,c.rva,at,image.bytes.copyOfRange(at,at+c.bytes.size).hex(),c.bytes.hex(),false)
        }, key.notes + failures.notes + listOf(
            "EFISP 保留正常系统引导及非阻断警告；已证明的返回父 ABL、自动切槽和阻断故障转入本镜像原生 Fastboot。",
            "按实际定位结果应用音量下资格策略；未完整匹配的可选项保留原生流程，手动命令保留。",
            "故障恢复使用独立 RX/RW 小模块及原始入口栈；KBL 仍由显式命令启动。",
            "连续阻断故障达到 3 次优先进入 Fastboot；私有 EFI 计数服务不可用时保留 RAM 恢复。",
            "已退出 UEFI Boot Services 后的内核异常不在本恢复模块可直接接管的范围内。",
        ), linkedMapOf("rxRva" to layout.rx.rva,"stateRva" to layout.rw.rva,"moduleSha256" to FastbootRecoveryModule.SHA256,
            "checkpointRva" to checkpointMetadata(facts),"hookCount" to finalChanges.size,
            "autoSlotSites" to slots?.automaticCallSites.orEmpty().map { it.callRva },
            "defaultSlotSites" to slots?.defaultInitializationSites.orEmpty().map { it.decisionRva },
            "authCalls" to finalChanges.filter { it.name=="原生引导认证调用边界" }.map { it.rva },
            "normalRetryAccountingPreserved" to true,"manualCommandsPreserved" to true))
    }
    private fun checkpointMetadata(facts: FastbootRecoveryFacts.Facts) = facts.prerequisites.checkpointRva
    private fun words(vararg w: Int) = ByteArray(w.size*4).also { b -> w.forEachIndexed { i,v -> b.putWord(i*4,v) } }
    private fun qword(v: Long) = words(v.toInt(),(v ushr 32).toInt())
    private fun align(v: Long, a: Long) = (v+a-1)/a*a
    private fun fail(s: String): Nothing = throw PatchException("Fastboot recovery：$s；原始输入保持不变。")

    /** GPR/NZCV preserving in-function gates. Helpers are GPR-only. */
    internal class Code(private val start: Long, prefix: ByteArray = byteArrayOf()) {
        private val data = ArrayList<Int>().also { a ->
            require(prefix.size%4==0); for (p in prefix.indices step 4) a += prefix.word(p)
        }
        val pc get() = start + data.size*4L
        val size get() = data.size*4
        fun emit(w: Int) { data += w }
        fun placeholder(): Long = pc.also { emit(0) }
        fun replace(at: Long, word: Int) { data[((at-start)/4).toInt()] = word }
        fun overwrite(offset: Int, bytes: ByteArray) {
            require(offset%4==0 && bytes.size%4==0 && offset.toLong()+bytes.size<=size)
            for (n in bytes.indices step 4) data[(offset+n)/4]=bytes.word(n)
        }
        fun align() { while (size%16!=0) emit(0xd503201f.toInt()) }
        fun branch(target: Long, link: Boolean=false) = emit(A64.branch(pc,target) or if(link)0x80000000.toInt() else 0)
        fun immediate(reg: Int, value: Long) {
            require(reg in 0..30)
            emit(0xd2800000.toInt() or ((value.toInt() and 65535) shl 5) or reg)
            for (n in 1..3) emit(0xf2800000.toInt() or (n shl 21) or (((value ushr (n*16)).toInt() and 65535) shl 5) or reg)
        }
        fun address(reg: Int, rva: Long) {
            val delta = ((rva and -4096L) - (pc and -4096L)) shr 12
            if (delta !in -0x100000L..0xfffffL) fail("ADRP 到目标的实际范围")
            emit(0x90000000.toInt() or ((delta.toInt() and 3) shl 29) or (((delta shr 2).toInt() and 0x7ffff) shl 5) or reg)
            emit(0x91000000.toInt() or ((rva.toInt() and 4095) shl 10) or (reg shl 5) or reg)
        }
        fun scalar(load: Boolean, reg: Int, off: Int) = emit((if(load)0xf9400000.toInt() else 0xf9000000.toInt()) or ((off/8) shl 10) or (31 shl 5) or reg)
        private fun pair(load: Boolean, a: Int, b: Int, off: Int) = emit((if(load)0xa9400000.toInt() else 0xa9000000.toInt()) or ((off/8) shl 15) or (b shl 10) or (31 shl 5) or a)
        fun save() {
            emit(0xd10443ff.toInt()) // sub sp,sp,#272
            for(r in 0..28 step 2) pair(false,r,r+1,r*8)
            scalar(false,30,240); emit(0xd53b4209.toInt()); scalar(false,9,248)
        }
        fun restore() {
            scalar(true,9,248); emit(0xd51b4209.toInt())
            for(r in 0..28 step 2) pair(true,r,r+1,r*8)
            scalar(true,30,240); emit(0x910443ff.toInt())
        }
        fun cbz(from: Long, to: Long, reg: Int, nonzero: Boolean=false): Int {
            val delta=to-from
            if(delta%4!=0L || delta !in -0x100000L..0xffffcL) fail("条件分支的实际范围")
            return 0xb4000000.toInt() or (if(nonzero)0x01000000 else 0) or (((delta shr 2).toInt() and 0x7ffff) shl 5) or reg
        }
        /** Relocate semantics, rather than copying a PC-relative instruction. */
        fun replay(originalPc: Long, word: Int) {
            A64.adr(originalPc,word)?.let { (r,target) -> address(r,target); return }
            A64.adrp(originalPc,word)?.let { (r,target) -> address(r,target); return }
            A64.bTarget(originalPc,word)?.let { branch(it); return }
            A64.blTarget(originalPc,word)?.let { branch(it,true); return }
            val bits=when {
                word and 0xff000010.toInt()==0x54000000 || word and 0x7e000000==0x34000000 -> 19
                word and 0x7e000000==0x36000000 -> 14
                else -> 0
            }
            if(bits!=0) {
                val mask=(1 shl bits)-1
                val delta=((((word ushr 5) and mask).toLong() shl (64-bits)) shr (64-bits)) shl 2
                // Use a local conditional and two B instructions; native target
                // may be beyond the short conditional range of appended code.
                emit((word and (mask shl 5).inv()) or (2 shl 5))
                branch(originalPc+4); branch(originalPc+delta)
                return
            }
            if(word and 0x3b000000==0x18000000) {
                val immediate=(((word ushr 5) and 0x7ffff).toLong() shl 45) shr 45
                val target=originalPc+(immediate shl 2)
                val reg=word and 31;val vector=word and 0x04000000!=0;val kind=word ushr 30
                if(vector && kind==3)fail("未定义的 SIMD literal opcode")
                val op=if(vector)listOf(0xbd400000.toInt(),0xfd400000.toInt(),0x3dc00000)[kind]
                    else listOf(0xb9400000.toInt(),0xf9400000.toInt(),0xb9800000.toInt(),0xf9800000.toInt())[kind]
                if(!vector && kind<3 && reg!=31) {
                    address(reg,target);emit(op or (reg shl 5) or reg)
                } else {
                    // SIMD, prefetch and zero-register loads have no disposable
                    // GPR. Preserve a real scratch register without changing NZCV.
                    emit(0xd10043ff.toInt());scalar(false,17,0)
                    address(17,target);emit(op or (17 shl 5) or reg)
                    scalar(true,17,0);emit(0x910043ff.toInt())
                }
                return
            }
            emit(word)
        }
        fun bytes() = ByteArray(size).also { b -> data.forEachIndexed { n,w -> b.putWord(n*4,w) } }
    }
}
