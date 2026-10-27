package net.hawyn.ablefisp.patch

internal data class StringReference(val pc: Long, val address: Long, val register: Int)
internal object A64 {
    fun blTarget(pc: Long, w: Int): Long? = if (w and 0xfc000000.toInt() == 0x94000000.toInt()) pc + (signExtend((w and 0x03ffffff).toLong(), 26) shl 2) else null
    fun bTarget(pc: Long, w: Int): Long? = if (w and 0xfc000000.toInt() == 0x14000000) pc + (signExtend((w and 0x03ffffff).toLong(), 26) shl 2) else null
    fun cbzW0(pc: Long, w: Int): Long? = if (w and 0xff00001f.toInt() == 0x34000000) pc + (signExtend(((w ushr 5) and 0x7ffff).toLong(), 19) shl 2) else null
    fun cbzX0(pc: Long, w: Int): Long? = if (w and 0xff00001f.toInt() == 0xb4000000.toInt()) pc + (signExtend(((w ushr 5) and 0x7ffff).toLong(), 19) shl 2) else null
    fun beqTarget(pc: Long, w: Int): Long? = if (w and 0xff00001f.toInt() == 0x54000000) pc + (signExtend(((w ushr 5) and 0x7ffff).toLong(), 19) shl 2) else null
    fun tbnzW0Bit31(pc: Long, w: Int): Long? = if (w and 0xfff8001f.toInt() == 0x37f80000) pc + (signExtend(((w ushr 5) and 0x3fff).toLong(), 14) shl 2) else null
    fun branch(pc: Long, target: Long): Int {
        val displacement = target - pc
        if (displacement and 3 != 0L || displacement < -0x08000000L || displacement >= 0x08000000L) throw PatchException("分支目标超出 ARM64 指令范围。")
        return 0x14000000 or ((displacement shr 2).toInt() and 0x03ffffff)
    }

    fun movWImmediate(w: Int): Pair<Int, Int>? {
        if (w and 0xff800000.toInt() == 0x52800000 && (w ushr 21) and 3 == 0) return (w and 31) to ((w ushr 5) and 0xffff)
        if (w and 0x7fffffe0 == 0x2a1f03e0) return (w and 31) to 0
        return null
    }
    fun movXZero(w: Int): Int? = if (w and 0xffffffe0.toInt() == 0xaa1f03e0.toInt()) w and 31 else null
    fun cmpImmediate(w: Int): Pair<Int, Int>? = if (w and 0x7f00001f == 0x7100001f) {
        ((w ushr 5) and 31) to (((w ushr 10) and 0xfff) shl if (w and 0x00400000 != 0) 12 else 0)
    } else null

    fun adrp(pc: Long, w: Int): Pair<Int, Long>? = if (w and 0x9f000000.toInt() == 0x90000000.toInt()) {
        val imm = (((w ushr 5) and 0x7ffff).toLong() shl 2) or ((w ushr 29) and 3).toLong()
        (w and 31) to ((pc and -4096L) + (signExtend(imm, 21) shl 12))
    } else null
    fun adr(pc: Long, w: Int): Pair<Int, Long>? = if (w and 0x9f000000.toInt() == 0x10000000) {
        val imm = (((w ushr 5) and 0x7ffff).toLong() shl 2) or ((w ushr 29) and 3).toLong()
        (w and 31) to (pc + signExtend(imm, 21))
    } else null
    data class Add(val rd: Int, val rn: Int, val immediate: Long)
    fun add(w: Int): Add? = if (w and 0xff800000.toInt() == 0x91000000.toInt()) {
        Add(w and 31, (w ushr 5) and 31, ((w ushr 10) and 0xfff).toLong() shl if (w and 0x00400000 != 0) 12 else 0)
    } else null
    data class StpW(val rt: Int, val rt2: Int, val rn: Int, val offset: Long)
    fun stpW(w: Int): StpW? = if (w and 0xffc00000.toInt() == 0x29000000) {
        StpW(w and 31, (w ushr 10) and 31, (w ushr 5) and 31, signExtend(((w ushr 15) and 127).toLong(), 7) * 4)
    } else null
    fun ldpW(w: Int): StpW? = if (w and 0xffc00000.toInt() == 0x29400000) {
        StpW(w and 31, (w ushr 10) and 31, (w ushr 5) and 31, signExtend(((w ushr 15) and 127).toLong(), 7) * 4)
    } else null

    sealed interface Value {
        data class Constant(val value: Long) : Value
        data class Stack(val offset: Long) : Value
        data class Address(val rva: Long) : Value
    }
    data class Cell(val value: Value, val size: Int)
    data class State(val registers: Map<Int, Value>, val stack: Map<Long, Cell>)

