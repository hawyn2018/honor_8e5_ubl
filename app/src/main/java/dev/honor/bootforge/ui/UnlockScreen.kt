package dev.honor.bootforge.ui

import android.os.Build

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.honor.bootforge.BackupUiState
import dev.honor.bootforge.BuildConfig
import dev.honor.bootforge.core.EfispProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class UnlockUiState(
    val rootStatus: String = "执行时获取",
    val deviceName: String = "执行时读取",
    val slot: String = "",
    val partitionSummary: String = "可直接开始流程，或先读取 userdata 与 efisp 信息",
    val efispFileName: String = "efisp.img",
    val ablFileName: String = "abl.img",
    val efispCustom: Boolean = false,
    val ablCustom: Boolean = false,
    val efispImage: String = "efisp.img · 内置镜像",
    val ablImage: String = "abl.img · 内置镜像",
    val busy: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "准备就绪，可直接开始流程",
    val logs: List<String> = emptyList(),
    val recoveryPackageReady: Boolean = false,
    val canExecute: Boolean = BuildConfig.ALLOW_PARTITION_WRITES,
    val readOnlyBuild: Boolean = !BuildConfig.ALLOW_PARTITION_WRITES,
    val backendMode: String = "",
    val backupExported: Boolean = false,
    val canRebootRecovery: Boolean = false,
    val toolsSummary: String = "准备时检查内置分区工具",
    val toolWarnings: List<String> = emptyList(),
)

data class UnlockActions(
    val onInspect: () -> Unit = {},
    val onInspectDirect: () -> Unit = onInspect,
    val onInspectCustom: () -> Unit = onInspect,
    val onPrepareTools: () -> Unit = {},
    val onSelectEfisp: () -> Unit = {},
    val onSelectAbl: () -> Unit = {},
    val onResetEfisp: () -> Unit = {},
    val onResetAbl: () -> Unit = {},
    val onBuildRecovery: () -> Unit = {},
    val onExecute: () -> Unit = {},
    val onExecuteDirect: () -> Unit = onExecute,
    val onExecuteCustom: () -> Unit = onExecute,
    val onBuildRecoveryDirect: () -> Unit = onBuildRecovery,
    val onBuildRecoveryCustom: () -> Unit = onBuildRecovery,
    val onSharePackage: () -> Unit = {},
    val onShareLogs: () -> Unit = {},
    val onClearLogs: () -> Unit = {},
    val onRebootRecovery: () -> Unit = {},
)

private val Accent: Color
    @Composable get() = MiuixTheme.colorScheme.primary
private val Green = Color(0xFF23A87A)

