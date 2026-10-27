package dev.honor.bootforge

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.honor.bootforge.core.Payloads
import dev.honor.bootforge.core.UnlockCompletion
import dev.honor.bootforge.core.UnlockRequest
import dev.honor.bootforge.core.ImageTarget
import dev.honor.bootforge.core.ToolReport
import dev.honor.bootforge.core.Probe
import dev.honor.bootforge.core.RootShell
import dev.honor.bootforge.core.scriptCommand
import dev.honor.bootforge.ui.UnlockUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object RunState {
    var state by mutableStateOf(UnlockUiState())
    var nextScript: String = "unlock.sh"
    var preparedJob: PreparedJob? = null
    var preparedArchive: File? = null
    fun append(message: String) { state = state.copy(logs = state.logs + message.lines()) }
    fun clearPrepared() {
        preparedJob = null
        preparedArchive = null
        state = state.copy(recoveryPackageReady = false, backupExported = false, canRebootRecovery = false, canRebootBootloader = false, completedRequest = null)
    }
    fun tools(report: ToolReport) {
        state = state.copy(
            toolsSummary = if (report.coreReady) "核心工具已就绪 · 系统优先，内置备用" else "工具准备未完成，请查看缺失项目",
            toolWarnings = report.warnings,
        )
    }
}

class UnlockModel(application: Application) : AndroidViewModel(application) {
    val state get() = RunState.state
    private val app get() = getApplication<Application>()
    init {
        RunState.state = state.copy(
            readOnlyBuild = !BuildConfig.ALLOW_PARTITION_WRITES,
        )
        RunState.preparedJob = RunState.preparedJob ?: JobBackups.load(app)
        RunState.preparedArchive = RunState.preparedArchive ?: RunState.preparedJob?.archive?.takeIf { it.isFile && it.length() > 0 }
        runCatching { refreshImages() }.onFailure { RunState.append(it.message.orEmpty()) }
    }

    private fun refreshImages() {
        val efisp = Payloads.info(app, ImageTarget.EFISP)
        val abl = Payloads.info(app, ImageTarget.ABL)
        fun describe(info: Payloads.ImageInfo) = if (info.bytes > 0) "${info.name} · ${info.bytes} B\nSHA-256 ${info.sha256}" else info.name
        RunState.state = state.copy(efispFileName = efisp.name, ablFileName = abl.name,
            efispCustom = efisp.custom, ablCustom = abl.custom, efispImage = describe(efisp), ablImage = describe(abl))
    }

    private fun invalidatePreparedSelection() {
        RunState.clearPrepared()
        RunState.state = state.copy(recoveryPackageReady = false, backupExported = false, canRebootRecovery = false, canRebootBootloader = false, completedRequest = null,
            canExecute = BuildConfig.ALLOW_PARTITION_WRITES, backendMode = "")
    }

    private suspend fun cancelPreparedSelection() {
        val job = RunState.preparedJob ?: return
        withContext(Dispatchers.IO) {
            if (JobBackups.present(app, job)) JobBackups.releaseForNewPlan(app, Payloads.stageRuntime(app), job)
        }
        RunState.clearPrepared()
    }

    fun changeRequest(request: UnlockRequest) {
        if (state.busy || request == state.request) return
        if (RunState.preparedJob == null) {
            // Ordinary page/option changes are local selections, not running partition tasks.
            // Avoid pulsing the entire screen's busy/progress state on every settled swipe.
            RunState.preparedArchive = null
            RunState.state = state.copy(request = request, stage = "已选择 ${request.mode.label}",
                recoveryPackageReady = false, backupExported = false,
                canRebootRecovery = false, canRebootBootloader = false, completedRequest = null,
                canExecute = BuildConfig.ALLOW_PARTITION_WRITES, backendMode = "")
            return
        }
        // A prepared RAM job still needs its original cleanup/lock-release handshake.
        task("正在切换解锁方式") {
            cancelPreparedSelection()
            invalidatePreparedSelection()
            RunState.state = state.copy(request = request, stage = "已选择 ${request.mode.label}")
        }
    }

    fun selectCustomImage(target: String, uri: Uri) = task("正在保存自定义文件") {
        val imageTarget = requireNotNull(ImageTarget.fromId(target))
        cancelPreparedSelection()
        withContext(Dispatchers.IO) {
            Payloads.importImage(app, imageTarget, uri)
        }
        invalidatePreparedSelection()
        refreshImages()
        RunState.state = state.copy(stage = "自定义文件已保存；ABL 写入与 efisp 来源按页面所选选项分别处理")
    }

