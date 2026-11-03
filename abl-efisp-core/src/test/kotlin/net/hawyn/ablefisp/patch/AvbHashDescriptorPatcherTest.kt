package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AvbHashDescriptorPatcherTest {
    private data class Fixture(val image: PeImage, val code: Int, val digest: Int, val loaded: Int, val length: Int, val result: Int, val comparisonBranch: Int, val messageRef: Int, val mismatchCall: Int)
    private fun mem(load: Boolean, width: Int, rt: Int, rn: Int, at: Int): Int =
        (if (width == 8) if (load) 0xf9400000.toInt() else 0xf9000000.toInt() else if (load) 0xb9400000.toInt() else 0xb9000000.toInt()) or
            ((at / width) shl 10) or (rn shl 5) or rt
    private fun mov(rd: Int, rn: Int) = 0xaa0003e0.toInt() or (rn shl 16) or rd
    private fun immediate(rd: Int, value: Int) = 0x52800000 or (value shl 5) or rd
    private fun cb(base: Int, pc: Int, target: Int, register: Int) = base or ((((target - pc) / 4) and 0x7ffff) shl 5) or register
    private fun fixture(code: Int = 0x1000, data: Int = 0x2000, length: Int = 27, result: Int = 26, scheduled: Boolean = false, copiedResult: Boolean = false, spilledResult: Boolean = false): Fixture {
        val b = ByteArray(data + 0x1000)
        fun half(at: Int, value: Int) { b[at] = value.toByte(); b[at + 1] = (value ushr 8).toByte() }
        b[0] = 77; b[1] = 90; b.putWord(0x3c, 0x80); b.putWord(0x80, 0x4550)
        half(0x84, 0xaa64); half(0x86, 1); half(0x94, 0xf0)
        half(0x98, 0x20b); b.putWord(0xa8, code); b.putWord(0xd0, b.size); b.putWord(0xd4, 0x1000)
        val section = 0x188
        ".text".toByteArray().copyInto(b, section)
        b.putWord(section + 8, b.size - 0x1000); b.putWord(section + 12, 0x1000)
        b.putWord(section + 16, b.size - 0x1000); b.putWord(section + 20, 0x1000); b.putWord(section + 36, 0x60000020)
        for (at in 0x1000 until b.size step 4) b.putWord(at, NOP)
        val digest = if (scheduled) 0x120 else 0x80; val loaded = digest + 0x10; val expected = digest + 0x20
        fun put(at: Int, word: Int) = b.putWord(code + at, word)
        fun branch(at: Int, to: Int) = put(at, A64.branch((code + at).toLong(), (code + to).toLong()))
        fun call(at: Int, to: Int) = put(at, A64.branch((code + at).toLong(), (code + to).toLong()) or 0x80000000.toInt())
        put(0, PAC); call(4, 0x5c0); put(8, mem(false, 8, 0, 31, digest)); put(12, immediate(length, 32)); branch(16, 0x40)
        put(0x40, mem(true, 4, 8, 31, expected)); put(0x44, cb(0x34000000, code + 0x44, code + 0x120, 8))
        put(0x48, 0xeb00001f.toInt() or (8 shl 16) or (length shl 5)); put(0x4c, cb(0x54000001, code + 0x4c, code + 0x140, 0))
        put(if (scheduled) 0x54 else 0x50, mem(true, 8, 0, 31, digest))
        put(if (scheduled) 0x58 else 0x54, mov(1, 29)); put(if (scheduled) 0x50 else 0x58, mov(2, length))
        call(0x5c, 0x500)
        val comparisonBranch = when { spilledResult -> 0x6c; copiedResult -> 0x68; else -> 0x60 }
        if (spilledResult) {
            put(0x60, mem(false, 4, 0, 31, 0x200)); put(0x64, mem(true, 4, 10, 31, 0x200))
        } else if (copiedResult) put(0x60, mov(10, 0))
        put(comparisonBranch, cb(0x34000000, code + comparisonBranch, code + 0x100, if (copiedResult || spilledResult) 10 else 0))
        val messageRef = comparisonBranch + 4; val mismatchCall = messageRef + 12
        val pageDelta = ((data.toLong() and -4096L) - ((code + messageRef).toLong() and -4096L)) shr 12
        put(messageRef, 0x90000006.toInt() or (((pageDelta and 3).toInt()) shl 29) or ((((pageDelta shr 2) and 0x7ffff).toInt()) shl 5))
        put(messageRef + 4, 0x910000c6.toInt() or ((data and 0xfff) shl 10)); put(messageRef + 8, 0xaa1f03e7.toInt()); call(mismatchCall, 0x580)
        put(mismatchCall + 4, immediate(result, 3)); branch(mismatchCall + 8, 0x160)
        put(if (scheduled) 0x104 else 0x100, mem(true, 8, 8, 31, loaded)); put(if (scheduled) 0x100 else 0x104, immediate(result, 0))
        put(0x108, cb(0xb5000000.toInt(), code + 0x108, code + 0x180, 8)); branch(0x10c, 0x160)
        branch(0x120, 0x160); put(0x140, immediate(result, 6)); branch(0x144, 0x160); put(0x160, RET)
        call(0x180, 0x600); put(0x184, mem(false, 8, 0, 29, 0x20)); put(0x188, mem(true, 8, 1, 31, digest)); put(0x18c, mov(2, length)); call(0x190, 0x640)
        put(0x194, mem(false, 8, length, 29, 0x28)); put(0x198, mem(true, 8, 9, 31, loaded)); put(0x19c, mem(false, 8, 9, 29, 8)); put(0x1a0, mem(false, 4, result, 29, 0x1c)); branch(0x1a4, 0x160)
        put(0x500, PAC); put(0x504, cb(0xb4000000.toInt(), code + 0x504, code + 0x538, 2)); put(0x508, immediate(8, 0))
        put(0x50c, 0x38401409); put(0x510, 0x3840142a); put(0x514, 0xd1000442.toInt()); put(0x518, 0x4a090149); put(0x51c, 0x2a090108)
        put(0x520, cb(0xb5000000.toInt(), code + 0x520, code + 0x50c, 2)); put(0x524, 0x7100011f); put(0x528, 0x1a9f07e0); branch(0x52c, 0x540); put(0x538, immediate(0, 0)); put(0x540, RET)
        for (offset in listOf(0x580, 0x5c0, 0x600, 0x640, 0x680)) { put(offset, PAC); put(offset + 4, RET) }
        ": Hash of data does not match digest in descriptor.\n\u0000".toByteArray().copyInto(b, data)
        return Fixture(PeImage.parse(b), code, digest, loaded, length, result, comparisonBranch, messageRef, mismatchCall)
    }

    private fun replace(f: Fixture, offset: Int, word: Int) = PeImage.parse(f.image.bytes.copyOf().also { it.putWord(f.code + offset, word) })

    @Test fun fixesOnlyTheComparisonResultAndReimportsWithoutTouchingNativeBranchOrLogs() {
        val f = fixture(); val before = f.image.bytes.copyOf(); val p = AvbHashDescriptorPatcher.analyze(f.image).single()
        assertEquals(0x105cL, p.compareCallRva); assertEquals(0x1100L, p.matchJoinRva); assertEquals(0x104cL, p.digestLengthGateRva); assertEquals(0x1044L, p.noDigestBranchRva)
        assertFalse(p.alreadyApplied); assertEquals(0, p.comparisonResultRegister); assertArrayEquals(before, f.image.bytes)
        val edits = AvbHashDescriptorPatcher.edits(p); assertEquals(1, edits.size); assertEquals(4, edits.single().replacement.size)
        assertEquals(immediate(0, 0), edits.single().replacement.word(0))
        val patched = apply(f.image, p).bytes
        val again = AvbHashDescriptorPatcher.analyze(PeImage.parse(patched)).single(); assertTrue(again.alreadyApplied); assertNull(again.compareHelperRva)
        assertEquals(p.matchJoinRva, again.matchJoinRva)
        assertTrue(before.indices.filter { before[it] != patched[it] }.all { it in 0x105c..0x105f })
    }

    @Test fun relocatedRegisterAllocatedAndScheduledCodeUsesTheSameDataRelations() {
        val f = fixture(0x3000, 0x7000, 21, 24, true); val p = AvbHashDescriptorPatcher.analyze(f.image).single()
        assertEquals(0x305cL, p.compareCallRva); assertEquals(0x3100L, p.matchJoinRva); assertEquals(21, p.digestLengthRegister); assertEquals(24, p.resultRegister); assertEquals(0x120, p.computedDigestStackOffset)
    }

    @Test fun reversedLoadedPointerConditionMustStillSendNonemptyDataToBookkeeping() {
        val f = fixture()
        assertThrows(PatchException::class.java) { AvbHashDescriptorPatcher.analyze(replace(f, 0x108, cb(0xb4000000.toInt(), 0x1108, 0x1180, 8))) }
    }

    @Test fun changedSuccessfulResultIsNotMistakenForOk() {
        val f = fixture()
        assertThrows(PatchException::class.java) { AvbHashDescriptorPatcher.analyze(replace(f, 0x104, immediate(f.result, 1))) }
    }

    @Test fun anUnrelatedByteHelperDoesNotQualifyAsDigestCompare() {
        val f = fixture()
        assertThrows(PatchException::class.java) { AvbHashDescriptorPatcher.analyze(replace(f, 0x518, NOP)) }
    }

    @Test fun wrongLengthGuardAndChangedProducerAreRejected() {
        val f = fixture()
        assertThrows(PatchException::class.java) { AvbHashDescriptorPatcher.analyze(replace(f, 0x4c, cb(0x54000000, 0x104c, 0x1140, 0))) }
        assertThrows(PatchException::class.java) { AvbHashDescriptorPatcher.analyze(replace(f, 8, mem(false, 8, 1, 31, f.digest))) }
        assertThrows(PatchException::class.java) { AvbHashDescriptorPatcher.analyze(replace(f, 0x58, immediate(f.length, 64))) }
    }

    @Test fun aMessageWithMissingFunctionalRelationStopsInsteadOfGuessing() {
        val f = fixture()
        assertThrows(PatchException::class.java) { AvbHashDescriptorPatcher.analyze(replace(f, 0x64, NOP)) }
    }

    @Test fun stackMovementAndPartialPointerStoresCannotPassThePreservedDigestProof() {
        val f = fixture()
        // Replace the producer's local B with a fallthrough operation. The
        // existing length, compare, message and output relations still exist.
        for (word in listOf(
            0x910043ff.toInt(), // ADD SP,SP,#16
            0xd10043ff.toInt(), // SUB SP,SP,#16
            0xf84107e9.toInt(), // LDR X9,[SP],#16
            0x39000000 or (f.digest shl 10) or (31 shl 5) or 9, // STRB W9,[SP,digest]
            0x79000000 or ((f.digest / 2) shl 10) or (31 shl 5) or 9, // STRH
            0x3d800000 or ((f.digest / 16) shl 10) or (31 shl 5), // STR Q0
        )) assertThrows(Integer.toHexString(word), PatchException::class.java) {
            AvbHashDescriptorPatcher.analyze(replace(f, 0x10, word))
        }
    }

    private fun apply(image: PeImage, plan: AvbHashDescriptorPatcher.Plan): PeImage = PeImage.parse(image.bytes.copyOf().also { bytes ->
        AvbHashDescriptorPatcher.edits(plan).forEach { edit ->
            edit.replacement.copyInto(bytes, requireNotNull(image.rvaToOffset(edit.rva, edit.replacement.size)))
        }
    })

    @Test fun legacyBranchToSuccessMigratesWithoutInventingItsLostCallee() {
        val f = fixture(); val original = AvbHashDescriptorPatcher.analyze(f.image).single()
        val legacy = replace(f, 0x5c, A64.branch(original.compareCallRva, original.matchJoinRva))
        val plan = AvbHashDescriptorPatcher.analyze(legacy).single()
        assertNull(plan.compareHelperRva); assertFalse(plan.alreadyApplied)
        assertEquals(original.copy(compareHelperRva = null), plan)
        val upgraded = apply(legacy, plan); val again = AvbHashDescriptorPatcher.analyze(upgraded).single()
        assertTrue(again.alreadyApplied); assertEquals(immediate(0, 0), upgraded.word(plan.compareCallRva))
        assertEquals(f.image.word(plan.comparisonBranchRva), upgraded.word(plan.comparisonBranchRva))
        assertArrayEquals(upgraded.bytes, apply(upgraded, again).bytes)
    }

    @Test fun registerCopiesAndLocalSpillsKeepTheNativeBooleanConsumer() {
        for (spilled in listOf(false, true)) {
            val f = fixture(copiedResult = !spilled, spilledResult = spilled)
            val plan = AvbHashDescriptorPatcher.analyze(f.image).single()
            assertEquals(10, plan.comparisonResultRegister)
            assertEquals((f.code + f.comparisonBranch).toLong(), plan.comparisonBranchRva)
            val after = apply(f.image, plan)
            assertEquals(immediate(0, 0), after.word(plan.compareCallRva))
            assertEquals(f.image.word(plan.comparisonBranchRva), after.word(plan.comparisonBranchRva))
            assertTrue(AvbHashDescriptorPatcher.analyze(after).single().alreadyApplied)
        }
    }

    @Test fun overwrittenResultWrongConsumerAndSideEntryDoNotProveAbiZero() {
        val f = fixture(copiedResult = true)
        for (changed in listOf(
            replace(f, 0x60, mov(10, 1)),
            replace(f, 0x64, immediate(10, 1)),
            replace(f, 0x120, A64.branch((f.code + 0x120).toLong(), (f.code + 0x64).toLong())),
        )) assertThrows(PatchException::class.java) { AvbHashDescriptorPatcher.analyze(changed) }
    }

    @Test fun callerResultZeroPreservesEveryNativeErrorAndLoggingInstruction() {
        val f = fixture(); val p = AvbHashDescriptorPatcher.analyze(f.image).single(); val after = apply(f.image, p)
        val changed = requireNotNull(f.image.rvaToOffset(p.compareCallRva, 4))
        f.image.bytes.indices.filter { it !in changed until changed + 4 }.forEach { at ->
            assertEquals("byte outside comparison slot at $at", f.image.bytes[at], after.bytes[at])
        }
        assertArrayEquals(f.image.bytes.copyOfRange(f.code + 0x500, f.code + 0x544), after.bytes.copyOfRange(f.code + 0x500, f.code + 0x544))
        assertEquals(f.image.word(p.digestLengthGateRva), after.word(p.digestLengthGateRva))
        assertEquals(f.image.word(p.noDigestBranchRva), after.word(p.noDigestBranchRva))
        // No partition name participates in this shared descriptor routine.
        // Its fixed ABI result selects the original success arm for every caller.
        for (partition in listOf("init_boot", "boot", "vendor_boot", "vendor_dlkm", "arbitrary_descriptor")) {
            val registers = LongArray(31) { 0x1234_5678_ffff_ffffL xor it.toLong() }
            val saved = registers.copyOf(); registers[0] = (requireNotNull(A64.movWImmediate(requireNotNull(after.word(p.compareCallRva))))).second.toLong()
            assertEquals("$partition native CBZ takes success", 0L, registers[p.comparisonResultRegister])
            assertArrayEquals(saved.copyOfRange(1, 31), registers.copyOfRange(1, 31))
        }
    }

    @Test fun gapsBeyondTheFormerInstructionWindowsRetainTheSameProof() {
        val f = fixture(); val source = f.image; val bytes = source.bytes.copyOf(); val delta = 0x240L
        val cut = (f.code + 0x5c).toLong(); val end = (f.code + 0x6c0).toLong()
        fun mapped(pc: Long) = if (pc in cut..<end) pc + delta else pc
        for (pc in cut..<end step 4) bytes.putWord(pc.toInt(), NOP)
        for (pc in f.code.toLong()..<end step 4) {
            val raw = requireNotNull(source.word(pc)); val destination = mapped(pc)
            val direct = A64.bTarget(pc, raw) ?: A64.blTarget(pc, raw)
            val conditional = raw and 0xff000010.toInt() == 0x54000000 || raw and 0x7e000000 == 0x34000000
            val moved = when {
                direct != null -> A64.branch(destination, mapped(direct)) or (raw and 0x80000000.toInt())
                conditional -> {
                    val target = pc + (signExtend(((raw ushr 5) and 0x7ffff).toLong(), 19) shl 2)
                    (raw and 0xff00001f.toInt()) or (((((mapped(target) - destination) shr 2).toInt()) and 0x7ffff) shl 5)
                }
                else -> raw
            }
            bytes.putWord(destination.toInt(), moved)
        }
        val widened = PeImage.parse(bytes); val plan = AvbHashDescriptorPatcher.analyze(widened).single()
        assertEquals(cut + delta, plan.compareCallRva); assertEquals(0x1100L + delta, plan.matchJoinRva)
        assertEquals(0x104cL, plan.digestLengthGateRva); assertEquals(0x1044L, plan.noDigestBranchRva)
        val after = apply(widened, plan)
        assertTrue(AvbHashDescriptorPatcher.analyze(after).single().alreadyApplied)
    }

    @Test fun unresolvedOuterDispatcherDoesNotEraseAProvenLocalDigestProducer() {
        val f = fixture(); val b = f.image.bytes.copyOf()
        fun put(at: Int, word: Int) = b.putWord(f.code + at, word)
        put(4, 0xd61f0120.toInt()) // BR X9: the outer switch target stays unresolved.
        for (at in listOf(8, 12, 16)) put(at, NOP)
        put(0x24, A64.branch(0x1024, 0x15c0) or 0x80000000.toInt())
        put(0x28, mem(false, 8, 0, 31, f.digest)); put(0x2c, immediate(f.length, 32))
        put(0x30, A64.branch(0x1030, 0x1040))
        val local = PeImage.parse(b); val plan = AvbHashDescriptorPatcher.analyze(local).single()
        assertEquals(0x105cL, plan.compareCallRva)
        assertTrue(AvbHashDescriptorPatcher.analyze(apply(local, plan)).single().alreadyApplied)
        val wrongStore = b.copyOf().also { it.putWord(0x1028, mem(false, 8, 1, 31, f.digest)) }
        assertThrows(PatchException::class.java) { AvbHashDescriptorPatcher.analyze(PeImage.parse(wrongStore)) }
        // A real function-entry edge that bypasses every producer remains invalid.
        val bypass = b.copyOf().also { it.putWord(0x1004, A64.branch(0x1004, 0x1040)) }
        assertThrows(PatchException::class.java) { AvbHashDescriptorPatcher.analyze(PeImage.parse(bypass)) }
    }

    @Test fun oldSyntheticImagesWithoutTheFeatureAreKept() {
        val f = fixture(); val b = f.image.bytes.copyOf(); b.fill(0, 0x2000, 0x2100)
        assertTrue(AvbHashDescriptorPatcher.analyze(PeImage.parse(b)).isEmpty())
    }

    @Test fun threeOriginalFamiliesAndBothApplicationModesUseTheNewPlan() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.take(7)
            .firstOrNull { File(it, "analysis/builtin-vs-bkq-stock-20261030/builtin-inner.pe").isFile }
        assumeTrue("Local original firmware fixtures", root != null)
        for (path in listOf("analysis/builtin-vs-bkq-stock-20261030/builtin-inner.pe", "analysis/builtin-vs-bkq-stock-20261030/bkq_stock-inner.pe", "analysis/abl-efisp-1.3-magic8-20261030/abl-stock-extracted.pe")) {
            val source = File(requireNotNull(root), path).readBytes(); val image = PeImage.parse(source); val plan = AvbHashDescriptorPatcher.analyze(image).single()
            for (mode in PatchMode.entries) {
                val result = AblEfisp.patch(source, mode); val after = PeImage.parse(result.output)
                assertEquals(immediate(0, 0), after.word(plan.compareCallRva))
                assertEquals(image.word(plan.comparisonBranchRva), after.word(plan.comparisonBranchRva))
                assertEquals(image.word(plan.mismatchLogCallRva), after.word(plan.mismatchLogCallRva))
                assertTrue(AvbHashDescriptorPatcher.analyze(after).single().alreadyApplied)
                assertTrue(result.patches.any { it.name.startsWith("AVB HashDescriptor") })
            }
        }
    }

    @Test fun completedSpecialSdamImageUpgradesThroughTheFormalApiAndReimports() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.take(7)
            .firstOrNull { File(it, "analysis/high-abl-low-system-20261102/sdam-candidate-v2/efisp.img").isFile }
        assumeTrue("Local retained 2.8.12 completed fixture", root != null)
        val source = File(requireNotNull(root), "analysis/high-abl-low-system-20261102/sdam-candidate-v2/efisp.img").readBytes()
        val before = PeImage.parse(source); val old = AvbHashDescriptorPatcher.analyze(before).single()
        assertFalse(old.alreadyApplied); assertNull(old.compareHelperRva)
        assertEquals(A64.branch(old.compareCallRva, old.matchJoinRva), before.word(old.compareCallRva))
        val result = AblEfisp.patchWithKbl(source, PatchMode.FREEDOM_FAKE_LOCK); val after = PeImage.parse(result.output)
        assertEquals(immediate(0, 0), after.word(old.compareCallRva))
        assertEquals(before.word(old.comparisonBranchRva), after.word(old.comparisonBranchRva))
        assertEquals(before.word(old.mismatchLogCallRva), after.word(old.mismatchLogCallRva))
        assertEquals(before.word(0x8670c), after.word(0x8670c)) // independent retained special-SDAM oracle
        val again = AblEfisp.patchWithKbl(result.output, PatchMode.FREEDOM_FAKE_LOCK)
        assertArrayEquals(result.output, again.output)
    }

    private companion object { const val PAC = 0xd503233f.toInt(); const val RET = 0xd65f03c0.toInt(); const val NOP = 0xd503201f.toInt() }
}
