package net.hawyn.ablefisp.patch

/** Keep a successful normal handoff green when the shared cleanup recalculates state. */
internal object LateBootStatePatcher {
    const val STUB_BYTES = 20
    data class Plan(
        val selectorRva: Long,
        val originalSelector: Int,
        val storeRva: Long,
        val errorRegister: Int,
        val stateRegister: Int,
        val allowRegister: Int,
        val existingStubRva: Long? = null,
    )
    private data class Existing(val stub: Long, val original: Int, val error: Int)
    private class SpinProof(val image: PeImage) {
        val starts = image.instructions().filter { it.second in setOf(0xd503233f.toInt(), 0xd503237f.toInt()) }.map { it.first }.toList()
        val cache = HashMap<Long, Boolean>()
        fun neverReturns(target: Long): Boolean = cache.getOrPut(target) { privateSpinDoesNotReturn(image, target, starts) }
    }

    fun analyze(image: PeImage, facts: NormalBootStateFacts): List<Plan> {
        if (facts.functionStart >= facts.functionEnd || !image.isCode(facts.normalStateStore)) incomplete()
        // Existing B-detours leave the function's CFG. Restore only a byte-exact
        // recognized selector in a private analysis snapshot, then reprove it.
        val existing = HashMap<Long, Existing>()
        var restored: ByteArray? = null
        for (pc in facts.functionStart until facts.functionEnd step 4) {
            val target = image.word(pc)?.let { A64.bTarget(pc, it) } ?: continue
            val decoded = decodeStub(image, pc, target) ?: continue
            existing[pc] = decoded
            val bytes = restored ?: image.bytes.copyOf().also { restored = it }
            bytes.putWord(requireNotNull(image.rvaToOffset(pc, 4)), decoded.original)
        }
        val source = restored?.let { PeImage.parse(it) } ?: image
        val flow = A64ValueOrigins(source, facts.functionStart, facts.functionEnd)
        val body = (facts.functionStart until facts.functionEnd step 4).mapNotNull { pc -> source.word(pc)?.let { pc to it } }
        val formatterTargets = formatterTargets(source, facts, body)
        if (formatterTargets.isEmpty()) incomplete()
        fun sameContext(pc: Long, register: Int, offset: Long): Boolean {
            val alias = flow.objectOffset(pc, register, facts.contextBaseRegister) ?: return false
            return alias.toLong() + offset == facts.contextFieldOffset &&
                flow.sameValue(pc, facts.contextBaseRegister, facts.normalStateStore, facts.contextBaseRegister)
        }
        val stores = body.filter { (pc, raw) ->
            val access = StateBranchEvaluator.access(raw) ?: return@filter false
            !access.load && access.width == 4 && access.rt != 31 && sameContext(pc, access.rn, access.offset.toLong())
        }
        val found = ArrayList<Plan>()
        for ((selector, raw) in body) {
            if (!isSelector(raw)) continue
            val state = raw and 31
            val compare = body.lastOrNull { (pc, word) ->
                pc < selector && pc >= selector - 0x100 && A64.cmpImmediate(word)?.second == 0 &&
                    StraightLineLiveness.preserves(source, pc + 4, selector, flags = true)
            } ?: continue
            val allow = requireNotNull(A64.cmpImmediate(compare.second)).first
            if (allow == 31) continue
            for ((store, storeWord) in stores) {
                if (store <= selector || store > selector + 0x100) continue
                val access = requireNotNull(StateBranchEvaluator.access(storeWord))
                if (!flow.derivesFrom(store, access.rt, selector, state)) continue
                for ((branch, branchWord) in body.filter { it.first in store + 4..store + 32 }) {
                    // This is the pending 64-bit local return value: nonzero
                    // enters the existing formatter/failure path, zero returns.
                    if (branchWord and 0xff000000.toInt() != 0xb5000000.toInt()) continue
                    val error = branchWord and 31
                    if (error !in 0..29 || error == state || error == facts.contextBaseRegister ||
                        !StraightLineLiveness.preserves(source, selector + 4, branch, setOf(error))) continue
                    val failed = branch + (signExtend(((branchWord ushr 5) and 0x7ffff).toLong(), 19) shl 2)
                    if (failed !in facts.functionStart..<facts.functionEnd) continue
                    if (!reachesFormatter(source, facts, flow, failed, formatterTargets)) continue
                    if (!reachesReturnValue(source, facts, branch + 4, error, formatterTargets)) continue
                    val zeroDefinitions = body.filter { (_, word) ->
                        A64.movXZero(word) == error || A64.movWImmediate(word) == (error to 0)
                    }
                    val normalZeros = zeroDefinitions.filter { (pc, _) ->
                        reachable(source, facts, facts.normalStateStore, pc) &&
                            reachesPreserving(source, facts, pc + 4, branch, error)
                    }
                    if (normalZeros.isEmpty() || !normalPolicySource(source, facts, flow, body,
                            compare.first, allow, normalZeros.map { it.first }, formatterTargets)) continue
                    val prior = existing[selector]
                    if (prior != null && (prior.original != raw || prior.error != error)) continue
                    found += Plan(selector, raw, store, error, state, allow, prior?.stub)
                }
            }
        }
        val distinct = found.distinct()
        if (distinct.isEmpty() || distinct.groupBy { it.storeRva }.any { it.value.size != 1 }) incomplete()
        if (existing.keys.any { key -> distinct.none { it.selectorRva == key } }) incomplete()
        return distinct
    }

