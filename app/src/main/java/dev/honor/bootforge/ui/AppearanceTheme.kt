package dev.honor.bootforge.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class AppearanceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("appearance_v2", Context.MODE_PRIVATE)
    var settings by mutableStateOf(AppearanceSettings.fromPersisted(
        preferences.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap(),
    ))
        private set

    fun update(value: AppearanceSettings) {
        settings = value.normalized()
        preferences.edit().also { editor ->
            settings.persistedValues().forEach { (key, entry) -> editor.putString(key, entry) }
        }.apply()
    }
}

/** Uses Miuix's actual HCT dynamic-color pipeline, including its 2021/2025 implementations. */
@Composable
fun BootForgeTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var wallpaperRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(context, settings.monetExtraction) {
        if (!settings.monetExtraction) return@DisposableEffect onDispose { }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_WALLPAPER_CHANGED) wallpaperRevision++
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    val systemSeed = remember(context, configuration, wallpaperRevision, settings.monetExtraction) {
        if (settings.monetExtraction) systemAccentSeed(context) else null
    }
    val seed = Color(settings.accentArgb(systemSeed))
    val controller = remember(settings.monetEnabled, settings.paletteStyle, settings.colorSpec, seed) {
        ThemeController(
            colorSchemeMode = if (settings.monetEnabled) ColorSchemeMode.MonetSystem else ColorSchemeMode.System,
            lightColors = lightColorScheme(primary = seed),
            darkColors = darkColorScheme(primary = seed),
            // Passing a seed is intentional: keyColor=null bypasses style/spec in Miuix 0.9.4.
            keyColor = seed,
            paletteStyle = when (settings.paletteStyle) {
                MonetStyle.TonalSpot -> ThemePaletteStyle.TonalSpot
                MonetStyle.Neutral -> ThemePaletteStyle.Neutral
                MonetStyle.Vibrant -> ThemePaletteStyle.Vibrant
                MonetStyle.Expressive -> ThemePaletteStyle.Expressive
            },
            colorSpec = when (settings.colorSpec) {
                MonetSpec.Spec2021 -> ThemeColorSpec.Spec2021
                MonetSpec.Spec2025 -> ThemeColorSpec.Spec2025
            },
        )
    }
    MiuixTheme(controller = controller, content = content)
}

private fun systemAccentSeed(context: Context): Long? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Android's wallpaper-derived accent resource requires no wallpaper or storage permission.
        context.getColor(android.R.color.system_accent1_500).toLong() and 0xFFFFFFFFL
    } else {
        val accent = TypedValue()
        if (!context.theme.resolveAttribute(android.R.attr.colorAccent, accent, true)) return@runCatching null
        val color = if (accent.resourceId != 0) context.getColor(accent.resourceId) else accent.data
        color.toLong() and 0xFFFFFFFFL
    }
}.getOrNull()
