package net.hawyn.ablefisp.patch

/**
 * Command-scoped ordinary unlock policy. The real DeviceInfo write is retained;
 * a successful USB response is not substituted for a failed protocol request.
 * Secure-world key policy and the OEMINFO authentication source are external.
 */
internal object FlashingUnlockPatcher {
    const val FRONT_STUB_BYTES = 16
    const val TRANSACTION_STUB_BYTES = 104

    data class Plan(
        val frontGateRva: Long,
        val transactionGateRva: Long,
        val abilityGateRva: Long,
        val requestedRegister: Int,
        val differentRva: Long,
        val allowedRva: Long,
        val typeRegister: Int,
        val targetRegister: Int,
        val originalTypeTargetRva: Long,
        val deviceInfoRva: Long,
        val normalFieldOffset: Int,
        val writeBackendRva: Long,
        val deviceInfoBytes: Int,
        val statusRegister: Int,
        val epilogueRva: Long,
        val existingFrontStubRva: Long? = null,
        val existingTransactionStubRva: Long? = null,
        val criticalFieldOffset: Int = normalFieldOffset + 1,
    ) {
        val notes: List<String> get() = listOf(
            "按 flashing unlock/lock 命令注册、目标参数、共享门控、普通状态字段及 VB 写协议关系定位。",
            "普通 unlock 在确认后提交原 DeviceInfo 写接口，再独立 READ_CONFIG 到临时缓冲区回读实际标志；不使用展示值或 ABL 缓存确认成功。",
            "普通 unlock 跳过该事务内的 Keymaster reset 与 recovery wipe 请求；lock/critical 重放原流程。",
            "协议写入失败保留真实错误；标准 DeviceInfo 提交不等于已证明修改 HONOR OEMINFO 认证锁，也不保证外部密钥策略保留原 data 可用性。",
        )
    }

    private data class Function(val start: Long, val end: Long)
    private data class Front(val gate: Long, val requested: Int, val different: Long,
                             val ability: Long, val allowed: Long, val stub: Long?)
    private data class Transaction(val gate: Long, val type: Int, val target: Int,
                                   val criticalTarget: Long, val ordinary: Long, val critical: Long,
                                   val status: Int, val epilogue: Long, val stub: Long?)
    private data class Setter(val function: Function, val buffer: Long, val offset: Int, val backend: Long)
    private data class RestoredFront(val original: Int, val register: Int, val different: Long)
    private data class RestoredTransaction(val original: Int)

    private class Index(val image: PeImage) {
        val code = image.instructions().toList()
        val starts = code.filter { it.second in setOf(0xd503233f.toInt(), 0xd503237f.toInt()) }.map { it.first }
        val refs = A64.references(image)
        private val flows = hashMapOf<Function, A64ValueOrigins>()
        fun function(pc: Long): Function? {
            val first = starts.lastOrNull { it <= pc } ?: return null
            val end = starts.firstOrNull { it > pc } ?: return null
            return Function(first, end)
        }
        fun body(f: Function) = code.filter { it.first in f.start..<f.end }
        fun flow(f: Function) = flows.getOrPut(f) { A64ValueOrigins(image, f.start, f.end) }
        fun entry(pc: Long) = function(pc)?.takeIf { it.start == pc }
    }

