package net.hawyn.ablefisp.patch

/** Small abstract execution of a state selector, following edges rather than
 * assuming an adjacent MOV/B/LDRB/CSET/LSL sequence or a particular register. */
internal object StateBranchEvaluator {
    data class Access(val load: Boolean, val width: Int, val rt: Int, val rn: Int, val offset: Int)
    data class Result(val store: Long, val base: Int, val offset: Int, val values: Set<Int>, val producer: Long, val register: Int)
    private data class Value(val values: Set<Int>, val producer: Long = -1, val register: Int = -1)
    private data class State(val pc: Long, val registers: Map<Int, Value>, val aliases: Map<Int, Int>, val equal: Set<Boolean>?)

    fun access(w: Int): Access? {
        if (w and 0x04000000 != 0) return null
        val operation = (w ushr 22) and 3
        if (operation !in 0..1) return null
        val width = 1 shl (w ushr 30)
        val offset = when {
            w and 0x3b000000 == 0x39000000 -> ((w ushr 10) and 0xfff) * width
            w and 0x3b200c00 == 0x38000000 -> signExtend(((w ushr 12) and 0x1ff).toLong(), 9).toInt()
            else -> return null
        }
        return Access(operation == 1, width, w and 31, (w ushr 5) and 31, offset)
    }

    /** Returns zero/nonzero successors for a boolean test. */
    fun booleanEdges(pc: Long, w: Int): Pair<Long, Long>? {
        val zero: Boolean
        val destination: Long
        when {
            w and 0x7e000000 == 0x36000000 && ((w ushr 19) and 31) == 0 && w ushr 31 == 0 -> {
                zero = w and 0x01000000 == 0
                destination = pc + (signExtend(((w ushr 5) and 0x3fff).toLong(), 14) shl 2)
            }
            w and 0x7e000000 == 0x34000000 -> {
                zero = w and 0x01000000 == 0
                destination = pc + (signExtend(((w ushr 5) and 0x7ffff).toLong(), 19) shl 2)
            }
            else -> return null
        }
        return if (zero) destination to pc + 4 else pc + 4 to destination
    }

