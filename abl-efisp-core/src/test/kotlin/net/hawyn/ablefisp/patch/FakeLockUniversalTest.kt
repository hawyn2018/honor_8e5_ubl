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

    @Test fun existingNewAndOtaLayoutsUseTheSameDiscoveryRules() {
        val root = root()
        for (path in listOf("ablnew.img", "analysis/locked-boot-feasibility/fastboot-ota-abl.pe")) {
            val input = File(root, path).readBytes()
            val unchanged = input.copyOf()
            val freedom = AblEfisp.patch(input)
            val patched = AblEfisp.patch(input, PatchMode.FREEDOM_FAKE_LOCK)
            val coreCount = SemanticPatcher.plan(PeImage.parse(AblEfisp.extract(input).output)).size
            assertEquals(listOf(0x4999cL, 0x499fcL, 0x511bcL), patched.patches.drop(coreCount).take(3).map { it.rva })
            assertEquals(freedom.patches.take(coreCount), patched.patches.take(coreCount))
            freedom.patches.forEach { shared ->
                assertEquals(shared, patched.patches.single { it.name == shared.name && it.rva == shared.rva })
            }
            assertArrayEquals(unchanged, input)
            assertArrayEquals(patched.output, AblEfisp.patch(patched.output, PatchMode.FREEDOM_FAKE_LOCK).output)
            val image = PeImage.parse(patched.output)
            assertTrue(BootFailurePolicyPatcher.analyze(image).all { it.existingStubRva != null })
            assertEquals(PeImage.parse(freedom.output).word(0x47368), image.word(0x47368))
            assertEquals(PeImage.parse(freedom.output).word(0x49038), image.word(0x49038))
        }
    }

    @Test fun independentlyEmulatedEquivalentLayoutsAndWrongSources() {
        val manifest = File(root(), "analysis/fakebl-compat-universal/semantic-fixtures.json")
        val cases = Regex("""\{\s*"name"\s*:\s*"([^"]+)"([\s\S]*?)\n\s*}""").findAll(manifest.readText()).toList()
        assertTrue(cases.size >= 14)
        val failures = ArrayList<String>()
        for (case in cases) {
            val name = case.groupValues[1]
            val body = case.groupValues[2]
            val file = Regex(""""file"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]
            val expectation = Regex(""""expected"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]
            val input = File(manifest.parentFile, file).readBytes()
            val unchanged = input.copyOf()
            try {
                val patched = AblEfisp.patch(input, PatchMode.FREEDOM_FAKE_LOCK)
                if (expectation == "reject") failures += "$name: accepted a wrong source"
                else {
                    assertArrayEquals(patched.output, AblEfisp.patch(patched.output, PatchMode.FREEDOM_FAKE_LOCK).output)
                    assertArrayEquals(unchanged, input)
                }
            } catch (error: PatchException) {
                if (expectation != "reject") failures += "$name: ${error.message}"
                assertArrayEquals(unchanged, input)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
