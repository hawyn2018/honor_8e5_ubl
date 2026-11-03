package net.hawyn.ablefisp.patch

/**
 * Native automatic-slot mutation boundaries, separate from command dispatch.
 *
 * This is analysis only: it emits neither a branch nor an error return. In
 * particular a nested caller must not jump to main with its own live frame.
 * The caller of this analyzer supplies a frame-safe recovery-request thunk.
 * Reimporters must restore their own recorded call edits to a virtual native
 * view before analysis; an unrecognized replacement is never guessed through.
 */
internal object FastbootSlotRecoveryFacts {
    enum class Kind {
        MAIN_ROLLBACK_SET_ACTIVE,
        BOOT_DETECTOR_SET_ACTIVE,
        HANDLE_ACTIVE_UNBOOTABLE,
    }

    data class Mutation(
        val storeRva: Long,
        val pairedReadRva: Long,
        val commitCallRva: Long,
        val commitRva: Long,
    )

    data class Site(
        val kind: Kind,
        val ownerStartRva: Long,
        val ownerEndRva: Long,
        val callRva: Long,
        val originalWord: Int,
        val nativeEntryRva: Long,
        val nativeMutationEvidence: List<Mutation>,
        /** Native zero-result branch, discovered from its actual CBZ/CBNZ. */
        val successGateRva: Long? = null,
        val nativeSuccessValue: Long? = null,
        /** Main: after the automatic ResetSystem; detector: its success join. */
        val naturalSuccessJoinRva: Long? = null,
        val automaticResetCallRva: Long? = null,
        val resetSystemFieldLoadRva: Long? = null,
        /** Excludes the native callee entirely, including nested getter writes. */
        val hookBeforeNativeEntry: Boolean = true,
        /** Main's automatic SetActive path resets even after a failed return. */
        val requiresRecoveryRequest: Boolean = true,
        /** A UEFI notification must return to its dispatcher before recovery. */
        val requiresDeferredCallback: Boolean = false,
        val callbackEntryRva: Long? = null,
        val callbackRegistrationRva: Long? = null,
        val callbackDispatchCallRva: Long? = null,
        val callbackTableRva: Long? = null,
        val callbackTableStride: Long? = null,
        val callbackTableColumn: Long? = null,
        val callbackFunctionPointerRvas: List<Long> = emptyList(),
        val callbackNaturalResumeRva: Long? = null,
        val callbackResultDeadRva: Long? = null,
        val safeWaitResumeRvas: List<Long> = emptyList(),
    ) {
        val originalTarget: Long get() = nativeEntryRva
        val firstMutationRva: Long get() = nativeMutationEvidence.minOf { it.storeRva }
    }

    /** Observations only. Healthy boot-attempt accounting is not an auto switch. */
    data class Window(
        val ownerStartRva: Long,
        val referenceRva: Long,
        val mutation: Mutation,
        val preserveNormalAttemptAccounting: Boolean = true,
    )

    data class StatusDefinition(val rva: Long, val originalWord: Int)

    /**
     * The native no-active -> default-slot repair is shared with manual
     * set_active. A runtime allowManualSlot guard belongs at decisionRva.
     * When the default leg is blocked, clear only the proved output capacity
     * and follow nativeErrorBranchRva. That path establishes the original EFI
     * error and uses its own epilogue; no deep restart or invented status.
     */
    data class DefaultInitializationSite(
        val functionStartRva: Long,
        val functionEndRva: Long,
        val manualHandlerRva: Long,
        val manualSharedQueryCallRva: Long,
        val decisionRva: Long,
        val decisionOriginalWord: Int,
        val defaultBranchTaken: Boolean,
        val nativeDefaultBranchRva: Long,
        val firstMutationRva: Long,
        val nativeCommitCallRva: Long,
        val nativeCommitRva: Long,
        val nativeErrorBranchRva: Long,
        val nativeErrorEpilogueRva: Long,
        val nativeErrorReturnRegister: Int,
        val nativeErrorValue: Long,
        val nativeErrorDefinitions: List<StatusDefinition>,
        val outputArgument: Int,
        val outputRegisterAtDecision: Int,
        val outputBytes: Int,
        val nativeOutputCopyCallRva: Long,
        val clearOutputWhenBlocked: Boolean = true,
    )

    data class Plan(
        val manualSetActiveEntryRva: Long,
        val commandTableStartRva: Long,
        val commandTableEndRva: Long,
        val sharedSetActiveRva: Long?,
        val manualBooleanArgument: Int?,
        val manualSetActiveCalls: List<Long>,
        val unbootableHandlerRva: Long?,
        val automaticCallSites: List<Site>,
        val retryDecrementWindows: List<Window>,
        val evidence: Map<String, Long>,
        val defaultInitializationSites: List<DefaultInitializationSite> = emptyList(),
    ) {
        val enabled: Boolean get() = automaticCallSites.isNotEmpty()
    }

    private data class Function(val start: Long, val end: Long)
    private data class Manual(val handler: Long, val tableStart: Long, val tableEnd: Long)
    private data class Call(val pc: Long, val word: Int, val target: Long, val owner: Function)
    private data class Shared(val function: Function, val argument: Int, val manualCalls: List<Long>)

    private class Index(val image: PeImage) {
        val code = image.instructions().toList()
        private val starts = code.filter { (_, word) -> isEntry(word) }.map { it.first }.sorted()
        private val bodies = hashMapOf<Function, List<Pair<Long, Int>>>()
        private val flows = hashMapOf<Function, A64ValueOrigins>()
        private val nativeReferences by lazy { A64.references(image) }
        private val referenceIndex by lazy { nativeReferences.groupBy { function(it.pc) } }
        fun function(pc: Long): Function? {
            val found = starts.binarySearch(pc)
            val position = if (found >= 0) found else -found - 2
            if (position < 0) return null
            val start = starts[position]
            val section = image.sections.firstOrNull { it.executable && start >= it.rva && start < it.rva + it.rawSize } ?: return null
            val end = minOf(starts.getOrNull(position + 1) ?: Long.MAX_VALUE, section.rva + section.rawSize)
            return if (pc in start..<end) Function(start, end) else null
        }
        fun body(function: Function) = bodies.getOrPut(function) { code.filter { it.first in function.start..<function.end } }
        fun flow(function: Function) = flows.getOrPut(function) { A64ValueOrigins(image, function.start, function.end) }
        fun texts(function: Function): List<Pair<Long, String>> = referenceIndex[function].orEmpty().mapNotNull {
            image.ascii(it.address, 512)?.let { text -> it.pc to text }
        }
        fun references(function: Function) = referenceIndex[function].orEmpty()
        fun addressReferences(target: Long) = nativeReferences.filter { it.address == target }
        fun has(function: Function, text: String) = texts(function).any { it.second.contains(text, ignoreCase = true) }
        val calls: List<Call> by lazy {
            code.mapNotNull { (pc, word) ->
                A64.blTarget(pc, word)?.let { target -> function(pc)?.let { Call(pc, word, target, it) } }
            }
        }
    }