    /** No firmware identifiers, model table, fixed RVA or register-number identity table. */
    fun analyze(image: PeImage): Plan? {
        val raw = Index(image)
        val unlock = commandHandlers(image, "flashing unlock")
        val lock = commandHandlers(image, "flashing lock")
        if (unlock.isEmpty() && lock.isEmpty()) {
            if (image.bytes.occurrences("flashing unlock\u0000".toByteArray(Charsets.US_ASCII)).isNotEmpty()) incomplete()
            return null
        }
        if (unlock.size != 1 || lock.size != 1) incomplete()
        val dispatchers = wrapperTargets(raw, unlock.single(), 1).intersect(wrapperTargets(raw, lock.single(), 0))
        if (dispatchers.size != 1) incomplete()
        val dispatcher = raw.entry(dispatchers.single()) ?: incomplete()
        val restored = image.bytes.copyOf()
        val frontStubs = hashMapOf<Long, Pair<Long, RestoredFront>>()
        val transactionStubs = hashMapOf<Long, Pair<Long, RestoredTransaction>>()
        for ((pc, word) in raw.body(dispatcher)) {
            val stub = A64.bTarget(pc, word) ?: continue
            val decoded = decodeFront(image, pc, stub) ?: continue
            frontStubs[pc] = stub to decoded
            restored.putWord(requireNotNull(image.rvaToOffset(pc, 4)), decoded.original)
        }
        // The original wipe string is retained as identity evidence; only the
        // ordinary unlock transaction will leave before those original effects.
        val wipes = image.bytes.occurrences("recovery\n--wipe_data\n".toByteArray(Charsets.US_ASCII))
            .mapNotNull(image::offsetToRva).toSet()
        val transactions = raw.refs.filter { it.address in wipes }.mapNotNull { raw.function(it.pc) }.distinct()
        if (transactions.isEmpty()) incomplete()
        for (owner in transactions) for ((pc, word) in raw.body(owner)) {
            val stub = A64.bTarget(pc, word) ?: continue
            val decoded = decodeTransaction(image, pc, stub) ?: continue
            transactionStubs[pc] = stub to decoded
            restored.putWord(requireNotNull(image.rvaToOffset(pc, 4)), decoded.original)
        }
        val index = Index(PeImage.parse(restored))
        val fronts = discoverFront(index, dispatcher, frontStubs).distinct()
        if (fronts.size != 1) incomplete()
        val front = fronts.single()
        val results = arrayListOf<Plan>()
        for (owner in transactions) {
            if (!hasDispatchCall(index, dispatcher, owner.start)) continue
            for (tx in discoverTransaction(index, owner, transactionStubs)) {
                val normal = setter(index, tx.ordinary) ?: continue
                val critical = setter(index, tx.critical) ?: continue
                if (normal.buffer != critical.buffer || critical.offset == normal.offset ||
                    normal.backend != critical.backend || !isWriteBackend(index, normal.backend)) continue
                val bytes = backendBytes(index, normal.backend) ?: continue
                if (bytes <= normal.offset || bytes <= critical.offset || bytes > 4095) continue
                if (!hasResetAndWipe(index, owner, tx, wipes)) continue
                val ignored = listOfNotNull(front.stub?.let { it until it + FRONT_STUB_BYTES },
                    tx.stub?.let { it until it + TRANSACTION_STUB_BYTES })
                if (!externalEntriesSafe(index, dispatcher, ignored) || !externalEntriesSafe(index, owner, ignored)) continue
                results += Plan(front.gate, tx.gate, front.ability, front.requested, front.different, front.allowed,
                    tx.type, tx.target, tx.criticalTarget, normal.buffer, normal.offset, normal.backend, bytes,
                    tx.status, tx.epilogue, front.stub, tx.stub, critical.offset)
            }
        }
        val unique = results.distinct()
        if (unique.size != 1) throw PatchException(
            "flashing unlock：命令门控、普通状态写入与无清数据事务关系未唯一匹配，本次未生成镜像。",
            if (unique.size > 1) PatchFailureKind.AMBIGUOUS_CORE_FEATURE else PatchFailureKind.INCOMPLETE_CORE_FEATURE,
        )
        val plan = unique.single()
        // Existing bytes must describe exactly this re-proved plan, not merely
        // resemble the beginning of an adapter.
        plan.existingFrontStubRva?.let { stub ->
            if (!same(image, stub, frontCode(plan, stub))) incomplete()
        }
        plan.existingTransactionStubRva?.let { stub ->
            if (!same(image, stub, transactionCode(plan, stub))) incomplete()
        }
        return plan
    }

