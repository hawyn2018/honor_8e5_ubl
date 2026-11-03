package dev.honor.bootforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A modal foreground card over the already prepared, frosted application. */
@Composable
internal fun LicenseConsentScreen(saving: Boolean, error: String, onAgree: () -> Unit, onExit: () -> Unit) {
    var reading by rememberSaveable { mutableStateOf(false) }
    BackHandler { /* The license prompt cannot be dismissed to reveal operations. */ }
    Dialog(onDismissRequest = {}, properties = DialogProperties(
        dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false,
    )) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setDimAmount(.08f) }
        if (reading) {
            Card(modifier = Modifier.padding(18.dp).widthIn(max = 760.dp).fillMaxSize(.94f),
                cornerRadius = 28.dp, insideMargin = PaddingValues(0.dp)) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("AGPLv3 许可证", modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Button(onClick = { reading = false }, cornerRadius = 14.dp) { Text("返回") }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LicensePage(PaddingValues(top = 4.dp, bottom = 14.dp),
                            onAgree = onAgree, savingConsent = saving, consentError = error)
                    }
                }
            }
        } else {
            Card(modifier = Modifier.padding(24.dp).widthIn(max = 480.dp).fillMaxWidth(),
                cornerRadius = 28.dp, insideMargin = PaddingValues(24.dp)) {
                // Match text-to-text whitespace with the visible button edge.
                val contactBottomInset = with(LocalDensity.current) { 5.sp.toDp() }
                Column(Modifier.heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Only the text area scrolls; the consent actions stay visible.
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LicenseDocumentIcon(Modifier.size(46.dp))
                        Text("开源许可证", fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                        Text(ProjectLicenseNotice.SCOPE, fontSize = 14.sp, lineHeight = 23.sp)
                        Text("本软件按“现状”提供，完整条款请阅读许可证。",
                            style = licenseConsentSmallTextStyle(trim = LineHeightStyle.Trim.Both),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        ProjectRepositoryFooter(modifier = Modifier.padding(bottom = contactBottomInset),
                            compact = true, shortRepositoryLabel = true)
                        Button(onClick = { reading = true }, enabled = !saving, modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                            Text("阅读完整许可证")
                        }
                    }
                    if (error.isNotEmpty()) Text(error, fontSize = 12.sp, lineHeight = 20.sp, color = Color(0xFFE53935))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onExit, enabled = !saving, modifier = Modifier.weight(1f), cornerRadius = 16.dp) { Text("退出") }
                        Button(onClick = onAgree, enabled = !saving, modifier = Modifier.weight(1f), cornerRadius = 16.dp,
                            colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (saving) "正在保存…" else "同意", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}