    fun analyze(image: PeImage): Plan {
        val manual = manualCommand(image)
        val index = Index(image)
        val manualFunction = index.function(manual.handler)
            ?.takeIf { it.start == manual.handler } ?: fail("manual set_active 函数入口")
        val manualCandidates = index.calls.filter { it.owner == manualFunction }.mapNotNull { call ->
            val function = index.function(call.target)?.takeIf { it.start == call.target } ?: return@mapNotNull null
            if (!index.has(function, "SetActiveSlot:")) return@mapNotNull null
            val automatic = index.calls.filter { it.target == function.start && it.owner != manualFunction }
            val flags = (1..7).filter { argument ->
                constant(index, call, argument) == 1L &&
                    automatic.any { constant(index, it, argument) == 0L } && consumesEntryFlag(index, function, argument)
            }
            flags.singleOrNull()?.let { Shared(function, it, listOf(call.pc)) }
        }.groupBy { it.function to it.argument }.map { (key, values) ->
            Shared(key.first, key.second, values.flatMap { it.manualCalls }.distinct().sorted())
        }
        if (manualCandidates.isEmpty()) {
            val slotMarkers = index.code.mapNotNull { index.function(it.first) }.distinct().any {
                index.has(it, "HandleActiveSlotUnbootable:") || index.has(it, "rollback enable, change active slot")
            }
            if (slotMarkers) fail("存在自动槽位逻辑，但 manual/automatic SetActive 数据流不完整")
            return Plan(manual.handler, manual.tableStart, manual.tableEnd, null, null,
                emptyList(), null, emptyList(), emptyList(), mapOf("automaticSites" to 0L))
        }
        val shared = manualCandidates.singleOrNull() ?: fail("SetActive 共享入口或布尔参数歧义")
        val sharedMutations = mutations(index, shared.function)
        if (sharedMutations.size < 2) fail("SetActive 双槽属性存储与提交关系")
        val commit = sharedMutations.map { it.commitRva }.distinct().singleOrNull() ?: fail("SetActive 提交入口歧义")

        val handlers = index.calls.filter { it.target == shared.function.start && it.owner != manualFunction }
            .map { it.owner }.distinct().filter { index.has(it, "HandleActiveSlotUnbootable:") }
        val handler = handlers.singleOrNull() ?: fail("自动 unbootable 处理函数")
        val handlerMutations = mutations(index, handler).filter { it.commitRva == commit }
        // Some native versions only mark/commit the active slot. Others also
        // clear an alternate slot before calling the same SetActive(false).
        // The optional second store is not the identity of this transaction.
        // Prove the actual mark -> commit -> false call -> ResetSystem chain.
        val handlerRelations = index.calls.filter {
            it.owner == handler && it.target == shared.function.start && constant(index, it, shared.argument) == 0L
        }.mapNotNull { automatic ->
            val marks = handlerMutations.filter { mutation ->
                mutation.commitCallRva < automatic.pc
            }
            if (marks.isEmpty()) return@mapNotNull null
            val resets = index.body(handler).mapNotNull reset@{ (pc, word) ->
                if (pc <= automatic.pc || word and 0xfffffc1f.toInt() != 0xd63f0000.toInt()) return@reset null
                val field = resetFieldProducer(index, handler, automatic.pc + 4, pc, (word ushr 5) and 31)
                    ?: return@reset null
                pc to field
            }
            resets.singleOrNull()?.let { reset -> Triple(automatic.pc, reset.first, marks.size.toLong()) }
        }
        val handlerRelation = handlerRelations.singleOrNull()
        if (handlerMutations.isEmpty() || !index.has(handler, "Rebooting") || handlerRelation == null)
            fail("unbootable 标记、对槽恢复、SetActive(false) 与重启关系")

        val sites = ArrayList<Site>()
        for (call in index.calls.filter { it.target == shared.function.start }) {
            if (call.owner == manualFunction) {
                if (call.pc !in shared.manualCalls || constant(index, call, shared.argument) != 1L)
                    fail("manual set_active 参数变化")
                continue
            }
            if (call.owner == handler) continue // Its callers are intercepted BEFORE its first write.
            if (constant(index, call, shared.argument) != 0L) fail("未分类 SetActive 调用参数")
            val kind = when {
                index.has(call.owner, "rollback enable, change active slot") && index.has(call.owner, "LinuxLoaderEntry") ->
                    Kind.MAIN_ROLLBACK_SET_ACTIVE
                index.has(call.owner, "BootDetector") && index.has(call.owner, "change active slot") &&
                    index.has(call.owner, "current active slot") -> Kind.BOOT_DETECTOR_SET_ACTIVE
                else -> fail("未证明为自动失败恢复的 SetActive 调用")
            }
            sites += site(index, call, kind, sharedMutations)
        }
        val handlerCalls = index.calls.filter { it.target == handler.start }
        if (handlerCalls.isEmpty()) fail("unbootable 处理的调用者")
        for (call in handlerCalls) {
            if (call.owner == manualFunction || call.owner == shared.function)
                fail("unbootable 入口与 manual SetActive 共用，需额外上下文证明")
            sites += site(index, call, Kind.HANDLE_ACTIVE_UNBOOTABLE, handlerMutations)
        }
        if (sites.none { it.kind == Kind.MAIN_ROLLBACK_SET_ACTIVE }) fail("main 自动切槽入口")
        if (sites.none { it.kind == Kind.BOOT_DETECTOR_SET_ACTIVE }) fail("BootDetector 自动切槽入口")
        val manualRange = manualFunction.start..<manualFunction.end
        if (sites.any { it.callRva in manualRange || it.ownerStartRva == shared.function.start })
            fail("恢复点侵入 manual 或共用 SetActive 函数")

        val retry = index.code.mapNotNull { index.function(it.first) }.distinct().flatMap { function ->
            val references = index.texts(function).filter { it.second.contains("Reduce Active Slot time", ignoreCase = true) }
            if (references.isEmpty()) return@flatMap emptyList()
            val changes = mutations(index, function).filter { it.commitRva == commit }
            references.map { reference ->
                val candidate = changes.filter { it.storeRva in reference.first + 4..reference.first + 256 }.minByOrNull { it.storeRva }
                    ?: fail("正常 attempt 递减属性/提交窗口")
                Window(function.start, reference.first, candidate)
            }
        }
        val classified = classifyCallbacks(index, sites)
        val defaults = defaultInitialization(index, manual.handler, shared.function, commit)
        return Plan(manual.handler, manual.tableStart, manual.tableEnd, shared.function.start, shared.argument,
            shared.manualCalls, handler.start, classified.distinctBy { it.callRva }.sortedBy { it.callRva }, retry,
            linkedMapOf("manualSetActive" to manual.handler, "sharedSetActive" to shared.function.start,
                "manualTrueArgument" to shared.argument.toLong(), "nativeCommit" to commit,
                "unbootableHandler" to handler.start, "handlerAutomaticCallers" to handlerCalls.size.toLong(),
                "handlerMarkMutations" to handlerRelation.third,
                "handlerSetActiveCall" to handlerRelation.first, "handlerResetCall" to handlerRelation.second,
                "automaticSites" to sites.size.toLong(), "normalAttemptWindowsPreserved" to retry.size.toLong(),
                "deferredCallbackSites" to classified.count { it.requiresDeferredCallback }.toLong(),
                "defaultInitializationSites" to defaults.size.toLong()), defaults)
    }

