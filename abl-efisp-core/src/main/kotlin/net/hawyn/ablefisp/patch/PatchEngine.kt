package net.hawyn.ablefisp.patch

enum class PatchMode { FREEDOM, FREEDOM_FAKE_LOCK }

data class PatchMatch(
    val name: String,
    val rva: Long,
    val fileOffset: Int,
    val before: String,
    val after: String,
    val alreadyApplied: Boolean,
)

data class PatchResult @JvmOverloads constructor(
    val output: ByteArray,
    val sourceFormat: String,
    val sourceSha256: String,
    val outputSha256: String,
    val imageSize: Int,
    val patches: List<PatchMatch>,
    val notes: List<String>,
    val alreadyPatched: Boolean,
    val mode: PatchMode = PatchMode.FREEDOM,
)

/** Pure JVM engine: extraction and semantic matching never write the source file. */
object PatchEngine {
    fun extract(input: ByteArray): ExtractedImage = FirmwareExtractor.extract(input)

    @JvmOverloads
    fun analyzeAndPatch(input: ByteArray, mode: PatchMode = PatchMode.FREEDOM): PatchResult {
        val extracted = extract(input)
        val image = PeImage.parse(extracted.output)
        val analysis = SemanticPatcher.analyze(image)
        val additional = if (mode == PatchMode.FREEDOM_FAKE_LOCK) {
            FakeLockPatcher.analyze(image, requireNotNull(analysis.stateGlobal))
        } else SemanticPlan(emptyList(), emptyList())
        val rootPolicy = if (mode == PatchMode.FREEDOM_FAKE_LOCK) {
            CheckRootPolicyPatcher.analyze(image)
        } else SemanticPlan(emptyList(), emptyList())
        val recovery = BootFailurePolicyPatcher.analyze(image)
        val lateState = if (mode == PatchMode.FREEDOM_FAKE_LOCK) {
            LateBootStatePatcher.analyze(image, requireNotNull(additional.normalBootState))
        } else emptyList()
        val rollbackUpdate = RollbackUpdatePatcher.analyze(image)
        fun sharesCallerMapping(anchor: Long, stub: Long?, size: Int): Boolean {
            if (stub == null) return false
            return image.sections.any { section ->
                val length = minOf(section.rawSize.toLong(), section.virtualSize.takeIf { it > 0 } ?: section.rawSize.toLong())
                section.executable && !section.writable && section.characteristics and 0x02000000L == 0L &&
                    anchor >= section.rva && anchor + 4 <= section.rva + length &&
                    stub >= section.rva && stub + size <= section.rva + length
            }
        }
        val relocatedRecovery = recovery.filterNot { sharesCallerMapping(it.callRva, it.existingStubRva, BootFailurePolicyPatcher.STUB_BYTES) }
        val relocatedLateState = lateState.filterNot { sharesCallerMapping(it.selectorRva, it.existingStubRva, LateBootStatePatcher.STUB_BYTES) }
        val slots = MappedRxCodeAllocator.allocate(image, relocatedRecovery.map {
            MappedRxCodeAllocator.Request(it.callRva, BootFailurePolicyPatcher.STUB_BYTES)
        } + relocatedLateState.map {
            MappedRxCodeAllocator.Request(it.selectorRva, LateBootStatePatcher.STUB_BYTES)
        })
        val destinations = relocatedRecovery.zip(slots.take(relocatedRecovery.size)).associate { (item, slot) -> item.callRva to slot.rva }
        val lateDestinations = relocatedLateState.zip(slots.drop(relocatedRecovery.size)).associate { (item, slot) -> item.selectorRva to slot.rva }
        val recoveryEdits = recovery.flatMap { item ->
            val stub = destinations[item.callRva] ?: requireNotNull(item.existingStubRva)
            BootFailurePolicyPatcher.edits(item, stub)
        }
        val lateEdits = lateState.flatMap { item ->
            val stub = lateDestinations[item.selectorRva] ?: requireNotNull(item.existingStubRva)
            LateBootStatePatcher.edits(item, stub)
        }
        val plan = analysis.edits + additional.edits + rootPolicy.edits + recoveryEdits + lateEdits + rollbackUpdate.edits
        val output = image.bytes.copyOf()
        val occupied = HashSet<Int>()
        val matches = plan.map { edit ->
            val offset = image.rvaToOffset(edit.rva, edit.replacement.size)
                ?: throw PatchException("修补目标未映射到文件。")
            for (at in offset until offset + edit.replacement.size) {
                if (!occupied.add(at)) throw PatchException("修补目标范围重叠，已停止生成。")
            }
            val before = output.copyOfRange(offset, offset + edit.replacement.size)
            edit.replacement.copyInto(output, offset)
            PatchMatch(edit.name, edit.rva, offset, before.hex(), edit.replacement.hex(), before.contentEquals(edit.replacement))
        }
        val recoveryNotes = if (recovery.isEmpty()) listOf(
            "未发现此类 AVB hash 故障恢复调用，没有添加额外恢复补丁。",
        ) else listOf(
            "按 hash 错误上报、允许错误值来源与错误码范围定位 ${recovery.size} 处恢复调用。",
            "仅允许验证错误且结果为 ERROR_VERIFICATION 时跳过该厂商故障上报；读取、内存、元数据格式等其他错误保持原调用。",
            "建议保留原版 vbmeta；该修改不保证被改签名或损坏的元数据能通过 Android 后续校验。",
            "条件代码使用调用点所在的原有只读可执行映射，不追加代码节或扩大加载范围；已存在的同映射补丁可复用。",
        )
        val lateNotes = if (lateState.isEmpty()) emptyList() else listOf(
            "按同一启动状态字段、允许错误值来源、失败分支和本地返回变量定位 ${lateState.size} 处尾部状态重算。",
            "仅正常成功且允许验证错误时保持 green；失败回退的原状态、错误返回和资源清理保留。",
        )
        return PatchResult(
            output, extracted.sourceFormat, input.sha256(), output.sha256(), output.size,
            matches, extracted.notes + analysis.notes + additional.notes + rootPolicy.notes + recoveryNotes + lateNotes + rollbackUpdate.notes + listOf(
                "已按代码关系定位三处状态初始化分支。",
                "输出保留原有 PE 节布局、入口与加载长度。",
                "已写入定位到的功能修改点。",
            ), matches.all { it.alreadyApplied }, mode,
        )
    }
}