@Composable
fun UnlockScreen(
    state: UnlockUiState,
    actions: UnlockActions,
    backupState: BackupUiState = BackupUiState(),
    onBackup: () -> Unit = {},
    appearance: AppearanceSettings = AppearanceSettings(),
    onAppearanceChange: (AppearanceSettings) -> Unit = {},
    rootBusy: Boolean = false,
    rootOpenRequest: Int = 0,
    rootContent: @Composable (PaddingValues, Boolean) -> Unit = { _, _ -> },
) {
    val pagerState = rememberPagerState(initialPage = if (rootOpenRequest > 0) 0 else 2, pageCount = { 4 })
    val navigationScope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val selectPage: (Int) -> Unit = { destination ->
        navigationJob?.cancel()
        navigationJob = navigationScope.launch { pagerState.animateScrollToPage(destination) }
    }
    LaunchedEffect(rootOpenRequest) { if (rootOpenRequest > 0) selectPage(0) }
    val page = pagerState.currentPage
    var customPage by rememberSaveable { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var confirmCustom by remember { mutableStateOf(false) }
    var showReboot by remember { mutableStateOf(false) }
    val target = targetPartition(state.slot)
    val operationState = state.copy(busy = state.busy || backupState.busy || rootBusy)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentPadding = PaddingValues(
        start = 20.dp, end = 20.dp,
        top = statusTop + 60.dp,
        bottom = navigationBottom + if (appearance.floatingBottomBar) 110.dp else 94.dp,
    )
    val backdrop = if (Build.VERSION.SDK_INT >= 33) GlassRendererApi33.rememberBackdrop() else null
    val pageStates = rememberSaveableStateHolder()
    val navigating = pagerState.isScrollInProgress || pagerState.targetPage != 2
    BackHandler(enabled = page != 2 || navigating || customPage) {
        if (page != 2 || navigating) selectPage(2) else customPage = false
    }
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        // Record an opaque page background too, so blur edges never sample transparent gaps.
        // Navigation stays sharp above its captured backdrop.
        Box(Modifier.fillMaxSize()
            .let { if (backdrop != null && Build.VERSION.SDK_INT >= 33) backdrop.capture(it) else it }
            .background(MiuixTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { pageIndex -> pageIndex },
            ) { pageIndex ->
                pageStates.SaveableStateProvider(pageIndex) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        when (pageIndex) {
                            0 -> rootContent(contentPadding, operationState.busy)
                            1 -> BackupPage(backupState, operationState.busy, onBackup, contentPadding)
                            3 -> Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                                SettingsPage(appearance, onAppearanceChange, contentPadding)
                            }
                            else -> UnlockPage(operationState, actions, target, customPage, contentPadding,
                                onCustomPage = { customPage = it },
                                onConfirm = { confirmCustom = customPage; showConfirm = true },
                                onReboot = { showReboot = true })
                        }
                    }
                }
            }
        }
        GlassTopBar(
            backdrop = backdrop,
            title = listOf("提权", "分区备份", "刷入gbl解锁bl", "设置")[page],
            blurDp = appearance.topBlurDp,
            statusBarHeight = statusTop,
        )
        GlassBottomBar(
            backdrop, page,
            { (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 3f) },
            appearance, navigationBottom, Modifier.align(Alignment.BottomCenter),
            onSelect = selectPage,
        )
    }
    if (showConfirm) {
        val confirmationState = if (confirmCustom) operationState else operationState.copy(
            efispFileName = "efisp.img", ablFileName = "abl.img", efispCustom = false, ablCustom = false,
        )
        ConfirmWriteDialog(confirmationState, target, onDismiss = { showConfirm = false }) {
            showConfirm = false
            if (confirmCustom) actions.onExecuteCustom() else actions.onExecuteDirect()
        }
    }
    if (showReboot) {
        Dialog(onDismissRequest = { showReboot = false }) {
            Panel {
                Text("现在重启到 Recovery？", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                MutedText("进入后恢复出厂设置会清空全部用户数据和应用内备份。如需保留分区备份，请先导出操作包。选择稍后将停留在当前页面。", lineHeight = 22)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { showReboot = false; actions.onRebootRecovery() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("确认重启 Recovery") }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { showReboot = false }, modifier = Modifier.fillMaxWidth()) { Text("稍后重启") }
            }
        }
    }
}

