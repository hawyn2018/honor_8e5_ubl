package dev.honor.bootforge

import dev.honor.bootforge.core.AblRollbackGuardPolicy
import dev.honor.bootforge.core.AblRollbackInspector
import dev.honor.bootforge.core.EfispArbFallback
import dev.honor.bootforge.core.EfispImagePatcher
import dev.honor.bootforge.core.EfispPatchMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Exercises the production OTA decision chain on ordinary host files. Counter
 * containers have valid ELF/MBN/DER structure, not a trusted firmware signature.
 * Only the image-patching backend is replaced; parsing and policies are real.
 */
class OtaArbFlowSimulationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun p32(b: ByteArray, at: Int, n: Long) {
        repeat(4) { b[at + it] = (n ushr (it * 8)).toByte() }
    }
    private fun p16(b: ByteArray, at: Int, n: Int) {
        repeat(2) { b[at + it] = (n ushr (it * 8)).toByte() }
    }
    private fun der(tag: Int, bytes: ByteArray): ByteArray = byteArrayOf(tag.toByte()) +
        (if (bytes.size < 128) byteArrayOf(bytes.size.toByte()) else
            byteArrayOf(0x82.toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte())) + bytes
    private fun seq(vararg bytes: ByteArray) = der(0x30, bytes.fold(byteArrayOf()) { all, part -> all + part })
    private fun certificate(): ByteArray {
        val subject = seq(der(0x31, seq(der(6, byteArrayOf(0x55, 4, 11)), der(12, "OTA fixture".toByteArray()))))
        val algorithm = seq(der(6, byteArrayOf(0x2a, 3)))
        val validity = seq(der(23, "260101000000Z".toByteArray()), der(23, "490101000000Z".toByteArray()))
        val spki = seq(algorithm, der(3, byteArrayOf(0, 1)))
        return seq(seq(der(2, byteArrayOf(1)), algorithm, seq(), validity, subject, spki),
            algorithm, der(3, byteArrayOf(0, 1)))
    }
    private fun metadata(value: Long): ByteArray = ByteArray(224).also {
        p32(it, 0, 3); p32(it, 4, 0); p32(it, 8, value)
    }
    private fun signedMbn(oem: Long, qti: Long? = null, version: Int = 7): ByteArray {
        val common = ByteArray(24).also { p32(it, 8, 0x7fffffff) }
        val qtiBytes = qti?.let(::metadata) ?: byteArrayOf()
        val oemBytes = metadata(oem)
        val cert = certificate(); val signature = ByteArray(32) { 0x5a }
        val header = ByteArray(if (version == 8) 56 else 40).also {
            p32(it, 4, version.toLong()); p32(it, 8, common.size.toLong())
            p32(it, 12, qtiBytes.size.toLong()); p32(it, 16, oemBytes.size.toLong())
            p32(it, 20, 16); p32(it, 32, signature.size.toLong()); p32(it, 36, cert.size.toLong())
        }
        return header + common + qtiBytes + oemBytes + ByteArray(16) + signature + cert
    }
    private fun signedElf(oem: Long, qti: Long? = null, version: Int = 7): ByteArray {
        val mbn = signedMbn(oem, qti, version)
        val offset = 256
        return ByteArray(offset + mbn.size).also {
            byteArrayOf(0x7f, 69, 76, 70, 2, 1, 1).copyInto(it)
            p16(it, 16, 2); p16(it, 18, 183); p32(it, 20, 1)
            p32(it, 32, 64); p16(it, 52, 64); p16(it, 54, 56); p16(it, 56, 1)
            p32(it, 68, 0x02000000); p32(it, 72, offset.toLong()); p32(it, 96, mbn.size.toLong())
            mbn.copyInto(it, offset)
        }
    }
    private fun noCounterPe(): ByteArray = ByteArray(1024).also {
        it[0] = 77; it[1] = 90; p32(it, 0x3c, 0x80); p32(it, 0x80, 0x4550)
        p16(it, 0x84, 0xaa64); p16(it, 0x86, 1); p16(it, 0x94, 0xf0)
        p16(it, 0x98, 0x20b); p32(it, 0xa8, 0x1000); p32(it, 0xd0, 0x2000)
        p32(it, 0xd4, 0x200); p16(it, 0xdc, 10); p32(it, 0x104, 16)
        p32(it, 0x190, 0x200); p32(it, 0x194, 0x1000); p32(it, 0x198, 0x200)
        p32(it, 0x19c, 0x200); p32(it, 0x1ac, 0x60000020)
    }
    private fun image(name: String, bytes: ByteArray): File = File(temporary.root, name).apply { writeBytes(bytes) }
    private fun patchFixture(source: File, destination: File, mode: Boolean, bytes: ByteArray): EfispImagePatcher.Result {
        destination.writeBytes(bytes)
        return EfispImagePatcher.Result(bytes.size.toLong(), OtaPolicy.sha(bytes), OtaPolicy.sha(source.readBytes()),
            "structural-test-fixture", mode, 0, 0, emptyList())
    }

    @Test fun actualModernContainersDriveCopyUsingMaximumOemOrQtiCounter() {
        val cases = listOf(0L to 0L, 1L to 0L, 0L to 1L, 7L to 2L, 2L to 11L, 0xffffffffL to 0L)
        for (version in listOf(7, 8)) for ((oem, qti) in cases) {
            for ((kind, bytes) in listOf("elf" to signedElf(oem, qti, version), "mbn" to signedMbn(oem, qti, version))) {
                val file = image("boot-$kind-$version-$oem-$qti.img", bytes)
                assertTrue(OtaPolicy.signedContainer(bytes))
                val parsed = AblRollbackGuardPolicy.inspectReadable(file)
                assertTrue(parsed.toString(), parsed is AblRollbackInspector.Result.Parsed)
                val result = parsed as AblRollbackInspector.Result.Parsed
                assertEquals(listOf(AblRollbackInspector.Authority.QTI, AblRollbackInspector.Authority.OEM), result.counters.map { it.authority })
                assertEquals(listOf(qti, oem), result.counters.map { it.value })
                val decision = OtaPolicy.arb(result)
                assertEquals(maxOf(oem, qti), decision.value)
                assertEquals(oem != 0L || qti != 0L, OtaPolicy.requiresBootChainCopy(listOf(decision)))
                assertArrayEquals(bytes, file.readBytes())
            }
        }
    }

    @Test fun absentAndUnrecognizedMetadataMapToZeroWithoutMaskingAnotherPositiveImage() {
        val zero = image("zero.img", signedElf(0, 0))
        val absent = image("absent.img", noCounterPe())
        val unknown = image("unknown.img", byteArrayOf(1, 2, 3))
        assertTrue(AblRollbackGuardPolicy.inspectReadable(absent) is AblRollbackInspector.Result.NoMetadata)
        assertTrue(AblRollbackGuardPolicy.inspectReadable(unknown) is AblRollbackInspector.Result.Invalid)
        val decisions = listOf(zero, absent, unknown).map { OtaPolicy.arb(AblRollbackGuardPolicy.inspectReadable(it)) }
        assertEquals(listOf(0L, 0L, 0L), decisions.map { it.value })
        assertFalse(OtaPolicy.requiresBootChainCopy(decisions))
        assertFalse(OtaPolicy.requiresBootChainCopy(emptyList()))
        val positive = image("positive.img", signedElf(0, 9, 8))
        assertTrue(OtaPolicy.requiresBootChainCopy(decisions + OtaPolicy.arb(AblRollbackGuardPolicy.inspectReadable(positive))))
    }

    @Test fun actualZeroAbsentAndUnknownProductsKeepSelectedWithoutReadingFallbackSources() {
        val products = listOf(signedElf(0, 0), signedMbn(0, 0, 8), noCounterPe(), byteArrayOf(1, 2, 3))
        products.forEachIndexed { index, bytes ->
            val initial = image("selected-$index.img", bytes)
            val inspections = mutableListOf<File>()
            val result = EfispArbFallback.prepareWith(initial, { error("current source must stay unused") },
                { error("builtin source must stay unused") }, temporary.root, true, {},
                { file -> inspections += file; AblRollbackGuardPolicy.inspectReadable(file) },
                { _, _, _, _ -> error("accepted selected product must not be patched") })
            assertEquals(listOf(initial), inspections)
            assertEquals(EfispArbFallback.Source.SELECTED, result.source)
            assertEquals(initial, result.file)
            assertEquals(if (index < 2) "0" else "absent", result.arbBinding)
            assertArrayEquals(bytes, initial.readBytes())
        }
    }

    @Test fun eitherAuthorityTriggersCurrentFallbackAndPreservesBothPatchModes() {
        for ((label, initialBytes) in listOf("oem" to signedElf(1, 0), "qti" to signedElf(0, 7, 8))) {
            for (fakeLock in listOf(false, true)) {
                val initial = image("selected-$label-$fakeLock.img", initialBytes)
                val currentBytes = signedElf(99, 9)
                val current = image("source-current-$label-$fakeLock.img", currentBytes)
                val inspections = mutableListOf<File>(); val sources = mutableListOf<File>(); val modes = mutableListOf<Boolean>()
                val result = EfispArbFallback.prepareWith(initial, { current }, { error("builtin source must stay unused") },
                    temporary.root, fakeLock, {},
                    { file -> inspections += file; check(file != current); AblRollbackGuardPolicy.inspectReadable(file) },
                    { source, destination, mode, _ ->
                        sources += source; modes += mode
                        patchFixture(source, destination, mode, signedElf(0, 0))
                    })
                assertEquals(listOf(current), sources); assertEquals(listOf(fakeLock), modes)
                assertEquals(listOf(initial, result.file), inspections)
                assertEquals(EfispArbFallback.Source.CURRENT_SLOT_ABL, result.source)
                assertEquals("0", result.arbBinding)
                assertEquals(OtaPolicy.sha(currentBytes), result.sourceSha256)
                assertArrayEquals(currentBytes, current.readBytes())
            }
        }
    }

    @Test fun currentFallbackWithAbsentOrUnrecognizedMetadataIsAdopted() {
        listOf(noCounterPe(), byteArrayOf(1, 2, 3)).forEachIndexed { index, product ->
            val initial = image("positive-$index.img", signedElf(4, 0))
            val current = image("source-$index.img", signedElf(8, 0))
            val result = EfispArbFallback.prepareWith(initial, { current }, { error("builtin source must stay unused") },
                temporary.root, false, {}, AblRollbackGuardPolicy::inspectReadable,
                { source, destination, mode, _ -> patchFixture(source, destination, mode, product) })
            assertEquals(EfispArbFallback.Source.CURRENT_SLOT_ABL, result.source)
            assertEquals("absent", result.arbBinding)
            assertArrayEquals(product, result.file.readBytes())
        }
    }

    @Test fun secondPositiveProductUsesBuiltinWithoutInspectingItsCounter() {
        for (fakeLock in listOf(false, true)) {
            val initial = image("selected-last-$fakeLock.img", signedElf(1, 0))
            val current = image("source-current-last-$fakeLock.img", signedElf(0, 0))
            val builtin = image("source-builtin-last-$fakeLock.img", signedElf(33, 0))
            val inspections = mutableListOf<File>(); val patches = mutableListOf<Pair<File, Boolean>>()
            val finalBytes = signedElf(9, 7, 8)
            val result = EfispArbFallback.prepareWith(initial, { current }, { builtin }, temporary.root, fakeLock, {},
                { file ->
                    check(!file.name.contains("builtin")); inspections += file
                    AblRollbackGuardPolicy.inspectReadable(file)
                },
                { source, destination, mode, _ ->
                    patches += source to mode
                    patchFixture(source, destination, mode, if (source == current) signedElf(0, 5) else finalBytes)
                })
            assertEquals(listOf(current to fakeLock, builtin to fakeLock), patches)
            assertEquals(2, inspections.size)
            assertEquals(EfispArbFallback.Source.BUILTIN_ABL, result.source)
            assertEquals("builtin-unchecked", result.arbBinding)
            assertNull(result.rollback)
            assertArrayEquals(finalBytes, result.file.readBytes())
            assertEquals(OtaPolicy.sha(builtin.readBytes()), result.sourceSha256)
        }
    }

    @Test fun realFileReadFailuresAndPatchFailuresStopWithoutBecomingNoCounter() {
        val badInputs = listOf(File(temporary.root, "missing.img"), image("empty.img", byteArrayOf()), temporary.newFolder("directory"))
        badInputs.forEach { input ->
            assertThrows(IllegalStateException::class.java) {
                EfispArbFallback.prepareWith(input, { error("current source must stay unused") },
                    { error("builtin source must stay unused") }, temporary.root, true, {},
                    AblRollbackGuardPolicy::inspectReadable, { _, _, _, _ -> error("patch must stay unused") })
            }
        }
        val initial = image("selected-readable.img", signedElf(1, 0))
        val current = image("current-readable.img", signedElf(0, 0))
        assertThrows(IllegalStateException::class.java) {
            EfispArbFallback.prepareWith(initial, { current }, { error("builtin source must stay unused") },
                temporary.root, false, {}, AblRollbackGuardPolicy::inspectReadable,
                { source, destination, mode, _ ->
                    val result = patchFixture(source, destination, mode, noCounterPe())
                    check(destination.delete()); result
                })
        }
        assertThrows(IOException::class.java) {
            EfispArbFallback.prepareWith(initial, { current }, { error("builtin source must stay unused") },
                temporary.root, true, {}, AblRollbackGuardPolicy::inspectReadable,
                { _, _, _, _ -> throw IOException("fixture output failed") })
        }
    }

    @Test fun existingEfispAndOperationScopePoliciesStillGateTheOtaDecisionChain() {
        OtaOperation.entries.forEach {
            assertThrows(IllegalStateException::class.java) { OtaOperationPolicy.requireEfisp(false) }
        }
        OtaOperationPolicy.requireEfisp(true)
        val standard = OtaRequest().withPreset(OtaPreset.STANDARD)
        val experimental = OtaRequest().withPreset(OtaPreset.EXPERIMENTAL)
        assertEquals(OtaEfispSource.CURRENT_SLOT_ABL, standard.efispSource)
        assertEquals(OtaEfispSource.OTHER_SLOT_ABL, experimental.efispSource)
        for (request in listOf(standard, experimental)) {
            assertEquals(OtaAblSource.BUILTIN, request.ablSource)
            OtaOperationPolicy.validateRequest(OtaOperation.FULL, request.efispSource)
            OtaOperationPolicy.validateWriteTargets(OtaOperation.FULL, listOf("xbl_b", "abl_b", "efisp"))
            assertEquals(EfispPatchMode.FAKE_LOCK, request.patchMode)
        }
        OtaOperationPolicy.validateRequest(OtaOperation.EFISP_ONLY, OtaEfispSource.CURRENT_SLOT_ABL)
        OtaOperationPolicy.validateWriteTargets(OtaOperation.EFISP_ONLY, listOf("efisp"))
        assertThrows(IllegalStateException::class.java) {
            OtaOperationPolicy.validateWriteTargets(OtaOperation.EFISP_ONLY, listOf("abl_b", "efisp"))
        }
        OtaOperationPolicy.validateRequest(OtaOperation.EXPORT_EFISP, OtaEfispSource.CURRENT_SLOT_ABL)
        OtaOperationPolicy.validateWriteTargets(OtaOperation.EXPORT_EFISP, emptyList())
        assertThrows(IllegalStateException::class.java) {
            OtaOperationPolicy.validateWriteTargets(OtaOperation.EXPORT_EFISP, listOf("efisp"))
        }
        assertThrows(IllegalStateException::class.java) {
            OtaOperationPolicy.validateRequest(OtaOperation.EFISP_ONLY, OtaEfispSource.NONE)
        }
        assertThrows(IllegalStateException::class.java) {
            OtaOperationPolicy.validateRequest(OtaOperation.EXPORT_EFISP, OtaEfispSource.UPLOADED_EFISP)
        }
        assertFalse(OtaPolicy.bootRoles.containsKey("abl"))
        assertFalse(OtaPolicy.referenceRoles.containsKey("abl"))
    }

    @Test fun actualArbResolutionCanFeedReadOnlyExportWithoutReplacingOriginalSnapshot() {
        val original = image("efisp.img", signedElf(1, 0))
        val originalBytes = original.readBytes()
        val current = image("current-export.img", signedElf(77, 0))
        val prepared = EfispArbFallback.prepareWith(original, { current }, { error("builtin source must stay unused") },
            temporary.root, false, {}, AblRollbackGuardPolicy::inspectReadable,
            { source, destination, mode, _ -> patchFixture(source, destination, mode, noCounterPe()) })
        OtaOperationPolicy.validateWriteTargets(OtaOperation.EXPORT_EFISP, emptyList())
        val exported = OtaExportArtifact.prepare(temporary.root, prepared.file)
        assertEquals(File(temporary.root, "export/efisp.img"), exported)
        assertArrayEquals(prepared.file.readBytes(), exported.readBytes())
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals(EfispArbFallback.Source.CURRENT_SLOT_ABL, prepared.source)
    }
}
