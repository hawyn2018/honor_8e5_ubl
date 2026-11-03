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

class EfispImagePatcherTest {
    @get:Rule val directory = TemporaryFolder()

    private fun localAbl(): File {
        val workspace = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(8).firstOrNull { File(it, "analysis/ablpatcher-fixtures/files/stock-container.fixture.elf").isFile }
        assumeTrue("Optional local firmware integration fixture", workspace != null)
        return File(workspace!!, "analysis/ablpatcher-fixtures/files/stock-container.fixture.elf")
    }

    private fun assertFinalTopology(bytes: ByteArray, mode: PatchMode): Int {
        val proof = AblEfisp.patchWithKbl(bytes, mode)
        assertTrue("Completed Recovery/KBL mappings must reimport without edits", proof.alreadyPatched)
        assertArrayEquals(bytes, proof.output)
        assertEquals(ImageIo.sha256(bytes), proof.sourceSha256)
        assertEquals(proof.sourceSha256, proof.outputSha256)
        assertEquals(bytes.size, proof.imageSize)
        assertTrue(proof.patches.any { it.name.startsWith("KBL") })
        assertTrue(proof.patches.any { it.name == "EFISP 返回边界隔离" })
        for (patch in proof.patches) {
            val expected = patch.after.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertArrayEquals(patch.name, expected,
                bytes.copyOfRange(patch.fileOffset, patch.fileOffset + expected.size))
        }
        return proof.patches.size
    }

    @Test fun refusesSameInputAndOutputBeforeChangingSource() {
        val source = directory.newFile("abl.img").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { EfispImagePatcher.patch(source, source, false) }
        assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
    }

    @Test fun invalidSourceDoesNotReplaceExistingOutput() {
        val source = directory.newFile("abl.img").apply { writeBytes(ByteArray(32)) }
        val output = directory.newFile("efisp.img").apply { writeText("previous verified output") }
        assertThrows(Exception::class.java) { EfispImagePatcher.patch(source, output, false) }
        assertEquals("previous verified output", output.readText())
        assertEquals(2, directory.root.listFiles()!!.size)
    }

    @Test fun emptySourceDoesNotPublishAnOutput() {
        val source = directory.newFile("empty.img")
        val output = File(directory.root, "efisp.img")
        assertThrows(IllegalArgumentException::class.java) { EfispImagePatcher.patch(source, output, false) }
        assertFalse(output.exists())
    }

    @Test fun freedomPublishesCustomizedImageWithMatchingHashes() {
        val source = localAbl()
        val sourceBefore = source.readBytes()
        val output = File(directory.root, "efisp.img")
        val logs = mutableListOf<String>()
        val result = EfispImagePatcher.patch(source, output, false, onLog = { logs += it })
        assertEquals(output.length(), result.bytes)
        assertEquals(ImageIo.sha256(output.readBytes()), result.sha256)
        assertEquals(ImageIo.sha256(sourceBefore), result.sourceSha256)
        assertFalse(result.fakeLock)
        assertTrue(result.patchCount > 0)
        assertEquals(result.patchCount, assertFinalTopology(output.readBytes(), PatchMode.FREEDOM))
        assertTrue(logs.any { it.contains("文字定制前补丁 ") && it.contains("RVA ") && it.contains("PE 文件偏移 ") })
        assertTrue(logs.any { it.contains("文字定制前原始字节=") })
        assertTrue(logs.any { it.contains("文字定制前修补字节=") })
        assertTrue(logs.any { it.contains("最终补丁 ") && it.contains("文字定制后字节校验通过") })
        assertTrue(logs.any { it.contains("最终镜像重导入、完整字节与核心补丁回读校验通过") })
        val entries = AblEfisp.scanText(output.readBytes()).entries
        assertEquals("device freedom (github hawyn2018)", EfispImagePatcher.FASTBOOT_TEXT)
        assertEquals(EfispImagePatcher.FASTBOOT_TEXT, entries.single { it.id == "fastboot-unlocked" }.text)
        val warnings = entries.filter { it.id.startsWith("resource-") && it.text.contains("has been unlocked") }
        assertTrue(warnings.isNotEmpty())
        assertTrue(warnings.all { it.text == EfispImagePatcher.BOOT_WARNING })
        assertArrayEquals(sourceBefore, source.readBytes())
        assertEquals(listOf("efisp.img"), directory.root.listFiles()!!.map { it.name })
    }

    @Test fun fakeLockChangesFastbootAndPreservesAllMenuResources() {
        val source = localAbl()
        val prepared = AblEfisp.patch(source.readBytes(), PatchMode.FREEDOM_FAKE_LOCK)
        val originalMenus = AblEfisp.scanText(prepared.output).entries
            .filter { it.id.startsWith("resource-") }.associate { it.id to it.text }
        val output = File(directory.root, "efisp.img")
        val result = EfispImagePatcher.patch(source, output, true)
        val entries = AblEfisp.scanText(output.readBytes()).entries
        assertTrue(result.fakeLock)
        assertEquals("device freedom (github hawyn2018)", EfispImagePatcher.FASTBOOT_TEXT)
        assertEquals(EfispImagePatcher.FASTBOOT_TEXT, entries.single { it.id == "fastboot-unlocked" }.text)
        assertEquals(originalMenus, entries.filter { it.id.startsWith("resource-") }.associate { it.id to it.text })
        assertEquals(ImageIo.sha256(output.readBytes()), result.sha256)
        assertEquals(result.patchCount, assertFinalTopology(output.readBytes(), PatchMode.FREEDOM_FAKE_LOCK))
    }

    @Test fun missingFastbootCustomizationStopsWithoutReplacingOutput() {
        val image = AblEfisp.extract(localAbl().readBytes()).output
        val marker = "PHONE_UNLOCKED strncpy_s error".toByteArray(Charsets.US_ASCII)
        var changed = 0
        for (at in 0..image.size - marker.size) {
            if (marker.indices.all { image[at + it] == marker[it] }) {
                image[at] = 'X'.code.toByte()
                changed++
            }
        }
        assertTrue(changed > 0)
        val source = directory.newFile("without-label.pe").apply { writeBytes(image) }
        val output = directory.newFile("efisp.img").apply { writeText("retained") }
        val failure = assertThrows(IllegalStateException::class.java) { EfispImagePatcher.patch(source, output, false) }
        assertTrue(failure.message.orEmpty().contains("Fastboot"))
        assertEquals("retained", output.readText())
    }
}