internal data class PlannedEdit(val name: String, val rva: Long, val replacement: ByteArray)
internal data class NormalBootStateFacts(
    val functionStart: Long,
    val functionEnd: Long,
    val policyDefinitionPc: Long,
    val policyRegister: Int,
    val normalStateProducer: Long,
    val normalStateRegister: Int,
    val normalStateStore: Long,
    val contextBaseRegister: Int,
    val contextFieldOffset: Long,
)
internal data class SemanticPlan(
    val edits: List<PlannedEdit>, val notes: List<String>, val stateGlobal: Long? = null,
    val normalBootState: NormalBootStateFacts? = null,
)

internal object SemanticPatcher {
    private val CONTEXT = "fastboot flash:rdmodev1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 1)
    private val WEAK_MARKERS = listOf("fastboot flash:rdmodev1", "common_check_rdmod_v1_state")
        .map { it.toByteArray(Charsets.US_ASCII) }
    private data class Success(
        val target: Long, val stateRegister: Int, val storeStart: Long,
        val store: Long, val global: Long, val readOemInfo: Long,
    )
    private data class Cluster(val readBranch: Long, val logBranch: Long, val logExit: Long, val verifyBranch: Long, val success: Success)
    private data class Descriptor(val message: Long, val messageLength: Int, val signature: A64.Value, val signatureLength: Int)

    fun plan(image: PeImage): List<PlannedEdit> = analyze(image).edits

