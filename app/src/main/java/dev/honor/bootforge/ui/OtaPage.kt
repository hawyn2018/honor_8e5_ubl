package dev.honor.bootforge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.honor.bootforge.BuildConfig
import dev.honor.bootforge.OtaAblSource
import dev.honor.bootforge.OtaEfispSource
import dev.honor.bootforge.OtaOperation
import dev.honor.bootforge.OtaCompletionPolicy
import dev.honor.bootforge.OtaCompletionAction
import dev.honor.bootforge.OtaPreset
import dev.honor.bootforge.OtaRequest
import dev.honor.bootforge.OtaUiState
import dev.honor.bootforge.core.EfispPatchMode
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class OtaActions(
    val onPrepareSystemUpdate: () -> Unit = {},
    val onRequestChanged: (OtaRequest) -> Unit = {},
    val onSelectAbl: () -> Unit = {},
    val onSelectEfispAbl: () -> Unit = {},
    val onSelectEfisp: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onWriteEfispOnly: () -> Unit = {},
    val onExportEfisp: () -> Unit = {},
    val onClearLogs: () -> Unit = {},
    val onExportDiagnostics: () -> Unit = {},
    val onReturnToSystemUpdater: () -> Unit = {},
)

@Composable
fun OtaPage(state: OtaUiState, operationBusy: Boolean, actions: OtaActions, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val enabled = !operationBusy && !state.busy
    val request = state.request
    var confirm by remember { mutableStateOf<String?>(null) }
    val needsAbl = request.ablSource == OtaAblSource.UPLOADED
    val needsEfisp = request.efispSource in listOf(OtaEfispSource.UPLOADED_EFISP, OtaEfispSource.UPLOADED_EFISP_UPGRADE)
    val handlesEfisp = request.efispSource != OtaEfispSource.NONE
    val patchesEfisp = handlesEfisp && request.efispSource != OtaEfispSource.UPLOADED_EFISP
    val titleStyle = TextStyle(fontSize = 34.sp, lineHeight = 45.sp, fontWeight = FontWeight.Bold)
    val textMeasurer = rememberTextMeasurer()
    val titleWidth = textMeasurer.measure("更新系统，保留自由", titleStyle, softWrap = false).size.width
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Text(if (titleWidth <= constraints.maxWidth) "更新系统，保留自由" else "更新系统，\n保留自由", style = titleStyle)
                }
                Spacer(Modifier.height(10.dp))
                OtaRebootWarning()
            }
        }
        item(key = "prepare_system_update") {
            Panel {
                Button(onClick = actions.onPrepareSystemUpdate, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (state.preparingUpdate) "正在检查或安装…" else "升级前请点此处安装或更新模块",
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Spacer(Modifier.height(10.dp))
                OtaHint("检查已安装与待更新模块版本，内置版本较新时安装并即时启用；相同或更高版本保留。启用时会重新打开软件更新应用，无需重启手机。")
                if (state.updatePreparationMessage.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.updatePreparationMessage, fontSize = 12.sp, lineHeight = 21.sp,
                        color = if (state.updatePreparationFailed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary)
                }
            }
        }
        item {
            Panel {
                Text("保留锁态 OTA", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                OtaHint("请先完成系统 OTA 安装，再在重启前手动执行。按所选来源处理对槽 ABL 与共享 efisp，并保留所需恢复备份。")
                Spacer(Modifier.height(10.dp))
                val backupDirectory = buildAnnotatedString {
                    append("将自动备份，备份目录为：\n")
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = MiuixTheme.colorScheme.primary)) {
                        append("Download/hnubl/hnubl_ota_backup_yyyyMMdd_HHmmss")
                    }
                }
                SelectionContainer { Text(backupDirectory, fontSize = 12.sp, lineHeight = 21.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                Spacer(Modifier.height(18.dp))
                Button(onClick = { confirm = "ota" }, enabled = enabled && BuildConfig.ALLOW_PARTITION_WRITES,
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (state.busy) "正在处理…" else "保留锁态 OTA", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                Text("操作可能需要数分钟，请耐心等待", fontSize = 13.sp, lineHeight = 22.sp, color = Color(0xFFE53935))
                Spacer(Modifier.height(14.dp))
                Text(state.stage, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                if (state.slot.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    OtaHint("当前槽位 ${state.slot.uppercase()} · 目标槽位 ${if (state.slot.trimStart('_') == "a") "B" else "A"}")
                }
                if (state.busy || state.progress > 0f) {
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primary.copy(alpha = .12f))) {
                        Box(Modifier.fillMaxWidth(state.progress.coerceIn(0f, 1f)).height(5.dp).background(MiuixTheme.colorScheme.primary))
                    }
                }
                if (state.outputDirectory.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    SelectionContainer { Text(state.outputDirectory, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 18.sp) }
                }
                if (state.error.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(state.error, fontSize = 12.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.error)
                }
                if (state.completed && OtaCompletionPolicy.action(state.operation) != OtaCompletionAction.NONE) {
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = actions.onReturnToSystemUpdater,
                        enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("返回软件更新") }
                }
            }
        }
        item {
            Panel {
                OtaSectionTitle("OTA 方式")
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OtaPreset.entries.forEach { preset ->
                        PageButton(preset.label, request.preset == preset, Modifier.weight(1f), enabled) {
                            actions.onRequestChanged(request.withPreset(preset))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OtaHint(when (request.preset) {
                    OtaPreset.STANDARD -> "使用应用内置 ABL，并由当前槽位 ABL 修补 efisp。"
                    OtaPreset.EXPERIMENTAL -> "使用应用内置 ABL，并由对槽 ABL 修补 efisp。"
                    OtaPreset.CUSTOM -> "按下方所选来源执行。"
                })
                Spacer(Modifier.height(16.dp))
                OtaSectionTitle("ABL 刷写来源")
                Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OtaAblSource.entries.forEach { source ->
                        SourceChoice(source.label, request.ablSource == source, enabled) {
                            actions.onRequestChanged(request.copy(ablSource = source))
                        }
                    }
                }
                if (needsAbl) {
                    Spacer(Modifier.height(12.dp))
                    OtaHint(state.ablFileName)
                    Button(onClick = actions.onSelectAbl, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("上传用于刷写的 ABL") }
                }
            }
        }
        item {
            Panel {
                OtaSectionTitle("efisp 处理方式")
                Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OtaEfispSource.entries.forEach { source ->
                        SourceChoice(source.label, request.efispSource == source, enabled) {
                            actions.onRequestChanged(request.copy(efispSource = source))
                        }
                    }
                }
                if (request.efispSource == OtaEfispSource.UPLOADED_ABL) {
                    Spacer(Modifier.height(12.dp))
                    OtaHint(state.efispAblFileName)
                    Button(onClick = actions.onSelectEfispAbl, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text("上传用于修补 EFISP 的 ABL")
                    }
                }
                if (needsEfisp) {
                    Spacer(Modifier.height(12.dp))
                    OtaHint(state.efispFileName)
                    Button(onClick = actions.onSelectEfisp, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("选择 efisp 文件") }
                }
                if (handlesEfisp) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { confirm = "efisp" }, enabled = enabled && BuildConfig.ALLOW_PARTITION_WRITES,
                        modifier = Modifier.fillMaxWidth()) { Text("仅刷写efisp") }
                    OtaEfispActionWarning("(仅为单独更新efisp功能时使用，仅使用此功能无法保锁态OTA)")
                }
                if (patchesEfisp) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = actions.onExportEfisp, enabled = enabled,
                        modifier = Modifier.fillMaxWidth()) { Text("导出efisp") }
                }
                // Retain an actionable failure even if this source has no export button.
                // The task/model owns clearing it; a busy-state transition must not hide it.
                if (state.efispPatchFailureHint.isNotBlank()) {
                    OtaEfispActionWarning(state.efispPatchFailureHint)
                }
                if (patchesEfisp) {
                    Spacer(Modifier.height(if (state.efispPatchFailureHint.isNotBlank()) 10.dp else 16.dp))
                    OtaSectionTitle("修补模式")
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EfispPatchMode.entries.forEach { mode ->
                            PageButton(mode.label, request.patchMode == mode, Modifier.weight(1f), enabled) {
                                actions.onRequestChanged(request.copy(patchMode = mode))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OtaHint("文件不限制扩展名。所需 ABL 会在覆盖前提取；增加新功能时按原镜像的功能和数据流重新识别。")
            }
        }
        item {
            Panel {
                OtaSectionTitle("运行日志")
                val fullLog = state.logs.joinToString("\n")
                SelectionContainer {
                    Box(Modifier.fillMaxWidth().height(280.dp).verticalScroll(rememberScrollState())) {
                        Text(fullLog.ifBlank { "等待操作…" }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 17.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("hnubl OTA 日志", fullLog))
                        Toast.makeText(context, "已复制全部日志", Toast.LENGTH_SHORT).show()
                    }, enabled = fullLog.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("复制全部") }
                    Button(onClick = actions.onClearLogs, enabled = enabled && fullLog.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("清空日志") }
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = actions.onExportDiagnostics, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.exportingDiagnostics) "正在导出诊断日志…" else "导出 OTA 诊断日志")
                }
                Spacer(Modifier.height(6.dp))
                OtaHint("收集完成标记、切槽计划、模块状态与详细事件，保存为 ZIP，便于远程排查。")
                if (state.diagnosticsMessage.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    OtaHint(state.diagnosticsMessage)
                }
            }
        }
        item(key = "ota_footer") { ProjectRepositoryFooter() }
    }
    confirm?.let { kind ->
        Dialog(onDismissRequest = { confirm = null }) {
            Panel {
                Text(if (kind == "efisp") "仅刷写 efisp？" else "开始保留锁态 OTA？", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                OtaHint(if (kind == "efisp") "备份原 efisp，按所选来源准备镜像，然后写入 efisp 分区。"
                    else "请确认系统 OTA 已安装完成。将备份所需分区，并按当前选项写入对槽引导分区和共享 efisp。")
                Spacer(Modifier.height(18.dp))
                Button(onClick = { confirm = null; if (kind == "efisp") actions.onWriteEfispOnly() else actions.onStart() }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("开始执行") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { confirm = null }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
}

@Composable
private fun OtaRebootWarning() {
    val warning = "注意：本工具无修补init_boot功能，在本工具执行完ota流程重启前，请进入kernelsu安装到未使用的槽位后再重启！"
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val size = remember(constraints.maxWidth, density.density, density.fontScale, measurer) {
            (0..16).map { 12f - it * .25f }.firstOrNull { candidate ->
                measurer.measure(warning, TextStyle(fontSize = candidate.sp, lineHeight = (candidate * 1.75f).sp),
                    constraints = Constraints(maxWidth = constraints.maxWidth)).lineCount <= 2
            } ?: 8f
        }
        Text(warning, style = TextStyle(fontSize = size.sp, lineHeight = (size * 1.75f).sp), color = Color(0xFFE53935))
    }
}

@Composable
private fun OtaSectionTitle(title: String) {
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun OtaHint(text: String) {
    Text(text, fontSize = 12.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

/** Match action warnings in font, automatic line spacing and distance from the button. */
@Composable
private fun OtaEfispActionWarning(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, fontSize = 12.sp, lineHeight = 21.sp, color = Color(0xFFE53935))
}
