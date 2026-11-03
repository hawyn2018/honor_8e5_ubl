package net.hawyn.ablefisp.patch

/**
 * Fix this shared HashDescriptor comparison's ABI result to zero, after native
 * reads, digest construction and descriptor-length checks. Every descriptor/
 * partition entering this flow uses its original CBZ and continuation. No
 * generic comparison implementation, logger, metadata or no-digest path changes.
 */
internal object AvbHashDescriptorPatcher {
    data class Plan(
        val compareCallRva: Long,
        val compareHelperRva: Long?,
        val comparisonBranchRva: Long,
        val matchJoinRva: Long,
        val resultRegister: Int,
        val mismatchLogCallRva: Long,
        val digestLengthGateRva: Long,
        val noDigestBranchRva: Long,
        val digestLengthRegister: Int,
        val computedDigestStackOffset: Int,
        val alreadyApplied: Boolean,
        val comparisonResultRegister: Int = 0,
    )

    private data class Function(val start: Long, val end: Long)
    private data class Memory(val register: Int, val base: Int, val offset: Int, val bytes: Int, val load: Boolean)
    private data class Join(val result: Int, val data: Long, val loadedStack: Int)
    private data class Length(val gate: Long, val noDigest: Long)
    private const val MESSAGE = "Hash of data does not match digest in descriptor."
    private const val MOV_W0_ZERO = 0x52800000

    fun analyze(image: PeImage): List<Plan> {
        val occurrences = image.bytes.occurrences(MESSAGE.toByteArray(Charsets.US_ASCII))
        if (occurrences.isEmpty()) return emptyList()
        val anchors = occurrences.flatMap { at ->
            val starts = listOf(at, at - 2).filter { it >= 0 }
            starts.filter { start -> image.bytes.asciiZ(start, MESSAGE.length + 8)?.let {
                it.trimEnd('\r', '\n').removePrefix(": ") == MESSAGE
            } == true }.mapNotNull(image::offsetToRva)
        }.toSet()
        val code = image.instructions().toList()
        val starts = code.filter { it.second in listOf(0xd503233f.toInt(), 0xd503237f.toInt()) }.map { it.first }
        fun function(pc: Long): Function? {
            val start = starts.lastOrNull { it <= pc } ?: return null
            val end = starts.firstOrNull { it > pc } ?: return null
            return Function(start, end)
        }
        val refs = A64.references(image).filter { it.address in anchors }
        val plans = ArrayList<Plan>()
        var incomplete = refs.isEmpty()
        var ambiguous = false
        for (ref in refs) {
            val owner = function(ref.pc)
            if (owner == null) { incomplete = true; continue }
            val logCalls = (ref.pc + 4..<owner.end step 4).filter { pc ->
                A64.blTarget(pc, image.word(pc) ?: 0) != null && linear(image, ref.pc, pc) &&
                    A64.argumentState(image, ref.pc, pc).registers.values.any { it == A64.Value.Address(ref.address) }
            }
            val logCall = logCalls.singleOrNull()
            if (logCall == null) {
                incomplete = true; continue
            }
            val candidates = ArrayList<Plan>()
            for (branch in owner.start..<logCall step 4) {
                val raw = image.word(branch) ?: continue
                if (raw and 0x7f000000 != 0x34000000 || raw and 31 == 31) continue // native CBZ W/Xn
                val tested = raw and 31
                val match = branch + (signExtend(((raw ushr 5) and 0x7ffff).toLong(), 19) shl 2)
                if (match !in owner.start..<owner.end || !linear(image, branch + 4, logCall)) continue
                for (call in owner.start..<branch step 4) {
                    val original = image.word(call) ?: continue
                    val helper = A64.blTarget(call, original)
                    val legacySkip = A64.bTarget(call, original) == match
                    val zero = original == MOV_W0_ZERO || original == 0x2a1f03e0
                    if (helper == null && !legacySkip && !zero ||
                        helper != null && !isByteCompare(image, helper, function(helper))) continue
                    if (!resultFromAbiZero(image, code, call, branch, tested)) continue
                    val digest = loadedStackBefore(image, call, 0, owner.start) ?: continue
                    val length = copiedRegisterBefore(image, call, 2, owner.start) ?: continue
                    if (length !in 19..29 || !hasDigestProducer(image, owner, call, digest, length)) continue
                    val join = successJoin(image, owner, match, digest, length) ?: continue
                    if (!mismatchResult(image, logCall, join.result, owner.end)) continue
                    val guards = lengthGuard(image, owner, call, length) ?: continue
                    candidates += Plan(call, helper, branch, match, join.result, logCall,
                        guards.gate, guards.noDigest, length, digest, original == MOV_W0_ZERO, tested)
                }
            }
            val unique = candidates.distinctBy { it.compareCallRva }
            if (unique.size != 1) { incomplete = true; ambiguous = ambiguous || unique.size > 1 } else plans += unique.single()
        }
        val unique = plans.distinctBy { it.compareCallRva }
        if (incomplete || unique.size != refs.map { it.pc }.distinct().size || unique.isEmpty()) {
            throw PatchException("AVB HashDescriptor 摘要比较、长度门与成功输出路径未唯一匹配，本次未生成镜像。",
                if (ambiguous || plans.size > refs.size || plans.groupBy { it.mismatchLogCallRva }.any { it.value.size > 1 })
                    PatchFailureKind.AMBIGUOUS_CORE_FEATURE else PatchFailureKind.INCOMPLETE_CORE_FEATURE)
        }
        return unique
    }

