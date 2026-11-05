package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Independent sample mutations exercise matcher invariants, not production address tables. */
class FlashingUnlockCompatibilityTest {
    private fun source(): PeImage {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(8).firstOrNull { File(it, "analysis/ablnew-compat/ablnew-inner.pe").isFile }
        assumeTrue("Local firmware integration fixture", root != null)
        return PeImage.parse(File(requireNotNull(root), "analysis/ablnew-compat/ablnew-inner.pe").readBytes())
    }

    private fun changed(source: PeImage, edits: Map<Long, Int>): PeImage {
        val copy = source.bytes.copyOf()
        edits.forEach { (rva, value) -> copy.putWord(requireNotNull(source.rvaToOffset(rva, 4)), value) }
        return PeImage.parse(copy)
    }

    private fun branch(pc: Long, target: Long): Int =
        0x14000000 or (((target - pc) / 4).toInt() and 0x03ffffff)

    private fun rejectUnchanged(input: PeImage, throughDefaultApi: Boolean = false,
                                preservedOptionalRanges: List<LongRange> = emptyList()) {
        val snapshot = input.bytes.copyOf()
        val failure = assertThrows(PatchException::class.java) {
            FlashingUnlockPatcher.analyze(input)
        }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("flashing unlock"))
        assertArrayEquals("Rejected caller-owned input stays unchanged", snapshot, input.bytes)
        if (!throughDefaultApi) return

