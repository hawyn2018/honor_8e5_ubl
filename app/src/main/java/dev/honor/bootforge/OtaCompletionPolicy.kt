package dev.honor.bootforge

import dev.honor.bootforge.core.OtaCompletionMarker
import dev.honor.bootforge.core.KernelSuHandoff

enum class OtaCompletionAction(val label: String) {
    OPEN_KERNEL_SU("KernelSU 对槽安装"),
    REBOOT_TO_TRY_EFISP("重启体验新版efisp"),
    NONE("")
}

/** One completion contract shared by the service, model and page. */
object OtaCompletionPolicy {
    fun action(operation: OtaOperation): OtaCompletionAction = when (operation) {
        OtaOperation.FULL -> OtaCompletionAction.OPEN_KERNEL_SU
        OtaOperation.EFISP_ONLY -> OtaCompletionAction.REBOOT_TO_TRY_EFISP
        OtaOperation.EXPORT_EFISP -> OtaCompletionAction.NONE
    }

    fun available(operation: OtaOperation, requested: OtaCompletionAction, completed: Boolean,
        busy: Boolean): Boolean =
        completed && !busy && requested != OtaCompletionAction.NONE && action(operation) == requested

    fun stage(operation: OtaOperation, markerReady: Boolean? = null): String = when (operation) {
        OtaOperation.FULL -> if (markerReady == false)
            "保留锁态 OTA 已完成，联动标记未就绪，请查看日志"
        else "保留锁态 OTA 已完成，请在 KernelSU 安装到未使用的槽位"
        OtaOperation.EFISP_ONLY -> "仅刷写 efisp 完成"
        OtaOperation.EXPORT_EFISP -> "efisp 导出完成，未写入分区"
    }
}

/** Auxiliary work after a successful transaction; neither action performs reboot. */
internal object OtaCompletionFlow {
    data class Result(val markerReady: Boolean?, val logs: List<String>)

    fun afterSuccess(operation: OtaOperation, writeMarker: () -> OtaCompletionMarker.Result,
        openKernelSu: () -> KernelSuHandoff.Result): Result {
        if (operation != OtaOperation.FULL)
            return Result(null, emptyList())
        val logs = mutableListOf<String>()
        val marker = try { writeMarker() }
        catch (failure: Exception) { OtaCompletionMarker.Result(false,
            listOf("[更新联动] 警告：完成标记未确认，OTA 成功结果保留：${failure.message ?: failure.javaClass.simpleName}")) }
        logs += marker.logs
        val handoff = try { openKernelSu() }
        catch (failure: Exception) { KernelSuHandoff.Result(false,
            listOf("[更新联动] 打开 KernelSU 失败，OTA 成功结果保留，请手动打开：${failure.message ?: failure.javaClass.simpleName}")) }
        logs += handoff.logs
        return Result(marker.marked, logs)
    }
}
