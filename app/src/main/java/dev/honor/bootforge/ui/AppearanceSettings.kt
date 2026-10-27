package dev.honor.bootforge.ui

import java.util.Locale

/** Persisted values are independent of Compose and Android, so damaged preferences have a fallback. */
data class AppearanceSettings(
    val topBlurDp: Float = 28f,
    val bottomBlurDp: Float = 32f,
    val liquidGlass: Boolean = true,
    val floatingBottomBar: Boolean = true,
    val monetExtraction: Boolean = true,
    val monetEnabled: Boolean = true,
    val accent: AccentOption = AccentOption.Pink,
    val customAccentHex: String = DEFAULT_ACCENT_HEX,
    val paletteStyle: MonetStyle = MonetStyle.Vibrant,
    val colorSpec: MonetSpec = MonetSpec.Spec2021,
) {
    fun normalized(): AppearanceSettings = copy(
        topBlurDp = topBlurDp.validBlur(28f),
        bottomBlurDp = bottomBlurDp.validBlur(32f),
        customAccentHex = normalizeAccentHex(customAccentHex) ?: DEFAULT_ACCENT_HEX,
    )

    /** A selected color wins; System uses the extracted system seed only while extraction is on. */
    fun accentArgb(systemSeed: Long?): Long = when (accent) {
        AccentOption.System -> if (monetExtraction) systemSeed ?: DEFAULT_ACCENT_ARGB else DEFAULT_ACCENT_ARGB
        AccentOption.Custom -> 0xFF000000L or (normalizeAccentHex(customAccentHex)
            ?: DEFAULT_ACCENT_HEX).removePrefix("#").toLong(16)
        else -> requireNotNull(accent.argb)
    }

    fun persistedValues(): Map<String, String> = normalized().let {
        mapOf(
            "topBlurDp" to it.topBlurDp.toString(),
            "bottomBlurDp" to it.bottomBlurDp.toString(),
            "liquidGlass" to it.liquidGlass.toString(),
            "floatingBottomBar" to it.floatingBottomBar.toString(),
            "monetExtraction" to it.monetExtraction.toString(),
            "monetEnabled" to it.monetEnabled.toString(),
            "accent" to it.accent.name,
            "customAccentHex" to it.customAccentHex,
            "paletteStyle" to it.paletteStyle.name,
            "colorSpec" to it.colorSpec.name,
        )
    }

    companion object {
        const val DEFAULT_ACCENT_HEX = "#B64777"
        const val DEFAULT_ACCENT_ARGB = 0xFFB64777L

        fun fromPersisted(values: Map<String, String>): AppearanceSettings = AppearanceSettings(
            topBlurDp = values["topBlurDp"]?.toFloatOrNull() ?: 28f,
            bottomBlurDp = values["bottomBlurDp"]?.toFloatOrNull() ?: 32f,
            liquidGlass = values["liquidGlass"]?.toBooleanStrictOrNull() ?: true,
            floatingBottomBar = values["floatingBottomBar"]?.toBooleanStrictOrNull() ?: true,
            monetExtraction = values["monetExtraction"]?.toBooleanStrictOrNull() ?: true,
            monetEnabled = values["monetEnabled"]?.toBooleanStrictOrNull() ?: true,
            accent = AccentOption.entries.firstOrNull { it.name == values["accent"] } ?: AccentOption.Pink,
            customAccentHex = values["customAccentHex"] ?: DEFAULT_ACCENT_HEX,
            paletteStyle = MonetStyle.entries.firstOrNull { it.name == values["paletteStyle"] } ?: MonetStyle.Vibrant,
            colorSpec = MonetSpec.entries.firstOrNull { it.name == values["colorSpec"] } ?: MonetSpec.Spec2021,
        ).normalized()
    }
}

enum class AccentOption(val label: String, val argb: Long? = null) {
    System("跟随系统"),
    Blue("晴空蓝", 0xFF3478F6L),
    Green("薄荷绿", 0xFF168665L),
    Purple("鸢尾紫", 0xFF7956C4L),
    Orange("暖杏橙", 0xFFB65D13L),
    Pink("蔷薇粉", 0xFFB64777L),
    Custom("自定义"),
}

/** These four Miuix styles all implement both Material 2021 and 2025 color specifications. */
enum class MonetStyle(val label: String, val description: String) {
    TonalSpot("柔和 · Tonal Spot", "均衡的 Material You 色调"),
    Neutral("中性 · Neutral", "低饱和度，安静克制"),
    Vibrant("鲜明 · Vibrant", "更鲜亮的强调色与容器颜色"),
    Expressive("表现 · Expressive", "更丰富的色相变化"),
}

enum class MonetSpec(val label: String) {
    Spec2021("Material 2021"),
    Spec2025("Material 2025"),
}

fun normalizeAccentHex(value: String): String? {
    val digits = value.trim().removePrefix("#")
    return if (digits.matches(Regex("[0-9a-fA-F]{6}"))) "#${digits.uppercase(Locale.ROOT)}" else null
}

private fun Float.validBlur(fallback: Float): Float = if (isFinite()) coerceIn(0f, 60f) else fallback