    fun analyze(image: PeImage): SemanticPlan {
        val contexts = image.bytes.occurrences(CONTEXT).mapNotNull(image::offsetToRva).toSet()
        if (contexts.isEmpty()) {
            val hasPartialEvidence = WEAK_MARKERS.any { image.bytes.occurrences(it).isNotEmpty() }
            throw PatchException(
                if (hasPartialEvidence) "发现 rdmode 相关消息，但认证上下文未完整匹配。" else "镜像未包含当前类型的状态修补特征。",
                if (hasPartialEvidence) PatchFailureKind.INCOMPLETE_CORE_FEATURE else PatchFailureKind.NO_CORE_FEATURE,
            )
        }
        val refs = A64.references(image)
        val candidates = ArrayList<Cluster>()
        for (ref in refs.filter { it.address in contexts }) {
            var pc = ref.pc
            while (pc <= ref.pc + 0xa0 && image.isCode(pc)) {
                val word = image.word(pc) ?: break
                val verify = A64.blTarget(pc, word)
                if (verify != null && image.isCode(verify) && A64.immediateBefore(image, pc, 1) == 2 && A64.immediateBefore(image, pc, 2) == 2) {
                    val branchPc = pc + 4
                    val branch = image.word(branchPc)
                    val successTarget = branch?.let { A64.cbzW0(branchPc, it) ?: A64.bTarget(branchPc, it) }
                    val success = successTarget?.let { successBlock(image, it) }
                    if (success != null) {
                        candidates += clustersForSecondCall(image, refs, ref, pc, verify, success)
                    }
                }
                pc += 4
            }
        }
        val clusters = candidates.distinctBy { listOf(it.readBranch, it.logBranch, it.verifyBranch) }
        if (clusters.isEmpty()) throw PatchException("已找到消息，但读取、验签、日志循环和状态写入关系未完整匹配；本次没有修改。", PatchFailureKind.INCOMPLETE_CORE_FEATURE)
        if (clusters.size != 1) throw PatchException("识别到 ${clusters.size} 组有效修补目标，目标存在歧义；本次没有修改。", PatchFailureKind.AMBIGUOUS_CORE_FEATURE)
        val cluster = clusters.single()
        val partition = partitionEdit(image, refs)
        fun branchEdit(name: String, pc: Long, target: Long): PlannedEdit {
            val result = ByteArray(4)
            result.putWord(0, A64.branch(pc, target))
            return PlannedEdit(name, pc, result)
        }
        return SemanticPlan(listOfNotNull(
            branchEdit("OEMINFO 读取结果分支", cluster.readBranch, cluster.readBranch + 4),
            branchEdit("rdmode 日志循环分支", cluster.logBranch, cluster.logExit),
            branchEdit("rdmode 验证结果分支", cluster.verifyBranch, cluster.success.target),
            partition.edit,
        ), listOf(partition.note), cluster.success.global)
    }

