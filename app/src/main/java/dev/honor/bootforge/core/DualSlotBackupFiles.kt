package dev.honor.bootforge.core

import java.io.File

/** Shared names for the backup batch, its durable exports and partial-result reporting. */
object DualSlotBackupFiles {
    val partitions = listOf("init_boot_a", "init_boot_b", "abl_a", "abl_b")
    val images = partitions.map { "$it.img" }

    private fun letter(slot: String): Char = when (slot) {
        "_a" -> 'a'
        "_b" -> 'b'
        else -> error("当前运行槽位无效：$slot")
    }

    fun activeSlotFileName(slot: String): String = "slot_active_${letter(slot)}"

    fun writeActiveSlotFile(directory: File, slot: String): File {
        val name = activeSlotFileName(slot)
        return File(directory, name).apply {
            outputStream().use { out ->
                out.write(letter(slot).code)
                out.fd.sync()
            }
        }
    }

    fun originalsSaved(slot: String, fileNames: Collection<String>): Boolean =
        slot in listOf("_a", "_b") && (images + activeSlotFileName(slot)).all { it in fileNames }
}