    fun edits(plan: Plan, frontStubRva: Long, transactionStubRva: Long): List<PlannedEdit> = listOf(
        PlannedEdit("flashing unlock：普通解锁前端门控", plan.frontGateRva, word(A64.branch(plan.frontGateRva, frontStubRva))),
        PlannedEdit("flashing unlock：普通解锁允许路径", plan.abilityGateRva, word(A64.branch(plan.abilityGateRva, plan.allowedRva))),
        PlannedEdit("flashing unlock：普通解锁事务分流", plan.transactionGateRva, word(A64.branch(plan.transactionGateRva, transactionStubRva))),
        PlannedEdit("flashing unlock：保留其他操作的前端条件", frontStubRva, frontCode(plan, frontStubRva)),
        PlannedEdit("flashing unlock：原后端提交且不请求清数据", transactionStubRva, transactionCode(plan, transactionStubRva)),
    )

    private fun frontCode(p: Plan, stub: Long): ByteArray = words(intArrayOf(
        cb(stub, stub + 8, p.requestedRegister, true),
        conditional(stub + 4, stub + 12, 0),
        A64.branch(stub + 8, p.differentRva),
        A64.branch(stub + 12, p.frontGateRva + 4),
    ))

    private fun transactionCode(p: Plan, stub: Long): ByteArray {
        require(p.typeRegister in 19..29 && p.targetRegister in 19..29 && p.typeRegister != p.targetRegister)
        require(p.statusRegister in 19..29 && p.normalFieldOffset in 0..<p.deviceInfoBytes)
        require(p.deviceInfoBytes in 16..4095)
        val frameBytes = (p.deviceInfoBytes + 15) and -16
        val frameImmediate = if (frameBytes == 4096) 0x400400 else frameBytes shl 10
        return words(intArrayOf(
            cb(stub, stub + 92, p.typeRegister, true),
            cb(stub + 4, stub + 92, p.targetRegister, false),
            adrp(stub + 8, p.deviceInfoRva, 1),
            0x91000021.toInt() or ((p.deviceInfoRva.toInt() and 4095) shl 10),
            0x39000020 or p.targetRegister or (p.normalFieldOffset shl 10),
            0x52800020, // Standard VerifiedBoot WRITE_CONFIG operation.
            A64.branch(stub + 24, p.writeBackendRva) or 0x80000000.toInt(),
            0xaa0003e0.toInt() or p.statusRegister,
            cb(stub + 32, stub + 88, 0, true, wide = true),
            0xd10003ff.toInt() or frameImmediate, // Temporary, aligned read buffer.
            0x390003ff or (p.normalFieldOffset shl 10), // Never accept an unfilled old stack byte.
            0x910003e1.toInt(), // ADD X1,SP,#0
            0x2a1f03e0, // READ_CONFIG operation0
            A64.branch(stub + 52, p.writeBackendRva) or 0x80000000.toInt(),
            0xaa0003e0.toInt() or p.statusRegister,
            cb(stub + 60, stub + 84, 0, true, wide = true),
            0x394003e0 or (p.normalFieldOffset shl 10),
            0x7100041f, // CMP W0,#1
            conditional(stub + 72, stub + 84, 0),
            0xd28000e0.toInt() or p.statusRegister,
            0xf2f00000.toInt() or p.statusRegister, // EFI_DEVICE_ERROR, 0x8000000000000007.
            0x910003ff.toInt() or frameImmediate,
            A64.branch(stub + 88, p.epilogueRva),
            conditional(stub + 92, stub + 100, 0),
            A64.branch(stub + 96, p.transactionGateRva + 4),
            A64.branch(stub + 100, p.originalTypeTargetRva),
        ))
    }

