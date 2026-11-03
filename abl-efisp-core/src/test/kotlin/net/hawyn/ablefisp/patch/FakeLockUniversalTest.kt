package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FakeLockUniversalTest {
    private fun root(): File {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "analysis/fakebl-compat-universal/semantic-fixtures.json").isFile }
        assumeTrue("Independent semantic fixture manifest", root != null)
        return root!!
    }

    /** Test-only classification of a changed reload source, never a changed
     * destination field or consumer. The old fixtures changed the unpatched
     * byte value; the current contract writes zero to the same proved field. */
    private fun onlyBooleanReloadSourceChanged(source: PeImage, candidate: PeImage, outgoing: Long): Boolean {
        if (source.bytes.size != candidate.bytes.size) return false
        val changedWords = source.bytes.indices.filter { source.bytes[it] != candidate.bytes[it] }
            .map { it and -4 }.toSet()
        val offset = changedWords.singleOrNull() ?: return false
        val pc = candidate.offsetToRva(offset) ?: return false
        if (source.offsetToRva(offset) != pc || pc + 4 != outgoing) return false
        val before = source.word(pc) ?: return false
        val after = candidate.word(pc) ?: return false
        // Only the immediate of the immediately consumed LDR Wn,[SP,#slot]
        // changes. Opcode, access width, base and destination register agree.
        if (before and 0xffc00000.toInt() != 0xb9400000.toInt() ||
            (before xor after) and 0x003ffc00.inv() != 0) return false
        val load = StateBranchEvaluator.access(after) ?: return false
        val byte = candidate.word(outgoing)?.let(StateBranchEvaluator::access) ?: return false
        return load.load && load.width == 4 && load.rn == 31 &&
            !byte.load && byte.width == 1 && byte.rn == 31 && byte.rt == load.rt &&
            source.word(outgoing) == candidate.word(outgoing)
    }

    private fun plan(image: PeImage): SemanticPlan = FakeLockPatcher.analyze(image,
        requireNotNull(SemanticPatcher.analyze(image).stateGlobal))

    private fun assertNativeReporting(image: PeImage) {
        val plans = BootFailurePolicyPatcher.analyze(image)
        assertTrue(plans.isNotEmpty())
        for (item in plans) {
            assertNull(item.existingStubRva)
            assertEquals(item.originalTarget, A64.blTarget(item.callRva, requireNotNull(image.word(item.callRva))))
            assertTrue(BootFailurePolicyPatcher.nativeReportingEdits(item).isEmpty())
        }
    }

    @Test fun existingNewAndOtaLayoutsUseTheSameDiscoveryRules() {
        val root = root()
        for (path in listOf("ablnew.img", "analysis/locked-boot-feasibility/fastboot-ota-abl.pe")) {
            val input = File(root, path).readBytes()
            val unchanged = input.copyOf()
            val freedom = legacyPatch(input)
            val patched = legacyPatch(input, PatchMode.FREEDOM_FAKE_LOCK)
            val coreCount = SemanticPatcher.plan(PeImage.parse(AblEfisp.extract(input).output)).size
            assertEquals(listOf(0x4999cL, 0x499fcL, 0x511bcL), patched.patches.drop(coreCount).take(3).map { it.rva })
            assertEquals(freedom.patches.take(coreCount), patched.patches.take(coreCount))
            freedom.patches.forEach { shared ->
                assertEquals(shared, patched.patches.single { it.name == shared.name && it.rva == shared.rva })
            }
            assertArrayEquals(unchanged, input)
            assertArrayEquals(patched.output, legacyPatch(patched.output, PatchMode.FREEDOM_FAKE_LOCK).output)
            val image = PeImage.parse(patched.output)
            assertNativeReporting(image)
            assertEquals(PeImage.parse(freedom.output).word(0x47368), image.word(0x47368))
            assertEquals(PeImage.parse(freedom.output).word(0x49038), image.word(0x49038))
        }
    }

    @Test fun independentlyEmulatedLayoutsRespectTheConsumerFieldContract() {
        val manifest = File(root(), "analysis/fakebl-compat-universal/semantic-fixtures.json")
        val cases = Regex("""\{\s*"name"\s*:\s*"([^"]+)"([\s\S]*?)\n\s*}""").findAll(manifest.readText()).toList()
        assertTrue(cases.size >= 14)
        val failures = ArrayList<String>()
        val acceptedSourceVariants = HashSet<String>()
        val retainedRejects = HashSet<String>()
        for (case in cases) {
            val name = case.groupValues[1]
            val body = case.groupValues[2]
            val file = Regex(""""file"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]
            val expectation = Regex(""""expected"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]
            val input = File(manifest.parentFile, file).readBytes()
            val unchanged = input.copyOf()
            val sourcePath = Regex(""""source"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]
            val outgoing = Regex(""""expected_unlocked_writer_rva"\s*:\s*"([^"]+)"""")
                .find(body)!!.groupValues[1].removePrefix("0x").toLong(16)
            val source = PeImage.parse(File(root(), sourcePath).readBytes())
            val candidate = PeImage.parse(input)
            val sourceVariant = expectation == "reject" && onlyBooleanReloadSourceChanged(source, candidate, outgoing)
            if (sourceVariant) {
                val baseline = plan(source)
                val actual = plan(candidate)
                assertEquals(name, baseline.normalBootState, actual.normalBootState)
                assertEquals(name, baseline.edits.map { Triple(it.name, it.rva, it.replacement.hex()) },
                    actual.edits.map { Triple(it.name, it.rva, it.replacement.hex()) })
                assertEquals(outgoing, actual.edits.single { it.name == "Keymaster 输入 unlocked 字节" }.rva)
                acceptedSourceVariants += name
            } else if (expectation == "reject") {
                assertThrows(name, PatchException::class.java) { plan(candidate) }
                retainedRejects += name
            }
            val acceptsConsumerContract = expectation != "reject" || sourceVariant
            try {
                val patched = legacyPatch(input, PatchMode.FREEDOM_FAKE_LOCK)
                if (!acceptsConsumerContract) failures += "$name: accepted an invalid consumption relation"
                else {
                    assertArrayEquals(patched.output, legacyPatch(patched.output, PatchMode.FREEDOM_FAKE_LOCK).output)
                    assertArrayEquals(unchanged, input)
                }
            } catch (error: PatchException) {
                if (acceptsConsumerContract) failures += "$name: ${error.message}"
                assertArrayEquals(unchanged, input)
            }
        }
        assertEquals(setOf("old-wrong-output-policy-field", "new-wrong-output-policy-field"), acceptedSourceVariants)
        assertEquals(setOf("old-overwritten-locked-pointer", "new-overwritten-locked-pointer"), retainedRejects)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
