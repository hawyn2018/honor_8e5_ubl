package dev.honor.bootforge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.honor.bootforge.core.SavedUpload
import dev.honor.bootforge.core.UploadRole
import dev.honor.bootforge.core.UploadedImageFiles
import dev.honor.bootforge.core.UploadSelectionPersistence
import java.io.File

/** Stable purposes: abl flashes ABL; efisp-abl patches EFISP; efisp imports EFI.
 * Legacy abl remains the flash input only. A missing EFISP ABL requires a new
 * explicit selection, rather than silently borrowing the old shared upload. */
internal object OtaInputs {
    private fun role(kind: String) = requireNotNull(UploadRole.fromKey(kind)) { "上传类型无效" }
    private fun prefs(context: Context) = context.getSharedPreferences("ota-inputs", 0)
    private fun files(context: Context) = UploadedImageFiles(File(context.filesDir, "ota-inputs"))
    private fun record(context: Context, kind: String): SavedUpload? {
        role(kind); val p = prefs(context); val name = p.getString(kind, "").orEmpty()
        if (name.isBlank()) return null
        return SavedUpload(name, p.getString("$kind-name", "${kind}.img").orEmpty(), p.getString("$kind-sha", "").orEmpty())
    }
    fun displayName(context: Context, kind: String): String {
        val selected = record(context, kind) ?: return "尚未上传${role(kind).label}"
        val file = files(context).path(role(kind), selected.fileName)
        return if (file.isFile && file.length() > 0) selected.displayName else "已选文件缺失，请重新上传${role(kind).label}"
    }
    private fun publish(context: Context, kind: String, saved: SavedUpload): Boolean {
        val p = prefs(context)
        val next = mapOf<String,Any?>(kind to saved.fileName, "$kind-name" to saved.displayName, "$kind-sha" to saved.sha256)
        val all = p.all; val previous = next.keys.associateWith { all[it] }
        return UploadSelectionPersistence.publish(previous,next) { values ->
            val edit = p.edit()
            values.forEach { (key,value) -> if (value == null) edit.remove(key) else edit.putString(key,value as String) }
            edit.commit()
        }
    }
    fun importImage(context: Context, kind: String, uri: Uri): String {
        val purpose = role(kind)
        val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull().orEmpty()
        return files(context).install(purpose, name, record(context, kind), 128L*1024*1024, open = {
            checkNotNull(context.contentResolver.openInputStream(uri)) { "上传文件读取失败" }
        }, publish = { saved -> publish(context,kind,saved) }).displayName
    }
    fun snapshot(context: Context, kind: String, destination: File): File = files(context).snapshot(role(kind),
        checkNotNull(record(context, kind)) { "请先上传${role(kind).label}" }, destination)
}
