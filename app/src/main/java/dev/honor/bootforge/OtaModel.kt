package dev.honor.bootforge

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.honor.bootforge.core.UploadRole
import dev.honor.bootforge.core.EfispPatchMode
import dev.honor.bootforge.core.OtaUpdatePreparation
import dev.honor.bootforge.core.OtaDiagnosticsArchive
import dev.honor.bootforge.core.Payloads
import dev.honor.bootforge.core.RootShell
import dev.honor.bootforge.core.SystemUpdaterHandoff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class OtaAblSource(val label: String) {
    CURRENT_SLOT_ABL("当前槽位ABL"), BUILTIN("应用内置ABL"), UPLOADED("ABL自定义")
}
enum class OtaEfispSource(val label: String) {
    NONE("不操作 efisp"), UPLOADED_EFISP("直接刷入上传的 efisp"), UPLOADED_ABL("上传 ABL 修补"),
    OTHER_SLOT_ABL("对槽 ABL 修补"), BUILTIN_ABL("内置 ABL 修补"),
    CURRENT_SLOT_ABL("当前槽 ABL 修补"), EXISTING_EFISP("设备现有 efisp 增加新功能"),
    UPLOADED_EFISP_UPGRADE("上传 efisp 增加新功能")
}
enum class OtaPreset(val label: String) {
    STANDARD("标准OTA"), EXPERIMENTAL("尝鲜OTA"), CUSTOM("自定义OTA")
}
enum class OtaOperation { FULL, EFISP_ONLY, EXPORT_EFISP }

data class OtaRequest(val ablSource: OtaAblSource = OtaAblSource.BUILTIN,
    val efispSource: OtaEfispSource = OtaEfispSource.OTHER_SLOT_ABL,
    val patchMode: EfispPatchMode = EfispPatchMode.FAKE_LOCK,
    val preset: OtaPreset = OtaPreset.EXPERIMENTAL) {
    /** Preset buttons bind only the two sources; CUSTOM is a label, not a reset. */
    fun withPreset(value: OtaPreset): OtaRequest = when (value) {
        OtaPreset.STANDARD -> copy(preset = value, ablSource = OtaAblSource.BUILTIN,
            efispSource = OtaEfispSource.CURRENT_SLOT_ABL)
        OtaPreset.EXPERIMENTAL -> copy(preset = value, ablSource = OtaAblSource.BUILTIN,
            efispSource = OtaEfispSource.OTHER_SLOT_ABL)
        OtaPreset.CUSTOM -> copy(preset = value)
    }

    /** A manual source edit leaves preset mode. Re-selecting a source is a no-op. */
    fun editedFrom(previous: OtaRequest): OtaRequest {
        val sourcesChanged = ablSource != previous.ablSource || efispSource != previous.efispSource
        val explicitPresetSelection = preset != previous.preset
        val presetMatchesSources = preset == OtaPreset.CUSTOM || this == withPreset(preset)
        return if ((sourcesChanged && !explicitPresetSelection) || !presetMatchesSources)
            copy(preset = OtaPreset.CUSTOM) else this
    }

    fun requiredUploads(operation: OtaOperation): Set<UploadRole> = buildSet {
        if (operation == OtaOperation.FULL && ablSource == OtaAblSource.UPLOADED) add(UploadRole.ABL_FLASH)
        when (efispSource) {
            OtaEfispSource.UPLOADED_ABL -> add(UploadRole.EFISP_ABL)
            OtaEfispSource.UPLOADED_EFISP, OtaEfispSource.UPLOADED_EFISP_UPGRADE -> add(UploadRole.EFISP_IMAGE)
            else -> Unit
        }
    }

    fun encode() = listOf("v2", preset.name, ablSource.name, efispSource.name, patchMode.name).joinToString(":")
    companion object {
        fun decode(value: String): OtaRequest {
            val p = value.split(':')
            if (p.size == 4 && p[0] in listOf("true", "false")) {
                // v1 requests are transient service inputs. Preserve explicit uploads/built-ins;
                // recognized standard/experimental pairs follow the current preset bindings.
                require(p[1] in listOf("AUTO", "CURRENT_SLOT_ABL", "BUILTIN", "UPLOADED"))
                val abl = if (p[0] == "false" || p[1] == "AUTO") OtaAblSource.CURRENT_SLOT_ABL
                    else OtaAblSource.valueOf(p[1])
                val efisp = OtaEfispSource.valueOf(p[2])
                val legacy = OtaRequest(abl, efisp, EfispPatchMode.valueOf(p[3]), OtaPreset.CUSTOM)
                return when {
                    abl != OtaAblSource.CURRENT_SLOT_ABL -> legacy
                    efisp == OtaEfispSource.CURRENT_SLOT_ABL -> legacy.withPreset(OtaPreset.STANDARD)
                    efisp == OtaEfispSource.OTHER_SLOT_ABL -> legacy.withPreset(OtaPreset.EXPERIMENTAL)
                    else -> legacy
                }
            }
            require(p.size == 5 && p[0] == "v2") { "OTA 选项格式无效" }
            return OtaRequest(OtaAblSource.valueOf(p[2]), OtaEfispSource.valueOf(p[3]),
                EfispPatchMode.valueOf(p[4]), OtaPreset.valueOf(p[1]))
        }
    }
}
data class OtaUiState(val busy: Boolean = false, val stage: String = "系统 OTA 安装完成后、重启前手动执行",
    val logs: List<String> = emptyList(), val progress: Float = 0f, val slot: String = "",
    val outputDirectory: String = "", val error: String = "", val completed: Boolean = false,
    val efispPatchFailureHint: String = "", val completionMarkerReady: Boolean? = null,
    val operation: OtaOperation = OtaOperation.FULL, val exportFilePath: String = "",
    val request: OtaRequest = OtaRequest(), val ablFileName: String = "尚未上传用于刷写的 ABL",
    val efispAblFileName: String = "尚未上传用于修补 EFISP 的 ABL", val efispFileName: String = "尚未上传 efisp",
    val preparingUpdate: Boolean = false, val updatePreparationMessage: String = "", val updatePreparationFailed: Boolean = false,
    val exportingDiagnostics: Boolean = false, val diagnosticsMessage: String = "")
