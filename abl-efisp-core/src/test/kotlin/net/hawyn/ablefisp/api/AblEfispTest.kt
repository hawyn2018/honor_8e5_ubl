package net.hawyn.ablefisp.api

import net.hawyn.ablefisp.io.ImageIo
import net.hawyn.ablefisp.patch.PatchEngine
import net.hawyn.ablefisp.patch.PatchException
import net.hawyn.ablefisp.patch.PatchFailureKind
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AblEfispTest {
    @Test fun barePeWithoutCoreFeaturesCanPrepareTextDirectly() {
        val input = barePe()
        val unchanged = input.copyOf()
        val prepared = AblEfisp.prepareTextEditing(input)
        assertNull(prepared.patchResult)
        assertEquals("ARM64 PE", prepared.sourceFormat)
        assertEquals(ImageIo.sha256(input), prepared.sourceSha256)
        assertEquals(ImageIo.sha256(prepared.output), prepared.outputSha256)
        assertTrue(prepared.entries.any { it.text == "Your device has been unlocked" })
        assertArrayEquals(unchanged, input)
        assertNotSame(input, prepared.output)
        prepared.output[0x2200] = 7
        assertArrayEquals(unchanged, input)
    }

    @Test fun editsReturnIndependentBytesAndKeepPreparationCatalogue() {
        val prepared = AblEfisp.prepareTextEditing(barePe())
        val baseline = prepared.output.copyOf()
        val warning = prepared.entries.single { it.text == "Your device has been unlocked" }
        val edited = AblEfisp.editText(prepared.output, mapOf(warning.id to "Custom warning"))
        assertArrayEquals(baseline, prepared.output)
        assertNotSame(prepared.output, edited.output)
        assertEquals("Your device has been unlocked", warning.text)
        assertEquals("Custom warning", AblEfisp.scanText(edited.output).entries.single { it.id == warning.id }.text)
        assertEquals(warning.id, edited.changes.single().id)
        val unchanged = AblEfisp.editText(edited.output, emptyMap())
        assertArrayEquals(edited.output, unchanged.output)
        assertNotSame(edited.output, unchanged.output)
    }

    @Test fun facadeCollectionsAreReadOnlySnapshots() {
        val first = AblEfisp.prepareTextEditing(barePe())
        val second = AblEfisp.prepareTextEditing(barePe())
        assertThrows(UnsupportedOperationException::class.java) { (first.entries as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (first.notes as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) {
            (AblEfisp.extract(barePe()).notes as MutableList<*>).clear()
        }
        assertEquals(first.entries, second.entries)
        assertNotSame(first.output, second.output)
    }

    @Test fun partialCoreRawPeDoesNotFallBackToText() {
        val input = barePe()
        "common_check_rdmod_v1_state\u0000".toByteArray().copyInto(input, 0x3000)
        val unchanged = input.copyOf()
        val error = assertThrows(PatchException::class.java) { AblEfisp.prepareTextEditing(input) }
        assertEquals(PatchFailureKind.INCOMPLETE_CORE_FEATURE, error.kind)
        assertArrayEquals(unchanged, input)
    }

    @Test fun wrappedImageWithoutCoreDoesNotUseBarePeFallback() {
        val wrapped = elfContainer(barePe())
        assertArrayEquals(barePe(), AblEfisp.extract(wrapped).output)
        val error = assertThrows(PatchException::class.java) { AblEfisp.prepareTextEditing(wrapped) }
        assertEquals(PatchFailureKind.NO_CORE_FEATURE, error.kind)
        val malformed = assertThrows(PatchException::class.java) {
            AblEfisp.prepareTextEditing(byteArrayOf(0x4d, 0x5a))
        }
        assertEquals(PatchFailureKind.STRUCTURE_INVALID, malformed.kind)
    }

    @Test fun corePreparationPreservesPatchApiAndAlreadyPatchedSemantics() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "analysis/ablpatcher-fixtures/files/stock-container.fixture.elf").isFile }
        assumeTrue("Optional local firmware integration fixture", root != null)
        val input = File(root!!, "analysis/ablpatcher-fixtures/files/stock-container.fixture.elf").readBytes()
        val unchanged = input.copyOf()
        val expected = PatchEngine.analyzeAndPatch(input)
        val patched = AblEfisp.patch(input)
        assertArrayEquals(expected.output, patched.output)
        assertEquals(expected.patches, patched.patches)
        val prepared = AblEfisp.prepareTextEditing(input)
        assertNotNull(prepared.patchResult)
        assertSame(prepared.output, prepared.patchResult!!.output)
        assertArrayEquals(patched.output, prepared.output)
        assertFalse(prepared.patchResult.alreadyPatched)
        val again = AblEfisp.prepareTextEditing(prepared.output)
        assertTrue(requireNotNull(again.patchResult).alreadyPatched)
        assertArrayEquals(prepared.output, again.output)
        assertArrayEquals(unchanged, input)
        assertThrows(UnsupportedOperationException::class.java) { (patched.patches as MutableList<*>).clear() }
    }

    /** Small synthetic PE with only menu resources; no firmware payload is bundled. */
    private fun barePe(): ByteArray {
        val bytes = ByteArray(0x4000)
        fun word(at: Int, value: Int) { repeat(4) { bytes[at + it] = (value ushr (it * 8)).toByte() } }
        fun half(at: Int, value: Int) { repeat(2) { bytes[at + it] = (value ushr (it * 8)).toByte() } }
        bytes[0] = 0x4d; bytes[1] = 0x5a
        word(0x3c, 0x80); word(0x80, 0x4550)
        half(0x84, 0xaa64); half(0x86, 2); half(0x94, 0xf0)
        val optional = 0x98
        half(optional, 0x20b); word(optional + 16, 0x1000)
        word(optional + 32, 0x1000); word(optional + 36, 0x1000)
        word(optional + 56, bytes.size); word(optional + 60, 0x1000); half(optional + 68, 10)
        val table = optional + 0xf0
        ".text".toByteArray().copyInto(bytes, table)
        word(table + 8, 0x1000); word(table + 12, 0x1000)
        word(table + 16, 0x1000); word(table + 20, 0x1000); word(table + 36, 0x60000020)
        ".data".toByteArray().copyInto(bytes, table + 40)
        word(table + 48, 0x2000); word(table + 52, 0x2000)
        word(table + 56, 0x2000); word(table + 60, 0x2000); word(table + 76, 0xc0000040.toInt())
        word(0x1000, 0xd65f03c0.toInt())
        fun resource(at: Int, text: String) {
            text.toByteArray().copyInto(bytes, at)
            intArrayOf(1, 0, 1, 0, 0, 9).forEachIndexed { index, value -> word(at + 256 + index * 4, value) }
        }
        resource(0x2100, "<!>")
        resource(0x2218, "Your device has been unlocked")
        resource(0x2330, "Press Power key to continue")
        return bytes
    }

    private fun elfContainer(pe: ByteArray): ByteArray {
        val bytes = ByteArray(0x1000 + pe.size)
        fun value(at: Int, number: Long, size: Int) { repeat(size) { bytes[at + it] = (number ushr (it * 8)).toByte() } }
        byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1).copyInto(bytes)
        value(32, 64, 8); value(52, 64, 2); value(54, 56, 2); value(56, 1, 2)
        value(64, 1, 4); value(72, 0x1000, 8)
        value(96, pe.size.toLong(), 8); value(104, pe.size.toLong(), 8)
        pe.copyInto(bytes, 0x1000)
        return bytes
    }
}
