package dev.honor.bootforge.ui

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
fun SettingsPage(
    settings: AppearanceSettings,
    onChange: (AppearanceSettings) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text("随心设定", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    SettingsHint("玻璃效果、强调色与动态调色板，修改后即时生效。")
                }
            }
            item {
                SettingsCard {
                    SettingsSectionTitle("玻璃与底栏")
                    BlurSetting("顶部模糊度", "状态栏与悬浮顶栏共用一个玻璃背景", settings.topBlurDp) {
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
                    SettingsHint("取色关闭时，系统选项使用默认蓝色；选择指定强调色后，以指定颜色为种子。")
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
        }
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
private fun SettingsToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            SettingsHint(description)
        }
        Switch(checked = checked, onCheckedChange = onChange)
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