    private data class TableAddress(val base: Long, val stride: Long, val column: Long)

    /** Address-taken UI handlers can run below a real UEFI notification frame.
     * Resolve their native indexed function-table dispatch, rather than assuming
     * that every direct BL to HandleActiveSlotUnbootable is synchronous.
     */
    private fun classifyCallbacks(index: Index, sites: List<Site>): List<Site> {
        val owners = sites.map { it.ownerStartRva }.toSet()
        // An inlined Handle call can live directly in a callback registered
        // through code-built ADR(P) arguments, with no stored table pointer.
        // Prove the actual CreateEvent notify-function parameter; generic
        // self-address logs and ordinary state-table references are irrelevant.
        val registrations = directUefiRegistrations(index, owners)
        if (registrations.isNotEmpty()) fail("CreateEvent 直接注册 callback 内的自动切槽尚缺原生返回与同步清理证明")
        val pointerIndex = hashMapOf<Long, MutableList<Long>>()
        for (section in index.image.sections.filter { !it.executable }) {
            for (pc in section.rva until section.rva + section.rawSize - 7 step 8) {
                val at = index.image.rvaToOffset(pc, 8) ?: continue
                val value = index.image.bytes.u64(at)
                val owner = if (value in owners) value else (value - index.image.imageBase).takeIf { it in owners }
                if (owner != null) pointerIndex.getOrPut(owner) { arrayListOf() }.add(pc)
            }
        }
        val addressTaken = sites.associateWith { pointerIndex[it.ownerStartRva].orEmpty() }.filterValues { it.isNotEmpty() }
        if (addressTaken.isEmpty()) return sites
        val failures = AutoBootFailureFacts.analyze(index.image)
        val callbacks = failures.sites.filter { it.executionContext == AutoBootFailureFacts.ExecutionContext.CALLBACK }
            .distinctBy { it.ownerStart }
        return sites.map { site ->
            val stored = addressTaken[site] ?: return@map site
            val matches = callbacks.flatMap { callback ->
                val function = index.function(callback.ownerStart)?.takeIf { it.start == callback.ownerStart }
                    ?: return@flatMap emptyList()
                val body = index.body(function)
                body.filter { (_, word) -> word and 0xfffffc1f.toInt() == 0xd63f0000.toInt() }.flatMap { (dispatch, word) ->
                    val targetRegister = (word ushr 5) and 31
                    body.filter { (pc, raw) ->
                        pc < dispatch && raw and 0xffc00000.toInt() == 0xf9400000.toInt() ||
                            pc < dispatch && raw and 0xffe00c00.toInt() == 0xf8600800.toInt()
                    }.mapNotNull { (load, raw) ->
                        val produced = raw and 31
                        val flow = A64ValueOrigins(index.image, load, dispatch + 4)
                        if (flow.origins(dispatch, targetRegister)?.contains(A64ValueOrigin.Definition(load, produced)) != true)
                            return@mapNotNull null
                        val address = tableAddress(index, function, load, raw) ?: return@mapNotNull null
                        if (address.stride < 8 || address.column !in 0..<address.stride || address.column % 8 != 0L)
                            return@mapNotNull null
                        val entries = stored.filter { pointer ->
                            val delta = pointer - address.base
                            delta >= 0 && delta % address.stride == address.column
                        }
                        if (entries.isEmpty()) return@mapNotNull null
                        val poll = failures.safeWaitResumes.filter { it.hookRva == callback.safeWaitResumeRva }
                        if (poll.isEmpty()) fail("UEFI 自动切槽 callback 缺少同事件的同步恢复点")
                        CallbackMatch(callback.ownerStart, callback.nativeFailureEvidence.first(), dispatch,
                            address, entries, poll.map { it.hookRva })
                    }
                }
            }.distinct()
            val match = matches.singleOrNull() ?: fail("address-taken 自动切槽的 UEFI 函数表调用链")
            val dead = resultKilledBeforeUse(index, site) ?: fail("callback 自动切槽返回值仍被使用")
            site.copy(requiresDeferredCallback = true, callbackEntryRva = match.entry,
                callbackRegistrationRva = match.registration, callbackDispatchCallRva = match.dispatch,
                callbackTableRva = match.table.base, callbackTableStride = match.table.stride,
                callbackTableColumn = match.table.column, callbackFunctionPointerRvas = match.entries,
                callbackNaturalResumeRva = site.callRva + 4, callbackResultDeadRva = dead,
                safeWaitResumeRvas = match.polls)
        }
    }