    fun edits(plan: Plan, stubRva: Long): List<PlannedEdit> {
        if (!isSelector(plan.originalSelector) || plan.errorRegister !in 0..29 || plan.errorRegister == plan.stateRegister) incomplete()
        val words = intArrayOf(
            0xb5000060.toInt() or plan.errorRegister, // CBNZ Xpending, original selector
            plan.originalSelector and 0x400.inv(), // CSINC -> CSEL; same EQ flags and operands
            A64.branch(stubRva + 8, plan.selectorRva + 4),
            plan.originalSelector,
            A64.branch(stubRva + 16, plan.selectorRva + 4),
        )
        val code = ByteArray(STUB_BYTES)
        words.forEachIndexed { index, word -> code.putWord(index * 4, word) }
        val redirect = ByteArray(4).also { it.putWord(0, A64.branch(plan.selectorRva, stubRva)) }
        return listOf(
            PlannedEdit("正常完成时保留 green 状态", plan.selectorRva, redirect),
            PlannedEdit("保留失败回退的条件状态选择", stubRva, code),
        )
    }

    private fun decodeStub(image: PeImage, selector: Long, stub: Long): Existing? {
        if (!image.isCode(stub, STUB_BYTES)) return null
        val guard = image.word(stub) ?: return null
        val original = image.word(stub + 12) ?: return null
        if (guard and -32 != 0xb5000060.toInt() || !isSelector(original) ||
            image.word(stub + 4) != (original and 0x400.inv()) ||
            image.word(stub + 8)?.let { A64.bTarget(stub + 8, it) } != selector + 4 ||
            image.word(stub + 16)?.let { A64.bTarget(stub + 16, it) } != selector + 4) return null
        return Existing(stub, original, guard and 31)
    }

    private fun isSelector(word: Int): Boolean = word and 0xfffffc00.toInt() == 0x1a9f0400 &&
        word and 31 != 31 // CSINC Wstate, Wprevious, WZR, EQ

    private fun formatterTargets(image: PeImage, facts: NormalBootStateFacts, body: List<Pair<Long, Int>>): Set<Long> {
        val starts = image.instructions().filter { it.second in setOf(0xd503233f.toInt(), 0xd503237f.toInt()) }.map { it.first }.toList()
        val refs = A64.references(image)
        return body.mapNotNull { (pc, word) -> A64.blTarget(pc, word) }.distinct().filter { target ->
            val end = starts.firstOrNull { it > target } ?: return@filter false
            if (target !in starts || refs.none { it.pc in target..<end && image.ascii(it.address)?.contains("androidboot.verifiedbootstate") == true }) return@filter false
            (target until end step 4).any { pc ->
                val word = image.word(pc) ?: return@any false
                word and 0xffc00000.toInt() == 0xb9800000.toInt() &&
                    ((word ushr 10) and 0xfff) * 4L == facts.contextFieldOffset &&
                    formatterArgumentSource(image, target, end, pc, (word ushr 5) and 31) == 0
            }
        }.toSet()
    }

