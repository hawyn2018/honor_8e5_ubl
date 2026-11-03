package net.hawyn.ablefisp.patch

/** Small position-independent A64 adapters; no absolute pointers or relocations. */
internal object SuperUnlockCode {
    const val REGISTRATION_BYTES = 304
    const val REGISTRATION_CODE_BYTES = 296
    const val HANDLER_BYTES = 416
    const val DATA_BYTES = 160
    const val TOTAL_BYTES = REGISTRATION_BYTES + HANDLER_BYTES + DATA_BYTES
    const val COMMAND = "oem superunlock"
    private val messages = linkedMapOf(
        "command" to COMMAND,
        "done" to "DeviceInfo unlocked; persistence unverified",
        "already" to "DeviceInfo already unlocked",
        "failure" to "superunlock failed",
        "magic" to "ANDROID-BOOT!",
    )

    fun dataOffsets(): Map<String, Int> {
        var offset = REGISTRATION_BYTES + HANDLER_BYTES
        return messages.mapValues { (_, text) -> offset.also { offset += text.length + 1 } }.also {
            check(offset <= TOTAL_BYTES)
        }
    }

    fun bytes(p: SuperUnlockPatcher.Plan, base: Long): ByteArray {
        val output = ByteArray(TOTAL_BYTES)
        val strings = dataOffsets().mapValues { base + it.value }
        registration(p, base, base + REGISTRATION_BYTES, strings.getValue("command")).copyInto(output)
        handler(p, base + REGISTRATION_BYTES, strings).copyInto(output, REGISTRATION_BYTES)
        for ((name, text) in messages) (text + '\u0000').toByteArray(Charsets.US_ASCII)
            .copyInto(output, dataOffsets().getValue(name))
        // Keep the reservation's final byte initialized, so later text-tail
        // allocation begins after this entire block rather than its padding.
        "HNU-SUPERUNLOCK01".toByteArray(Charsets.US_ASCII).copyInto(output, TOTAL_BYTES - 17)
        output[TOTAL_BYTES - 1] = 0x7f
        return output
    }

    private fun registration(p: SuperUnlockPatcher.Plan, base: Long, handler: Long, command: Long): ByteArray {
        val c = Code(base)
        c.condition(p.exitCondition, "done")
        c.branch(p.registrationContinueRva)
        c.label("done")
        c.emit(sub(31, 31, 704))
        for (r in 0..16 step 2) c.emit(pair(false, r, r + 1, 31, r * 8))
        c.emit(store64(18, 31, 144)); c.emit(store64(30, 31, 152))
        c.emit(0xd53b4210.toInt()) // MRS X16,NZCV
        c.emit(store64(16, 31, 160))
        for (r in 0..30 step 2) c.emit(vectorPair(false, r, r + 1, 31, 176 + r * 16))
        c.emit(0xd53b4410.toInt()); c.emit(store64(16, 31, 688)) // FPCR
        c.emit(0xd53b4430.toInt()); c.emit(store64(16, 31, 696)) // FPSR
        c.address(0, command); c.address(1, handler); c.call(p.nativeRegisterRva)
        c.emit(load64(16, 31, 688)); c.emit(0xd51b4410.toInt())
        c.emit(load64(16, 31, 696)); c.emit(0xd51b4430.toInt())
        for (r in 0..30 step 2) c.emit(vectorPair(true, r, r + 1, 31, 176 + r * 16))
        c.emit(load64(16, 31, 160)); c.emit(0xd51b4210.toInt()) // MSR NZCV,X16
        for (r in 0..16 step 2) c.emit(pair(true, r, r + 1, 31, r * 8))
        c.emit(load64(18, 31, 144)); c.emit(load64(30, 31, 152))
        c.emit(add(31, 31, 704)); c.branch(p.registrationExitRva)
        return c.finish().also { check(it.size == REGISTRATION_CODE_BYTES) }
    }