@Composable
private fun UnlockPage(
    state: UnlockUiState, actions: UnlockActions, target: String?, customPage: Boolean,
    contentPadding: PaddingValues, onCustomPage: (Boolean) -> Unit, onConfirm: () -> Unit, onReboot: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(MiuixTheme.colorScheme.surface).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PageButton("直接解锁", !customPage, Modifier.weight(1f)) { onCustomPage(false) }
                PageButton("自定义解锁", customPage, Modifier.weight(1f)) { onCustomPage(true) }
            }
        }
        item {
            Column(Modifier.padding(start = 4.dp, bottom = 3.dp)) {
                Text(if (customPage) "使用自己的镜像" else "一切，准备就绪", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                MutedText(if (customPage) "分别选择 efisp 与 ABL 文件，未选择的项目沿用内置镜像。" else "使用内置 efisp.img 和 abl.img，按顺序完成分区与刷入。", lineHeight = 21)
            }
        }
        if (customPage) {
            item {
                CustomImagePanel("PE", "efisp 分区镜像", "写入 efisp · 容量按实际布局", state.efispFileName, state.efispImage,
                    state.efispCustom, state.busy, actions.onSelectEfisp, actions.onResetEfisp)
            }
            item {
                CustomImagePanel("ABL", "ABL 分区镜像", "写入 ${target ?: "当前槽位 abl 分区"}", state.ablFileName, state.ablImage,
                    state.ablCustom, state.busy, actions.onSelectAbl, actions.onResetAbl)
            }
            item { MutedText("从文件管理中选择任意文件，不限制扩展名。镜像适用性由使用者判断。", Modifier.padding(horizontal = 4.dp), fontSize = 12, lineHeight = 20) }
        } else {
            item { HeroCard(target, state) }
            item {
                Panel {
                    Text("本次使用内置镜像", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    ImageRow("PE", "efisp.img", "默认镜像 → efisp", "") { onCustomPage(true) }
                    ThinDivider()
                    ImageRow("ABL", "abl.img", "默认镜像 → ${target ?: "当前槽位 abl"}", "") { onCustomPage(true) }
                }
            }
        }
        item {
            SectionTitle("设备与分区", "01")
            Spacer(Modifier.height(10.dp))
            Panel {
                InfoLine("设备", state.deviceName)
                ThinDivider()
                InfoLine("Root 权限", state.rootStatus)
                ThinDivider()
                InfoLine("当前槽位", if (target == null) "执行时自动识别" else "${state.slot.trimStart('_').uppercase()}  ·  $target")
                ThinDivider()
                MutedText(state.partitionSummary, Modifier.padding(vertical = 14.dp), lineHeight = 21)
                Button(onClick = if (customPage) actions.onInspectCustom else actions.onInspectDirect, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                    Text(if (state.busy) "正在处理…" else "读取设备与分区信息", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            SectionTitle("操作步骤", "02")
            Spacer(Modifier.height(10.dp))
            Panel {
                StepRow("1", "备份 GPT 与原始分区", "保存本次任务所需的恢复备份", false)
                StepRow("2", "缩小 F2FS 文件系统", "释放 userdata 尾部空间", false)
                StepRow("3", "缩小 userdata 并创建 efisp", "尾部已释放超过 60 MiB 时跳过缩容，按实际空间创建 efisp", false)
                StepRow("4", "刷入当前槽位的 abl 分区", "${if (customPage) state.ablFileName else "abl.img"} → ${target ?: "abl_a / abl_b（自动识别）"}", false)
                StepRow("5", "刷入 efisp 镜像", "${if (customPage) state.efispFileName else "efisp.img"} → efisp", false)
                StepRow("6", "选择重启 Recovery 清空数据", "任务完成后提示，实际格式化在 Recovery 完成", true)
                Spacer(Modifier.height(16.dp))
                MutedText("新建名称 ${EfispProfile.LABEL} · 类型 EFI System（${EfispProfile.TYPE_CODE}）· PARTUUID 自动生成", fontSize = 11, lineHeight = 18)
            }
        }
        item {
            SectionTitle("执行操作", "03")
            Spacer(Modifier.height(10.dp))
            Panel {
                Text(if (customPage) "开始自定义解锁" else "开始直接解锁", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (!customPage) {
                    Text("解锁可能需要数分钟，请耐心等待", fontSize = 13.sp, lineHeight = 22.sp, color = Color(0xFFE53935))
                    Spacer(Modifier.height(8.dp))
                    MutedText("请确保备份已导出设备", lineHeight = 22)
                    Spacer(Modifier.height(8.dp))
                }
                MutedText("使用者自行确认设备和镜像适用性。开始后自动识别当前槽位，全部写入完成后，再选择是否进入 Recovery 清空 data。已有 efisp 时直接使用该分区。", lineHeight = 22)
                Spacer(Modifier.height(16.dp))
                ExecuteButton(state, target, onConfirm)
                ThinDivider(Modifier.padding(vertical = 20.dp))
                Button(onClick = if (customPage) actions.onBuildRecoveryCustom else actions.onBuildRecoveryDirect, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) { Text("生成操作与恢复包") }
                if (state.recoveryPackageReady) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = actions.onSharePackage, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("保存 / 分享已生成的操作包") }
                }
            }
        }
        item { ToolPanel(state, actions) }
        item { ActivityPanel(state) }
        if (state.canRebootRecovery) {
            item {
                Panel {
                    Text("分区与镜像写入已完成", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    MutedText("进入 Recovery 后恢复出厂设置，完成数据清空与文件系统重建。", lineHeight = 22)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onReboot, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("选择是否重启 Recovery") }
                }
            }
        }
        item { LogPanel(state, actions) }
        item { MutedText("分区调整与引导镜像写入会影响设备启动并可能清空全部数据。请自行确认所选文件及操作。", Modifier.padding(horizontal = 4.dp), fontSize = 12, lineHeight = 19) }
        item(key = "github_footer") { ProjectRepositoryFooter() }
    }
}

@Composable
private fun BackupPage(state: BackupUiState, operationBusy: Boolean, onBackup: () -> Unit, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                Badge("当前槽位 · ${state.slot.takeIf { it.isNotBlank() }?.trimStart('_')?.uppercase() ?: "自动识别"}", Accent)
                Spacer(Modifier.height(18.dp))
                Text("为重要分区，\n留一份原本", fontSize = 34.sp, lineHeight = 45.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                MutedText("读取当前槽位的引导分区，保存原始镜像、分区表与一份修补后的 vbmeta。", lineHeight = 22)
            }
        }
        item {
            Panel {
                Text("备份内容", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                InfoLine("init_boot", "原始分区镜像")
                ThinDivider()
                InfoLine("vbmeta", "原始分区镜像")
                ThinDivider()
                InfoLine("abl", "原始分区镜像")
                ThinDivider()
                InfoLine("GPT 分区表", "原样保存每盘主备表及独立区域")
                ThinDivider()
                InfoLine("vbmeta 修补", "额外保存 vbmeta_patched.img")
            }
        }
        item {
            Panel {
                Text("保存到内部存储", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                SelectionContainer { Text("Download/ublbackup_日期_时间", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Accent) }
                Spacer(Modifier.height(12.dp))
                MutedText("目录时间取点击备份时的设备时间。主备 GPT 各自保留原始字节与 CRC 状态；GPT 提示不会阻止 vbmeta 副本修补。", lineHeight = 22)
                Spacer(Modifier.height(18.dp))
                Button(onClick = onBackup, enabled = !operationBusy, modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (state.busy) "正在备份…" else "备份并修补vbmeta", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                Text("备份可能需要数分钟，请耐心等待", fontSize = 13.sp, lineHeight = 22.sp, color = Color(0xFFE53935))
            }
        }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("备份状态", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    val statusLabel = when {
                        state.busy -> "进行中"
                        state.status == "partial" -> "部分完成"
                        state.status == "complete_with_warnings" -> "已保存 · 有提示"
                        state.status == "complete" -> "完成"
                        else -> "待命"
                    }
                    Badge(statusLabel, if (state.status == "partial" || state.warnings.isNotEmpty()) Color(0xFFD88C20) else if (state.busy) Accent else Green)
                }
                Spacer(Modifier.height(13.dp))
                MutedText(state.stage, lineHeight = 21)
                if (state.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().background(Color(0xFFD88C20).copy(alpha = .10f), RoundedCornerShape(13.dp)).padding(13.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("备份提示", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFD88C20))
                            state.warnings.forEach { warning -> Text(warning, fontSize = 12.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurface) }
                            MutedText("主备属性差异与 CRC 结果分别记录。请保留原始快照、清单和诊断日志。", fontSize = 11, lineHeight = 18)
                        }
                    }
                }
                state.diskStatuses.forEach { disk ->
                    Spacer(Modifier.height(12.dp))
                    val label = when (disk.status) {
                        "complete" -> "已保存主备原始区域"
                        "complete_with_warnings" -> "已保存，存在提示"
                        "partial" -> "部分完成"
                        else -> "未完整导出，证据已保留"
                    }
                    Text("${disk.node} · $label", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    if (disk.detail.isNotBlank()) MutedText(disk.detail, fontSize = 11, lineHeight = 18)
                }
                if (state.busy || state.progress > 0f) {
                    Spacer(Modifier.height(15.dp))
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Accent.copy(alpha = .12f))) {
                        Box(Modifier.fillMaxWidth(state.progress.coerceIn(0f, 1f)).height(5.dp).background(Accent, CircleShape))
                    }
                }
                state.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, fontSize = 12.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.error)
                }
                if (state.outputDirectory.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    SelectionContainer { Text(state.outputDirectory, fontSize = 11.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, color = Accent) }
                }
            }
        }
        if (state.files.isNotEmpty()) {
            item { SectionTitle("已保存的备份", "${state.files.size}") }
            items(state.files.size) { index ->
                val file = state.files[index]
                Panel {
                    Text(file.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    MutedText("${"%.2f".format(file.bytes / 1048576.0)} MiB", fontSize = 12)
                    Spacer(Modifier.height(10.dp))
                    SelectionContainer { Text("SHA-256  ${file.sha256}", fontSize = 10.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                }
            }
        }
        item(key = "github_footer") { ProjectRepositoryFooter() }
    }
}

@Composable
private fun PageButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(15.dp)).background(if (selected) Accent.copy(alpha = .12f) else MiuixTheme.colorScheme.surface)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick).padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) Accent else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun CustomImagePanel(icon: String, title: String, destination: String, fileName: String, information: String, custom: Boolean, busy: Boolean, onSelect: () -> Unit, onReset: () -> Unit) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Accent.copy(alpha = .10f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Text(icon, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                MutedText(destination, fontSize = 12)
            }
            Badge(if (custom) "自定义" else "默认", if (custom) Accent else Green)
        }
        Spacer(Modifier.height(16.dp))
        Text(fileName, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(7.dp))
        SelectionContainer { Text(information, fontSize = 11.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSelect, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (custom) "重新选择文件" else "从文件管理选择") }
        if (custom) {
            Spacer(Modifier.height(9.dp))
            Button(onClick = onReset, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("恢复默认镜像") }
        }
    }
}