        val result = AblEfisp.patch(input.bytes, PatchMode.FREEDOM)
        assertArrayEquals("Optional skip still leaves caller-owned input unchanged", snapshot, input.bytes)
        assertTrue(result.notes.joinToString("\n"), result.notes.any {
            it.contains("已跳过可选功能「flashing unlock 增强」") && it.contains("保留该项原有代码")
        })
        assertTrue("A skipped/unproved adapter is not newly repaired or enabled", result.patches.none {
            it.name.contains("flashing unlock", ignoreCase = true) || it.name.contains("oem superunlock", ignoreCase = true)
        })
        val after = PeImage.parse(result.output)
        assertFalse("The default API cases must protect their real optional bytes", preservedOptionalRanges.isEmpty())
        for (range in preservedOptionalRanges) {
            val size = (range.last - range.first + 1).toInt()
            val beforeAt = requireNotNull(input.rvaToOffset(range.first, size))
            val afterAt = requireNotNull(after.rvaToOffset(range.first, size))
            assertArrayEquals("Skipped optional bytes remain as supplied, including tampering",
                snapshot.copyOfRange(beforeAt, beforeAt + size), result.output.copyOfRange(afterAt, afterAt + size))
        }
        for (name in listOf("OEMINFO 读取结果分支", "rdmode 日志循环分支", "rdmode 验证结果分支")) {
            val patch = result.patches.single { it.name == name }
            val expected = patch.after.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val at = requireNotNull(after.rvaToOffset(patch.rva, expected.size))
            assertArrayEquals("Required core patch still applies: $name", expected,
                result.output.copyOfRange(at, at + expected.size))
        }
    }

    @Test fun rejectsWrongWrapperConstantsAndAnUnregisteredUnlockPointer() {
        val source = source()
        assertEquals(0x52800020, source.word(0x1f57c)) // unlock's original requested=1
        assertEquals(0x2a1f03e0, source.word(0x1f5a8)) // lock's original requested=0
        val originalPlan = requireNotNull(FlashingUnlockPatcher.analyze(source))
        val nativeOptional = listOf(0x1f57cL, originalPlan.frontGateRva,
            originalPlan.abilityGateRva, originalPlan.transactionGateRva).map { it..it + 3 }
        rejectUnchanged(changed(source, mapOf(0x1f57cL to 0x2a1f03e0)), throughDefaultApi = true,
            preservedOptionalRanges = nativeOptional)
        rejectUnchanged(changed(source, mapOf(0x1f5a8L to 0x52800020)))
        // The string still exists, but its registration no longer points at it.
        // A self-pointer to the table is neither an unlock command nor code.
        rejectUnchanged(changed(source, mapOf(0x1a83d8L to 0x1a83d8)))
    }

    @Test fun rejectsEntriesThatBypassTheWrapperCurrentOrAbilityDecision() {
        val source = source()
        val variants = listOf(
            // The original wrapper constant remains in the bytes, but this new
            // edge reaches the tail branch without executing the assignment.
            mapOf(0x1f574L to branch(0x1f574, 0x1f588)),
            // Preserve CMP/CSEL/branch bytes while making their preparation path unreachable.
            mapOf(0x20d74L to branch(0x20d74, 0x20d84)),
            // The original TST still exists. An already-state arm now enters
            // ability preparation without going through the requested test.
            mapOf(0x20d88L to branch(0x20d88, 0x20df4)),
        )
        for (edits in variants) rejectUnchanged(changed(source, edits))
    }

    @Test fun acceptsIndependentSchedulingAndProtocolMethodTemporaryRename() {
        val source = source()
        val original = requireNotNull(FlashingUnlockPatcher.analyze(source))
        val variants = listOf(
            // MOV requested and ADD SP commute; both remain before AUTIASP.
            // LDR LR has already executed, and this does not change any branch target.
            mapOf(0x1f57cL to requireNotNull(source.word(0x1f580)),
                0x1f580L to requireNotNull(source.word(0x1f57c))),
            // X9 is a caller-saved temporary here. The following canary helper
            // redefines X9 before using it, so renaming the LDR+BLR pair is exact.
            mapOf(0xd0a0L to 0xf9400409.toInt(), 0xd0a4L to 0xd63f0120.toInt()),
        )
        for (edits in variants) {
            val candidate = changed(source, edits)
            val snapshot = candidate.bytes.copyOf()
            assertEquals("Same function/argument/field identities after equivalent code changes",
                original, requireNotNull(FlashingUnlockPatcher.analyze(candidate)))
            assertArrayEquals(snapshot, candidate.bytes)
        }
    }

    @Test fun rejectsAnEpilogueThatOverwritesTheRealBackendStatus() {
        val source = source()
        // The original MOV X0,Xstatus remains and a RET still follows. Loading
        // a saved value into X0 afterwards would nevertheless replace the result.
        val originalRestore = requireNotNull(source.word(0x22104))
        assertEquals(20, originalRestore and 31)
        rejectUnchanged(changed(source, mapOf(0x22104L to (originalRestore and 31.inv()))))
    }

    @Test fun rejectsTamperedFinishedAdaptersInsteadOfRepairingTheirClaims() {
        val source = source()
        val completed = AblEfisp.patch(source.bytes, PatchMode.FREEDOM)
        // Isolate flashing's own malformed adapter contract. Changing an inner
        // native byte under VM's immutable receipt is separately a whole-layer
        // integrity error, which the default API must reject before matching.
        val withoutVm = VmDtboCompatPatcher.remove(completed.output)
        val image = PeImage.parse(withoutVm)
        val plan = requireNotNull(FlashingUnlockPatcher.analyze(image))
        val front = requireNotNull(plan.existingFrontStubRva)
        val transaction = requireNotNull(plan.existingTransactionStubRva)
        assertEquals(0x52800020, image.word(transaction + 20))
        assertEquals(0x2a1f03e0, image.word(transaction + 48))
        val completedImage=PeImage.parse(completed.output)
        val tamperedWhole=changed(completedImage,mapOf(transaction + 48 to 0x52800020))
        val wholeSnapshot=tamperedWhole.bytes.copyOf()
        val receiptFailure=assertThrows(PatchException::class.java) {
            AblEfisp.patch(tamperedWhole.bytes,PatchMode.FREEDOM)
        }
        assertTrue(receiptFailure.message.orEmpty(),receiptFailure.message.orEmpty().contains("VM DTBO"))
        assertArrayEquals("Whole-layer rejection preserves the tampered caller-owned image",wholeSnapshot,tamperedWhole.bytes)
        assertArrayEquals("The untampered complete output remains idempotent",completed.output,
            AblEfisp.patch(completed.output,PatchMode.FREEDOM).output)
        // WRITE_CONFIG becoming READ would create a false commit. READ_CONFIG
        // becoming WRITE would issue a second write from the temporary buffer.
        rejectUnchanged(changed(image, mapOf(transaction + 20 to 0x2a1f03e0)))
        val existingOptional = completed.patches.filter {
            it.name.contains("flashing unlock", ignoreCase = true) || it.name.contains("oem superunlock", ignoreCase = true)
        }.map { it.rva..it.rva + it.after.replace(" ", "").length / 2 - 1 }
        // Keeping these tampered bytes means the optional enhancement was
        // skipped. It is not evidence that the old adapter was repaired/enabled.
        rejectUnchanged(changed(image, mapOf(transaction + 48 to 0x52800020)), throughDefaultApi = true,
            preservedOptionalRanges = existingOptional)
        // Exact reimport must reject reversed original lock/current semantics.
        val oldConditional = requireNotNull(image.word(front + 4))
        rejectUnchanged(changed(image, mapOf(front + 4 to (oldConditional xor 1))))
        assertArrayEquals(withoutVm, image.bytes)
    }
}
