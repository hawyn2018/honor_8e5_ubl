package dev.honor.bootforge.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Both bars sample the same scrolling content; their foreground is never passed through a blur. */
@Composable
internal fun GlassTopBar(backdrop: GlassBackdrop?, title: String, blurDp: Float, statusBarHeight: Dp) {
    val surface = MiuixTheme.colorScheme.surface
    Column(
        Modifier.fillMaxWidth()
            .blurBackground(backdrop, blurDp, surface)
            .padding(top = statusBarHeight, bottom = 2.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(50.dp).padding(start = 26.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), fontSize = MiuixTheme.textStyles.title3.fontSize, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.widthIn(min = 36.dp).height(36.dp).clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = .10f))
                .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text("honor_ubl", fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun GlassBottomBar(
    backdrop: GlassBackdrop?,
    selected: Int,
    selectionPosition: () -> Float,
    appearance: AppearanceSettings,
    navigationBottom: Dp,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val floating = appearance.floatingBottomBar
    val liquid = appearance.liquidGlass
    val surface = MiuixTheme.colorScheme.surface
    val primary = MiuixTheme.colorScheme.primary
    val dark = isSystemInDarkTheme()
    val tabs = listOf("提权", "备份", "解锁", "设置")
    val shape = if (floating) RoundedCornerShape(31.dp) else RectangleShape
    val container = if (floating) Modifier.padding(start = 22.dp, end = 22.dp, bottom = navigationBottom + 13.dp)
        else Modifier
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        // Floating mode draws only the clipped capsule. Its margins and system
        // navigation inset display the unmodified page underneath.
        if (!floating) {
            Box(Modifier.matchParentSize()
                .blurBackground(backdrop, appearance.bottomBlurDp, surface))
        }
        Row(
            container.widthIn(max = 540.dp).fillMaxWidth()
                .glassSurface(backdrop, shape, appearance.bottomBlurDp, liquid, surface, dark)
                .padding(start = 7.dp, end = 7.dp, top = 7.dp, bottom = if (floating) 7.dp else navigationBottom + 7.dp)
                .drawBehind {
                    val gap = 4.dp.toPx()
                    val lastIndex = tabs.lastIndex.toFloat()
                    val itemWidth = (size.width - gap * lastIndex).coerceAtLeast(0f) / tabs.size
                    val position = selectionPosition().coerceIn(0f, lastIndex)
                    val visualPosition = if (layoutDirection == LayoutDirection.Rtl) lastIndex - position else position
                    drawRoundRect(
                        color = primary.copy(alpha = .13f),
                        topLeft = Offset((itemWidth + gap) * visualPosition, 0f),
                        size = Size(itemWidth, size.height),
                        cornerRadius = CornerRadius(24.dp.toPx()),
                    )
                }
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { index, label ->
                val active = selected == index
                val color by animateColorAsState(if (active) primary else MiuixTheme.colorScheme.onSurfaceVariantSummary, label = "navigation color")
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                        .semantics { contentDescription = "$label 页面" }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NavigationGlyph(index, color, Modifier.size(25.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(label, fontSize = 11.sp, color = color, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

/** Opaque handle keeps Android 13-only library types out of the common screen and navigation. */
internal interface GlassBackdrop {
    fun capture(modifier: Modifier): Modifier
    fun blurBackground(modifier: Modifier, blurDp: Float): Modifier
    fun glassSurface(modifier: Modifier, shape: Shape, blurDp: Float, liquid: Boolean, surface: Color, dark: Boolean): Modifier
}

private fun Modifier.blurBackground(backdrop: GlassBackdrop?, blurDp: Float, surface: Color): Modifier {
    if (backdrop == null || Build.VERSION.SDK_INT < 33) return background(surface)
    return backdrop.blurBackground(this, blurDp)
}

private fun Modifier.glassSurface(
    backdrop: GlassBackdrop?, shape: Shape, blurDp: Float, liquid: Boolean, surface: Color, dark: Boolean,
): Modifier {
    // No blur library class, shader closure or renderer is instantiated on Android 9–12.
    if (backdrop == null || Build.VERSION.SDK_INT < 33) return clip(shape).background(surface.copy(alpha = .94f))
    return backdrop.glassSurface(this, shape, blurDp, liquid, surface, dark)
}

@Composable
private fun NavigationGlyph(index: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * .072f
        when (index) {
            0 -> {
                val shield = Path().apply {
                    moveTo(w * .50f, h * .09f)
                    lineTo(w * .84f, h * .22f)
                    lineTo(w * .80f, h * .57f)
                    cubicTo(w * .77f, h * .74f, w * .62f, h * .86f, w * .50f, h * .91f)
                    cubicTo(w * .38f, h * .86f, w * .23f, h * .74f, w * .20f, h * .57f)
                    lineTo(w * .16f, h * .22f)
                    close()
                }
                drawPath(shield, color, style = Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                val bolt = Path().apply {
                    moveTo(w * .56f, h * .30f)
                    lineTo(w * .40f, h * .52f)
                    lineTo(w * .59f, h * .52f)
                    lineTo(w * .44f, h * .73f)
                }
                drawPath(bolt, color, style = Stroke(stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            1 -> {
                drawRoundRect(color, Offset(w * .14f, h * .27f), Size(w * .72f, h * .56f), CornerRadius(w * .10f), style = Stroke(stroke))
                drawLine(color, Offset(w * .14f, h * .41f), Offset(w * .86f, h * .41f), stroke)
                drawLine(color, Offset(w * .40f, h * .60f), Offset(w * .60f, h * .60f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .25f, h * .17f), Offset(w * .75f, h * .17f), stroke, StrokeCap.Round)
            }
            2 -> {
                drawRoundRect(color, Offset(w * .17f, h * .41f), Size(w * .66f, h * .44f), CornerRadius(w * .10f), style = Stroke(stroke))
                val shackle = Path().apply {
                    moveTo(w * .30f, h * .43f)
                    lineTo(w * .30f, h * .29f)
                    cubicTo(w * .30f, h * .07f, w * .74f, h * .07f, w * .74f, h * .29f)
                }
                drawPath(shackle, color, style = Stroke(stroke, cap = StrokeCap.Round))
                drawLine(color, Offset(w * .50f, h * .60f), Offset(w * .50f, h * .70f), stroke, StrokeCap.Round)
            }
            else -> {
                val gear = Path()
                for (i in 0 until 24) {
                    val angle = i * Math.PI / 12.0 - Math.PI / 2
                    val radius = w * if (i % 4 == 0 || i % 4 == 3) .42f else .34f
                    val point = Offset(w * .5f + kotlin.math.cos(angle).toFloat() * radius, h * .5f + kotlin.math.sin(angle).toFloat() * radius)
                    if (i == 0) gear.moveTo(point.x, point.y) else gear.lineTo(point.x, point.y)
                }
                gear.close()
                drawPath(gear, color, style = Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawCircle(color, w * .14f, Offset(w * .5f, h * .5f), style = Stroke(stroke))
            }
        }
    }
}
