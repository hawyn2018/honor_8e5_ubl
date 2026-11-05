package net.hawyn.ablefisp

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.hawyn.ablefisp.ui.AblEfispScreen
import net.hawyn.ablefisp.patch.PatchMode

/** Standalone entry point; the reusable feature is hosted by the library module. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            AblEfispScreen(
                manageSystemBars = true,
                appName = getString(R.string.app_name),
                fixedPatchMode = PatchMode.valueOf(BuildConfig.PATCH_MODE),
                appVersion = BuildConfig.VERSION_NAME,
            )
        }
    }
}