    private fun discoverFront(index: Index, owner: Function,
                              existing: Map<Long, Pair<Long, RestoredFront>>): List<Front> {
        val image = index.image; val flow = index.flow(owner); val found = arrayListOf<Front>()
        if (index.body(owner).any { isBr(it.second) }) return emptyList()
        for ((pc, word) in index.body(owner)) {
            if (word and 0xff00001f.toInt() != 0x54000001) continue
            val different = conditionalTarget(pc, word)
            if (different !in owner.start..<owner.end || different <= pc) continue
            val compare = image.word(pc - 4) ?: continue
            // CMP Wcurrent,Wrequested,UXTB is the effective-state comparison.
            if (compare and 0xffe0fc1f.toInt() != 0x6b20001f) continue
            val requestedAtCompare = (compare ushr 16) and 31
            if (!flow.derivesFromEntry(pc - 4, requestedAtCompare, 0) || !flow.hasOnlyLinearEntry(pc - 4, pc)) continue
            val test = image.word(different) ?: continue
            if (test and 0xfffffc1f.toInt() != 0x72001c1f) continue // TST Wrequested,#0xff
            val requested = (test ushr 5) and 31
            if (requested !in 19..29 || !flow.derivesFromEntry(pc, requested, 0) ||
                !flow.derivesFromEntry(different, requested, 0)) continue
            val allowed = image.word(different + 4)?.let { A64.beqTarget(different + 4, it) } ?: continue
            if (allowed !in different + 8..<owner.end) continue
            // The ability decision is reached only after the requested!=0 edge.
            for (gate in different + 8 until allowed step 4) {
                val raw = image.word(gate) ?: continue
                val target = cbTarget(gate, raw, nonzero = true) ?: A64.bTarget(gate, raw) ?: continue
                if (target != allowed) continue
                val load = image.word(gate - 4) ?: continue
                if (load and 0xffc00000.toInt() != 0xb9400000.toInt()) continue
                val ability = load and 31; val base = (load ushr 5) and 31
                if (cbTarget(gate, raw, true) != null && raw and 31 != ability) continue
                val address = flow.globalAddress(gate - 4, base)?.plus(((load ushr 10) and 0xfff) * 4L) ?: continue
                if (!image.isWritable(address, 4) || !flow.hasOnlyLinearEntry(different, gate)) continue
                if (!sameFrontFailure(index, owner, pc + 4, different, gate + 4, allowed)) continue
                val saved = existing[pc]
                if (saved != null && (saved.second.register != requested || saved.second.different != different)) continue
                found += Front(pc, requested, different, gate, allowed, saved?.first)
            }
        }
        return found
    }

    private fun sameFrontFailure(index: Index, owner: Function, first: Long, firstEnd: Long,
                                 second: Long, secondEnd: Long): Boolean {
        val image = index.image
        val firstCalls = index.body(owner).filter { it.first in first..<firstEnd }
            .mapNotNull { (pc, word) -> A64.blTarget(pc, word) ?: A64.bTarget(pc, word) }.toSet()
        val secondTail = index.body(owner).filter { it.first in second..<secondEnd }
            .mapNotNull { (pc, word) -> A64.bTarget(pc, word) }.toSet()
        // Both original denial paths terminate through the same FAIL transport.
        return secondTail.any { it in firstCalls && image.isCode(it) }
    }

    private fun discoverTransaction(index: Index, owner: Function,
                                    existing: Map<Long, Pair<Long, RestoredTransaction>>): List<Transaction> {
        val image = index.image; val body = index.body(owner); val flow = index.flow(owner)
        val result = arrayListOf<Transaction>()
        if (body.any { isBr(it.second) }) return result
        for ((gate, raw) in body) {
            val criticalTarget = A64.beqTarget(gate, raw) ?: continue
            val compare = body.lastOrNull { (pc, w) -> pc < gate && A64.cmpImmediate(w)?.second == 1 &&
                StraightLineLiveness.preserves(image, pc + 4, gate, flags = true) } ?: continue
            val compared = requireNotNull(A64.cmpImmediate(compare.second)).first
            if (!flow.derivesFromEntry(compare.first, compared, 0) || !flow.hasOnlyLinearEntry(compare.first, gate)) continue
            val guard = image.word(gate + 4) ?: continue
            if (cbTarget(gate + 4, guard, true) == null) continue
            val type = guard and 31
            if (type !in 19..29 || !flow.derivesFromEntry(gate, type, 0)) continue
            val ordinaryCall = firstDirectCall(index, owner, gate + 8) ?: continue
            val criticalCall = firstDirectCall(index, owner, criticalTarget) ?: continue
            val normal = A64.blTarget(ordinaryCall, requireNotNull(image.word(ordinaryCall))) ?: continue
            val critical = A64.blTarget(criticalCall, requireNotNull(image.word(criticalCall))) ?: continue
            if (normal == critical || !flow.derivesFromEntry(ordinaryCall, 0, 1) ||
                !flow.derivesFromEntry(criticalCall, 0, 1)) continue
            val target = (19..29).filter { it != type && flow.derivesFromEntry(gate, it, 1) }.singleOrNull() ?: continue
            val join = image.word(ordinaryCall + 4)?.let { A64.bTarget(ordinaryCall + 4, it) } ?: continue
            if (join != criticalCall + 4) continue
            val move = image.word(join) ?: continue
            if (move and 0xffffffe0.toInt() != 0xaa0003e0.toInt()) continue // MOV Xstatus,X0
            val status = move and 31
            if (status !in 19..29) continue
            val epilogue = image.word(join + 4)?.let { cbTarget(join + 4, it, true, wide = true) } ?: continue
            if (!returnsStatus(index, owner, epilogue, status)) continue
            result += Transaction(gate, type, target, criticalTarget, normal, critical, status, epilogue, existing[gate]?.first)
        }
        return result
    }

