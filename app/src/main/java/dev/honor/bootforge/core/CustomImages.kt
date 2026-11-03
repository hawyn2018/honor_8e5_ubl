package dev.honor.bootforge.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

internal object CustomImages {
    private fun prefs(context: Context) = context.getSharedPreferences("custom_images", 0)
    private fun files(context: Context) = UploadedImageFiles(File(context.filesDir, "selected"))
    private fun record(context: Context, role: UploadRole): SavedUpload? {
        val p = prefs(context); if (!p.getBoolean("${role.key}_selected", false)) return null
        return SavedUpload(p.getString("${role.key}_file", "${role.key}.bin").orEmpty(),
            p.getString("${role.key}_name", "${role.key}.img").orEmpty(), p.getString("${role.key}_sha256", "").orEmpty())
    }
    fun selectedFile(context: Context, role: UploadRole): File {
        val saved = checkNotNull(record(context, role)) { "请先上传${role.label}" }
        return files(context).selected(role, saved)
    }
    fun info(context: Context, role: UploadRole): Payloads.ImageInfo {
        val saved = record(context, role) ?: return when (role) {
            UploadRole.ABL_FLASH -> Payloads.ImageInfo("abl.img", 642136L, BUILTIN_ABL_SHA256, false)
            UploadRole.EFISP_ABL -> Payloads.ImageInfo("尚未上传用于修补 EFISP 的 ABL", 0, "", false)
            UploadRole.EFISP_IMAGE -> Payloads.ImageInfo("efisp.img · 运行时生成", 0, "", false)
        }
        val file = files(context).path(role, saved.fileName)
        check(file.isFile && file.length() > 0) { "${role.label}已缺失，请重新上传或清除选择" }
        return Payloads.ImageInfo(saved.displayName, file.length(), saved.sha256, true)
    }
    private fun publish(context: Context, role: UploadRole, saved: SavedUpload): Boolean {
        val p = prefs(context)
        val next = mapOf<String,Any?>("${role.key}_selected" to true, "${role.key}_file" to saved.fileName,
            "${role.key}_name" to saved.displayName, "${role.key}_sha256" to saved.sha256)
        val all = p.all; val previous = next.keys.associateWith { all[it] }
        return UploadSelectionPersistence.publish(previous,next) { values ->
            val edit = p.edit()
            values.forEach { (key,value) -> when (value) {
                null -> edit.remove(key)
                is Boolean -> edit.putBoolean(key,value)
                is String -> edit.putString(key,value)
                else -> error("上传选择字段类型无效：$key")
            } }
            edit.commit()
        }
    }
    fun importImage(context: Context, role: UploadRole, uri: Uri): Payloads.ImageInfo {
        val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull().orEmpty()
        val saved = files(context).install(role, name, record(context, role), open = {
            checkNotNull(context.contentResolver.openInputStream(uri)) { "文件读取失败" }
        }, publish = { selected -> publish(context,role,selected) })
        return Payloads.ImageInfo(saved.displayName, files(context).path(role, saved.fileName).length(), saved.sha256, true)
    }
    fun snapshot(context: Context, role: UploadRole, destination: File): File = files(context).snapshot(role,
        checkNotNull(record(context, role)) { "请先上传${role.label}" }, destination)
    fun reset(context: Context, role: UploadRole) {
        check(prefs(context).edit().putBoolean("${role.key}_selected", false).commit()) { "上传选择清除失败" }
    }
}
