package net.hawyn.ablefisp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import net.hawyn.ablefisp.EditableTextItem
import net.hawyn.ablefisp.PatcherState
import net.hawyn.ablefisp.patch.PatchMode
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun PatcherApp(
    state: PatcherState,
    onImport: () -> Unit,
    onExtract: () -> Unit,
    onCheckRoot: () -> Unit,
    onPatch: () -> Unit,
    onSaveImage: () -> Unit,
    onSaveOriginal: () -> Unit,
    onSaveReport: () -> Unit,
    onShare: () -> Unit,
    onCopyLog: () -> Unit,
    onClearMessage: () -> Unit,
    onPrepareTexts: () -> Unit,
    onUpdateText: (String, String) -> Unit,
    onResetText: (String) -> Unit,
    onApplyTexts: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    showTopBar: Boolean = true,
    wrapInTheme: Boolean = true,
    manageSystemBars: Boolean = false,
    onPatchModeChange: ((PatchMode) -> Unit)? = null,
    appName: String = "ABL-EFISP",
) {
    val controller = remember { ThemeController(ColorSchemeMode.System, keyColor = Color(0xFF3482FF)) }
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    val context = LocalContext.current
    if (manageSystemBars) DisposableEffect(view, dark) {
        val insets = context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
        val oldStatus = insets?.isAppearanceLightStatusBars
        val oldNavigation = insets?.isAppearanceLightNavigationBars
        insets?.isAppearanceLightStatusBars = !dark
        insets?.isAppearanceLightNavigationBars = !dark
        onDispose {
            insets?.let {
                if (oldStatus != null) it.isAppearanceLightStatusBars = oldStatus
                if (oldNavigation != null) it.isAppearanceLightNavigationBars = oldNavigation
            }
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); onClearMessage() }
    }
    val content: @Composable () -> Unit = {
        var page by rememberSaveable { mutableIntStateOf(0) }
        Scaffold(topBar = {
            if (showTopBar) TopAppBar(title = appName, largeTitle = appName,
                navigationIcon = { onBack?.let { back -> Button(onClick = back) { Text("返回") } } })
        }) { padding ->
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.widthIn(max = 800.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                        top = padding.calculateTopPadding() + 6.dp,
                        bottom = padding.calculateBottomPadding() + 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { Hero(appName) }
                    item { PageTabs(page, onChange = { page = it }) }
                    item { SourceCard(state, onImport, onExtract, onCheckRoot, onSaveOriginal) }
                    onPatchModeChange?.let { changeMode ->
                        item { PatchModeCard(state.patchMode, state.busy, changeMode) }
                    }
                    if (state.busy) item { WorkCard(state.stage) }
                    state.error?.let { error -> item { ErrorCard(error) } }
                    if (page == 0) {
                        item {
                            Panel {
                                PanelTitle("02", "生成 EFISP", "按当前镜像识别修补位置")
                                Spacer(Modifier.height(16.dp))
                                Body("解析 ABL 内的 EFI 程序，按功能定位修补位置，生成 efisp.img。")
                                Spacer(Modifier.height(10.dp))
                                Body("生成后可在该镜像的 Fastboot 中执行 fastboot oem superunlock；原 flashing unlock 流程保留。")
                                Spacer(Modifier.height(18.dp))
                                Button(onClick = onPatch, enabled = state.source != null && !state.busy,
                                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                                    Text(if (state.hasOutput) "从源镜像重新生成" else "分析并生成 efisp.img", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        if (state.result != null) item {
                            Panel {
                                val result = state.result
                                StatusBadge(if (result.alreadyPatched) "已具备修补特征" else "修补已完成", positive = true)
                                Spacer(Modifier.height(14.dp))
                                result.patches.forEachIndexed { index, match ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("✓", color = MiuixTheme.colorScheme.primary, fontSize = 18.sp,
                                            modifier = Modifier.width(28.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(match.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                            Text("RVA ${hex(match.rva)} · 文件 ${hex(match.fileOffset.toLong())}",
                                                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        }
                                        Text(if (match.alreadyApplied) "已具备" else "已应用", fontSize = 11.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                    if (index < result.patches.lastIndex) Divider()
                                }
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { page = 1; onPrepareTexts() }, enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth()) { Text("继续定制当前镜像的文字") }
                            }
                        }
                    } else {
                        item {
                            Panel {
                                PanelTitle("02", "文字定制", "Fastboot 提示与开机警告")
                                Spacer(Modifier.height(14.dp))
                                Body(if (state.hasOutput) "使用当前会话中的 efisp.img，原文和修改后文字可一起对照。"
                                    else "导入 ABL 时先完成 EFISP 修补；导入 EFISP 时直接读取提示文字。")
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = onPrepareTexts, enabled = state.source != null && !state.busy && state.textEntries.none { it.changed },
                                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                                    Text(if (state.textEntries.isEmpty()) "读取提示文字" else "重新读取当前镜像")
                                }
                                if (state.textSource.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp)); Caption(state.textSource)
                                }
                            }
                        }
                        items(state.textEntries, key = { "text-${it.id}" }) { entry ->
                            TextEditorCard(entry, state.busy, onUpdateText, onResetText)
                        }
                        if (state.textEntries.isNotEmpty()) item {
                            val changes = state.textEntries.count { it.changed }
                            Panel {
                                Text(if (changes == 0) "文字与当前镜像一致" else "$changes 项文字等待应用",
                                    fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                Spacer(Modifier.height(10.dp))
                                Body("确认后会同步处理文字存储、引用和已识别的长度参数，并生成新的 efisp.img。")
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = onApplyTexts,
                                    enabled = changes > 0 && state.textEntries.filter { it.changed }.all { it.valid } && !state.busy,
                                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                                    Text("确认文字修改", fontWeight = FontWeight.SemiBold)
                                }
                                if (state.textAppliedCount > 0) {
                                    Spacer(Modifier.height(10.dp)); Caption("本次会话已应用 ${state.textAppliedCount} 项文字修改")
                                }
                            }
                        }
                    }
                    if (state.hasOutput) item { OutputCard(state, onSaveImage, onShare, onSaveReport) }
                    val notes = ((state.result?.notes ?: emptyList()) + state.textNotes).distinct()
                    if (notes.isNotEmpty()) item {
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        Panel {
                            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("处理说明", fontWeight = FontWeight.Medium)
                                Text(if (expanded) "收起 −" else "展开 +", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
                            }
                            AnimatedVisibility(expanded) {
                                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    notes.forEach { Caption(it) }
                                }
                            }
                        }
                    }
                    item { LogCard(state.logs, onCopyLog) }
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Caption("$appName  ·  hawyn2018")
                            Spacer(Modifier.height(5.dp))
                            Text("本地处理 · 文件导出", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .7f))
                        }
                    }
                }
            }
        }
    }
    Box(modifier) {
        if (wrapInTheme) MiuixTheme(controller = controller) { content() } else content()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}