    private fun setter(index: Index, entry: Long): Setter? {
        val owner = index.entry(entry) ?: return null
        val image = index.image; val flow = index.flow(owner); val found = arrayListOf<Setter>()
        for ((store, raw) in index.body(owner)) {
            if (raw and 0xffc00000.toInt() != 0x39000000) continue
            val source = raw and 31; val base = (raw ushr 5) and 31; val offset = (raw ushr 10) and 0xfff
            if (!flow.derivesFromEntry(store, source, 0)) continue
            val global = flow.globalAddress(store, base) ?: continue
            if (!image.isWritable(global, offset + 1)) continue
            val call = firstDirectCall(index, owner, store + 4) ?: continue
            if (A64.immediateBefore(image, call, 0) != 1 || flow.globalAddress(call, 1) != global) continue
            if (!flow.hasOnlyLinearEntry(store, call)) continue
            val backend = A64.blTarget(call, requireNotNull(image.word(call))) ?: continue
            found += Setter(owner, global, offset, backend)
        }
        return found.singleOrNull()
    }

    private fun isWriteBackend(index: Index, entry: Long): Boolean = backendBytes(index, entry) != null

    private fun backendBytes(index: Index, entry: Long): Int? {
        val owner = index.entry(entry) ?: return null
        if (!hasVerifiedBootGuid(index, owner)) return null
        val image = index.image; val flow = index.flow(owner)
        val sizes = index.body(owner).mapNotNull { (pc, word) ->
            if (!isBlr(word) || !flow.derivesFromEntry(pc, 1, 0) || !flow.derivesFromEntry(pc, 2, 1)) return@mapNotNull null
            val pointer = (word ushr 5) and 31
            val load = image.word(pc - 4) ?: return@mapNotNull null
            if (load and -32 != 0xf9400400.toInt() || load and 31 != pointer) return@mapNotNull null
            A64.immediateBefore(image, pc, 3)?.takeIf { it in 16..4095 }
        }.distinct()
        return sizes.singleOrNull()
    }

    private fun hasResetAndWipe(index: Index, owner: Function, tx: Transaction, wipes: Set<Long>): Boolean {
        if (index.refs.none { it.pc in tx.gate + 4..<owner.end && it.address in wipes }) return false
        val image = index.image
        val reset = index.body(owner).mapNotNull { (pc, raw) ->
            A64.blTarget(pc, raw)?.let { target -> (index.entry(target)?.let { pc to it }) }
        }.filter { (pc, f) -> pc > tx.gate && hasVerifiedBootGuid(index, f) &&
            index.body(f).any { (_, word) -> word and 0xfffffc00.toInt() == 0xf9401800.toInt() } }
        return reset.size == 1 && index.body(owner).any { (pc, raw) ->
            pc > reset.single().first && A64.blTarget(pc, raw) != null && A64.immediateBefore(image, pc, 2) == 0x800
        }
    }

