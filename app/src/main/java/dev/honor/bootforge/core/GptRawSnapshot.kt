package dev.honor.bootforge.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32

data class GptRawRegion(
    val name: String,
    val diskOffsetBytes: Long,
    val snapshotOffsetBytes: Long,
    val length: Long,
    val sha256: String,
)

data class GptRawCrcStatus(
    val headerStored: Long,
    val headerComputed: Long,
    val entriesStored: Long,
    val entriesComputed: Long,
) {
    val headerValid: Boolean get() = headerStored == headerComputed
    val entriesValid: Boolean get() = entriesStored == entriesComputed
}

data class GptRawSnapshotMetadata(
    val diskBytes: Long,
    val logicalSectorBytes: Long,
    val diskGuid: String,
    val entryCount: Long,
    val entrySize: Long,
    val entryBytes: Long,
    val regions: List<GptRawRegion>,
    val primary: GptRawCrcStatus,
    val backup: GptRawCrcStatus,
    val attributeDifferenceCount: Int,
    val attributeDifferenceMask: String,
    val otherEntryDifferenceCount: Int,
    val warnings: List<String>,
)

/**
 * Parses the bounded, byte-preserving HUBLGT22 container. The source disk is never
 * opened. CRC failures describe the bytes as captured; neither table is repaired
 * or chosen as canonical. Geometry must still be safe before offsets are used.
 */
object GptRawSnapshot {
    const val HEADER_BYTES = 512L
    const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    private val magic = "HUBLGT22".toByteArray(Charsets.US_ASCII)
    private val gptSignature = "EFI PART".toByteArray(Charsets.US_ASCII)

