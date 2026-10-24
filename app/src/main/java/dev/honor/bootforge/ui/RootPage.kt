package dev.honor.bootforge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.honor.bootforge.wireless.WirelessUiState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class RootUiState(
    val busy: Boolean = false,
    val shizukuStatus: String = "尚未授权",
    val rootReady: Boolean = false,
    val deployed: Boolean = false,
    val stage: String = "等待 Shell 就绪",
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

data class WirelessActions(
    val onHost: (String) -> Unit,
    val onPairingPort: (String) -> Unit,
    val onConnectPort: (String) -> Unit,
    val onPairingCode: (String) -> Unit,
    val onPreparePairing: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onPair: () -> Unit,
    val onConnect: () -> Unit,
    val onCheck: () -> Unit,
    val onCancel: () -> Unit,
)

@Composable
fun RootPage(
    rootState: RootUiState,
    wirelessState: WirelessUiState,
    operationBusy: Boolean,
    padding: PaddingValues,
    wirelessActions: WirelessActions,
    onDeploy: () -> Unit,
    onRun: () -> Unit,
    onCheck: () -> Unit,
    onClean: () -> Unit,
) {
    val foreignBusy = operationBusy && !wirelessState.busy && !wirelessState.waiting && !rootState.busy
    val wirelessBusy = foreignBusy || rootState.busy || wirelessState.busy
    val rootBusy = wirelessBusy || wirelessState.waiting
    val pairingValid = wirelessState.host.isNotBlank() && validPort(wirelessState.pairingPort) &&
        wirelessState.pairingCode.length == 6 && wirelessState.pairingCode.all { it in '0'..'9' }
    val connectValid = wirelessState.host.isNotBlank() && validPort(wirelessState.connectPort)
    LazyColumn(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                RootBadge(if (wirelessState.shellReady) "Shell · UID 2000" else "权限与连接")
                Spacer(Modifier.height(18.dp))
                Text("连接 Shell，\n准备后续操作", fontSize = 34.sp, lineHeight = 45.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                RootHint("在本应用内完成无线调试配对，建立 Shell 通道；匹配成功后自动部署并执行 MagicRoot 提权链，Root 直接授予本应用。")
            }
        }
        item {
            RootPanel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("无线调试 · Shell", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    RootBadge(when {
                        wirelessState.busy -> "处理中"
                        wirelessState.shellReady -> "已连接"
                        wirelessState.waiting -> "等待配对"
                        else -> "待连接"
                    })
                }
                Spacer(Modifier.height(10.dp))
                RootInfoLine("Shell 权限", if (wirelessState.shellReady) "通道已就绪 · UID 2000" else "等待配对或连接")
                RootInfoLine("配对状态", if (wirelessState.paired) "已完成配对，可直接连接" else "等待完成配对")
                RootDivider()
                RootInfoLine("自动发现", wirelessState.discovery)
                Spacer(Modifier.height(8.dp))
                RootHint(wirelessState.stage)
                Spacer(Modifier.height(10.dp))
                RootHint("Shell 使用 UID 2000，使用时请保持无线调试开启。Root 命令经同一 Shell 通道由 bfexec 提权执行。")
                RootError(wirelessState.error)
            }
        }
        item {
            RootPanel {
                Text("配对与连接", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                RootHint("配对端口用于输入配对码；连接端口来自无线调试主页。两者分别填写，也可自动发现。")
                Spacer(Modifier.height(14.dp))
                TextField(
                    value = wirelessState.host,
                    onValueChange = { wirelessActions.onHost(it.replace("\n", "").take(253)) },
                    label = "本机地址 · 默认 127.0.0.1",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !wirelessBusy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(10.dp))
                RootNumberField("配对端口", wirelessState.pairingPort, 5, !wirelessBusy, wirelessActions.onPairingPort)
                Spacer(Modifier.height(10.dp))
                RootNumberField("6 位配对码", wirelessState.pairingCode, 6, !wirelessBusy, wirelessActions.onPairingCode)
                Spacer(Modifier.height(10.dp))
                RootNumberField("连接端口 · 无线调试主页", wirelessState.connectPort, 5, !wirelessBusy, wirelessActions.onConnectPort)
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = wirelessActions.onPreparePairing,
                    enabled = !wirelessBusy && !wirelessState.waiting,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                ) {
                    Text(if (wirelessState.waiting) "正在等待配对码…" else "开始配对 / 自动发现")
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = wirelessActions.onOpenSettings, enabled = !wirelessBusy, modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                    Text("打开开发者选项")
                }
                Spacer(Modifier.height(14.dp))
                RootHint("打开无线调试中的“使用配对码配对设备”，保持该窗口，展开本应用通知并输入配对码；也可分屏在本页填写端口和配对码。")
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = wirelessActions.onPair,
                    enabled = !wirelessBusy && pairingValid,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("配对并获取 Shell", fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = wirelessActions.onConnect,
                    enabled = !wirelessBusy && connectValid,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                ) { Text("连接并获取 Shell") }
                Spacer(Modifier.height(10.dp))
                Button(onClick = wirelessActions.onCheck, enabled = !wirelessBusy, modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                    Text("检查 Shell")
                }
                if (wirelessState.waiting || wirelessState.busy) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = wirelessActions.onCancel, modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                        Text("结束配对")
                    }
                }
            }
        }
        item {
            RootPanel {
                Text("Shell 日志", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                RootConsole(wirelessState.logs, "Shell console\n等待无线调试配对…")
            }
        }
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                Text("MagicRoot · 提权", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text("提权时间可能较长请耐心等待", fontSize = 13.sp, lineHeight = 22.sp, color = Color(0xFFE53935))
                Spacer(Modifier.height(8.dp))
                RootHint("已接入 MagicRoot（CVE-2026-64560）提权链：Shell 就绪后自动部署并执行；成功后 Root 仅授予本应用，不安装 ksud、不加冕 KernelSU 管理器。")
            }
        }
        item {
            RootPanel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Root 提权", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    RootBadge(if (rootState.busy) "进行中" else if (rootState.rootReady) "已就绪" else "待提权")
                }
                Spacer(Modifier.height(10.dp))
                RootInfoLine("提权通道", if (rootState.rootReady) "已就绪" else "等待 Shell 就绪或手动提权")
                RootDivider()
                RootInfoLine("提权资产", if (rootState.deployed) "已部署" else "尚未部署")
                Spacer(Modifier.height(8.dp))
                RootHint(rootState.stage)
                Spacer(Modifier.height(10.dp))
                RootHint("配对成功后将自动部署并执行提权；成功后 Root 通道驻留至重启或手动清理，可分别前往备份页和解锁页启动任务。")
                RootError(rootState.error)
                Spacer(Modifier.height(18.dp))
                Button(onClick = onDeploy, enabled = !rootBusy, modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                    Text("部署提权资产")
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onRun,
                    enabled = !rootBusy,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(if (rootState.busy) "正在处理…" else "执行提权流程", fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(10.dp))
                Button(onClick = onCheck, enabled = !rootBusy, modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                    Text("只读检查提权状态")
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = onClean, enabled = !rootBusy, modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                    Text("清理提权临时文件")
                }
            }
        }
        item {
            RootPanel {
                Text("提权日志", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                RootHint("长按日志可选择并复制。")
                Spacer(Modifier.height(12.dp))
                RootConsole(rootState.logs, "console\n等待用户接入…")
            }
        }
    }
}