@Composable
private fun ExecuteButton(state: UnlockUiState, target: String?, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = state.canExecute && !state.busy,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColorsPrimary(),
        cornerRadius = 16.dp,
    ) { Text(if (state.busy) "正在处理…" else "开始流程 · ${target ?: "当前槽位"}", fontWeight = FontWeight.SemiBold) }
    if (state.readOnlyBuild) {
        Spacer(Modifier.height(10.dp))
        MutedText("只读调试构建已关闭分区写入", fontSize = 12)
    }
}

private fun targetPartition(slot: String): String? = when (slot.trim().lowercase().removePrefix("_")) {
    "a" -> "abl_a"
    "b" -> "abl_b"
    else -> null
}

@Composable
private fun HeroCard(target: String?, state: UnlockUiState) {
    val shape = RoundedCornerShape(26.dp)
    val ink = MiuixTheme.colorScheme.onPrimary
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(Accent, androidx.compose.ui.graphics.lerp(Accent, Color.Black, .17f))))
            .padding(23.dp),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(ink.copy(alpha = .055f), size.width * .40f, Offset(size.width * 1.00f, size.height * .30f))
            drawCircle(ink.copy(alpha = .045f), size.width * .30f, Offset(size.width * 1.03f, size.height * .25f))
        }
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(ink.copy(alpha = .16f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    BootGlyph(Modifier.size(27.dp), ink, unlocked = state.canRebootRecovery)
                }
                Spacer(Modifier.weight(1f))
                Text("BOOTLOADER", color = ink.copy(alpha = .75f), fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(22.dp))
            Text("更自由的使用设备", color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("按实际尾部空间创建 efisp  ·  当前槽位刷写", color = ink.copy(alpha = .8f), fontSize = 13.sp)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("目标分区", color = ink.copy(alpha = .67f), fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                Text(target ?: "当前槽位", color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = if (target != null) FontFamily.Monospace else FontFamily.Default)
                Spacer(Modifier.weight(1f))
                Box(Modifier.background(ink.copy(alpha = .15f), RoundedCornerShape(20.dp)).padding(horizontal = 11.dp, vertical = 6.dp)) {
                    Text(if (state.busy) "处理中" else "A / B", fontSize = 11.sp, color = ink)
                }
            }
        }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(20.dp), content = content)
}