    private fun reachesFormatter(image: PeImage, facts: NormalBootStateFacts, flow: A64ValueOrigins, start: Long, targets: Set<Long>): Boolean =
        walk(image, facts, start) { pc, word ->
            val target = A64.blTarget(pc, word)
            target in targets && flow.sameValue(pc, 0, facts.normalStateStore, facts.contextBaseRegister)
        }

    /**
     * Error paths may reuse the same GP register for unrelated values. Reprove
     * the policy on the successful completion slice from a known private spill.
     * Opaque calls retain an unmodified caller-private spill under the same ABI
     * assumption used by A64ValueOrigins; explicit local writes are audited.
     */
    private fun normalPolicySource(image: PeImage, facts: NormalBootStateFacts, flow: A64ValueOrigins,
                                   body: List<Pair<Long, Int>>, compare: Long, allow: Int,
                                   zeroDefinitions: List<Long>, formatters: Set<Long>): Boolean {
        if (flow.derivesFromOrZero(compare, allow, facts.policyDefinitionPc, facts.policyRegister)) return true
        val spins = SpinProof(image)
        val loads = body.mapNotNull { (pc, word) ->
            val access = StateBranchEvaluator.access(word) ?: return@mapNotNull null
            if (!access.load || access.width != 4 || access.rt != allow) return@mapNotNull null
            val slot = flow.stackAddress(pc, access.rn)?.plus(access.offset) ?: return@mapNotNull null
            if (!flow.stackDerivesFromOrZero(facts.normalStateStore, slot, 4, facts.policyDefinitionPc, facts.policyRegister)) return@mapNotNull null
            Triple(pc, slot, access.rt)
        }
        return zeroDefinitions.all { zero -> loads.any { (load, slot, register) ->
            // The first normal formatter follows the observed normal store;
            // only the subsequent cleanup slice must avoid another formatter.
            val beforeZero = pathNodes(image, facts, facts.normalStateStore, zero, emptySet(), spins = spins) ?: return@any false
            val toCompare = pathNodes(image, facts, zero + 4, compare, formatters, spins = spins) ?: return@any false
            if (load !in toCompare || pathNodes(image, facts, zero + 4, compare, formatters, load, spins) != null) return@any false
            val afterLoad = pathNodes(image, facts, load + 4, compare, formatters, spins = spins) ?: return@any false
            if (afterLoad.any { pc ->
                    val word = requireNotNull(image.word(pc)); val call = A64.blTarget(pc, word)
                    if (call != null) !callPreserves(image, call, register) else writesRegister(word, register)
                }) return@any false
            (beforeZero + toCompare).all { pc -> !writesPrivateSlot(image, flow, pc, slot, 4) }
        } }
    }

