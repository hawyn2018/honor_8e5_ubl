package net.hawyn.ablefisp.patch

/**
 * Last-resort RX capacity for the independent command adapter. The caller first
 * tries existing RX space, then calls this helper only when that space is full.
 * Original section mappings, bytes, absolute pointers and directories stay put.
 */
internal object PeCodeSectionAppender {
    const val MAX_FILE_BYTES = 60 * 1024 * 1024
    const val MIN_SECTION_BYTES = 4096

    data class Result(
        val bytes: ByteArray,
        val newSectionRva: Long,
        val allocationBytes: Int,
        val metadataEdits: List<PlannedEdit>,
    ) {
        val sectionRva get() = newSectionRva
    }

    fun append(image: PeImage, requiredBytes: Int, branchAnchors: List<Long> = emptyList()): Result {
        if (requiredBytes <= 0 || requiredBytes >= MAX_FILE_BYTES) fail("新增代码长度无效")
        val source = image.bytes
        if (source.size >= MAX_FILE_BYTES) fail("输入镜像已达到追加容量上限")
        val pe = source.u32(0x3c).asBoundedInt("PE 头位置")
        val optional = pe + 24
        val optionalSize = source.u16(pe + 20)
        val table = optional + optionalSize
        val count = source.u16(pe + 6)
        val slot = table + count * 40
        val slotEnd = slot.toLong() + 40
        val firstRaw = image.sections.filter { it.rawSize > 0 }.minOfOrNull { it.raw } ?: image.headersSize
        if (count >= 64 || slotEnd > minOf(image.headersSize, firstRaw) ||
            slot < 0 || slotEnd > source.size || (slot until slot + 40).any { source[it] != 0.toByte() }) {
            fail("PE 节表没有完整空位可追加 RX 映射")
        }
        // A zero-filled directory range is still owned data, not free section
        // header storage. The security directory uses a file offset; headers
        // have the same file offset/RVA, so both forms use this overlap check.
        val capacity = maxOf(0, (optionalSize - 112) / 8)
        val directoryCount = minOf(source.u32(optional + 108), capacity.toLong(), 16L).toInt()
        for (index in 0 until directoryCount) {
            val at = optional + 112 + index * 8
            val address = source.u32(at)
            val size = source.u32(at + 4)
            if (address > 0 && size > 0 && intersects(slot.toLong(), slotEnd, address, address + size)) {
                fail("新增节表位置已被现有 PE 目录占用")
            }
        }
        val symbols = source.u32(pe + 12)
        val symbolBytes = source.u32(pe + 16) * 18L
        if (symbols > 0 && symbolBytes > 0 && intersects(slot.toLong(), slotEnd, symbols, symbols + symbolBytes)) {
            fail("新增节表位置已被 COFF 符号数据占用")
        }

        val fileAlignment = alignment(source.u32(optional + 36))
        val sectionAlignment = alignment(source.u32(optional + 32))
        if (sectionAlignment < fileAlignment) fail("PE 加载对齐小于文件对齐")
        val rawAlignment = maxOf(MIN_SECTION_BYTES.toLong(), fileAlignment)
        val rvaAlignment = maxOf(MIN_SECTION_BYTES.toLong(), sectionAlignment)
        // Append after ALL received bytes, including an existing certificate or
        // retained overlay. Never insert before, truncate, or move old data.
        val raw = aligned(source.size.toLong(), rawAlignment)
        val allocation = aligned(maxOf(requiredBytes, MIN_SECTION_BYTES).toLong(), rawAlignment)
        val fileEnd = raw + allocation
        if (fileEnd >= MAX_FILE_BYTES) fail("追加后镜像必须小于 60 MiB")
        val memoryEnd = maxOf(source.u32(optional + 56), image.sections.maxOf {
            it.rva + maxOf(it.virtualSize, it.rawSize.toLong())
        })
        val rva = aligned(memoryEnd, rvaAlignment)
        val loadedEnd = aligned(rva + allocation, sectionAlignment)
        val codeBytes = source.u32(optional + 4) + allocation
        if (rva > 0xffff_ffffL || loadedEnd > 0xffff_ffffL || codeBytes > 0xffff_ffffL) {
            fail("追加 RX 映射超出 PE32+ 的 RVA 或长度字段范围")
        }
        verifyBranches(image, rva, requiredBytes, branchAnchors)

        val sectionHeader = ByteArray(40)
        // The target firmware's SEC DEP path recognizes .text-style names;
        // executable characteristics alone do not grant instruction access.
        ".textsu".toByteArray(Charsets.US_ASCII).copyInto(sectionHeader)
        sectionHeader.putWord(8, allocation.toInt())
        sectionHeader.putWord(12, rva.toInt())
        sectionHeader.putWord(16, allocation.toInt())
        sectionHeader.putWord(20, raw.toInt())
        sectionHeader.putWord(36, 0x60000020) // code + RX; not writable/discardable
        fun word(value: Long) = ByteArray(4).also { it.putWord(0, value.toInt()) }
        val sectionCount = byteArrayOf((count + 1).toByte(), ((count + 1) ushr 8).toByte())
        val edits = listOf(
            PlannedEdit("oem superunlock：PE映射元数据 新增 RX 节记录", slot.toLong(), sectionHeader),
            PlannedEdit("oem superunlock：PE映射元数据 节数量", (pe + 6).toLong(), sectionCount),
            PlannedEdit("oem superunlock：PE映射元数据 SizeOfCode", (optional + 4).toLong(), word(codeBytes)),
            PlannedEdit("oem superunlock：PE映射元数据 SizeOfImage", (optional + 56).toLong(), word(loadedEnd)),
            PlannedEdit("oem superunlock：PE映射元数据 CheckSum", (optional + 64).toLong(), word(0)),
        )
        val output = source.copyOf(fileEnd.toInt())
        edits.forEach { edit -> edit.replacement.copyInto(output, edit.rva.toInt()) }
        val expanded = PeImage.parse(output)
        if (expanded.entryRva != image.entryRva || expanded.imageBase != image.imageBase ||
            expanded.sections.take(count) != image.sections || expanded.sections.size != count + 1 ||
            expanded.rvaToOffset(rva, requiredBytes) != raw.toInt() || !expanded.isCode(rva, requiredBytes) ||
            expanded.bytes.size != output.size) {
            fail("追加 RX 映射的文件位置或原节关系回读不一致")
        }
        return Result(output, rva, allocation.toInt(), edits)
    }