@Composable private fun Hero(appName: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        FirmwareMark()
        Spacer(Modifier.width(17.dp))
        Column(Modifier.weight(1f)) {
            Text("让镜像适配你的设备", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Body("$appName · 提取、修补与文字定制")
            Spacer(Modifier.height(9.dp))
            StatusBadge("ABL → EFISP", positive = true)
        }
    }
}

@Composable private fun FirmwareMark() {
    val primary = MiuixTheme.colorScheme.primary
    Box(Modifier.size(70.dp).clip(RoundedCornerShape(22.dp)).background(primary.copy(alpha = .11f)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(42.dp)) {
            val stroke = size.width * .065f
            drawRoundRect(primary, Offset(size.width * .13f, size.height * .13f), Size(size.width * .74f, size.height * .74f),
                CornerRadius(size.width * .12f), style = Stroke(stroke))
            for (fraction in listOf(.32f, .5f, .68f)) {
                val p = size.width * fraction
                drawLine(primary, Offset(p, 0f), Offset(p, size.height * .13f), stroke, StrokeCap.Round)
                drawLine(primary, Offset(p, size.height * .87f), Offset(p, size.height), stroke, StrokeCap.Round)
                drawLine(primary, Offset(0f, p), Offset(size.width * .13f, p), stroke, StrokeCap.Round)
                drawLine(primary, Offset(size.width * .87f, p), Offset(size.width, p), stroke, StrokeCap.Round)
            }
            drawLine(primary, Offset(size.width * .34f, size.height * .5f), Offset(size.width * .66f, size.height * .5f), stroke, StrokeCap.Round)
            drawLine(primary, Offset(size.width * .53f, size.height * .36f), Offset(size.width * .67f, size.height * .5f), stroke, StrokeCap.Round)
            drawLine(primary, Offset(size.width * .53f, size.height * .64f), Offset(size.width * .67f, size.height * .5f), stroke, StrokeCap.Round)
        }
    }
}

