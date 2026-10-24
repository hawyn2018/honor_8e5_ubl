package dev.honor.bootforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class GptRawSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()
    private val diskBytes = 64L * 1024 * 1024

    @Test fun `preserves full 512 byte and 4096 byte regions including padding`() {
        listOf(512, 4096).forEach { sector ->
            val bytes = snapshot(sector)
            val file = temporary.newFile().apply { writeBytes(bytes) }
            val metadata = GptRawSnapshot.validate(file, diskBytes, sector.toLong())
            assertTrue(metadata.primary.headerValid && metadata.primary.entriesValid)
            assertTrue(metadata.backup.headerValid && metadata.backup.entriesValid)
            assertEquals(0, metadata.attributeDifferenceCount)
            assertTrue(metadata.warnings.isEmpty())
            val extracted = GptRawSnapshot.extractRegions(file, metadata, temporary.newFolder(), "gpt_sda")
            assertEquals(5, extracted.size)
            metadata.regions.zip(extracted).forEach { (region, output) ->
                assertTrue(output.readBytes().contentEquals(bytes.copyOfRange(region.snapshotOffsetBytes.toInt(), (region.snapshotOffsetBytes + region.length).toInt())))
                assertEquals(region.length, output.length())
            }
            assertTrue(file.readBytes().contentEquals(bytes))
        }
    }

    @Test fun `independently valid tables may have different attributes and stored CRCs`() {
        val bytes = snapshot(4096, backupAttributes = 1L shl 50)
        val metadata = validate(bytes, 4096)
        assertTrue(metadata.primary.entriesValid && metadata.backup.entriesValid)
        assertTrue(metadata.primary.headerValid && metadata.backup.headerValid)
        assertTrue(metadata.primary.entriesStored != metadata.backup.entriesStored)
        assertEquals(1, metadata.attributeDifferenceCount)
        assertEquals("0x0004000000000000", metadata.attributeDifferenceMask)
        assertEquals(0, metadata.otherEntryDifferenceCount)
        assertTrue(metadata.warnings.any { "属性不同" in it })
    }

    @Test fun `accepts vendor saturated protective MBR on a smaller UFS disk`() {
        val bytes = snapshot(4096, backupAttributes = 1L shl 50).apply { putInt(512 + 458, -1) }
        val metadata = validate(bytes, 4096)
        assertTrue(metadata.primary.headerValid && metadata.backup.headerValid)
        assertTrue(metadata.primary.entriesValid && metadata.backup.entriesValid)
        assertEquals(1, metadata.attributeDifferenceCount)
    }

    @Test fun `other attribute bits and nonattribute differences are retained and warned`() {
        val bytes = snapshot(512, backupAttributes = Long.MIN_VALUE or 7L)
        val secondaryEntries = backupEntriesOffset(512)
        bytes[secondaryEntries + 20] = 0x77
        bytes.refreshCrcs(512)
        val metadata = validate(bytes)
        assertEquals("0x8000000000000007", metadata.attributeDifferenceMask)
        assertEquals(1, metadata.otherEntryDifferenceCount)
        assertTrue(metadata.primary.entriesValid && metadata.backup.entriesValid)
        assertTrue(metadata.warnings.size >= 2)
    }

    @Test fun `bad entries CRC is a warning and leaves both arrays untouched`() {
        val bytes = snapshot(4096)
        bytes[primaryEntriesOffset(4096) + 52] = 0x20
        val file = temporary.newFile().apply { writeBytes(bytes) }
        val metadata = GptRawSnapshot.validate(file, diskBytes, 4096)
        assertFalse(metadata.primary.entriesValid)
        assertTrue(metadata.backup.entriesValid)
        assertTrue(metadata.primary.headerValid)
        assertTrue(metadata.warnings.any { "主 GPT 条目表 CRC" in it })
        GptRawSnapshot.extractRegions(file, metadata, temporary.newFolder(), "gpt_sdb")
        assertTrue(file.readBytes().contentEquals(bytes))
    }

    @Test fun `bad header CRC is reported independently from the entries CRC`() {
        val bytes = snapshot(512)
        bytes[512 + 512 + 16] = (bytes[512 + 512 + 16].toInt() xor 1).toByte()
        val metadata = validate(bytes)
        assertFalse(metadata.primary.headerValid)
        assertTrue(metadata.primary.entriesValid && metadata.backup.headerValid && metadata.backup.entriesValid)
    }

    @Test fun `rejects truncated trailing malformed or oversized container structure`() {
        val good = snapshot(512)
        listOf(0, 511, 1023, good.size - 1).forEach { reject(good.copyOf(it)) }
        reject(good + byteArrayOf(0))
        reject(good.copyOf().apply { this[0] = 0 })
        reject(good.copyOf().apply { putInt(12, 2) })
        reject(good.copyOf().apply { this[64] = 1 })
        reject(good.copyOf().apply { putInt(8, 4096) })
        reject(good.copyOf().apply { putLong(56, GptRawSnapshot.MAX_ENTRY_BYTES + 1) })
        reject(good.copyOf().apply { putLong(32, Long.MIN_VALUE) })
        reject(good.copyOf().apply { putLong(16, diskBytes + 512) })
    }

    @Test fun `rejects header locations dimensions GUIDs and overlapping regions`() {
        val main = 1024
        val backup = backupHeaderOffset(512)
        reject(snapshot(512).apply { putLong(main + 24, 2) })
        reject(snapshot(512).apply { putLong(backup + 32, 2) })
        reject(snapshot(512).apply { putInt(main + 80, -1) })
        reject(snapshot(512).apply { putInt(main + 84, 64) })
        reject(snapshot(512).apply { this[backup + 56] = 8 })
        reject(snapshot(512).apply { putLong(32, 1); putLong(main + 72, 1) })
        reject(snapshot(512).apply { putLong(main + 40, 2); putLong(backup + 40, 2) })
        reject(snapshot(512).apply { putLong(backup + 72, diskBytes / 512 - 1) })
        reject(snapshot(512).apply { this[512 + 510] = 0 })
    }

    private fun validate(bytes: ByteArray, sector: Int = 512): GptRawSnapshotMetadata =
        GptRawSnapshot.validate(temporary.newFile().apply { writeBytes(bytes) }, diskBytes, sector.toLong())
    private fun reject(bytes: ByteArray) {
        assertTrue("Untrusted structure was accepted", runCatching { validate(bytes) }.isFailure)
    }

    private fun primaryEntriesOffset(sector: Int) = 512 + 2 * sector
    private fun paddedEntries(sector: Int) = ((5 * 128 + sector - 1) / sector) * sector
    private fun backupHeaderOffset(sector: Int) = primaryEntriesOffset(sector) + paddedEntries(sector)
    private fun backupEntriesOffset(sector: Int) = backupHeaderOffset(sector) + sector

    private fun snapshot(sector: Int, backupAttributes: Long = 0): ByteArray {
        val padded = paddedEntries(sector)
        val bytes = ByteArray(512 + 3 * sector + 2 * padded)
        val sectors = diskBytes / sector
        val tableSectors = padded.toLong() / sector
        "HUBLGT22".toByteArray().copyInto(bytes)
        bytes.putInt(8, sector); bytes.putInt(12, 1)
        bytes.putLong(16, diskBytes); bytes.putLong(24, 1); bytes.putLong(32, 2)
        bytes.putLong(40, sectors - 1); bytes.putLong(48, sectors - tableSectors - 1); bytes.putLong(56, 5L * 128L)
        val mbr = 512
        bytes[mbr + 510] = 0x55; bytes[mbr + 511] = 0xaa.toByte(); bytes[mbr + 450] = 0xee.toByte()
        bytes.putInt(mbr + 454, 1); bytes.putInt(mbr + 458, (sectors - 1).toInt())
        if (sector > 512) bytes[mbr + 512] = 0x6b
        val main = mbr + sector
        val backup = backupHeaderOffset(sector)
        listOf(main, backup).forEach { header ->
            "EFI PART".toByteArray().copyInto(bytes, header)
            bytes.putInt(header + 8, 0x10000); bytes.putInt(header + 12, 92)
            bytes.putLong(header + 24, if (header == main) 1 else sectors - 1)
            bytes.putLong(header + 32, if (header == main) sectors - 1 else 1)
            bytes.putLong(header + 40, 2 + tableSectors); bytes.putLong(header + 48, sectors - tableSectors - 2)
            bytes[header + 56] = 1
            bytes.putLong(header + 72, if (header == main) 2 else sectors - tableSectors - 1)
            bytes.putInt(header + 80, 5); bytes.putInt(header + 84, 128)
            bytes[header + sector - 1] = if (header == main) 0x61 else 0x62
        }
        bytes.putLong(backupEntriesOffset(sector) + 48, backupAttributes)
        bytes.refreshCrcs(sector)
        return bytes
    }

    private fun ByteArray.refreshCrcs(sector: Int) {
        listOf((512 + sector) to primaryEntriesOffset(sector), backupHeaderOffset(sector) to backupEntriesOffset(sector)).forEach { (header, entries) ->
            putInt(header + 88, CRC32().apply { update(this@refreshCrcs, entries, 5 * 128) }.value.toInt())
            putInt(header + 16, 0)
            putInt(header + 16, CRC32().apply { update(this@refreshCrcs, header, 92) }.value.toInt())
        }
    }
    private fun ByteArray.putInt(offset: Int, value: Int) { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value) }
    private fun ByteArray.putLong(offset: Int, value: Long) { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value) }
}