    private fun writesPrivateSlot(image: PeImage, flow: A64ValueOrigins, pc: Long, slot: Int, length: Int): Boolean {
        val word = image.word(pc) ?: return true
        // A frame move invalidates relative spill coordinates. Calls restore SP.
        if ((word and 0x1f000000 == 0x11000000 || word and 0x1f200000 == 0x0b200000) &&
            word and 31 == 31 && word and 0x20000000 == 0) return true
        fun overlaps(base: Int, offset: Int, width: Int): Boolean = base + offset < slot + length && slot < base + offset + width
        val rn = (word ushr 5) and 31
        if (word and 0x3a000000 == 0x28000000) {
            val mode = (word ushr 23) and 3
            if (rn == 31 && mode in setOf(1, 3)) return true
            if (word and 0x00400000 != 0) return false
            val base = flow.stackAddress(pc, rn) ?: return false
            val vector = word and 0x04000000 != 0
            val width = if (vector) 4 shl (word ushr 30) else if (word ushr 31 == 1) 8 else 4
            val offset = if (mode == 1) 0 else signExtend(((word ushr 15) and 127).toLong(), 7).toInt() * width
            return overlaps(base, offset, 2 * width)
        }
        if (word and 0x3b000000 in setOf(0x39000000, 0x38000000)) {
            val unsigned = word and 0x3b000000 == 0x39000000
            val indexed = !unsigned && word and 0x00200000 != 0
            val mode = (word ushr 10) and 3
            if (!unsigned && !indexed && rn == 31 && mode in setOf(1, 3)) return true
            val vector = word and 0x04000000 != 0
            val operation = (word ushr 22) and 3
            val store = if (vector) operation and 1 == 0 else operation == 0
            if (!store) return false
            val base = flow.stackAddress(pc, rn) ?: return false
            // An unproved index into this caller's stack can alias the spill.
            if (indexed) return true
            val width = if (vector && operation >= 2) 16 else 1 shl (word ushr 30)
            val offset = if (unsigned) ((word ushr 10) and 0xfff) * width
                else if (mode == 1) 0 else signExtend(((word ushr 12) and 0x1ff).toLong(), 9).toInt()
            return overlaps(base, offset, width)
        }
        // Exclusive/atomic accesses to the local frame require a separate proof.
        if (word and 0x0a000000 == 0x08000000 && flow.stackAddress(pc, rn) != null) return true
        return false
    }

    /** Nodes on paths between two local blocks; a forbidden node tests dominance. */
    private fun pathNodes(image: PeImage, facts: NormalBootStateFacts, start: Long, target: Long,
                          stopCalls: Set<Long>, forbidden: Long? = null, spins: SpinProof = SpinProof(image)): Set<Long>? {
        val pending = ArrayDeque<Long>(); pending.add(start)
        val visited = HashSet<Long>(); val predecessors = HashMap<Long, MutableSet<Long>>()
        while (pending.isNotEmpty() && visited.size < 20000) {
            val pc = pending.removeFirst()
            if (pc == forbidden || pc !in facts.functionStart..<facts.functionEnd || !visited.add(pc)) continue
            if (pc == target) continue
            val word = image.word(pc) ?: continue
            if (isReturn(word) || isIndirect(word)) continue
            val call = A64.blTarget(pc, word)
            if (call in stopCalls || call?.let(spins::neverReturns) == true) continue
            val branch = directTarget(pc, word)
            val edges = if (branch == null) listOf(pc + 4) else if (A64.bTarget(pc, word) != null) listOf(branch) else listOf(branch, pc + 4)
            for (next in edges.filter { it != forbidden && it in facts.functionStart..<facts.functionEnd }) {
                predecessors.getOrPut(next) { HashSet() }.add(pc); pending.add(next)
            }
        }
        if (pending.isNotEmpty() || target !in visited) return null
        val relevant = HashSet<Long>(); pending.add(target)
        while (pending.isNotEmpty()) {
            val pc = pending.removeFirst()
            if (relevant.add(pc)) pending.addAll(predecessors[pc].orEmpty())
        }
        return relevant
    }

    /** A constant nonzero private stack flag in a load/CBNZ self-loop never returns. */
    private fun privateSpinDoesNotReturn(image: PeImage, target: Long, starts: List<Long>): Boolean {
        val end = starts.firstOrNull { it > target } ?: return false
        if (end - target > 0x200) return false
        var flow: A64ValueOrigins? = null
        for (pc in target until end - 4 step 4) {
            val access = image.word(pc)?.let(StateBranchEvaluator::access) ?: continue
            if (!access.load || access.width != 4 || access.rn != 31) continue
            val branch = image.word(pc + 4) ?: continue
            if (branch and 0xff00001f.toInt() != (0x35000000 or access.rt) ||
                pc + 4 + (signExtend(((branch ushr 5) and 0x7ffff).toLong(), 19) shl 2) != pc) continue
            // An earlier return/conditional edge would invalidate dominance.
            if ((target until pc step 4).any { before ->
                    val word = image.word(before) ?: return@any true
                    directTarget(before, word) != null || isReturn(word) || isIndirect(word)
                }) continue
            val proof = flow ?: A64ValueOrigins(image, target, end).also { flow = it }
            if ((target until pc step 4).any { before ->
                    image.word(before)?.let { A64.blTarget(before, it) } != null &&
                        (0..7).any { proof.stackAddress(before, it) != null }
                }) continue // the supposedly private flag may escape to a callee
            val origins = proof.stackOrigins(pc, access.offset, 4) ?: continue
            if (origins.isNotEmpty() && origins.all { origin ->
                    origin is A64ValueOrigin.Definition && image.word(origin.pc)?.let(A64::movWImmediate)?.let {
                        it.first == origin.register && it.second != 0
                    } == true
                }) return true
        }
        return false
    }