private fun validPort(value: String) = value.toIntOrNull()?.let { it in 1..65535 } == true

@Composable
private fun RootNumberField(label: String, value: String, limit: Int, enabled: Boolean, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = { onChange(it.filter { character -> character in '0'..'9' }.take(limit)) },
        modifier = Modifier.fillMaxWidth(),
        label = label,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
    )
}

@Composable
private fun RootError(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            Text(error, fontSize = 12.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RootConsole(logs: List<String>, emptyText: String) {
    val context = LocalContext.current
    val completeLog = logs.joinToString("\n")
    Column {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 260.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = .05f)).padding(13.dp),
        ) {
            SelectionContainer {
                Text(
                    text = if (logs.isEmpty()) emptyText else completeLog,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    fontSize = 10.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
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
            enabled = logs.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
        ) {
            Text("复制全部日志")
        }
    }
}

@Composable
private fun RootPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(20.dp), content = content)
}

@Composable
private fun RootHint(text: String) {
    Text(text, fontSize = 13.sp, lineHeight = 22.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun RootInfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(86.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RootBadge(text: String) {
    val primary = MiuixTheme.colorScheme.primary
    Box(Modifier.background(primary.copy(alpha = .10f), RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = primary)
    }
}

@Composable
private fun RootDivider() {
    Box(Modifier.fillMaxWidth().height(.5.dp).background(MiuixTheme.colorScheme.dividerLine.copy(alpha = .6f)))
}
