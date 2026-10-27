package net.hawyn.ablefisp.text

internal data class TextStorage(val rva: Long, val offset: Int, val size: Int)

/** Allocates only mapped executable/readable space; an added section never moves old sections. */
internal class TextAllocator(original: ByteArray, private val references: List<TextRef>) {
    var data: ByteArray = original.copyOf()
        private set
    private val reservations = mutableListOf<TextStorage>()

    fun allocate(payload: ByteArray): TextStorage = reserve(payload.size).also { payload.copyInto(data, it.offset) }
    fun reserve(size: Int): TextStorage {
        requireText(size in 1..1024 * 1024, "新增文字区域大小异常")
        var image = TextPe(data)
        val pointers = relocationTargets(image)
        for (section in image.sections.filter { it.flags and 0x60000000L == 0x60000000L }.reversed()) {
            val end = section.raw + minOf(section.size, section.virtualSize.takeIf { it > 0 } ?: section.size)
            var last = end
            while (last > section.raw && data[last - 1] == 0.toByte()) last--
            val start = alignText(maxOf(last.toLong() + 16, reservations.filter { it.offset in section.raw until end }.maxOfOrNull { (it.offset + it.size).toLong() } ?: 0), 16).toInt()
            val tailRva = section.rva + last - section.raw
            val endRva = section.rva + end - section.raw
            // A pointer into a zero tail can name an object whose remaining
            // extent is unknown. Keep its entire suffix, including targets in
            // the guard/alignment gap; never skip the pointer and allocate in
            // that object's middle. An unreferenced prefix is still usable.
            val firstReferenced = (references.asSequence().map { it.target } + pointers.asSequence())
                .filter { it >= tailRva && it < endRva }.minOrNull()
            val usableEnd = firstReferenced?.let { section.raw + it - section.rva } ?: end.toLong()
            if (start.toLong() + size <= usableEnd) {
                val rva = section.rva + start - section.raw
                return TextStorage(rva, start, size).also(reservations::add)
            }
        }
        // Repeated editing can exhaust the only spare section-header slot in a
        // firmware PE. Grow our last mapped section in place instead of adding
        // one header on every pass. The recovery policy can own the last header
        // slot as .bfpol; extend that tail too, after its existing code and data.
        // Existing file offsets and RVAs stay fixed.
        val tail = image.sections.firstOrNull { section ->
            section.name in setOf(".eftxt", ".bfpol") && section.flags and 0x60000000L == 0x60000000L &&
                section.virtualSize == section.size && section.raw + section.size == data.size &&
                section.rva + section.size == image.sections.maxOf { it.rva + maxOf(it.size, it.virtualSize) }
        }
        if (tail != null) {
            val extra = alignText(maxOf(size, 4096).toLong(), image.fileAlignment).toIntChecked()
            requireText(data.size.toLong() + extra <= 128L * 1024 * 1024, "文字存储超出镜像大小限制")
            val offset = data.size
            val address = tail.rva + tail.size
            data = data.copyOf(data.size + extra)
            put32(data, tail.header + 8, tail.size.toLong() + extra)
            put32(data, tail.header + 16, tail.size.toLong() + extra)
            put32(data, image.optional + 4, image.u32(image.optional + 4) + extra)
            put32(data, image.optional + 56, alignText(address + extra, image.sectionAlignment))
            put32(data, image.optional + 64, 0)
            requireText(TextPe(data).rvaToOffset(address, size) == offset, "文字存储扩展映射校验失败")
            return TextStorage(address, offset, size).also(reservations::add)
        }
        val count = image.sections.size
        val header = image.sectionTable + count * 40
        val firstRaw = image.sections.filter { it.size > 0 }.minOf { it.raw }
        requireText(header + 40 <= minOf(image.sizeOfHeaders, firstRaw) && (header until header + 40).all { data[it] == 0.toByte() }, "PE 节表没有新增文字段的空间")
        val raw = alignText(data.size.toLong(), image.fileAlignment).toIntChecked()
        val rawSize = alignText(maxOf(size, 4096).toLong(), image.fileAlignment).toIntChecked()
        val rva = alignText(image.sections.maxOf { it.rva + maxOf(it.size, it.virtualSize) }, image.sectionAlignment)
        requireText(raw.toLong() + rawSize <= 128L * 1024 * 1024 && rva + rawSize <= 0xffff_ffffL, "新增文字段超出镜像大小限制")
        data = data.copyOf(raw + rawSize)
        ".eftxt".toByteArray(Charsets.US_ASCII).copyInto(data, header)
        put32(data, header + 8, rawSize.toLong()); put32(data, header + 12, rva)
        put32(data, header + 16, rawSize.toLong()); put32(data, header + 20, raw.toLong())
        put32(data, header + 36, 0x60000020)
        put16(data, image.pe + 6, count + 1)
        put32(data, image.optional + 4, image.u32(image.optional + 4) + rawSize)
        put32(data, image.optional + 56, alignText(rva + rawSize, image.sectionAlignment))
        // The source firmware uses zero checksums; retain that convention after modification.
        put32(data, image.optional + 64, 0)
        image = TextPe(data)
        requireText(image.rvaToOffset(rva, size) == raw, "新增文字段映射校验失败")
        return TextStorage(rva, raw, size).also(reservations::add)
    }

