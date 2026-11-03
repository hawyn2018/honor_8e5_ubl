package net.hawyn.ablefisp.patch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Own RX/RW mappings and an independently checked restoration receipt.
 * Existing mapped sections/file offsets never move. Non-owned text edits survive removal.
 */
internal object FastbootRecoverySections {
    const val RX_NAME = ".textfr"
    const val RW_NAME = ".datafr"
    const val FOOTER_BYTES = 64
    private val MAGIC = "HBFBR137".toByteArray(Charsets.US_ASCII)
    data class Edit(val raw: Int, val before: ByteArray, val after: ByteArray)
    data class Layout(val output: ByteArray, val rx: PeSection, val rw: PeSection)
    data class Removed(val bytes: ByteArray, val hadRecovery: Boolean, val rebuiltFromDamage: Boolean)

    fun receiptBytes(image: PeImage, edits: List<Edit>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(image.headersSize)
            out.write(image.bytes, 0, image.headersSize)
            out.writeInt(edits.size)
            edits.forEach {
                require(it.before.size == it.after.size && it.raw >= image.headersSize &&
                    it.raw.toLong() + it.before.size <= image.bytes.size)
                out.writeInt(it.raw); out.writeInt(it.before.size)
                out.write(it.before); out.write(it.after)
            }
        }
        return bytes.toByteArray()
    }

    fun append(image: PeImage, rxRequired: Int, stateRequired: Int): Layout {
        require(rxRequired > FOOTER_BYTES && stateRequired > 0)
        var source = image.bytes
        var pe = source.u32(0x3c).asBoundedInt("PE 头")
        var optional = pe + 24
        val count = source.u16(pe + 6)
        val optionalBytes = source.u16(pe + 20)
        if (count > 65533) fail("PE 节数量没有两个恢复映射的名额")
        var table = optional + optionalBytes
        var slot = table + count * 40
        var end = slot + 80
        val headerEnd = minOf(image.headersSize, image.sections.filter { it.rawSize > 0 }.minOf { it.raw })
        fun owned(start: Int, finish: Int): Boolean {
            val directories = minOf(16L, source.u32(optional + 108),
                maxOf(0, (optionalBytes - 112) / 8).toLong()).toInt()
            for (i in 0 until directories) {
                val at = optional + 112 + i * 8
                val address = source.u32(at); val size = source.u32(at + 4)
                if (size > 0 && address < finish && start < address + size) return true
            }
            val symbols = source.u32(pe + 12); val bytes = source.u32(pe + 16) * 18
            return bytes > 0 && symbols < finish && start < symbols + bytes
        }
        if (end > headerEnd || (slot until end).any { source[it] != 0.toByte() } || owned(slot, end)) {
            val span = 24 + optionalBytes + (count + 2) * 40
            val target = (0x80..pe - span step 8).firstOrNull { at ->
                at + span <= headerEnd && !owned(at, at + span) &&
                    (at until at + span).all { source[it] == 0.toByte() }
            } ?: fail("PE 头部没有可证明未占用的恢复节表空间")
            val relocated = source.copyOf()
            source.copyInto(relocated, target, pe, table + count * 40)
            relocated.putWord(0x3c, target)
            source = relocated; pe = target; optional = pe + 24
            table = optional + optionalBytes; slot = table + count * 40; end = slot + 80
        }
        if (owned(slot, end)) fail("恢复节表与原目录或符号信息重叠")
        fun alignment(at: Int): Long = source.u32(at).also {
            if (it <= 0 || it and (it - 1) != 0L) fail("PE 对齐字段无效")
        }
        val fileAlign = alignment(optional + 36)
        val sectionAlign = alignment(optional + 32)
        if (sectionAlign < fileAlign) fail("PE 加载对齐顺序无效")
        val rawAlign = maxOf(4096L, fileAlign)
        val rvaAlign = maxOf(4096L, sectionAlign)
        val rxRaw = aligned(source.size.toLong(), rawAlign)
        val rxSize = aligned(rxRequired.toLong(), rawAlign)
        val rxRva = aligned(maxOf(source.u32(optional + 56), image.sections.maxOf {
            it.rva + maxOf(it.virtualSize, it.rawSize.toLong())
        }), rvaAlign)
        val rwRaw = rxRaw + rxSize
        val rwSize = aligned(stateRequired.toLong(), rawAlign)
        val rwRva = aligned(rxRva + rxSize, rvaAlign)
        val fileEnd = rwRaw + rwSize
        val memoryEnd = aligned(rwRva + rwSize, sectionAlign)
        val codeBytes = source.u32(optional + 4) + rxSize
        val dataBytes = source.u32(optional + 8) + rwSize
        if (fileEnd > Int.MAX_VALUE || memoryEnd > 0xffff_ffffL ||
            codeBytes > 0xffff_ffffL || dataBytes > 0xffff_ffffL) fail("恢复映射超出实际 PE/ByteArray 字段范围")
        val out = source.copyOf(fileEnd.toInt())
        fun section(at: Int, name: String, raw: Long, rva: Long, size: Long, flags: Int) {
            name.toByteArray(Charsets.US_ASCII).copyInto(out, at)
            out.putWord(at + 8, size.toInt()); out.putWord(at + 12, rva.toInt())
            out.putWord(at + 16, size.toInt()); out.putWord(at + 20, raw.toInt())
            out.putWord(at + 36, flags)
        }
        section(slot, RX_NAME, rxRaw, rxRva, rxSize, 0x60000020)
        section(slot + 40, RW_NAME, rwRaw, rwRva, rwSize, 0xc0000040.toInt())
        out[pe + 6] = (count + 2).toByte(); out[pe + 7] = ((count + 2) ushr 8).toByte()
        out.putWord(optional + 4, codeBytes.toInt()); out.putWord(optional + 8, dataBytes.toInt())
        out.putWord(optional + 56, memoryEnd.toInt()); out.putWord(optional + 64, 0)
        val checked = PeImage.parse(out)
        check(checked.entryRva == image.entryRva && checked.imageBase == image.imageBase &&
            checked.sections.take(count) == image.sections)
        val rx = checked.sections[count]; val rw = checked.sections[count + 1]
        check(rx.name == RX_NAME && rx.executable && !rx.writable &&
            rw.name == RW_NAME && !rw.executable && rw.writable)
        return Layout(out, rx, rw)
    }

    fun writeReceipt(layout: Layout, original: PeImage, edits: List<Edit>, codeEnd: Int) {
        val metadata = receiptBytes(original, edits)
        val footer = layout.rx.raw + layout.rx.rawSize - FOOTER_BYTES
        val at = footer - metadata.size
        if (at < layout.rx.raw + codeEnd) fail("恢复映射的代码与恢复清单重叠")
        metadata.copyInto(layout.output, at)
        MAGIC.copyInto(layout.output, footer)
        layout.output.putWord(footer + 8, 1)
        layout.output.putWord(footer + 12, metadata.size)
        put64(layout.output, footer + 16, original.bytes.size.toLong())
        layout.output.putWord(footer + 24, at - layout.rx.raw)
        layout.output.putWord(footer + 28, original.headersSize)
        digest(metadata).copyInto(layout.output, footer + 32)
    }

    fun remove(input: ByteArray): Removed {
        val image = PeImage.parse(input)
        val rx = image.sections.filter { it.name == RX_NAME }
        val rw = image.sections.filter { it.name == RW_NAME }
        if (rx.isEmpty() && rw.isEmpty()) return Removed(input.copyOf(), false, false)
        if (rx.size != 1 || rw.size != 1 || image.sections.takeLast(2) != listOf(rx.single(), rw.single()) ||
            !rx.single().executable || rx.single().writable || rw.single().executable || !rw.single().writable)
            fail("旧恢复映射结构不完整")
        val owned = rx.single()
        val footer = owned.raw + owned.rawSize - FOOTER_BYTES
        if (footer < owned.raw || !input.matches(footer, MAGIC) || input.u32(footer + 8) != 1L)
            fail("旧恢复清单标记或版本无效")
        val originalLength = input.u64(footer + 16).asBoundedInt("原始 PE 长度")
        val size = input.u32(footer + 12).asBoundedInt("恢复清单长度")
        val relative = input.u32(footer + 24).asBoundedInt("恢复清单位置")
        val at = owned.raw.toLong() + relative
        if (originalLength < image.headersSize || originalLength > owned.raw ||
            at < owned.raw || at + size != footer.toLong()) fail("旧恢复清单范围不一致")
        val metadata = input.copyOfRange(at.toInt(), footer)
        if (!digest(metadata).contentEquals(input.copyOfRange(footer + 32, footer + 64)))
            fail("旧恢复清单校验不一致")
        val out = input.copyOf(originalLength)
        val records = ArrayList<Edit>()
        DataInputStream(ByteArrayInputStream(metadata)).use { stream ->
            val headers = stream.readInt()
            if (headers != input.u32(footer + 28).toInt() || headers !in 0x40..originalLength || headers > stream.available())
                fail("旧恢复 PE 头快照范围无效")
            stream.readFully(out, 0, headers)
            val count = stream.readInt()
            if (count < 0 || count > stream.available() / 8) fail("旧恢复记录数量无效")
            repeat(count) {
                val raw = stream.readInt(); val length = stream.readInt()
                if (raw < headers || length <= 0 || raw.toLong() + length > originalLength ||
                    length.toLong() * 2 > stream.available()) fail("旧恢复指令记录范围无效")
                val before = ByteArray(length).also(stream::readFully)
                val after = ByteArray(length).also(stream::readFully)
                val actual = input.copyOfRange(raw, raw + length)
                if (!actual.contentEquals(after) && !actual.contentEquals(before))
                    fail("旧恢复挂接点已被其他代码覆盖")
                before.copyInto(out, raw)
                records += Edit(raw, before, after)
            }
            if (stream.available() != 0) fail("旧恢复清单存在额外字段")
        }
        val native = PeImage.parse(out)
        if (native.sections.size + 2 != image.sections.size ||
            native.sections != image.sections.take(native.sections.size) ||
            native.entryRva != image.entryRva || native.imageBase != image.imageBase)
            fail("恢复原 PE 后映射关系不一致")
        /* Receipt-controlled hooks/header are sufficient to rebuild damaged owned code.
         * Do not restore a whole source snapshot over legitimate non-owned text edits. */
        return Removed(out, true, false)
    }

    private fun put64(out: ByteArray, at: Int, value: Long) {
        out.putWord(at, value.toInt()); out.putWord(at + 4, (value ushr 32).toInt())
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun aligned(value: Long, alignment: Long): Long {
        if (value < 0 || value > Long.MAX_VALUE - alignment + 1) fail("恢复映射长度溢出")
        return (value + alignment - 1) / alignment * alignment
    }
    private fun fail(message: String): Nothing = throw PatchException("$message；原始镜像保持不变。")
}
