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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PROJECT_REPOSITORY_URL = "https://github.com/hawyn2018/honor_8e5_ubl"

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectRepositoryFooter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier.fillMaxWidth()
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "访问 GitHub 项目",
                onLongClickLabel = "复制 GitHub 链接",
                onClick = {
                    runCatching { uriHandler.openUri(PROJECT_REPOSITORY_URL) }
                        .onFailure {
                            Toast.makeText(context, "打开链接失败，请长按复制后访问", Toast.LENGTH_LONG).show()
                        }
                },
                onLongClick = {
                    runCatching {
                        checkNotNull(context.getSystemService(ClipboardManager::class.java))
                            .setPrimaryClip(ClipData.newPlainText("honor_ubl GitHub", PROJECT_REPOSITORY_URL))
                    }.onSuccess {
                        Toast.makeText(context, "已复制 GitHub 链接", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "复制链接失败，请稍后重试", Toast.LENGTH_LONG).show()
                    }
                },
            )
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            PROJECT_REPOSITORY_URL,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 12.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}
