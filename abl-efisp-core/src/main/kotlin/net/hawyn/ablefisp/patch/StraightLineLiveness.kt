package net.hawyn.ablefisp.patch

/** Bounded straight-line proofs used only around the state/property selectors. */
internal object StraightLineLiveness {
    private data class Effect(val writes: Set<Int> = emptySet(), val flags: Boolean = false)

    fun mayWriteRegister(word: Int, register: Int): Boolean {
        if (A64.blTarget(0, word) != null || A64.bTarget(0, word) != null ||
            word and 0xff000010.toInt() == 0x54000000 ||
            word and 0x7e000000 in listOf(0x34000000, 0x36000000) ||
            word and 0xfffffc1f.toInt() in listOf(0xd63f0000.toInt(), 0xd65f0000.toInt())) return register == 30
        return effect(word)?.writes?.contains(register) ?: true
    }

    /**
     * The definition immediately before [from] must survive until [until].
     * Unmodelled instructions and every transfer of control stop the proof.
     * Register numbers cover both their W and X aliases; vector writes are separate.
     */
    fun preserves(
        image: PeImage,
        from: Long,
        until: Long,
        registers: Set<Int> = emptySet(),
        flags: Boolean = false,
    ): Boolean {
        if (from > until || from and 3 != 0L || until and 3 != 0L) return false
        var pc = from
        while (pc < until) {
            if (!image.isCode(pc)) return false
            val effect = image.word(pc)?.let(::effect) ?: return false
            if (effect.writes.any { it in registers } || flags && effect.flags) return false
            pc += 4
        }
        return true
    }

    /** A64.references records ADRP's PC, so include its actual ADD definition. */
    fun referenceEnd(image: PeImage, reference: StringReference): Long? {
        val first = image.word(reference.pc) ?: return null
        A64.adr(reference.pc, first)?.let {
            return if (it.first == reference.register && it.second == reference.address) reference.pc + 4 else null
        }
        val page = A64.adrp(reference.pc, first) ?: return null
        for (n in 1..4) {
            val pc = reference.pc + n * 4
            val add = image.word(pc)?.let(A64::add) ?: continue
            if (add.rn != page.first || add.rd != reference.register || page.second + add.immediate != reference.address) continue
            if (!preserves(image, reference.pc + 4, pc, setOf(page.first))) return null
            return pc + 4
        }
        return null
    }

    private fun effect(word: Int): Effect? {
        fun writes(vararg registers: Int, flags: Boolean = false) =
            Effect(registers.filter { it != 31 }.toSet(), flags)
        if (word == 0xd503201f.toInt()) return Effect() // NOP
        if (A64.adr(0, word) != null || A64.adrp(0, word) != null) return writes(word and 31)

        // Integer/FP/SIMD load/store pairs. Only integer loads write GP destinations.
        if (word and 0x3a000000 == 0x28000000) {
            val writeBack = (word ushr 23) and 3 in listOf(1, 3)
            val integerLoad = word and 0x00400000 != 0 && word and 0x04000000 == 0
            return writes(
                if (writeBack) (word ushr 5) and 31 else 31,
                if (integerLoad) word and 31 else 31,
                if (integerLoad) (word ushr 10) and 31 else 31,
            )
        }

        // Unsigned immediate memory forms never update their base register.
        if (word and 0x3b000000 == 0x39000000) {
            val vector = word and 0x04000000 != 0
            val operation = (word ushr 22) and 3
            val prefetch = word ushr 30 == 3 && operation == 2
            return if (!vector && operation != 0 && !prefetch) writes(word and 31) else Effect()
        }
        // Unscaled/pre/post-index and register-offset memory forms.
        if (word and 0x3b000000 == 0x38000000) {
            val registerOffset = word and 0x00200000 != 0
            if (registerOffset && word and 0x00000c00 != 0x00000800) return null
            val mode = (word ushr 10) and 3
            val writeBack = !registerOffset && mode in listOf(1, 3)
            val vector = word and 0x04000000 != 0
            val operation = (word ushr 22) and 3
            val prefetch = word ushr 30 == 3 && operation == 2
            return writes(
                if (writeBack) (word ushr 5) and 31 else 31,
                if (!vector && operation != 0 && !prefetch) word and 31 else 31,
            )
        }
        if (word and 0x3b000000 == 0x18000000) { // literal loads
            return if (word and 0x04000000 == 0 && word ushr 30 != 3) writes(word and 31) else Effect()
        }

        // ADD/SUB immediate or shifted/extended register, including CMP/CMN aliases.
        if (word and 0x1f000000 in listOf(0x11000000, 0x0b000000)) {
            return writes(word and 31, flags = word and 0x20000000 != 0)
        }
        // Logical immediate / shifted register; only ANDS/BICS update NZCV.
        if (word and 0x1f800000 == 0x12000000 || word and 0x1f000000 == 0x0a000000) {
            return writes(word and 31, flags = (word ushr 29) and 3 == 3)
        }
        // Move-wide, bitfield/shift aliases and EXTR.
        if (word and 0x1f800000 in listOf(0x12800000, 0x13000000, 0x13800000)) return writes(word and 31)
        // CSEL/CSINC/CSINV/CSNEG, including CSET: consume but do not modify NZCV.
        if (word and 0x1fe00000 == 0x1a800000) return writes(word and 31)
        return null
    }
}