    private fun clustersForSecondCall(
        image: PeImage, refs: List<StringReference>, context: StringReference,
        secondCall: Long, verifyTarget: Long, success: Success,
    ): List<Cluster> {
        val result = ArrayList<Cluster>()
        val firstCalls = scan(image, maxOf(0, context.pc - 0x800), context.pc).filter { (pc, w) ->
            A64.blTarget(pc, w) == verifyTarget &&
                A64.immediateBefore(image, pc, 1) == 2 && A64.immediateBefore(image, pc, 2) == 2 &&
                image.word(pc + 4)?.let { A64.cbzW0(pc + 4, it) } == success.target
        }.map { it.first }
        for (firstCall in firstCalls) {
            val readCalls = scan(image, maxOf(0, firstCall - 0x240), firstCall).filter { (pc, w) ->
                A64.blTarget(pc, w) == success.readOemInfo &&
                    A64.immediateBefore(image, pc, 0) == 0x82 && A64.immediateBefore(image, pc, 2) == 0x180
            }.map { it.first }
            for (readCall in readCalls) {
                val readState = A64.argumentState(image, readCall - 64, readCall)
                val readBuffer = readState.registers[1] as? A64.Value.Stack ?: continue
                val firstDescriptor = descriptor(image, readCall - 64, firstCall) ?: continue
                val secondDescriptor = descriptor(image, context.pc, secondCall) ?: continue
                if (firstDescriptor.signature != readBuffer || secondDescriptor.signature != readBuffer ||
                    firstDescriptor.signatureLength != 0x180 || secondDescriptor.signatureLength != 0x180 ||
                    firstDescriptor.messageLength <= 0 || secondDescriptor.messageLength != CONTEXT.size ||
                    secondDescriptor.message != context.address) continue
                // The first message is an opaque authentication payload. Its content
                // and length are not firmware identity: the same signature-buffer
                // source, callee/arguments and shared status path establish the link.
                if (image.rvaToOffset(firstDescriptor.message, firstDescriptor.messageLength) == null) continue
                val branchPc = readCall + 4
                val word = image.word(branchPc) ?: continue
                val failure = A64.tbnzW0Bit31(branchPc, word)
                val isPatched = A64.bTarget(branchPc, word) == branchPc + 4
                if (failure == null && !isPatched) continue
                if (failure != null && (failure <= secondCall || failure >= success.storeStart)) continue
                // The failure fall-through immediately before the shared store must set
                // the same state register to zero. It is not a different success variable.
                val failedState = image.word(success.storeStart - 4)?.let(A64::movWImmediate)
                if (failedState != (success.stateRegister to 0)) continue
                val loops = logLoops(image, refs, firstCall + 8, context.pc)
                if (loops.size == 1) result += Cluster(branchPc, loops[0].first, loops[0].second, secondCall + 4, success)
            }
        }
        return result
    }

    private fun successBlock(image: PeImage, target: Long): Success? {
        if (!image.isCode(target, 8)) return null
        val state = image.word(target)?.let(A64::movWImmediate) ?: return null
        if (state.second != 1 || state.first == 31) return null
        val storeStart = (1..4).firstNotNullOfOrNull { n ->
            image.word(target + n * 4)?.let { A64.bTarget(target + n * 4, it) }
        } ?: return null
        if (!image.isCode(storeStart)) return null
        for ((pc, word) in scan(image, storeStart, storeStart + 0x80)) {
            val pair = A64.stpW(word) ?: continue
            if (pair.rt != state.first || pair.rt2 != 31) continue
            val global = A64.registerAddress(image, storeStart, pc, pair.rn)?.plus(pair.offset) ?: continue
            if (!image.isWritable(global, 8)) continue
            if (!hasStateConsumer(image, global)) continue
            for (n in 1..5) {
                val call = pc + n * 4
                val callee = image.word(call)?.let { A64.blTarget(call, it) } ?: continue
                if (A64.immediateBefore(image, call, 0) != 0x43 || A64.immediateBefore(image, call, 2) != 12) continue
                if (image.isCode(callee)) return Success(target, state.first, storeStart, pc, global, callee)
            }
        }
        return null
    }

    private fun descriptor(image: PeImage, start: Long, call: Long): Descriptor? {
        val state = A64.argumentState(image, start, call)
        val address = state.registers[0] as? A64.Value.Stack ?: return null
        fun field(offset: Long, size: Int): A64.Value? = state.stack[address.offset + offset]?.takeIf { it.size >= size }?.value
        val message = field(0, 8) as? A64.Value.Address ?: return null
        val length = field(8, 2) as? A64.Value.Constant ?: return null
        val signature = field(16, 8) ?: return null
        val signatureLength = field(24, 2) as? A64.Value.Constant ?: return null
        return Descriptor(message.rva, length.value.toInt(), signature, signatureLength.value.toInt())
    }

    private fun hasStateConsumer(image: PeImage, global: Long): Boolean = A64.references(image).any { ref ->
        if (ref.address != global) return@any false
        scan(image, ref.pc + 4, ref.pc + 40).any { (pc, word) ->
            val pair = A64.ldpW(word)
            pair != null && pair.rn == ref.register && pair.offset == 0L &&
                scan(image, pc + 4, pc + 24).any { (_, next) -> A64.cmpImmediate(next) == (pair.rt to 1) }
        }
    }

