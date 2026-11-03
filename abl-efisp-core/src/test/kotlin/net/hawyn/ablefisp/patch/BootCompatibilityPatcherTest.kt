package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Firmware addresses below are independent test oracles, never dispatch inputs. */
class BootCompatibilityPatcherTest {
    private data class Fixture(val file: String, val rollback: Long, val auth: Set<Long>)
    private val fixtures = listOf(
        Fixture("analysis/builtin-vs-bkq-stock-20261030/builtin-inner.pe", 0x84658, setOf(0x9f778, 0x9f78c)),
        Fixture("analysis/ablnew-compat/ablnew-inner.pe", 0x4de94, setOf(0x9b4f4, 0x9b508)),
        Fixture("analysis/efisp-version-policy-live-20261101/current-efisp.pe", 0x4de94, setOf(0x9b4f4, 0x9b508)),
    )
    private fun root(): File {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(8).firstOrNull { File(it, "analysis/ablnew-compat/ablnew-inner.pe").isFile }
        assumeTrue("Local firmware fixtures", root != null)
        return requireNotNull(root)
    }
    private fun source(fixture: Fixture): PeImage = PeImage.parse(File(root(), fixture.file).readBytes())
    private fun apply(image: PeImage, plan: SemanticPlan): PeImage {
        val bytes = image.bytes.copyOf()
        plan.edits.forEach { it.replacement.copyInto(bytes, requireNotNull(image.rvaToOffset(it.rva, it.replacement.size))) }
        return PeImage.parse(bytes)
    }
    @Test fun discoversIndependentPoliciesAndReimportsWithoutOtherByteChanges() {
        for (fixture in fixtures) {
            val original = source(fixture)
            val snapshot = original.bytes.copyOf()
            val plan = BootCompatibilityPatcher.analyze(original)
            val rollback = plan.edits.filter { it.name.startsWith("AVB 低索引") }
            assertEquals(fixture.file, setOf(fixture.rollback), rollback.map { it.rva }.toSet())
            val oem = plan.edits.filter { it.name.startsWith("OEM 版本") }
            assertEquals(fixture.file, 5, oem.size)
            val auth = plan.edits.filter { it.name.startsWith("BRINGUPAUTH") }
            assertEquals(fixture.file, fixture.auth, auth.map { it.rva }.toSet())
            val patched = apply(original, plan)
            assertArrayEquals(snapshot, original.bytes)
            val allowed = plan.edits.flatMap { edit ->
                val offset = requireNotNull(original.rvaToOffset(edit.rva, 4))
                (offset until offset + 4).toList()
            }.toSet()
            assertTrue(original.bytes.indices.all { it in allowed || original.bytes[it] == patched.bytes[it] })
            assertArrayEquals(patched.bytes, apply(patched, BootCompatibilityPatcher.analyze(patched)).bytes)
            assertEquals(original.entryRva, patched.entryRva)
            assertEquals(original.sections, patched.sections)
        }
    }
    @Test fun absentDiagnosticDoesNotAuthorizeAMatchingInstructionShape() {
        val original = source(fixtures[1])
        val bytes = original.bytes.copyOf()
        for (text in listOf("Image rollback index is less than the stored rollback index.",
            "not allow update CT01 or CR01 version:")) {
            bytes.occurrences(text.toByteArray()).forEach { bytes[it] = '!'.code.toByte() }
        }
        val plan = BootCompatibilityPatcher.analyze(PeImage.parse(bytes))
        assertFalse(plan.edits.any { it.name.startsWith("AVB 低索引") || it.name.startsWith("BRINGUPAUTH") })
        assertEquals(5, plan.edits.count { it.name.startsWith("OEM 版本") })
    }
    @Test fun wrongRollbackErrorCodeKeepsNativeDecision() {
        val original = source(fixtures[1])
        val bytes = original.bytes.copyOf()
        val error = 0x4deccL
        val old = requireNotNull(original.word(error))
        assertEquals(4, requireNotNull(A64.movWImmediate(old)).second)
        bytes.putWord(requireNotNull(original.rvaToOffset(error, 4)), (old and (0xffff shl 5).inv()) or (6 shl 5))
        val plan = BootCompatibilityPatcher.analyze(PeImage.parse(bytes))
        assertFalse(plan.edits.any { it.name.startsWith("AVB 低索引") })
    }
    @Test fun unrelatedNeedleAndTagDoNotRemoveAuthenticationBranches() {
        val original = source(fixtures[1])
        for (text in listOf("BRINGUPAUTH", "CT01", "CR01")) {
            val bytes = original.bytes.copyOf()
            bytes.occurrences((text + "\u0000").toByteArray()).forEach { bytes[it] = '!'.code.toByte() }
            assertFalse(BootCompatibilityPatcher.analyze(PeImage.parse(bytes)).edits.any { it.name.startsWith("BRINGUPAUTH") })
        }
    }
    @Test fun unsignedThresholdRegistersMayChangeWithoutAFirmwareTable() {
        val original = source(fixtures[1])
        val bytes = original.bytes.copyOf()
        // Only the comparison's operand registers change; policy discovery is
        // tied to its unsigned relationship and error consumer, not X8/X12.
        val compare = fixtures[1].rollback - 4
        val old = requireNotNull(original.word(compare))
        bytes.putWord(requireNotNull(original.rvaToOffset(compare, 4)),
            (old and ((31 shl 5) or (31 shl 16)).inv()) or (9 shl 5) or (14 shl 16))
        assertEquals(setOf(fixtures[1].rollback), BootCompatibilityPatcher.analyze(PeImage.parse(bytes)).edits
            .filter { it.name.startsWith("AVB 低索引") }.map { it.rva }.toSet())
    }
    @Test fun versionCounterRegisterRenamingAndChangedPenaltyPreserveTheSink() {
        val original = source(fixtures[1])
        val bytes = original.bytes.copyOf()
        val load = 0x49120L; val add = 0x49124L; val store = 0x49128L
        bytes.putWord(requireNotNull(original.rvaToOffset(load, 4)), requireNotNull(original.word(load)) and 31.inv() or 10)
        bytes.putWord(requireNotNull(original.rvaToOffset(add, 4)),
            (requireNotNull(original.word(add)) and ((31 shl 5) or 31 or (0xfff shl 10)).inv()) or (10 shl 5) or 10 or (19 shl 10))
        bytes.putWord(requireNotNull(original.rvaToOffset(store, 4)), requireNotNull(original.word(store)) and 31.inv() or 10)
        val changed = PeImage.parse(bytes)
        val edit = BootCompatibilityPatcher.analyze(changed).edits.single { it.rva == add }
        assertEquals(10, edit.replacement.word(0) and 31)
        assertEquals(10, (edit.replacement.word(0) ushr 5) and 31)
        assertEquals(0, (edit.replacement.word(0) ushr 10) and 0xfff)
    }
}
