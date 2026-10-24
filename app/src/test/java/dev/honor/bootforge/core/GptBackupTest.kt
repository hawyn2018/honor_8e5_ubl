package dev.honor.bootforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class GptBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val diskBytes = 64L * 1024 * 1024
    private val expectedGuid = "12345678-9ABC-DEF0-1122-334455667788"

    @Test fun `accepts native sgdisk backup from 512 byte disk`() {
        val metadata = validate(backup())
        assertEquals(expectedGuid, metadata.diskGuid)
        assertEquals(128L, metadata.entryCount)
        assertEquals(128L, metadata.entrySize)
    }

    @Test fun `accepts native backup with 4096 byte source sectors and 512 byte file headers`() {
        assertEquals(expectedGuid, validate(backup(sector = 4096), sector = 4096).diskGuid)
    }

    @Test fun `accepts larger valid partition entry size`() {
        assertEquals(256L, validate(backup(entrySize = 256)).entrySize)
    }

    @Test fun `rejects truncated header and entry array`() {
        val bytes = backup()
        listOf(0, 511, 1023, 1535, bytes.size - 1).forEach { reject(bytes.copyOf(it)) }
    }

    @Test fun `rejects trailing data instead of silently accepting another file layout`() {
        reject(backup() + byteArrayOf(0))
    }

    @Test fun `rejects protective MBR without signature`() {
        reject(backup().apply { this[510] = 0 })
    }

    @Test fun `rejects missing or duplicate protective MBR record`() {
        reject(backup().apply { this[450] = 0 })
        reject(backup().apply { copyInto(this, 462, 446, 462) })
    }

    @Test fun `rejects protective MBR range outside disk`() {
        reject(backup().apply { putInt(446 + 8, 0) })
        reject(backup().apply { putInt(446 + 12, -1) })
    }

    @Test fun `rejects invalid main or secondary header CRC`() {
        listOf(512, 1024).forEach { offset -> reject(backup().apply { this[offset + 56] = 0 }) }
    }

    @Test fun `rejects entry array corruption`() {
        reject(backup().apply { this[1536 + 15] = 1 })
    }

    @Test fun `rejects wrong signature and revision even with recalculated CRC`() {
        reject(backup().apply { this[512] = 0; headerCrc(512) })
        reject(backup().apply { putInt(512 + 8, 0x00020000); headerCrc(512) })
    }

    @Test fun `rejects malformed header length and reserved field`() {
        listOf(0, 91, 513, -1).forEach { size -> reject(backup().apply { putInt(512 + 12, size) }) }
        reject(backup().apply { putInt(512 + 20, 1); headerCrc(512) })
    }

    @Test fun `rejects headers claiming a different disk or reversed locations`() {
        reject(backup().apply { putLong(512 + 24, 2); headerCrc(512) })
        reject(backup().apply { putLong(1024 + 24, diskBytes / 512 - 2); headerCrc(1024) })
        reject(backup().apply { putLong(1024 + 32, 2); headerCrc(1024) })
    }

    @Test fun `rejects mismatched GUID and usable ranges`() {
        reject(backup().apply { this[1024 + 57] = 0; headerCrc(1024) })
        reject(backup().apply { putLong(1024 + 40, 35); headerCrc(1024) })
        reject(backup().apply { putInt(1024 + 12, 96); headerCrc(1024) })
    }

    @Test fun `rejects empty disk GUID`() {
        reject(backup().apply {
            fill(0, 512 + 56, 512 + 72); fill(0, 1024 + 56, 1024 + 72)
            headerCrc(512); headerCrc(1024)
        })
    }

    @Test fun `rejects inconsistent entry count size or CRC in secondary header`() {
        listOf(80 to 129, 84 to 256, 88 to 0).forEach { (field, value) ->
            reject(backup().apply { putInt(1024 + field, value); headerCrc(1024) })
        }
    }

    @Test fun `rejects unsigned entry sizes and counts that would overflow or allocate excessive memory`() {
        reject(backup().apply { headers { putInt(it + 80, -1); putInt(it + 84, -1) } })
        reject(backup().apply { headers { putInt(it + 80, 0) } })
        reject(backup().apply { headers { putInt(it + 84, 64) } })
        reject(backup().apply { headers { putInt(it + 84, 192) } })
        reject(backup().apply { headers { putInt(it + 84, 0x40000000) } })
    }

    @Test fun `rejects negative representation of unsigned 64 bit LBA`() {
        reject(backup().apply { putLong(512 + 72, -1L); headerCrc(512) })
    }

    @Test fun `rejects entry tables overlapping usable sectors or either header`() {
        reject(backup().apply { putLong(512 + 72, 1); headerCrc(512) })
        reject(backup().apply { putLong(512 + 72, 3); headerCrc(512) })
        reject(backup().apply { putLong(1024 + 72, diskBytes / 512 - 32); headerCrc(1024) })
        reject(backup().apply { putLong(1024 + 72, diskBytes / 512 - 34); headerCrc(1024) })
    }

    @Test fun `rejects inverted usable range and array counts larger than actual file`() {
        reject(backup().apply { headers { putLong(it + 40, diskBytes / 512 - 33) } })
        reject(backup().apply { headers { putInt(it + 80, 256) } })
    }

    @Test fun `rejects mismatched disk size and invalid logical sector sizes`() {
        val file = temporary.newFile().apply { writeBytes(backup()) }
        listOf(0L, 511L, 768L, 131072L).forEach { sector ->
            assertTrue(runCatching { GptBackup.validate(file, diskBytes, sector) }.isFailure)
        }
        listOf(0L, diskBytes - 1, diskBytes + 512).forEach { bytes ->
            assertTrue(runCatching { GptBackup.validate(file, bytes, 512) }.isFailure)
        }
    }

    @Test fun `rejects oversized file before processing entries`() {
        val file = temporary.newFile()
        java.io.RandomAccessFile(file, "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
        assertTrue(runCatching { GptBackup.validate(file, diskBytes, 512) }.isFailure)
    }

    private fun validate(bytes: ByteArray, sector: Long = 512): GptBackupMetadata =
        GptBackup.validate(temporary.newFile().apply { writeBytes(bytes) }, diskBytes, sector)

    private fun reject(bytes: ByteArray) {
        assertTrue("Malformed GPT backup was accepted", runCatching { validate(bytes) }.isFailure)
    }

    private fun backup(sector: Long = 512, entrySize: Int = 128): ByteArray {
        val tableBytes = 128 * entrySize
        val bytes = ByteArray(1536 + tableBytes)
        val sectors = diskBytes / sector
        val tableSectors = (tableBytes + sector - 1) / sector
        bytes[510] = 0x55
        bytes[511] = 0xaa.toByte()
        bytes[450] = 0xee.toByte()
        bytes.putInt(454, 1)
        bytes.putInt(458, (sectors - 1).toInt())
        val guid = byteArrayOf(0x78, 0x56, 0x34, 0x12, 0xbc.toByte(), 0x9a.toByte(), 0xf0.toByte(), 0xde.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
        val tableCrc = CRC32().apply { update(bytes, 1536, tableBytes) }.value
        listOf(512, 1024).forEach { offset ->
            "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(bytes, offset)
            bytes.putInt(offset + 8, 0x00010000)
            bytes.putInt(offset + 12, 92)
            bytes.putLong(offset + 24, if (offset == 512) 1 else sectors - 1)
            bytes.putLong(offset + 32, if (offset == 512) sectors - 1 else 1)
            bytes.putLong(offset + 40, 2 + tableSectors)
            bytes.putLong(offset + 48, sectors - tableSectors - 2)
            guid.copyInto(bytes, offset + 56)
            bytes.putLong(offset + 72, if (offset == 512) 2 else sectors - tableSectors - 1)
            bytes.putInt(offset + 80, 128)
            bytes.putInt(offset + 84, entrySize)
            bytes.putInt(offset + 88, tableCrc.toInt())
            bytes.headerCrc(offset)
        }
        return bytes
    }

    private fun ByteArray.headers(change: (Int) -> Unit) {
        listOf(512, 1024).forEach { offset -> change(offset); headerCrc(offset) }
    }

    private fun ByteArray.headerCrc(offset: Int) {
        putInt(offset + 16, 0)
        val size = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).getInt(offset + 12)
        putInt(offset + 16, CRC32().apply { update(this@headerCrc, offset, size) }.value.toInt())
    }

    private fun ByteArray.putInt(offset: Int, value: Int) { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value) }
    private fun ByteArray.putLong(offset: Int, value: Long) { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value) }
}