    private fun logLoops(image: PeImage, refs: List<StringReference>, start: Long, end: Long): List<Pair<Long, Long>> {
        val found = ArrayList<Pair<Long, Long>>()
        for ((pc, word) in scan(image, start, end)) {
            val exit = A64.beqTarget(pc, word) ?: A64.bTarget(pc, word) ?: continue
            if (exit <= pc + 8 || exit > end) continue
            val compare = image.word(pc - 4)?.let(A64::cmpImmediate) ?: continue
            if (compare.second !in 1..256 || compare.first == 31) continue
            val copyCall = image.word(pc + 4)?.let { A64.blTarget(pc + 4, it) } ?: continue
            if (image.word(pc + 8)?.let { A64.bTarget(pc + 8, it) } != pc - 4) continue
            val logRefs = refs.filter { it.pc in (pc - 32)..pc }
            val hasMessage = logRefs.any { ref ->
                val value = image.ascii(ref.address, 256)
                value != null && value.isNotEmpty() && compare.second in value.length..(value.length + 1) &&
                    A64.byteCopyHelper(image, copyCall, compare.first, ref.register)
            }
            if (!hasMessage) continue
            // The loop's index is initialized to zero just before its source string.
            val zeroed = scan(image, maxOf(start, pc - 40), pc).any { (_, w) ->
                A64.movXZero(w) == compare.first || A64.movWImmediate(w) == (compare.first to 0)
            }
            if (zeroed) found += pc to exit
        }
        return found
    }

    private data class PartitionDecision(val edit: PlannedEdit?, val note: String)

    private fun partitionEdit(image: PeImage, refs: List<StringReference>): PartitionDecision {
        val possibilities = ArrayList<Pair<Long, Boolean>>()
        for ((value, patched) in listOf("efisp" to false, "jfisp" to true)) {
            val bytes = (value + "\u0000").toByteArray(Charsets.UTF_16LE)
            for (offset in image.bytes.occurrences(bytes)) {
                val rva = image.offsetToRva(offset) ?: continue
                // A string's presence alone is insufficient: require code references that
                // use it as an argument to the partition-name preparation path.
                val references = refs.filter { it.address == rva }
                val loadingPath = references.filter { it.register == 0 }.any { lengthRef ->
                    val hasLengthCall = scan(image, lengthRef.pc + 4, lengthRef.pc + 32).any { (pc, w) ->
                        A64.blTarget(pc, w)?.let { image.isCode(it) } == true
                    }
                    hasLengthCall && references.filter { it.register == 2 && it.pc > lengthRef.pc && it.pc <= lengthRef.pc + 0x100 }
                        .any { copyRef ->
                            val readSuccesses = partitionReadSuccesses(image, copyRef)
                            readSuccesses.any { hasLoadImageCall(image, it) }
                        }
                }
                if (loadingPath) possibilities += rva to patched
            }
        }
        val unique = possibilities.distinct()
        // The currently installed ABL can load this output as an EFI payload. Its
        // own external loader is therefore optional; only the three state branches
        // are required. Rename solely when the entire local loading chain proves
        // one target. An absent, incomplete or ambiguous chain is preserved verbatim.
        if (unique.size != 1) return PartitionDecision(null,
            if (unique.isEmpty()) "外部 EFI 加载分区未完整匹配，已保留原名称；三处分支修补照常应用。"
            else "外部 EFI 加载分区存在 ${unique.size} 个有效目标，已跳过名称修改；三处分支修补照常应用。")
        return PartitionDecision(PlannedEdit("外部 EFI 加载分区", unique.single().first, "j".toByteArray(Charsets.UTF_16LE)),
            "已唯一确认外部 EFI 加载链，附加调整加载分区名称为 jfisp。")
    }