@Composable private fun PageTabs(page: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MiuixTheme.colorScheme.surface).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("镜像修补", "文字定制").forEachIndexed { index, title ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                .background(if (page == index) MiuixTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
                .clickable { onChange(index) }.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                Text(title, fontSize = 15.sp, fontWeight = if (page == index) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (page == index) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable private fun PatchModeCard(mode: PatchMode, busy: Boolean, onChange: (PatchMode) -> Unit) {
    Panel {
        Text("修补模式", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(13.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.background).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(PatchMode.FREEDOM to "Freedom", PatchMode.FREEDOM_FAKE_LOCK to "Freedom + 假回锁")
                .forEach { (option, title) ->
                    val selected = mode == option
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
                        .selectable(selected = selected, enabled = !busy, role = Role.RadioButton, onClick = { onChange(option) })
                        .padding(horizontal = 8.dp, vertical = 13.dp), contentAlignment = Alignment.Center) {
                        Text(title, fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
        }
        Spacer(Modifier.height(12.dp))
        Body(if (mode == PatchMode.FREEDOM_FAKE_LOCK)
            "Fastboot 保持解锁能力，系统启动报告锁定状态；同时阻止启动时请求上调回滚版本。"
        else "应用 Freedom 解锁补丁，保留 Fastboot 解锁能力，并阻止启动时请求上调回滚版本。")
        Body("普通 flashing unlock 保留确认，提交并独立读回持久状态，省略显式清数据请求。")
        Spacer(Modifier.height(7.dp))
        Caption("切换模式会清除当前输出和文字草稿，保留原始镜像。")
    }
}

@Composable private fun SourceCard(state: PatcherState, onImport: () -> Unit, onExtract: () -> Unit, onCheckRoot: () -> Unit, onSaveOriginal: () -> Unit) {
    Panel {
        PanelTitle("01", "镜像来源", "从当前设备提取，或选择本地文件")
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onExtract, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("提取当前槽位", fontSize = 14.sp) }
            Button(onClick = onImport, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("导入镜像文件", fontSize = 14.sp) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (state.rootReady) Color(0xFF35A66C) else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .5f)))
            Spacer(Modifier.width(8.dp))
            Text(state.rootStatus, modifier = Modifier.weight(1f), fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text("检测 Root", modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !state.busy, onClick = onCheckRoot).padding(8.dp),
                color = MiuixTheme.colorScheme.primary, fontSize = 12.sp)
        }
        state.source?.let { source ->
            Spacer(Modifier.height(13.dp)); Divider(); Spacer(Modifier.height(16.dp))
            Text(source.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Caption("${formatBytes(source.size)}${source.slot?.let { " · 槽位 ${it.removePrefix("_").uppercase()}" } ?: ""}")
            Spacer(Modifier.height(5.dp)); Caption(source.description)
            Spacer(Modifier.height(12.dp)); HashText(source.sha256)
            Spacer(Modifier.height(12.dp))
            Text("保存原始镜像", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !state.busy, onClick = onSaveOriginal).padding(vertical = 7.dp))
        }
    }
}

@Composable private fun TextEditorCard(entry: EditableTextItem, busy: Boolean, onUpdate: (String, String) -> Unit, onReset: (String) -> Unit) {
    Panel {
        Text(entry.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(7.dp))
        Caption("EFISP 文件偏移 ${hex(entry.fileOffset.toLong())} · RVA ${hex(entry.rva)}")
        if (entry.originalFileOffset != entry.fileOffset) {
            Spacer(Modifier.height(3.dp)); Caption("首次读取位置 ${hex(entry.originalFileOffset.toLong())} · RVA ${hex(entry.originalRva)}")
        }
        Spacer(Modifier.height(15.dp))
        BoxWithConstraints {
            if (maxWidth >= 580.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { OriginalText(entry.originalText) }
                    Column(Modifier.weight(1f)) { DraftText(entry, busy, onUpdate) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    OriginalText(entry.originalText)
                    DraftText(entry, busy, onUpdate)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (!entry.editable) "仅查看" else if (entry.changed) "等待应用" else if (entry.currentText != entry.originalText) "已应用" else "与读入文字一致",
                color = if (entry.changed) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
            if (entry.editable) Text("恢复原文", color = MiuixTheme.colorScheme.primary, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !busy) { onReset(entry.id) }.padding(8.dp))
        }
        entry.reason?.let { Spacer(Modifier.height(5.dp)); Caption(it) }
    }
}

@Composable private fun OriginalText(value: String) {
    Caption("原文字")
    Spacer(Modifier.height(7.dp))
    SelectionContainer {
        Text(value.ifEmpty { "（空）" }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.background).padding(13.dp), fontSize = 13.sp, lineHeight = 20.sp,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable private fun DraftText(entry: EditableTextItem, busy: Boolean, onUpdate: (String, String) -> Unit) {
    Caption("修改后文字")
    Spacer(Modifier.height(7.dp))
    if (entry.editable) {
        TextField(value = entry.draft, onValueChange = { onUpdate(entry.id, it) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp))
        Spacer(Modifier.height(7.dp))
        val length = entry.draft.toByteArray(Charsets.UTF_8).size
        Text("$length / ${entry.maxBytes} 字节 · 英文、数字、标点与换行", fontSize = 11.sp,
            color = if (entry.valid) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.error)
    } else {
        Text(entry.currentText, fontSize = 13.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable private fun OutputCard(state: PatcherState, onSave: () -> Unit, onShare: () -> Unit, onReport: () -> Unit) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("efisp.img", fontWeight = FontWeight.Bold, fontSize = 23.sp)
                Spacer(Modifier.height(6.dp)); Caption("${formatBytes(state.outputBytes)} · 已生成")
            }
            StatusBadge("已就绪", positive = true)
        }
        Spacer(Modifier.height(16.dp)); HashText(state.outputSha256)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onSave, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
            Text("保存 efisp.img", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onShare, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("分享镜像", fontSize = 13.sp) }
            Button(onClick = onReport, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("导出修补报告", fontSize = 13.sp) }
        }
    }
}

@Composable private fun WorkCard(stage: String) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp)); Text(stage, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(16.dp)); LinearProgressIndicator()
    }
}

