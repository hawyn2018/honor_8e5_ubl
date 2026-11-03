package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class BootFailurePolicyPatcherTest {
    private fun sample(path: String): PeImage {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "ablnew.img").isFile }
        assumeTrue("Local firmware samples", root != null)
        val file = File(root, path)
        assumeTrue("Sample $path", file.isFile)
        return PeImage.parse(file.readBytes())
    }

    /** Independent historical-format fixture; never used by production. */
    private fun legacySuppressionFixture(image: PeImage, plan: BootFailurePolicyPatcher.Plan, stub: Long): ByteArray {
        val output = image.bytes.copyOf()
        val body = ByteArray(20)
        body.putWord(0, 0x34000080 or plan.allowRegister)
        body.putWord(4, 0x71000c1f or (plan.errorRegister shl 5))
        body.putWord(8, 0x54000041)
        body.putWord(12, 0xd65f03c0.toInt())
        body.putWord(16, A64.branch(stub + 16, plan.originalTarget))
        output.putWord(requireNotNull(image.rvaToOffset(plan.callRva, 4)),
            A64.branch(plan.callRva, stub) or 0x80000000.toInt())
        body.copyInto(output, requireNotNull(image.rvaToOffset(stub, body.size)))
        return output
    }

    @Test fun discoversOldNewAndOtaFromTheSameFunctionRelations() {
        for (path in listOf(
            "analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe",
            "analysis/ablnew-compat/ablnew-inner.pe",
            "analysis/locked-boot-feasibility/fastboot-ota-abl.pe",
        )) {
            val image = sample(path)
            val unchanged = image.bytes.copyOf()
            val plans = BootFailurePolicyPatcher.analyze(image)
            assertEquals(path, 1, plans.size)
            val plan = plans.single()
            assertNotEquals(plan.allowRegister, plan.errorRegister)
            assertNull(plan.existingStubRva)
            assertTrue(BootFailurePolicyPatcher.nativeReportingEdits(plan).isEmpty())
            assertEquals(plan.originalTarget, A64.blTarget(plan.callRva, requireNotNull(image.word(plan.callRva))))
            assertArrayEquals(unchanged, image.bytes)
        }
    }

    @Test fun legacyRestorationEmitsOnlyTheNativeCallAndNoNewStub() {
        val fresh = BootFailurePolicyPatcher.Plan(0x1000, 0x2000, 21, 27)
        assertTrue(BootFailurePolicyPatcher.nativeReportingEdits(fresh).isEmpty())
        val legacy = fresh.copy(existingStubRva = 0x3000)
        val edit = BootFailurePolicyPatcher.nativeReportingEdits(legacy).single()
        assertEquals("恢复原生 AVB hash 故障上报调用", edit.name)
        assertEquals(legacy.callRva, edit.rva)
        assertEquals(4, edit.replacement.size)
        assertEquals(legacy.originalTarget, A64.blTarget(edit.rva, edit.replacement.word(0)))
        assertNotEquals(legacy.existingStubRva, A64.blTarget(edit.rva, edit.replacement.word(0)))
    }

    @Test fun legacyStubSourcesAreReprovedAndOnlyItsCallerIsRestored() {
        val original = sample("analysis/ablnew-compat/ablnew-inner.pe")
        val originalSaved = original.bytes.copyOf()
        val plan = BootFailurePolicyPatcher.analyze(original).single()
        // This test fixture supplies its own historical code. Production never
        // allocates or emits a report-suppression stub after this migration.
        val section = original.sections.first { it.executable }
        val stubRva = section.rva + section.rawSize - 0x100
        val candidate = legacySuppressionFixture(original, plan, stubRva)
        val candidateSaved = candidate.copyOf()
        val imported = PeImage.parse(candidate)
        val found = BootFailurePolicyPatcher.analyze(imported).single()
        assertEquals(plan.callRva, found.callRva)
        assertEquals(plan.originalTarget, found.originalTarget)
        assertEquals(plan.allowRegister, found.allowRegister)
        assertEquals(plan.errorRegister, found.errorRegister)
        assertEquals(stubRva, found.existingStubRva)
        val restored = candidate.copyOf()
        val edit = BootFailurePolicyPatcher.nativeReportingEdits(found).single()
        assertEquals(plan.callRva, edit.rva)
        edit.replacement.copyInto(restored, requireNotNull(imported.rvaToOffset(edit.rva, edit.replacement.size)))
        val expected = candidate.copyOf()
        val callOffset = requireNotNull(original.rvaToOffset(plan.callRva, 4))
        original.bytes.copyInto(expected, callOffset, callOffset, callOffset + 4)
        assertArrayEquals(expected, restored) // Every orphan stub/other byte is retained.
        val stubOffset = requireNotNull(original.rvaToOffset(stubRva, 20))
        assertArrayEquals(candidate.copyOfRange(stubOffset, stubOffset + 20), restored.copyOfRange(stubOffset, stubOffset + 20))
        val repeated = BootFailurePolicyPatcher.analyze(PeImage.parse(restored)).single()
        assertNull(repeated.existingStubRva)
        assertTrue(BootFailurePolicyPatcher.nativeReportingEdits(repeated).isEmpty())
        assertArrayEquals(candidateSaved, candidate)
        assertArrayEquals(originalSaved, original.bytes)
    }

    @Test fun alteredLegacyCodeDoesNotRestoreAnUnprovedTarget() {
        val original = sample("analysis/ablnew-compat/ablnew-inner.pe")
        val plan = BootFailurePolicyPatcher.analyze(original).single()
        val section = original.sections.first { it.executable }
        val stubRva = section.rva + section.rawSize - 0x100
        val candidate = legacySuppressionFixture(original, plan, stubRva)
        for (offset in listOf(8, 16)) {
            val altered = candidate.copyOf()
            val at = requireNotNull(original.rvaToOffset(stubRva + offset, 4))
            if (offset == 8) altered.putWord(at, 0x54000040) // Wrong historical condition.
            else altered.putWord(at, A64.branch(stubRva + 16, 0x14f4)) // Logger, not reporter.
            val saved = altered.copyOf()
            assertThrows(PatchException::class.java) { BootFailurePolicyPatcher.analyze(PeImage.parse(altered)) }
            assertArrayEquals(saved, altered)
        }
    }

    @Test fun aWrongPolicyBitIsRejectedInsteadOfGuessingTheRegister() {
        val original = sample("analysis/ablnew-compat/ablnew-inner.pe")
        val plan = BootFailurePolicyPatcher.analyze(original).single()
        val candidate = original.bytes.copyOf()
        // Change only the caller's AND #1 into AND #2. The dataflow continues to
        // the same argument, but it no longer means allow-verification-error.
        val at = requireNotNull(original.rvaToOffset(0x4c9b4, 4))
        candidate.putWord(at, (candidate.word(at) and 0x000003ff) or 0x121f0000)
        assertThrows(PatchException::class.java) { BootFailurePolicyPatcher.analyze(PeImage.parse(candidate)) }
        assertEquals(0x4ea30L, plan.callRva)
    }

    @Test fun unboundedOrOutOfFunctionSwitchTargetsAreRejected() {
        val original = sample("analysis/ablnew-compat/ablnew-inner.pe")
        val noBound = original.bytes.copyOf()
        noBound.putWord(requireNotNull(original.rvaToOffset(0x4dad4, 4)), 0xd503201f.toInt())
        assertThrows(PatchException::class.java) { BootFailurePolicyPatcher.analyze(PeImage.parse(noBound)) }
        val badEntry = original.bytes.copyOf()
        val entry = requireNotNull(original.rvaToOffset(0xb3fae, 2))
        badEntry[entry] = 0xff.toByte(); badEntry[entry + 1] = 0xff.toByte()
        assertThrows(PatchException::class.java) { BootFailurePolicyPatcher.analyze(PeImage.parse(badEntry)) }
    }

    @Test fun aConsumedReportReturnIsRejected() {
        val original = sample("analysis/ablnew-compat/ablnew-inner.pe")
        val candidate = original.bytes.copyOf()
        // Keep the post-report error range, but consume W0 before overwriting it.
        candidate.putWord(requireNotNull(original.rvaToOffset(0x4ea40, 4)), 0x2a0003e9)
        assertThrows(PatchException::class.java) { BootFailurePolicyPatcher.analyze(PeImage.parse(candidate)) }
    }

    @Test fun wordRangeCheckDoesNotProveAnUnconstrainedXIndex() {
        val original = sample("analysis/ablnew-compat/ablnew-inner.pe")
        val candidate = original.bytes.copyOf()
        // CMP W8 still bounds low32, but this X copy does not zero high32.
        candidate.putWord(requireNotNull(original.rvaToOffset(0x4dad0, 4)), 0xaa1403e8.toInt())
        assertThrows(PatchException::class.java) { BootFailurePolicyPatcher.analyze(PeImage.parse(candidate)) }
    }

    @Test fun registerRenamingAndScheduledIdentityCopiesKeepTheirFunctionalMeaning() {
        for (name in listOf("allow_saved_pair_swap", "error_saved_pair_swap", "allow_and_error_pair_swap",
            "scheduled_allow_and_report_args", "allow_copy_add_zero")) {
            val image = sample("analysis/fakebl-repair-20261026/semantic-fixtures/$name.pe")
            assertEquals(name, 1, BootFailurePolicyPatcher.analyze(image).size)
        }
    }

    @Test fun unrelatedArgumentsWrongErrorDomainsAndLoggingDecoysAreRejected() {
        for (name in listOf("wrong_policy_bit", "unknown_allow_origin", "wrong_error_return_range", "hash_report_to_logging_function")) {
            val image = sample("analysis/fakebl-repair-20261026/semantic-fixtures/$name.pe")
            assertThrows(name, PatchException::class.java) { BootFailurePolicyPatcher.analyze(image) }
        }
    }
}
