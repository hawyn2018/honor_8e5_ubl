package net.hawyn.ablefisp.patch

/**
 * Reserve zero tail padding in the caller's existing RX mapping. This allocator
 * never grows a file/section, changes PE headers or treats an interior zero hole
 * as free space. All returned ranges are planned together before any caller writes.
 *
 * Placement is derived from this input's section contents, not firmware identity
 * or fixed addresses. No reference-risk inference is performed on numeric data,
 * instructions, directories or relocation targets. Functional target recognition
 * remains the responsibility of the patch analyzers. Mapped capacity, alignment,
 * disjoint placement and direct A64 branch reach are output requirements.
 */
internal object MappedRxCodeAllocator {
    data class Request(val anchorRva: Long, val size: Int)
    data class Allocation(val rva: Long, val size: Int)

    private data class Tail(val section: PeSection, val start: Long, val end: Long)
    private const val GUARD = 16L
    private const val ALIGNMENT = 16L

    fun allocate(image: PeImage, requests: List<Request>): List<Allocation> {
        if (requests.isEmpty()) return emptyList()
        val pe = image.bytes.u32(0x3c).asBoundedInt("PE 头位置")
        val mappedImageSize = image.bytes.u32(pe + 24 + 56)
        val owners = requests.map { request ->
            if (request.size !in 1..1024 * 1024 || request.anchorRva and 3L != 0L) {
                throw PatchException("原映射代码分配：调用点或代码长度无效。")
            }
            val owner = image.sections.singleOrNull { section ->
                section.executable && !section.writable && section.characteristics and 0x40000000L != 0L &&
                    section.characteristics and 0x02000000L == 0L && section.rva + mappedSize(section) <= mappedImageSize &&
                    request.anchorRva >= section.rva && request.anchorRva + 4 <= section.rva + mappedSize(section)
            } ?: throw PatchException("原映射代码分配：调用点不在保留的只读可执行节内。")
            if (image.word(request.anchorRva) == 0) throw PatchException("原映射代码分配：调用点位于零填充而非已有指令。")
            owner
        }
        val tails = owners.distinct().map { section ->
            val mapped = mappedSize(section)
            var endOfContent = section.raw + mapped.toInt()
            while (endOfContent > section.raw && image.bytes[endOfContent - 1] == 0.toByte()) endOfContent--
            val start = align16(section.rva + endOfContent - section.raw + GUARD)
            val end = section.rva + mapped
            if (start >= end) throw PatchException("原映射代码分配：调用点所在节没有带保护间隔的零填充尾部。")
            Tail(section, start, end)
        }
        val next = tails.associate { it.section to it.start }.toMutableMap()
        return requests.mapIndexed { index, request ->
            val section = owners[index]
            val tail = tails.single { it.section == section }
            val start = requireNotNull(next[section])
            val end = start + request.size
            if (end > tail.end || image.rvaToOffset(start, request.size) == null) {
                throw PatchException("原映射代码分配：调用点所在节的可用零尾不足，未追加新代码节。")
            }
            A64.branch(request.anchorRva, start) // Both sites must be in direct A64 branch range.
            // The guard separates original initialized content from the new
            // code. Individual self-contained stubs need alignment and disjoint
            // ranges, not a second artificial guard after every reservation.
            next[section] = align16(end)
            Allocation(start, request.size)
        }
    }

    private fun mappedSize(section: PeSection): Long =
        minOf(section.rawSize.toLong(), section.virtualSize.takeIf { it > 0 } ?: section.rawSize.toLong())

    private fun align16(value: Long): Long = (value + ALIGNMENT - 1) and -ALIGNMENT

}
