package net.hawyn.ablefisp.text

internal data class TextSection(val name: String, val rva: Long, val virtualSize: Int, val raw: Int, val size: Int, val flags: Long, val header: Int)
internal class TextPe(val data: ByteArray) {
    val pe: Int
    val optional: Int
    val sectionTable: Int
    val sections: List<TextSection>
    val fileAlignment: Int
    val sectionAlignment: Int
    val sizeOfHeaders: Int
    val codeEnd: Long
    init {
        requireText(data.size >= 128 && data[0] == 0x4d.toByte() && data[1] == 0x5a.toByte(), "请选择已提取的 EFI PE 镜像")
        pe = u32(0x3c).toIntChecked()
        requireText(pe >= 0x40 && pe <= data.size - 24 && u32(pe) == 0x4550L, "PE 文件头异常")
        requireText(u16(pe + 4) == 0xaa64, "文字编辑当前支持 ARM64 EFI 镜像")
        val count = u16(pe + 6)
        val optSize = u16(pe + 20)
        optional = pe + 24
        requireText(count in 1..96 && optSize >= 0xf0 && optional + optSize <= data.size, "PE 节表异常")
        requireText(u16(optional) == 0x20b, "文字编辑需要 PE32+ 格式")
        fileAlignment = u32(optional + 36).toIntChecked()
        sectionAlignment = u32(optional + 32).toIntChecked()
        sizeOfHeaders = u32(optional + 60).toIntChecked()
        requireText(fileAlignment in 0x200..0x10000 && fileAlignment.countOneBits() == 1 && sectionAlignment >= fileAlignment && sectionAlignment.countOneBits() == 1, "PE 对齐参数异常")
        sectionTable = optional + optSize
        requireText(sectionTable.toLong() + count * 40L <= data.size, "PE 节表超出文件")
        sections = (0 until count).map { index ->
            val h = sectionTable + index * 40
            val name = data.copyOfRange(h, h + 8).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
            val raw = u32(h + 20).toIntChecked(); val size = u32(h + 16).toIntChecked()
            val virtual = u32(h + 8).toIntChecked(); val rva = u32(h + 12)
            requireText(raw >= 0 && size >= 0 && raw.toLong() + size <= data.size && rva + maxOf(size, virtual).toLong() <= 0xffff_ffffL, "PE 节范围异常")
            TextSection(name, rva, virtual, raw, size, u32(h + 36), h)
        }
        val executable = sections.filter { it.flags and 0x20000000L != 0L }
        requireText(executable.isNotEmpty(), "PE 没有代码节")
        // Firmware often combines machine code, strings and fonts in one executable section.
        // Stop instruction recognition at its first real Fastboot string pool.
        val pool = asciiStrings().filter { (_, text) -> text.length >= 8 && text.contains("fastboot", ignoreCase = true) }.map { (offset, _) -> offsetToRva(offset) }.filter { address -> executable.any { address >= it.rva && address < it.rva + it.size } }.minOrNull()
        codeEnd = pool ?: executable.maxOf { it.rva + it.size }
    }
    fun u16(offset: Int): Int { bounds(offset, 2); return (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8) }
    fun u32(offset: Int): Long { bounds(offset, 4); var result = 0L; for (i in 0..3) result = result or ((data[offset + i].toLong() and 255) shl (8 * i)); return result }
    fun word(rva: Long): Int = u32(rvaToOffset(rva, 4)).toInt()
    fun rvaToOffset(rva: Long, bytes: Int = 1): Int {
        val section = sections.firstOrNull { rva >= it.rva && rva - it.rva + bytes <= minOf(it.size, if (it.virtualSize == 0) it.size else it.virtualSize).toLong() }
        if (section != null) return (section.raw + rva - section.rva).toInt()
        if (rva >= 0 && rva + bytes <= minOf(sizeOfHeaders, data.size)) return rva.toInt()
        throw TextEditException("文字地址超出 PE 映射范围")
    }
    fun offsetToRva(offset: Int): Long {
        val section = sections.firstOrNull { offset >= it.raw && offset.toLong() < it.raw.toLong() + it.size }
        if (section != null) return section.rva + offset - section.raw
        if (offset in 0 until minOf(sizeOfHeaders, data.size)) return offset.toLong()
        throw TextEditException("文件偏移不属于 PE 映射范围")
    }
    fun cString(rva: Long, maxBytes: Int = 4096): String? = runCatching {
        val start = rvaToOffset(rva)
        val section = sections.firstOrNull { start >= it.raw && start < it.raw + it.size } ?: return@runCatching null
        val end = minOf(start + maxBytes, section.raw + section.size)
        var at = start
        while (at < end && data[at] != 0.toByte()) { if (!visible(data[at].toInt() and 255)) return@runCatching null; at++ }
        if (at >= end) null else data.copyOfRange(start, at).toString(Charsets.US_ASCII)
    }.getOrNull()
    fun asciiStrings(): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        for (section in sections) {
            var at = section.raw
            while (at < section.raw + section.size) {
                if (!visible(data[at].toInt() and 255)) { at++; continue }
                val start = at
                while (at < section.raw + section.size && visible(data[at].toInt() and 255)) at++
                if (at < section.raw + section.size && data[at] == 0.toByte() && at - start >= 3) result += start to data.copyOfRange(start, at).toString(Charsets.US_ASCII)
                at++
            }
        }
        return result
    }
    fun codeWords(): Sequence<Long> = sequence { for (section in sections.filter { it.flags and 0x20000000L != 0L }) { var r = section.rva; val end = minOf(section.rva + section.size, codeEnd); while (r + 4 <= end) { yield(r); r += 4 } } }
    private fun bounds(offset: Int, size: Int) { requireText(offset >= 0 && offset.toLong() + size <= data.size, "PE 数据超出文件") }
    companion object { fun visible(value: Int) = value in 32..126 || value == 10 || value == 13 }
}

