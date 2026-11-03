package dev.honor.bootforge.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.honor.bootforge.BackupState
import dev.honor.bootforge.OtaState
import dev.honor.bootforge.RunState
import dev.honor.bootforge.core.CustomSuSettings
import dev.honor.bootforge.core.CustomSuSettingsStore
import dev.honor.bootforge.core.RootShell
import dev.honor.bootforge.wireless.WirelessState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsPage(
    settings: AppearanceSettings,
    onChange: (AppearanceSettings) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    onSupport: () -> Unit = {},
    onLicense: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var iconRequestBusy by remember { mutableStateOf(false) }
    val suStore = remember(context.applicationContext) { CustomSuSettingsStore.inDirectory(context.filesDir) }
    val initialSu = remember(suStore) { runCatching { suStore.read() } }
    var savedSu by remember { mutableStateOf(initialSu.getOrDefault(CustomSuSettings())) }
    var suEnabled by rememberSaveable { mutableStateOf(savedSu.enabled) }
    var suPath by rememberSaveable { mutableStateOf(savedSu.path) }
    var suBusy by remember { mutableStateOf(false) }
    var suError by rememberSaveable { mutableStateOf(initialSu.exceptionOrNull()?.message) }
    var suMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val operationBusy = RunState.state.busy || BackupState.state.busy || OtaState.state.busy ||
        WirelessState.state.busy || WirelessState.state.waiting
    val suDraft = CustomSuSettings(suEnabled, suPath)
    val suValidation = suDraft.validationError()
    val suEditable = !operationBusy && !suBusy
    val iconColor = MiuixTheme.colorScheme.primary
    val iconPreview = remember(iconColor) { AppLauncherIcon.bitmap(iconColor.toArgb()).asImageBitmap() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text("随心设定", fontSize = 34.sp, lineHeight = 45.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text("玻璃效果、强调色与动态调色板，修改后即时生效。",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp, lineHeight = 22.sp)
                }
            }
            item(key = "support_and_license") {
                Card(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
                    cornerRadius = 24.dp,
                    insideMargin = PaddingValues(0.dp),
                ) {
                    Column {
                        SettingsDetailRow("支持", onSupport) { SupportHeart(Modifier.size(38.dp)) }
                        Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp)
                            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = .06f)))
                        SettingsDetailRow("许可证", onLicense) { LicenseDocumentIcon(Modifier.size(38.dp)) }
                    }
                }
            }
            item(key = "custom_su") {
                SettingsCard {
                    SettingsSectionTitle("自定义 su 路径")
                    SettingsToggle("使用自定义 su", "默认关闭；开启后使用下面保存的路径获取提权权限。", suEnabled,
                        enabled = suEditable) {
                        suEnabled = it
                        suError = null
                        suMessage = null
                    }
                    Box(Modifier.fillMaxWidth()) {
                        TextField(
                            value = suPath,
                            onValueChange = { suPath = it; suError = null; suMessage = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = "",
                            singleLine = true,
                            enabled = suEditable,
                        )
                        if (suPath.isEmpty()) Text(
                            "绝对路径，例如 /system/bin/su",
                            modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 20.dp),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    SettingsHint("保存后重新检查授权；仅实际返回 UID 0 的路径可用于备份、刷写和设备检查。关闭后恢复原有提权方式。")
                    val error = suError ?: suValidation
                    if (error != null) Text(error, color = Color(0xFFD9363E), fontSize = 12.sp, lineHeight = 19.sp)
                    else suMessage?.let { SettingsHint(it) }
                    if (operationBusy && !suBusy) SettingsHint("任务进行中，请在任务结束后修改提权设置。")
                    Button(
                        onClick = {
                            // Reserve on the main thread before starting IO, just like other app jobs.
                            if (RunState.state.busy || BackupState.state.busy || OtaState.state.busy ||
                                WirelessState.state.busy || WirelessState.state.waiting || suBusy) return@Button
                            val selected = CustomSuSettings(suEnabled, suPath)
                            if (selected.validationError() != null) return@Button
                            suBusy = true
                            suError = null
                            suMessage = null
                            RunState.state = RunState.state.copy(busy = true, rootStatus = "提权待授权")
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    // Finish this short atomic save even if the settings page is disposed.
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        suStore.save(selected)
                                        RootShell.customSuSettings()
                                    }
                                    savedSu = selected
                                    suMessage = "设置已保存，请在提权页重新检查授权。"
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (error: Exception) {
                                    suError = "保存提权设置失败：${error.message ?: error.javaClass.simpleName}"
                                } finally {
                                    suBusy = false
                                    RunState.state = RunState.state.copy(busy = false)
                                }
                            }
                        },
                        enabled = suEditable && suValidation == null && (suDraft != savedSu || suError != null),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(if (suBusy) "正在保存…" else "保存设置") }
                }
            }
            item {
                SettingsCard {
                    SettingsSectionTitle("玻璃与底栏")
                    BlurSetting("顶部模糊度", "状态栏、顶栏与解锁二级菜单共用一个玻璃背景", settings.topBlurDp) {
                        onChange(settings.copy(topBlurDp = it))
                    }
                    BlurSetting("底部模糊度", "独立调整底栏背景的模糊半径", settings.bottomBlurDp) {
                        onChange(settings.copy(bottomBlurDp = it))
                    }
                    SettingsToggle("底部液态玻璃", "为底栏加入折射与高光效果", settings.liquidGlass) {
                        onChange(settings.copy(liquidGlass = it))
                    }
                    SettingsToggle("悬浮底栏", "开启胶囊式浮动布局，关闭后底栏贴合底边", settings.floatingBottomBar) {
                        onChange(settings.copy(floatingBottomBar = it))
                    }
                }
            }
            item {
                SettingsCard {
                    SettingsSectionTitle("Monet 动态色彩")
                    SettingsToggle("Monet 取色", "跟随系统强调色时，从系统壁纸配色提取种子颜色", settings.monetExtraction) {
                        onChange(settings.copy(monetExtraction = it))
                    }
                    SettingsToggle("启用 Monet 颜色", "将种子颜色生成的完整调色板应用到界面", settings.monetEnabled) {
                        onChange(settings.copy(monetEnabled = it))
                    }
                    SettingsHint("取色关闭时，系统选项使用默认蔷薇粉；选择指定强调色后，以指定颜色为种子。")
                    PalettePreview()
                }
            }
            item {
                SettingsCard {
                    SettingsSectionTitle("强调色")
                    SettingsHint("选择跟随系统，或为界面指定一个颜色。")
                    AccentChoices(settings.accent) { onChange(settings.copy(accent = it)) }
                    if (settings.accent == AccentOption.Custom) {
                        var customText by rememberSaveable(settings.customAccentHex) { mutableStateOf(settings.customAccentHex) }
                        val normalized = normalizeAccentHex(customText)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextField(
                                value = customText,
                                onValueChange = { customText = it.take(16) },
                                modifier = Modifier.weight(1f),
                                label = "#RRGGBB",
                                singleLine = true,
                            )
                            Button(
                                onClick = { normalized?.let { onChange(settings.copy(customAccentHex = it)) } },
                                enabled = normalized != null,
                            ) { Text("应用") }
                        }
                        SettingsHint(if (normalized == null) "请输入六位十六进制颜色，例如 #3478F6。" else "自定义颜色：$normalized")
                    }
                }
            }
            item {
                SettingsCard {
                    SettingsSectionTitle("Monet 色彩风格")
                    if (!settings.monetEnabled) SettingsHint("启用 Monet 颜色后应用所选风格。")
                    MonetStyle.entries.forEach { style ->
                        StyleChoice(style.label, style.description, settings.paletteStyle == style) {
                            onChange(settings.copy(paletteStyle = style))
                        }
                    }
                }
            }
            item {
                SettingsCard {
                    SettingsSectionTitle("Monet 色彩标准")
                    SettingsHint("四种色彩风格均支持以下标准，标准会实际改变调色板的生成方式。")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MonetSpec.entries.forEach { spec ->
                            SettingChip(spec.label, settings.colorSpec == spec, Modifier.weight(1f)) {
                                onChange(settings.copy(colorSpec = spec))
                            }
                        }
                    }
                }
            }
            item(key = "app_color_launcher_icon") {
                SettingsCard {
                    SettingsSectionTitle("随应用配色的桌面图标")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Image(bitmap = iconPreview, contentDescription = null, modifier = Modifier.size(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text("hnubl", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            SettingsHint("背景与开始流程按钮同色，锁为白色。")
                        }
                    }
                    SettingsHint("添加一个随应用配色更新的桌面入口，可替换原桌面图标；调整强调色或 Monet 配色后自动同步。")
                    Button(
                        onClick = {
                            iconRequestBusy = true
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { AppLauncherIcon.request(context, iconColor.toArgb()) }
                                    val message = when (result) {
                                        AppLauncherIcon.UpdateResult.PIN_REQUESTED -> "请在桌面弹窗中确认添加"
                                        AppLauncherIcon.UpdateResult.UPDATED, AppLauncherIcon.UpdateResult.UNCHANGED -> "桌面图标已与当前按钮颜色同步"
                                        AppLauncherIcon.UpdateResult.UNSUPPORTED -> "当前桌面暂不支持添加此入口"
                                        AppLauncherIcon.UpdateResult.RATE_LIMITED -> "桌面更新较频繁，请稍后再试"
                                        AppLauncherIcon.UpdateResult.NOT_PINNED -> "请重新添加桌面图标"
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                } catch (error: Exception) {
                                    Toast.makeText(context, "添加桌面图标失败：${error.message ?: "请稍后重试"}", Toast.LENGTH_LONG).show()
                                } finally {
                                    iconRequestBusy = false
                                }
                            }
                        }, enabled = !iconRequestBusy, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(if (iconRequestBusy) "正在更新…" else "添加或更新桌面图标") }
                }
            }
            item(key = "github_footer") { ProjectRepositoryFooter() }
        }
    }
}

