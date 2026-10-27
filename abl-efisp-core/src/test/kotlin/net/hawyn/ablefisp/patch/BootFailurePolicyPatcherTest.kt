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
            assertArrayEquals(unchanged, image.bytes)
        }
    }

    @Test fun trampolineGuardsBothPolicyAndVerificationResult() {
        val plan = BootFailurePolicyPatcher.Plan(0x1000, 0x2000, 21, 27)
        val edits = BootFailurePolicyPatcher.edits(plan, 0x3000)
        assertEquals(2, edits.size)
        val stub = edits[1].replacement
        assertEquals(20, stub.size)
        assertEquals(0x34000095, stub.word(0)) // CBZ W21, original path
        assertEquals(0x71000f7f, stub.word(4)) // CMP W27,3
        assertEquals(0x54000041, stub.word(8)) // B.NE original path
        assertEquals(0xd65f03c0.toInt(), stub.word(12))
        assertEquals(plan.originalTarget, A64.bTarget(0x3010, stub.word(16)))
        assertEquals(0x3000L, A64.blTarget(plan.callRva, edits[0].replacement.word(0)))
    }

    @Test fun patchedStubIsDecodedThenItsSourcesAreReproved() {
        val original = sample("analysis/ablnew-compat/ablnew-inner.pe")
        val plan = BootFailurePolicyPatcher.analyze(original).single()
        // This fixture uses zero test space near the section's end. Production
        // allocation belongs to the PE section allocator, never this test cave.
        val section = original.sections.first { it.executable }
        val stubRva = section.rva + section.rawSize - 0x100
        val candidate = original.bytes.copyOf()
        for (edit in BootFailurePolicyPatcher.edits(plan, stubRva)) {
            val at = requireNotNull(original.rvaToOffset(edit.rva, edit.replacement.size))
            edit.replacement.copyInto(candidate, at)
        }
        val found = BootFailurePolicyPatcher.analyze(PeImage.parse(candidate)).single()
        assertEquals(plan.callRva, found.callRva)
        assertEquals(plan.originalTarget, found.originalTarget)
        assertEquals(plan.allowRegister, found.allowRegister)
        assertEquals(plan.errorRegister, found.errorRegister)
        assertEquals(stubRva, found.existingStubRva)
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