    private fun hasVerifiedBootGuid(index: Index, owner: Function): Boolean {
        val guid = byteArrayOf(0x91.toByte(), 0xff.toByte(), 0x5e, 0x8e.toByte(), 0xb6.toByte(), 0x21,
            0xd3.toByte(), 0x47, 0xaf.toByte(), 0x2b, 0xc1.toByte(), 0x5a, 0x01, 0xe0.toByte(), 0x20, 0xec.toByte())
        val addresses = index.image.bytes.occurrences(guid).mapNotNull(index.image::offsetToRva).toSet()
        return index.refs.any { it.pc in owner.start..<owner.end && it.address in addresses }
    }

    private fun hasDispatchCall(index: Index, owner: Function, transaction: Long): Boolean {
        val flow = index.flow(owner)
        return index.body(owner).any { (pc, raw) -> A64.blTarget(pc, raw) == transaction &&
            flow.origins(pc, 0) == setOf(A64ValueOrigin.Zero) && flow.derivesFromEntry(pc, 1, 0) }
    }

    private fun returnsStatus(index: Index, owner: Function, entry: Long, register: Int): Boolean {
        if (entry !in owner.start..<owner.end) return false
        val image = index.image
        var defined = false
        val failEdges = arrayListOf<Long>()
        for (pc in entry until minOf(owner.end, entry + 256) step 4) {
            val raw = image.word(pc) ?: return false
            if (raw == 0xd65f03c0.toInt()) return defined && failEdges.all { target ->
                target == owner.end - 4 && image.word(target)?.let { A64.blTarget(target, it) } != null
            }
            A64.blTarget(pc, raw)?.let { target ->
                // Only a bounded, no-store leaf may run before the unchanged
                // stack restoration. Reset and BCB wrappers are not such leaves.
                if (!pureLeaf(image, target, register, preserveResult = defined)) return false
                return@let
            } ?: run {
                if (raw and 0xff00001f.toInt() == 0x54000001) {
                    failEdges += conditionalTarget(pc, raw)
                } else if (isTransfer(pc, raw) || isStore(raw)) return false
                else if (raw == (0xaa0003e0.toInt() or (register shl 16))) {
                    if (defined) return false
                    defined = true
                }
                else {
                    if (!defined && StraightLineLiveness.mayWriteRegister(raw, register)) return false
                    if (defined && raw !in setOf(0xd50323bf.toInt(), 0xd50323ff.toInt()) &&
                        StraightLineLiveness.mayWriteRegister(raw, 0)) return false
                    val memory = raw and 0x3b000000 in setOf(0x39000000, 0x38000000) || raw and 0x3a000000 == 0x28000000
                    if (memory && ((raw ushr 5) and 31 != 31 || !defined && writesSp(raw))) return false
                    val known = A64.adrp(pc, raw) != null || A64.adr(pc, raw) != null || A64.add(raw) != null || memory ||
                        raw in setOf(0xd503201f.toInt(), 0xd50323bf.toInt(), 0xd50323ff.toInt())
                    if (!known) return false
                }
            }
        }
        return false
    }

    private fun pureLeaf(image: PeImage, entry: Long, preserved: Int, preserveResult: Boolean): Boolean {
        for (pc in entry until entry + 128 step 4) {
            val word = image.word(pc) ?: return false
            if (word == 0xd65f03c0.toInt()) return true
            if (word in setOf(0xd503245f.toInt(), 0xd503249f.toInt(), 0xd50324df.toInt(), 0xd503201f.toInt())) continue
            if (isTransfer(pc, word) || isStore(word) || writesSp(word) ||
                StraightLineLiveness.mayWriteRegister(word, preserved) ||
                preserveResult && StraightLineLiveness.mayWriteRegister(word, 0)) return false
        }
        return false
    }

    private fun isStore(word: Int): Boolean = word and 0x3a400000 == 0x28000000 ||
        word and 0x3b000000 in setOf(0x39000000, 0x38000000) && (word ushr 22) and 3 == 0