    private data class CallbackMatch(val entry: Long, val registration: Long, val dispatch: Long,
        val table: TableAddress, val entries: List<Long>, val polls: List<Long>)

    private fun directUefiRegistrations(index: Index, owners: Set<Long>): Map<Long, Long> {
        val references = owners.flatMap { owner -> index.addressReferences(owner).map { owner to it } }
        if (references.isEmpty()) return emptyMap()
        val environment = KblEnvironmentFacts.analyze(index.image)
        val found = linkedMapOf<Long, Long>()
        for ((owner, reference) in references) {
            val function = index.function(reference.pc) ?: continue
            for ((pc, word) in index.body(function).filter { it.first in reference.pc + 4..reference.pc + 128 }) {
                if (word and 0xfffffc1f.toInt() != 0xd63f0000.toInt()) continue
                val arguments = A64.argumentState(index.image, maxOf(function.start, pc - 128), pc)
                if ((arguments.registers[2] as? A64.Value.Address)?.rva != owner) continue
                val method = definition(index, function, pc, (word ushr 5) and 31) ?: continue
                val methodWord = index.image.word(method) ?: continue
                val field = StateBranchEvaluator.access(methodWord) ?: continue
                if (!field.load || field.width != 8 || field.offset != 0x50) continue
                val table = definition(index, function, method, field.rn) ?: continue
                val tableField = StateBranchEvaluator.access(index.image.word(table) ?: continue) ?: continue
                if (!tableField.load || tableField.width != 8) continue
                val slot = scalarAddress(index, function, table, tableField.rn)?.plus(tableField.offset) ?: continue
                if (slot == environment.bootServicesGlobal) found[owner] = pc
            }
        }
        return found
    }

    private fun definition(index: Index, owner: Function, pc: Long, register: Int): Long? {
        for (at in pc - 4 downTo maxOf(owner.start, pc - 128) step 4) {
            val word = index.image.word(at) ?: return null
            if (A64.blTarget(at, word) != null || word and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) {
                if (register < 19) return null
                continue
            }
            if (StraightLineLiveness.mayWriteRegister(word, register)) return at
        }
        return null
    }

    private fun scalarConstant(index: Index, owner: Function, pc: Long, register: Int): Long? {
        if (register == 31) return 0L
        val at = definition(index, owner, pc, register) ?: return null
        val word = index.image.word(at) ?: return null
        A64.movWImmediate(word)?.let { if (it.first == register) return it.second.toLong() }
        if (word and 0xff800000.toInt() == 0xd2800000.toInt())
            return ((word ushr 5) and 65535).toLong() shl (((word ushr 21) and 3) * 16)
        return null
    }

    private fun scalarAddress(index: Index, owner: Function, pc: Long, register: Int, depth: Int = 0): Long? {
        if (depth > 8) return null
        val at = definition(index, owner, pc, register) ?: return null
        val word = index.image.word(at) ?: return null
        A64.adr(at, word)?.takeIf { it.first == register }?.let { return it.second }
        A64.adrp(at, word)?.takeIf { it.first == register }?.let { return it.second }
        A64.add(word)?.takeIf { it.rd == register && it.rn != 31 }?.let {
            return scalarAddress(index, owner, at, it.rn, depth + 1)?.plus(it.immediate)
        }
        return null
    }

    private fun multiplyAddress(index: Index, owner: Function, pc: Long, register: Int, depth: Int = 0): TableAddress? {
        if (depth > 8) return null
        val at = definition(index, owner, pc, register) ?: return null
        val word = index.image.word(at) ?: return null
        A64.add(word)?.takeIf { it.rd == register && it.rn != 31 }?.let {
            return multiplyAddress(index, owner, at, it.rn, depth + 1)?.let { base -> base.copy(column = base.column + it.immediate) }
        }
        // UMADDL (including UMULL with XZR addend): native enum-index *
        // independently proved record stride + actual referenced table base.
        if (word and 0xffe08000.toInt() != 0x9ba00000.toInt()) return null
        val rn = (word ushr 5) and 31
        val rm = (word ushr 16) and 31
        val addend = (word ushr 10) and 31
        val stride = listOfNotNull(scalarConstant(index, owner, at, rn), scalarConstant(index, owner, at, rm))
            .filter { it >= 8 && it % 8 == 0L }.distinct().singleOrNull() ?: return null
        val base = if (addend == 31) 0L else scalarAddress(index, owner, at, addend) ?: return null
        return TableAddress(base, stride, 0)
    }

    private fun tableAddress(index: Index, owner: Function, pc: Long, word: Int): TableAddress? {
        val baseRegister = (word ushr 5) and 31
        if (word and 0xffe00c00.toInt() == 0xf8600800.toInt()) {
            // LDR Xt,[Xbase,Xindex], unshifted UXTX. Other forms need their
            // actual extension/scale proved, not a guessed byte index.
            if ((word ushr 13) and 7 != 3 || word and 0x1000 != 0) return null
            val base = scalarAddress(index, owner, pc, baseRegister) ?: return null
            val indexPart = multiplyAddress(index, owner, pc, (word ushr 16) and 31) ?: return null
            if (indexPart.base != 0L) return null
            return indexPart.copy(base = base)
        }
        if (word and 0xffc00000.toInt() != 0xf9400000.toInt()) return null
        val address = multiplyAddress(index, owner, pc, baseRegister) ?: return null
        return address.copy(column = address.column + ((word ushr 10) and 4095) * 8L)
    }