@Composable private fun ErrorCard(message: String) {
    Panel {
        Text("本次处理提示", fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.error)
        Spacer(Modifier.height(9.dp))
        SelectionContainer { Text(message, fontSize = 13.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.error) }
    }
}

@Composable private fun LogCard(logs: List<String>, onCopy: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("处理记录", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).clickable { expanded = !expanded })
            Text(if (expanded) "收起" else "展开", color = MiuixTheme.colorScheme.primary, fontSize = 12.sp,
                modifier = Modifier.clickable { expanded = !expanded }.padding(8.dp))
            Text("复制", color = MiuixTheme.colorScheme.primary, fontSize = 12.sp,
                modifier = Modifier.clickable(enabled = logs.isNotEmpty(), onClick = onCopy).padding(8.dp))
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(if (logs.isEmpty()) "导入或提取镜像后，处理记录会显示在这里。" else logs.joinToString("\n"),
                    modifier = Modifier.fillMaxWidth(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(19.dp), content = content)
}
@Composable private fun PanelTitle(number: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(number, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = .10f)).padding(8.dp))
        Spacer(Modifier.width(11.dp))
        Column {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp)); Caption(subtitle)
        }
    }
}
@Composable private fun StatusBadge(text: String, positive: Boolean) {
    val color = if (positive) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .09f)).padding(horizontal = 9.dp, vertical = 5.dp))
}
@Composable private fun HashText(hash: String) {
    Caption("SHA-256")
    Spacer(Modifier.height(5.dp))
    SelectionContainer { Text(hash, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 17.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
}
@Composable private fun Body(text: String) {
    Text(text, fontSize = 13.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
@Composable private fun Caption(text: String) {
    Text(text, fontSize = 12.sp, lineHeight = 19.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
@Composable private fun Divider() {
    Box(Modifier.fillMaxWidth().height(.5.dp).background(MiuixTheme.colorScheme.dividerLine.copy(alpha = .7f)))
}
private fun hex(value: Long) = "0x${value.toString(16).uppercase()}"
private fun formatBytes(bytes: Int): String = if (bytes >= 1024 * 1024) "%.2f MiB".format(bytes / (1024.0 * 1024)) else "%.1f KiB".format(bytes / 1024.0)
