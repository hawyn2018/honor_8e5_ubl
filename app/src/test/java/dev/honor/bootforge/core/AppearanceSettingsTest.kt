package dev.honor.bootforge.core

import dev.honor.bootforge.ui.AccentOption
import dev.honor.bootforge.ui.AppearanceSettings
import dev.honor.bootforge.ui.MonetSpec
import dev.honor.bootforge.ui.MonetStyle
import dev.honor.bootforge.ui.normalizeAccentHex
import org.junit.Assert.*
import org.junit.Test

class AppearanceSettingsTest {
    @Test fun settingsPersistRoundTrip() {
        val input = AppearanceSettings(0f, 60f, false, false, false, false, AccentOption.Custom,
            "#1a2B3c", MonetStyle.Expressive, MonetSpec.Spec2025)
        assertEquals(input.copy(customAccentHex = "#1A2B3C"), AppearanceSettings.fromPersisted(input.persistedValues()))
    }

    @Test fun invalidStoredValuesHaveBoundedDeterministicFallbacks() {
        val decoded = AppearanceSettings.fromPersisted(mapOf(
            "topBlurDp" to "NaN", "bottomBlurDp" to "Infinity", "accent" to "removed-option",
            "paletteStyle" to "new-style", "colorSpec" to "future", "customAccentHex" to "#x12345",
            "monetExtraction" to "perhaps", "liquidGlass" to "false",
        ))
        assertEquals(AppearanceSettings(liquidGlass = false), decoded)
        assertEquals(0f, AppearanceSettings(topBlurDp = -10f).normalized().topBlurDp)
        assertEquals(60f, AppearanceSettings(bottomBlurDp = 90f).normalized().bottomBlurDp)
    }

    @Test fun accentSourceHonorsSystemSwitchAndExplicitColor() {
        val system = 0xFF556677L
        assertEquals(system, AppearanceSettings().accentArgb(system))
        assertEquals(AppearanceSettings.DEFAULT_ACCENT_ARGB, AppearanceSettings(monetExtraction = false).accentArgb(system))
        assertEquals(AppearanceSettings.DEFAULT_ACCENT_ARGB, AppearanceSettings().accentArgb(null))
        assertEquals(AccentOption.Green.argb, AppearanceSettings(accent = AccentOption.Green).accentArgb(system))
        assertEquals(0xFF012ABCL, AppearanceSettings(accent = AccentOption.Custom, customAccentHex = "012abc").accentArgb(system))
        assertEquals(AppearanceSettings.DEFAULT_ACCENT_ARGB, AppearanceSettings(accent = AccentOption.Custom, customAccentHex = "bad").accentArgb(system))
    }

    @Test fun customColorRequiresExactlySixOpaqueRgbDigits() {
        assertEquals("#01ABCD", normalizeAccentHex("  #01abcd  "))
        assertEquals("#ABCDEF", normalizeAccentHex("abcdef"))
        listOf("#fff", "#12345678", "0x123456", "#12g456", "#12345;", "").forEach { assertNull(normalizeAccentHex(it)) }
    }
}