    /*
     * BLTools BlApply(read-modify-write) behavior reference, not copied firmware
     * coordinates: https://github.com/SuperTurtleDev/gbl_root_canoe/blob/
     * 4ef438cfde42943694fde5753bbf5d8c0b4ec02e/submodules/uefi/edk2/
     * AndroidToolsPkg/Application/BLTools/BLTools.c (BSD-3-Clause).
     * One-way unlock only. The existing flashing-unlock/menu transaction is not
     * called or replaced. A successful DeviceInfo write is not OEMINFO proof.
     */
    private fun handler(p: SuperUnlockPatcher.Plan, base: Long, text: Map<String, Long>): ByteArray {
        val c = Code(base)
        c.emit(0xd503233f.toInt()) // PACIASP / callable indirect entry
        c.emit(sub(31, 31, 64))
        c.emit(pair(false, 19, 20, 31, 0)); c.emit(pair(false, 21, 22, 31, 16))
        c.emit(pair(false, 23, 24, 31, 32)); c.emit(store64(30, 31, 48))
        c.emit(sub(31, 31, p.frameBytes)); c.emit(add(19, 31, 0))
        c.emit(sub(31, 31, p.frameBytes)); c.emit(add(20, 31, 0))
        c.zero(0, "args_ok", wide = true)
        c.label("arg_loop")
        c.emit(loadByte(8, 0, 0)); c.zero(8, "args_ok")
        c.emit(cmp(8, 32)); c.condition(1, "fail_args")
        c.emit(add(0, 0, 1)); c.b("arg_loop")
        c.label("args_ok")
        clear(c, 19, p.frameBytes, "clear_a")
        c.emit(movW(8, 255)); c.emit(storeByte(8, 19, p.normalFieldOffset))
        c.emit(storeByte(8, 19, p.criticalFieldOffset))
        rw(c, p, 0, 19); c.nonzero(0, "fail_read", wide = true)
        magic(c, p, 19, text.getValue("magic"), "check_a")
        c.emit(loadByte(8, 19, p.normalFieldOffset)); c.emit(cmp(8, 255)); c.condition(0, "fail_magic")
        c.emit(loadByte(21, 19, p.criticalFieldOffset)); c.emit(cmp(21, 255)); c.condition(0, "fail_magic")
        c.emit(cmp(8, 1)); c.condition(0, "already")
        c.emit(movW(8, 1)); c.emit(storeByte(8, 19, p.normalFieldOffset))
        rw(c, p, 1, 19); c.nonzero(0, "fail_write", wide = true)
        clear(c, 20, p.frameBytes, "clear_b")
        c.emit(movW(9, 255)); c.emit(0x4a000000 or (9 shl 16) or (21 shl 5) or 8) // EOR W8,W21,W9
        c.emit(storeByte(8, 20, p.criticalFieldOffset))
        rw(c, p, 0, 20); c.nonzero(0, "fail_verify", wide = true)
        magic(c, p, 20, text.getValue("magic"), "check_b", "fail_verify")
        c.emit(loadByte(8, 20, p.normalFieldOffset)); c.emit(cmp(8, 1)); c.condition(1, "fail_verify")
        c.emit(loadByte(8, 20, p.criticalFieldOffset))
        c.emit(0x6b00001f or (21 shl 16) or (8 shl 5)); c.condition(1, "fail_verify")
        c.address(0, text.getValue("done")); c.call(p.fastbootOkayRva); c.b("return")
        c.label("already"); c.address(0, text.getValue("already")); c.call(p.fastbootOkayRva); c.b("return")
        for (name in listOf("args", "read", "magic", "write", "verify")) {
            c.label("fail_$name")
        }
        c.address(0, text.getValue("failure")); c.call(p.fastbootFailRva); c.b("return")
        c.label("return")
        c.emit(add(31, 31, p.frameBytes)); c.emit(add(31, 31, p.frameBytes))
        c.emit(pair(true, 19, 20, 31, 0)); c.emit(pair(true, 21, 22, 31, 16))
        c.emit(pair(true, 23, 24, 31, 32)); c.emit(load64(30, 31, 48))
        c.emit(add(31, 31, 64)); c.emit(0xd50323bf.toInt()); c.emit(0xd65f03c0.toInt())
        return c.finish().also { check(it.size <= HANDLER_BYTES) { "superunlock handler ${it.size} exceeds allocation" } }
    }

    private fun rw(c: Code, p: SuperUnlockPatcher.Plan, operation: Int, buffer: Int) {
        c.emit(movW(0, operation)); c.emit(move(1, buffer)); c.call(p.readWriteBackendRva)
    }
    private fun clear(c: Code, buffer: Int, size: Int, label: String) {
        c.emit(move(8, buffer)); c.emit(movW(9, size / 16)); c.label(label)
        c.emit(pair(false, 31, 31, 8, 0)); c.emit(add(8, 8, 16))
        c.emit(0x71000529); c.condition(1, label) // SUBS W9,W9,#1
    }
    private fun magic(c: Code, p: SuperUnlockPatcher.Plan, buffer: Int, literal: Long, label: String, fail: String = "fail_magic") {
        c.emit(move(8, buffer)); c.address(9, literal); c.emit(movW(10, p.magic.size)); c.label(label)
        c.emit(loadByte(12, 8, 0)); c.emit(loadByte(13, 9, 0))
        c.emit(0x6b00001f or (13 shl 16) or (12 shl 5)); c.condition(1, fail)
        c.emit(add(8, 8, 1)); c.emit(add(9, 9, 1)); c.emit(0x7100054a); c.condition(1, label)
    }

