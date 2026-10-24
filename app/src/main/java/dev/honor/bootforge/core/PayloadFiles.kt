package dev.honor.bootforge.core

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class ImageTarget(val id: String, val stagedName: String) {
    EFISP("efisp", "efisp.img"),
    ABL("abl", "abl.img");

    companion object {
        fun fromId(id: String): ImageTarget? = entries.firstOrNull { it.id == id }
    }
}

data class StagedImage(
    val target: ImageTarget,
    val bytes: Long,
    val sha256: String,
    val custom: Boolean,
)

/** Copies selected or built-in images into stable names for a single execution. */
object PayloadFiles {
    /**
     * Inputs are copied before any published file is replaced. The manifest is
     * published last, so a failed import never publishes a new image selection.
     * It records the exact copied bytes, independently of names or file formats.
     */
    fun stageImages(
        directory: File,
        overrides: Map<ImageTarget, File>,
        openDefault: (String) -> InputStream,
    ): List<StagedImage> = stageImages(directory, overrides, true, openDefault)

    @Synchronized
    fun stageImages(
        directory: File,
        overrides: Map<ImageTarget, File>,
        useCustomImages: Boolean,
        openDefault: (String) -> InputStream,
    ): List<StagedImage> {
        check(directory.isDirectory || directory.mkdirs()) { "镜像暂存目录创建失败" }
        val pending = mutableListOf<Pair<File, StagedImage>>()
        var manifestTemp: File? = null
        try {
            for (target in ImageTarget.entries) {
                val temp = File.createTempFile(".payload-${target.id}-", ".tmp", directory)
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    val override = if (useCustomImages) overrides[target] else null
                    (override?.inputStream() ?: openDefault(target.stagedName)).use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                copied = Math.addExact(copied, count.toLong())
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    check(copied > 0L) { "${target.id} 镜像为空" }
                    val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    pending += temp to StagedImage(target, copied, sha256, override != null)
                } catch (failure: Exception) {
                    temp.delete()
                    throw failure
                }
            }
            manifestTemp = File.createTempFile(".payload-manifest-", ".tmp", directory)
            manifestTemp.outputStream().use { output ->
                pending.forEach { (_, image) ->
                    output.write("${image.sha256}  ${image.target.stagedName}\n".toByteArray(Charsets.UTF_8))
                }
                output.fd.sync()
            }
            pending.forEach { (temp, image) -> replace(temp, File(directory, image.target.stagedName)) }
            replace(manifestTemp, File(directory, "payloads.sha256"))
            return pending.map { it.second }
        } finally {
            pending.forEach { it.first.delete() }
            manifestTemp?.delete()
        }
    }

    private fun replace(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