    /** Local argument dataflow, not execution emulation. Only straight descriptor-preparation
     * windows are accepted; calls invalidate volatile registers, and stack fields retain
     * their source identities (message, signature pointer, and explicit lengths). */
    fun argumentState(image: PeImage, start: Long, endExclusive: Long): State {
        val registers = HashMap<Int, Value>()
        val stack = HashMap<Long, Cell>()
        fun base(register: Int): Value? = if (register == 31) Value.Stack(0) else registers[register]
        fun addValue(value: Value, delta: Long): Value = when (value) {
            is Value.Constant -> Value.Constant(value.value + delta)
            is Value.Stack -> Value.Stack(value.offset + delta)
            is Value.Address -> Value.Address(value.rva + delta)
        }
        fun put(address: Value?, offset: Long, value: Value?, size: Int) {
            if (address !is Value.Stack) return
            val at = address.offset + offset
            val overlapping = stack.filter { (key, cell) -> key < at + size && at < key + cell.size }.keys
            overlapping.forEach(stack::remove)
            if (value != null) stack[at] = Cell(value, size)
        }
        var pc = (start + 3) and -4L
        while (pc < endExclusive) {
            val w = image.word(pc) ?: break
            when {
                blTarget(pc, w) != null -> (0..18).forEach(registers::remove)
                adrp(pc, w) != null -> adrp(pc, w)!!.let { registers[it.first] = Value.Address(it.second) }
                adr(pc, w) != null -> adr(pc, w)!!.let { registers[it.first] = Value.Address(it.second) }
                add(w) != null -> add(w)!!.let { a ->
                    if (a.rd != 31) {
                        val source = base(a.rn)
                        if (source == null) registers.remove(a.rd) else registers[a.rd] = addValue(source, a.immediate)
                    }
                }
                movWImmediate(w) != null -> movWImmediate(w)!!.let { registers[it.first] = Value.Constant(it.second.toLong()) }
                movXZero(w) != null -> registers[movXZero(w)!!] = Value.Constant(0)
                w and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt() -> {
                    val destination = w and 31; val source = (w ushr 16) and 31
                    val value = if (source == 31) Value.Constant(0) else registers[source]
                    if (value == null) registers.remove(destination) else registers[destination] = value
                }
                w and 0xffc00000.toInt() in listOf(0xf9000000.toInt(), 0xb9000000.toInt(), 0x79000000) -> {
                    val size = when (w ushr 30) { 3 -> 8; 2 -> 4; else -> 2 }
                    val value = if (w and 31 == 31) Value.Constant(0) else registers[w and 31]
                    put(base((w ushr 5) and 31), ((w ushr 10) and 0xfff).toLong() * size, value, size)
                }
                w and 0xffc00000.toInt() == 0xa9000000.toInt() || stpW(w) != null -> {
                    val size = if (w ushr 31 == 1) 8 else 4
                    val address = base((w ushr 5) and 31)
                    val offset = signExtend(((w ushr 15) and 127).toLong(), 7) * size
                    val first = w and 31; val second = (w ushr 10) and 31
                    put(address, offset, if (first == 31) Value.Constant(0) else registers[first], size)
                    put(address, offset + size, if (second == 31) Value.Constant(0) else registers[second], size)
                }
                w and 0x3b400000 == 0x39400000 -> registers.remove(w and 31)
            }
            pc += 4
        }
        return State(registers, stack)
    }

    fun immediateBefore(image: PeImage, call: Long, register: Int, maxInstructions: Int = 28): Int? {
        for (n in 1..maxInstructions) {
            val pc = call - n * 4L
            val w = image.word(pc) ?: return null
            if (blTarget(pc, w) != null || w == 0xd65f03c0.toInt()) return null
            movWImmediate(w)?.let { if (it.first == register) return it.second }
            if (adrp(pc, w)?.first == register || adr(pc, w)?.first == register || add(w)?.rd == register) return null
            // General scalar loads overwrite their destination register.
            if (w and 0x3b400000 == 0x39400000 && w and 31 == register) return null
        }
        return null
    }

    fun references(image: PeImage): List<StringReference> {
        val result = ArrayList<StringReference>()
        for ((pc, w) in image.instructions()) {
            adr(pc, w)?.takeIf { it.first != 31 }?.let { result += StringReference(pc, it.second, it.first) }
            val page = adrp(pc, w)?.takeIf { it.first != 31 } ?: continue
            for (n in 1..4) {
                val at = pc + n * 4
                val next = image.word(at) ?: break
                val added = add(next)
                if (added?.rn == page.first) {
                    result += StringReference(pc, page.second + added.immediate, added.rd)
                    break
                }
                if (blTarget(at, next) != null || bTarget(at, next) != null || adrp(at, next)?.first == page.first) break
            }
        }
        return result
    }

    fun registerAddress(image: PeImage, start: Long, endExclusive: Long, register: Int): Long? {
        val values = HashMap<Int, Long>()
        var pc = start
        while (pc < endExclusive) {
            val w = image.word(pc) ?: return null
            adrp(pc, w)?.let { values[it.first] = it.second }
            adr(pc, w)?.let { values[it.first] = it.second }
            add(w)?.let { add -> values[add.rn]?.let { values[add.rd] = it + add.immediate } }
            pc += 4
        }
        return values[register]
    }

    fun byteCopyHelper(image: PeImage, target: Long, counter: Int, sourceRegister: Int? = null): Boolean {
        var load: Int? = null
        var store: Int? = null
        var increment = false
        for (n in 0..8) {
            val w = image.word(target + n * 4) ?: return false
            if (w and 0xffe00c00.toInt() == 0x38600800 && (w ushr 16) and 31 == counter &&
                (sourceRegister == null || (w ushr 5) and 31 == sourceRegister)) load = w and 31
            if (w and 0xffe00c00.toInt() == 0x38200800 && (w ushr 16) and 31 == counter) store = w and 31
            add(w)?.let { if (it.rd == counter && it.rn == counter && it.immediate == 1L) increment = true }
            if (w == 0xd65f03c0.toInt()) return load != null && store == load && increment
            if (blTarget(target + n * 4, w) != null) return false
        }
        return false
    }
}