    fun edits(plan: Plan): List<PlannedEdit> = listOf(PlannedEdit(
        "AVB HashDescriptor 数据摘要采用既有匹配成功路径", plan.compareCallRva,
        ByteArray(4).also { it.putWord(0, MOV_W0_ZERO) },
    ))

    /** Model the call slot's result as entry ABI register zero. This proves
     * copies/spills/reloads into the actual tested register, without recovering
     * a lost comparison target from an older B-to-success image. */
    private fun resultFromAbiZero(image: PeImage, code: List<Pair<Long, Int>>,
                                  call: Long, branch: Long, tested: Int): Boolean {
        if (call + 4 > branch || !linear(image, call + 4, branch)) return false
        if (code.any { (from, word) ->
                val target = directTarget(from, word) ?: A64.blTarget(from, word)
                from !in call + 4..<branch && target != null && target in call + 4..branch
            }) return false
        val flow = A64ValueOrigins(image, call + 4, branch + 4)
        return flow.derivesFromEntry(branch, tested, 0)
    }

    private fun directTarget(pc: Long, word: Int): Long? {
        A64.bTarget(pc, word)?.let { return it }
        if (word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 == 0x34000000)
            return pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
        if (word and 0x7e000000 == 0x36000000)
            return pc + (signExtend(((word ushr 5) and 0x3fff).toLong(), 14) shl 2)
        return null
    }

    private fun memory(word: Int): Memory? {
        val kind = word and 0xffc00000.toInt()
        val bytes = when (kind) { 0xf9400000.toInt(), 0xf9000000.toInt() -> 8; 0xb9400000.toInt(), 0xb9000000.toInt() -> 4; else -> return null }
        return Memory(word and 31, (word ushr 5) and 31, ((word ushr 10) and 0xfff) * bytes, bytes,
            kind == 0xf9400000.toInt() || kind == 0xb9400000.toInt())
    }

    private fun copy(word: Int): Pair<Int, Int>? =
        if (word and 0xffe0ffe0.toInt() in listOf(0xaa0003e0.toInt(), 0x2a0003e0)) (word and 31) to ((word ushr 16) and 31)
        else A64.add(word)?.takeIf { it.immediate == 0L }?.let { it.rd to it.rn }

    private fun loadedStackBefore(image: PeImage, at: Long, target: Int, low: Long): Int? {
        var register = target
        for (pc in at - 4 downTo low step 4) {
            val raw = image.word(pc) ?: return null
            val alias = copy(raw)
            if (alias?.first == register) { register = alias.second; continue }
            val load = memory(raw)
            if (load?.load == true && load.register == register) return load.offset.takeIf { load.base == 31 && load.bytes == 8 }
            if (A64.blTarget(pc, raw) != null || A64.bTarget(pc, raw) != null) return null
            if (StraightLineLiveness.mayWriteRegister(raw, register)) return null
        }
        return null
    }

