package dev.honor.bootforge.core

import net.hawyn.ablefisp.api.AblEfisp
import net.hawyn.ablefisp.io.ImageIo
import net.hawyn.ablefisp.patch.PatchMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Optional real-image integration cases; redistribution does not include the firmware fixtures. */
class EfispCompatibilityTest {
    @get:Rule val directory = TemporaryFolder()

    private fun fixture(relative: String): File {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .take(8).firstOrNull { File(it, relative).isFile }
        assumeTrue("Local firmware integration fixture: $relative", root != null)
        return File(checkNotNull(root), relative)
    }

    @Test fun updatedOppositeSlotImageSupportsBothModesAndReimport() {
        val source = fixture("analysis/custom-flash-error-v244-20261027/source-other-abl.img")
        val before = source.readBytes()
        for (fake in listOf(false, true)) {
            val mode = if (fake) PatchMode.FREEDOM_FAKE_LOCK else PatchMode.FREEDOM
            val core = AblEfisp.patchWithKbl(before, mode)
            val generated = File(directory.root, "generated-$fake.img")
            val logs = mutableListOf<String>()
            val result = EfispImagePatcher.patch(source, generated, fake, onLog = { logs += it })
            assertTrue(result.patchCount > 0)
            val bytes = generated.readBytes()
            val finalProof = AblEfisp.patchWithKbl(bytes, mode)
            assertTrue(finalProof.alreadyPatched)
            assertArrayEquals("Final nested Recovery/KBL ownership must be stable", bytes, finalProof.output)
            assertEquals(ImageIo.sha256(bytes), finalProof.sourceSha256)
            assertEquals(finalProof.sourceSha256, finalProof.outputSha256)
            assertEquals(finalProof.patches.size, result.patchCount)
            assertTrue(finalProof.patches.any { it.name.startsWith("KBL") })
            assertTrue(finalProof.patches.any { it.name == "EFISP 返回边界隔离" })
            for (patch in finalProof.patches) {
                val expected = patch.after.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                assertArrayEquals(patch.name, expected,
                    bytes.copyOfRange(patch.fileOffset, patch.fileOffset + expected.size))
            }
            val text = AblEfisp.scanText(bytes).entries.single { it.id == "fastboot-unlocked" }
            assertEquals(EfispImagePatcher.FASTBOOT_TEXT, text.text)
            assertFalse(text.text.contains('\n'))
            assertTrue(text.maxBytes >= text.text.toByteArray(Charsets.US_ASCII).size)
            assertTrue(logs.any { it.contains("修补完成") })
            assertFalse(logs.any { it.contains("修补失败") })

            val reimport = File(directory.root, "reimport-$fake.img")
            EfispImagePatcher.patch(generated, reimport, fake)
            assertArrayEquals("A completed image must remain stable on reimport", bytes, reimport.readBytes())

            val coreOnly = File(directory.root, "core-only-$fake.pe").apply { writeBytes(core.output) }
            val customizedAgain = File(directory.root, "core-reimport-$fake.img")
            EfispImagePatcher.patch(coreOnly, customizedAgain, fake)
            assertArrayEquals("Core-only products must receive identical customization", bytes, customizedAgain.readBytes())
        }
        assertArrayEquals("Reading and patching must not change the selected ABL", before, source.readBytes())
    }

    @Test fun previousSuccessfulFakeLockProductsReceiveCurrentFeaturesAndReimportStably() {
        val source = fixture("analysis/postfakebl-v244-20261027/source-abl-before.full.img")
        val previous = fixture("analysis/postfakebl-v244-20261027/actual-efisp.img")
        for ((index, input) in listOf(source, previous).withIndex()) {
            val original = input.readBytes()
            val generated = File(directory.root, "upgraded-$index.img")
            EfispImagePatcher.patch(input, generated, true)
            val bytes = generated.readBytes()
            val plan = AblEfisp.patchWithKbl(bytes, PatchMode.FREEDOM_FAKE_LOCK)
            assertTrue(plan.patches.any { it.name.startsWith("AVB HashDescriptor") })
            assertTrue(plan.patches.any { it.name.startsWith("KBL") })
            assertTrue(plan.patches.any { it.name == "EFISP 返回边界隔离" })
            assertTrue(plan.alreadyPatched)
            assertEquals(ImageIo.sha256(bytes), plan.sourceSha256)
            assertEquals(plan.sourceSha256, plan.outputSha256)
            assertArrayEquals("The completed image must retain the current Recovery/KBL topology", bytes, plan.output)
            assertEquals(EfispImagePatcher.FASTBOOT_TEXT,
                AblEfisp.scanText(bytes).entries.single { it.id == "fastboot-unlocked" }.text)
            val imported = File(directory.root, "upgraded-reimport-$index.img")
            EfispImagePatcher.patch(generated, imported, true)
            assertArrayEquals(bytes, imported.readBytes())
            assertArrayEquals("The legacy source remains available", original, input.readBytes())
        }
    }
}
