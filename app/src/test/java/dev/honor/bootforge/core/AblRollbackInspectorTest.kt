package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/** Generated structural fixtures deliberately carry no assertion of signature trust. */
class AblRollbackInspectorTest {
    private fun p32(b: ByteArray, at: Int, n: Long) { repeat(4) { b[at + it] = (n ushr (it * 8)).toByte() } }
    private fun p16(b: ByteArray, at: Int, n: Int) { repeat(2) { b[at + it] = (n ushr (it * 8)).toByte() } }
    private fun der(tag: Int, b: ByteArray): ByteArray = byteArrayOf(tag.toByte()) +
        (if (b.size < 128) byteArrayOf(b.size.toByte()) else byteArrayOf(0x82.toByte(), (b.size ushr 8).toByte(), b.size.toByte())) + b
    private fun seq(vararg b: ByteArray) = der(0x30, b.fold(byteArrayOf()) { a, v -> a + v })
    private fun cert(vararg ous: String): ByteArray {
        val subject = seq(*ous.map { der(0x31, seq(der(6, byteArrayOf(0x55, 4, 11)), der(12, it.toByteArray()))) }.toTypedArray())
        val algorithm = seq(der(6, byteArrayOf(0x2a, 3)))
        val validity = seq(der(23, "260101000000Z".toByteArray()), der(23, "490101000000Z".toByteArray()))
        val spki = seq(algorithm, der(3, byteArrayOf(0, 1)))
        return seq(seq(der(2, byteArrayOf(1)), algorithm, seq(), validity, subject, spki), algorithm, der(3, byteArrayOf(0, 1)))
    }
    private fun meta(value: Long, major: Int = 3, minor: Int = 0): ByteArray = ByteArray(if (major <= 1) 120 else if (minor == 1) 228 else 224).also {
        p32(it, 0, major.toLong()); p32(it, 4, minor.toLong()); p32(it, if (major <= 1) 116 else 8, value)
    }
    private fun modern(oem: ByteArray = meta(0), qti: ByteArray = byteArrayOf(), version: Int = 7): ByteArray {
        val common = ByteArray(24); p32(common, 8, 0x7fffffff) // SW_ID is not ARB.
        val chain = cert("Hihonor"); val sig = ByteArray(32) { 0x5a }
        val h = ByteArray(if (version == 8) 56 else 40)
        p32(h, 4, version.toLong()); p32(h, 8, common.size.toLong()); p32(h, 12, qti.size.toLong()); p32(h, 16, oem.size.toLong())
        p32(h, 20, 16); p32(h, 32, sig.size.toLong()); p32(h, 36, chain.size.toLong())
        return h + common + qti + oem + ByteArray(16) + sig + chain
    }
    private fun elf(mbn: ByteArray, klass: Int = 1): ByteArray {
        val eh = if (klass == 1) 52 else 64; val stride = if (klass == 1) 32 else 56; val offset = 256
        return ByteArray(offset + mbn.size).also {
            byteArrayOf(0x7f, 69, 76, 70, klass.toByte(), 1, 1).copyInto(it)
            p16(it, 16, 2); p16(it, 18, if (klass == 1) 40 else 183); p32(it, 20, 1)
            p32(it, if (klass == 1) 28 else 32, eh.toLong()); p16(it, if (klass == 1) 40 else 52, eh)
            p16(it, if (klass == 1) 42 else 54, stride); p16(it, if (klass == 1) 44 else 56, 1)
            p32(it, eh + if (klass == 1) 24 else 4, 0x02000000)
            p32(it, eh + if (klass == 1) 4 else 8, offset.toLong()); p32(it, eh + if (klass == 1) 16 else 32, mbn.size.toLong())
            mbn.copyInto(it, offset)
        }
    }
    private fun pe(): ByteArray = ByteArray(1024).also {
        it[0] = 77; it[1] = 90; p32(it, 0x3c, 0x80); p32(it, 0x80, 0x4550); p16(it, 0x84, 0xaa64); p16(it, 0x86, 1); p16(it, 0x94, 0xf0)
        p16(it, 0x98, 0x20b); p32(it, 0xa8, 0x1000); p32(it, 0xd0, 0x2000); p32(it, 0xd4, 0x200); p16(it, 0xdc, 10); p32(it, 0x104, 16)
        p32(it, 0x190, 0x200); p32(it, 0x194, 0x1000); p32(it, 0x198, 0x200); p32(it, 0x19c, 0x200); p32(it, 0x1ac, 0x60000020)
    }
    private fun legacy(value: Long, extraCa: Boolean = false): ByteArray {
        val sw = "01 %08X0000001C SW_ID".format(value)
        val chain = cert(sw) + if (extraCa) cert("01 FFFFFFFF0000001C SW_ID") else byteArrayOf()
        return ByteArray(40).also { p32(it, 4, 3); p32(it, 16, (16 + 32 + chain.size).toLong()); p32(it, 20, 16); p32(it, 28, 32); p32(it, 36, chain.size.toLong()) } + ByteArray(16) + ByteArray(32) + chain
    }
    private fun parsed(b: ByteArray) = AblRollbackInspector.inspect(b).also { assertTrue(it.toString(), it is AblRollbackInspector.Result.Parsed) } as AblRollbackInspector.Result.Parsed
    private fun invalid(b: ByteArray) { assertTrue(AblRollbackInspector.inspect(b).toString(), AblRollbackInspector.inspect(b) is AblRollbackInspector.Result.Invalid) }

