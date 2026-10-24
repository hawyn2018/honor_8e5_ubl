package dev.honor.bootforge.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VbmetaPatcherTest {
    @get:Rule val directory = TemporaryFolder()

    private fun fixture(flags: Int = 0, bytes: Int = 4096): ByteArray = ByteArray(bytes).apply {
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)
        buffer.put(byteArrayOf(65, 86, 66, 48)).putInt(1).putInt(0)
        buffer.putLong(12, 64).putLong(20, 64)
        buffer.putInt(VbmetaPatcher.FLAGS_OFFSET, flags)
        "avbtool 1.3.0".toByteArray().copyInto(this, VbmetaPatcher.RELEASE_OFFSET)
        for (index in 256 until size) this[index] = (index * 13).toByte()
    }

    private fun patch(bytes: ByteArray): Pair<VbmetaPatcher.Result, ByteArray> {
        val source = directory.newFile("source-${System.nanoTime()}.img").apply { writeBytes(bytes) }
        val destination = java.io.File(directory.root, "patched-${System.nanoTime()}.img")
        val result = VbmetaPatcher.patch(source, destination)
        assertArrayEquals("Original image must remain unchanged", bytes, source.readBytes())
        return result to destination.readBytes()
    }

    @Test fun `sets verification flag in big endian low byte and preserves full partition image`() {
        val original = fixture()
        val (result, patched) = patch(original)
        assertEquals(0L, result.originalFlags)
        assertEquals(2L, result.patchedFlags)
        assertEquals(1, result.changedBytes)
        assertEquals(original.size.toLong(), result.bytes)
        assertEquals(2.toByte(), patched[123])
        assertEquals(listOf(123), original.indices.filter { original[it] != patched[it] })
        assertArrayEquals(original.copyOfRange(128, 176), patched.copyOfRange(128, 176))
    }

    @Test fun `preserves pre-existing verity and unknown flags`() {
        val (result, patched) = patch(fixture(0x40000001))
        assertEquals(0x40000003L, result.patchedFlags)
        assertEquals(0x40000003, ByteBuffer.wrap(patched).order(ByteOrder.BIG_ENDIAN).getInt(120))
    }

    @Test fun `already disabled flag is idempotent`() {
        val original = fixture(3)
        val (result, patched) = patch(original)
        assertEquals(0, result.changedBytes)
        assertArrayEquals(original, patched)
    }

    @Test fun `does not replace decoy avbtool sequence in payload`() {
        val original = fixture().apply { byteArrayOf(0, 0, 0, 0, 0, 97, 118, 98, 116, 111, 111, 108).copyInto(this, 512) }
        val (_, patched) = patch(original)
        assertArrayEquals(original.copyOfRange(512, 524), patched.copyOfRange(512, 524))
    }

    @Test fun `rejects truncated header`() = rejects(ByteArray(255))
    @Test fun `rejects missing magic despite embedded avbtool`() = rejects(fixture().apply { this[0] = 0 })
    @Test fun `rejects oversized data block`() = rejects(fixture().apply { ByteBuffer.wrap(this).putLong(12, 8192) })
    @Test fun `rejects unsigned size above signed file range`() = rejects(fixture().apply { ByteBuffer.wrap(this).putLong(12, Long.MIN_VALUE) })
    @Test fun `rejects unaligned data block`() = rejects(fixture().apply { ByteBuffer.wrap(this).putLong(20, 63) })
    @Test fun `rejects authentication range outside its data block`() = rejects(fixture().apply {
        ByteBuffer.wrap(this).putLong(32, 32).putLong(40, 64)
    })
    @Test fun `rejects overflowing auxiliary range without integer wraparound`() = rejects(fixture().apply {
        ByteBuffer.wrap(this).putLong(96, Long.MAX_VALUE).putLong(104, 64)
    })
    @Test fun `rejects unterminated release string`() = rejects(fixture().apply { fill(65.toByte(), 128, 176) })
    @Test fun `rejects reserved fields`() = rejects(fixture().apply { this[255] = 1 })
    @Test fun `rejects unsupported header major`() = rejects(fixture().apply { ByteBuffer.wrap(this).putInt(4, 2) })

    @Test fun `refuses overwriting original or existing output`() {
        val source = directory.newFile("in.img").apply { writeBytes(fixture()) }
        assertTrue(runCatching { VbmetaPatcher.patch(source, source) }.isFailure)
        val existing = directory.newFile("existing.img").apply { writeText("preserve") }
        assertTrue(runCatching { VbmetaPatcher.patch(source, existing) }.isFailure)
        assertEquals("preserve", existing.readText())
    }

    private fun rejects(bytes: ByteArray) {
        val source = directory.newFile("invalid.img").apply { writeBytes(bytes) }
        val target = java.io.File(directory.root, "invalid-patched.img")
        assertTrue(runCatching { VbmetaPatcher.patch(source, target) }.isFailure)
        assertFalse(target.exists())
        assertArrayEquals(bytes, source.readBytes())
    }
}
