package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Firmware coordinates below are independent fixture oracles, not discovery rules. */
class SpecialSdamCompatibilityPatcherTest {
    private fun root(): File = requireNotNull(generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .take(10).firstOrNull { File(it, "analysis/bkq150-newabl-bootloop-20261102/fallback-audit/source-inner.pe").isFile })
    private fun read(path: String) = File(root(), path).readBytes()
    private fun source() = PeImage.parse(read("analysis/bkq150-newabl-bootloop-20261102/fallback-audit/source-inner.pe"))
    private fun replace(source: PeImage, changes: Map<Long, Int>): PeImage = PeImage.parse(source.bytes.copyOf().also { bytes ->
        changes.forEach { (pc, word) -> bytes.putWord(requireNotNull(source.rvaToOffset(pc, 4)), word) }
    })
    private fun apply(source: PeImage, plan: SemanticPlan): PeImage = PeImage.parse(source.bytes.copyOf().also { bytes ->
        plan.edits.forEach { it.replacement.copyInto(bytes, requireNotNull(source.rvaToOffset(it.rva, it.replacement.size))) }
    })
    private fun word(source: PeImage, pc: Long) = requireNotNull(source.word(pc))
    private fun mov(register: Int, immediate: Int) = 0x52800000 or (immediate shl 5) or register
    private fun skip(source: PeImage) {
        val saved = source.bytes.copyOf()
        val result = SpecialSdamCompatibilityPatcher.analyze(source)
        assertTrue(result.edits.isEmpty())
        assertTrue(result.notes.any { it.contains("已跳过") })
        assertArrayEquals(saved, source.bytes)
    }

    @Test fun sourceSelectsOnlyTheNativePreTransactionNoUpdateGate() {
        val image = source(); val saved = image.bytes.copyOf()
        val plan = SpecialSdamCompatibilityPatcher.analyze(image)
        val edit = plan.edits.single()
        assertEquals(0x8670cL, edit.rva)
        assertEquals(4, edit.replacement.size)
        assertEquals(0x86774L, A64.bTarget(edit.rva, edit.replacement.word(0)))
        assertTrue(plan.notes.any { it.contains("bank=18") && it.contains("offset=17") && it.contains("length=1") })
        assertTrue(plan.notes.any { it.contains("xvdd") && it.contains("只读") && it.contains("Rawdump") })
        val output = apply(image, plan)
        val at = requireNotNull(image.rvaToOffset(edit.rva, 4))
        assertTrue(saved.indices.all { saved[it] == output.bytes[it] || it in at until at + 4 })
        assertEquals(image.sections, output.sections)
        assertArrayEquals(saved, image.bytes)
        // Native output clear, common cleanup, ordinary VADJ operations and both
        // direct reset call paths keep their original instructions.
        for (pc in listOf(0x86700L, 0x86818, 0x86820, 0x86830, 0x8653c, 0x865c0,
            0xfdb4, 0x119a0, 0x119e0, 0x8693c, 0x45ab0)) assertEquals(image.word(pc), output.word(pc))
    }

    @Test fun theOwnedBranchIsReprovedAndIdempotent() {
        val image = source()
        val first = SpecialSdamCompatibilityPatcher.analyze(image)
        val output = apply(image, first)
        val repeated = SpecialSdamCompatibilityPatcher.analyze(output)
        assertEquals(first.edits.single().rva, repeated.edits.single().rva)
        assertArrayEquals(output.bytes, apply(output, repeated).bytes)
    }

    @Test fun olderAndUnrelatedFamiliesRemainOptionalWithoutInventingTheFeature() {
        for (path in listOf("analysis/builtin-vs-bkq-stock-20261030/builtin-inner.pe",
            "analysis/bkqan90-abl-patch-failure-20261102/fakebl138/input-inner.pe")) skip(PeImage.parse(read(path)))
    }

