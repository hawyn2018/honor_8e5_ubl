package dev.honor.bootforge.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.CRC32

data class GptBackupMetadata(val diskGuid: String, val entryCount: Long, val entrySize: Long)

/**
 * Validates GPT fdisk's SaveGPTBackup format, not a raw image of disk sectors.
 * Its MBR, main header and secondary header occupy 512 bytes each even when
 * the source disk uses 4096-byte logical sectors; one entry array follows.
 * The source disk is never opened. This validates the exported file only:
 * sgdisk recomputes CRCs during export, so callers must also verify the disk.
 */
object GptBackup {
    private const val PREFIX_BYTES = 1536L
    private const val MAX_FILE_BYTES = 16L * 1024 * 1024
    private val signature = "EFI PART".toByteArray(Charsets.US_ASCII)

    fun validate(file: File, diskBytes: Long, logicalSectorBytes: Long): GptBackupMetadata {
        check(logicalSectorBytes in 512L..65536L && logicalSectorBytes and (logicalSectorBytes - 1) == 0L) {
            "GPT 备份的逻辑扇区大小无效"
        }
        check(diskBytes > 0 && diskBytes % logicalSectorBytes == 0L) { "GPT 备份的磁盘容量无效" }
        val sectors = diskBytes / logicalSectorBytes
        check(sectors >= 6) { "GPT 备份的磁盘容量过小" }
        check(file.isFile) { "GPT 备份文件缺失" }
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            check(length in (PREFIX_BYTES + 128)..MAX_FILE_BYTES) { "GPT 备份文件长度无效" }
            val mbr = ByteArray(512).also(input::readFully)
            validateMbr(mbr, sectors)
            val main = readHeader(input)
            val secondary = readHeader(input)

            check(main.currentLba == 1L && main.backupLba == sectors - 1 &&
                secondary.currentLba == main.backupLba && secondary.backupLba == main.currentLba) {
                "GPT 主备表头位置与磁盘不一致"
            }
            check(main.headerSize == secondary.headerSize && main.firstUsable == secondary.firstUsable && main.lastUsable == secondary.lastUsable &&
                main.entryCount == secondary.entryCount && main.entrySize == secondary.entrySize &&
                main.entriesCrc == secondary.entriesCrc && main.guid.contentEquals(secondary.guid)) {
                "GPT 主备表头参数不一致"
            }
            check(main.guid.any { it != 0.toByte() }) { "GPT 磁盘 GUID 为空" }
            check(main.firstUsable > main.currentLba && main.firstUsable <= main.lastUsable &&
                main.lastUsable < secondary.currentLba) { "GPT 可用范围无效" }

            // Both fields are unsigned 32-bit values; bound before multiplying.
            check(main.entryCount > 0 && main.entrySize >= 128 &&
                main.entrySize and (main.entrySize - 1) == 0L &&
                main.entrySize <= MAX_FILE_BYTES - PREFIX_BYTES &&
                main.entryCount <= (MAX_FILE_BYTES - PREFIX_BYTES) / main.entrySize) {
                "GPT 分区条目数量或大小无效"
            }
            val tableBytes = main.entryCount * main.entrySize
            check(length == PREFIX_BYTES + tableBytes) { "GPT 备份条目表长度不符" }
            val tableSectors = (tableBytes + logicalSectorBytes - 1) / logicalSectorBytes
            // Subtraction-based bounds avoid wrapping near signed Long's limit.
            check(main.entriesLba >= 2 && main.entriesLba < main.firstUsable &&
                tableSectors <= main.firstUsable - main.entriesLba &&
                secondary.entriesLba > main.lastUsable && secondary.entriesLba < secondary.currentLba &&
                tableSectors <= secondary.currentLba - secondary.entriesLba) {
                "GPT 分区条目表超出磁盘边界或与可用空间重叠"
            }

            val crc = CRC32()
            val buffer = ByteArray(64 * 1024)
            var remaining = tableBytes
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size.toLong()).toInt()
                input.readFully(buffer, 0, count)
                crc.update(buffer, 0, count)
                remaining -= count
            }
            check(crc.value == main.entriesCrc) { "GPT 分区条目表 CRC 校验失败" }
            check(input.length() == length && input.filePointer == length) { "GPT 备份文件在校验期间发生变化" }
            return GptBackupMetadata(formatGuid(main.guid), main.entryCount, main.entrySize)
        }
    }

    private data class Header(
        val headerSize: Long,
        val currentLba: Long,
        val backupLba: Long,
        val firstUsable: Long,
        val lastUsable: Long,
        val guid: ByteArray,
        val entriesLba: Long,
        val entryCount: Long,
        val entrySize: Long,
        val entriesCrc: Long,
    )

    private fun readHeader(input: RandomAccessFile): Header {
        val bytes = ByteArray(512).also(input::readFully)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(bytes.copyOfRange(0, 8).contentEquals(signature) && data.getInt(8) == 0x00010000) {
            "GPT 表头签名或版本无效"
        }
        val size = unsignedInt(data, 12)
        check(size in 92L..512L && data.getInt(20) == 0) { "GPT 表头长度或保留字段无效" }
        val expectedCrc = unsignedInt(data, 16)
        data.putInt(16, 0)
        check(CRC32().apply { update(bytes, 0, size.toInt()) }.value == expectedCrc) { "GPT 表头 CRC 校验失败" }
        fun lba(offset: Int): Long = data.getLong(offset).also {
            check(it >= 0) { "GPT LBA 超出受支持的磁盘容量范围" }
        }
        return Header(size, lba(24), lba(32), lba(40), lba(48), bytes.copyOfRange(56, 72), lba(72),
            unsignedInt(data, 80), unsignedInt(data, 84), unsignedInt(data, 88))
    }

    private fun validateMbr(mbr: ByteArray, sectors: Long) {
        check(mbr[510] == 0x55.toByte() && mbr[511] == 0xaa.toByte()) { "GPT 保护 MBR 签名无效" }
        val data = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)
        var protectiveRecords = 0
        repeat(4) { index ->
            val offset = 446 + index * 16
            if (mbr[offset + 4] == 0xee.toByte()) {
                protectiveRecords++
                check(unsignedInt(data, offset + 8) == 1L &&
                    unsignedInt(data, offset + 12) in 1L..minOf(sectors - 1, 0xffffffffL)) {
                    "GPT 保护 MBR 分区范围无效"
                }
            }
        }
        check(protectiveRecords == 1) { "GPT 保护 MBR 分区记录缺失或重复" }
    }

    private fun unsignedInt(data: ByteBuffer, offset: Int): Long = data.getInt(offset).toLong() and 0xffffffffL

    private fun formatGuid(bytes: ByteArray): String {
        val order = intArrayOf(3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15)
        return buildString {
            order.forEachIndexed { index, offset ->
                if (index == 4 || index == 6 || index == 8 || index == 10) append('-')
                append(String.format(Locale.ROOT, "%02X", bytes[offset].toInt() and 0xff))
            }
        }
    }
}
