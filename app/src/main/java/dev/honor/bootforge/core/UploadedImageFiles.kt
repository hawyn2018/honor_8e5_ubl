package dev.honor.bootforge.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Upload identities are purposes, not final partition names. Old abl belongs
 * exclusively to ABL flashing; it is never a fallback for EFISP's source ABL. */
enum class UploadRole(val key: String, val label: String) {
    ABL_FLASH("abl", "用于刷写 ABL 的镜像"),
    EFISP_ABL("efisp-abl", "用于修补 EFISP 的 ABL"),
    EFISP_IMAGE("efisp", "EFISP 镜像");
    companion object {
        fun fromKey(key: String): UploadRole? = entries.firstOrNull { it.key == key }
        fun forTarget(target: ImageTarget): UploadRole = if (target == ImageTarget.ABL) ABL_FLASH else EFISP_IMAGE
    }
}

internal data class SavedUpload(val fileName: String, val displayName: String, val sha256: String)

/** File-only transaction. Publish metadata last; replacing one role never
 * deletes or chooses another role's file, even after repository reconstruction. */
internal class UploadedImageFiles(private val directory: File) {
    fun path(role: UploadRole, name: String): File {
        val key = Regex.escape(role.key)
        check(name == "${role.key}.bin" || Regex("$key-[a-f0-9-]{36}\\.(?:img|bin)").matches(name)) { "${role.label}保存路径无效" }
        val file = File(directory, name)
        check(file.canonicalFile.parentFile == directory.canonicalFile) { "上传文件超出保存目录" }
        return file
    }
    fun selected(role: UploadRole, saved: SavedUpload): File = path(role, saved.fileName).also {
        check(Regex("[a-f0-9]{64}").matches(saved.sha256) && it.isFile && it.length() > 0 && Payloads.sha256(it) == saved.sha256) {
            "${role.label}缺失或已变化，请重新上传"
        }
    }
    fun install(role: UploadRole, displayName: String, previous: SavedUpload?, maxBytes: Long? = null,
                open: () -> InputStream, publish: (SavedUpload) -> Boolean): SavedUpload {
        check(directory.isDirectory || directory.mkdirs()) { "上传文件目录创建失败" }
        val destination = File(directory, "${role.key}-${UUID.randomUUID()}.img")
        val temporary = File.createTempFile(".${role.key}-", ".import", directory)
        var committed = false
        try {
            open().use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break; if (n == 0) continue
                    total = Math.addExact(total, n.toLong())
                    check(maxBytes == null || total <= maxBytes) { "上传文件超过允许大小" }
                    output.write(buffer, 0, n)
                }
                output.fd.sync()
            } }
            check(temporary.length() > 0) { "上传文件为空" }
            val name = displayName.replace(Regex("[\\r\\n\\t]"), " ").take(256).ifBlank { "${role.key}.img" }
            val saved = SavedUpload(destination.name, name, Payloads.sha256(temporary))
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            check(publish(saved)) { "上传文件记录保存失败" }
            committed = true
            previous?.let { runCatching { path(role, it.fileName).takeIf { old -> old != destination }?.delete() } }
            return saved
        } finally {
            temporary.delete()
            if (!committed) destination.delete()
        }
    }
    fun snapshot(role: UploadRole, saved: SavedUpload, destination: File): File {
        val source = selected(role, saved)
        check(source.canonicalFile != destination.canonicalFile) { "上传来源与快照路径相同" }
        check(!destination.exists()) { "上传快照目标已存在" }
        try {
            source.copyTo(destination)
            check(Payloads.sha256(destination) == saved.sha256 && Payloads.sha256(source) == saved.sha256) { "上传文件固定校验失败" }
            return destination
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }
}
