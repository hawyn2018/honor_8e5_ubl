package dev.honor.bootforge.ui

import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

/** This class and every Miuix blur/shader type are reached only inside the Android 13 branch. */
@RequiresApi(33)
internal object GlassRendererApi33 {
    @Composable
    fun rememberBackdrop(): GlassBackdrop {
        val source = rememberLayerBackdrop()
        return remember(source) { Api33GlassBackdrop(source) }
    }
}

@RequiresApi(33)
private class Api33GlassBackdrop(private val source: LayerBackdrop) : GlassBackdrop {
    override fun capture(modifier: Modifier): Modifier = modifier.layerBackdrop(source)

    override fun blurBackground(modifier: Modifier, blurDp: Float): Modifier = modifier.drawBackdrop(
        backdrop = source,
        shape = { RectangleShape },
        effects = { blur(blurDp.coerceIn(0f, 80f).dp.toPx()) },
        highlight = null,
        onDrawSurface = {},
        enabled = true,
    )

    override fun glassSurface(
            modifier: Modifier, shape: Shape, blurDp: Float, liquid: Boolean, surface: Color, dark: Boolean,
        ): Modifier = with(modifier) {
        clip(shape)
            .drawBackdrop(
                backdrop = source,
                shape = { shape },
                effects = {
                    blur(blurDp.coerceIn(0f, 80f).dp.toPx())
                    if (liquid) {
                        colorControls(saturation = 1.13f, contrast = 1.03f)
                        // This affects the sampled page only: a rounded lens bends the backdrop near its edge.
                        val sampleScale = downscaleFactor.coerceAtLeast(1).toFloat()
                        runtimeShaderEffect("bootforge-liquid-lens-v4", LiquidLensShader, "content") {
                            setFloatUniform("resolution", size.width / sampleScale, size.height / sampleScale)
                            setFloatUniform("inset", padding / sampleScale)
                            setFloatUniform("radius", if (shape == RectangleShape) 0f else 31.dp.toPx() / sampleScale)
                            setFloatUniform("bend", 5.dp.toPx() / sampleScale)
                            // The input is the padded, downsampled render target. Keep
                            // refraction inside its texel centres, including the blur halo.
                            setFloatUniform("sampleMax",
                                maxOf(1f, kotlin.math.floor((size.width + padding * 2f) / sampleScale)) - .5f,
                                maxOf(1f, kotlin.math.floor((size.height + padding * 2f) / sampleScale)) - .5f)
                        }
                    }
                },
                // Keep blur continuous to the clipped edge without a separate
                // bright outline that reads as an unblurred transparent seam.
                highlight = null,
                onDrawSurface = {
                    drawRect(surface.copy(alpha = if (liquid) { if (dark) .40f else .36f } else { if (dark) .70f else .65f }))
                },
                enabled = true,
            )
    }
}

private const val LiquidLensShader = """
uniform shader content;
uniform float2 resolution;
uniform float inset;
uniform float radius;
uniform float bend;
uniform float2 sampleMax;
half4 main(float2 coord) {
    float2 p = coord - float2(inset) - resolution * 0.5;
    float r = min(radius, min(resolution.x, resolution.y) * 0.5);
    float2 q = abs(p) - (resolution * 0.5 - float2(r));
    float2 outside = max(q, float2(0.0));
    float edgeDistance = length(outside) + min(max(q.x, q.y), 0.0) - r;
    float2 normal = length(outside) > 0.001 ? normalize(outside) * sign(p)
        : (q.x > q.y ? float2(sign(p.x), 0.0) : float2(0.0, sign(p.y)));
    // At least 3*bend keeps the inward mapping monotonic even for square bars.
    float edgeWidth = max(r * 0.7, max(bend * 3.0, 1.0));
    float edge = 1.0 - smoothstep(0.0, edgeWidth, max(-edgeDistance, 0.0));
    // Sample toward the interior throughout the lens. An outward displacement
    // creates an alpha fallback band at the filter's output boundary.
    float2 sampleCoord = coord - normal * bend * edge * edge;
    half4 base = content.eval(coord);
    half4 refracted = content.eval(clamp(sampleCoord, float2(0.5), sampleMax));
    // A RuntimeShader input filter may crop its output before an outward lens
    // sample reaches the padded texture edge. Composite over the unshifted,
    // already-blurred input so transparent taps cannot expose sharp page text.
    return refracted + base * (1.0 - refracted.a);
}
"""

