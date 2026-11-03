package dev.honor.bootforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun StartupCheckingPage() {
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Text("正在检查已有授权…", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
