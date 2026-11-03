package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FakeLockPatcherTest {
    private fun root(): File {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "abl.elf").isFile }
        assumeTrue("Local firmware integration input", root != null)
        return root!!
    }

    private fun assertNativeReporting(image: PeImage) {
        val plans = BootFailurePolicyPatcher.analyze(image)
        assertTrue(plans.isNotEmpty())
        for (item in plans) {
            assertNull(item.existingStubRva)
            assertEquals(item.originalTarget, A64.blTarget(item.callRva, requireNotNull(image.word(item.callRva))))
            assertTrue(BootFailurePolicyPatcher.nativeReportingEdits(item).isEmpty())
        }
    }

    private fun historicalFake(): ByteArray {
        val file = File(root(), "analysis/rollback-update-20261027/outputs/old.fake.pe")
        assumeTrue("Independent historical fake-lock golden", file.isFile)
        return file.readBytes().also {
            assertEquals("a886873536259fa340077b2e064ab229cac0d78e56aab871ea5b0572693d3eaf", it.sha256())
        }
    }

    /** Exact native-byte oracle for this fixture; no new reporting or Late
     * patcher is called to construct the expected fresh output. */
    private fun nativeReportingGolden(): ByteArray {
        val source = PeImage.parse(AblEfisp.extract(File(root(), "abl.elf").readBytes()).output)
        assertEquals("00b12cd4179d907c2097b6942fcf47dc928372622d494166fcb340a8dc5f07bf", source.bytes.sha256())
        val historical = historicalFake();val image = PeImage.parse(historical)
        val reportCall = 0x851f4L;val retiredStub = 0x1a7ba0L
        val oldLate = 0x1a7bc0L;val selector = 0x9c954L
        assertEquals(0x9400a36a.toInt(), requireNotNull(source.word(reportCall)))
        assertEquals(0x94048a6b.toInt(), requireNotNull(image.word(reportCall)))
        assertEquals(0x34000096, requireNotNull(image.word(retiredStub)))
        assertEquals(0xb500007a.toInt(), requireNotNull(image.word(oldLate)))
        assertEquals(0x1a9f0508, requireNotNull(source.word(selector)))
        val expected = historical.copyOf()
        expected.putWord(requireNotNull(image.rvaToOffset(reportCall,4)), requireNotNull(source.word(reportCall)))
        for (rva in listOf(retiredStub, oldLate)) {
            val from = requireNotNull(source.rvaToOffset(rva,20));val to = requireNotNull(image.rvaToOffset(rva,20))
            assertTrue(source.bytes.copyOfRange(from,from+20).all { it == 0.toByte() })
            source.bytes.copyInto(expected,to,from,from+20)
        }
        val words = intArrayOf(0xb500007a.toInt(),0x1a9f0108,
            A64.branch(retiredStub+8,selector+4),0x1a9f0508,A64.branch(retiredStub+16,selector+4))
        words.forEachIndexed { n,w -> expected.putWord(requireNotNull(image.rvaToOffset(retiredStub+n*4L,4)),w) }
        expected.putWord(requireNotNull(image.rvaToOffset(selector,4)),A64.branch(selector,retiredStub))
        assertEquals("0204b6d3402a39ec8e94e0a5b8dba3f71660b57bb47125723dcf341631dfff39", expected.sha256())
        return expected
    }

    @Test fun newModeRetainsFreedomAndAddsNormalOutputsWithSelectiveRecovery() {
        val input = File(root(), "abl.elf").readBytes()
        val original = input.copyOf()
        val freedom = legacyPatch(input)
        val result = legacyPatch(input, PatchMode.FREEDOM_FAKE_LOCK)
        val expectedNative = nativeReportingGolden()
        assertArrayEquals(expectedNative,result.output)
        assertEquals(expectedNative.sha256(),result.outputSha256)
        val coreCount = SemanticPatcher.plan(PeImage.parse(AblEfisp.extract(input).output)).size
        assertEquals(freedom.patches.take(coreCount), result.patches.take(coreCount))
        freedom.patches.forEach { shared ->
            assertEquals(shared, result.patches.single { it.name == shared.name && it.rva == shared.rva })
        }
        assertEquals(listOf(0x9cc70L, 0x9ce34L, 0x87988L), result.patches.drop(coreCount).take(3).map { it.rva })
        assertEquals(PatchMode.FREEDOM_FAKE_LOCK, result.mode)
        assertArrayEquals(original, input)
        val changed = result.patches.filter { patch -> freedom.patches.none { it.name == patch.name && it.rva == patch.rva } }.flatMap {
            (it.fileOffset until it.fileOffset + it.after.split(' ').size).toList()
        }.toSet()
        assertTrue(freedom.output.indices.all { freedom.output[it] == result.output[it] || it in changed })
        // Preserve AVB policy and its spill; zero only the separate outgoing field.
        val pe = PeImage.parse(result.output)
        assertEquals(PeImage.parse(freedom.output).word(0x9b0b0), pe.word(0x9b0b0))
        assertEquals(PeImage.parse(freedom.output).word(0x9cc68), pe.word(0x9cc68))
        assertEquals(0x52800008, pe.word(0x9cc70))
        assertEquals(0x390103ff, pe.word(0x9ce34))
        val recovery = BootFailurePolicyPatcher.analyze(pe)
        assertTrue(recovery.isNotEmpty())
        assertNativeReporting(pe)
        assertEquals(PeImage.parse(freedom.output).sections, pe.sections)
        assertEquals(freedom.output.size, result.output.size)
        for (item in recovery) {
            val section = pe.sections.single { item.callRva >= it.rva && item.callRva < it.rva + it.rawSize }
            assertTrue(section.executable)
            assertFalse(section.writable)
            assertEquals(PeImage.parse(AblEfisp.extract(input).output).word(item.callRva),pe.word(item.callRva))
        }
        val rootPolicy = CheckRootPolicyPatcher.analyze(pe)
        assertTrue(rootPolicy.edits.isNotEmpty())
        assertTrue(rootPolicy.edits.all { pe.word(it.rva)!! and 31 == 31 })
    }

    @Test fun repeatedAndAlreadyFreedomInputsRemainIdempotent() {
        val input = File(root(), "abl.elf").readBytes()
        val once = legacyPatch(input, PatchMode.FREEDOM_FAKE_LOCK)
        val twice = legacyPatch(once.output, PatchMode.FREEDOM_FAKE_LOCK)
        assertTrue(twice.alreadyPatched)
        assertArrayEquals(once.output, twice.output)
        val legacyFreedom = File(root(), "analysis/fakebl-compat-universal/outputs/old.freedom.pe").readBytes()
        assertArrayEquals(once.output, legacyPatch(legacyFreedom, PatchMode.FREEDOM_FAKE_LOCK).output)
        val freedom = legacyPatch(input)
        val converted = legacyPatch(freedom.output, PatchMode.FREEDOM_FAKE_LOCK)
        // Reusing an existing BFR adapter can place the later adapter at a
        // different aligned address. Check its behavior contract and stability.
        val pe = PeImage.parse(converted.output)
        assertEquals(0x52800008, pe.word(0x9cc70))
        assertEquals(0x390103ff, pe.word(0x9ce34))
        assertNativeReporting(pe)
        val facts = FakeLockPatcher.analyze(pe, requireNotNull(SemanticPatcher.analyze(pe).stateGlobal))
        val convertedLate = LateBootStatePatcher.analyze(pe, requireNotNull(facts.normalBootState)).single()
        val canonical = PeImage.parse(once.output)
        val canonicalFacts = FakeLockPatcher.analyze(canonical, requireNotNull(SemanticPatcher.analyze(canonical).stateGlobal))
        val canonicalLate = LateBootStatePatcher.analyze(canonical, requireNotNull(canonicalFacts.normalBootState)).single()
        val convertedStub = requireNotNull(convertedLate.existingStubRva)
        val canonicalStub = requireNotNull(canonicalLate.existingStubRva)
        assertEquals(canonicalLate.selectorRva, convertedLate.selectorRva)
        // A real old finished image retains its now-unreferenced report stub
        // and its existing Late placement. Fresh canonical output need not have
        // those bytes; require the exact native-call migration and narrow bounds.
        val historical = historicalFake();val historicalSaved = historical.copyOf()
        val historicalImage = PeImage.parse(historical)
        val historicalReport = BootFailurePolicyPatcher.analyze(historicalImage).single()
        val orphan = requireNotNull(historicalReport.existingStubRva)
        val historicalExpected = historical.copyOf()
        val native = PeImage.parse(AblEfisp.extract(input).output)
        val callAt = requireNotNull(historicalImage.rvaToOffset(historicalReport.callRva,4))
        historicalExpected.putWord(callAt,requireNotNull(native.word(historicalReport.callRva)))
        val historicalUpgrade = legacyPatch(historical,PatchMode.FREEDOM_FAKE_LOCK)
        assertArrayEquals(historicalExpected,historicalUpgrade.output)
        val historicalUpgraded = PeImage.parse(historicalUpgrade.output)
        assertNativeReporting(historicalUpgraded)
        val historicalFacts = FakeLockPatcher.analyze(historicalUpgraded,
            requireNotNull(SemanticPatcher.analyze(historicalUpgraded).stateGlobal))
        val historicalLate = LateBootStatePatcher.analyze(historicalUpgraded,requireNotNull(historicalFacts.normalBootState)).single()
        val historicalStub = requireNotNull(historicalLate.existingStubRva)
        assertEquals(canonicalLate.selectorRva,historicalLate.selectorRva)
        for (offset in listOf(0,4,12)) assertEquals(canonical.word(canonicalStub+offset),historicalUpgraded.word(historicalStub+offset))
        for (offset in listOf(8,16)) assertEquals(historicalLate.selectorRva+4,
            A64.bTarget(historicalStub+offset,requireNotNull(historicalUpgraded.word(historicalStub+offset))))
        val orphanAt = requireNotNull(historicalImage.rvaToOffset(orphan,20))
        assertArrayEquals(historical.copyOfRange(orphanAt,orphanAt+20),historicalUpgrade.output.copyOfRange(orphanAt,orphanAt+20))
        val inheritedRanges = listOf(historicalLate.selectorRva to 4,orphan to 20,canonicalStub to 20,historicalStub to 20)
            .flatMap { (rva,size) -> val at=requireNotNull(canonical.rvaToOffset(rva,size));(at until at+size).toList() }.toSet()
        assertEquals(once.output.size,historicalUpgrade.output.size)
        assertTrue(once.output.indices.all { once.output[it]==historicalUpgrade.output[it] || it in inheritedRanges })
        val historicalRepeated = legacyPatch(historicalUpgrade.output,PatchMode.FREEDOM_FAKE_LOCK)
        assertTrue(historicalRepeated.alreadyPatched);assertArrayEquals(historicalUpgrade.output,historicalRepeated.output)
        assertArrayEquals(historicalSaved,historical)

        // The condition/selection instructions are identical; only the two
        // return B displacements change with the adapter's address.
        for (offset in listOf(0, 4, 12)) assertEquals(canonical.word(canonicalStub + offset), pe.word(convertedStub + offset))
        for (offset in listOf(8, 16)) assertEquals(convertedLate.selectorRva + 4,
            A64.bTarget(convertedStub + offset, requireNotNull(pe.word(convertedStub + offset))))
        val allowed = listOf(convertedLate.selectorRva to 4, canonicalStub to 20, convertedStub to 20).flatMap { (rva, size) ->
            val start = requireNotNull(pe.rvaToOffset(rva, size))
            (start until start + size).toList()
        }.toSet()
        assertEquals(once.output.size, converted.output.size)
        assertTrue(once.output.indices.all { once.output[it] == converted.output[it] || it in allowed })
        freedom.patches.forEach { shared ->
            val offset = requireNotNull(pe.rvaToOffset(shared.rva, shared.after.split(' ').size))
            assertEquals(shared.after, pe.bytes.copyOfRange(offset, offset + shared.after.split(' ').size).hex())
        }
        val repeatedConversion = legacyPatch(converted.output, PatchMode.FREEDOM_FAKE_LOCK)
        assertTrue(repeatedConversion.alreadyPatched)
        assertArrayEquals(converted.output, repeatedConversion.output)
    }

    @Test fun aDifferentBooleanSourceKeepsTheSameProvedDownstreamFields() {
        val input = AblEfisp.extract(File(root(), "abl.elf").readBytes()).output
        val source = PeImage.parse(input.copyOf())
        val global = requireNotNull(SemanticPatcher.analyze(source).stateGlobal)
        val baseline = FakeLockPatcher.analyze(source, global)
        input.putWord(requireNotNull(source.rvaToOffset(0x9ce30, 4)), 0xb94033e8.toInt()) // Another source slot, same output field.
        val unchanged = input.copyOf()
        val candidate = PeImage.parse(input)
        val plan = FakeLockPatcher.analyze(candidate, global)
        assertEquals(baseline.normalBootState, plan.normalBootState)
        assertEquals(baseline.edits.map { Triple(it.name, it.rva, it.replacement.hex()) },
            plan.edits.map { Triple(it.name, it.rva, it.replacement.hex()) })
        assertEquals(5, legacyPatch(input).patches.size)
        val result = legacyPatch(input, PatchMode.FREEDOM_FAKE_LOCK)
        val sink = result.patches.single { it.name == "Keymaster 输入 unlocked 字节" }
        assertEquals(0x9ce34L, sink.rva)
        val patched = PeImage.parse(result.output)
        assertEquals(0x390103ff, patched.word(sink.rva))
        assertEquals(candidate.word(0x9ce30), patched.word(0x9ce30))
        assertArrayEquals(unchanged, input)
    }

    @Test fun rawSectionOffsetsAreMappedInsteadOfTreatedAsRvas() {
        val file = File(root(), "analysis/ablpatcher-fixtures/files/raw-offset-shift.fixture.pe")
        assumeTrue("Independent raw-offset relocation fixture", file.isFile)
        val result = legacyPatch(file.readBytes(), PatchMode.FREEDOM_FAKE_LOCK)
        assertEquals(0x9dc70, result.patches.single { it.rva == 0x9cc70L }.fileOffset)
        assertTrue(legacyPatch(result.output, PatchMode.FREEDOM_FAKE_LOCK).alreadyPatched)
    }

    @Test fun finalPolicyWriterIsPatchedInsteadOfAnEarlierCopy() {
        val input = AblEfisp.extract(File(root(), "abl.elf").readBytes()).output
        val pe = PeImage.parse(input)
        input.putWord(requireNotNull(pe.rvaToOffset(0x9d0c0, 4)), 0x390103f5) // STRB W21,[SP,#0x40]
        val result = legacyPatch(input, PatchMode.FREEDOM_FAKE_LOCK)
        val sink = result.patches.single { it.name == "Keymaster 输入 unlocked 字节" }
        assertEquals(0x9d0c0L, sink.rva)
        assertEquals(0x390103ff, PeImage.parse(result.output).word(sink.rva))
        assertEquals(pe.word(0x9ce34), PeImage.parse(result.output).word(0x9ce34))
    }

    @Test fun stackAliasOverwriteAndFrameChangeAreRejected() {
        val source = AblEfisp.extract(File(root(), "abl.elf").readBytes()).output
        for (alias in listOf(false, true)) {
            val candidate = source.copyOf()
            val pe = PeImage.parse(candidate)
            fun set(pc: Long, word: Int) = candidate.putWord(requireNotNull(pe.rvaToOffset(pc, 4)), word)
            if (alias) {
                set(0x9d0b4, 0x910103e9.toInt()) // ADD X9,SP,#0x40
                set(0x9d0b8, 0xaa0903ea.toInt()) // MOV X10,X9
                set(0x9d0c0, 0x39000155) // STRB W21,[X10]
            } else set(0x9d0c0, 0x910043ff.toInt()) // ADD SP,SP,#0x10
            assertThrows(PatchException::class.java) { legacyPatch(candidate, PatchMode.FREEDOM_FAKE_LOCK) }
        }
    }

    @Test fun loadFromTheOutgoingFieldIsNotAWrite() {
        val input = AblEfisp.extract(File(root(), "abl.elf").readBytes()).output
        val pe = PeImage.parse(input)
        input.putWord(requireNotNull(pe.rvaToOffset(0x9d0c0, 4)), 0xb84403e8.toInt()) // LDUR W8,[SP,#0x40]
        assertEquals(PatchMode.FREEDOM_FAKE_LOCK, legacyPatch(input, PatchMode.FREEDOM_FAKE_LOCK).mode)
    }
}
