package dev.honor.bootforge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.honor.bootforge.BackupUiState
import dev.honor.bootforge.BuildConfig
import dev.honor.bootforge.core.EfispProfile
import dev.honor.bootforge.core.AblWriteSource
import dev.honor.bootforge.core.AblWriteTarget
import dev.honor.bootforge.core.EfispPatchMode
import dev.honor.bootforge.core.EfispSource
import dev.honor.bootforge.core.UnlockMode
import dev.honor.bootforge.core.UnlockCompletion
import dev.honor.bootforge.core.UnlockRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class UnlockUiState(
    val request: UnlockRequest = UnlockRequest(),
    val rootStatus: String = "执行时获取",
    val deviceName: String = "执行时读取",
    val slot: String = "",
    val partitionSummary: String = "可直接开始流程，或先读取 userdata 与 efisp 信息",
    val efispFileName: String = "efisp.img",
    val ablFileName: String = "abl.img",
    val efispCustom: Boolean = false,
    val ablCustom: Boolean = false,
    val efispImage: String = "efisp.img · 运行时生成或使用上传文件",
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
    val canRebootBootloader: Boolean = false,
    val completedRequest: UnlockRequest? = null,
    val toolsSummary: String = "准备时检查内置分区工具",
    val toolWarnings: List<String> = emptyList(),
)

