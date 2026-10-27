package dev.honor.bootforge.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Icon
import androidx.annotation.WorkerThread
import androidx.core.graphics.PathParser
import dev.honor.bootforge.MainActivity
import kotlin.math.min

/** An optional pinned home-screen entry; the normal application icon remains a packaged resource. */
internal object AppLauncherIcon {
    enum class UpdateResult { PIN_REQUESTED, UPDATED, UNCHANGED, NOT_PINNED, UNSUPPORTED, RATE_LIMITED }

    private const val SHORTCUT_ID = "hnubl-app-theme"
    private const val PREFERENCES = "launcher_icon_v1"
    private const val COLOR_KEY = "successful_argb"
    private const val RENDERER_KEY = "renderer_version"
    private const val RENDERER_VERSION = 2
    private const val BACKGROUND_PATH = "M24,4L84,4Q104,4 104,24L104,84Q104,104 84,104L24,104Q4,104 4,84L4,24Q4,4 24,4"
    private const val LOCK_PATH = "M34,48L74,48Q79,48 79,53L79,77Q79,82 74,82L34,82Q29,82 29,77L29,53Q29,48 34,48M39,48L39,38Q39,23 54,23Q69,23 69,38L61,38Q61,31 54,31Q47,31 47,38L47,48Z"
    private const val KEYHOLE_PATH = "M51,60L57,60L57,72L51,72Z"

    /** Only an explicit foreground user action may request pinning; PIN_REQUESTED is not confirmation. */
    @WorkerThread
    @Synchronized
    fun request(context: Context, argb: Int): UpdateResult {
        val app = context.applicationContext ?: context
        val manager = app.getSystemService(ShortcutManager::class.java) ?: return UpdateResult.UNSUPPORTED
        if (manager.pinnedShortcuts.any { it.id == SHORTCUT_ID }) return update(app, manager, argb)
        if (!manager.isRequestPinShortcutSupported) return UpdateResult.UNSUPPORTED
        return if (manager.requestPinShortcut(shortcut(app, manager, argb), null)) UpdateResult.PIN_REQUESTED
            else UpdateResult.UNSUPPORTED
    }

    /** Refresh only an existing entry; never creates an icon or opens a pin-confirmation dialog. */
    @WorkerThread
    @Synchronized
    fun synchronize(context: Context, argb: Int): UpdateResult {
        val app = context.applicationContext ?: context
        val manager = app.getSystemService(ShortcutManager::class.java) ?: return UpdateResult.UNSUPPORTED
        if (manager.pinnedShortcuts.none { it.id == SHORTCUT_ID }) return UpdateResult.NOT_PINNED
        return update(app, manager, argb)
    }

    private fun update(context: Context, manager: ShortcutManager, argb: Int): UpdateResult {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.contains(COLOR_KEY) && preferences.getInt(COLOR_KEY, 0) == argb &&
            preferences.getInt(RENDERER_KEY, 0) == RENDERER_VERSION) return UpdateResult.UNCHANGED
        if (manager.isRateLimitingActive) return UpdateResult.RATE_LIMITED
        if (!manager.updateShortcuts(listOf(shortcut(context, manager, argb)))) return UpdateResult.RATE_LIMITED
        // Do not remember a request, rejection or rate-limited update as an applied color.
        preferences.edit().putInt(COLOR_KEY, argb).putInt(RENDERER_KEY, RENDERER_VERSION).apply()
        return UpdateResult.UPDATED
    }

    private fun shortcut(context: Context, manager: ShortcutManager, argb: Int): ShortcutInfo {
        val activity = ComponentName(context.packageName, MainActivity::class.java.name)
        val intent = Intent(Intent.ACTION_MAIN).setComponent(activity).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return ShortcutInfo.Builder(context, SHORTCUT_ID)
            .setShortLabel("hnubl")
            .setLongLabel("hnubl")
            .setActivity(activity)
            .setIntent(intent)
            .setIcon(icon(manager, argb))
            .build()
    }

    private fun icon(manager: ShortcutManager, argb: Int): Icon {
        val size = min(manager.iconMaxWidth, manager.iconMaxHeight).coerceAtLeast(1)
        return Icon.createWithBitmap(bitmap(argb, size))
    }

    /** Preserve the original 108x108 drawable geometry; only its two paint colors change. */
    internal fun bitmap(argb: Int, size: Int = 108): Bitmap {
        require(size > 0)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / 108f, size / 108f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        listOf(BACKGROUND_PATH to argb, LOCK_PATH to Color.WHITE, KEYHOLE_PATH to argb).forEach { (data, color) ->
            val path = checkNotNull(PathParser.createPathFromPathData(data)).apply { fillType = Path.FillType.WINDING }
            paint.color = color
            canvas.drawPath(path, paint)
        }
        return bitmap
    }
}
