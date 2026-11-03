package dev.honor.bootforge

import dev.honor.bootforge.core.OtaCompletionMarker
import dev.honor.bootforge.core.SystemUpdaterHandoff

enum class OtaCompletionAction { RETURN_TO_UPDATER, NONE }

/** One completion contract shared by the service, model and page. */
object OtaCompletionPolicy {
    fun action(operation: OtaOperation): OtaCompletionAction = when (operation) {
        OtaOperation.FULL -> OtaCompletionAction.RETURN_TO_UPDATER
        OtaOperation.EFISP_ONLY -> OtaCompletionAction.RETURN_TO_UPDATER
        OtaOperation.EXPORT_EFISP -> OtaCompletionAction.NONE
    }

    fun available(operation: OtaOperation, requested: OtaCompletionAction, completed: Boolean,
        busy: Boolean): Boolean =
        completed && !busy && requested != OtaCompletionAction.NONE && action(operation) == requested

    fun stage(operation: OtaOperation, markerReady: Boolean? = null): String = when (operation) {
        OtaOperation.FULL -> if (markerReady == false)
            "保留锁态 OTA 已完成，联动标记未就绪，请查看日志并返回软件更新"
        else "保留锁态 OTA 已完成，请返回软件更新点击立即重启"
        OtaOperation.EFISP_ONLY -> "仅刷写 efisp 完成，可返回软件更新"
        OtaOperation.EXPORT_EFISP -> "efisp 导出完成，未写入分区"
    }
}

/** Auxiliary work after a successful transaction; neither action performs reboot. */
internal object OtaCompletionFlow {
    data class Result(val markerReady: Boolean?, val logs: List<String>)

    fun afterSuccess(operation: OtaOperation, writeMarker: () -> OtaCompletionMarker.Result,
        openUpdater: () -> SystemUpdaterHandoff.Result): Result {
        if (operation != OtaOperation.FULL)
            return Result(null, emptyList())
        val logs = mutableListOf<String>()
        val marker = try { writeMarker() }
        catch (failure: Exception) { OtaCompletionMarker.Result(false,
            listOf("[更新联动] 警告：完成标记未确认，OTA 成功结果保留：${failure.message ?: failure.javaClass.simpleName}")) }
        logs += marker.logs
        val handoff = try { openUpdater() }
        catch (failure: Exception) { SystemUpdaterHandoff.Result(false,
            listOf("[更新联动] 打开软件更新失败，OTA 成功结果保留，请手动打开：${failure.message ?: failure.javaClass.simpleName}")) }
        logs += handoff.logs
        return Result(marker.marked, logs)
    }
}