data class UnlockActions(
    val onRequestChanged: (UnlockRequest) -> Unit = {},
    val onInspect: (UnlockRequest) -> Unit = {},
    val onPrepareTools: () -> Unit = {},
    val onSelectEfisp: () -> Unit = {},
    val onSelectAbl: () -> Unit = {},
    val onResetEfisp: () -> Unit = {},
    val onResetAbl: () -> Unit = {},
    val onBuildRecovery: (UnlockRequest) -> Unit = {},
    val onExecute: (UnlockRequest) -> Unit = {},
    val onSharePackage: () -> Unit = {},
    val onShareLogs: () -> Unit = {},
    val onClearLogs: () -> Unit = {},
    val onRebootRecovery: () -> Unit = {},
    val onRebootBootloader: () -> Unit = {},
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
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val navigationScope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val selectPage: (Int) -> Unit = { destination ->
        navigationJob?.cancel()
        navigationJob = navigationScope.launch { pagerState.animateScrollToPage(destination) }
    }
    LaunchedEffect(rootOpenRequest) { if (rootOpenRequest > 0) selectPage(0) }
    val page = pagerState.currentPage
    var showConfirm by remember { mutableStateOf(false) }
    var confirmationState by remember { mutableStateOf(state) }
    var rebootChoice by remember { mutableStateOf<UnlockCompletion?>(null) }
    val target = targetPartition(state.slot)
    val operationState = state.copy(busy = state.busy || backupState.busy || rootBusy)
    val unlockNavigation = rememberUnlockNavigation(operationState, actions)
    val density = LocalDensity.current
    var unlockTabsHeight by remember { mutableStateOf(74.dp) }
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
    BackHandler(enabled = page != 2 || navigating || state.request.mode != UnlockMode.FAKE_LOCK) {
        if (page != 2 || navigating) selectPage(2)
        else if (!operationState.busy) actions.onRequestChanged(state.request.copy(mode = UnlockMode.FAKE_LOCK))
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
                            else -> UnlockPage(operationState, actions, target,
                                PaddingValues(start = 20.dp, end = 20.dp,
                                    top = contentPadding.calculateTopPadding() + unlockTabsHeight,
                                    bottom = contentPadding.calculateBottomPadding()),
                                unlockNavigation,
                                onConfirm = { request ->
                                    confirmationState = operationState.copy(request = request.resolved())
                                    showConfirm = true
                                },
                                onReboot = { rebootChoice = if (operationState.canRebootBootloader) UnlockCompletion.BOOTLOADER else UnlockCompletion.RECOVERY })
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
        ) {
            if (page == 2) {
                Box(Modifier.fillMaxWidth().onSizeChanged { size ->
                    unlockTabsHeight = with(density) { size.height.toDp() }
                }, contentAlignment = Alignment.TopCenter) {
                    UnlockModeTabs(
                        selectedPage = unlockNavigation.pager.currentPage,
                        position = { (unlockNavigation.pager.currentPage + unlockNavigation.pager.currentPageOffsetFraction)
                            .coerceIn(0f, UnlockMode.entries.lastIndex.toFloat()) },
                        enabled = !operationState.busy && !unlockNavigation.syncingFromModel,
                        onSelect = unlockNavigation.onSelect,
                        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        }
        GlassBottomBar(
            backdrop, page,
            { (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 3f) },
            appearance, navigationBottom, Modifier.align(Alignment.BottomCenter),
            onSelect = selectPage,
        )
    }
    if (showConfirm) {
        val frozenState = confirmationState.copy(busy = operationState.busy, canExecute = operationState.canExecute)
        ConfirmWriteDialog(frozenState, onDismiss = { showConfirm = false }) {
            showConfirm = false
            actions.onExecute(frozenState.request)
        }
    }
    rebootChoice?.let { choice ->
        val canReboot = !operationState.busy && when (choice) {
            UnlockCompletion.BOOTLOADER -> operationState.canRebootBootloader
            UnlockCompletion.RECOVERY -> operationState.canRebootRecovery
        }
        Dialog(onDismissRequest = { rebootChoice = null }) {
            Panel {
                Text("现在重启进入 ${choice.destination}？", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                MutedText(if (choice == UnlockCompletion.BOOTLOADER)
                    "刷写完成。可选择重启进入 Fastboot，或选择稍后停留在当前页面。"
                    else "进入后恢复出厂设置会清空全部用户数据和应用内备份。如需保留分区备份，请先导出操作包。选择稍后将停留在当前页面。", lineHeight = 22)
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    rebootChoice = null
                    if (choice == UnlockCompletion.BOOTLOADER) actions.onRebootBootloader() else actions.onRebootRecovery()
                }, enabled = canReboot, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text("确认重启进入 ${choice.destination}")
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { rebootChoice = null }, modifier = Modifier.fillMaxWidth()) { Text("稍后重启") }
            }
        }
    }
}

@Composable
private fun rememberUnlockNavigation(state: UnlockUiState, actions: UnlockActions): UnlockNavigation {
    val modes = UnlockMode.entries
    val pager = rememberPagerState(initialPage = state.request.mode.ordinal, pageCount = { modes.size })
    val scope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    var navigationGeneration by remember { mutableStateOf(0L) }
    var menuTarget by remember { mutableStateOf<Int?>(null) }
    var syncingFromModel by remember { mutableStateOf(false) }
    var modelSyncGeneration by remember { mutableStateOf(0L) }
    var modelSyncNonce by remember { mutableStateOf(0L) }
    var pendingMode by remember { mutableStateOf<UnlockMode?>(null) }
    var syncedModelMode by remember { mutableStateOf<UnlockMode?>(null) }
    val selectPage: (Int) -> Unit = { destination ->
        if (!state.busy && !syncingFromModel) {
            navigationGeneration += 1
            val generation = navigationGeneration
            menuTarget = destination
            navigationJob?.cancel()
            navigationJob = scope.launch {
                try { pager.animateScrollToPage(destination) }
                finally { if (generation == navigationGeneration) menuTarget = null }
            }
        }
    }
    // External changes (including Back) navigate the pager without echoing the old page back.
    LaunchedEffect(state.request.mode, modelSyncNonce) {
        modelSyncGeneration += 1
        val generation = modelSyncGeneration
        val requestedMode = state.request.mode
        syncingFromModel = true
        navigationGeneration += 1
        navigationJob?.cancel()
        menuTarget = null
        try {
            val destination = requestedMode.ordinal
            if (pager.currentPage != destination || pager.currentPageOffsetFraction != 0f) {
                pager.animateScrollToPage(destination)
            }
        } finally {
            // Cancellation must release the commit guard too; stale effects cannot replace a newer sync.
            if (generation == modelSyncGeneration) {
                syncedModelMode = requestedMode
                syncingFromModel = false
            }
        }
    }
    // One submission per settled navigation. A rejected request rolls the UI back instead of retrying Root queries.
    LaunchedEffect(pager.settledPage, pager.isScrollInProgress, state.busy,
        state.request.mode, syncingFromModel, syncedModelMode, menuTarget, pendingMode) {
        if (!syncingFromModel && menuTarget == null && !pager.isScrollInProgress) {
            val submitted = pendingMode
            if (submitted != null) {
                if (state.request.mode == submitted) {
                    pendingMode = null
                } else if (!state.busy) {
                    // Guard before clearing pending, so recomposition cannot resubmit the failed target.
                    syncingFromModel = true
                    pendingMode = null
                    modelSyncNonce += 1
                }
            } else if (!state.busy && syncedModelMode == state.request.mode) {
                val mode = modes[pager.settledPage]
                if (state.request.mode != mode) {
                    pendingMode = mode
                    actions.onRequestChanged(state.request.copy(mode = mode))
                }
            }
        }
    }
    return UnlockNavigation(pager, syncingFromModel, selectPage) { mode ->
        !state.busy && !pager.isScrollInProgress && !syncingFromModel && menuTarget == null && pendingMode == null &&
            syncedModelMode == state.request.mode && pager.settledPage == mode.ordinal && state.request.mode == mode
    }
}

private data class UnlockNavigation(
    val pager: PagerState,
    val syncingFromModel: Boolean,
    val onSelect: (Int) -> Unit,
    val canInteract: (UnlockMode) -> Boolean,
)

/** Navigation locks actions while retaining the operation's real visual busy state. */
private fun UnlockActions.whenNavigationReady(ready: () -> Boolean): UnlockActions = copy(
    onRequestChanged = { if (ready()) onRequestChanged(it) },
    onInspect = { if (ready()) onInspect(it) },
    onPrepareTools = { if (ready()) onPrepareTools() },
    onSelectEfisp = { if (ready()) onSelectEfisp() },
    onSelectAbl = { if (ready()) onSelectAbl() },
    onResetEfisp = { if (ready()) onResetEfisp() },
    onResetAbl = { if (ready()) onResetAbl() },
    onBuildRecovery = { if (ready()) onBuildRecovery(it) },
    onExecute = { if (ready()) onExecute(it) },
    onSharePackage = { if (ready()) onSharePackage() },
    onShareLogs = { if (ready()) onShareLogs() },
    onClearLogs = { if (ready()) onClearLogs() },
    onRebootRecovery = { if (ready()) onRebootRecovery() },
    onRebootBootloader = { if (ready()) onRebootBootloader() },
)

@Composable
private fun UnlockPage(
    state: UnlockUiState, actions: UnlockActions, target: String?,
    contentPadding: PaddingValues, navigation: UnlockNavigation,
    onConfirm: (UnlockRequest) -> Unit, onReboot: () -> Unit,
) {
    val modes = UnlockMode.entries
    val pager = navigation.pager
    val pageStates = rememberSaveableStateHolder()
    val consumeHorizontalRemainder = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                Offset(available.x, 0f)

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                Velocity(available.x, 0f)
        }
    }
    Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().nestedScroll(consumeHorizontalRemainder),
            userScrollEnabled = !state.busy && !navigation.syncingFromModel,
            key = { modes[it].name },
        ) { page ->
            val mode = modes[page]
            pageStates.SaveableStateProvider(mode.name) {
                UnlockModePage(
                    state = state.copy(request = state.request.copy(mode = mode)),
                    actions = actions.whenNavigationReady { navigation.canInteract(mode) }, target = target,
                    contentPadding = contentPadding,
                    onConfirm = { if (navigation.canInteract(mode)) onConfirm(it) },
                    onReboot = { if (navigation.canInteract(mode)) onReboot() },
                )
            }
        }
    }
}