    @Test fun modernOemCountersAndCommonSoftwareIdAreDistinct() {
        val zero = parsed(elf(modern())); assertEquals(listOf(0L), zero.counters.map { it.value })
        assertEquals(AblRollbackInspector.Authority.OEM, zero.counters.single().authority)
        assertEquals(AblRollbackGuardPolicy.Decision.Allowed, AblRollbackGuardPolicy.finalEfisp(zero))
        assertEquals(AblRollbackGuardPolicy.Decision.Unsupported, AblRollbackGuardPolicy.finalEfisp(parsed(elf(modern(meta(1))))))
    }
    @Test fun qtiAndOemHaveIndependentOffsets() {
        val r = parsed(elf(modern(meta(11), meta(7)), 2))
        assertEquals(listOf(AblRollbackInspector.Authority.QTI, AblRollbackInspector.Authority.OEM), r.counters.map { it.authority })
        assertEquals(listOf(7L, 11L), r.counters.map { it.value })
        assertEquals(AblRollbackGuardPolicy.Decision.Unsupported, AblRollbackGuardPolicy.finalEfisp(r))
        assertEquals(AblRollbackGuardPolicy.Decision.Unsupported, AblRollbackGuardPolicy.finalEfisp(parsed(elf(modern(meta(0), meta(1))))))
    }
    @Test fun metadataVersionsUseTheirOwnCounterField() {
        for (v in 0..1) assertEquals(9L, parsed(modern(meta(9, v))).counters.single().value)
        assertEquals(9L, parsed(modern(meta(9, 2))).counters.single().value)
        assertEquals(9L, parsed(modern(meta(9, 3, 1))).counters.single().value)
        assertEquals(9L, parsed(modern(meta(9), version = 8)).counters.single().value)
        invalid(modern(meta(0).also { p32(it, 0, 99) }))
        invalid(modern(meta(0, 3, 1).copyOf(224)))
    }
    @Test fun legacyUsesOnlyLeafSubjectAndHighHalfOfSwId() {
        assertEquals(0L, parsed(legacy(0, true)).counters.single().value)
        assertEquals(7L, parsed(legacy(7)).counters.single().value)
        val b = legacy(0); val text = "SW_ID".toByteArray(); text.copyInto(b, 40); assertEquals(0L, parsed(b).counters.single().value)
    }
    @Test fun malformedOrUnknownSignedLayoutsNeverBecomeZero() {
        invalid(byteArrayOf()); invalid(ByteArray(20)); invalid(elf(modern()).copyOf(100))
        invalid(modern().also { p32(it, 4, 6) })
        invalid(elf(modern()).also { p32(it, 52 + 24, 0) })
        invalid(elf(modern()).also { p32(it, 52 + 16, 0xffffffffL) })
        invalid(modern().also { p32(it, 36, 0) })
        invalid(modern().also { p32(it, 16, 0xffffffffL) })
        invalid(modern().let { it.copyOf(it.size - 1) })
        invalid(modern().also { it[it.size - cert("Hihonor").size] = 0x31 })
        invalid(modern().also { p32(it, 16, 0) })
        invalid(modern().also { b -> val at = b.toString(Charsets.ISO_8859_1).indexOf("260101000000Z"); b[at - 2] = 5 })
    }
    @Test fun peRequiresMappedExecutableEntryAndEmptyCertificateDirectory() {
        val b = pe(); val result = AblRollbackInspector.inspect(b)
        assertTrue(result is AblRollbackInspector.Result.NoMetadata)
        assertEquals(AblRollbackGuardPolicy.Decision.Allowed, AblRollbackGuardPolicy.finalEfisp(result))
        "01 FFFFFFFF0000001C SW_ID".toByteArray().copyInto(b, 600)
        assertTrue(AblRollbackInspector.inspect(b) is AblRollbackInspector.Result.NoMetadata)
        invalid(pe().also { p32(it, 0x128, 0x300); p32(it, 0x12c, 0x20) })
        invalid(pe().also { p32(it, 0xa8, 0x5000) })
        invalid(pe().also { p32(it, 0x1ac, 0x40000040) })
        invalid(pe().also { p32(it, 0x19c, 0x100) })
        invalid(pe().also { p16(it, 0x84, 0x8664) })
    }
    @Test fun fileInspectionIsReadOnlyAndSameAsByteArray() {
        val b = elf(modern()); val saved = b.clone(); val f = File.createTempFile("abl-arb-", ".img")
        try { f.writeBytes(b); assertEquals(AblRollbackInspector.inspect(b), AblRollbackInspector.inspect(f)); assertArrayEquals(saved, b); assertArrayEquals(saved, f.readBytes()) }
        finally { f.delete() }
    }
    @Test fun unknownAndEmptyCounterResultsAreTreatedAsNoCounter() {
        assertEquals(AblRollbackGuardPolicy.Decision.Allowed, AblRollbackGuardPolicy.finalEfisp(AblRollbackInspector.Result.Invalid("broken")))
        assertEquals(AblRollbackGuardPolicy.Decision.Allowed, AblRollbackGuardPolicy.finalEfisp(AblRollbackInspector.Result.Parsed(AblRollbackInspector.Format.SIGNED_ELF, emptyList(), emptyList())))
        assertEquals(AblRollbackGuardPolicy.Decision.Allowed, AblRollbackGuardPolicy.finalEfisp(AblRollbackInspector.Result.NoMetadata(AblRollbackInspector.Format.SIGNED_MBN, emptyList())))
    }
    @Test fun paddedPartitionPeAndExact128MiBAreAcceptedButOversizeIsNot() {
        for (size in listOf(68 * 1024 * 1024, AblRollbackInspector.MAX_IMAGE_BYTES)) {
            assertTrue(AblRollbackInspector.inspect(pe().copyOf(size)) is AblRollbackInspector.Result.NoMetadata)
        }
        val f = File.createTempFile("abl-arb-limit-", ".img")
        try {
            RandomAccessFile(f, "rw").use { it.setLength(AblRollbackInspector.MAX_IMAGE_BYTES.toLong() + 1) }
            assertTrue(AblRollbackInspector.inspect(f) is AblRollbackInspector.Result.Invalid)
        } finally { f.delete() }
    }
}