    private fun resultKilledBeforeUse(index: Index, site: Site): Long? {
        for (pc in site.callRva + 4 until minOf(site.ownerEndRva, site.callRva + 68) step 4) {
            val word = index.image.word(pc) ?: return null
            val immediate = A64.movWImmediate(word)
            if (immediate != null) { if (immediate.first == 0) return pc; continue }
            val address = A64.adr(pc, word) ?: A64.adrp(pc, word)
            if (address != null) { if (address.first == 0) return pc; continue }
            val add = A64.add(word)
            if (add != null) {
                if (add.rn == 0) return null
                if (add.rd == 0) return pc
                continue
            }
            if (word == 0xd503201f.toInt()) continue
            return null // Any unproved consumer/control-flow retains its result.
        }
        return null
    }

    private fun defaultInitialization(index: Index, manualHandler: Long, shared: Function, commit: Long): List<DefaultInitializationSite> {
        val queries = index.calls.filter { it.owner == shared }.mapNotNull { call ->
            val function = index.function(call.target)?.takeIf { it.start == call.target } ?: return@mapNotNull null
            if (index.has(function, "GetActiveSlot: First boot: set default slot") && index.has(function, "GetActiveSlot: No active slot found"))
                call to function else null
        }.distinctBy { it.second }
        if (queries.isEmpty()) {
            // Absence of this optional native default-repair feature is explicit.
            if (index.code.mapNotNull { index.function(it.first) }.distinct().any { index.has(it, "GetActiveSlot: First boot: set default slot") })
                fail("默认槽位初始化与 manual SetActive 的共享查询关系")
            return emptyList()
        }
        return queries.map { (manualQuery, function) ->
            val body = index.body(function)
            val defaultReference = index.references(function).filter {
                index.image.ascii(it.address)?.contains("GetActiveSlot: First boot: set default slot", true) == true
            }.singleOrNull() ?: fail("默认槽位初始化日志来源")
            val errorReference = index.references(function).filter {
                index.image.ascii(it.address)?.contains("GetActiveSlot: No active slot found", true) == true
            }.singleOrNull() ?: fail("无 active 槽位的原生错误来源")
            val errorLog = index.calls.filter { it.owner == function && it.pc in errorReference.pc + 4..errorReference.pc + 96 }
                .firstOrNull {
                    (A64.argumentState(index.image, errorReference.pc, it.pc).registers[1] as? A64.Value.Address)?.rva == errorReference.address
                } ?: fail("无 active 错误日志的实际消费者")
            val stores = body.filter { (pc, word) ->
                pc in defaultReference.pc + 4..defaultReference.pc + 96 &&
                    word and 0xffc00000.toInt() == 0x39000000 &&
                    (word ushr 5) and 31 != 31 && index.flow(function).stackAddress(pc, (word ushr 5) and 31) == null
            }
            val firstStore = stores.firstOrNull() ?: fail("默认 active 属性首次写入")
            val nativeCommit = index.calls.firstOrNull {
                it.owner == function && it.target == commit && it.pc in firstStore.first + 4..firstStore.first + 256 && constant(index, it, 0) == 1L
            } ?: fail("默认槽位属性写入的原生提交")
            val copy = index.calls.filter { it.owner == function && it.pc in firstStore.first + 4..<nativeCommit.pc }.singleOrNull { call ->
                index.function(call.target)?.let { it.start == call.target && char16Copy(index, it) } == true &&
                    constant(index, call, 1)?.let { it > 0 && it <= Int.MAX_VALUE / 2 } == true
            } ?: fail("默认槽位输出缓冲区的有界 CHAR16 复制")
            val capacity = requireNotNull(constant(index, copy, 1)).toInt()
            val outputArguments = (0..7).filter { argument ->
                entrySource(index, function, copy.pc, 0) == A64ValueOrigin.EntryArgument(argument)
            }
            val outputArgument = outputArguments.singleOrNull() ?: fail("默认槽位输出必须来自同一入口缓冲区")
            val decisions = body.mapNotNull { (pc, word) ->
                val target = conditionalTarget(pc, word) ?: return@mapNotNull null
                if (pc >= firstStore.first) return@mapNotNull null
                val takenDefault = linearReach(index.image, target, defaultReference.pc)
                val fallDefault = linearReach(index.image, pc + 4, defaultReference.pc)
                if (takenDefault == fallDefault) return@mapNotNull null
                val error = if (takenDefault) pc + 4 else target
                if (!linearReach(index.image, error, errorLog.pc)) return@mapNotNull null
                val beginning = if (takenDefault) target else pc + 4
                if (!linearReach(index.image, beginning, firstStore.first, setOf(errorLog.target))) return@mapNotNull null
                // Prove that no other branch can enter the default block after
                // this decision and reach the first mutation without the guard.
                val incoming = body.filter { (at, raw) ->
                    val to = directTarget(at, raw)
                    to != null && to in beginning..firstStore.first && at !in beginning..<firstStore.first
                }
                if (incoming.any { it.first != pc }) return@mapNotNull null
                val previous = index.image.word(beginning - 4)
                if (beginning != pc + 4 && previous != null && A64.bTarget(beginning - 4, previous) == null && !isReturn(previous))
                    return@mapNotNull null
                val output = (0..30).filter {
                    entrySource(index, function, pc, it) == A64ValueOrigin.EntryArgument(outputArgument)
                }.minOrNull() ?: return@mapNotNull null
                DefaultDecision(pc, word, takenDefault, beginning, error, output)
            }.singleOrNull() ?: fail("默认初始化前唯一的无 active 错误分流")

            val returns = body.filter { (_, word) -> isReturn(word) }
            val epilogues = body.mapNotNull { (pc, word) ->
                if (word and 0xffe0ffff.toInt() != 0xaa0003e0.toInt()) return@mapNotNull null // MOV X0,Xn
                val source = (word ushr 16) and 31
                if (source !in 1..30) return@mapNotNull null
                val ret = returns.firstOrNull { it.first in pc + 4..pc + 64 } ?: return@mapNotNull null
                val joins = body.mapNotNull { (at, raw) -> A64.bTarget(at, raw) }
                    .filter { it in maxOf(function.start, pc - 64)..pc &&
                        linearReach(index.image, decisions.errorBranch, it, setOf(errorLog.target)) }.distinct()
                joins.mapNotNull { join ->
                    val flow = A64ValueOrigins(index.image, join, ret.first + 4)
                    if (flow.derivesFromEntry(ret.first, 0, source)) ErrorReturn(join, source) else null
                }.singleOrNull()
            }.distinct()
            val epilogue = epilogues.singleOrNull() ?: fail("默认初始化的原生错误返回 epilogue")
            val definition = nativeStatus(index, function, decisions.errorBranch, errorLog.pc, epilogue.register)
            if (definition.value and Long.MIN_VALUE == 0L) fail("默认初始化的原生失败返回值不是 EFI error")
            if (!linearReach(index.image, decisions.errorBranch, epilogue.rva, setOf(errorLog.target)))
                fail("原无 active 错误路径不是只读日志后返回")
            DefaultInitializationSite(function.start, function.end, manualHandler, manualQuery.pc,
                decisions.pc, decisions.word, decisions.defaultTaken, decisions.defaultBranch,
                firstStore.first, nativeCommit.pc, nativeCommit.target, decisions.errorBranch,
                epilogue.rva, epilogue.register, definition.value, definition.words,
                outputArgument, decisions.output, capacity * 2, copy.pc)
        }
    }