    private fun move(rd: Int, rn: Int) = 0xaa0003e0.toInt() or (rn shl 16) or rd
    private fun movW(rd: Int, value: Int) = 0x52800000 or (value shl 5) or rd
    private fun cmp(rn: Int, value: Int) = 0x7100001f or (value shl 10) or (rn shl 5)
    private fun immediate(value: Int): Int {
        require(value >= 0)
        return if (value <= 4095) value shl 10 else {
            require(value % 4096 == 0 && value / 4096 <= 4095)
            0x400000 or ((value / 4096) shl 10)
        }
    }
    private fun add(rd: Int, rn: Int, value: Int) = 0x91000000.toInt() or immediate(value) or (rn shl 5) or rd
    private fun sub(rd: Int, rn: Int, value: Int) = 0xd1000000.toInt() or immediate(value) or (rn shl 5) or rd
    private fun loadByte(rt: Int, rn: Int, offset: Int) = 0x39400000 or (offset shl 10) or (rn shl 5) or rt
    private fun storeByte(rt: Int, rn: Int, offset: Int) = 0x39000000 or (offset shl 10) or (rn shl 5) or rt
    private fun load64(rt: Int, rn: Int, offset: Int) = 0xf9400000.toInt() or ((offset / 8) shl 10) or (rn shl 5) or rt
    private fun store64(rt: Int, rn: Int, offset: Int) = 0xf9000000.toInt() or ((offset / 8) shl 10) or (rn shl 5) or rt
    private fun pair(load: Boolean, rt: Int, rt2: Int, rn: Int, offset: Int) =
        (if (load) 0xa9400000.toInt() else 0xa9000000.toInt()) or
            (((offset / 8) and 127) shl 15) or (rt2 shl 10) or (rn shl 5) or rt
    private fun vectorPair(load: Boolean, rt: Int, rt2: Int, rn: Int, offset: Int) =
        (if (load) 0xad400000.toInt() else 0xad000000.toInt()) or
            (((offset / 16) and 127) shl 15) or (rt2 shl 10) or (rn shl 5) or rt

    private class Code(val base: Long) {
        private val code = arrayListOf<Int>()
        private val labels = hashMapOf<String, Long>()
        private val fixups = arrayListOf<Pair<Int, (Long) -> Int>>()
        val pc get() = base + code.size * 4
        fun emit(word: Int) { code += word }
        fun label(name: String) { check(labels.put(name, pc) == null) }
        fun branch(target: Long) = emit(A64.branch(pc, target))
        fun call(target: Long) = emit(A64.branch(pc, target) or 0x80000000.toInt())
        fun b(label: String) = defer(label) { pc, target -> A64.branch(pc, target) }
        fun condition(condition: Int, label: String) = defer(label) { pc, target ->
            val d = target - pc; check(d and 3L == 0L && d in -0x100000L..<0x100000L)
            0x54000000 or (((d shr 2).toInt() and 0x7ffff) shl 5) or condition
        }
        fun zero(register: Int, label: String, wide: Boolean = false) = cb(register, label, false, wide)
        fun nonzero(register: Int, label: String, wide: Boolean = false) = cb(register, label, true, wide)
        private fun cb(register: Int, label: String, nonzero: Boolean, wide: Boolean) = defer(label) { pc, target ->
            val d = target - pc; check(d and 3L == 0L && d in -0x100000L..<0x100000L)
            (if (wide) 0xb4000000.toInt() else 0x34000000) or (if (nonzero) 0x01000000 else 0) or
                (((d shr 2).toInt() and 0x7ffff) shl 5) or register
        }
        fun address(register: Int, address: Long) {
            val d = address - pc; check(d in -0x100000L..<0x100000L)
            emit(0x10000000 or ((d.toInt() and 3) shl 29) or (((d shr 2).toInt() and 0x7ffff) shl 5) or register)
        }
        private fun defer(label: String, encode: (Long, Long) -> Int) {
            val at = pc; fixups += code.size to { _: Long -> encode(at, labels.getValue(label)) }; emit(0)
        }
        fun finish(): ByteArray {
            for ((index, encode) in fixups) code[index] = encode(base + index * 4)
            return ByteArray(code.size * 4).also { data -> code.forEachIndexed { index, word -> data.putWord(index * 4, word) } }
        }
    }
}
