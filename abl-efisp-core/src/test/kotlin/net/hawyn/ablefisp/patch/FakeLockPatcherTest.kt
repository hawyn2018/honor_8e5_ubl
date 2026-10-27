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

    @Test fun newModeRetainsFreedomAndAddsNormalOutputsWithSelectiveRecovery() {
        val input = File(root(), "abl.elf").readBytes()
        val original = input.copyOf()
        val freedom = AblEfisp.patch(input)
        val result = AblEfisp.patch(input, PatchMode.FREEDOM_FAKE_LOCK)
        assertEquals("a886873536259fa340077b2e064ab229cac0d78e56aab871ea5b0572693d3eaf", result.outputSha256)
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
        assertTrue(recovery.all { it.existingStubRva != null })
        assertEquals(PeImage.parse(freedom.output).sections, pe.sections)
        assertEquals(freedom.output.size, result.output.size)
        for (item in recovery) {
            val section = pe.sections.single { item.callRva >= it.rva && item.callRva < it.rva + it.rawSize }
            assertTrue(section.executable)
            assertFalse(section.writable)
            assertTrue(requireNotNull(item.existingStubRva) in section.rva until section.rva + section.rawSize)
        }
        val rootPolicy = CheckRootPolicyPatcher.analyze(pe)
        assertTrue(rootPolicy.edits.isNotEmpty())
        assertTrue(rootPolicy.edits.all { pe.word(it.rva)!! and 31 == 31 })
    }

    @Test fun repeatedAndAlreadyFreedomInputsRemainIdempotent() {
        val input = File(root(), "abl.elf").readBytes()
        val once = AblEfisp.patch(input, PatchMode.FREEDOM_FAKE_LOCK)
        val twice = AblEfisp.patch(once.output, PatchMode.FREEDOM_FAKE_LOCK)
        assertTrue(twice.alreadyPatched)
        assertArrayEquals(once.output, twice.output)
        val legacyFreedom = File(root(), "analysis/fakebl-compat-universal/outputs/old.freedom.pe").readBytes()
        assertArrayEquals(once.output, AblEfisp.patch(legacyFreedom, PatchMode.FREEDOM_FAKE_LOCK).output)
        val freedom = AblEfisp.patch(input)
        val converted = AblEfisp.patch(freedom.output, PatchMode.FREEDOM_FAKE_LOCK)
        // Reusing an existing BFR adapter can place the later adapter at a
        // different aligned address. Check its behavior contract and stability.
        val pe = PeImage.parse(converted.output)
        assertEquals(0x52800008, pe.word(0x9cc70))
        assertEquals(0x390103ff, pe.word(0x9ce34))
        assertTrue(BootFailurePolicyPatcher.analyze(pe).all { it.existingStubRva != null })
        val facts = FakeLockPatcher.analyze(pe, requireNotNull(SemanticPatcher.analyze(pe).stateGlobal))
        val convertedLate = LateBootStatePatcher.analyze(pe, requireNotNull(facts.normalBootState)).single()
        val canonical = PeImage.parse(once.output)
        val canonicalFacts = FakeLockPatcher.analyze(canonical, requireNotNull(SemanticPatcher.analyze(canonical).stateGlobal))
        val canonicalLate = LateBootStatePatcher.analyze(canonical, requireNotNull(canonicalFacts.normalBootState)).single()
        val convertedStub = requireNotNull(convertedLate.existingStubRva)
        val canonicalStub = requireNotNull(canonicalLate.existingStubRva)
        assertEquals(canonicalLate.selectorRva, convertedLate.selectorRva)
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
        val repeatedConversion = AblEfisp.patch(converted.output, PatchMode.FREEDOM_FAKE_LOCK)
        assertTrue(repeatedConversion.alreadyPatched)
        assertArrayEquals(converted.output, repeatedConversion.output)
    }

    @Test fun inconsistentDownstreamInputFailsBeforeAnyCallerBytesChange() {
        val input = AblEfisp.extract(File(root(), "abl.elf").readBytes()).output
        val pe = PeImage.parse(input)
        input.putWord(requireNotNull(pe.rvaToOffset(0x9ce30, 4)), 0xb94033e8.toInt()) // Another stack field.
        val unchanged = input.copyOf()
        assertEquals(7, AblEfisp.patch(input).patches.size)
        val failure = assertThrows(PatchException::class.java) { AblEfisp.patch(input, PatchMode.FREEDOM_FAKE_LOCK) }
        assertEquals(PatchFailureKind.INCOMPLETE_CORE_FEATURE, failure.kind)
        assertArrayEquals(unchanged, input)
    }

    @Test fun rawSectionOffsetsAreMappedInsteadOfTreatedAsRvas() {
        val file = File(root(), "analysis/ablpatcher-fixtures/files/raw-offset-shift.fixture.pe")
        assumeTrue("Independent raw-offset relocation fixture", file.isFile)
        val result = AblEfisp.patch(file.readBytes(), PatchMode.FREEDOM_FAKE_LOCK)
        assertEquals(0x9dc70, result.patches.single { it.rva == 0x9cc70L }.fileOffset)
        assertTrue(AblEfisp.patch(result.output, PatchMode.FREEDOM_FAKE_LOCK).alreadyPatched)
    }

    @Test fun finalPolicyWriterIsPatchedInsteadOfAnEarlierCopy() {
        val input = AblEfisp.extract(File(root(), "abl.elf").readBytes()).output
        val pe = PeImage.parse(input)
        input.putWord(requireNotNull(pe.rvaToOffset(0x9d0c0, 4)), 0x390103f5) // STRB W21,[SP,#0x40]
        val result = AblEfisp.patch(input, PatchMode.FREEDOM_FAKE_LOCK)
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
            assertThrows(PatchException::class.java) { AblEfisp.patch(candidate, PatchMode.FREEDOM_FAKE_LOCK) }
        }
    }

    @Test fun loadFromTheOutgoingFieldIsNotAWrite() {
        val input = AblEfisp.extract(File(root(), "abl.elf").readBytes()).output
        val pe = PeImage.parse(input)
        input.putWord(requireNotNull(pe.rvaToOffset(0x9d0c0, 4)), 0xb84403e8.toInt()) // LDUR W8,[SP,#0x40]
        assertEquals(PatchMode.FREEDOM_FAKE_LOCK, AblEfisp.patch(input, PatchMode.FREEDOM_FAKE_LOCK).mode)
    }
}