internal fun requireText(condition: Boolean, message: String) { if (!condition) throw TextEditException(message) }
internal fun Long.toIntChecked(): Int { requireText(this in 0..Int.MAX_VALUE.toLong(), "PE 数值超出支持范围"); return toInt() }
internal fun put32(data: ByteArray, offset: Int, value: Long) { requireText(offset >= 0 && offset + 4 <= data.size, "写入位置超出镜像"); for (i in 0..3) data[offset + i] = (value ushr (8 * i)).toByte() }
internal fun put16(data: ByteArray, offset: Int, value: Int) { data[offset] = value.toByte(); data[offset + 1] = (value ushr 8).toByte() }
internal fun alignText(value: Long, alignment: Int): Long = ((value + alignment - 1) / alignment) * alignment
internal fun sxText(value: Long, bits: Int): Long = (value xor (1L shl (bits - 1))) - (1L shl (bits - 1))

internal data class TextRef(val pc: Long, val target: Long, val register: Int, val secondPc: Long?)
internal object TextA64 {
    fun refs(image: TextPe): List<TextRef> {
        val result = mutableListOf<TextRef>()
        for (pc in image.codeWords()) {
            val w = image.word(pc).toLong() and 0xffff_ffffL
            val reg = (w and 31).toInt()
            if (w and 0x9f000000L == 0x90000000L) {
                val page = (pc and -4096L) + (sxText((((w ushr 5) and 0x7ffff) shl 2) or ((w ushr 29) and 3), 21) shl 12)
                val next = runCatching { image.word(pc + 4).toLong() and 0xffff_ffffL }.getOrNull() ?: continue
                if (next and 0xffc00000L == 0x91000000L && ((next ushr 5) and 31).toInt() == reg && (next and 31).toInt() == reg) result += TextRef(pc, page + ((next ushr 10) and 0xfff), reg, pc + 4)
            } else if (w and 0x9f000000L == 0x10000000L) {
                result += TextRef(pc, pc + sxText((((w ushr 5) and 0x7ffff) shl 2) or ((w ushr 29) and 3), 21), reg, null)
            }
        }
        return result
    }
    fun call(image: TextPe, pc: Long): Long? {
        val w = runCatching { image.word(pc).toLong() and 0xffff_ffffL }.getOrNull() ?: return null
        return if (w and 0xfc000000L == 0x94000000L) pc + (sxText(w and 0x3ffffff, 26) shl 2) else null
    }
    fun movImmediate(word: Int, register: Int): Int? {
        val w = word.toLong() and 0xffff_ffffL
        return if (w and 0xffe0001fL == (0x52800000L or register.toLong())) ((w ushr 5) and 0xffff).toInt() else null
    }
    fun stackAddress(word: Int, register: Int): Int? {
        val w = word.toLong() and 0xffff_ffffL
        return if (w and 0xffc003ffL == (0x910003e0L or register.toLong())) ((w ushr 10) and 0xfff).toInt() else null
    }
    fun movW(register: Int, immediate: Int): Int { requireText(immediate in 0..65535, "长度超出指令容量"); return (0x52800000L or (immediate.toLong() shl 5) or register.toLong()).toInt() }
    fun adrp(register: Int, pc: Long, target: Long): Int {
        val delta = ((target and -4096L) - (pc and -4096L)) shr 12
        requireText(delta in -(1L shl 20) until (1L shl 20), "新文字超出 ADRP 寻址范围")
        val imm = delta and 0x1fffff
        return (0x90000000L or ((imm and 3) shl 29) or ((imm ushr 2) shl 5) or register.toLong()).toInt()
    }
    fun add(register: Int, target: Long): Int = (0x91000000L or ((target and 0xfff) shl 10) or (register.toLong() shl 5) or register.toLong()).toInt()
    fun branch(pc: Long, target: Long, link: Boolean = false): Int {
        val distance = target - pc
        requireText(distance and 3L == 0L && distance in -(1L shl 27) until (1L shl 27), "新代码超出分支范围")
        return ((if (link) 0x94000000L else 0x14000000L) or ((distance shr 2) and 0x3ffffff)).toInt()
    }
    fun redirect(data: ByteArray, image: TextPe, ref: TextRef, target: Long) {
        val first = image.rvaToOffset(ref.pc, 4)
        if (ref.secondPc != null) {
            put32(data, first, adrp(ref.register, ref.pc, target).toLong())
            put32(data, image.rvaToOffset(ref.secondPc, 4), add(ref.register, target).toLong())
        } else {
            val distance = target - ref.pc
            requireText(distance in -(1L shl 20) until (1L shl 20), "新文字超出 ADR 寻址范围")
            val imm = distance and 0x1fffff
            put32(data, first, 0x10000000L or ((imm and 3) shl 29) or ((imm ushr 2) shl 5) or ref.register.toLong())
        }
    }
}