    fun validate(file: File, diskBytes: Long, logicalSectorBytes: Long): GptRawSnapshotMetadata {
        check(logicalSectorBytes in 512L..65536L && logicalSectorBytes and (logicalSectorBytes - 1) == 0L) {
            "GPT 原始快照逻辑扇区大小无效"
        }
        check(diskBytes > 0 && diskBytes % logicalSectorBytes == 0L && diskBytes / logicalSectorBytes >= 6) {
            "GPT 原始快照磁盘容量无效"
        }
        check(file.isFile) { "GPT 原始快照文件缺失" }
        RandomAccessFile(file, "r").use { input ->
            val originalLength = input.length()
            check(originalLength in HEADER_BYTES..(HEADER_BYTES + 3 * 65536L + 2 * (MAX_ENTRY_BYTES + 65535L))) {
                "GPT 原始快照文件长度超出边界"
            }
            val container = readAt(input, 0, HEADER_BYTES.toInt())
            val fields = ByteBuffer.wrap(container).order(ByteOrder.LITTLE_ENDIAN)
            check(container.copyOfRange(0, 8).contentEquals(magic) && u32(fields, 12) == 1L) {
                "GPT 原始快照格式或版本无效"
            }
            check(container.copyOfRange(64, container.size).all { it == 0.toByte() }) { "GPT 原始快照容器保留字段无效" }
            check(u32(fields, 8) == logicalSectorBytes && positive64(fields, 16) == diskBytes) {
                "GPT 原始快照与磁盘几何信息不一致"
            }
            val sector = logicalSectorBytes
            val sectors = diskBytes / sector
            val primaryHeaderLba = positive64(fields, 24)
            val primaryEntriesLba = positive64(fields, 32)
            val backupHeaderLba = positive64(fields, 40)
            val backupEntriesLba = positive64(fields, 48)
            val entryBytes = positive64(fields, 56)
            check(entryBytes in 128L..MAX_ENTRY_BYTES) { "GPT 原始快照分区条目表长度无效" }
            val paddedEntries = ((entryBytes + sector - 1) / sector) * sector
            check(primaryHeaderLba == 1L && backupHeaderLba == sectors - 1) { "GPT 原始快照主备表头位置无效" }
            val expectedLength = HEADER_BYTES + 3 * sector + 2 * paddedEntries
            check(originalLength == expectedLength) { "GPT 原始快照长度与区域布局不符" }

            val mbrOffset = HEADER_BYTES
            val primaryHeaderOffset = mbrOffset + sector
            val primaryEntriesOffset = primaryHeaderOffset + sector
            val backupHeaderOffset = primaryEntriesOffset + paddedEntries
            val backupEntriesOffset = backupHeaderOffset + sector
            val mbr = readAt(input, mbrOffset, sector.toInt())
            check(mbr[510] == 0x55.toByte() && mbr[511] == 0xaa.toByte()) { "GPT 原始快照保护 MBR 签名无效" }
            val mbrFields = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)
            val protective = (0..3).filter { mbr[446 + it * 16 + 4] == 0xee.toByte() }
            check(protective.size == 1) { "GPT 原始快照保护 MBR 记录缺失或重复" }
            val protectiveOffset = 446 + protective.single() * 16
            check(mbr[protectiveOffset] == 0.toByte() && u32(mbrFields, protectiveOffset + 8) == 1L &&
                u32(mbrFields, protectiveOffset + 12) in setOf(minOf(sectors - 1, 0xffffffffL), 0xffffffffL) &&
                (0..3).filter { it != protective.single() }.all { index ->
                    mbr.copyOfRange(446 + index * 16, 446 + (index + 1) * 16).all { it == 0.toByte() }
                }) {
                "GPT 原始快照保护 MBR 范围无效"
            }

            val main = parseHeader(readAt(input, primaryHeaderOffset, sector.toInt()))
            val secondary = parseHeader(readAt(input, backupHeaderOffset, sector.toInt()))
            check(main.currentLba == primaryHeaderLba && main.backupLba == backupHeaderLba &&
                secondary.currentLba == backupHeaderLba && secondary.backupLba == primaryHeaderLba &&
                main.entriesLba == primaryEntriesLba && secondary.entriesLba == backupEntriesLba) {
                "GPT 原始快照的容器位置与主备表头不一致"
            }
            check(main.headerSize == secondary.headerSize && main.firstUsable == secondary.firstUsable && main.lastUsable == secondary.lastUsable &&
                main.entryCount == secondary.entryCount && main.entrySize == secondary.entrySize &&
                main.guid.contentEquals(secondary.guid)) { "GPT 主备结构参数不一致，原始快照结构不可信" }
            check(main.guid.any { it != 0.toByte() } && main.firstUsable > 1 &&
                main.firstUsable <= main.lastUsable && main.lastUsable < backupHeaderLba) { "GPT 可用范围或磁盘 GUID 无效" }
            check(main.entryCount > 0 && main.entrySize in 128L..MAX_ENTRY_BYTES && main.entrySize % 128L == 0L &&
                main.entryCount <= MAX_ENTRY_BYTES / main.entrySize && main.entryCount * main.entrySize == entryBytes) {
                "GPT 分区条目数量、大小或容器长度不一致"
            }
            val tableSectors = paddedEntries / sector
            check(primaryEntriesLba >= 2 && primaryEntriesLba < main.firstUsable &&
                tableSectors <= main.firstUsable - primaryEntriesLba &&
                backupEntriesLba > main.lastUsable && backupEntriesLba < backupHeaderLba &&
                tableSectors <= backupHeaderLba - backupEntriesLba) { "GPT 分区条目表与表头或可用范围重叠" }

            val primaryCrc = GptRawCrcStatus(main.headerStored, main.headerComputed, main.entriesCrc,
                rangeCrc(input, primaryEntriesOffset, entryBytes))
            val backupCrc = GptRawCrcStatus(secondary.headerStored, secondary.headerComputed, secondary.entriesCrc,
                rangeCrc(input, backupEntriesOffset, entryBytes))
            val differences = compareEntries(file, primaryEntriesOffset, backupEntriesOffset, entryBytes, main.entrySize)
            val warnings = buildList {
                if (!primaryCrc.headerValid) add("主 GPT 表头 CRC 不匹配，原始字节已保留")
                if (!primaryCrc.entriesValid) add("主 GPT 条目表 CRC 不匹配，原始字节已保留")
                if (!backupCrc.headerValid) add("备 GPT 表头 CRC 不匹配，原始字节已保留")
                if (!backupCrc.entriesValid) add("备 GPT 条目表 CRC 不匹配，原始字节已保留")
                if (differences.attributes > 0) add("主备 GPT 有 ${differences.attributes} 个条目的属性不同，差异掩码 ${differences.maskHex}；两份表均原样保存")
                if (differences.other > 0) add("主备 GPT 有 ${differences.other} 个条目存在属性以外的差异，两份表均原样保存")
            }
            fun region(name: String, lba: Long, offset: Long, length: Long): GptRawRegion {
                check(lba < sectors && length <= diskBytes - lba * sector) { "GPT 区域超出物理磁盘边界" }
                return GptRawRegion(name, lba * sector, offset, length, rangeHash(input, offset, length))
            }
            val regions = listOf(
                region("mbr", 0, mbrOffset, sector),
                region("primary_header", primaryHeaderLba, primaryHeaderOffset, sector),
                region("primary_entries", primaryEntriesLba, primaryEntriesOffset, paddedEntries),
                region("backup_header", backupHeaderLba, backupHeaderOffset, sector),
                region("backup_entries", backupEntriesLba, backupEntriesOffset, paddedEntries),
            )
            check(input.length() == originalLength) { "GPT 原始快照在解析期间发生变化" }
            return GptRawSnapshotMetadata(diskBytes, sector, formatGuid(main.guid), main.entryCount, main.entrySize,
                entryBytes, regions, primaryCrc, backupCrc, differences.attributes, differences.maskHex, differences.other, warnings)
        }
    }

    /** Each extracted file includes the original sector padding and has its own hash. */
    fun extractRegions(file: File, metadata: GptRawSnapshotMetadata, directory: File, prefix: String): List<File> {
        check(Regex("[A-Za-z0-9_-]+").matches(prefix)) { "GPT 导出文件前缀无效" }
        return RandomAccessFile(file, "r").use { input ->
            metadata.regions.map { region ->
                check(region.snapshotOffsetBytes >= HEADER_BYTES && region.length > 0 &&
                    region.snapshotOffsetBytes <= input.length() && region.length <= input.length() - region.snapshotOffsetBytes) {
                    "GPT 区域导出范围无效"
                }
                val output = File(directory, "${prefix}_${region.name}.bin")
                check(!output.exists()) { "GPT 区域导出目标已存在" }
                input.seek(region.snapshotOffsetBytes)
                output.outputStream().use { stream ->
                    var remaining = region.length
                    val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val count = minOf(remaining, buffer.size.toLong()).toInt()
                        input.readFully(buffer, 0, count)
                        stream.write(buffer, 0, count)
                        remaining -= count
                    }
                    stream.fd.sync()
                }
                check(output.length() == region.length && output.inputStream().use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    hex(digest.digest())
                } == region.sha256) { "GPT 独立区域导出校验失败" }
                output
            }
        }
    }

    private data class Header(val headerSize: Long, val currentLba: Long, val backupLba: Long, val firstUsable: Long, val lastUsable: Long,
        val guid: ByteArray, val entriesLba: Long, val entryCount: Long, val entrySize: Long,
        val entriesCrc: Long, val headerStored: Long, val headerComputed: Long)

    private fun parseHeader(bytes: ByteArray): Header {
        val fields = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(bytes.copyOfRange(0, 8).contentEquals(gptSignature) && u32(fields, 8) == 0x00010000L) { "GPT 表头签名或版本无效" }
        val size = u32(fields, 12)
        check(size in 92L..bytes.size.toLong() && u32(fields, 20) == 0L) { "GPT 表头长度或保留字段无效" }
        val stored = u32(fields, 16)
        val checked = bytes.copyOf(size.toInt()).apply { fill(0, 16, 20) }
        return Header(size, positive64(fields, 24), positive64(fields, 32), positive64(fields, 40), positive64(fields, 48),
            bytes.copyOfRange(56, 72), positive64(fields, 72), u32(fields, 80), u32(fields, 84), u32(fields, 88),
            stored, CRC32().apply { update(checked) }.value)
    }

    private data class Differences(val attributes: Int, val mask: Long, val other: Int) {
        val maskHex: String get() = "0x" + java.lang.Long.toUnsignedString(mask, 16).padStart(16, '0')
    }

    private fun compareEntries(file: File, first: Long, second: Long, bytes: Long, entrySize: Long): Differences {
        var attributes = 0
        var other = 0
        var mask = 0L
        var attributeChanged = false
        var otherChanged = false
        RandomAccessFile(file, "r").use { a -> RandomAccessFile(file, "r").use { b ->
            a.seek(first); b.seek(second)
            val left = ByteArray(64 * 1024)
            val right = ByteArray(left.size)
            var processed = 0L
            while (processed < bytes) {
                val count = minOf(bytes - processed, left.size.toLong()).toInt()
                a.readFully(left, 0, count); b.readFully(right, 0, count)
                repeat(count) { index ->
                    val within = (processed + index) % entrySize
                    val xor = (left[index].toInt() xor right[index].toInt()) and 0xff
                    if (xor != 0) {
                        if (within in 48L..55L) {
                            attributeChanged = true
                            mask = mask or (xor.toLong() shl ((within - 48) * 8).toInt())
                        } else otherChanged = true
                    }
                    if (within == entrySize - 1) {
                        if (attributeChanged) attributes++
                        if (otherChanged) other++
                        attributeChanged = false; otherChanged = false
                    }
                }
                processed += count
            }
        } }
        return Differences(attributes, mask, other)
    }

    private fun readAt(file: RandomAccessFile, offset: Long, count: Int): ByteArray {
        file.seek(offset)
        return ByteArray(count).also(file::readFully)
    }
    private fun u32(buffer: ByteBuffer, offset: Int): Long = buffer.getInt(offset).toLong() and 0xffffffffL
    private fun positive64(buffer: ByteBuffer, offset: Int): Long = buffer.getLong(offset).also {
        check(it >= 0) { "GPT 无符号字段超出受支持的有符号容量边界" }
    }
    private fun rangeCrc(file: RandomAccessFile, offset: Long, length: Long): Long {
        val crc = CRC32()
        readRange(file, offset, length) { bytes, count -> crc.update(bytes, 0, count) }
        return crc.value
    }
    private fun rangeHash(file: RandomAccessFile, offset: Long, length: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        readRange(file, offset, length) { bytes, count -> digest.update(bytes, 0, count) }
        return hex(digest.digest())
    }
    private fun readRange(file: RandomAccessFile, offset: Long, length: Long, update: (ByteArray, Int) -> Unit) {
        file.seek(offset)
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val count = minOf(remaining, buffer.size.toLong()).toInt()
            file.readFully(buffer, 0, count)
            update(buffer, count)
            remaining -= count
        }
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    private fun formatGuid(bytes: ByteArray): String {
        val order = intArrayOf(3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15)
        return buildString {
            order.forEachIndexed { index, offset ->
                if (index in intArrayOf(4, 6, 8, 10)) append('-')
                append("%02X".format(Locale.ROOT, bytes[offset].toInt() and 0xff))
            }
        }
    }
}