    private fun writesSp(word: Int): Boolean = word and 31 == 31 && word and 0x20000000 == 0 &&
        word and 0x1f000000 == 0x11000000 || (word ushr 5) and 31 == 31 &&
        (word and 0x3a000000 == 0x28000000 && (word ushr 23) and 3 in setOf(1, 3) ||
            word and 0x3b200000 == 0x38000000 && (word ushr 10) and 3 in setOf(1, 3))

    private fun firstDirectCall(index: Index, owner: Function, start: Long): Long? {
        for (pc in start until owner.end step 4) {
            val word = index.image.word(pc) ?: return null
            if (A64.blTarget(pc, word) != null) return pc
            if (isTransfer(pc, word)) return null
        }
        return null
    }

    private fun commandHandlers(image: PeImage, name: String): Set<Long> {
        val names = image.bytes.occurrences((name + '\u0000').toByteArray(Charsets.US_ASCII))
            .mapNotNull(image::offsetToRva).toSet()
        val result = hashSetOf<Long>()
        for (at in 0..image.bytes.size - 16 step 4) {
            if (pointer(image, image.bytes.u64(at)) !in names) continue
            val entry = pointer(image, image.bytes.u64(at + 8)) ?: continue
            if (image.isCode(entry) && image.word(entry) in setOf(0xd503233f.toInt(), 0xd503237f.toInt())) result += entry
        }
        return result
    }

    private fun wrapperTargets(index: Index, entry: Long, value: Int): Set<Long> {
        val owner = index.entry(entry) ?: return emptySet()
        val flow = index.flow(owner)
        return index.body(owner).mapNotNull { (pc, raw) ->
            val target = A64.bTarget(pc, raw) ?: A64.blTarget(pc, raw) ?: return@mapNotNull null
            if (target in owner.start..<owner.end || constantBefore(index.image, owner, pc, 0) != value) return@mapNotNull null
            val definition = (pc - 4 downTo owner.start step 4).firstOrNull {
                index.image.word(it)?.let(A64::movWImmediate)?.first == 0
            } ?: return@mapNotNull null
            if (!flow.hasOnlyLinearEntry(definition, pc)) return@mapNotNull null
            target.takeIf { index.entry(it) != null }
        }.toSet()
    }

    private fun constantBefore(image: PeImage, owner: Function, before: Long, register: Int): Int? {
        for (pc in before - 4 downTo owner.start step 4) {
            val word = image.word(pc) ?: return null
            if (isTransfer(pc, word)) return null
            A64.movWImmediate(word)?.let { if (it.first == register) return it.second }
            if (word in setOf(0xd50323bf.toInt(), 0xd50323ff.toInt(), 0xd503245f.toInt(), 0xd503249f.toInt(), 0xd50324df.toInt())) continue
            if (StraightLineLiveness.mayWriteRegister(word, register)) return null
        }
        return null
    }

    private fun externalEntriesSafe(index: Index, owner: Function, ignored: List<LongRange>): Boolean =
        index.code.none { (pc, raw) -> pc !in owner.start..<owner.end && ignored.none { pc in it } &&
            directTarget(pc, raw)?.let { it in owner.start + 4..<owner.end } == true }

    private fun decodeFront(image: PeImage, site: Long, stub: Long): RestoredFront? {
        if (!image.isCode(stub, FRONT_STUB_BYTES)) return null
        val first = image.word(stub) ?: return null
        if (cbTarget(stub, first, true) != stub + 8) return null
        val second = image.word(stub + 4) ?: return null
        val different = image.word(stub + 8)?.let { A64.bTarget(stub + 8, it) } ?: return null
        if (A64.beqTarget(stub + 4, second) != stub + 12 ||
            image.word(stub + 12)?.let { A64.bTarget(stub + 12, it) } != site + 4) return null
        return RestoredFront(conditional(site, different, 1), first and 31, different)
    }