    /** Actual GP copy summary, including writes to callee-saved registers in outlined leaves. */
    private fun leafSources(image: PeImage, target: Long): IntArray? {
        val sources = IntArray(31) { it }
        for (n in 0 until 64) {
            val at = target + n * 4
            val word = image.word(at)?.takeIf { image.isCode(at) } ?: return null
            if (isReturn(word)) return sources
            if (directTarget(at, word) != null || A64.blTarget(at, word) != null || isIndirect(word)) return null
            copySources(word, sources)
        }
        return null
    }

    private fun copySources(word: Int, sources: IntArray) {
        val add = A64.add(word)
        if (word and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt()) {
            val destination = word and 31; val source = (word ushr 16) and 31
            if (destination != 31) sources[destination] = if (source == 31) -1 else sources[source]
        } else if (add != null && add.immediate == 0L && add.rd != 31) {
            sources[add.rd] = if (add.rn == 31) -1 else sources[add.rn]
        } else for (index in sources.indices) if (writesRegister(word, index)) sources[index] = -1
    }

    /** Small join-aware pointer proof for the formatter's input object. */
    private fun formatterArgumentSource(image: PeImage, start: Long, end: Long, before: Long, register: Int): Int? {
        val states = hashMapOf(start to IntArray(31) { it }); val pending = ArrayDeque<Long>(); pending.add(start)
        val summaries = HashMap<Long, IntArray?>(); var steps = 0
        while (pending.isNotEmpty() && steps++ < 20000) {
            val pc = pending.removeFirst(); val input = states[pc] ?: continue
            val word = image.word(pc) ?: continue; val output = input.copyOf()
            val call = A64.blTarget(pc, word)
            if (call != null) {
                val summary = summaries.getOrPut(call) { leafSources(image, call) }
                if (summary != null) for (index in output.indices) output[index] = summary[index].takeIf { it in 0..30 }?.let { input[it] } ?: -1
                else {
                    val ordinary = image.word(call) in setOf(0xd503233f.toInt(), 0xd503237f.toInt())
                    for (index in output.indices) if (!ordinary || index !in 19..29) output[index] = -1
                }
                output[30] = -1
            } else copySources(word, output)
            if (isReturn(word) || isIndirect(word)) continue
            val branch = directTarget(pc, word)
            val edges = if (branch == null) listOf(pc + 4) else if (A64.bTarget(pc, word) != null) listOf(branch) else listOf(branch, pc + 4)
            for (next in edges.filter { it in start..<end }) {
                val old = states[next]
                if (old == null) { states[next] = output.copyOf(); pending.add(next) }
                else {
                    var changed = false
                    for (index in old.indices) if (old[index] != output[index] && old[index] != -1) { old[index] = -1; changed = true }
                    if (changed) pending.add(next)
                }
            }
        }
        if (pending.isNotEmpty()) return null
        return states[before]?.get(register)?.takeIf { it >= 0 }
    }

    private fun reachesReturnValue(image: PeImage, facts: NormalBootStateFacts, start: Long, register: Int, formatters: Set<Long>): Boolean =
        walk(image, facts, start, preserve = register, stopCalls = formatters) { pc, word ->
            word and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt() && word and 31 == 0 &&
                (word ushr 16) and 31 == register && epiloguePreservesReturn(image, facts, pc + 4)
        }

