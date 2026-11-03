package dev.honor.bootforge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val QQ_GROUP_NUMBER = "941336897"
private const val QQ_GROUP_URL = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=941336897&card_type=group&source=qrcode"
private const val XIANYU_USERNAME = "2018hawyn"
private const val XIANYU_URL = "https://m.tb.cn/h.jgDi6un?tk=4QbA6Z6JhAO"
private const val COOLAPK_USERNAME = "hawyn"
private const val COOLAPK_APP_URL = "coolmarket://u/43879986"
private const val COOLAPK_PROFILE_URL = "https://www.coolapk.com/u/43879986"
private const val TELEGRAM_GROUP_USERNAME = "blroot"
private const val TELEGRAM_GROUP_URL = "https://t.me/blroot"
private const val PROJECT_REPOSITORY_URL = "https://github.com/hawyn2018/honor_8e5_ubl"

@Composable
internal fun ProjectRepositoryFooter(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    shortRepositoryLabel: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "作者官方联系方式如下，其他来源请勿轻信！",
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = if (compact) 0.dp else 12.dp),
            fontSize = 12.sp,
            lineHeight = if (compact) 21.sp else 20.sp,
            style = if (compact) licenseConsentSmallTextStyle(trim = LineHeightStyle.Trim.FirstLineTop)
                else MiuixTheme.textStyles.main,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        FooterContactRow(
            compact = compact,
            text = "QQ交流群：$QQ_GROUP_NUMBER",
            openUrl = QQ_GROUP_URL,
            copyText = QQ_GROUP_NUMBER,
            openLabel = "打开QQ群入口",
            copyLabel = "QQ群号",
            openError = "打开QQ群入口失败，可长按复制群号后在QQ中搜索",
        )
        FooterContactRow(
            compact = compact,
            text = "闲鱼:$XIANYU_USERNAME",
            openUrl = XIANYU_URL,
            copyText = XIANYU_URL,
            openLabel = "打开闲鱼链接",
            copyLabel = "闲鱼链接",
        )
        FooterContactRow(
            compact = compact,
            text = "酷安:$COOLAPK_USERNAME",
            openUrl = COOLAPK_APP_URL,
            fallbackUrl = COOLAPK_PROFILE_URL,
            copyText = COOLAPK_USERNAME,
            openLabel = "打开酷安主页",
            copyLabel = "酷安用户名",
        )
        FooterContactRow(
            compact = compact,
            text = "telegram群组: $TELEGRAM_GROUP_USERNAME",
            openUrl = TELEGRAM_GROUP_URL,
            copyText = TELEGRAM_GROUP_USERNAME,
            openLabel = "打开Telegram群组",
            copyLabel = "Telegram群组名",
        )
        FooterContactRow(
            compact = compact,
            compactTrim = LineHeightStyle.Trim.LastLineBottom,
            text = if (shortRepositoryLabel) PROJECT_REPOSITORY_URL.removePrefix("https://") else PROJECT_REPOSITORY_URL,
            openUrl = PROJECT_REPOSITORY_URL,
            copyText = PROJECT_REPOSITORY_URL,
            openLabel = "访问GitHub项目",
            copyLabel = "GitHub链接",
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FooterContactRow(
    compact: Boolean,
    compactTrim: LineHeightStyle.Trim = LineHeightStyle.Trim.None,
    text: String,
    openUrl: String,
    copyText: String,
    openLabel: String,
    copyLabel: String,
    fallbackUrl: String? = null,
    openError: String = "打开链接失败，请长按复制后访问",
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Text(
        text,
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(
                role = Role.Button,
                onClickLabel = openLabel,
                onLongClickLabel = "复制$copyLabel",
                onClick = {
                    runCatching { uriHandler.openUri(openUrl) }
                        .recoverCatching { error ->
                            if (fallbackUrl == null) throw error
                            uriHandler.openUri(fallbackUrl)
                        }
                        .onFailure { Toast.makeText(context, openError, Toast.LENGTH_LONG).show() }
                },
                onLongClick = {
                    runCatching {
                        checkNotNull(context.getSystemService(ClipboardManager::class.java))
                            .setPrimaryClip(ClipData.newPlainText("honor_ubl $copyLabel", copyText))
                    }.onSuccess {
                        Toast.makeText(context, "已复制$copyLabel", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "复制失败，请稍后重试", Toast.LENGTH_LONG).show()
                    }
                },
            )
            .padding(horizontal = 8.dp, vertical = if (compact) 0.dp else 12.dp),
        fontSize = 12.sp,
        lineHeight = if (compact) 21.sp else 20.sp,
        style = if (compact) licenseConsentSmallTextStyle(trim = compactTrim) else MiuixTheme.textStyles.main,
        textAlign = TextAlign.Center,
        color = MiuixTheme.colorScheme.primary,
    )
}