    private fun decodeTransaction(image: PeImage, site: Long, stub: Long): RestoredTransaction? {
        if (!image.isCode(stub, TRANSACTION_STUB_BYTES)) return null
        if (image.word(stub)?.let { cbTarget(stub, it, true) } != stub + 92 ||
            image.word(stub + 4)?.let { cbTarget(stub + 4, it, false) } != stub + 92 ||
            image.word(stub + 96)?.let { A64.bTarget(stub + 96, it) } != site + 4) return null
        val conditionalWord = image.word(stub + 92) ?: return null
        if (A64.beqTarget(stub + 92, conditionalWord) != stub + 100) return null
        val target = image.word(stub + 100)?.let { A64.bTarget(stub + 100, it) } ?: return null
        return RestoredTransaction(conditional(site, target, 0))
    }

    private fun pointer(image: PeImage, value: Long): Long? {
        val raw = value.toULong(); val base = image.imageBase.toULong()
        if (raw < base || raw - base > 0xffff_ffffUL) return null
        return (raw - base).toLong().takeIf { image.rvaToOffset(it) != null }
    }
    private fun same(image: PeImage, rva: Long, bytes: ByteArray): Boolean = image.rvaToOffset(rva, bytes.size)?.let {
        image.bytes.copyOfRange(it, it + bytes.size).contentEquals(bytes)
    } == true
    private fun word(value: Int) = ByteArray(4).also { it.putWord(0, value) }
    private fun words(values: IntArray) = ByteArray(values.size * 4).also { bytes -> values.forEachIndexed { i, w -> bytes.putWord(i * 4, w) } }
    private fun cb(pc: Long, target: Long, register: Int, nonzero: Boolean, wide: Boolean = false): Int {
        val d = target - pc
        if (register !in 0..30 || d and 3 != 0L || d < -0x100000 || d >= 0x100000) incomplete()
        return (if (wide) 0xb4000000.toInt() else 0x34000000) or (if (nonzero) 0x01000000 else 0) or
            (((d shr 2).toInt() and 0x7ffff) shl 5) or register
    }
    private fun conditional(pc: Long, target: Long, condition: Int): Int {
        val d = target - pc
        if (d and 3 != 0L || d < -0x100000 || d >= 0x100000) incomplete()
        return 0x54000000 or (((d shr 2).toInt() and 0x7ffff) shl 5) or condition
    }
    private fun adrp(pc: Long, address: Long, register: Int): Int {
        val pages = ((address and -4096L) - (pc and -4096L)) shr 12
        if (pages < -0x100000 || pages >= 0x100000) incomplete()
        return 0x90000000.toInt() or ((pages.toInt() and 3) shl 29) or (((pages shr 2).toInt() and 0x7ffff) shl 5) or register
    }
    private fun cbTarget(pc: Long, word: Int, nonzero: Boolean, wide: Boolean = false): Long? =
        if (word and 0xff000000.toInt() == ((if (wide) 0xb4000000.toInt() else 0x34000000) or (if (nonzero) 0x01000000 else 0)))
            pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2) else null
    private fun conditionalTarget(pc: Long, word: Int) = pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
    private fun isBr(word: Int) = word and 0xfffffc1f.toInt() == 0xd61f0000.toInt()
    private fun isBlr(word: Int) = word and 0xfffffc1f.toInt() == 0xd63f0000.toInt()
    private fun isConditional(word: Int) = word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 in setOf(0x34000000, 0x36000000)
    private fun directTarget(pc: Long, word: Int): Long? = A64.blTarget(pc, word) ?: A64.bTarget(pc, word) ?:
        if (isConditional(word)) pc + (signExtend(((word ushr 5) and if (word and 0x7e000000 == 0x36000000) 0x3fff else 0x7ffff).toLong(),
            if (word and 0x7e000000 == 0x36000000) 14 else 19) shl 2) else null
    private fun isTransfer(pc: Long, word: Int) = directTarget(pc, word) != null || isBlr(word) || isBr(word) ||
        word and 0xfffffc1f.toInt() == 0xd65f0000.toInt() || word in setOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt())
    private fun incomplete(): Nothing = throw PatchException(
        "flashing unlock：已发现命令或事务证据，但功能关系未完整匹配，本次未生成镜像。", PatchFailureKind.INCOMPLETE_CORE_FEATURE)
}
