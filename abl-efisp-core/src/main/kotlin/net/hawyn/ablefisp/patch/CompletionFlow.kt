package net.hawyn.ablefisp.patch

/** Bounded intra-procedural reaching definitions. Calls are modelled as returns,
 * never as proof that an external service accepts the resulting input. */
internal class CompletionFlow @JvmOverloads constructor(
    private val image: PeImage, private val start: Long, private val end: Long,
    private val frameDistinct: Set<Int> = emptySet(),
    private val objectAliases: ((Long, Int, Int) -> Int?)? = null,
) {
    private val predecessors = HashMap<Long, MutableList<Long>>()
    private val reachable = HashSet<Long>()

    init {
        val pending = arrayListOf(start)
        var index = 0
        while (index < pending.size) {
            val pc = pending[index++]
            if (pc !in start..<end || !image.isCode(pc) || !reachable.add(pc)) continue
            val word = requireNotNull(image.word(pc))
            for (next in successors(pc, word)) {
                if (next !in start..<end || next and 3 != 0L) continue
                predecessors.getOrPut(next) { arrayListOf() }.add(pc)
                if (next !in reachable) pending += next
            }
        }
    }

    fun hasOnlyDefinition(target: Long, expected: Long, base: Int, offset: Int, count: Int): Boolean {
        return hasOnlyDefinitions(target, setOf(expected), base, offset, count)
    }

    fun hasOnlyDefinitions(target: Long, expected: Set<Long>, base: Int, offset: Int, count: Int): Boolean {
        if (target !in reachable || expected.isEmpty() || expected.any { it !in reachable }) return false
        val pending = ArrayList(predecessors[target].orEmpty())
        val visited = HashSet<Long>()
        val definitions = HashSet<Long>()
        var index = 0
        while (index < pending.size) {
            val pc = pending[index++]
            if (!visited.add(pc)) continue
            if (writes(pc, base, offset, count)) {
                definitions += pc
            } else {
                if (pc == start) return false
                pending += predecessors[pc].orEmpty()
            }
        }
        return definitions == expected
    }

    private fun successors(pc: Long, word: Int): List<Long> {
        A64.bTarget(pc, word)?.let { return listOf(it) }
        if (word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 == 0x34000000) {
            return listOf(pc + 4, pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2))
        }
        if (word and 0x7e000000 == 0x36000000) {
            return listOf(pc + 4, pc + (signExtend(((word ushr 5) and 0x3fff).toLong(), 14) shl 2))
        }
        // RET/RETAA/RETAB and indirect BR leave this function; BL/BLR return.
        if (word and 0xfffffc1f.toInt() == 0xd65f0000.toInt() ||
            word in listOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt()) ||
            word and 0xfffffc1f.toInt() == 0xd61f0000.toInt()) return emptyList()
        return listOf(pc + 4)
    }

    private fun writes(pc: Long, wantedBase: Int, wantedOffset: Int, count: Int): Boolean {
        val w = requireNotNull(image.word(pc))
        val rn = (w ushr 5) and 31
        // A different stack frame or a redefined context is a different input object.
        if (wantedBase == 31) {
            if (w and 0x1f000000 == 0x11000000 && w and 0x20000000 == 0 && w and 31 == 31) return true
            if (w and 0x1f200000 == 0x0b200000 && w and 0x20000000 == 0 && w and 31 == 31) return true
            if (rn == 31 && w and 0x3a000000 == 0x28000000 && (w ushr 23) and 3 in listOf(1, 3)) return true
            if (rn == 31 && w and 0x3b200000 == 0x38000000 && (w ushr 10) and 3 in listOf(1, 3)) return true
        } else if (StraightLineLiveness.mayWriteRegister(w, wantedBase)) return true
        val vector = w and 0x04000000 != 0
        val operation = (w ushr 22) and 3
        val scalarOrVectorStore = if (vector) operation == 0 || operation == 2 else operation == 0
        val pairStore = w and 0x3a400000 == 0x28000000
        val singleStore = w and 0x3b000000 in listOf(0x39000000, 0x38000000) && scalarOrVectorStore
        if (!pairStore && !singleStore) return false
        val baseDelta = when {
            rn == wantedBase -> 0
            wantedBase == 31 && rn in frameDistinct -> return false
            wantedBase == 31 && frameDistinct.any { objectAliases?.invoke(pc, rn, it) != null } -> return false
            wantedBase == 31 -> {
                val alias = stackAlias(pc, rn) ?: return true
                if (alias == Int.MIN_VALUE) return false
                alias
            }
            else -> objectAliases?.invoke(pc, rn, wantedBase) ?: return false
        }
        fun overlaps(offset: Int, bytes: Int) = baseDelta + offset < wantedOffset + count &&
            wantedOffset < baseDelta + offset + bytes
        // Scalar/SIMD unsigned-immediate stores, preserving their access width.
        if (w and 0x3b000000 == 0x39000000 && scalarOrVectorStore) {
            val bytes = if (vector && operation == 2) 16 else 1 shl (w ushr 30)
            return overlaps(((w ushr 10) and 0xfff) * bytes, bytes)
        }
        // STP offset. Other address modes can move SP and are conservatively hazardous.
        if (w and 0x3a400000 == 0x28000000) {
            if ((w ushr 23) and 3 != 2) return true
            val opc = w ushr 30
            val bytes = if (w and 0x04000000 != 0) 4 shl opc else if (opc == 2) 8 else 4
            return overlaps(signExtend(((w ushr 15) and 127).toLong(), 7).toInt() * bytes, bytes * 2)
        }
        // Unscaled scalar/SIMD stores; pre/post-index stores have writeback.
        if (w and 0x3b200000 == 0x38000000 && scalarOrVectorStore) {
            if ((w ushr 10) and 3 != 0) return true
            val bytes = if (vector && operation == 2) 16 else 1 shl (w ushr 30)
            return overlaps(signExtend(((w ushr 12) and 0x1ff).toLong(), 9).toInt(), bytes)
        }
        if (w and 0x3b200c00 == 0x38200800 && scalarOrVectorStore) return true
        return false
    }

    private fun stackAlias(pc: Long, register: Int): Int? {
        // Resolve a local ADD Xn,SP,#offset through a straight preparation window.
        // Unknown control flow or a different write to the alias ends this proof.
        var current = register
        var delta = 0
        for (n in 1..24) {
            val at = pc - n * 4L
            if (at < start) break
            val w = image.word(at) ?: return null
            A64.add(w)?.let { if (it.rd == current) {
                delta += it.immediate.toInt()
                if (it.rn == 31) return delta
                current = it.rn
                continue
            } }
            if (w and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt() && w and 31 == current) {
                current = (w ushr 16) and 31
                if (current == 31) return Int.MIN_VALUE
                continue
            }
            if (A64.adrp(at, w)?.first == current || A64.adr(at, w)?.first == current ||
                A64.movWImmediate(w)?.first == current) return Int.MIN_VALUE
            if (A64.blTarget(at, w) != null || A64.bTarget(at, w) != null ||
                w and 0xff000010.toInt() == 0x54000000 ||
                w and 0x7e000000 in listOf(0x34000000, 0x36000000)) return null
            if (StraightLineLiveness.mayWriteRegister(w, current)) return null
        }
        return null
    }
}