object OtaState {
    var state by mutableStateOf(OtaUiState())
    fun append(value: String) { state = state.copy(logs = state.logs + value.lines()) }
}
class OtaModel(application: Application) : AndroidViewModel(application) {
    val state get() = OtaState.state
    private val app get() = getApplication<Application>()
    init {
        if (!state.busy) {
            val saved = runCatching { app.getSharedPreferences("ota-selection", 0).getString("request", null)?.let(OtaRequest::decode) }.getOrNull()
            fun name(role: UploadRole) = runCatching { OtaInputs.displayName(app, role.key) }.getOrElse { "已选文件记录无效，请重新上传${role.label}" }
            OtaState.state = state.copy(request = saved ?: state.request, ablFileName = name(UploadRole.ABL_FLASH),
                efispAblFileName = name(UploadRole.EFISP_ABL), efispFileName = name(UploadRole.EFISP_IMAGE))
        }
    }
    fun requestChanged(request: OtaRequest) {
        if (state.busy || RunState.state.busy || BackupState.state.busy) return
        val selected = request.editedFrom(state.request)
        if (!app.getSharedPreferences("ota-selection", 0).edit().putString("request", selected.encode()).commit()) {
            OtaState.state = state.copy(error = "OTA 选项保存失败"); return
        }
        OtaState.state = state.copy(request = selected, completed = false, error = "", efispPatchFailureHint = "")
    }
    fun clearLogs() { if (!state.busy) OtaState.state = state.copy(logs = emptyList()) }
    fun exportDiagnostics(uri: Uri) {
        if (state.busy || RunState.state.busy || BackupState.state.busy) return
        if (uri.scheme != "content") return
        val clicked = System.currentTimeMillis()
        val zone = java.util.TimeZone.getDefault()
        val console = state.logs.joinToString("\n")
        val report = buildString {
            appendLine("hnubl=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("package=${app.packageName}")
            appendLine("android_sdk=${android.os.Build.VERSION.SDK_INT}")
            appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("build=${android.os.Build.DISPLAY}")
            appendLine("operation=${state.operation}; completed=${state.completed}; marker_ready=${state.completionMarkerReady}")
            appendLine("selection=${state.request.encode()}")
            appendLine("stage=${state.stage}")
            appendLine("error=${state.error}")
            runCatching {
                @Suppress("DEPRECATION")
                val updater = app.packageManager.getPackageInfo("com.hihonor.ouc", 0)
                appendLine("updater=${updater.versionName} (${updater.longVersionCode})")
            }.onFailure { appendLine("updater_metadata=${it.javaClass.simpleName}: ${it.message}") }
        }
        // Export is isolated from the OTA completion/result fields and never calls
        // BEGIN, RESET, CONSUME, a reboot or a partition transaction.
        OtaState.state = state.copy(busy = true, exportingDiagnostics = true, diagnosticsMessage = "正在收集 OTA 诊断日志…")
        RunState.state = RunState.state.copy(busy = true)
        viewModelScope.launch {
            try {
                val complete = withContext(Dispatchers.IO) {
                    val result = OtaDiagnosticsArchive.collect(File(app.cacheDir, "ota-diagnostics"), report,
                        console, clicked, zone) { command, output, errors -> RootShell.capture(command, output, errors, 45) }
                    try {
                        val expected = Payloads.sha256(result.archive)
                        checkNotNull(app.contentResolver.openOutputStream(uri, "wt")) { "诊断文件打开失败" }.use { output ->
                            result.archive.inputStream().use { it.copyTo(output) }
                        }
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        var bytes = 0L
                        checkNotNull(app.contentResolver.openInputStream(uri)) { "诊断文件回读失败" }.use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                bytes += count; digest.update(buffer, 0, count)
                            }
                        }
                        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        check(bytes == result.archive.length() && actual == expected) { "诊断文件校验失败，请重新导出" }
                        result.rootEvidenceComplete
                    } finally { result.archive.delete() }
                }
                OtaState.state = state.copy(diagnosticsMessage = if (complete) "OTA 诊断日志已导出，可直接发送此 ZIP 排查问题"
                    else "诊断日志已导出，部分 Root 证据未取得；缺失原因已写入 ZIP")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                OtaState.state = state.copy(diagnosticsMessage = "诊断日志导出失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                OtaState.state = state.copy(busy = false, exportingDiagnostics = false)
                RunState.state = RunState.state.copy(busy = false)
            }
        }
    }
    fun prepareSystemUpdate() {
        if (state.busy || RunState.state.busy || BackupState.state.busy) return
        // Reserve synchronously so repeated taps and service starts see the same busy state.
        OtaState.state = state.copy(busy = true, preparingUpdate = true,
            updatePreparationMessage = "", updatePreparationFailed = false)
        RunState.state = RunState.state.copy(busy = true)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val directory = File(app.filesDir, "ota-module-install")
                    val staged = OtaUpdatePreparation.stage(directory, app.assets::open)
                    OtaUpdatePreparation.execute(directory, staged.archive, staged.sha256, onLog = { line ->
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            OtaState.append(line)
                            if (line.startsWith("[更新模块]") && line.endsWith("…"))
                                OtaState.state = state.copy(updatePreparationMessage = line.removePrefix("[更新模块] "))
                        }
                    })
                }
                OtaState.state = state.copy(updatePreparationMessage = result.message,
                    updatePreparationFailed = !result.successful)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = "更新模块准备失败：${error.message ?: error.javaClass.simpleName}"
                OtaState.append(message)
                OtaState.state = state.copy(updatePreparationMessage = message, updatePreparationFailed = true)
            } finally {
                OtaState.state = state.copy(busy = false, preparingUpdate = false)
                RunState.state = RunState.state.copy(busy = false)
            }
        }
    }
    fun importAbl(uri: Uri) = importImage(UploadRole.ABL_FLASH.key, uri)
    fun importEfispAbl(uri: Uri) = importImage(UploadRole.EFISP_ABL.key, uri)
    fun importEfisp(uri: Uri) = importImage(UploadRole.EFISP_IMAGE.key, uri)
    private fun importImage(kind: String, uri: Uri) {
        if (state.busy || RunState.state.busy || BackupState.state.busy) return
        OtaState.state = state.copy(busy = true, error = "", completed = false, efispPatchFailureHint = "")
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { OtaInputs.importImage(app, kind, uri) }
                OtaState.state = when (kind) {
                    UploadRole.ABL_FLASH.key -> state.copy(ablFileName = name)
                    UploadRole.EFISP_ABL.key -> state.copy(efispAblFileName = name)
                    else -> state.copy(efispFileName = name)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Throwable) {
                if (e is VirtualMachineError || e is ThreadDeath) throw e
                OtaState.state = state.copy(error = e.message ?: e.javaClass.simpleName)
            }
            finally { OtaState.state = state.copy(busy = false) }
        }
    }
    fun onStart() = startOperation(OtaOperation.FULL)
    fun onWriteEfispOnly() = startOperation(OtaOperation.EFISP_ONLY)
    fun exportEfisp(uri: Uri) = startOperation(OtaOperation.EXPORT_EFISP, uri)
    private fun startOperation(operation: OtaOperation, exportUri: Uri? = null) {
        if (state.busy || RunState.state.busy || BackupState.state.busy) return
        if (operation != OtaOperation.FULL && state.request.efispSource == OtaEfispSource.NONE) {
            OtaState.state = state.copy(error = "请先选择 efisp 处理方式")
            return
        }
        if (operation == OtaOperation.EXPORT_EFISP && state.request.efispSource == OtaEfispSource.UPLOADED_EFISP) {
            OtaState.state = state.copy(error = "直接刷入上传的 efisp 无需生成，请选择修补来源后导出")
            return
        }
        if (RunState.preparedJob != null || JobBackups.load(app) != null) {
            OtaState.state = state.copy(error = "存在待执行或待释放的解锁任务，请先完成原任务清理")
            return
        }
        if (operation == OtaOperation.EXPORT_EFISP || BuildConfig.ALLOW_PARTITION_WRITES) {
            try { OtaService.start(app, state.request, operation, exportUri) }
            catch (e: Exception) { OtaState.state = state.copy(error = e.message.orEmpty()) }
        }
    }
    fun returnToSystemUpdater() {
        if (!OtaCompletionPolicy.available(state.operation, OtaCompletionAction.RETURN_TO_UPDATER,
                state.completed, state.busy || RunState.state.busy || BackupState.state.busy)) return
        OtaState.state = state.copy(busy = true)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = withContext(Dispatchers.IO) {
                    SystemUpdaterHandoff.open(app, File(app.filesDir, "ota-module/updater-open.log"))
                }
                result.logs.forEach(OtaState::append)
            } finally { OtaState.state = state.copy(busy = false) }
        }
    }
}