    @Test fun matchingParametersAreExtractedRatherThanFixedToOneBankOrOffset() {
        val image = source()
        val variant = replace(image, mapOf(
            0xb2fc0L to mov(0, 2), 0x867c8L to mov(0, 2),
            0xb2fc4L to mov(1, 19), 0x867ccL to mov(1, 19),
            0xb2fc8L to mov(2, 18), 0x867d4L to mov(2, 18),
        ))
        val plan = SpecialSdamCompatibilityPatcher.analyze(variant)
        assertEquals(0x8670cL, plan.edits.single().rva)
        assertTrue(plan.notes.any { it.contains("PMIC=2") && it.contains("bank=19") && it.contains("offset=18") })
    }

    @Test fun outlinedPreparationCanMoveAcrossAnIndependentProtocolLoad() {
        val image = source()
        val variant = replace(image, mapOf(
            0x8674cL to word(image, 0x86750),
            0x86750L to (A64.branch(0x86750, 0xb2fbc) or 0x80000000.toInt()),
        ))
        assertEquals(0x8670cL, SpecialSdamCompatibilityPatcher.analyze(variant).edits.single().rva)
    }

    @Test fun readAndWriteMethodTemporariesCanUseDifferentRegisters() {
        val image = source()
        fun destination(pc: Long, register: Int) = (word(image, pc) and -32) or register
        fun method(pc: Long, register: Int) = (word(image, pc) and 0x3ff.inv()) or (register shl 5) or register
        fun indirect(pc: Long, register: Int) = (word(image, pc) and 0x3e0.inv()) or (register shl 5)
        val variant = replace(image, mapOf(
            0x86750L to destination(0x86750, 11), 0x86754L to method(0x86754, 11), 0x86758L to indirect(0x86758, 11),
            0x867bcL to destination(0x867bc, 13), 0x867d8L to method(0x867d8, 13), 0x867ecL to indirect(0x867ec, 13),
        ))
        assertEquals(0x8670cL, SpecialSdamCompatibilityPatcher.analyze(variant).edits.single().rva)
    }

    @Test fun mismatchedBufferParametersFlagOrWriteFailureRemainNative() {
        val image = source()
        for (changes in listOf(
            mapOf(0x867ccL to mov(1, 19)), // read/write parameters differ
            mapOf(0x867c4L to 0x910017e4.toInt()), // write buffer SP+5 has no matching read
            mapOf(0x867e0L to mov(20, 0)), // update no longer produces true
            mapOf(0x86774L to 0xd2800020.toInt()), // proposed native no-update returns one
            mapOf(0x867f0L to (word(image, 0x867f0) xor 0x01000000)), // reversed EFI status outcome
            mapOf(0x45a60L to (word(image, 0x45a60) + 0x400),
                0x45aacL to (word(image, 0x45aac) + 0x400)), // no proved ResetSystem method remains
        )) skip(replace(image, changes))
    }

    @Test fun missingProtocolIdentityAndTwoCompleteGateCandidatesAreSkipped() {
        val image = source()
        val guid = image.bytes.copyOf()
        guid[requireNotNull(image.rvaToOffset(0x1b4f44))] = (guid[requireNotNull(image.rvaToOffset(0x1b4f44))].toInt() xor 1).toByte()
        skip(PeImage.parse(guid))
        val secondGate = 0x54000001 or ((((0x86774L - 0x86710) / 4).toInt() and 0x7ffff) shl 5)
        skip(replace(image, mapOf(0x86710L to secondGate)))
    }

    @Test fun shiftedHelperAddressAndScheduledGateKeepTheSameNativeContinuation() {
        val image = source()
        val moved = relocate(image, 0x866dc, 0x86838, 0x1a7800, 0x8670c)
        val delta = 0x1a7800L - 0x866dc
        val plan = SpecialSdamCompatibilityPatcher.analyze(moved)
        val edit = plan.edits.single()
        assertEquals(0x8670c + delta + 4, edit.rva)
        assertEquals(0x86774 + delta + 4, A64.bTarget(edit.rva, edit.replacement.word(0)))
    }

