package dev.honor.bootforge.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PayloadFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private val defaults = mapOf(
        "efisp.img" to byteArrayOf(1, 2, 3, 4),
        "abl.img" to byteArrayOf(5, 6, 7),
    )
    private fun openDefault(name: String) = ByteArrayInputStream(checkNotNull(defaults[name]))
    private fun selected(name: String, content: ByteArray) = temporary.newFile(name).apply { writeBytes(content) }

    @Test fun targetIdsAndStableNamesAreIndependentOfInputNames() {
        assertEquals(ImageTarget.EFISP, ImageTarget.fromId("efisp"))
        assertEquals(ImageTarget.ABL, ImageTarget.fromId("abl"))
        assertNull(ImageTarget.fromId("unknown"))
        assertEquals("efisp.img", ImageTarget.EFISP.stagedName)
        assertEquals("abl.img", ImageTarget.ABL.stagedName)
    }

    @Test fun eachTargetIndependentlyFallsBackToItsDefault() {
        for (target in ImageTarget.entries) {
            val directory = temporary.newFolder()
            val content = "custom-${target.id}".toByteArray()
            val custom = selected("selected-${target.id}", content)
            val images = PayloadFiles.stageImages(directory, mapOf(target to custom), ::openDefault)
            for (image in images) {
                assertEquals(image.target == target, image.custom)
                val expected = if (image.target == target) content else defaults.getValue(image.target.stagedName)
                assertArrayEquals(expected, File(directory, image.target.stagedName).readBytes())
                assertEquals(expected.size.toLong(), image.bytes)
            }
        }
    }

    @Test fun arbitraryNamesExtensionsAndBinaryContentsAreAccepted() {
        val directory = temporary.newFolder()
        val efisp = byteArrayOf(0, -1, 0, 7, 8)
        val abl = "plain text, with no ELF header".toByteArray()
        val images = PayloadFiles.stageImages(directory, mapOf(
            ImageTarget.EFISP to selected("镜像 arbitrary.no-known-extension", efisp),
            ImageTarget.ABL to selected("ABL without extension", abl),
        ), ::openDefault)
        assertTrue(images.all { it.custom })
        assertArrayEquals(efisp, File(directory, ImageTarget.EFISP.stagedName).readBytes())
        assertArrayEquals(abl, File(directory, ImageTarget.ABL.stagedName).readBytes())
    }

    @Test fun clearingBothSelectionsRestoresDefaultsWithoutRetainingCustomData() {
        val directory = temporary.newFolder()
        PayloadFiles.stageImages(directory, mapOf(
            ImageTarget.EFISP to selected("first", ByteArray(8192) { 42 }),
            ImageTarget.ABL to selected("second", ByteArray(4096) { 17 }),
        ), ::openDefault)
        val images = PayloadFiles.stageImages(directory, emptyMap(), ::openDefault)
        assertFalse(images.any { it.custom })
        images.forEach { image ->
            assertArrayEquals(defaults.getValue(image.target.stagedName), File(directory, image.target.stagedName).readBytes())
        }
        assertEquals(3, directory.listFiles()!!.size)
    }

    @Test fun directModeUsesDefaultsAndRetainsCustomFilesForTheNextCustomRun() {
        val directory = temporary.newFolder()
        val efispBytes = byteArrayOf(90, 91, 92)
        val ablBytes = byteArrayOf(93, 94)
        val overrides = mapOf(
            ImageTarget.EFISP to selected("persistent-efisp", efispBytes),
            ImageTarget.ABL to selected("persistent-abl", ablBytes),
        )
        val direct = PayloadFiles.stageImages(directory, overrides, false, ::openDefault)
        assertTrue(direct.none { it.custom })
        direct.forEach { assertArrayEquals(defaults.getValue(it.target.stagedName), File(directory, it.target.stagedName).readBytes()) }
        assertArrayEquals(efispBytes, overrides.getValue(ImageTarget.EFISP).readBytes())
        assertArrayEquals(ablBytes, overrides.getValue(ImageTarget.ABL).readBytes())
        val custom = PayloadFiles.stageImages(directory, overrides, true, ::openDefault)
        assertTrue(custom.all { it.custom })
        assertArrayEquals(efispBytes, File(directory, ImageTarget.EFISP.stagedName).readBytes())
        assertArrayEquals(ablBytes, File(directory, ImageTarget.ABL.stagedName).readBytes())
        assertNotEquals(direct.first().sha256, custom.first().sha256)
    }

    @Test fun directModeDoesNotOpenMissingCustomInputs() {
        val directory = temporary.newFolder()
        val images = PayloadFiles.stageImages(directory,
            mapOf(ImageTarget.EFISP to File(directory, "missing-custom-file")), false, ::openDefault)
        assertTrue(images.none { it.custom })
        assertArrayEquals(defaults.getValue(ImageTarget.EFISP.stagedName),
            File(directory, ImageTarget.EFISP.stagedName).readBytes())
    }

    @Test fun manifestHashesAndSizesDescribeTheActuallyCopiedBytes() {
        val directory = temporary.newFolder()
        val content = ByteArray(140003) { (it * 31).toByte() }
        val images = PayloadFiles.stageImages(directory, mapOf(
            ImageTarget.EFISP to selected("large.bin", content),
        ), ::openDefault)
        val manifest = File(directory, "payloads.sha256").readText(Charsets.UTF_8)
        assertTrue(manifest.endsWith("\n"))
        val lines = manifest.trimEnd('\n').split('\n')
        assertEquals(2, lines.size)
        images.forEachIndexed { index, image ->
            val copied = File(directory, image.target.stagedName).readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(copied)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(copied.size.toLong(), image.bytes)
            assertEquals(hash, image.sha256)
            assertEquals("$hash  ${image.target.stagedName}", lines[index])
            assertTrue(Regex("[a-f0-9]{64}  [^\r\n]+\\n").matches(lines[index] + "\n"))
        }
    }

    @Test fun emptyImageBlocksPublicationAndPreservesPreviousSelection() {
        val directory = temporary.newFolder()
        PayloadFiles.stageImages(directory, emptyMap(), ::openDefault)
        val previous = directory.listFiles()!!.associate { it.name to it.readBytes() }
        val empty = selected("empty.anything", byteArrayOf())
        try {
            PayloadFiles.stageImages(directory, mapOf(ImageTarget.ABL to empty), ::openDefault)
            fail("An empty image must stop staging")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("abl"))
        }
        assertEquals(previous.keys, directory.listFiles()!!.map { it.name }.toSet())
        previous.forEach { (name, bytes) -> assertArrayEquals(bytes, File(directory, name).readBytes()) }
    }

    @Test fun inputFailureDoesNotPublishANewManifest() {
        val directory = temporary.newFolder()
        PayloadFiles.stageImages(directory, emptyMap(), ::openDefault)
        val before = Files.readAllBytes(File(directory, "payloads.sha256").toPath())
        try {
            PayloadFiles.stageImages(directory, mapOf(ImageTarget.EFISP to selected("replacement", byteArrayOf(9)))) { name ->
                if (name == ImageTarget.ABL.stagedName) throw IOException("Source unavailable")
                openDefault(name)
            }
            fail("A failed source must stop staging")
        } catch (_: IOException) {
            assertArrayEquals(before, File(directory, "payloads.sha256").readBytes())
        }
        assertEquals(3, directory.listFiles()!!.size)
    }
}
