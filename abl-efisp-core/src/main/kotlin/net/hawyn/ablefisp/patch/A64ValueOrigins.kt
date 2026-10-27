package net.hawyn.ablefisp.patch

/** Leaf sources, not inferred runtime values. Unknown is never an accepted source. */
internal sealed interface A64ValueOrigin {
    data class Definition(val pc: Long, val register: Int) : A64ValueOrigin
    data class EntryArgument(val register: Int) : A64ValueOrigin
    data object Zero : A64ValueOrigin
    data object Unknown : A64ValueOrigin
}

/**
 * Bounded, intra-procedural provenance for copy operations and local spill slots.
 * W/X names share one identity; byte-wise memory definitions preserve access widths.
 * Calls return according to the ABI: volatile registers are clobbered, callee-saved
 * registers and unmodified local spill slots survive. Callee memory side effects
 * and external service acceptance are outside this model.
 *
 * Copy cycles are solved as a finite set of source equations. An external source
 * propagates through a cycle; a cycle without any external definition stays unknown.
 */
internal class A64ValueOrigins(
    private val image: PeImage, private val start: Long, private val end: Long,
    /** Optional, externally proved bounded jump-table edges. Other BRs stay terminal. */
    private val extraSuccessors: Map<Long, List<Long>> = emptyMap(),
) {
    private data class Node(val pc: Long, val register: Int = -1, val byte: Int = 0) {
        val memory get() = register < 0
    }
    private data class Equation(val dependencies: List<Node>, val leaves: Set<A64ValueOrigin> = emptySet())
    private data class Access(
        val register: Int, val base: Int, val offset: Int, val size: Int,
        val load: Boolean, val vector: Boolean = false, val indexed: Boolean = false,
        val indexRegister: Int = -1, val indexScale: Int = 1,
    )
    private data class Region(val identity: Long, val low: Int, val high: Int, val guardLow: Int? = null, val guardSize: Int = 0)
    private data class Interval(val low: Long, val high: Long)
    private data class Decoded(
        val writes: Set<Int>?,
        val copy: Pair<Int, Int>? = null,
        val copy64: Boolean = true,
        val zero: Int? = null,
        val memory: List<Access> = emptyList(),
        val writeBack: Pair<Int, Int>? = null,
        val call: Boolean = false,
        val uncertainDefinition: Boolean = false,
    )

    private val words = LinkedHashMap<Long, Int>()
    private val decoded = HashMap<Long, Decoded>()
    private val predecessors = HashMap<Long, MutableList<Long>>()
    private val successors = HashMap<Long, List<Long>>()
    private val addresses = HashMap<Long, IntArray>()
    private val globals = HashMap<Long, LongArray>()
    private val regions = HashMap<Long, Array<Region?>>()
    private val regionCache = HashMap<Node, Region?>()
    private val equations = HashMap<Node, Equation>()
    private val settled = HashMap<Node, Set<A64ValueOrigin>>()
    private val leafCallWrites = HashMap<Long, Set<Int>?>()
    private val spinCalls = HashMap<Long, Boolean>()
    private var budgetExceeded = false

    init {
        if (start >= 0 && end > start && (end - start) / 4 <= MAX_INSTRUCTIONS && image.isCode(start)) {
            buildGraph()
            buildAddresses()
        } else budgetExceeded = true
    }

    fun derivesFrom(pc: Long, register: Int, definitionPc: Long, definitionRegister: Int): Boolean =
        origins(pc, register) == setOf(A64ValueOrigin.Definition(definitionPc, definitionRegister))

    fun derivesFromOrZero(pc: Long, register: Int, definitionPc: Long, definitionRegister: Int): Boolean =
        policyOrZero(origins(pc, register), definitionPc, definitionRegister)

    fun derivesFromEntry(pc: Long, register: Int, argument: Int): Boolean =
        argument in 0..30 && origins(pc, register) == setOf(A64ValueOrigin.EntryArgument(argument))

    fun stackDerivesFrom(pc: Long, offset: Int, width: Int, definitionPc: Long, definitionRegister: Int): Boolean =
        stackOrigins(pc, offset, width) == setOf(A64ValueOrigin.Definition(definitionPc, definitionRegister))

    fun stackDerivesFromOrZero(pc: Long, offset: Int, width: Int, definitionPc: Long, definitionRegister: Int): Boolean =
        policyOrZero(stackOrigins(pc, offset, width), definitionPc, definitionRegister)

    /** A single known source establishes equality; equal multi-source sets do not. */
    fun sameValue(pc1: Long, register1: Int, pc2: Long, register2: Int): Boolean {
        val first = origins(pc1, register1) ?: return false
        return first.size == 1 && first == origins(pc2, register2)
    }

    fun origins(pc: Long, register: Int): Set<A64ValueOrigin>? {
        if (register !in 0..30 || pc !in words || budgetExceeded) return null
        return solve(listOf(Node(pc, register)))
    }

    fun stackOrigins(pc: Long, offset: Int, width: Int): Set<A64ValueOrigin>? {
        if (width !in 1..32 || pc !in words || budgetExceeded) return null
        val sp = addresses[pc]?.get(31)?.takeIf(::isStack) ?: return null
        val at = addOffset(sp, offset).takeIf(::isStack) ?: return null
        return solve((0 until width).map { Node(pc, byte = at + it) })
    }

    /** Address of a register relative to SP at this instruction, before execution. */
    fun stackAddress(pc: Long, register: Int): Int? {
        if (register !in 0..31 || budgetExceeded) return null
        val state = addresses[pc] ?: return null
        val address = state[register]
        val sp = state[31]
        return if (isStack(address) && isStack(sp)) address - sp else null
    }

    /** Unique ADR/ADRP-based address at an instruction, represented as a PE RVA. */
    fun globalAddress(pc: Long, register: Int): Long? =
        if (register !in 0..30 || budgetExceeded) null else globals[pc]?.get(register)?.takeIf { it >= 0 }

    /** Null preserves ambiguity when the bounded CFG proof exhausted its budget. */
    fun isReachable(pc: Long): Boolean? = if (budgetExceeded) null else pc in words

    /** An address in the entry object's region, including known MOV/ADD aliases. */
    fun addressDerivesFromEntry(pc: Long, register: Int, argument: Int): Boolean =
        register in 0..30 && argument in 0..30 && !budgetExceeded &&
            regions[pc]?.get(register)?.identity == 0x1_0000_0000L + argument

    /** Every entry after [from] through [until] must carry the preceding instruction's state. */
    fun hasOnlyLinearEntry(from: Long, until: Long): Boolean {
        if (budgetExceeded || from > until || from and 3 != 0L || until and 3 != 0L || from !in words || until !in words) return false
        var at = from + 4
        while (at <= until) {
            if (predecessors[at].orEmpty().distinct() != listOf(at - 4)) return false
            at += 4
        }
        return true
    }

    /** Exact relative offset within one proven external object, including MOV aliases. */
    fun objectOffset(pc: Long, register: Int, reference: Int): Int? {
        if (register !in 0..30 || reference !in 0..30 || budgetExceeded) return null
        val a = regions[pc]?.get(register) ?: return null
        val b = regions[pc]?.get(reference) ?: return null
        return if (a.identity == b.identity && a.low == a.high && b.low == b.high) a.low - b.low else null
    }

    private fun policyOrZero(found: Set<A64ValueOrigin>?, pc: Long, register: Int): Boolean {
        val policy = A64ValueOrigin.Definition(pc, register)
        return found != null && policy in found && found.all { it == policy || it == A64ValueOrigin.Zero }
    }

    private fun buildGraph() {
        val pending = arrayListOf(start)
        var index = 0
        while (index < pending.size) {
            val pc = pending[index++]
            if (pc in words || pc !in start..<end || pc and 3 != 0L || !image.isCode(pc)) continue
            if (words.size >= MAX_INSTRUCTIONS) { budgetExceeded = true; return }
            val word = image.word(pc) ?: continue
            words[pc] = word
            val next = next(pc, word).filter { it in start..<end && it and 3 == 0L && image.isCode(it) }.distinct()
            successors[pc] = next
            for (at in next) {
                predecessors.getOrPut(at) { arrayListOf() }.add(pc)
                if (at !in words) pending += at
            }
        }
    }

    private fun next(pc: Long, word: Int): List<Long> {
        A64.blTarget(pc, word)?.let { if (provenSpinCall(it)) return emptyList() }
        A64.bTarget(pc, word)?.let { return listOf(it) }
        if (word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 == 0x34000000) {
            return listOf(pc + 4, pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2))
        }
        if (word and 0x7e000000 == 0x36000000) {
            return listOf(pc + 4, pc + (signExtend(((word ushr 5) and 0x3fff).toLong(), 14) shl 2))
        }
        if (word and 0xfffffc1f.toInt() == 0xd61f0000.toInt()) return extraSuccessors[pc].orEmpty()
        if (word and 0xfffffc1f.toInt() == 0xd65f0000.toInt() ||
            word in listOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt())) return emptyList()
        return listOf(pc + 4)
    }

    private fun buildAddresses() {
        // Incoming arguments belong to the caller, not to this function's new frame.
        addresses[start] = IntArray(32) { if (it == 31) 0 else NON_STACK }
        globals[start] = LongArray(32) { NON_GLOBAL }
        regions[start] = Array(32) { if (it == 31) null else Region(0x1_0000_0000L + it, 0, 0) }
        val pending = ArrayDeque<Long>()
        val queued = hashSetOf(start)
        pending.add(start)
        var steps = 0
        while (pending.isNotEmpty()) {
            if (++steps > MAX_ADDRESS_STEPS) { budgetExceeded = true; return }
            val pc = pending.removeFirst()
            queued.remove(pc)
            val before = addresses[pc] ?: continue
            val after = addressTransfer(pc, before)
            val afterGlobals = globalTransfer(pc, requireNotNull(globals[pc]))
            val afterRegions = regionTransfer(pc, requireNotNull(regions[pc]))
            for (at in successors[pc].orEmpty()) {
                val prior = addresses[at]
                var changed = prior == null
                val joined = if (prior == null) after.copyOf() else prior.copyOf().also { result ->
                    for (r in result.indices) {
                        if (result[r] != after[r] && result[r] != UNKNOWN_ADDRESS) {
                            result[r] = UNKNOWN_ADDRESS
                            changed = true
                        }
                    }
                }
                val priorGlobals = globals[at]
                val joinedGlobals = if (priorGlobals == null) afterGlobals.copyOf() else priorGlobals.copyOf().also { result ->
                    for (r in result.indices) {
                        if (result[r] != afterGlobals[r] && result[r] != UNKNOWN_GLOBAL) {
                            result[r] = UNKNOWN_GLOBAL
                            changed = true
                        }
                    }
                }
                val priorRegions = regions[at]
                val joinedRegions = if (priorRegions == null) afterRegions.copyOf() else priorRegions.copyOf().also { result ->
                    for (r in result.indices) if (result[r] != afterRegions[r] && result[r] != null) {
                        result[r] = null
                        changed = true
                    }
                }
                if (changed) {
                    addresses[at] = joined
                    globals[at] = joinedGlobals
                    regions[at] = joinedRegions
                    if (queued.add(at)) pending.add(at)
                }
            }
        }
    }

    private fun addressTransfer(pc: Long, before: IntArray): IntArray {
        val word = requireNotNull(words[pc] ?: image.word(pc))
        val d = decode(pc)
        val after = before.copyOf()
        if (d.writes == null) for (r in 0..30) after[r] = UNKNOWN_ADDRESS
        else d.writes.forEach { if (it != 31) after[it] = UNKNOWN_ADDRESS }
        if (d.call) {
            after[30] = NON_STACK
        }
        d.copy?.let { (rd, rm) ->
            after[rd] = if (rm == 31) NON_STACK else if (d.copy64 || before[rm] == NON_STACK) before[rm] else UNKNOWN_ADDRESS
        }
        d.zero?.let { after[it] = NON_STACK }
        if (A64.adrp(pc, word) != null || A64.adr(pc, word) != null || isMoveWide(word)) {
            val rd = word and 31
            if (rd != 31) after[rd] = NON_STACK
        }
        if (word and 0x1f000000 == 0x11000000) {
            val rd = word and 31
            val rn = (word ushr 5) and 31
            val flags = word and 0x20000000 != 0
            if (rd != 31 || !flags) {
                val magnitude = ((word ushr 10) and 0xfff) shl if (word and 0x00400000 != 0) 12 else 0
                val delta = if (word and 0x40000000 != 0) -magnitude else magnitude
                after[rd] = addOffset(before[rn], delta)
            }
        }
        d.writeBack?.let { (rn, delta) -> after[rn] = addOffset(before[rn], delta) }
        return after
    }

    private fun globalTransfer(pc: Long, before: LongArray): LongArray {
        val word = requireNotNull(words[pc] ?: image.word(pc))
        val d = decode(pc)
        val after = before.copyOf()
        if (d.writes == null) for (r in 0..30) after[r] = UNKNOWN_GLOBAL
        else d.writes.forEach { if (it != 31) after[it] = UNKNOWN_GLOBAL }
        if (d.call) {
            after[30] = NON_GLOBAL
        }
        d.copy?.let { (rd, rm) ->
            after[rd] = if (rm == 31) NON_GLOBAL else if (d.copy64 || before[rm] == NON_GLOBAL) before[rm] else UNKNOWN_GLOBAL
        }
        d.zero?.let { after[it] = NON_GLOBAL }
        if (isMoveWide(word) && word and 31 != 31) after[word and 31] = NON_GLOBAL
        A64.adrp(pc, word)?.let { after[it.first] = it.second }
        A64.adr(pc, word)?.let { after[it.first] = it.second }
        if (word and 0x1f000000 == 0x11000000) {
            val rd = word and 31
            val rn = (word ushr 5) and 31
            val flags = word and 0x20000000 != 0
            if (rd != 31 || !flags) {
                val magnitude = ((word ushr 10) and 0xfff).toLong() shl if (word and 0x00400000 != 0) 12 else 0
                val delta = if (word and 0x40000000 != 0) -magnitude else magnitude
                after[rd] = if (rn == 31) NON_GLOBAL else addGlobal(before[rn], delta)
            }
        }
        d.writeBack?.let { (rn, delta) -> after[rn] = if (rn == 31) NON_GLOBAL else addGlobal(before[rn], delta.toLong()) }
        return after
    }

    private fun regionTransfer(pc: Long, before: Array<Region?>): Array<Region?> {
        val word = requireNotNull(words[pc])
        val d = decode(pc)
        val after = before.copyOf()
        if (d.writes == null) for (r in 0..30) after[r] = null
        else d.writes.forEach { if (it != 31) after[it] = null }
        d.copy?.let { (rd, rm) -> if (rm != 31 && d.copy64) after[rd] = before[rm] }
        A64.adrp(pc, word)?.let { after[it.first] = if (it.second in 0..Int.MAX_VALUE.toLong()) Region(0, it.second.toInt(), it.second.toInt()) else null }
        A64.adr(pc, word)?.let { after[it.first] = if (it.second in 0..Int.MAX_VALUE.toLong()) Region(0, it.second.toInt(), it.second.toInt()) else null }
        if (word and 0x1f000000 == 0x11000000) {
            val rd = word and 31
            val rn = (word ushr 5) and 31
            if (rd != 31 && rn != 31) {
                val amount = ((word ushr 10) and 0xfff) shl if (word and 0x00400000 != 0) 12 else 0
                val delta = if (word and 0x40000000 != 0) -amount else amount
                after[rd] = before[rn]?.let { shiftRegion(it, delta.toLong(), delta.toLong()) }
            }
        }
        d.writeBack?.let { (rn, delta) -> if (rn != 31) after[rn] = before[rn]?.let { shiftRegion(it, delta.toLong(), delta.toLong()) } }
        return after
    }

    private fun solve(roots: List<Node>): Set<A64ValueOrigin>? {
        if (budgetExceeded) return null
        val local = HashMap<Node, Equation>()
        val pending = ArrayDeque<Node>()
        roots.forEach(pending::add)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node in local) continue
            if (equations.size >= MAX_NODES) { budgetExceeded = true; return null }
            val known = settled[node]
            if (known != null) {
                local[node] = Equation(emptyList(), known)
                continue
            }
            val equation = equations.getOrPut(node) { equation(node) }
            local[node] = equation
            equation.dependencies.forEach { if (it !in local) pending.add(it) }
        }
        val users = HashMap<Node, MutableList<Node>>()
        val values = HashMap<Node, MutableSet<A64ValueOrigin>>()
        val work = ArrayDeque<Node>()
        val queued = HashSet<Node>()
        for ((node, equation) in local) {
            values[node] = equation.leaves.toMutableSet()
            equation.dependencies.forEach { users.getOrPut(it) { arrayListOf() }.add(node) }
            if (equation.leaves.isNotEmpty()) { work.add(node); queued.add(node) }
        }
        var steps = 0
        while (work.isNotEmpty()) {
            if (++steps > MAX_PROPAGATION_STEPS) return null
            val source = work.removeFirst()
            queued.remove(source)
            for (user in users[source].orEmpty()) {
                val destination = requireNotNull(values[user])
                if (destination.addAll(requireNotNull(values[source]))) {
                    if (destination.size > MAX_ORIGINS) return null
                    if (queued.add(user)) work.add(user)
                }
            }
        }
        for (node in local.keys) {
            if (node !in settled) settled[node] = values[node].orEmpty().toSet().ifEmpty { setOf(A64ValueOrigin.Unknown) }
        }
        val result = LinkedHashSet<A64ValueOrigin>()
        for (root in roots) {
            val found = settled[root].orEmpty()
            if (found.isEmpty() || A64ValueOrigin.Unknown in found) return null
            result += found
        }
        return result.toSet()
    }

    private fun equation(node: Node): Equation {
        if (node.pc !in words) return unknown()
        if (node.pc == start) return if (node.memory) unknown() else Equation(emptyList(), setOf(A64ValueOrigin.EntryArgument(node.register)))
        val incoming = predecessors[node.pc].orEmpty()
        if (incoming.isEmpty()) return unknown()
        val dependencies = ArrayList<Node>()
        val leaves = LinkedHashSet<A64ValueOrigin>()
        for (pc in incoming) {
            val d = decode(pc)
            val source = if (node.memory) memorySource(node.byte, pc, d) else registerSource(node.register, pc, d)
            dependencies += source.dependencies
            leaves += source.leaves
        }
        return Equation(dependencies.distinct(), leaves)
    }

    private fun registerSource(register: Int, pc: Long, d: Decoded): Equation {
        if (d.call && register in d.writes.orEmpty()) return unknown()
        if (d.writes == null) return unknown()
        if (register !in d.writes) return Equation(listOf(Node(pc, register)))
        d.copy?.takeIf { it.first == register }?.let { (_, source) ->
            return if (source == 31) zero() else Equation(listOf(Node(pc, source)))
        }
        if (d.zero == register) return zero()
        if (d.writeBack?.first == register) return definition(pc, register)
        val load = d.memory.singleOrNull { it.load && !it.vector && it.register == register }
        if (load != null) {
            val base = addresses[pc]?.get(load.base) ?: return unknown()
            if (base == NON_STACK) return definition(pc, register)
            if (!isStack(base) || load.indexed) return unknown()
            val at = addOffset(base, load.offset).takeIf(::isStack) ?: return unknown()
            return Equation((0 until load.size).map { Node(pc, byte = at + it) })
        }
        return if (d.uncertainDefinition) unknown() else definition(pc, register)
    }

    private fun memorySource(byte: Int, pc: Long, d: Decoded): Equation {
        if (d.writes == null) return unknown()
        for (store in d.memory.filterNot { it.load }) {
            val base = addresses[pc]?.get(store.base) ?: return unknown()
            if (base == NON_STACK) continue
            if (!isStack(base)) {
                val region = externalRegion(pc, store.base)
                if (region != null && !store.indexed) {
                    val access = shiftRegion(region, store.offset.toLong(), store.offset.toLong())
                    val guard = region.guardLow
                    if (access != null && (guard == null ||
                            access.high.toLong() + store.size <= guard || access.low >= guard.toLong() + region.guardSize)) continue
                }
                return unknown()
            }
            if (store.indexed) return unknown()
            val at = addOffset(base, store.offset).takeIf(::isStack) ?: return unknown()
            if (byte !in at until at + store.size) continue
            if (store.vector) return unknown()
            return if (store.register == 31) zero() else Equation(listOf(Node(pc, store.register)))
        }
        return Equation(listOf(Node(pc, byte = byte)))
    }

    private fun decode(pc: Long): Decoded = decoded.getOrPut(pc) {
        val w = requireNotNull(words[pc] ?: image.word(pc))
        val rd = w and 31
        fun writes(vararg registers: Int) = registers.filter { it != 31 }.toSet()
        if (A64.blTarget(pc, w) != null || w and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) {
            val target = A64.blTarget(pc, w)
            val affected = target?.let(::smallLeafCallWrites) ?: (0..18).toSet()
            return@getOrPut Decoded(affected + 30, call = true)
        }
        if (A64.bTarget(pc, w) != null || w and 0xff000010.toInt() == 0x54000000 ||
            w and 0x7e000000 in listOf(0x34000000, 0x36000000) ||
            w and 0xfffffc1f.toInt() in listOf(0xd65f0000.toInt(), 0xd61f0000.toInt()) ||
            w in listOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt())) return@getOrPut Decoded(emptySet())
        if (w and 0xfffff01f.toInt() == 0xd503201f.toInt()) return@getOrPut Decoded(writes(30))
        if (w and 0xffc00000.toInt() == 0xd5000000.toInt()) {
            return@getOrPut Decoded(if (w and 0x00200000 != 0) writes(rd) else emptySet())
        }
        if (w and 0xffe0ffe0.toInt() in listOf(0xaa0003e0.toInt(), 0x2a0003e0)) {
            val source = (w ushr 16) and 31
            return@getOrPut Decoded(writes(rd), copy = rd to source, copy64 = w ushr 31 != 0)
        }
        // ADD Wd,Wn,#0 / ADD Xd,Xn,#0 are GP identity copies. Register 31
        // denotes SP here, so it must not be modelled as the MOV zero register.
        if (w and 0x7ffffc00 == 0x11000000 && rd != 31 && (w ushr 5) and 31 != 31) {
            return@getOrPut Decoded(writes(rd), copy = rd to ((w ushr 5) and 31), copy64 = w ushr 31 != 0)
        }
        A64.movWImmediate(w)?.let { if (it.second == 0) return@getOrPut Decoded(writes(it.first), zero = it.first) }
        A64.movXZero(w)?.let { return@getOrPut Decoded(writes(it), zero = it) }
        if (isMoveWide(w) && (w ushr 5) and 0xffff == 0 && w and 0x60000000 == 0x40000000) {
            return@getOrPut Decoded(writes(rd), zero = rd)
        }
        // Scalar/SIMD single-register memory operations.
        if (w and 0x3b000000 in listOf(0x39000000, 0x38000000)) {
            val vector = w and 0x04000000 != 0
            val operation = (w ushr 22) and 3
            val prefetch = !vector && w ushr 30 == 3 && operation == 2
            if (prefetch) return@getOrPut Decoded(emptySet())
            val load = if (vector) operation and 1 != 0 else operation != 0
            val size = if (vector && operation >= 2) 16 else 1 shl (w ushr 30)
            val rn = (w ushr 5) and 31
            val unsigned = w and 0x3b000000 == 0x39000000
            val indexed = !unsigned && w and 0x00200000 != 0
            val mode = (w ushr 10) and 3
            if (indexed && mode != 2) return@getOrPut Decoded(null)
            val immediate = if (unsigned) ((w ushr 10) and 0xfff) * size else signExtend(((w ushr 12) and 0x1ff).toLong(), 9).toInt()
            val writeBack = if (!unsigned && !indexed && mode in listOf(1, 3)) rn to immediate else null
            val offset = if (!unsigned && !indexed && mode == 1) 0 else immediate
            val indexOption = (w ushr 13) and 7
            if (indexed && indexOption !in listOf(2, 3, 6, 7)) return@getOrPut Decoded(null)
            return@getOrPut Decoded(
                writes(*(listOfNotNull(if (load && !vector) rd else null, writeBack?.first).toIntArray())),
                memory = listOf(Access(rd, rn, if (indexed) 0 else offset, size, load, vector, indexed,
                    if (indexed) (w ushr 16) and 31 else -1, if (indexed && w and 0x1000 != 0) size else 1)), writeBack = writeBack,
            )
        }
        // Pair memory operations, including W/X spill/reload pairs.
        if (w and 0x3a000000 == 0x28000000) {
            val vector = w and 0x04000000 != 0
            val load = w and 0x00400000 != 0
            val opc = w ushr 30
            val size = if (vector) 4 shl opc else if (opc == 2) 8 else 4
            val rn = (w ushr 5) and 31
            val rt2 = (w ushr 10) and 31
            val mode = (w ushr 23) and 3
            val immediate = signExtend(((w ushr 15) and 127).toLong(), 7).toInt() * size
            val offset = if (mode == 1) 0 else immediate
            val writeBack = if (mode in listOf(1, 3)) rn to immediate else null
            return@getOrPut Decoded(
                writes(*(listOfNotNull(if (load && !vector) rd else null, if (load && !vector) rt2 else null, writeBack?.first).toIntArray())),
                memory = listOf(Access(rd, rn, offset, size, load, vector), Access(rt2, rn, offset + size, size, load, vector)),
                writeBack = writeBack,
            )
        }
        if (A64.adr(pc, w) != null || A64.adrp(pc, w) != null) return@getOrPut Decoded(writes(rd))
        if (w and 0x3b000000 == 0x18000000) {
            return@getOrPut Decoded(if (w and 0x04000000 == 0 && w ushr 30 != 3) writes(rd) else emptySet())
        }
        if (w and 0x1fe00000 == 0x1a400000) return@getOrPut Decoded(emptySet()) // CCMP/CCMN
        if (w and 0x0ff80000 == 0x0f000000) return@getOrPut Decoded(emptySet()) // SIMD modified immediate (MOVI/MVNI)
        if (w and 0x1f000000 in listOf(0x11000000, 0x12000000, 0x13000000) ||
            w and 0x0e000000 == 0x0a000000) return@getOrPut Decoded(writes(rd))
        // A conservative GP destination for SIMD/FP transfers. Ordinary vector
        // instructions may therefore reject a relation, never invent a GP copy.
        if (w and 0x0e000000 == 0x0e000000) return@getOrPut Decoded(writes(rd), uncertainDefinition = true)
        Decoded(null)
    }

    private fun isMoveWide(w: Int) = w and 0x1f800000 == 0x12800000

    private fun externalRegion(pc: Long, register: Int, active: MutableSet<Node> = HashSet()): Region? {
        regions[pc]?.get(register)?.let { return it }
        val key = Node(pc, register)
        if (regionCache.containsKey(key)) return regionCache[key]
        if (!active.add(key) || active.size > 256) return null
        var result: Region? = null
        var valid = true
        for (prior in predecessors[pc].orEmpty()) {
            val d = decode(prior)
            val candidate = when {
                d.writes == null -> null
                register !in d.writes -> externalRegion(prior, register, active)
                d.copy?.first == register && d.copy64 && d.copy.second != 31 -> externalRegion(prior, d.copy.second, active)
                isMadd(requireNotNull(words[prior])) && requireNotNull(words[prior]) and 31 == register -> boundedMaddRegion(prior)
                else -> null
            }
            if (candidate == null || result != null && candidate != result) { valid = false; break }
            result = candidate
        }
        active.remove(key)
        return (if (valid) result else null).also { regionCache[key] = it }
    }

    /** A checked table index may form a pointer without a simple ADD immediate.
     * Preserve its external object region only after proving the index bound and
     * that intervening writes cannot alter the guarded counter being reloaded. */
    private fun boundedMaddRegion(pc: Long): Region? {
        val word = words[pc] ?: return null
        if (!isMadd(word)) return null
        val baseRegister = (word ushr 10) and 31
        val base = regions[pc]?.get(baseRegister) ?: return null
        var branch = pc - 4
        var steps = 0
        while (branch >= start && ++steps <= 2048) {
            val raw = words[branch] ?: return null
            if (isTransfer(branch, raw)) break
            branch -= 4
        }
        val branchWord = words[branch] ?: return null
        if (branchWord and 0xff000010.toInt() != 0x54000000 || branchWord and 15 !in listOf(8, 2)) return null
        val branchTarget = branch + (signExtend(((branchWord ushr 5) and 0x7ffff).toLong(), 19) shl 2)
        if (branchTarget in (branch + 4)..pc) return null
        // There must be no other entry into this fall-through block.
        var at = branch + 4
        while (at <= pc) {
            if (predecessors[at].orEmpty().distinct() != listOf(at - 4)) return null
            at += 4
        }
        var comparePc = branch - 4
        var compare: Pair<Int, Int>? = null
        while (comparePc >= start && branch - comparePc <= 128) {
            val raw = words[comparePc] ?: return null
            A64.cmpImmediate(raw)?.let { compare = it }
            if (compare != null) break
            if (isTransfer(comparePc, raw)) return null
            comparePc -= 4
        }
        val guard = compare ?: return null
        if (!StraightLineLiveness.preserves(image, comparePc + 4, branch, flags = true)) return null
        val maximum = guard.second.toLong() - if (branchWord and 15 == 2) 1 else 0
        if (maximum !in 0..MAX_FRAME.toLong()) return null
        var loadPc = comparePc - 4
        var load: Access? = null
        while (loadPc >= start && comparePc - loadPc <= 512) {
            val d = decode(loadPc)
            if (d.writes == null || isTransfer(loadPc, requireNotNull(words[loadPc]))) return null
            if (guard.first in d.writes) {
                load = d.memory.singleOrNull { it.load && !it.vector && !it.indexed && it.register == guard.first }
                break
            }
            loadPc -= 4
        }
        val source = load ?: return null
        val objectBase = regions[loadPc]?.get(source.base) ?: return null
        if (objectBase.low != objectBase.high) return null
        val guarded = shiftRegion(objectBase, source.offset.toLong(), source.offset.toLong()) ?: return null
        val bounds = HashMap<Int, Interval>()
        bounds[guard.first] = Interval(0, maximum)
        at = loadPc + 4
        while (at < pc) {
            val raw = words[at] ?: return null
            val d = decode(at)
            if (d.writes == null || at != branch && isTransfer(at, raw)) return null
            for (store in d.memory.filterNot { it.load }) {
                val address = addresses[at]?.get(store.base) ?: return null
                if (isStack(address)) continue
                val objectRegion = regions[at]?.get(store.base) ?: return null
                if (objectRegion.identity != guarded.identity) return null
                val indexed = if (store.indexed) bounds[store.indexRegister]?.let {
                    Interval(it.low * store.indexScale, it.high * store.indexScale)
                } ?: return null else Interval(store.offset.toLong(), store.offset.toLong())
                val range = shiftRegion(objectRegion, indexed.low, indexed.high) ?: return null
                if (range.low.toLong() < guarded.high.toLong() + source.size && guarded.low.toLong() < range.high.toLong() + store.size) return null
            }
            val updates = HashMap<Int, Interval>()
            for (read in d.memory.filter { it.load && !it.vector && !it.indexed }) {
                val region = regions[at]?.get(read.base)?.let { shiftRegion(it, read.offset.toLong(), read.offset.toLong()) }
                if (region == guarded && read.size == source.size) updates[read.register] = Interval(0, maximum)
            }
            d.copy?.let { (rd, rm) -> bounds[rm]?.let { updates[rd] = it } }
            A64.movWImmediate(raw)?.let { updates[it.first] = Interval(it.second.toLong(), it.second.toLong()) }
            if (isMadd(raw)) {
                arithmeticMadd(raw, bounds)?.let { updates[raw and 31] = it }
            }
            d.writes.forEach(bounds::remove)
            bounds.putAll(updates)
            at += 4
        }
        val index = bounds[(word ushr 5) and 31] ?: return null
        val stride = bounds[(word ushr 16) and 31] ?: return null
        if (index.low < 0 || stride.low != stride.high || stride.low <= 0) return null
        return shiftRegion(base, index.low * stride.low, index.high * stride.high)?.copy(guardLow = guarded.low, guardSize = source.size)
    }

    private fun arithmeticMadd(word: Int, bounds: Map<Int, Interval>): Interval? {
        val first = bounds[(word ushr 5) and 31] ?: return null
        val second = bounds[(word ushr 16) and 31] ?: return null
        val accumulator = (word ushr 10) and 31
        val add = if (accumulator == 31) Interval(0, 0) else bounds[accumulator] ?: return null
        val low = first.low * second.low + add.low
        val high = first.high * second.high + add.high
        return if (low in 0..MAX_FRAME.toLong() && high in low..MAX_FRAME.toLong()) Interval(low, high) else null
    }

    private fun isMadd(word: Int) = word and 0x7fe08000 == 0x1b000000
    private fun isTransfer(pc: Long, word: Int) = A64.blTarget(pc, word) != null || A64.bTarget(pc, word) != null ||
        word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 in listOf(0x34000000, 0x36000000) ||
        word and 0xfffffc1f.toInt() in listOf(0xd63f0000.toInt(), 0xd65f0000.toInt(), 0xd61f0000.toInt())

    private fun shiftRegion(region: Region, low: Long, high: Long): Region? {
        val first = region.low.toLong() + low
        val last = region.high.toLong() + high
        return if (first in -MAX_FRAME.toLong()..Int.MAX_VALUE.toLong() && last in first..Int.MAX_VALUE.toLong()) {
            region.copy(low = first.toInt(), high = last.toInt())
        } else null
    }

    /** Prove a closed nonzero stack-flag loop. This is a control-flow property,
     * not a symbol/RVA list. Debugger or external asynchronous memory writes are
     * outside the normal execution model. */
    private fun provenSpinCall(target: Long): Boolean {
        spinCalls[target]?.let { return it }
        // Publish the negative seed first to stop recursive helper inspection.
        spinCalls[target] = false
        val constants = HashMap<Int, Int>()
        val state = IntArray(32) { if (it == 31) 0 else NON_STACK }
        val stored = HashMap<Int, Int>()
        var pc = target
        repeat(96) {
            if (!image.isCode(pc)) return false
            val word = image.word(pc) ?: return false
            val d = decode(pc)
            val load = d.memory.singleOrNull { it.load && !it.vector && !it.indexed && it.base == 31 && it.size == 4 }
            if (load != null) {
                val branchPc = pc + 4
                val branch = image.word(branchPc) ?: return false
                val back = if (branch and 0xff000000.toInt() == 0x35000000) {
                    branchPc + (signExtend(((branch ushr 5) and 0x7ffff).toLong(), 19) shl 2)
                } else -1L
                val key = addOffset(state[31], load.offset)
                if (branch and 31 == load.register && back == pc && stored[key]?.let { value -> value != 0 } == true) {
                    spinCalls[target] = true
                    return true
                }
            }
            if (A64.bTarget(pc, word) != null || word and 0xff000010.toInt() == 0x54000000 ||
                word and 0x7e000000 in listOf(0x34000000, 0x36000000) ||
                word and 0xfffffc1f.toInt() in listOf(0xd65f0000.toInt(), 0xd61f0000.toInt())) return false
            if (d.writes == null) return false
            if (d.call && (0..7).any { register -> isStack(state[register]) || state[register] == UNKNOWN_ADDRESS }) return false
            if (stored.isNotEmpty() && (d.writeBack?.first == 31 ||
                    word and 0x1f000000 == 0x11000000 && word and 31 == 31 && word and 0x20000000 == 0)) return false
            for (store in d.memory.filterNot { it.load }) {
                val base = state[store.base]
                if (base == NON_STACK) continue
                if (!isStack(base) || store.indexed || store.vector) return false
                val at = addOffset(base, store.offset)
                val overlapping = stored.keys.filter { key -> key < at + store.size && at < key + 4 }
                overlapping.forEach(stored::remove)
                if (store.size == 4) {
                    val value = if (store.register == 31) 0 else constants[store.register]
                    if (value != null) stored[at] = value
                }
            }
            val nextState = addressTransfer(pc, state)
            d.writes.forEach(constants::remove)
            A64.movWImmediate(word)?.let { constants[it.first] = it.second }
            nextState.copyInto(state)
            pc += 4
        }
        return false
    }

    /** Outlined compiler helpers often use BL while preserving ordinary argument
     * registers. Prove their small leaf write set; all other calls retain ABI
     * volatile clobbers. No symbol name, address or firmware identity is used. */
    private fun smallLeafCallWrites(target: Long): Set<Int>? {
        if (leafCallWrites.containsKey(target)) return leafCallWrites[target]
        val pending = arrayListOf(target)
        val visited = HashSet<Long>()
        val written = HashSet<Int>()
        var returns = false
        var index = 0
        var valid = true
        while (index < pending.size && valid) {
            val pc = pending[index++]
            if (!visited.add(pc)) continue
            if (visited.size > 128 || !image.isCode(pc)) { valid = false; break }
            val word = image.word(pc) ?: run { valid = false; break }
            if (A64.blTarget(pc, word) != null || word and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) {
                valid = false; break
            }
            if (word and 0xfffffc1f.toInt() == 0xd61f0000.toInt()) { valid = false; break }
            val effects = decode(pc).writes
            if (effects == null) { valid = false; break }
            written += effects.filter { it in 0..18 }
            val following = next(pc, word)
            if (following.isEmpty()) returns = true else pending += following
        }
        val result = if (valid && returns) written.toSet() else null
        leafCallWrites[target] = result
        return result
    }
    private fun unknown() = Equation(emptyList(), setOf(A64ValueOrigin.Unknown))
    private fun zero() = Equation(emptyList(), setOf(A64ValueOrigin.Zero))
    private fun definition(pc: Long, register: Int) = Equation(emptyList(), setOf(A64ValueOrigin.Definition(pc, register)))

    private fun isStack(address: Int) = address in -MAX_FRAME..MAX_FRAME
    private fun addOffset(base: Int, delta: Int): Int = when {
        base == NON_STACK -> NON_STACK
        !isStack(base) -> UNKNOWN_ADDRESS
        base.toLong() + delta !in -MAX_FRAME.toLong()..MAX_FRAME.toLong() -> UNKNOWN_ADDRESS
        else -> base + delta
    }

    private fun addGlobal(base: Long, delta: Long): Long = when {
        base == NON_GLOBAL -> NON_GLOBAL
        base < 0 -> UNKNOWN_GLOBAL
        base + delta !in 0..0xffff_ffffL -> UNKNOWN_GLOBAL
        else -> base + delta
    }

    private companion object {
        const val UNKNOWN_ADDRESS = Int.MIN_VALUE
        const val NON_STACK = Int.MIN_VALUE + 1
        const val UNKNOWN_GLOBAL = Long.MIN_VALUE
        const val NON_GLOBAL = Long.MIN_VALUE + 1
        const val MAX_FRAME = 0x1000000
        const val MAX_INSTRUCTIONS = 100000
        const val MAX_ADDRESS_STEPS = 1000000
        const val MAX_NODES = 250000
        const val MAX_PROPAGATION_STEPS = 2000000
        const val MAX_ORIGINS = 128
    }
}