    private fun partitionReadSuccesses(image: PeImage, copyReference: StringReference): List<Long> {
        val copyCall = scan(image, copyReference.pc + 4, copyReference.pc + 48)
            .firstOrNull { (pc, w) -> A64.blTarget(pc, w) != null }?.first ?: return emptyList()
        val copyState = A64.argumentState(image, copyReference.pc, copyCall)
        if (copyState.registers[2] != A64.Value.Address(copyReference.address) || copyState.registers[0] !is A64.Value.Stack) return emptyList()
        val copyExit = image.word(copyCall + 4)?.let { A64.cbzX0(copyCall + 4, it) } ?: return emptyList()
        // Partition lookup prepares several output pointers and the BlockIO selection
        // flags, then checks the returned handle count before allocating/reading data.
        val lookupCall = scan(image, copyExit, copyExit + 96).firstOrNull { (pc, w) ->
            A64.blTarget(pc, w) != null && A64.immediateBefore(image, pc, 0) == 0x20e
        }?.first ?: return emptyList()
        if (scan(image, lookupCall + 4, lookupCall + 36).none { (_, w) -> A64.cmpImmediate(w)?.second == 1 }) return emptyList()
        // A successful partition read branches to a block which uses BootServices'
        // LoadImage method (+0xC8 in the ARM64 EFI_BOOT_SERVICES table).
        return scan(image, lookupCall + 20, lookupCall + 0xa0).mapNotNull { (pc, w) ->
            if (A64.blTarget(pc, w) == null) return@mapNotNull null
            val state = A64.argumentState(image, pc - 48, pc)
            if (state.registers[1] !is A64.Value.Stack || state.registers[2] != copyState.registers[0]) return@mapNotNull null
            image.word(pc + 4)?.let { A64.cbzX0(pc + 4, it) }
        }.toList()
    }

    private data class MethodCall(val load: Long, val call: Long)

    private fun c8MethodCall(image: PeImage, pc: Long, word: Int): MethodCall? {
        if (word and 0xffc00000.toInt() != 0xf9400000.toInt() || ((word ushr 10) and 0xfff) * 8 != 0xc8) return null
        val register = word and 31
        for ((at, next) in scan(image, pc + 4, pc + 32)) {
            if (next and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) {
                return if ((next ushr 5) and 31 == register) MethodCall(pc, at) else null
            }
            if (A64.blTarget(at, next) != null || A64.bTarget(at, next) != null ||
                next == 0xd65f03c0.toInt() || next and 0xff000010.toInt() == 0x54000000 ||
                next and 0x7e000000 == 0x34000000 || next and 0x7e000000 == 0x36000000) return null
            // Loading a subobject's method (LDR table,[object,#C8]; LDR fn,[table,#K])
            // is not a call through method C8. Likewise scalar address/arithmetic/move
            // writes invalidate the previously loaded function pointer.
            val destination = when {
                next and 0x3b400000 == 0x39400000 -> next and 31
                A64.adrp(at, next) != null -> A64.adrp(at, next)!!.first
                A64.adr(at, next) != null -> A64.adr(at, next)!!.first
                A64.add(next) != null -> A64.add(next)!!.rd
                A64.movWImmediate(next) != null -> A64.movWImmediate(next)!!.first
                A64.movXZero(next) != null -> A64.movXZero(next)
                next and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt() -> next and 31
                else -> null
            }
            if (destination == register) return null
        }
        return null
    }

    private fun hasLoadImageCall(image: PeImage, start: Long): Boolean = scan(image, start, start + 0x140).any { (pc, word) ->
        val method = c8MethodCall(image, pc, word) ?: return@any false
        val state = A64.argumentState(image, start, method.call)
        val policy = state.registers[0] as? A64.Value.Constant
        policy != null && policy.value in 0L..1L && state.registers[2] == A64.Value.Constant(0) && state.registers[5] is A64.Value.Stack
    }

    private fun scan(image: PeImage, start: Long, end: Long): Sequence<Pair<Long, Int>> = sequence {
        var pc = (start + 3) and -4L
        while (pc < end) {
            if (image.isCode(pc)) image.word(pc)?.let { yield(pc to it) }
            pc += 4
        }
    }
}