    private fun copiedRegisterBefore(image: PeImage, at: Long, target: Int, low: Long): Int? {
        var register = target
        for (pc in at - 4 downTo low step 4) {
            val raw = image.word(pc) ?: return null
            val alias = copy(raw)
            if (alias?.first == register) {
                register = alias.second
                if (register in 19..29) return register
                continue
            }
            if (A64.blTarget(pc, raw) != null || A64.bTarget(pc, raw) != null || StraightLineLiveness.mayWriteRegister(raw, register)) return null
        }
        return null
    }

    private fun linear(image: PeImage, start: Long, end: Long): Boolean = (start..<end step 4).all { pc ->
        val w = image.word(pc) ?: return@all false
        A64.bTarget(pc, w) == null && w and 0xff000010.toInt() != 0x54000000 &&
            w and 0x7e000000 !in listOf(0x34000000, 0x36000000) &&
            w and 0xfffffc1f.toInt() !in listOf(0xd61f0000.toInt(), 0xd65f0000.toInt())
    }

    private fun mismatchResult(image: PeImage, call: Long, register: Int, end: Long): Boolean =
        (call + 4..end - 8 step 4).any { pc ->
            linear(image, call + 4, pc) && A64.movWImmediate(image.word(pc) ?: 0) == (register to 3) &&
                A64.bTarget(pc + 4, image.word(pc + 4) ?: 0) != null
        }

    private fun hasDigestProducer(image: PeImage, owner: Function, call: Long, stack: Int, length: Int): Boolean {
        val producers = (owner.start..<call step 4).filter { pc ->
            val m = memory(image.word(pc) ?: 0)
            m != null && !m.load && m.bytes == 8 && m.base == 31 && m.offset == stack && m.register == 0
        }
        val valid = producers.filter { pc ->
            val lastCall = (pc - 4 downTo owner.start step 4).firstOrNull {
                A64.blTarget(it, image.word(it) ?: 0) != null
            } ?: return@filter false
            if (!StraightLineLiveness.preserves(image, lastCall + 4, pc, setOf(0)) ||
                !digestSlotSurvives(image, pc + 4, call, stack)) return@filter false
            (owner.start..<call step 4).any { definition ->
                if (A64.movWImmediate(image.word(definition) ?: 0)?.let {
                        it.first == length && it.second in listOf(32, 64)
                    } != true) return@any false
                A64ValueOrigins(image, minOf(lastCall, definition), call + 4)
                    .derivesFrom(call, length, definition, length)
            }
        }.toSet()
        return valid.isNotEmpty() && digestDefinitionsBefore(image, owner, call, stack, valid)
    }

    /**
     * The finalizer's unchanged X0 is stored immediately above this window.
     * Follow the local hash/length CFG without crossing another call or frame
     * adjustment; every represented stack store must miss that 8-byte slot.
     * Error/no-digest edges leaving this window remain their original paths.
     */
    private fun digestSlotSurvives(image: PeImage, from: Long, until: Long, stack: Int): Boolean {
        val pending = ArrayDeque<Long>().also { it.add(from) }; val visited = HashSet<Long>(); var reaches = false
        while (pending.isNotEmpty()) {
            val pc = pending.removeFirst()
            if (pc == until) { reaches = true; continue }
            if (pc !in from..<until || !visited.add(pc)) continue
            val w = image.word(pc) ?: return false
            if (A64.blTarget(pc, w) != null || w and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) return false
            val jump = A64.bTarget(pc, w)
            if (jump != null) { pending.add(jump); continue }
            if (w and 0xfffffc1f.toInt() in listOf(0xd61f0000.toInt(), 0xd65f0000.toInt())) return false
            if (digestSlotHazard(w, stack)) return false
            if (w and 0xff000010.toInt() == 0x54000000 || w and 0x7e000000 == 0x34000000)
                pending.add(pc + (signExtend(((w ushr 5) and 0x7ffff).toLong(), 19) shl 2))
            else if (w and 0x7e000000 == 0x36000000)
                pending.add(pc + (signExtend(((w ushr 5) and 0x3fff).toLong(), 14) shl 2))
            pending.add(pc + 4)
        }
        return reaches
    }

