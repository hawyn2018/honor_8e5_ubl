package dev.honor.bootforge.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Both settings details use the same full-window chrome and return animation. */
@Composable
internal fun SupportScene(
    appearance: AppearanceSettings,
    contentPadding: PaddingValues,
    statusTop: Dp,
    navigationBottom: Dp,
    onBack: () -> Unit,
    page: SettingsDetailPage = SettingsDetailPage.SUPPORT,
) {
    val backdrop = if (Build.VERSION.SDK_INT >= 33) GlassRendererApi33.rememberBackdrop() else null
    val pagePadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = navigationBottom)
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize()
            .let { if (backdrop != null && Build.VERSION.SDK_INT >= 33) backdrop.capture(it) else it }
            .background(MiuixTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
            when (page) {
                SettingsDetailPage.SUPPORT -> SupportPage(pagePadding)
                SettingsDetailPage.LICENSE -> LicensePage(pagePadding)
            }
        }
        GlassTopBar(backdrop, page.title, appearance.topBlurDp, statusTop, onBack = onBack)
    }
}