    private data class DefaultDecision(val pc: Long, val word: Int, val defaultTaken: Boolean,
        val defaultBranch: Long, val errorBranch: Long, val output: Int)
    private data class ErrorReturn(val rva: Long, val register: Int)
    private data class NativeStatus(val value: Long, val words: List<StatusDefinition>)

    /** CHAR16 copy consumers, including the native loop's two-byte advances. */
    private fun char16Copy(index: Index, function: Function): Boolean {
        val body = index.body(function)
        val capacityCompared = body.any { (pc, word) ->
            word and 0x7f20001f == 0x6b00001f &&
                (index.flow(function).derivesFromEntry(pc, (word ushr 5) and 31, 1) ||
                    index.flow(function).derivesFromEntry(pc, (word ushr 16) and 31, 1))
        }
        if (!capacityCompared) return false
        val terminated = body.any { (pc, word) ->
            word and 0xffc0001f.toInt() == 0x7900001f &&
                pointerFromEntry(index, function, pc, (word ushr 5) and 31, 0)
        }
        if (!terminated) return false
        return body.any { (pc, word) ->
            if (word and 0xffc00000.toInt() != 0x79400000 ||
                !pointerFromEntry(index, function, pc, (word ushr 5) and 31, 2)) return@any false
            body.any { (storePc, storeWord) ->
                storePc in pc + 4..pc + 32 &&
                    (storeWord and 0xffc00000.toInt() == 0x79000000 ||
                        storeWord and 0xffe00c00.toInt() == 0x78000400) &&
                    pointerFromEntry(index, function, storePc, (storeWord ushr 5) and 31, 0) &&
                    A64ValueOrigins(index.image, pc, storePc + 4).derivesFrom(storePc, storeWord and 31, pc, word and 31)
            }
        }
    }

    private fun pointerFromEntry(index: Index, function: Function, pc: Long, register: Int, argument: Int): Boolean {
        val entry = A64ValueOrigin.EntryArgument(argument)
        val initial = index.flow(function).origins(pc, register) ?: return false
        if (entry !in initial) return false
        val pending = ArrayDeque<Pair<Long, Int>>().also { it.add(pc to register) }
        val seen = hashSetOf<Pair<Long, Int>>()
        while (pending.isNotEmpty()) {
            val point = pending.removeFirst()
            if (!seen.add(point)) continue
            val sources = index.flow(function).origins(point.first, point.second) ?: return false
            for (source in sources) {
                if (source == entry) continue
                val definition = source as? A64ValueOrigin.Definition ?: return false
                val word = index.image.word(definition.pc) ?: return false
                val add = A64.add(word)
                val advance = add?.let { it.rd == definition.register && it.rn == definition.register && it.immediate == 2L } == true ||
                    word and 0xffe00c00.toInt() == 0x78000400 &&
                    (word ushr 5) and 31 == definition.register && signed(((word ushr 12) and 0x1ff).toLong(), 9) == 2L
                if (!advance) return false
                pending.add(definition.pc to definition.register)
            }
        }
        return true
    }

    private fun nativeStatus(index: Index, function: Function, from: Long, until: Long, register: Int): NativeStatus {
        var value: Long? = null
        val definitions = arrayListOf<StatusDefinition>()
        for ((pc, word) in index.body(function).filter { it.first in from..<until }) {
            if (word and 31 != register) continue
            val opcode = word and 0xff800000.toInt()
            val shift = ((word ushr 21) and 3) * 16
            val immediate = ((word ushr 5) and 0xffff).toLong() shl shift
            when (opcode) {
                0xd2800000.toInt() -> { value = immediate; definitions.clear(); definitions += StatusDefinition(pc, word) }
                0x92800000.toInt() -> { value = immediate.inv(); definitions.clear(); definitions += StatusDefinition(pc, word) }
                0xf2800000.toInt() -> {
                    val previous = value ?: continue
                    value = (previous and (0xffffL shl shift).inv()) or immediate
                    definitions += StatusDefinition(pc, word)
                }
                else -> if (value != null && StraightLineLiveness.mayWriteRegister(word, register))
                    fail("原生错误状态定义在使用前被覆盖")
            }
        }
        return NativeStatus(value ?: fail("原生错误状态的 MOVZ/MOVK 来源"), definitions)
    }

