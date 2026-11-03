package dev.honor.bootforge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.honor.bootforge.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SupportPage(contentPadding: PaddingValues) {
    BoxWithConstraints(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
        val wide = maxWidth >= 640.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "thanks") { SupportMessage() }
            if (wide) {
                item(key = "payment_codes") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top) {
                        SupportCode(R.drawable.support_wechat, "微信支付支持二维码", 1688f / 2332f, Modifier.weight(1f))
                        SupportCode(R.drawable.support_alipay, "支付宝支持二维码", 1182f / 1772f, Modifier.weight(1f))
                    }
                }
            } else {
                item(key = "wechat") { SupportCode(R.drawable.support_wechat, "微信支付支持二维码", 1688f / 2332f) }
                item(key = "alipay") { SupportCode(R.drawable.support_alipay, "支付宝支持二维码", 1182f / 1772f) }
            }
            item(key = "github_footer") { ProjectRepositoryFooter() }
        }
    }
}

@Composable
private fun SupportMessage() {
    val colors = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp, insideMargin = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(colors.primary.copy(alpha = .10f), colors.surface)))
            .padding(24.dp)) {
            SupportHeart(Modifier.size(48.dp))
            Spacer(Modifier.height(20.dp))
            Text("作者研发不易", fontSize = 13.sp, lineHeight = 22.sp, color = colors.onSurfaceVariantSummary)
            Spacer(Modifier.height(6.dp))
            Text("感谢您的支持", fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("您的支持是我马不停蹄研发的动力", fontSize = 13.sp, lineHeight = 22.sp,
                color = colors.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun SupportCode(resource: Int, description: String, ratio: Float, modifier: Modifier = Modifier) {
    Image(painter = painterResource(resource), contentDescription = description,
        modifier = modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(26.dp)),
        contentScale = ContentScale.Fit)
}

@Composable
internal fun SupportHeart(modifier: Modifier = Modifier) {
    val color = MiuixTheme.colorScheme.primary
    Box(modifier.clip(CircleShape).background(color.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(11.dp)) {
            val heart = Path().apply {
                moveTo(size.width * .5f, size.height * .88f)
                cubicTo(-size.width * .16f, size.height * .45f, size.width * .02f, -size.height * .13f,
                    size.width * .5f, size.height * .22f)
                cubicTo(size.width * .98f, -size.height * .13f, size.width * 1.16f, size.height * .45f,
                    size.width * .5f, size.height * .88f)
                close()
            }
            drawPath(heart, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
internal fun PageChevron(modifier: Modifier, color: Color, pointsRight: Boolean = true) {
    Canvas(modifier) {
        val start = if (pointsRight) .36f else .64f
        val tip = 1f - start
        val stroke = 1.8.dp.toPx()
        drawLine(color, Offset(size.width * start, size.height * .22f),
            Offset(size.width * tip, size.height * .5f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * tip, size.height * .5f),
            Offset(size.width * start, size.height * .78f), stroke, StrokeCap.Round)
    }
}
