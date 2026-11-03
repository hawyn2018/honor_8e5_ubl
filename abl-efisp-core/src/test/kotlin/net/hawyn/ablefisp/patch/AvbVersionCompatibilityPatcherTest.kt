package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Addresses are fixture oracles only: production matching receives no table. */
class AvbVersionCompatibilityPatcherTest {
    private data class Fixture(val file: String, val header: Long, val footer: Long)
    private val fixtures = listOf(
        Fixture("analysis/builtin-vs-bkq-stock-20261030/builtin-inner.pe", 0x84444, 0x89030),
        Fixture("analysis/ablnew-compat/ablnew-inner.pe", 0x4dc80, 0x52a20),
        Fixture("analysis/efisp-version-policy-live-20261101/current-efisp.pe", 0x4dc80, 0x52a20),
    )
    private fun root(): File {
        val result = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(8).firstOrNull { File(it, fixtures[1].file).isFile }
        assumeTrue("Local firmware fixtures", result != null)
        return requireNotNull(result)
    }
    private fun source(f: Fixture = fixtures[1]) = PeImage.parse(File(root(), f.file).readBytes())
    private fun apply(image: PeImage, plan: SemanticPlan): PeImage {
        val bytes = image.bytes.copyOf()
        plan.edits.forEach { it.replacement.copyInto(bytes, requireNotNull(image.rvaToOffset(it.rva, it.replacement.size))) }
        return PeImage.parse(bytes)
    }
    private fun mutate(image: PeImage, words: Map<Long, Int>): PeImage {
        val bytes = image.bytes.copyOf()
        words.forEach { (pc, raw) -> bytes.putWord(requireNotNull(image.rvaToOffset(pc, 4)), raw) }
        return PeImage.parse(bytes)
    }
    private fun word(image: PeImage, pc: Long) = requireNotNull(image.word(pc))
    private fun header(plan: SemanticPlan) = plan.edits.filter { "required major/minor" in it.name }
    private fun footer(plan: SemanticPlan) = plan.edits.filter { "Footer major" in it.name }