    @Test fun aNonAdjacentTstAndConditionalResetBranchStillProvesTheReturnValue() {
        val image = source()
        val moved = relocate(image, 0x86580, 0x865ec, 0x1a7c00, 0x865b8)
        assertEquals(0x8670cL, SpecialSdamCompatibilityPatcher.analyze(moved).edits.single().rva)
    }

    @Test fun existingProductReimportsThroughTheRealKblApi() {
        val old = read("analysis/bkq150-newabl-bootloop-20261102/fallback-audit/offline-233110-fake-lock-hnubl-2.8.8.img")
        val saved = old.copyOf()
        val result = AblEfisp.patchWithKbl(old, PatchMode.FREEDOM_FAKE_LOCK)
        assertEquals(0x8670cL, result.patches.single { it.name == "special SDAM 更新事务采用原生不更新路径" }.rva)
        val repeated = AblEfisp.patchWithKbl(result.output, PatchMode.FREEDOM_FAKE_LOCK)
        assertTrue(repeated.alreadyPatched)
        assertArrayEquals(result.output, repeated.output)
        assertArrayEquals(saved, old)
    }

    /** Relocate just a real feature helper into unused fixture RX space, updating
     * its actual PC-relative instructions and direct callers. An inserted NOP
     * separates a real flags producer from its consumer without inventing an ABI. */
    private fun relocate(image: PeImage, start: Long, end: Long, destination: Long, nopBefore: Long): PeImage {
        val output = image.bytes.copyOf()
        val size = (end - start + 8).toInt()
        val raw = requireNotNull(image.rvaToOffset(destination, size))
        assertTrue(output.copyOfRange(raw, raw + size).all { it == 0.toByte() })
        fun mapped(pc: Long): Long = if (pc in start..<end) destination + pc - start + if (pc >= nopBefore) 4 else 0 else pc
        fun encode(pc: Long, at: Long, word: Int): Int {
            A64.blTarget(pc, word)?.let { return A64.branch(at, mapped(it)) or 0x80000000.toInt() }
            A64.bTarget(pc, word)?.let { return A64.branch(at, mapped(it)) }
            A64.adrp(pc, word)?.let { (rd, address) ->
                val delta = (address - (at and -4096L)) shr 12
                return 0x90000000.toInt() or ((delta.toInt() and 3) shl 29) or
                    (((delta shr 2).toInt() and 0x7ffff) shl 5) or rd
            }
            A64.adr(pc, word)?.let { (rd, address) ->
                val delta = mapped(address) - at
                return 0x10000000 or ((delta.toInt() and 3) shl 29) or
                    (((delta shr 2).toInt() and 0x7ffff) shl 5) or rd
            }
            if (word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 == 0x34000000) {
                val target = pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
                return (word and 0x00ffffe0.inv()) or (((((mapped(target) - at) shr 2).toInt()) and 0x7ffff) shl 5)
            }
            if (word and 0x7e000000 == 0x36000000) {
                val target = pc + (signExtend(((word ushr 5) and 0x3fff).toLong(), 14) shl 2)
                return (word and 0x0007ffe0.inv()) or (((((mapped(target) - at) shr 2).toInt()) and 0x3fff) shl 5)
            }
            return word
        }
        for (pc in start..<end step 4) output.putWord(requireNotNull(image.rvaToOffset(mapped(pc), 4)), encode(pc, mapped(pc), word(image, pc)))
        output.putWord(requireNotNull(image.rvaToOffset(destination + nopBefore - start, 4)), 0xd503201f.toInt())
        output.putWord(requireNotNull(image.rvaToOffset(destination + end - start + 4, 4)), 0xd503233f.toInt())
        image.instructions().forEach { (pc, word) ->
            if (pc !in start..<end && A64.blTarget(pc, word) == start)
                output.putWord(requireNotNull(image.rvaToOffset(pc, 4)), A64.branch(pc, destination) or 0x80000000.toInt())
        }
        return PeImage.parse(output)
    }
}