    /** Trace small outlined MOV/ADD argument-preparation leaves as well as MOVs. */
    private fun entrySource(index: Index, function: Function, pc: Long, register: Int, depth: Int = 0): A64ValueOrigin? {
        if (depth > 8) return null
        val source = index.flow(function).origins(pc, register)?.singleOrNull()
        if (source is A64ValueOrigin.EntryArgument || source == A64ValueOrigin.Zero) return source
        if (source is A64ValueOrigin.Definition) return source
        // General call provenance intentionally returns Unknown for written
        // registers. Only expand an actual small returning leaf whose exported
        // value is an entry argument, and prove that no later instruction/call
        // replaced that value before this consumer.
        val results = arrayListOf<A64ValueOrigin>()
        for (call in index.calls.filter { it.owner == function && it.pc in maxOf(function.start, pc - 128)..<pc }) {
            val after = call.pc + 4
            if (!index.flow(function).hasOnlyLinearEntry(after, pc)) continue
            val consumer = A64ValueOrigins(index.image, after, pc + 4)
            val exported = (0..30).filter { consumer.derivesFromEntry(pc, register, it) }
            if (exported.isEmpty()) continue
            var ret: Long? = null
            for (at in call.target until call.target + 128 step 4) {
                val word = index.image.word(at) ?: break
                if (isReturn(word)) { ret = at; break }
                if (controlTransfer(at, word)) break
            }
            val end = ret ?: continue
            val leaf = A64ValueOrigins(index.image, call.target, end + 4)
            for (result in exported) {
                val origin = leaf.origins(end, result)?.singleOrNull()
                if (origin == A64ValueOrigin.Zero) results += origin
                val argument = origin as? A64ValueOrigin.EntryArgument ?: continue
                entrySource(index, function, call.pc, argument.register, depth + 1)?.let { results += it }
            }
        }
        return results.distinct().singleOrNull()
    }

    /** Bounded forward-only native path; allowed calls must share the proved logger. */
    private fun linearReach(image: PeImage, start: Long, end: Long, allowedCalls: Set<Long> = emptySet()): Boolean {
        var pc = start
        val seen = hashSetOf<Long>()
        while (pc != end) {
            if (pc > end || end - pc > 1024 || !seen.add(pc)) return false
            val word = image.word(pc) ?: return false
            A64.blTarget(pc, word)?.let { if (it !in allowedCalls) return false }
            if (conditionalTarget(pc, word) != null || isReturn(word) ||
                word and 0xfffffc1f.toInt() in setOf(0xd61f0000.toInt(), 0xd63f0000.toInt())) return false
            if (word and 0x3a400000 == 0x28000000 && (word ushr 5) and 31 != 31 ||
                word and 0x3b400000 == 0x39000000 && (word ushr 5) and 31 != 31) return false
            val jump = A64.bTarget(pc, word)
            if (jump != null) { if (jump <= pc) return false; pc = jump } else pc += 4
        }
        return true
    }

