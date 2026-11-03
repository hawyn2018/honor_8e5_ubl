package dev.honor.bootforge.core

/** A failed SharedPreferences commit may already have changed its memory map.
 * Restore exactly this role's old fields before the new file is discarded. */
internal object UploadSelectionPersistence {
    fun publish(previous: Map<String,Any?>, next: Map<String,Any?>,
                commit: (Map<String,Any?>) -> Boolean): Boolean {
        require(previous.keys == next.keys)
        val failure = try {
            if (commit(next)) return true
            IllegalStateException("上传选择持久化失败")
        } catch (error: Throwable) { error }
        val rollback = runCatching { commit(previous) }
        val message = when {
            rollback.getOrNull() == true -> "上传选择保存失败，已恢复此前选择"
            rollback.isFailure -> "上传选择保存失败；恢复原选择时发生错误，请重新选择文件：${rollback.exceptionOrNull()?.message.orEmpty()}"
            else -> "上传选择保存失败；原选择的持久化恢复未成功，请重新选择文件"
        }
        throw IllegalStateException(message,failure).also { result ->
            rollback.exceptionOrNull()?.let { result.addSuppressed(it) }
        }
    }
}