    /** The descriptor dispatcher can enter this part of a large function via
     * an indirect switch. Prove the slot's actual incoming definitions backwards
     * from the comparison, rather than requiring the generic function-entry CFG
     * to resolve that unrelated dispatcher. Roots are the function entry and
     * independently proven finalizer-result stores. Every reachable direct edge
     * from these roots is retained; an uninitialised root, overlapping/unknown
     * store or frame change fails the proof. Unresolved BR targets stay unknown. */
    private fun digestDefinitionsBefore(image: PeImage, owner: Function, call: Long, stack: Int,
                                        expected: Set<Long>): Boolean {
        val predecessors = HashMap<Long, MutableList<Long>>()
        val reached = HashSet<Long>()
        val roots = ArrayDeque<Long>().also { it.add(owner.start); it.addAll(expected) }
        while (roots.isNotEmpty()) {
            val pc = roots.removeFirst()
            if (pc !in owner.start..<owner.end || !image.isCode(pc) || !reached.add(pc)) continue
            val w = image.word(pc) ?: return false
            val unconditional = A64.bTarget(pc, w)
            val terminal = w and 0xfffffc1f.toInt() in listOf(0xd61f0000.toInt(), 0xd65f0000.toInt()) ||
                w in listOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt())
            val next = when {
                unconditional != null -> listOf(unconditional)
                terminal -> emptyList()
                else -> listOfNotNull(pc + 4, directTarget(pc, w))
            }
            for (target in next.distinct()) if (target in owner.start..<owner.end) {
                predecessors.getOrPut(target) { arrayListOf() }.add(pc)
                roots.add(target)
            }
        }
        if (call !in reached) return false
        val pending = ArrayDeque<Long>().also { it.add(call) }
        val seen = HashSet<Long>(); val definitions = HashSet<Long>()
        while (pending.isNotEmpty()) {
            val pc = pending.removeFirst()
            if (!seen.add(pc)) continue
            if (pc in expected) { definitions += pc; continue }
            if (pc != call && digestSlotHazard(image.word(pc) ?: return false, stack)) return false
            val incoming = predecessors[pc].orEmpty()
            if (incoming.isEmpty()) return false
            pending.addAll(incoming)
        }
        return definitions == expected
    }

    private fun digestSlotHazard(w: Int, stack: Int): Boolean {
        val m = memory(w)
        if (m != null && !m.load && (m.base != 31 || m.offset < stack + 8 && stack < m.offset + m.bytes)) return true
        // Other memory-store forms are not silently treated as read-only.
        if (m == null && (w and 0x3b000000 in listOf(0x38000000, 0x39000000) && w and 0x00400000 == 0 ||
                w and 0x3a400000 == 0x28000000)) return true
        // Register31 is SP for these encodings, but ZR in many ordinary GP
        // writes. Keep this local; the shared liveness helper filters ZR.
        val spDestination = w and 31 == 31 && (
            w and 0x1f000000 == 0x11000000 && w and 0x20000000 == 0 ||
                w and 0x1f200000 == 0x0b200000 && w and 0x20000000 == 0 ||
                w and 0x1f800000 == 0x12000000 && (w ushr 29) and 3 != 3)
        val spWriteback = (w ushr 5) and 31 == 31 && (
            w and 0x3b200000 == 0x38000000 && (w ushr 10) and 3 in listOf(1, 3) ||
                w and 0x3a000000 == 0x28000000 && (w ushr 23) and 3 in listOf(1, 3))
        if (spDestination || spWriteback || StraightLineLiveness.mayWriteRegister(w, 31)) return true
        return false
    }

    private fun successJoin(image: PeImage, owner: Function, match: Long, digest: Int, length: Int): Join? {
        val limit = owner.end - 4
        val zero = (match..limit step 4).mapNotNull { pc ->
            A64.movWImmediate(image.word(pc) ?: 0)?.takeIf { it.second == 0 && it.first in 19..29 }?.let { pc to it.first }
        }
        val found = ArrayList<Join>()
        for ((zeroPc, result) in zero) for (loadPc in match..limit step 4) {
            val load = memory(image.word(loadPc) ?: 0) ?: continue
            if (!load.load || load.base != 31 || load.bytes != 8 ||
                !linear(image, match, maxOf(loadPc, zeroPc))) continue
            for (branchPc in maxOf(loadPc, zeroPc) + 4..limit step 4) {
                val branch = image.word(branchPc) ?: continue
                if (branch and 0xfe00001f.toInt() != (0xb4000000.toInt() or load.register)) continue
                if (load.register == result || !StraightLineLiveness.preserves(image, loadPc + 4, branchPc, setOf(load.register)) ||
                    !StraightLineLiveness.preserves(image, zeroPc + 4, branchPc, setOf(result))) continue
                val target = branchPc + (signExtend(((branch ushr 5) and 0x7ffff).toLong(), 19) shl 2)
                val nonempty = if (branch and 0x01000000 != 0) target else branchPc + 4
                val empty = if (branch and 0x01000000 != 0) branchPc + 4 else target
                if (nonempty !in owner.start..<owner.end || empty !in owner.start..<owner.end ||
                    !outputBookkeeping(image, nonempty, owner, result, digest, length, load.offset) ||
                    outputBookkeeping(image, empty, owner, result, digest, length, load.offset)) continue
                found += Join(result, nonempty, load.offset)
            }
        }
        return found.distinct().singleOrNull()
    }

    private fun outputBookkeeping(image: PeImage, start: Long, owner: Function, result: Int, digest: Int, length: Int, loaded: Int): Boolean {
        val end = owner.end - 4
        val reached = HashSet<Long>(); val pending = ArrayDeque<Long>().also { it.add(start) }
        while (pending.isNotEmpty()) {
            val pc = pending.removeFirst()
            if (pc !in start..end || !reached.add(pc)) continue
            val raw = image.word(pc) ?: return false
            val jump = A64.bTarget(pc, raw)
            if (jump != null) { pending.add(jump); continue }
            if (raw and 0xfffffc1f.toInt() in listOf(0xd61f0000.toInt(), 0xd65f0000.toInt()) ||
                raw in listOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt())) continue
            if (raw and 0xff000010.toInt() == 0x54000000 || raw and 0x7e000000 == 0x34000000)
                pending.add(pc + (signExtend(((raw ushr 5) and 0x7ffff).toLong(), 19) shl 2))
            else if (raw and 0x7e000000 == 0x36000000)
                pending.add(pc + (signExtend(((raw ushr 5) and 0x3fff).toLong(), 14) shl 2))
            pending.add(pc + 4)
        }
        val words = reached.sorted().map { it to (image.word(it) ?: return false) }
        val resultStores = words.mapNotNull { (pc, w) -> memory(w)?.takeIf { !it.load && it.bytes == 4 && it.register == result && it.base != 31 }?.let { pc to it } }
        for ((resultPc, resultStore) in resultStores) {
            val base = resultStore.base
            val lengthStore = words.any { (_, w) -> memory(w)?.let { !it.load && it.bytes == 8 && it.base == base && it.register == length } == true }
            val loadedStore = words.any { (pc, w) -> memory(w)?.let {
                !it.load && it.bytes == 8 && it.base == base && loadedStackBefore(image, pc, it.register, start) == loaded
            } == true }
            val copyCall = words.any { (pc, w) -> A64.blTarget(pc, w) != null &&
                loadedStackBefore(image, pc, 1, start) == digest && copiedRegisterBefore(image, pc, 2, start) == length }
            if (lengthStore && loadedStore && copyCall && resultPc > start) return true
        }
        return false
    }

    private fun lengthGuard(image: PeImage, owner: Function, call: Long, length: Int): Length? {
        val found = ArrayList<Length>()
        for (pc in owner.start..<call step 4) {
            val w = image.word(pc) ?: continue
            if (w and 0x7fe0fc1f != 0x6b00001f) continue
            val left = (w ushr 5) and 31; val right = (w ushr 16) and 31
            val expected = when (length) { left -> right; right -> left; else -> continue }
            val branches = (pc + 4..<call step 4).filter { at ->
                image.word(at)?.let { it and 0xff00001f.toInt() == 0x54000001 } == true &&
                    StraightLineLiveness.preserves(image, pc + 4, at, flags = true)
            }
            val source = (pc - 4 downTo owner.start step 4).firstOrNull {
                memory(image.word(it) ?: 0)?.let { m -> m.load && m.base == 31 && m.register == expected && m.bytes in listOf(4, 8) } == true
            } ?: continue
            val noDigest = (source + 4..<pc step 4).filter { at ->
                val raw = image.word(at) ?: return@filter false
                raw and 0x7f00001f == (0x34000000 or expected)
            }.singleOrNull() ?: continue
            for (guard in branches) {
                if (!StraightLineLiveness.preserves(image, source + 4, noDigest, setOf(expected)) ||
                    !StraightLineLiveness.preserves(image, noDigest + 4, pc, setOf(expected, length)) ||
                    !StraightLineLiveness.preserves(image, guard + 4, call, setOf(length)) ||
                    !linear(image, guard + 4, call)) continue
                found += Length(guard, noDigest)
            }
        }
        return found.distinct().singleOrNull()
    }

    /** A byte XOR/OR reduction with a real ABI-zero Boolean return. Nothing
     * edits this shared helper; only the proved HashDescriptor caller changes. */
    private fun isByteCompare(image: PeImage, target: Long, function: Function?): Boolean {
        if (function == null || function.start != target) return false
        val words = (function.start..<function.end step 4).map { it to (image.word(it) ?: return false) }
        val loads = words.mapNotNull { (pc, w) ->
            if (w and 0xffe00c00.toInt() == 0x38400400 && (w ushr 12) and 0x1ff == 1)
                Triple(pc, (w ushr 5) and 31, w and 31) else null
        }
        for (a in loads) for (b in loads) {
            // Prove the first iteration's pointer aliases before either pointer
            // is widened by the post-index loop backedge. The separately
            // proved decrement/backedge still establishes the real byte loop.
            val input = A64ValueOrigins(image, function.start, maxOf(a.first, b.first) + 4)
            if (!input.addressDerivesFromEntry(a.first, a.second, 0) ||
                !input.addressDerivesFromEntry(b.first, b.second, 1)) continue
            for (xor in words.filter { (pc, w) -> pc > maxOf(a.first, b.first) &&
                    w and 0x7f200000 == 0x4a000000 &&
                    setOf((w ushr 5) and 31, (w ushr 16) and 31) == setOf(a.third, b.third) &&
                    StraightLineLiveness.preserves(image, a.first + 4, pc, setOf(a.third)) &&
                    StraightLineLiveness.preserves(image, b.first + 4, pc, setOf(b.third)) }) {
                val x = xor.second and 31
                for (or in words.filter { (pc, w) -> pc > xor.first && w and 0x7f200000 == 0x2a000000 &&
                        (((w ushr 5) and 31 == (w and 31) && (w ushr 16) and 31 == x) ||
                            ((w ushr 16) and 31 == (w and 31) && (w ushr 5) and 31 == x)) &&
                        StraightLineLiveness.preserves(image, xor.first + 4, pc, setOf(x)) }) {
                    val accumulator = or.second and 31
                    val loopStart = minOf(a.first, b.first)
                    val prefix = A64ValueOrigins(image, function.start, loopStart + 4)
                    val initial = prefix.origins(loopStart, accumulator) == setOf(A64ValueOrigin.Zero)
                    val counters = words.mapNotNull { (_, w) ->
                        if (w and 0xfffffc00.toInt() != 0xd1000400.toInt() || (w and 31) != ((w ushr 5) and 31)) null
                        else (w and 31).takeIf { prefix.derivesFromEntry(loopStart, it, 2) }
                    }.toSet()
                    val loop = words.any { (pc, w) -> w and 0xff000000.toInt() == 0xb5000000.toInt() &&
                        (w and 31) in counters && directTarget(pc, w)?.let { it in loopStart..maxOf(a.first, b.first) } == true }
                    val boolean = words.any { (cmp, w) -> cmp > or.first && A64.cmpImmediate(w) == (accumulator to 0) &&
                        words.any { (at, raw) -> at > cmp && raw and -32 == 0x1a9f07e0 &&
                            StraightLineLiveness.preserves(image, cmp + 4, at, flags = true) &&
                            returnsBoolean(image, function, at, raw and 31) } }
                    if (initial && loop && boolean) return true
                }
            }
        }
        return false
    }

    private fun returnsBoolean(image: PeImage, function: Function, definition: Long, register: Int): Boolean {
        val tail = A64ValueOrigins(image, definition + 4, function.end)
        val returns = (definition + 4..<function.end step 4).filter { pc ->
            image.word(pc)?.let { it and 0xfffffc1f.toInt() == 0xd65f0000.toInt() ||
                it in listOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt()) } == true && tail.isReachable(pc) == true
        }
        return returns.isNotEmpty() && returns.all { tail.derivesFromEntry(it, 0, register) }
    }
}
