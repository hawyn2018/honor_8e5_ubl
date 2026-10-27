package net.hawyn.ablefisp.text

/** Resolves hoisted X0/W1 arguments only when every local incoming path agrees. */
internal class TextArgumentFlow(private val image: TextPe) {
    private val incoming = mutableMapOf<Long, MutableList<Long>>()
    init {
        for (pc in image.codeWords()) {
            val word = image.word(pc).toLong() and 0xffff_ffffL
            val target = when {
                word and 0xfc000000L == 0x14000000L -> pc + (sxText(word and 0x3ffffff, 26) shl 2)
                word and 0xff000010L == 0x54000000L || word and 0x7e000000L == 0x34000000L -> pc + (sxText((word ushr 5) and 0x7ffff, 19) shl 2)
                word and 0x7e000000L == 0x36000000L -> pc + (sxText((word ushr 5) and 0x3fff, 14) shl 2)
                else -> null
            }
            if (target != null) incoming.getOrPut(target) { mutableListOf() } += pc
        }
    }

    fun definition(at: Long, register: Int): Long? = runCatching {
        require(register in 0..1)
        val low = maxOf(0, at - 0x180)
        val high = at + 0x180
        val todo = ArrayDeque<Long>()
        val visited = mutableSetOf<Long>()
        val definitions = mutableSetOf<Long>()
        fun addPredecessors(pc: Long): Boolean {
            val direct = incoming[pc].orEmpty().toMutableList()
            val previous = pc - 4
            val word = image.word(previous).toLong() and 0xffff_ffffL
            val indirect = word and 0xfe000000L == 0xd6000000L
            val indirectCall = word and 0xfffffc1fL == 0xd63f0000L
            if (word and 0xfc000000L != 0x14000000L && (!indirect || indirectCall)) direct += previous
            if (direct.isEmpty() || direct.any { it !in low..high }) return false
            todo.addAll(direct)
            return true
        }
        if (!addPredecessors(at)) return@runCatching null
        while (todo.isNotEmpty()) {
            val pc = todo.removeLast()
            if (!visited.add(pc)) continue
            val word = image.word(pc).toLong() and 0xffff_ffffL
            val accepted = if (register == 0) TextA64.stackAddress(word.toInt(), 0) != null else TextA64.movImmediate(word.toInt(), 1) != null
            if (accepted) { definitions += pc; continue }
            if (word and 0xfc000000L == 0x94000000L || word and 0xfffffc1fL == 0xd63f0000L || writesRegister(word, register)) return@runCatching null
            if (!addPredecessors(pc)) return@runCatching null
        }
        definitions.singleOrNull()
    }.getOrNull()

    private fun writesRegister(word: Long, register: Int): Boolean {
        val target = register.toLong()
        // CCMP/CCMN encode NZCV in the low bits, not a destination register.
        if (word and 0x1fe00000L == 0x1a400000L) return false
        if (((word ushr 5) and 31) == target && ((word and 0x3a000000L == 0x28000000L && word and 0x00800000L != 0L) || (word and 0x3b200c00L) in listOf(0x38000400L, 0x38000c00L))) return true
        if (word and 0x04400000L == 0x00400000L && word and 0x3a000000L == 0x28000000L && ((word ushr 10) and 31) == target) return true
        if (word and 31 != target) return false
        if (word and 0x3b000000L == 0x18000000L && word and 0x04000000L == 0L) return true
        if ((word and 0x9f000000L) in listOf(0x10000000L, 0x90000000L)) return true
        if ((word and 0x1f000000L) in listOf(0x11000000L, 0x12000000L, 0x13000000L, 0x0b000000L, 0x0a000000L, 0x1a000000L, 0x1b000000L)) return true
        if (word and 0x1f800000L == 0x12800000L) return true
        if (word and 0x04000000L == 0L && word and 0x00400000L != 0L && (word and 0x3a000000L == 0x28000000L || (word and 0x3b000000L) in listOf(0x39000000L, 0x38000000L))) return true
        return (word and 0x7f3ffc00L) in listOf(0x1e380000L, 0x1e390000L, 0x1e260000L)
    }
}