@Composable
private fun SettingsDetailRow(title: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        icon()
        Text(title, modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        PageChevron(Modifier.size(20.dp), MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp, lineHeight = 19.sp)
}

@Composable
private fun BlurSetting(title: String, description: String, value: Float, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("${value.roundToInt()} dp", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        SettingsHint(description)
        Slider(value = value, onValueChange = onChange, valueRange = 0f..60f, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SettingsToggle(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            SettingsHint(description)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun PalettePreview() {
    val colors = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.primaryContainer).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Aa", modifier = Modifier.weight(1f), color = colors.onPrimaryContainer, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        listOf(colors.primary, colors.secondary, colors.primaryVariant, colors.surface).forEach { color ->
            Box(Modifier.size(26.dp).clip(CircleShape).background(color).border(1.dp, colors.onPrimaryContainer.copy(alpha = 0.10f), CircleShape))
        }
    }
}

@Composable
private fun AccentChoices(selected: AccentOption, onSelect: (AccentOption) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AccentOption.entries.chunked(3).forEach { choices ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { option ->
                    SettingChip(option.label, option == selected, Modifier.weight(1f), option.argb?.let(::Color)) { onSelect(option) }
                }
                repeat(3 - choices.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SettingChip(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    swatch: Color? = null,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.clip(shape).background(if (selected) colors.primaryContainer else colors.surface)
            .border(1.dp, if (selected) colors.primary.copy(alpha = 0.55f) else colors.onSurface.copy(alpha = 0.08f), shape)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 9.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(swatch))
            Spacer(Modifier.width(5.dp))
        }
        Text(title, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface)
    }
}

@Composable
private fun StyleChoice(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) colors.primaryContainer else colors.surface)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface)
            SettingsHint(description)
        }
        Box(Modifier.size(20.dp).border(if (selected) 6.dp else 1.5.dp, if (selected) colors.primary else colors.onSurface.copy(alpha = 0.35f), CircleShape))
    }
}