    private fun epiloguePreservesReturn(image: PeImage, facts: NormalBootStateFacts, start: Long): Boolean {
        for (n in 0 until 32) {
            val pc = start + n * 4
            if (pc !in facts.functionStart..<facts.functionEnd) return false
            val word = image.word(pc) ?: return false
            if (isReturn(word)) return true
            val call = A64.blTarget(pc, word)
            if (call != null) {
                if (!leafPreserves(image, call, 0)) return false
                continue
            }
            if (directTarget(pc, word) != null || isIndirect(word) || writesRegister(word, 0)) return false
        }
        return false
    }

    private fun leafPreserves(image: PeImage, target: Long, register: Int): Boolean {
        for (n in 0 until 64) {
            val pc = target + n * 4
            val word = image.word(pc)?.takeIf { image.isCode(pc) } ?: return false
            if (isReturn(word)) return true
            if (directTarget(pc, word) != null || A64.blTarget(pc, word) != null || isIndirect(word) ||
                writesRegister(word, register)) return false
        }
        return false
    }

    private fun callPreserves(image: PeImage, target: Long, register: Int): Boolean =
        (register in 19..29 && image.word(target) in setOf(0xd503233f.toInt(), 0xd503237f.toInt())) || leafPreserves(image, target, register)

    private fun reachable(image: PeImage, facts: NormalBootStateFacts, start: Long, target: Long): Boolean =
        walk(image, facts, start) { pc, _ -> pc == target }

    private fun reachesPreserving(image: PeImage, facts: NormalBootStateFacts, start: Long, target: Long, register: Int): Boolean =
        walk(image, facts, start, preserve = register) { pc, _ -> pc == target }

    /** Bounded graph traversal. No unresolved BR target is invented. */
    private fun walk(image: PeImage, facts: NormalBootStateFacts, start: Long, preserve: Int? = null,
                     stopCalls: Set<Long> = emptySet(), found: (Long, Int) -> Boolean): Boolean {
        val pending = ArrayDeque<Long>(); pending.add(start)
        val visited = HashSet<Long>()
        while (pending.isNotEmpty() && visited.size <= 20000) {
            val pc = pending.removeFirst()
            if (pc !in facts.functionStart..<facts.functionEnd || !visited.add(pc)) continue
            val word = image.word(pc) ?: continue
            if (found(pc, word)) return true
            val call = A64.blTarget(pc, word)
            if (call != null) {
                if (call !in stopCalls && (preserve == null || callPreserves(image, call, preserve))) pending.add(pc + 4)
                continue
            }
            if (isReturn(word) || isIndirect(word)) continue
            val branch = directTarget(pc, word)
            if (branch != null) {
                pending.add(branch)
                if (A64.bTarget(pc, word) == null) pending.add(pc + 4)
                continue
            }
            if (preserve == null || !writesRegister(word, preserve)) pending.add(pc + 4)
        }
        return false
    }

    private fun isReturn(word: Int): Boolean = word and 0xfffffc1f.toInt() == 0xd65f0000.toInt() ||
        word in setOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt())
    private fun writesRegister(word: Int, register: Int): Boolean = when (word) {
        0xd503233f.toInt(), 0xd503237f.toInt(), 0xd50323bf.toInt(), 0xd50323ff.toInt() -> register == 30
        0xd503241f.toInt(), 0xd503245f.toInt(), 0xd503249f.toInt(), 0xd50324df.toInt() -> false // BTI hints
        else -> StraightLineLiveness.mayWriteRegister(word, register)
    }
    private fun isIndirect(word: Int): Boolean = word and 0xfffffc1f.toInt() in setOf(0xd61f0000.toInt(), 0xd63f0000.toInt())
    private fun directTarget(pc: Long, word: Int): Long? {
        A64.bTarget(pc, word)?.let { return it }
        if (word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 == 0x34000000)
            return pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
        if (word and 0x7e000000 == 0x36000000) return pc + (signExtend(((word ushr 5) and 0x3fff).toLong(), 14) shl 2)
        return null
    }
    private fun incomplete(): Nothing = throw PatchException(
        "假回锁：正常完成后的状态重算、允许错误来源及失败回退关系未唯一匹配，本次未生成镜像。",
        PatchFailureKind.INCOMPLETE_CORE_FEATURE,
    )
}
