package dev.honor.bootforge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class LicenseLanguage(val label: String, val asset: String) {
    CHINESE("中文译文", "licenses/agpl-3.0-zh-CN.txt"),
    ENGLISH("English 原文", "licenses/agpl-3.0-en.txt"),
}

private data class LicenseDocument(val paragraphs: List<String>, val failed: Boolean = false)
private val translatedLicenseHeadings = setOf("前言", "条款和条件", "条款和条件结束", "如何将这些条款应用于你的新程序")

/** Shared project-scope notice; the AGPL text and upstream notices remain separate. */
internal object ProjectLicenseNotice {
    const val SCOPE = "本项目依据 GNU Affero 通用公共许可证第 3 版（AGPLv3）发布，范围包括 hnubl 应用、随附修补器、OTA 模块、KBL 微内核及相关项目源代码。"
    const val THIRD_PARTY = "第三方代码、工具和预置镜像的版权及许可声明予以保留，并继续适用各自的许可。"
    const val WARRANTY = "本软件及上述项目组件按“现状”提供；担保与责任限制以 AGPLv3 第 15—17 条及适用法律为准。请阅读完整许可证后确认。"
}

/** Complete offline texts share the same scene, toolbar and motion as SupportPage. */
@Composable
internal fun LicensePage(
    contentPadding: PaddingValues,
    onAgree: (() -> Unit)? = null,
    savingConsent: Boolean = false,
    consentError: String = "",
) {
    val context = LocalContext.current
    var language by rememberSaveable { mutableStateOf(LicenseLanguage.CHINESE) }
    // Load both small offline texts once; switching language keeps the same list
    // and never temporarily replaces its contents with a loading-only page.
    val documents by produceState<Map<LicenseLanguage, LicenseDocument>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            LicenseLanguage.entries.associateWith { option ->
                try {
                    val text = context.assets.open(option.asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    LicenseDocument(text.replace("\r\n", "\n").split(Regex("\\n[\\t ]*\\n"))
                        .map { it.trim() }.filter(String::isNotEmpty))
                } catch (_: Exception) {
                    LicenseDocument(emptyList(), failed = true)
                }
            }
        }
    }
    val document = documents?.get(language)
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "license_intro") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp,
                    insideMargin = PaddingValues(22.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LicenseDocumentIcon(Modifier.size(46.dp))
                        Text("软件及组件许可说明", fontSize = 18.sp, lineHeight = 27.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text(ProjectLicenseNotice.SCOPE, fontSize = 13.sp, lineHeight = 22.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text(ProjectLicenseNotice.THIRD_PARTY, fontSize = 12.sp, lineHeight = 21.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text(ProjectLicenseNotice.WARRANTY, fontSize = 12.sp, lineHeight = 21.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text("GNU Affero 通用公共许可证", fontSize = 26.sp, lineHeight = 35.sp,
                            fontWeight = FontWeight.Bold)
                        Text("AGPL v3.0 · 2007 年 11 月 19 日", fontSize = 12.sp, lineHeight = 20.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text("本应用以 GNU AGPL v3.0 发布，以下提供完整的英文原文与中文译文。",
                            fontSize = 13.sp, lineHeight = 22.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text("中文由 AI 依据 AGPLv3 英文原文翻译，为非官方译文；条款以英文原文为准。",
                            fontSize = 12.sp, lineHeight = 20.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LicenseLanguage.entries.forEach { option ->
                                val selected = language == option
                                Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                    .background(if (selected) MiuixTheme.colorScheme.primaryContainer
                                        else MiuixTheme.colorScheme.surface)
                                    .clickable(role = Role.RadioButton) { language = option }
                                    .padding(horizontal = 10.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
                                    Text(option.label, fontSize = 14.sp,
                                        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }
            val loaded = document
            if (loaded == null) {
                item(key = "loading") { Text("正在加载许可证…", fontSize = 13.sp) }
            } else if (loaded.failed) {
                item(key = "load_error") { Text("许可证文件读取失败，请重新打开页面。", fontSize = 13.sp) }
            } else {
                itemsIndexed(loaded.paragraphs, key = { index, _ -> "license_paragraph_$index" }) { _, paragraph ->
                    val heading = paragraph.length < 90 && (paragraph.startsWith("【") ||
                        paragraph.matches(Regex("[〇一二三四五六七八九十]+、.*")) ||
                        paragraph.matches(Regex("\\d+\\..*")) ||
                        paragraph in translatedLicenseHeadings ||
                        paragraph == "Preamble" || paragraph == "TERMS AND CONDITIONS" ||
                        paragraph == "END OF TERMS AND CONDITIONS" || paragraph == "How to Apply These Terms to Your New Programs")
                    SelectionContainer {
                        Text(paragraph, modifier = Modifier.padding(horizontal = 6.dp),
                            fontSize = if (heading) 15.sp else 13.sp,
                            lineHeight = if (heading) 24.sp else 22.sp,
                            fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal,
                            color = MiuixTheme.colorScheme.onSurface)
                    }
                }
            }
            item(key = "license_sources") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp,
                    insideMargin = PaddingValues(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LicenseSourceLink("英文原文：GNU AGPL v3.0", "https://www.gnu.org/licenses/agpl-3.0.html")
                    }
                }
            }
            item(key = "license_contact_footer") { ProjectRepositoryFooter(shortRepositoryLabel = true) }
            if (onAgree != null && loaded != null && !loaded.failed) {
                item(key = "license_agree") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (consentError.isNotEmpty()) {
                            Text(consentError, fontSize = 12.sp, lineHeight = 20.sp, color = Color(0xFFE53935))
                        }
                        Button(onClick = onAgree, enabled = !savingConsent,
                            modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp,
                            colors = ButtonDefaults.buttonColorsPrimary()) {
                            Text(if (savingConsent) "正在保存…" else "我同意", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseSourceLink(label: String, url: String) {
    val context = LocalContext.current
    Text(label, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }, color = MiuixTheme.colorScheme.primary, fontSize = 12.sp, lineHeight = 20.sp)
}

@Composable
internal fun LicenseDocumentIcon(modifier: Modifier = Modifier) {
    val color = MiuixTheme.colorScheme.primary
    Box(modifier.clip(CircleShape).background(color.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(9.dp)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * .2f, h * .05f)
                lineTo(w * .63f, h * .05f)
                lineTo(w * .83f, h * .27f)
                lineTo(w * .83f, h * .94f)
                lineTo(w * .2f, h * .94f)
                close()
                moveTo(w * .61f, h * .07f)
                lineTo(w * .61f, h * .29f)
                lineTo(w * .8f, h * .29f)
            }
            drawPath(path, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
            for (y in listOf(.48f, .65f, .81f)) {
                drawLine(color, Offset(w * .34f, h * y), Offset(w * .68f, h * y),
                    strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