    private fun relocationTargets(image: TextPe): List<Long> {
        val directory = image.optional + 112 + 5 * 8
        val rva = image.u32(directory); val length = image.u32(directory + 4)
        if (rva == 0L || length == 0L) return emptyList()
        val result = mutableListOf<Long>()
        val start = runCatching { image.rvaToOffset(rva, length.toIntChecked()) }.getOrNull() ?: throw TextEditException("重定位目录映射异常")
        var at = start
        val end = start + length.toIntChecked()
        val baseLow = image.u32(image.optional + 24)
        val baseHigh = image.u32(image.optional + 28)
        val base = baseLow or (baseHigh shl 32)
        while (at + 8 <= end) {
            val page = image.u32(at); val block = image.u32(at + 4).toIntChecked()
            if (page == 0L && block == 0) break
            requireText(block >= 8 && block % 2 == 0 && at + block <= end, "重定位块范围异常")
            for (e in at + 8 until at + block step 2) {
                val value = image.u16(e)
                if (value ushr 12 == 10) {
                    val offset = image.rvaToOffset(page + (value and 0xfff), 8)
                    val target = image.u32(offset) or (image.u32(offset + 4) shl 32)
                    if (target >= base) result += target - base
                }
            }
            at += block
        }
        return result
    }
}

internal object TextLayoutStubs {
    private val widthMarker = "AEFWIDTH".toByteArray(Charsets.US_ASCII)
    private val drawMarker = "AEFDRAW2".toByteArray(Charsets.US_ASCII)
    const val WIDTH_BYTES = 16 + 92

    fun widthPayload(rva: Long, fontCharZero: Long, budget: Int): ByteArray {
        val codeRva = rva + 16
        val words = mutableListOf<Int>()
        val labels = mutableMapOf<String, Int>()
        val fixups = mutableListOf<Triple<Int, String, Int>>()
        fun emit(word: Long) { words += word.toInt() }
        fun mark(name: String) { labels[name] = words.size }
        fun jump(name: String, kind: Int) { fixups += Triple(words.size, name, kind); words += 0 }
        emit(0xaa0003e9); emit(0x5280000a); emit(0x5280000b)
        words += TextA64.adrp(12, codeRva + words.size * 4, fontCharZero)
        words += TextA64.add(12, fontCharZero)
        mark("loop"); emit(0x3940012d); emit(0x91000529); jump("done", 2)
        emit(0x710029bf); jump("line", 1); emit(0x710035bf); jump("line", 1)
        emit(0x8b2d518e); emit(0x394001ce); emit(0x0b0e014a); jump("loop", 0)
        mark("line"); emit(0x6b0b015f); emit(0x1a8b814b); emit(0x5280000a); jump("loop", 0)
        mark("done"); emit(0x6b0b015f); emit(0x1a8b8140); emit(0xd65f03c0)
        for ((index, name, kind) in fixups) {
            val delta = labels.getValue(name) - index
            words[index] = when (kind) { 0 -> (0x14000000L or (delta.toLong() and 0x3ffffff)).toInt(); 1 -> (0x54000000L or ((delta.toLong() and 0x7ffff) shl 5)).toInt(); else -> (0x3400000dL or ((delta.toLong() and 0x7ffff) shl 5)).toInt() }
        }
        val payload = ByteArray(16 + words.size * 4)
        widthMarker.copyInto(payload); put32(payload, 8, fontCharZero); put32(payload, 12, budget.toLong())
        words.forEachIndexed { index, word -> put32(payload, 16 + index * 4, word.toLong()) }
        requireText(payload.size == WIDTH_BYTES, "测宽代码大小校验失败")
        return payload
    }
    fun readWidth(image: TextPe, function: Long): Pair<Long, Int>? = runCatching {
        val offset = image.rvaToOffset(function - 16, WIDTH_BYTES)
        if (!image.data.copyOfRange(offset, offset + 8).contentEquals(widthMarker)) return@runCatching null
        val font = image.u32(offset + 8); val budget = image.u32(offset + 12).toIntChecked()
        if (!image.data.copyOfRange(offset, offset + WIDTH_BYTES).contentEquals(widthPayload(function - 16, font, budget))) return@runCatching null
        font to budget
    }.getOrNull()