    private fun verifyBranches(image: PeImage, start: Long, requiredBytes: Int, requested: List<Long>) {
        val anchors = if (requested.isNotEmpty()) requested.distinct().also { values ->
            if (values.any { it and 3 != 0L || !image.isCode(it, 4) }) fail("新增代码的分支锚点未指向原代码")
        } else image.sections.filter { it.executable }.flatMap { section ->
            val visible = minOf(section.rawSize.toLong(), section.virtualSize.takeIf { it > 0 } ?: section.rawSize.toLong())
            val first = aligned(section.rva, 4)
            val end = section.rva + visible
            val last = (end - 4) and -4L
            if (first <= last) listOf(first, last) else emptyList()
        }
        if (anchors.isEmpty()) fail("新增代码缺少可达的原始分支锚点")
        val last = start + (requiredBytes - 1L) / 4 * 4
        fun reachable(from: Long, to: Long): Boolean {
            val delta = to - from
            return delta and 3 == 0L && delta >= -0x0800_0000L && delta <= 0x07ff_fffcL
        }
        if (anchors.any { anchor -> listOf(start, last).any { site ->
                !reachable(anchor, site) || !reachable(site, anchor)
            } }) fail("新增 RX 代码与原调用目标超出 ARM64 相对分支范围")
    }

    private fun alignment(value: Long): Long {
        if (value <= 0 || value and (value - 1) != 0L) fail("PE 对齐参数无效")
        return value
    }

    private fun aligned(value: Long, boundary: Long): Long {
        if (value < 0 || boundary <= 0 || value > Long.MAX_VALUE - boundary + 1) fail("追加代码对齐范围无效")
        return (value + boundary - 1) / boundary * boundary
    }

    private fun intersects(a: Long, b: Long, c: Long, d: Long) = a < d && c < b
    private fun fail(message: String): Nothing = throw PatchException("$message；原始镜像未修改。")
}
