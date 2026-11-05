package net.hawyn.ablefisp.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import net.hawyn.ablefisp.AblEfispOutput
import net.hawyn.ablefisp.AblEfispFeature
import net.hawyn.ablefisp.PatcherModel
import net.hawyn.ablefisp.patch.PatchMode

/**
 * An in-process page for a host Activity or navigation destination.
 *
 * The current LocalViewModelStoreOwner scopes the session (a navigation entry
 * when the host provides one). The same owner and [sessionKey] reuse one model,
 * including after a conditional page is removed and shown again. Give concurrent
 * pages in one owner different stable keys; use navigation-entry owners when
 * their models should be cleared on return.
 * [initialImageUri] is imported once per distinct URI; it requires the host's
 * existing read access. SAF selection/export, sharing and clipboard stay here.
 *
 * [onOutputReady] notifies the host after generation or text application; it can
 * be called repeatedly as the user edits. It does not navigate away. Every result
 * has immutable generation URIs. [onBack] is used only by this page's top bar.
 * Hosts supplying their own MiuixTheme/top bar can turn both wrappers off.
 * Window/system-bar settings are untouched unless [manageSystemBars] is true.
 * [fixedPatchMode] creates a mode-specific session with no mode switcher;
 * [appName] brands the page, clipboard and output report for that host variant.
 * [appVersion] records the host variant's version in reports and session identity.
 */
@Composable
fun AblEfispScreen(
    modifier: Modifier = Modifier,
    initialImageUri: Uri? = null,
    onBack: (() -> Unit)? = null,
    onOutputReady: ((AblEfispOutput) -> Unit)? = null,
    showTopBar: Boolean = true,
    wrapInTheme: Boolean = true,
    manageSystemBars: Boolean = false,
    sessionKey: String = "abl-efisp",
    fixedPatchMode: PatchMode? = null,
    appName: String = "ABL-EFISP",
    appVersion: String = AblEfispFeature.VERSION,
) {
    key(sessionKey, fixedPatchMode, appName, appVersion) {
        val context = LocalContext.current
        val application = context.applicationContext as Application
        val factory = remember(application, fixedPatchMode, appName, appVersion) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(PatcherModel::class.java))
                    return PatcherModel(application, fixedPatchMode, appName, appVersion) as T
                }
            }
        }
        val modelKey = "ablefisp.$sessionKey.${fixedPatchMode?.name ?: "selectable"}.$appName.$appVersion"
        val model: PatcherModel = viewModel(key = modelKey, factory = factory)
        val state = model.state
        val outputCallback by rememberUpdatedState(onOutputReady)
        var deliveredOutput by rememberSaveable { mutableStateOf<String?>(null) }

        val importImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) model.importImage(uri)
        }
        val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) model.saveImage(uri)
        }
        val saveOriginal = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) model.saveOriginal(uri)
        }
        val saveReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) model.saveReport(uri)
        }

        LaunchedEffect(initialImageUri, state.busy) {
            val uri = initialImageUri
            if (uri != null && !state.busy) model.importInitialImage(uri)
        }
        LaunchedEffect(state.outputRevision, state.hasOutput, onOutputReady != null) {
            if (state.hasOutput && outputCallback != null) {
                model.outputResult()?.let { output ->
                    if (deliveredOutput != output.imageUri.toString()) {
                        deliveredOutput = output.imageUri.toString()
                        outputCallback?.invoke(output)
                    }
                }
            }
        }

        PatcherApp(
            state = state,
            onImport = { importImage.launch(arrayOf("*/*")) },
            onExtract = model::extractCurrentSlot,
            onCheckRoot = model::checkRoot,
            onPatch = model::patch,
            onSaveImage = { saveImage.launch("efisp.img") },
            onSaveOriginal = { saveOriginal.launch(state.source?.name ?: "abl-original.img") },
            onSaveReport = { saveReport.launch("efisp-patch-report.json") },
            onShare = {
                model.outputResult()?.let { output ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, output.imageUri)
                        clipData = ClipData.newRawUri(output.fileName, output.imageUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享 efisp.img"))
                }
            },
            onCopyLog = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("$appName 日志", model.logText()))
                model.notifyMessage("日志已复制")
            },
            onClearMessage = model::clearMessage,
            onPrepareTexts = model::prepareTexts,
            onUpdateText = model::updateText,
            onResetText = model::resetText,
            onApplyTexts = model::applyTexts,
            modifier = modifier,
            onBack = onBack,
            showTopBar = showTopBar,
            wrapInTheme = wrapInTheme,
            manageSystemBars = manageSystemBars,
            onPatchModeChange = if (fixedPatchMode == null) model::selectPatchMode else null,
            appName = appName,
        )
    }
}