    private fun isReturn(word: Int) = word and 0xfffffc1f.toInt() == 0xd65f0000.toInt() ||
        word in setOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt())
    private fun conditionalTarget(pc: Long, word: Int): Long? = when {
        word and 0x7e000000 == 0x34000000 || word and 0xff000010.toInt() == 0x54000000 ->
            pc + (signed(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
        word and 0x7e000000 == 0x36000000 -> pc + (signed(((word ushr 5) and 0x3fff).toLong(), 14) shl 2)
        else -> null
    }
    private fun directTarget(pc: Long, word: Int) = A64.bTarget(pc, word) ?: conditionalTarget(pc, word)

    private fun site(index: Index, call: Call, kind: Kind, mutations: List<Mutation>): Site {
        if (kind == Kind.HANDLE_ACTIVE_UNBOOTABLE) return Site(kind,
            call.owner.start, call.owner.end, call.pc, call.word, call.target, mutations)
        val gate = index.body(call.owner).firstOrNull { (pc, raw) ->
            pc in call.pc + 4..call.pc + 24 && raw and 0x7e00001f == 0x34000000 &&
                (pc == call.pc + 4 || StraightLineLiveness.preserves(index.image, call.pc + 4, pc, setOf(0)))
        } ?: fail("自动 SetActive 的原生零结果分支")
        val displacement = signed(((gate.second ushr 5) and 0x7ffff).toLong(), 19) shl 2
        val success = if (gate.second and 0x01000000 == 0) gate.first + displacement else gate.first + 4
        if (success !in call.owner.start..<call.owner.end) fail("自动 SetActive 成功分支越界")
        if (kind == Kind.BOOT_DETECTOR_SET_ACTIVE) return Site(kind,
            call.owner.start, call.owner.end, call.pc, call.word, call.target, mutations,
            gate.first, 0, success)

        // ResetSystem's 0x68 field belongs to the standard EFI_RUNTIME_SERVICES
        // ABI, not a firmware RVA. Prove the actual indirect call consumes that
        // field, then use its native fallthrough. No guessed Fastboot/main PC.
        val end = minOf(call.owner.end, success + 192)
        for ((pc, raw) in index.body(call.owner).filter { it.first in success..<end }) {
            if (raw and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) {
                val register = (raw ushr 5) and 31
                val field = resetFieldProducer(index, call.owner, success, pc, register)
                    ?: fail("自动重启调用没有消费 ResetSystem 字段")
                if (!index.image.isCode(pc + 4)) fail("自动重启后的本地汇合点")
                return Site(kind, call.owner.start, call.owner.end, call.pc, call.word, call.target,
                    mutations, gate.first, 0, pc + 4, pc, field)
            }
            if (A64.bTarget(pc, raw) != null || raw and 0xff000010.toInt() == 0x54000000 ||
                raw and 0x7e000000 in setOf(0x34000000, 0x36000000) ||
                raw and 0xfffffc1f.toInt() in setOf(0xd61f0000.toInt(), 0xd65f0000.toInt()))
                fail("自动 SetActive 成功路径并非可证明的本地 reset 汇合")
        }
        fail("自动 SetActive 的 ResetSystem 后汇合点")
    }

    private fun resetFieldProducer(index: Index, owner: Function, start: Long, call: Long, register: Int): Long? {
        fun field(pc: Long, word: Int, flow: A64ValueOrigins): Boolean =
            word and 0xffc00000.toInt() == 0xf9400000.toInt() &&
                ((word ushr 10) and 0xfff) * 8 == 0x68 && (word ushr 5) and 31 != 31 &&
                flow.stackAddress(pc, (word ushr 5) and 31) == null
        val candidates = arrayListOf<Long>()
        for ((pc, word) in index.body(owner).filter { it.first in start..<call }) {
            if (field(pc, word, index.flow(owner))) {
                // Starting at the load preserves its exact producer identity.
                // A wider region proof can conservatively lose an intermediate
                // pointer-to-pointer source even though this consumer is local.
                val consumer = A64ValueOrigins(index.image, pc, call + 4)
                if (consumer.derivesFrom(call, register, pc, word and 31)) candidates += pc
            }
            val target = A64.blTarget(pc, word) ?: continue
            val leaf = arrayListOf<Pair<Long, Int>>()
            var ret: Long? = null
            for (at in target until target + 128 step 4) {
                val raw = index.image.word(at) ?: break
                if (isReturn(raw)) { ret = at; break }
                if (controlTransfer(at, raw)) break
                leaf += at to raw
            }
            val returning = ret ?: continue
            val caller = A64ValueOrigins(index.image, pc + 4, call + 4)
            for ((at, raw) in leaf) {
                val producer = A64ValueOrigins(index.image, target, returning + 4)
                if (!field(at, raw, producer)) continue
                val consumer = A64ValueOrigins(index.image, at, returning + 4)
                if ((0..30).any { exported ->
                        consumer.derivesFrom(returning, exported, at, raw and 31) &&
                            caller.derivesFromEntry(call, register, exported)
                    }) candidates += at
            }
        }
        return candidates.distinct().singleOrNull()
    }

    private fun consumesEntryFlag(index: Index, function: Function, argument: Int): Boolean =
        index.body(function).any { (pc, word) ->
            // AND/ANDS/ORR immediate consumers retain a scalar boolean source.
            word and 0x1f800000 == 0x12000000 &&
                index.flow(function).derivesFromEntry(pc, (word ushr 5) and 31, argument)
        }

    private fun constant(index: Index, call: Call, argument: Int): Long? {
        fun value(flow: A64ValueOrigins): Long? {
            val sources = flow.origins(call.pc, argument) ?: return null
            if (sources == setOf(A64ValueOrigin.Zero)) return 0
            val source = sources.singleOrNull() as? A64ValueOrigin.Definition ?: return null
            val immediate = index.image.word(source.pc)?.let(A64::movWImmediate) ?: return null
            return if (immediate.first == source.register) immediate.second.toLong() else null
        }
        value(index.flow(call.owner))?.let { return it }
        // A self-contained final basic block can still prove a freshly defined
        // argument when an earlier, unrelated main switch is not modeled.
        var start = maxOf(call.owner.start, call.pc - 128)
        for ((pc, word) in index.body(call.owner).filter { it.first in start..<call.pc }) {
            if (controlTransfer(pc, word)) start = pc + 4
        }
        if (start >= call.pc) return null
        return value(A64ValueOrigins(index.image, start, call.pc + 4))
    }

    private fun mutations(index: Index, function: Function): List<Mutation> {
        val body = index.body(function)
        val results = arrayListOf<Mutation>()
        for ((pc, word) in body) {
            val store = A64.stpW(word) ?: continue
            if (store.rn == 31 || index.flow(function).stackAddress(pc, store.rn) != null) continue
            val reads = body.filter { (at, raw) -> at in maxOf(function.start, pc - 160)..<pc &&
                raw and 0x3a400000 == 0x28400000 && raw and 0xc4000000.toInt() == 0 &&
                (raw ushr 5) and 31 == store.rn }
            val read = reads.lastOrNull() ?: continue
            val commits = index.calls.filter { it.owner == function && it.pc in pc + 4..pc + 96 && constant(index, it, 0) == 1L }
            val commit = commits.firstOrNull() ?: continue
            results += Mutation(pc, read.first, commit.pc, commit.target)
        }
        return results.distinct()
    }

    private fun manualCommand(image: PeImage): Manual {
        fun pointer(value: Long): Long? = listOf(value - image.imageBase, value).distinct()
            .firstOrNull { it >= 0 && image.rvaToOffset(it) != null }
        fun record(offset: Int): Pair<String, Long>? {
            if (offset < 0 || offset.toLong() + 16 > image.bytes.size) return null
            val nameRva = pointer(image.bytes.u64(offset)) ?: return null
            val name = image.ascii(nameRva, 96)?.takeIf { it.isNotEmpty() && it.all { c -> c.code in 32..126 } } ?: return null
            val handler = pointer(image.bytes.u64(offset + 8)) ?: return null
            if (!image.isCode(handler) || !isEntry(image.word(handler) ?: return null)) return null
            return name to handler
        }
        val candidates = arrayListOf<Manual>()
        val names = image.bytes.occurrences("set_active\u0000".toByteArray(Charsets.US_ASCII)).mapNotNull(image::offsetToRva).toSet()
        for (offset in 0..image.bytes.size - 16 step 8) {
            if (pointer(image.bytes.u64(offset)) !in names) continue
            val selected = record(offset)?.takeIf { it.first == "set_active" } ?: continue
            var low = offset; var high = offset + 16
            while (record(low - 16) != null) low -= 16
            while (record(high) != null) high += 16
            val commands = (low until high step 16).map { requireNotNull(record(it)).first }.toSet()
            if (!commands.containsAll(setOf("flash:", "erase:", "getvar:", "download:"))) continue
            candidates += Manual(selected.second, requireNotNull(image.offsetToRva(low)),
                requireNotNull(image.offsetToRva(high - 1)) + 1)
        }
        return candidates.distinct().singleOrNull() ?: fail("原 set_active 命令表入口缺失或歧义")
    }

    private fun isEntry(word: Int) = word in setOf(0xd503233f.toInt(), 0xd503237f.toInt())
    private fun signed(value: Long, bits: Int) = (value shl (64 - bits)) shr (64 - bits)
    private fun controlTransfer(pc: Long, word: Int) = A64.blTarget(pc, word) != null ||
        A64.bTarget(pc, word) != null || word and 0xff000010.toInt() == 0x54000000 ||
        word and 0x7e000000 in setOf(0x34000000, 0x36000000) ||
        word and 0xfffffc1f.toInt() in setOf(0xd61f0000.toInt(), 0xd63f0000.toInt(), 0xd65f0000.toInt())
    private fun fail(detail: String): Nothing = throw PatchException("Fastboot slot recovery：$detail。")
}