    fun evaluate(image: PeImage, start: Long, end: Long, entry: Long, testedRegister: Int, nonzero: Boolean): List<Result> {
        val pending = arrayListOf(State(entry, mapOf(testedRegister to Value(setOf(if (nonzero) 1 else 0))), emptyMap(), null))
        val seen = HashSet<State>()
        val edges = HashMap<State, MutableSet<State>>()
        val terminal = HashSet<State>()
        val result = ArrayList<Result>()
        var index = 0
        while (index < pending.size && seen.size < 512) {
            val s = pending[index++]
            if (s.pc !in start..<end || !image.isCode(s.pc) || !seen.add(s)) continue
            val pc = s.pc
            val w = image.word(pc) ?: continue
            val regs = s.registers.toMutableMap()
            val aliases = s.aliases.toMutableMap()
            var equal = s.equal
            fun value(r: Int): Value? = if (r == 31) Value(setOf(0)) else regs[r]
            fun assign(r: Int, v: Value?) { if (r != 31) { if (v == null) regs.remove(r) else regs[r] = v; aliases[r] = -1 } }
            fun next(to: Long) {
                val successor = State(to, regs.toMap(), aliases.toMap(), equal)
                edges.getOrPut(s) { hashSetOf() }.add(successor)
                pending += successor
            }
            A64.bTarget(pc, w)?.let { next(it); continue }
            booleanEdges(pc, w)?.let { edges ->
                val values = value(w and 31)?.values
                val bitTest = w and 0x7e000000 == 0x36000000
                if (values == null || values.any { if (bitTest) it and 1 == 0 else it == 0 }) next(edges.first)
                if (values == null || values.any { if (bitTest) it and 1 != 0 else it != 0 }) next(edges.second)
                continue
            }
            if (w and 0xff000010.toInt() == 0x54000000) {
                val condition = w and 15
                if (condition !in 0..1) continue
                val destination = pc + (signExtend(((w ushr 5) and 0x7ffff).toLong(), 19) shl 2)
                val possibilities = equal?.map { if (condition == 0) it else !it }?.toSet() ?: setOf(false, true)
                if (true in possibilities) next(destination)
                if (false in possibilities) next(pc + 4)
                continue
            }
            val memory = access(w)
            when {
                memory != null && !memory.load -> {
                    if (memory.width == 4 && memory.rn != 31) {
                        val v = value(memory.rt) ?: continue
                        val base = aliases[memory.rn] ?: memory.rn
                        if (base >= 0 && v.values.isNotEmpty() && v.values.all { it in 0..2 }) {
                            result += Result(pc, base, memory.offset, v.values, v.producer, v.register)
                            terminal += s
                        }
                        continue
                    }
                }
                memory != null -> assign(memory.rt, null)
                A64.movWImmediate(w) != null -> {
                    val (r, n) = requireNotNull(A64.movWImmediate(w))
                    assign(r, Value(setOf(n), pc, r))
                }
                w and 0xffe0ffe0.toInt() in listOf(0x2a0003e0, 0xaa0003e0.toInt()) -> {
                    val r = w and 31; val source = (w ushr 16) and 31
                    val alias = aliases[source] ?: source
                    assign(r, value(source))
                    if (w ushr 31 == 1) aliases[r] = alias
                }
                A64.cmpImmediate(w) != null -> {
                    val (r, n) = requireNotNull(A64.cmpImmediate(w))
                    equal = value(r)?.values?.map { it == n }?.toSet()
                }
                w and 0xffe00fe0.toInt() == 0x1a8007e0 && (w ushr 16) and 31 == 31 -> {
                    val condition = (w ushr 12) and 15
                    val v = if (condition in 0..1 && equal != null) equal.map { if ((condition == 0) == it) 0 else 1 }.toSet() else setOf(0, 1)
                    assign(w and 31, Value(v))
                }
                w and 0xff800000.toInt() == 0x53000000 -> {
                    val source = value((w ushr 5) and 31)
                    val rotate = (w ushr 16) and 31; val mask = (w ushr 10) and 31
                    val mapped = when {
                        source == null -> null
                        mask + 1 == rotate -> source.values.map { it shl (32 - rotate) }.toSet()
                        mask == 31 -> source.values.map { it ushr rotate }.toSet()
                        else -> null
                    }
                    assign(w and 31, mapped?.let { Value(it) })
                }
                w and 0xffe00000.toInt() == 0x0b000000 -> {
                    val a = value((w ushr 5) and 31); val b = value((w ushr 16) and 31)
                    val shift = (w ushr 10) and 31
                    val mapped = if (a == null || b == null) null else if (a === b) a.values.map { it + (it shl shift) }.toSet()
                        else a.values.flatMap { x -> b.values.map { y -> x + (y shl shift) } }.toSet()
                    assign(w and 31, mapped?.let { Value(it) })
                }
                A64.blTarget(pc, w) != null || w and 0xfffffc1f.toInt() == 0xd63f0000.toInt() -> {
                    (0..18).forEach { assign(it, null) }; equal = null
                }
                w == 0xd503201f.toInt() -> Unit
                else -> continue
            }
            next(pc + 4)
        }
        // Every feasible successor must reach a proven store. Unknown operations,
        // escaping edges and closed cycles must not disappear from the result.
        if (index < pending.size) return emptyList()
        val proven = terminal.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            for (s in seen) {
                val outgoing = edges[s].orEmpty()
                if (s !in proven && outgoing.isNotEmpty() && outgoing.all { it in proven }) changed = proven.add(s) || changed
            }
        } while (changed)
        return if (pending.first() in proven) result.distinct() else emptyList()
    }
}