@Composable
private fun SectionTitle(text: String, number: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        MutedText(number, fontSize = 11)
    }
}

@Composable
private fun MutedText(text: String, modifier: Modifier = Modifier, fontSize: Int = 13, lineHeight: Int = 20) {
    Text(text, modifier = modifier, fontSize = fontSize.sp, lineHeight = lineHeight.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(color.copy(alpha = .10f), RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun ThinDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(.5.dp).background(MiuixTheme.colorScheme.dividerLine.copy(alpha = .6f)))
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        MutedText(label, Modifier.width(86.dp))
        Text(value, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ImageRow(icon: String, name: String, detail: String, info: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(Accent.copy(alpha = .10f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Text(icon, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            MutedText(detail, fontSize = 11, lineHeight = 16)
            if (info.isNotBlank()) {
                Text(info.lineSequence().first(), modifier = Modifier.padding(top = 3.dp), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Spacer(Modifier.width(7.dp))
        Text("›", fontSize = 24.sp, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
    }
}

@Composable
private fun StepRow(number: String, title: String, detail: String, last: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(28.dp).background(Accent.copy(alpha = .11f), CircleShape), contentAlignment = Alignment.Center) {
                Text(number, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (!last) Box(Modifier.width(1.dp).height(40.dp).background(Accent.copy(alpha = .14f)))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.padding(top = 3.dp, bottom = if (last) 0.dp else 20.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(5.dp))
            MutedText(detail, fontSize = 11, lineHeight = 17)
        }
    }
}

@Composable
private fun ToolPanel(state: UnlockUiState, actions: UnlockActions) {
    Panel {
        Text("分区工具", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(9.dp))
        MutedText(state.toolsSummary, lineHeight = 21)
        if (state.toolWarnings.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            state.toolWarnings.forEach { warning ->
                Text(warning, modifier = Modifier.padding(bottom = 7.dp), fontSize = 12.sp, lineHeight = 19.sp, color = Color(0xFFD98124))
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = actions.onPrepareTools, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("准备工具") }
    }
}

@Composable
private fun ActivityPanel(state: UnlockUiState) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("执行状态", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Badge(if (state.busy) "进行中" else "待命", if (state.busy) Accent else Green)
        }
        Spacer(Modifier.height(15.dp))
        MutedText(state.stage, lineHeight = 21)
        if (state.busy || state.progress > 0f) {
            Spacer(Modifier.height(15.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Accent.copy(alpha = .12f))) {
                Box(Modifier.fillMaxWidth(state.progress.coerceIn(0f, 1f)).height(5.dp).background(Accent, CircleShape))
            }
            Text("${(state.progress.coerceIn(0f, 1f) * 100).toInt()}%", Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 11.sp, color = Accent)
        }
    }
}

@Composable
private fun LogPanel(state: UnlockUiState, actions: UnlockActions) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("运行日志", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("清空", Modifier.clickable(enabled = !state.busy, onClick = actions.onClearLogs).padding(horizontal = 8.dp, vertical = 8.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
            Text("导出", Modifier.clickable(enabled = state.logs.isNotEmpty(), onClick = actions.onShareLogs).padding(start = 8.dp, top = 8.dp, bottom = 8.dp), color = Accent, fontSize = 12.sp)
        }
        Spacer(Modifier.height(9.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 260.dp).clip(RoundedCornerShape(14.dp)).background(if (isSystemInDarkTheme()) Color(0xFF15171B) else Color(0xFFF5F6F8)).padding(13.dp)) {
            SelectionContainer {
                Text(
                    if (state.logs.isEmpty()) "刷入gbl解锁bl日志\n等待操作…" else state.logs.takeLast(150).joinToString("\n"),
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    fontSize = 10.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun ConfirmWriteDialog(state: UnlockUiState, target: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Panel(Modifier.widthIn(max = 460.dp).padding(horizontal = 22.dp).verticalScroll(rememberScrollState())) {
            Badge("开始刷入", Accent)
            Spacer(Modifier.height(16.dp))
            Text("按流程写入设备", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            MutedText("本次流程会调整 userdata 分区并写入引导镜像。当前槽位由工具自动读取，使用者自行确认适用性。", lineHeight = 22)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().background(Accent.copy(alpha = .08f), RoundedCornerShape(14.dp)).padding(14.dp)) {
                Text("1. ${state.ablFileName} → ${target ?: "当前槽位 abl"}\n2. ${state.efispFileName} → efisp", fontSize = 12.sp, lineHeight = 23.sp, fontFamily = FontFamily.Monospace, color = Accent)
            }
            Spacer(Modifier.height(15.dp))
            MutedText("未自定义的镜像使用内置文件。全部写入完成后再提示是否重启 Recovery 清空 data。", lineHeight = 21)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onConfirm, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("确认开始", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(9.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
}

@Composable
private fun BootGlyph(modifier: Modifier, color: Color, unlocked: Boolean) {
    Canvas(modifier.semantics {
        contentDescription = if (unlocked) "解锁操作已完成" else "解锁操作未完成"
    }) {
        val w = size.width
        val h = size.height
        val stroke = w * .075f
        drawRoundRect(color, Offset(w * .15f, h * .40f), Size(w * .70f, h * .50f), CornerRadius(w * .11f), style = Stroke(stroke))
        val shackle = Path().apply {
            moveTo(w * .30f, h * .42f)
            if (unlocked) {
                lineTo(w * .30f, h * .24f)
                cubicTo(w * .30f, h * .005f, w * .78f, h * .005f, w * .78f, h * .24f)
            } else {
                lineTo(w * .30f, h * .27f)
                cubicTo(w * .30f, h * .04f, w * .73f, h * .04f, w * .73f, h * .27f)
                lineTo(w * .73f, h * .42f)
            }
        }
        drawPath(shackle, color, style = Stroke(stroke, cap = StrokeCap.Round))
        drawCircle(color, w * .055f, Offset(w * .50f, h * .61f))
        drawLine(color, Offset(w * .50f, h * .63f), Offset(w * .50f, h * .74f), stroke, StrokeCap.Round)
    }
}
