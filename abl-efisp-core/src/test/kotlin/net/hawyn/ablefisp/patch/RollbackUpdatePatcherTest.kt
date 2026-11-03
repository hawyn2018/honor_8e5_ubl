package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Independent firmware coordinates are test oracles, never production dispatch tables. */
class RollbackUpdatePatcherTest {
    private data class Fixture(val name: String, val source: String, val gate: Long, val join: Long,
        val specialGate: Long? = null, val specialSkip: Long? = null)
    private val fixtures = listOf(
        Fixture("old", "abl.elf", 0x9b250, 0x9b770),
        Fixture("new", "ablnew.img", 0x47574, 0x47ab0, 0x8670c, 0x86774),
        Fixture("ota", "analysis/locked-boot-feasibility/fastboot-ota-abl.pe", 0x47574, 0x47ab0, 0x8670c, 0x86774),
    )

    private fun root(): File {
        val found = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "abl.elf").isFile }
        assumeTrue("Local firmware integration fixtures", found != null)
        return requireNotNull(found)
    }

    private fun read(path: String): ByteArray {
        val file = File(root(), path)
        assumeTrue("Local fixture $path", file.isFile)
        return file.readBytes()
    }

    private fun source(fixture: Fixture): PeImage = PeImage.parse(AblEfisp.extract(read(fixture.source)).output)
    private fun newImage(): PeImage = source(fixtures[1])
    private fun ByteArray.set(image: PeImage, rva: Long, word: Int) = putWord(requireNotNull(image.rvaToOffset(rva, 4)), word)
    private fun apply(image: PeImage, plan: SemanticPlan): PeImage {
        val output = image.bytes.copyOf()
        plan.edits.forEach { edit -> edit.replacement.copyInto(output, requireNotNull(image.rvaToOffset(edit.rva, edit.replacement.size))) }
        return PeImage.parse(output)
    }
    private fun changed(image: PeImage, edits: Map<Long, Int>): PeImage {
        val output = image.bytes.copyOf()
        edits.forEach { (pc, word) -> output.set(image, pc, word) }
        return PeImage.parse(output)
    }
    private fun eq(pc: Long, target: Long): Int = 0x54000000 or (((target - pc) / 4).toInt() shl 5)

    /** Independent historical allocation/native-call oracle, not the patcher under test. */
    private fun retireHistoricalReport(expected:ByteArray,baseline:PeImage,native:PeImage,fixture:Fixture,mode:PatchMode) {
        val call=if(fixture.name=="old")0x851f4L else 0x4ea30L
        val reporter=if(fixture.name=="old")0xadf9cL else 0xad554L
        val stub=when(fixture.name){"old"->0x1a7ba0L;"new"->0x1a7100L;"ota"->0x1a7140L;else->error("Unknown test fixture")}
        val selector=if(fixture.name=="old")0x9c954L else 0x48cd4L
        fun b(pc:Long,target:Long)=0x14000000 or (((target-pc)/4).toInt() and 0x3ffffff)
        val nativeCall=requireNotNull(native.word(call))
        assertEquals(0x94000000.toInt() or (b(call,reporter) and 0x03ffffff),nativeCall)
        assertEquals(0x94000000.toInt() or (b(call,stub) and 0x03ffffff),requireNotNull(baseline.word(call)))
        assertEquals(0x34000096,requireNotNull(baseline.word(stub)))
        assertEquals(0x71000f5f,requireNotNull(baseline.word(stub+4)))
        assertEquals(0x54000041,requireNotNull(baseline.word(stub+8)))
        assertEquals(0xd65f03c0.toInt(),requireNotNull(baseline.word(stub+12)))
        assertEquals(reporter,decodeBranch(stub+16,requireNotNull(baseline.word(stub+16))))
        val stubOffset=requireNotNull(native.rvaToOffset(stub,20))
        assertArrayEquals(ByteArray(20),native.bytes.copyOfRange(stubOffset,stubOffset+20))
        expected.set(baseline,call,nativeCall)
        native.bytes.copyInto(expected,requireNotNull(baseline.rvaToOffset(stub,20)),stubOffset,stubOffset+20)
        assertEquals(0x1a9f0508,requireNotNull(native.word(selector)))
        if(mode==PatchMode.FREEDOM){
            assertEquals(native.word(selector),baseline.word(selector))
            return
        }
        val oldLate=stub+32
        assertEquals(oldLate,decodeBranch(selector,requireNotNull(baseline.word(selector))))
        val oldLateOffset=requireNotNull(baseline.rvaToOffset(oldLate,20))
        val late=baseline.bytes.copyOfRange(oldLateOffset,oldLateOffset+20)
        assertEquals(0xb500007a.toInt(),late.word(0))
        assertEquals(0x1a9f0108,late.word(4))
        assertEquals(selector+4,decodeBranch(oldLate+8,late.word(8)))
        assertEquals(0x1a9f0508,late.word(12))
        assertEquals(selector+4,decodeBranch(oldLate+16,late.word(16)))
        val nativeOldLate=requireNotNull(native.rvaToOffset(oldLate,20))
        assertArrayEquals(ByteArray(20),native.bytes.copyOfRange(nativeOldLate,nativeOldLate+20))
        native.bytes.copyInto(expected,oldLateOffset,nativeOldLate,nativeOldLate+20)
        late.putWord(8,b(stub+8,selector+4));late.putWord(16,b(stub+16,selector+4))
        late.copyInto(expected,requireNotNull(baseline.rvaToOffset(stub,20)))
        expected.set(baseline,selector,b(selector,stub))
    }

    @Test fun discoversOnlyTheOriginalNoUpdateGateAndReimportsIt() {
        for (fixture in fixtures) {
            val input = source(fixture); val snapshot = input.bytes.copyOf()
            val plan = RollbackUpdatePatcher.analyze(input)
            val edit = plan.edits.single()
            assertEquals(fixture.gate, edit.rva)
            assertEquals(4, edit.replacement.size)
            assertEquals(fixture.join, decodeBranch(fixture.gate, edit.replacement.word(0)))
            val output = apply(input, plan)
            assertArrayEquals(snapshot, input.bytes)
            assertEquals(input.sections, output.sections)
            assertEquals(input.entryRva, output.entryRva)
            assertEquals(input.imageBase, output.imageBase)
            assertArrayEquals(input.bytes.copyOfRange(0, input.headersSize), output.bytes.copyOfRange(0, output.headersSize))
            val offset = requireNotNull(input.rvaToOffset(fixture.gate, 4))
            assertTrue(input.bytes.indices.all { input.bytes[it] == output.bytes[it] || it in offset until offset + 4 })
            val repeated = RollbackUpdatePatcher.analyze(output)
            assertEquals(fixture.gate, repeated.edits.single().rva)
            assertArrayEquals(output.bytes, apply(output, repeated).bytes)
        }
    }

    @Test fun bothModesChangeOnlyTheNewGateRelativeToTheirRetainedReleases() {
        for (fixture in fixtures) for (mode in PatchMode.entries) {
            val baselinePath = if (mode == PatchMode.FREEDOM)
                "analysis/freedom-original-vbmeta-20261026/outputs/${fixture.name}.freedom.pe"
            else "analysis/fakebl-failure2-20261026/outputs/${fixture.name}.fake.pe"
            val baseline = PeImage.parse(read(baselinePath))
            val expected = baseline.bytes.copyOf()
            // Fresh products no longer allocate the old report stub. Rebuild
            // only those exact historical spans and the displaced Late selector.
            retireHistoricalReport(expected,baseline,source(fixture),fixture,mode)
            expected.set(baseline, fixture.gate, 0x14000000 or (((fixture.join - fixture.gate) / 4).toInt() and 0x3ffffff))
            // Independent native-branch oracle: the new/OTA images also have
            // this special transaction; the old fixture has no such feature.
            fixture.specialGate?.let { gate ->
                assertEquals(0x54000341, baseline.word(gate))
                val target = requireNotNull(fixture.specialSkip)
                expected.set(baseline, gate, 0x14000000 or (((target - gate) / 4).toInt() and 0x3ffffff))
            }
            val original = read(fixture.source); val snapshot = original.copyOf()
            val result = legacyPatch(original, mode)
            val special = result.patches.filter { it.name == "special SDAM 更新事务采用原生不更新路径" }
            assertEquals(fixture.name, if (fixture.specialGate == null) 0 else 1, special.size)
            fixture.specialGate?.let { gate ->
                assertEquals(gate, special.single().rva)
                assertEquals(4, special.single().after.split(' ').size)
                assertEquals(requireNotNull(fixture.specialSkip), decodeBranch(gate,
                    requireNotNull(PeImage.parse(result.output).word(gate))))
            }
            assertArrayEquals("${fixture.name} $mode", expected, result.output)
            assertArrayEquals(snapshot, original)
            assertEquals(mode, result.mode)
            val repeated = legacyPatch(result.output, mode)
            assertTrue(repeated.alreadyPatched)
            assertArrayEquals(result.output, repeated.output)
        }
    }

    @Test fun rejectsMalformedCompanionsInsteadOfSilentlyKeepingOneRequest() {
        val source = newImage()
        val variants = listOf(
            // Preserve low command word; change only the high command halfword.
            mapOf(0x479e0L to (requireNotNull(source.word(0x479e0)) + 0x20)),
            mapOf(0x47a28L to (requireNotNull(source.word(0x47a28)) + 0x20)),
            // Same command constructor with a different protocol method.
            mapOf(0x47a30L to (requireNotNull(source.word(0x47a30)) + 0x400)),
            // Same method but a different response buffer.
            mapOf(0x47a24L to (requireNotNull(source.word(0x47a24)) + 0x2000)),
        )
        for (edits in variants) rejectUnchanged(changed(source, edits))
    }

    @Test fun rejectsWrongSkipTargetsAndDirectEntriesPastTheGate() {
        val source = newImage()
        rejectUnchanged(changed(source, mapOf(0x47574L to eq(0x47574, 0x47ab4))))
        rejectUnchanged(changed(source, mapOf(0x47574L to (0x14000000 or (((0x47ab4 - 0x47574) / 4) and 0x3ffffff)))))
        rejectUnchanged(changed(source, mapOf(0x47568L to (0x14000000 or (((0x479d0 - 0x47568) / 4) and 0x3ffffff)))))
        // Jump into the command prep, skipping its first MOVZ/stack definition.
        rejectUnchanged(changed(source, mapOf(0x47568L to (0x14000000 or (((0x479d8 - 0x47568) / 4) and 0x3ffffff)))))
        // Enter the TST immediately after the predicate call, skipping the call.
        rejectUnchanged(changed(source, mapOf(0x47568L to (0x14000000 or (((0x47570 - 0x47568) / 4) and 0x3ffffff)))))
    }

    @Test fun rejectsTruncatedProtocolPointersAndStackFrameChanges() {
        val source = newImage()
        // W0 load truncates the pointer which is subsequently used as X0.
        rejectUnchanged(changed(source, mapOf(0x479d0L to 0xb944f3e0.toInt())))
        // Replacing a pure preparation instruction with a frame mutation cannot
        // leave the remaining stack offsets proven to name the original frame.
        rejectUnchanged(changed(source, mapOf(0x479d8L to 0xd10043ff.toInt())))
        // A store in the prep could replace the protocol slot after its load.
        rejectUnchanged(changed(source, mapOf(0x479d8L to 0xf9027bff.toInt())))
    }

    @Test fun acceptsMethodRegisterRenamingAndIndependentScheduling() {
        val source = newImage()
        val renamed = changed(source, mapOf(
            0x479e8L to (requireNotNull(source.word(0x479e8)) and 31.inv() or 9),
            0x479ecL to (0xd63f0000.toInt() or (9 shl 5)),
            0x47a30L to (requireNotNull(source.word(0x47a30)) and 31.inv() or 10),
            0x47a34L to (0xd63f0000.toInt() or (10 shl 5)),
        ))
        assertEquals(0x47574L, RollbackUpdatePatcher.analyze(renamed).edits.single().rva)
        val scheduled = changed(source, mapOf(0x479d8L to requireNotNull(source.word(0x479e0)),
            0x479e0L to requireNotNull(source.word(0x479d8))))
        assertEquals(0x47574L, RollbackUpdatePatcher.analyze(scheduled).edits.single().rva)
    }

    @Test fun diagnosticTextIsNotRequiredWhenTheActualUpdateAbiIsProven() {
        val source = newImage(); val bytes = source.bytes.copyOf()
        for (marker in listOf("UpdateRollbackSyscall", "TZ_UPDATE_ROLLBACK_VERSION")) {
            val text = marker.toByteArray(Charsets.US_ASCII)
            bytes.occurrences(text).forEach { offset -> ByteArray(text.size) { 'x'.code.toByte() }.copyInto(bytes, offset) }
        }
        assertEquals(0x47574L, RollbackUpdatePatcher.analyze(PeImage.parse(bytes)).edits.single().rva)
    }

    @Test fun aFutureBuildCanHaveOnlyThePrimaryRequest() {
        val source = newImage(); val edits = linkedMapOf<Long, Int>()
        // Replace the entire legacy request with its original transport-error
        // route. No second command constructor or call remains in this fixture.
        for (pc in 0x47a18L until 0x47a38L step 4) edits[pc] = 0xd503201f.toInt()
        edits[0x47a18] = 0x14000000 or (((0x47a40 - 0x47a18) / 4) and 0x3ffffff)
        val result = RollbackUpdatePatcher.analyze(changed(source, edits))
        assertEquals(0x47574L, result.edits.single().rva)
    }

    @Test fun aJoinThatConsumesTheOldConditionFlagsIsNotRewritten() {
        val source = newImage()
        // CSEL X8,X8,X9,EQ at the common continuation would observe different
        // original condition flags when a true predicate is forced to skip.
        rejectUnchanged(changed(source, mapOf(0x47ab0L to 0x9a890108.toInt())))
        // CBZ X0 does not consume NZCV, but would still observe the retained
        // predicate return instead of the original update/no-update status.
        rejectUnchanged(changed(source, mapOf(0x47ab0L to 0xb4000040.toInt())))
    }

    private fun rejectUnchanged(source: PeImage) {
        val snapshot = source.bytes.copyOf()
        val failure = assertThrows(PatchException::class.java) { RollbackUpdatePatcher.analyze(source) }
        assertTrue(failure.kind in setOf(PatchFailureKind.INCOMPLETE_CORE_FEATURE, PatchFailureKind.AMBIGUOUS_CORE_FEATURE))
        assertArrayEquals(snapshot, source.bytes)
    }

    private fun decodeBranch(pc: Long, word: Int): Long {
        assertEquals(0x14000000, word and 0xfc000000.toInt())
        val immediate = (word and 0x3ffffff) shl 6 shr 6
        return pc + immediate.toLong() * 4
    }
}
