package net.hawyn.ablefisp.patch

/** New relative-branch code lives in its own RX mapping; existing sections never move. */
internal object PeExecutableSection {
    data class Allocation(val image: PeImage, val rva: Long, val headerChanges: List<PatchMatch>)

    fun append(image: PeImage, bytesRequired: Int): Allocation {
        if (bytesRequired <= 0) throw PatchException("新增代码长度无效。")
        val source = image.bytes
        val pe = source.u32(0x3c).asBoundedInt("PE 头位置")
        val optional = pe + 24
        val table = optional + source.u16(pe + 20)
        val count = source.u16(pe + 6)
        val header = table + count * 40
        val firstRaw = image.sections.filter { it.rawSize > 0 }.minOfOrNull { it.raw } ?: image.headersSize
        if (count >= 64 || header.toLong() + 40 > minOf(image.headersSize, firstRaw) ||
            (header until header + 40).any { source[it] != 0.toByte() }) {
            throw PatchException("PE 节表没有可追加代码段的空间；原有数据未修改。")
        }
        // Header padding is not usable when a PE directory already owns it.
        val directoryCapacity = maxOf(0, (source.u16(pe + 20) - 112) / 8)
        val directoryCount = minOf(source.u32(optional + 108).coerceAtMost(16).toInt(), directoryCapacity)
        for (index in 0 until directoryCount) {
            val at = optional + 112 + index * 8
            val address = source.u32(at); val size = source.u32(at + 4)
            if (size > 0 && address < header + 40L && header.toLong() < address + size) {
                throw PatchException("PE 新节表位置与已有目录重叠；原有数据未修改。")
            }
        }
        fun alignment(at: Int): Int {
            val value = source.u32(at).asBoundedInt("PE 对齐参数")
            if (value <= 0 || value and (value - 1) != 0) throw PatchException("PE 对齐参数无效。")
            return value
        }
        val fileAlignment = alignment(optional + 36)
        val sectionAlignment = alignment(optional + 32)
        val raw = align(source.size, fileAlignment)
        val rawSize = align(bytesRequired, fileAlignment)
        val memoryEnd = maxOf(source.u32(optional + 56), image.sections.maxOf { it.rva + maxOf(it.virtualSize, it.rawSize.toLong()) })
        val rva = alignLong(memoryEnd, sectionAlignment)
        val imageSize = alignLong(rva + rawSize, sectionAlignment)
        if (raw.toLong() + rawSize > Int.MAX_VALUE || imageSize > 0xffff_ffffL) {
            throw PatchException("追加代码段超出 PE 可映射范围。")
        }
        val codeSize = source.u32(optional + 4) + rawSize
        if (codeSize > 0xffff_ffffL) throw PatchException("PE 代码长度超出范围。")
        val output = source.copyOf(raw + rawSize)
        ".bfpol".toByteArray(Charsets.US_ASCII).copyInto(output, header)
        output.putWord(header + 8, rawSize)
        output.putWord(header + 12, rva.toInt())
        output.putWord(header + 16, rawSize)
        output.putWord(header + 20, raw)
        output.putWord(header + 36, 0x60000020) // code, executable/readable; neither writable nor discardable
        put16(output, pe + 6, count + 1)
        output.putWord(optional + 4, codeSize.toInt())
        output.putWord(optional + 56, imageSize.toInt())
        output.putWord(optional + 64, 0) // PE checksum is optional; matches the text allocator's convention.
        fun change(name: String, at: Int, size: Int): PatchMatch = PatchMatch(
            name, at.toLong(), at, source.copyOfRange(at, at + size).hex(),
            output.copyOfRange(at, at + size).hex(), source.copyOfRange(at, at + size).contentEquals(output.copyOfRange(at, at + size)),
        )
        val changes = listOf(
            change("新增错误策略代码段映射", header, 40),
            change("PE 代码段数量", pe + 6, 2),
            change("PE 代码长度", optional + 4, 4),
            change("PE 加载长度", optional + 56, 4),
            change("PE 可选校验和", optional + 64, 4),
        )
        val expanded = PeImage.parse(output)
        if (expanded.entryRva != image.entryRva || expanded.sections.take(count) != image.sections ||
            expanded.rvaToOffset(rva, bytesRequired) != raw || !expanded.isCode(rva, bytesRequired)) {
            throw PatchException("新增代码段映射关系无效。")
        }
        return Allocation(expanded, rva, changes)
    }

    private fun alignLong(value: Long, boundary: Int): Long {
        if (value < 0 || value > 0xffff_ffffL) throw PatchException("PE 加载地址超出范围。")
        return ((value + boundary - 1) / boundary) * boundary
    }

    private fun put16(data: ByteArray, at: Int, value: Int) {
        data[at] = value.toByte(); data[at + 1] = (value ushr 8).toByte()
    }
}