    @Test fun discoversBothNativePoliciesWithoutChangingParserOrMagicBytes() {
        for (fixture in fixtures) {
            val original = source(fixture); val snapshot = original.bytes.copyOf()
            val plan = AvbVersionCompatibilityPatcher.analyze(original)
            assertEquals(fixture.file, setOf(fixture.header), header(plan).map { it.rva }.toSet())
            assertEquals(fixture.file, setOf(fixture.footer), footer(plan).map { it.rva }.toSet())
            val patched = apply(original, plan)
            assertArrayEquals(snapshot, original.bytes)
            val allowed = plan.edits.flatMap { edit ->
                val at = requireNotNull(original.rvaToOffset(edit.rva, 4)); (at until at + 4).toList()
            }.toSet()
            assertTrue(original.bytes.indices.all { it in allowed || original.bytes[it] == patched.bytes[it] })
            assertEquals(original.entryRva, patched.entryRva)
            assertEquals(original.sections, patched.sections)
            val imported = AvbVersionCompatibilityPatcher.analyze(patched)
            assertEquals(2, imported.edits.size)
            assertArrayEquals(patched.bytes, apply(patched, imported).bytes)
        }
    }
    @Test fun changingBothDecodedFieldRegistersAndThresholdsKeepsDataFlowIdentity() {
        val image = source()
        val changed = mutate(image, mapOf(
            // Major assembled value W8 -> W16; minor W9 -> W17.
            0x4dc68L to ((word(image, 0x4dc68) and 31.inv()) or 16),
            0x4dc6cL to ((word(image, 0x4dc6c) and ((31 shl 5) or (0xfff shl 10)).inv()) or (16 shl 5) or (7 shl 10)),
            0x4dc78L to ((word(image, 0x4dc78) and 31.inv()) or 17),
            0x4dc7cL to ((word(image, 0x4dc7c) and ((31 shl 5) or (31 shl 16)).inv()) or (17 shl 5) or (21 shl 16)),
        ))
        assertEquals(setOf(fixtures[1].header), header(AvbVersionCompatibilityPatcher.analyze(changed)).map { it.rva }.toSet())
    }
    @Test fun ordinaryIndependentLoadsMayReorder() {
        val image = source()
        val changed = mutate(image, mapOf(0x4dc48L to word(image, 0x4dc4c), 0x4dc4cL to word(image, 0x4dc48)))
        assertEquals(1, header(AvbVersionCompatibilityPatcher.analyze(changed)).size)
    }
    @Test fun footerScalarLoadAndChangedRegisterRemainSupported() {
        val image = source()
        val changed = mutate(image, mapOf(
            0x52a14L to (0xb94003e0.toInt() or 17), // LDR W17,[SP]
            0x52a18L to 0xd503201f.toInt(),
            0x52a1cL to ((word(image, 0x52a1c) and ((31 shl 5) or (0xfff shl 10)).inv()) or (17 shl 5) or (9 shl 10)),
        ))
        assertEquals(1, footer(AvbVersionCompatibilityPatcher.analyze(changed)).size)
    }
    @Test fun scalarFieldsReversalAndCopyAliasesPreserveTheSameDecodedValues() {
        val image = source()
        val words = (0x4dc38L..0x4dc78L step 4).associateWith { 0xd503201f.toInt() }.toMutableMap()
        words[0x4dc38] = 0xb94003e0.toInt() or ((0x5ac / 4) shl 10) or 16
        words[0x4dc3c] = 0x5ac00800 or (16 shl 5) or 16 // REV W16,W16
        words[0x4dc40] = words.getValue(0x4dc3c) // second REV restores identical value
        words[0x4dc44] = 0x2a0003e0 or (16 shl 16) or 8 // MOV W8,W16
        words[0x4dc6c] = word(image, 0x4dc6c)
        words[0x4dc70] = 0xb94003e0.toInt() or ((0x5b0 / 4) shl 10) or 17
        words[0x4dc74] = 0x2a0003e0 or (17 shl 16) or 9
        assertEquals(1, header(AvbVersionCompatibilityPatcher.analyze(mutate(image, words))).size)
    }
    @Test fun floatingTransferDestinationMayChange() {
        val image = source()
        val changed = mutate(image, mapOf(
            0x52a18L to ((word(image, 0x52a18) and 31.inv()) or 17),
            0x52a1cL to ((word(image, 0x52a1c) and (31 shl 5).inv()) or (17 shl 5)),
        ))
        assertEquals(1, footer(AvbVersionCompatibilityPatcher.analyze(changed)).size)
    }
    @Test fun unknownVectorWriterDoesNotResurrectStaleFieldProvenance() {
        val image = source()
        // Make one instruction of space before the version gate, preserving
        // a real CMP and the native return targets. Without killing V0's old
        // provenance the EOR would incorrectly look like the loaded field.
        fun withMiddle(middle: Int) = mutate(image, mapOf(
            0x52a18L to middle,
            0x52a1cL to word(image, 0x52a18),
            0x52a20L to word(image, 0x52a1c),
            0x52a24L to (0x54000003 or (((0x52a80 - 0x52a24) / 4) shl 5)),
        ))
        // Control proves the shifted skeleton and extra NOP still match.
        assertEquals(1, footer(AvbVersionCompatibilityPatcher.analyze(withMiddle(0xd503201f.toInt()))).size)
        val changed = withMiddle(0x6e201c00) // EOR V0.16B,V0.16B,V0.16B
        assertTrue(footer(AvbVersionCompatibilityPatcher.analyze(changed)).isEmpty())
    }
    @Test fun nonAdjacentMinorFieldDoesNotAuthorizeVersionBypass() {
        val image = source()
        val load = word(image, 0x4dc5c)
        val changed = mutate(image, mapOf(0x4dc5cL to ((load and (0xfff shl 10).inv()) or (0x5c3 shl 10))))
        val plan = AvbVersionCompatibilityPatcher.analyze(changed)
        assertTrue(header(plan).isEmpty())
        assertEquals(1, footer(plan).size)
    }
    @Test fun differentResultKindDoesNotBecomeAVersionPolicy() {
        val image = source()
        val raw = word(image, 0x4dcb4)
        val changed = mutate(image, mapOf(0x4dcb4L to ((raw and (0xffff shl 5).inv()) or (6 shl 5))))
        assertTrue(header(AvbVersionCompatibilityPatcher.analyze(changed)).isEmpty())
    }
    @Test fun footerStillRequiresNativeMagicEqualityAndFourByteLiteral() {
        val image = source()
        val reversed = mutate(image, mapOf(0x529e8L to (word(image, 0x529e8) xor 0x01000000)))
        assertTrue(footer(AvbVersionCompatibilityPatcher.analyze(reversed)).isEmpty())
        val bytes = image.bytes.copyOf()
        bytes.occurrences("AVBf\u0000".toByteArray()).forEach { bytes[it] = '!'.code.toByte() }
        assertTrue(footer(AvbVersionCompatibilityPatcher.analyze(PeImage.parse(bytes))).isEmpty())
    }
    @Test fun absentDiagnosticsDoNotAuthorizeVersionLookingComparisons() {
        val image = source(); val bytes = image.bytes.copyOf()
        for (s in listOf("Mismatch between image version and libavb version.", "No support for footer version."))
            bytes.occurrences(s.toByteArray()).forEach { bytes[it] = '!'.code.toByte() }
        assertTrue(AvbVersionCompatibilityPatcher.analyze(PeImage.parse(bytes)).edits.isEmpty())
    }
}
