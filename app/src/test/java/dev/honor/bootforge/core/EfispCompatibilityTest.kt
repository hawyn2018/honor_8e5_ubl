package dev.honor.bootforge.core

import net.hawyn.ablefisp.api.AblEfisp
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
            val core = AblEfisp.patch(before, mode)
            val generated = File(directory.root, "generated-$fake.img")
            val logs = mutableListOf<String>()
            val result = EfispImagePatcher.patch(source, generated, fake, onLog = { logs += it })
            assertTrue(result.patchCount > 0)
            assertEquals(core.patches.size, result.patchCount)
            val bytes = generated.readBytes()
            for (patch in core.patches) {
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

    @Test fun existingSuccessfulFakeLockImageKeepsItsBytesAndReimports() {
        val source = fixture("analysis/postfakebl-v244-20261027/source-abl-before.full.img")
        val golden = fixture("analysis/postfakebl-v244-20261027/actual-efisp.img")
        val generated = File(directory.root, "existing-image.img")
        EfispImagePatcher.patch(source, generated, true)
        assertArrayEquals("The previously tested image must not change", golden.readBytes(), generated.readBytes())
        val imported = File(directory.root, "existing-reimport.img")
        EfispImagePatcher.patch(golden, imported, true)
        assertArrayEquals(golden.readBytes(), imported.readBytes())
    }
}