    fun resetCustomImage(target: String) = task("正在清除上传文件选择") {
        val imageTarget = requireNotNull(ImageTarget.fromId(target))
        cancelPreparedSelection()
        withContext(Dispatchers.IO) {
            Payloads.resetImage(app, imageTarget)
        }
        invalidatePreparedSelection()
        refreshImages()
        RunState.state = state.copy(stage = "${imageTarget.id} 已清除上传文件选择")
    }

    fun prepareTools() = task("正在准备分区与刷写工具") {
        val report = withContext(Dispatchers.IO) { RuntimeTools.inspect(app, Payloads.stageRuntime(app)) }
        RunState.tools(report)
        RunState.append(report.checks.joinToString("\n") { "${it.label} · ${it.source} · ${it.detail}" })
        RunState.state = state.copy(stage = if (report.coreReady) "工具准备完成" else "工具有缺失或运行错误，请查看警告",
            canExecute = BuildConfig.ALLOW_PARTITION_WRITES && !state.canRebootRecovery && !state.canRebootBootloader && state.completedRequest == null)
    }
    fun inspect(request: UnlockRequest = state.request) = task("正在以 root 只读检测") {
        RunState.state = state.copy(canRebootRecovery = false, canRebootBootloader = false, completedRequest = null)
        val dir = withGenerationLogs { log -> Payloads.stage(app, request, onLog = log) }
        val result = withContext(Dispatchers.IO) {
            val legacy = RootShell.run(scriptCommand(File(dir, "unlock.sh").absolutePath, "--check"), File(app.filesDir, "inspect.log"), 60)
            val legacyProbe = Probe.parse(legacy.output)
            if (legacyProbe.values["EFISP"].orEmpty() in listOf("absent", "", "unknown") && legacyProbe.root) {
                val online = RootShell.run(scriptCommand(File(dir, "online-create.sh").absolutePath, "--check"), File(app.filesDir, "online-inspect.log"), 60)
                RunState.nextScript = "online-create.sh"
                online
            } else {
                RunState.nextScript = "unlock.sh"
                legacy
            }
        }
        val probe = Probe.parse(result.output)
        RunState.append(result.output)
        check(probe.rootReported) { "分区检测脚本未正常返回权限结果，请查看上方日志" }
        val summary = listOf(
            "userdata：${probe.values["USERDATA"].orEmpty()}",
            "文件系统：${probe.values["FILESYSTEM"].orEmpty()}",
            "映射链：${probe.values["DATA_CHAIN"].orEmpty()}",
            "efisp：${probe.values["EFISP"].orEmpty().ifBlank { "尚未创建" }}",
            probe.blockReason,
        ).filter(String::isNotBlank).joinToString("\n")
        RunState.state = state.copy(
            rootStatus = if (probe.root) "Root 已取得" else "Root 待授权",
            deviceName = probe.values["DEVICE"].orEmpty().ifBlank { "请检查 root 授权" },
            slot = probe.slot,
            partitionSummary = summary,
            canExecute = BuildConfig.ALLOW_PARTITION_WRITES,
            backendMode = probe.mode,
            backupExported = false,
            canRebootRecovery = false, canRebootBootloader = false, completedRequest = null,
            stage = if (!BuildConfig.ALLOW_PARTITION_WRITES) "只读调试构建：分区写入已关闭"
                else "信息读取完成，可直接开始流程；执行时自动确定目标",
        )
    }
    fun prepareBundle(request: UnlockRequest = state.request) = task("正在准备工具与操作包") {
        RunState.state = state.copy(recoveryPackageReady = false)
        val dir = withGenerationLogs { log -> Payloads.stage(app, request, onLog = log) }
        val report = withContext(Dispatchers.IO) { RuntimeTools.inspect(app, dir) }
        RunState.tools(report)
        val mode = withContext(Dispatchers.IO) { RuntimeTools.mode(app) }
        RunState.state = state.copy(backendMode = mode)
        if (mode == "online-create" && report.onlineReady) {
            val currentHash = withContext(Dispatchers.IO) { Payloads.sha256(File(dir, "payloads.sha256")) }
            val engineHash = withContext(Dispatchers.IO) { Payloads.engineHash(dir) }
            var job = RunState.preparedJob
            val previous = job
            if (previous != null && !withContext(Dispatchers.IO) { JobBackups.present(app, previous) }) {
                RunState.clearPrepared()
                job = null
            }
            if (job != null && (job.payloadManifestSha256 != currentHash || job.engineSha256 != engineHash ||
                    withContext(Dispatchers.IO) { JobBackups.started(app, checkNotNull(job)) })) {
                val released = withContext(Dispatchers.IO) { JobBackups.releaseForNewPlan(app, dir, checkNotNull(job)) }
                RunState.append(released)
                RunState.clearPrepared()
                job = null
            }
            if (job == null) job = withContext(Dispatchers.IO) { JobBackups.prepare(app, dir) }
            val selectedJob = checkNotNull(job)
            RunState.preparedJob = selectedJob
            RunState.state = state.copy(slot = selectedJob.slot)
            withContext(Dispatchers.IO) { JobBackups.save(app, dir, selectedJob) }
            RunState.preparedArchive = selectedJob.archive
        }
        val file = withContext(Dispatchers.IO) { Payloads.bundle(app, RunState.preparedArchive, request) }
        RunState.state = state.copy(recoveryPackageReady = true, stage = "执行包已生成，点击导出保存")
        RunState.append("已生成 ${file.name}\n${Payloads.instructions}")
    }
    fun saveBundle(uri: Uri) = task("正在保存执行包") {
        withContext(Dispatchers.IO) {
            val file = File(app.cacheDir, "honor_ubl-Recovery.zip")
            check(state.recoveryPackageReady && file.isFile && file.length() > 0) { "执行包已被清理，请重新生成" }
            app.contentResolver.openOutputStream(uri).use { out -> checkNotNull(out); file.inputStream().use { it.copyTo(out) } }
        }
        val exported = state.backendMode != "online-create" || RunState.preparedJob != null
        app.getSharedPreferences("prepared_job", 0).edit().putBoolean("exported", exported).apply()
        RunState.state = state.copy(stage = "备份包已保存，请确认保存在电脑或独立外置存储", backupExported = exported)
    }
    fun saveLog(uri: Uri) = task("正在保存日志") {
        val log = state.logs.joinToString("\n")
        withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri).use { checkNotNull(it).write(log.toByteArray()) } }
        RunState.state = state.copy(stage = "日志已保存")
    }
    fun clearLogs() { if (!state.busy) RunState.state = state.copy(logs = emptyList()) }
    fun rebootRecovery() = requestReboot(UnlockCompletion.RECOVERY)
    fun rebootBootloader() = requestReboot(UnlockCompletion.BOOTLOADER)

    private fun requestReboot(completion: UnlockCompletion) {
        val completed = state.completedRequest
        val ready = completed?.completion == completion && when (completion) {
            UnlockCompletion.BOOTLOADER -> state.canRebootBootloader
            UnlockCompletion.RECOVERY -> state.canRebootRecovery
        }
        val command = completion.commandIfReady(ready, BuildConfig.ALLOW_PARTITION_WRITES, state.busy) ?: return
        task("正在按你的选择重启进入 ${completion.destination}") {
            try {
                val result = withContext(Dispatchers.IO) { RootShell.run(command, File(app.filesDir, "reboot.log"), 30) }
                check(result.exitCode == 0) { result.output.ifBlank { "${completion.destination} 重启命令未成功" } }
                RunState.state = state.copy(stage = if (completion == UnlockCompletion.BOOTLOADER)
                    "已请求进入 Fastboot" else "已请求进入 Recovery，请完成恢复出厂设置")
            } finally {
                RunState.state = state.copy(canRebootRecovery = false, canRebootBootloader = false)
            }
        }
    }
    private fun task(label: String, operation: suspend () -> Unit) {
        if (state.busy) return
        RunState.state = state.copy(busy = true, stage = label, progress = 0.1f)
        viewModelScope.launch {
            try { operation(); RunState.state = state.copy(progress = 1f) }
            catch (e: Exception) {
                RunState.append(e.message ?: e.javaClass.simpleName)
                // Inspection, file selection and export do not mutate partitions.
                // Preserve any existing write lockout; their errors must not disable direct mode.
                RunState.state = state.copy(stage = "操作已停止，请查看日志")
            } finally { RunState.state = state.copy(busy = false) }
        }
    }
}