@Composable
private fun UnlockModeTabs(
    selectedPage: Int, position: () -> Float, enabled: Boolean, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = UnlockMode.entries
    val highlight = Accent.copy(alpha = .12f)
    Box(modifier.fillMaxWidth().padding(5.dp)) {
        Row(
            Modifier.fillMaxWidth().selectableGroup().drawBehind {
                val gap = 5.dp.toPx()
                val width = (size.width - gap * (modes.size - 1)) / modes.size
                val start = position() * (width + gap)
                val left = if (layoutDirection == LayoutDirection.Ltr) start else size.width - width - start
                drawRoundRect(highlight, topLeft = Offset(left, 0f), size = Size(width, size.height),
                    cornerRadius = CornerRadius(15.dp.toPx()))
            },
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            modes.forEachIndexed { index, mode ->
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(15.dp))
                        .selectable(selected = selectedPage == index, enabled = enabled, role = Role.Tab,
                            onClick = { onSelect(index) }).padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(mode.label, fontSize = 14.sp,
                        fontWeight = if (selectedPage == index) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedPage == index) Accent else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

@Composable
private fun UnlockModePage(
    state: UnlockUiState, actions: UnlockActions, target: String?,
    contentPadding: PaddingValues, onConfirm: (UnlockRequest) -> Unit, onReboot: () -> Unit,
) {
    val request = state.request
    val effective = request.resolved()
    val ablDestination = ablWriteDestination(state.slot, effective)
    val customPage = request.mode == UnlockMode.CUSTOM
    val rootReady = state.rootStatus == "已提权" || state.rootStatus == "Root 已取得"
    val generatedEfisp = effective.efispSource != EfispSource.UPLOADED_EFISP
    val needsUploadedAbl = customPage &&
        (request.ablSource == AblWriteSource.UPLOADED || request.efispSource == EfispSource.UPLOADED_ABL)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(Modifier.padding(start = 4.dp, bottom = 3.dp)) {
                Text(when {
                    customPage -> "选择镜像与生成方式"
                    rootReady -> "一切，准备就绪"
                    else -> "等待root授权"
                }, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                MutedText(if (customPage) "分别选择 ABL 写入来源、efisp 来源和修补方式。"
                    else "提取当前槽位 ABL，采用${effective.patchMode.label}修补生成 efisp.img，并写入内置 abl.img。", fontSize = 11, lineHeight = 18)
                Spacer(Modifier.height(6.dp))
                Text("仅适用于搭载8e5芯片的荣耀设备，其他设备请勿尝试。",
                    fontSize = 11.sp, lineHeight = 18.sp, color = Color(0xFFE53935))
            }
        }
        if (customPage) {
            item {
                Panel {
                    Text("ABL 刷写来源", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AblWriteSource.entries.forEach { source ->
                            PageButton(source.label, request.ablSource == source, Modifier.weight(1f), enabled = !state.busy) {
                                actions.onRequestChanged(request.copy(ablSource = source))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("ABL 刷写槽位", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AblWriteTarget.entries.forEach { destination ->
                            PageButton(destination.label, request.ablTarget == destination, Modifier.weight(1f), enabled = !state.busy) {
                                actions.onRequestChanged(request.copy(ablTarget = destination))
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    MutedText("所选 ABL 将写入 $ablDestination。", fontSize = 12, lineHeight = 20)
                }
            }
            item {
                Panel {
                    Text("efisp 刷写来源", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        EfispSource.entries.forEach { source ->
                            SourceChoice(source.label, request.efispSource == source, !state.busy) {
                                actions.onRequestChanged(request.copy(efispSource = source))
                            }
                        }
                    }
                    run {
                        Spacer(Modifier.height(16.dp))
                        Text("模式选择", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EfispPatchMode.entries.forEach { mode ->
                                PageButton(mode.label, request.patchMode == mode, Modifier.weight(1f), enabled = !state.busy) {
                                    actions.onRequestChanged(request.copy(patchMode = mode))
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        MutedText(if (!generatedEfisp) "上传镜像保持原样，所选模式用于完成提示和重启入口。"
                            else "由所选 ABL 生成 efisp.img，并定制 fastboot 文字。"
                                + if (request.patchMode == EfispPatchMode.FREEDOM) "同时定制开机警告文字。" else "假回锁方式不修改开机警告文字。",
                            fontSize = 12, lineHeight = 20)
                    }
                }
            }
            if (needsUploadedAbl) {
                item {
                    CustomImagePanel("ABL", "上传的 ABL 文件", "用于所选 ABL 刷写或 efisp 生成来源",
                        if (state.ablCustom) state.ablFileName else "请选择 ABL 文件",
                        if (state.ablCustom) state.ablImage else "尚未选择上传的 ABL 文件", state.ablCustom,
                        state.busy, actions.onSelectAbl, actions.onResetAbl)
                }
            }
            if (request.efispSource == EfispSource.UPLOADED_EFISP) {
                item {
                    CustomImagePanel("PE", "上传的 efisp 文件", "所选文件原样写入 efisp",
                        if (state.efispCustom) state.efispFileName else "请选择 efisp 文件",
                        if (state.efispCustom) state.efispImage else "尚未选择上传的 efisp 文件", state.efispCustom,
                        state.busy, actions.onSelectEfisp, actions.onResetEfisp)
                }
            }
            item { MutedText("从文件管理中选择文件，不限制扩展名。已上传的 ABL 可同时用于 ABL 刷写和 efisp 生成。", Modifier.padding(horizontal = 4.dp), fontSize = 12, lineHeight = 20) }
        } else {
            item { HeroCard(target, state) }
            item {
                Panel {
                    Text("本次镜像来源", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    ImageRow("PE", "efisp.img", "当前槽位 ABL → ${effective.patchMode.label}修补", "执行时生成") {
                        if (!state.busy) actions.onRequestChanged(request.copy(mode = UnlockMode.CUSTOM))
                    }
                    ThinDivider()
                    ImageRow("ABL", "abl.img", "内置镜像 → $ablDestination", "") {
                        if (!state.busy) actions.onRequestChanged(request.copy(mode = UnlockMode.CUSTOM))
                    }
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
                Button(onClick = { actions.onInspect(effective) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                    Text(if (state.busy) "正在处理…" else "读取设备与分区信息", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            SectionTitle("操作步骤", "02")
            Spacer(Modifier.height(10.dp))
            Panel {
                StepRow("1", "备份 GPT 与原始分区", "保存本次任务所需的恢复备份", false)
                StepRow("2", "核对 F2FS 文件系统", "连续尾部空间不足时再缩容", false)
                StepRow("3", "调整 userdata 并创建 efisp", "按实际文件系统边界修正 GPT，使用连续尾部空间", false)
                StepRow("4", if (generatedEfisp) "提取 ABL 并准备 efisp.img" else "准备上传的 efisp 镜像",
                    if (generatedEfisp) "${effective.efispSource.label} · ${effective.patchMode.label}修补与文字定制"
                        else "${state.efispFileName} · 按上传内容写入", false)
                StepRow("5", when (effective.ablTarget) {
                    AblWriteTarget.CURRENT -> "刷入当前槽位的 abl 分区"
                    AblWriteTarget.OTHER -> "刷入对槽的 abl 分区"
                    AblWriteTarget.BOTH -> "刷入 A / B 双槽的 abl 分区"
                }, "${ablWriteName(state, effective)} → $ablDestination", false)
                StepRow("6", "刷入 efisp 镜像", "${efispWriteName(state, effective)} → efisp", false)
                StepRow("7", if (effective.completion == UnlockCompletion.BOOTLOADER) "刷写完成，可选重启进入 Fastboot" else "选择重启 Recovery 清空数据",
                    if (effective.completion == UnlockCompletion.BOOTLOADER) "全部写入及回读完成后，由使用者选择是否重启" else "任务完成后提示，实际格式化在 Recovery 完成", true)
                Spacer(Modifier.height(16.dp))
                MutedText("新建名称 ${EfispProfile.LABEL} · 类型 EFI System（${EfispProfile.TYPE_CODE}）· PARTUUID 自动生成", fontSize = 11, lineHeight = 18)
            }
        }
        item {
            SectionTitle("执行操作", "03")
            Spacer(Modifier.height(10.dp))
            Panel {
                Text("开始${request.mode.label}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("解锁可能需要数分钟，请耐心等待", fontSize = 13.sp, lineHeight = 22.sp, color = Color(0xFFE53935))
                Spacer(Modifier.height(8.dp))
                MutedText("请确保备份已导出设备", lineHeight = 22)
                Spacer(Modifier.height(8.dp))
                MutedText(if (effective.completion == UnlockCompletion.BOOTLOADER)
                    "使用者自行确认设备和镜像适用性。全部写入及回读完成后提示刷写完成，可选择重启进入 Fastboot。已有 efisp 时直接使用该分区。"
                    else "使用者自行确认设备和镜像适用性。开始后自动识别当前槽位，全部写入完成后，再选择是否进入 Recovery 清空 data。已有 efisp 时直接使用该分区。", lineHeight = 22)
                Spacer(Modifier.height(16.dp))
                ExecuteButton(state, ablDestination) { onConfirm(effective) }
                ThinDivider(Modifier.padding(vertical = 20.dp))
                Button(onClick = { actions.onBuildRecovery(effective) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) { Text("生成操作与恢复包") }
                if (state.recoveryPackageReady) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = actions.onSharePackage, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("保存 / 分享已生成的操作包") }
                }
            }
        }
        item { ToolPanel(state, actions) }
        item { ActivityPanel(state) }
        if (state.canRebootRecovery || state.canRebootBootloader) {
            item {
                Panel {
                    Text("刷写完成", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    if (state.canRebootRecovery) {
                        MutedText("进入 Recovery 后恢复出厂设置，完成数据清空与文件系统重建。", lineHeight = 22)
                        Spacer(Modifier.height(12.dp))
                    }
                    Button(onClick = onReboot, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                        Text(if (state.canRebootBootloader) "重启进入 Fastboot" else "选择是否重启 Recovery")
                    }
                }
            }
        }
        item { LogPanel(state, actions) }
        item { MutedText(if (effective.completion == UnlockCompletion.BOOTLOADER) "分区调整与引导镜像写入会影响设备启动。请自行确认所选文件及操作。"
            else "分区调整与引导镜像写入会影响设备启动并可能清空全部数据。请自行确认所选文件及操作。", Modifier.padding(horizontal = 4.dp), fontSize = 12, lineHeight = 19) }
        item(key = "github_footer") { ProjectRepositoryFooter() }
    }
}

private fun ablWriteName(state: UnlockUiState, request: UnlockRequest): String =
    if (request.ablSource == AblWriteSource.UPLOADED) state.ablFileName else "abl.img（内置）"

private fun efispWriteName(state: UnlockUiState, request: UnlockRequest): String =
    if (request.efispSource == EfispSource.UPLOADED_EFISP) state.efispFileName else "efisp.img（运行时生成）"

@Composable
private fun SourceChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = if (selected) Accent else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(if (selected) Accent.copy(alpha = .09f) else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(18.dp)) {
            drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            if (selected) drawCircle(color, radius = size.minDimension * .24f)
        }
        Spacer(Modifier.width(11.dp))
        Text(label, fontSize = 14.sp, lineHeight = 21.sp, color = color)
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
                MutedText("读取 A/B 双槽的 init_boot 与 ABL，保存原始镜像、当前槽位标记和 GPT 分区表。", lineHeight = 22)
            }
        }
        item {
            Panel {
                Text("备份内容", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                InfoLine("init_boot", "A / B 双槽原始镜像")
                ThinDivider()
                InfoLine("abl", "A / B 双槽原始镜像")
                ThinDivider()
                InfoLine("当前槽位", "slot_active_a 或 slot_active_b")
                ThinDivider()
                InfoLine("GPT 分区表", "原样保存每盘主备表及独立区域")
            }
        }
        item {
            Panel {
                Text("保存到内部存储", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                SelectionContainer { Text("Download/ublbackup_日期_时间", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Accent) }
                Spacer(Modifier.height(12.dp))
                MutedText("目录时间取点击备份时的设备时间。原始镜像先保存并回读校验；当前槽位保存在 slot_active_a 或 slot_active_b，文件内仅写 a 或 b。主备 GPT 各自保留原始字节与 CRC 状态。", lineHeight = 22)
                Spacer(Modifier.height(18.dp))
                Button(onClick = onBackup, enabled = !operationBusy, modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (state.busy) "正在备份…" else "备份分区与分区表", fontWeight = FontWeight.SemiBold)
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
private fun PageButton(label: String, selected: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(15.dp)).background(if (selected) Accent.copy(alpha = .12f) else MiuixTheme.colorScheme.surface)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick).padding(vertical = 13.dp),
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
            Badge(if (custom) "已选择" else "待选择", if (custom) Accent else Green)
        }
        Spacer(Modifier.height(16.dp))
        Text(fileName, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(7.dp))
        SelectionContainer { Text(information, fontSize = 11.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSelect, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (custom) "重新选择文件" else "从文件管理选择") }
        if (custom) {
            Spacer(Modifier.height(9.dp))
            Button(onClick = onReset, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("清除已选文件") }
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

private fun ablWriteDestination(slot: String, request: UnlockRequest): String {
    val selection = request.resolved().ablTarget
    val partitions = selection.partitionNames(slot)
    if (partitions.isNotEmpty()) return partitions.joinToString(" + ")
    return when (selection) {
        AblWriteTarget.CURRENT -> "当前槽位abl待识别"
        AblWriteTarget.OTHER -> "对槽 abl（当前槽位待识别）"
        AblWriteTarget.BOTH -> "abl_a + abl_b（当前槽位待识别）"
    }
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
                    BootGlyph(Modifier.size(27.dp), ink, unlocked = state.completedRequest != null)
                }
                Spacer(Modifier.weight(1f))
                Text("BOOTLOADER", color = ink.copy(alpha = .75f), fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(22.dp))
            Text("更自由的使用设备", color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("按连续尾部空间创建 efisp  ·  当前槽位刷写", color = ink.copy(alpha = .8f), fontSize = 13.sp)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("目标分区", color = ink.copy(alpha = .67f), fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                Text(target ?: "当前槽位abl待识别", modifier = Modifier.weight(1f), color = ink, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = if (target != null) FontFamily.Monospace else FontFamily.Default,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.widthIn(min = 56.dp).background(ink.copy(alpha = .15f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 11.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Text(if (state.busy) "处理中" else "A / B", fontSize = 11.sp, color = ink, maxLines = 1, softWrap = false)
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
    val connectorColor = Accent.copy(alpha = .14f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (last) 28.dp else 68.dp).drawBehind {
            if (!last) {
                // Follow the measured row height when either text wraps.
                val centerX = if (layoutDirection == LayoutDirection.Ltr) 14.dp.toPx() else size.width - 14.dp.toPx()
                drawLine(
                    color = connectorColor,
                    start = Offset(centerX, 28.dp.toPx()),
                    end = Offset(centerX, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(28.dp).background(Accent.copy(alpha = .11f), CircleShape), contentAlignment = Alignment.Center) {
            Text(number, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
    val context = LocalContext.current
    val completeLog = remember(state.logs) { state.logs.joinToString("\n") }
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
                LazyColumn(Modifier.fillMaxWidth()) {
                    if (state.logs.isEmpty()) {
                        item {
                            Text("刷入gbl解锁bl日志\n等待操作…", fontSize = 10.sp, lineHeight = 17.sp,
                                fontFamily = FontFamily.Monospace, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    } else {
                        items(state.logs.size) { index ->
                            Text(state.logs[index], modifier = Modifier.fillMaxWidth(), fontSize = 10.sp,
                                lineHeight = 17.sp, fontFamily = FontFamily.Monospace,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                runCatching {
                    checkNotNull(context.getSystemService(ClipboardManager::class.java))
                        .setPrimaryClip(ClipData.newPlainText("honor_ubl 日志", completeLog))
                }.onSuccess {
                    Toast.makeText(context, "已复制全部日志", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "复制日志失败：${it.message ?: "请稍后重试"}", Toast.LENGTH_LONG).show()
                }
            },
            enabled = state.logs.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
        ) {
            Text("复制全部日志")
        }
    }
}

@Composable
private fun ConfirmWriteDialog(state: UnlockUiState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Panel(Modifier.widthIn(max = 460.dp).padding(horizontal = 22.dp).verticalScroll(rememberScrollState())) {
            Badge("开始刷入", Accent)
            Spacer(Modifier.height(16.dp))
            Text("按流程写入设备", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            MutedText("本次流程会调整 userdata 分区并写入引导镜像。当前槽位由工具自动读取，使用者自行确认适用性。", lineHeight = 22)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().background(Accent.copy(alpha = .08f), RoundedCornerShape(14.dp)).padding(14.dp)) {
                val request = state.request.resolved()
                Text("模式：${request.mode.label}\nABL：${ablWriteName(state, request)} → ${ablWriteDestination(state.slot, request)}\n刷写槽位：${request.ablTarget.label}\n"
                    + "efisp：${efispWriteName(state, request)} → efisp\n来源：${request.efispSource.label}"
                    + "\n模式选择：${request.patchMode.label}",
                    fontSize = 12.sp, lineHeight = 23.sp, fontFamily = FontFamily.Monospace, color = Accent)
            }
            Spacer(Modifier.height(15.dp))
            MutedText(if (state.request.completion == UnlockCompletion.BOOTLOADER)
                "按本次选定来源生成或使用上传的 efisp 镜像，ABL 使用本次选定文件。全部写入及回读完成后提示刷写完成，可选择重启进入 Fastboot。"
                else "按本次选定来源生成或使用上传的 efisp 镜像，ABL 使用本次选定文件。全部写入完成后再提示是否重启 Recovery 清空 data。", lineHeight = 21)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onConfirm, enabled = state.canExecute && !state.busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("确认开始", fontWeight = FontWeight.SemiBold) }
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
