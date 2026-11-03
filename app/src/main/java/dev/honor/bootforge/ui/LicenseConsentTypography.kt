package dev.honor.bootforge.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Equal line boxes for both single-line contacts and wrapped consent notes. */
@Composable
internal fun licenseConsentSmallTextStyle(
    trim: LineHeightStyle.Trim = LineHeightStyle.Trim.None,
): TextStyle = MiuixTheme.textStyles.main.copy(
    fontSize = 12.sp,
    lineHeight = 21.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = trim,
    ),
)
