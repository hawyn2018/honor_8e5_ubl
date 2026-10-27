package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DualSlotBackupFilesTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun bothSlotsUseDistinctRestorableImageNames() {
        assertEquals(listOf("init_boot_a", "init_boot_b", "abl_a", "abl_b"), DualSlotBackupFiles.partitions)
        assertEquals(setOf("init_boot_a.img", "init_boot_b.img", "abl_a.img", "abl_b.img"), DualSlotBackupFiles.images.toSet())
        assertEquals(4, DualSlotBackupFiles.images.size)
    }

    @Test fun currentSlotMarkerContainsExactlyOneAsciiLetter() {
        for (slot in listOf("_a", "_b")) {
            val folder = directory.newFolder(slot)
            val marker = DualSlotBackupFiles.writeActiveSlotFile(folder, slot)
            val letter = slot.last()
            assertEquals("slot_active_$letter", marker.name)
            assertArrayEquals(byteArrayOf(letter.code.toByte()), marker.readBytes())
            assertEquals(listOf(marker.name), folder.listFiles()!!.map { it.name })
        }
    }

    @Test fun invalidSlotNeverCreatesAMarker() {
        val folder = directory.newFolder("invalid")
        for (slot in listOf("", "a", "_c", "_a\n", "../a")) {
            assertThrows(IllegalStateException::class.java) { DualSlotBackupFiles.writeActiveSlotFile(folder, slot) }
        }
        assertTrue(folder.listFiles()!!.isEmpty())
    }

    @Test fun partialBackupRequiresBothSlotsAndTheMatchingActiveMarker() {
        for (slot in listOf("_a", "_b")) {
            val marker = DualSlotBackupFiles.activeSlotFileName(slot)
            val complete = DualSlotBackupFiles.images + marker
            assertTrue(DualSlotBackupFiles.originalsSaved(slot, complete + "gpt-backup.json"))
            for (missing in complete) assertFalse("Missing $missing", DualSlotBackupFiles.originalsSaved(slot, complete - missing))
            val wrongMarker = DualSlotBackupFiles.activeSlotFileName(if (slot == "_a") "_b" else "_a")
            assertFalse(DualSlotBackupFiles.originalsSaved(slot, DualSlotBackupFiles.images + wrongMarker))
        }
        assertFalse(DualSlotBackupFiles.originalsSaved("_b", listOf("init_boot.img", "abl.img")))
        assertFalse(DualSlotBackupFiles.originalsSaved("", DualSlotBackupFiles.images))
    }
}