    /** Clamp every native glyph to the actual framebuffer before drawing; native LF still scrolls. */
    fun drawPayload(rva: Long, renderer: Long, cursor: Long, frameGlobal: Long, fontZero: Long, maxHeight: Int): ByteArray {
        val start = rva + 32
        val words = mutableListOf<Int>()
        val labels = mutableMapOf<String, Int>()
        val fixups = mutableListOf<Triple<Int, String, Int>>()
        fun emit(word: Long) { words += word.toInt() }
        fun mark(name: String) { labels[name] = words.size }
        fun jump(name: String, kind: Int) { fixups += Triple(words.size, name, kind); words += 0 }
        emit(0xd503233f); emit(0xa9bc53f3); emit(0xa9015bf5); emit(0xa9027bf7)
        emit(0xaa0003f3)
        words += TextA64.adrp(20, start + words.size * 4, cursor); words += TextA64.add(20, cursor)
        emit(0xb9400295)
        words += TextA64.adrp(22, start + words.size * 4, frameGlobal); words += TextA64.add(22, frameGlobal)
        words += TextA64.movW(23, maxHeight)
        mark("loop"); emit(0x39400268); emit(0x91000673); jump("done", 2)
        emit(0x3900c3e8); emit(0x3900c7ff)
        emit(0xf94002c9); jump("loop", 3) // frame pointer must exist
        emit(0xb940092e); jump("loop", 5) // panel width must be nonzero
        // Native background rows advance width+2 pixels. Reserve enough extra rows
        // for their linear footprint, without pulling rightmost glyphs leftward.
        emit(0x510006ea); emit(0x531f794a); emit(0x0b0e014a); emit(0x5100054a)
        emit(0x1ace094a); emit(0x0b17014a); emit(0xb9400d2b); emit(0x6b0a017f); jump("loop", 4)
        emit(0x4b0a016a); emit(0x7100115f); jump("loop", 4)
        emit(0xb940068b); emit(0x6b0a017f); emit(0x1a8b814b)
        // Native foreground changes Y=0 to Y=4. Establish that minimum first.
        emit(0x5280008c); emit(0x6b0c017f); emit(0x1a8b318b); emit(0xb900068b)
        words += TextA64.adrp(12, start + words.size * 4, fontZero); words += TextA64.add(12, fontZero)
        emit(0x3940c3e8); emit(0x8b28518d); emit(0x394001ad); emit(0x110005ad)
        emit(0xb940092e); emit(0x6b0d01df); jump("loop", 4)
        emit(0x4b0d01ce); emit(0xb940028c); emit(0x6b0e019f); emit(0x1a8c81cc); emit(0xb900028c)
        emit(0x9100c3e0)
        words += TextA64.branch(start + words.size * 4, renderer, true)
        emit(0x3940c3e8); emit(0x7100291f); jump("loop", 1)
        emit(0xb9000295); jump("loop", 0)
        mark("done"); emit(0xa9427bf7); emit(0xa9415bf5); emit(0xa8c453f3); emit(0xd50323bf); emit(0xd65f03c0)
        for ((index, name, kind) in fixups) {
            val delta = labels.getValue(name) - index
            words[index] = when (kind) {
                0 -> (0x14000000L or (delta.toLong() and 0x3ffffff)).toInt()
                1 -> (0x54000001L or ((delta.toLong() and 0x7ffff) shl 5)).toInt()
                2 -> (0x34000008L or ((delta.toLong() and 0x7ffff) shl 5)).toInt()
                3 -> (0xb4000009L or ((delta.toLong() and 0x7ffff) shl 5)).toInt()
                5 -> (0x3400000eL or ((delta.toLong() and 0x7ffff) shl 5)).toInt()
                else -> (0x54000003L or ((delta.toLong() and 0x7ffff) shl 5)).toInt()
            }
        }
        val payload = ByteArray(32 + words.size * 4)
        drawMarker.copyInto(payload); put32(payload, 8, renderer); put32(payload, 12, cursor)
        put32(payload, 16, frameGlobal); put32(payload, 20, fontZero); put32(payload, 24, maxHeight.toLong())
        words.forEachIndexed { index, word -> put32(payload, 32 + index * 4, word.toLong()) }
        return payload
    }
    fun readDraw(image: TextPe, function: Long): Pair<Long, Long>? = runCatching {
        val offset = image.rvaToOffset(function - 32, 32)
        if (!image.data.copyOfRange(offset, offset + 8).contentEquals(drawMarker)) return@runCatching null
        val renderer = image.u32(offset + 8); val cursor = image.u32(offset + 12)
        val payload = drawPayload(function - 32, renderer, cursor, image.u32(offset + 16), image.u32(offset + 20), image.u32(offset + 24).toIntChecked())
        val whole = image.rvaToOffset(function - 32, payload.size)
        if (!image.data.copyOfRange(whole, whole + payload.size).contentEquals(payload)) return@runCatching null
        renderer to cursor
    }.getOrNull()
}
