package net.hawyn.ablefisp.patch

internal data class PeSection(
    val name: String, val rva: Long, val virtualSize: Long, val raw: Int,
    val rawSize: Int, val characteristics: Long,
) {
    val executable get() = characteristics and 0x20000000L != 0L
    val writable get() = characteristics and 0x80000000L != 0L
}

internal class PeImage private constructor(
    val bytes: ByteArray,
    val sections: List<PeSection>,
    val headersSize: Int,
    val entryRva: Long,
    val imageBase: Long,
) {
    fun rvaToOffset(rva: Long, count: Int = 1): Int? {
        if (rva < 0 || count < 0) return null
        if (rva < headersSize && rva + count <= headersSize) return rva.toInt()
        val section = sections.firstOrNull {
            rva >= it.rva && rva + count <= it.rva + it.rawSize
        } ?: return null
        return (section.raw.toLong() + rva - section.rva).toInt()
    }

    fun offsetToRva(offset: Int): Long? {
        if (offset < 0) return null
        if (offset < headersSize) return offset.toLong()
        val section = sections.firstOrNull { offset >= it.raw && offset < it.raw.toLong() + it.rawSize } ?: return null
        return section.rva + offset - section.raw
    }

    fun word(rva: Long): Int? = rvaToOffset(rva, 4)?.let(bytes::word)
    fun ascii(rva: Long, max: Int = 512): String? = rvaToOffset(rva)?.let { bytes.asciiZ(it, max) }
    fun isCode(rva: Long, count: Int = 4): Boolean = sections.any {
        it.executable && rva >= it.rva && rva + count <= it.rva + it.rawSize
    }
    fun isWritable(rva: Long, count: Int): Boolean = sections.any {
        it.writable && rva >= it.rva && rva + count <= it.rva + maxOf(it.virtualSize, it.rawSize.toLong())
    }

    fun instructions(): Sequence<Pair<Long, Int>> = sequence {
        for (section in sections.filter { it.executable }) {
            var offset = align(section.raw, 4)
            val end = section.raw + section.rawSize
            while (offset <= end - 4) {
                yield(section.rva + offset - section.raw to bytes.word(offset))
                offset += 4
            }
        }
    }

    companion object {
        fun parse(input: ByteArray, start: Int = 0, available: Int = input.size - start): PeImage {
            input.range(start, available)
            if (available < 0x40 || !input.matches(start, byteArrayOf(0x4d, 0x5a))) throw PatchException("未识别到 PE 头。")
            val peRelative = input.u32(start + 0x3c).asBoundedInt("PE 头位置")
            if (peRelative < 0x40 || peRelative.toLong() + 24 > available) throw PatchException("PE 头位置无效。")
            val pe = start + peRelative
            if (!input.matches(pe, byteArrayOf(0x50, 0x45, 0, 0))) throw PatchException("PE 签名无效。")
            if (input.u16(pe + 4) != 0xaa64) throw PatchException("目标 PE 的指令架构需要为 ARM64。")
            val count = input.u16(pe + 6)
            val optionalSize = input.u16(pe + 20)
            if (count !in 1..64 || optionalSize < 112) throw PatchException("PE 节表结构无效。")
            val optional = pe + 24
            val sectionTable = optional + optionalSize
            if (sectionTable.toLong() + count * 40 > start.toLong() + available) throw PatchException("PE 节表被截断。")
            if (input.u16(optional) != 0x20b) throw PatchException("目标需要为 PE32+ 格式。")
            val headers = input.u32(optional + 60).asBoundedInt("PE 头长度")
            if (headers < sectionTable + count * 40 - start || headers > available) throw PatchException("PE 头长度无效。")
            val sections = ArrayList<PeSection>()
            var extent = headers
            repeat(count) { index ->
                val at = sectionTable + index * 40
                val name = input.copyOfRange(at, at + 8).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
                val vs = input.u32(at + 8)
                val rva = input.u32(at + 12)
                val rawSize = input.u32(at + 16).asBoundedInt("PE 节长度")
                val raw = input.u32(at + 20).asBoundedInt("PE 节位置")
                if (rva + maxOf(vs, rawSize.toLong()) > 0x1_0000_0000L || raw.toLong() + rawSize > available) {
                    throw PatchException("PE 节超出镜像范围：$name")
                }
                if (rawSize > 0 && raw < headers) throw PatchException("PE 节与文件头重叠。")
                sections += PeSection(name, rva, vs, raw, rawSize, input.u32(at + 36))
                extent = maxOf(extent, raw + rawSize)
            }
            for (left in sections.indices) for (right in left + 1 until sections.size) {
                val a = sections[left]; val b = sections[right]
                if (a.rawSize > 0 && b.rawSize > 0 && a.raw < b.raw.toLong() + b.rawSize && b.raw < a.raw.toLong() + a.rawSize) {
                    throw PatchException("PE 原始节范围重叠。")
                }
                val aSize = maxOf(a.virtualSize, a.rawSize.toLong()); val bSize = maxOf(b.virtualSize, b.rawSize.toLong())
                if (aSize > 0 && bSize > 0 && a.rva < b.rva + bSize && b.rva < a.rva + aSize) throw PatchException("PE 加载节范围重叠。")
            }
            if (optionalSize >= 152 && input.u32(optional + 108) > 4) {
                val certificate = input.u32(optional + 144)
                val certificateSize = input.u32(optional + 148)
                if (certificateSize > 0) {
                    if (certificate + certificateSize > available) throw PatchException("PE 证书表超出输入范围。")
                    extent = maxOf(extent, (certificate + certificateSize).asBoundedInt("PE 证书表"))
                }
            }
            val entry = input.u32(optional + 16)
            val result = PeImage(input.copyOfRange(start, start + extent), sections, headers, entry, input.u64(optional + 24))
            if (!result.isCode(entry)) throw PatchException("PE 入口未指向有效代码节。")
            return result
        }
    }
}
