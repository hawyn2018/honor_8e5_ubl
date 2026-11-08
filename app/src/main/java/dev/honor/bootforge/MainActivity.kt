package dev.honor.bootforge

import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.honor.bootforge.ui.UnlockActions
import dev.honor.bootforge.ui.UnlockScreen
import dev.honor.bootforge.ui.AppearanceStore
import dev.honor.bootforge.ui.BootForgeTheme
import dev.honor.bootforge.ui.RootPage
import dev.honor.bootforge.ui.OtaPage
import dev.honor.bootforge.ui.OtaActions
import dev.honor.bootforge.ui.LicenseConsentScreen
import dev.honor.bootforge.ui.StartupCheckingPage
import dev.honor.bootforge.ui.SupportedDevicesDialog
import dev.honor.bootforge.ui.WirelessActions
import dev.honor.bootforge.wireless.ShellAccess
import dev.honor.bootforge.wireless.WirelessService
import dev.honor.bootforge.wireless.WirelessState
import dev.honor.bootforge.wireless.WirelessPortPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
    private lateinit var licenseConsentModel: LicenseConsentModel
    private var appReady by mutableStateOf(false)
    private var restoreWirelessOnInitialize = false
    private val canOperate: Boolean
        get() = appReady && ::licenseConsentModel.isInitialized && licenseConsentModel.accepted && !PatchRetryPrompt.waiting
    private lateinit var model: UnlockModel
    private lateinit var appearance: AppearanceStore
    private lateinit var rootModel: RootFrameworkModel
    private lateinit var otaModel: OtaModel
    private lateinit var startupModel: StartupModel
    private var rootOpenRequest by mutableIntStateOf(0)
    private var otaOpenRequest by mutableIntStateOf(0)
    private var pendingWirelessAction = WirelessService.PREPARE
    private val wirelessPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!canOperate) return@registerForActivityResult
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            WirelessState.state = WirelessState.state.copy(error = "请允许附近设备／本地网络权限后再进行无线调试配对", stage = "等待本地网络权限")
        } else {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                WirelessState.append("通知权限未开启，可分屏在应用内填写配对端口和配对码")
            WirelessService.start(this, pendingWirelessAction)
        }
    }
    private val saveBundle = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && canOperate) model.saveBundle(uri)
    }
    private val saveLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null && canOperate) model.saveLog(uri)
    }
    private val selectEfisp = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && canOperate) model.selectCustomImage("efisp", uri)
    }
    private val selectAbl = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && canOperate) model.selectCustomImage("abl", uri)
    }
    private val selectEfispAbl = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && canOperate) model.selectCustomImage("efisp-abl", uri)
    }
    private val selectOtaAbl = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && canOperate) otaModel.importAbl(uri)
    }
    private val selectOtaEfispAbl = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && canOperate) otaModel.importEfispAbl(uri)
    }
    private val selectOtaEfisp = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && canOperate) otaModel.importEfisp(uri)
    }
    private val exportOtaEfisp = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && canOperate) otaModel.exportEfisp(uri)
    }
    private val exportOtaDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && canOperate) otaModel.exportDiagnostics(uri)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingWirelessAction = savedInstanceState?.getString("wireless_action") ?: WirelessService.PREPARE
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        appearance = AppearanceStore(this)
        licenseConsentModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[LicenseConsentModel::class.java]
        restoreWirelessOnInitialize = savedInstanceState?.getBoolean("license_business_initialized") != true
        if (restoreWirelessOnInitialize && savedInstanceState?.containsKey("license_pending_ota") == true) {
            intent.removeExtra("open_ota")
            intent.removeExtra("open_root")
            if (savedInstanceState.getBoolean("license_pending_ota")) intent.putExtra("open_ota", true)
            else if (savedInstanceState.getBoolean("license_pending_root")) intent.putExtra("open_root", true)
        }
        initializeApplication()
        setContent {
            val settings = appearance.settings
            BootForgeTheme(settings) {
                val consentVisible = !licenseConsentModel.accepted
                var supportedDevicesVisible by rememberSaveable { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                    Box(Modifier.fillMaxSize().let { modifier ->
                        if ((consentVisible || supportedDevicesVisible) && Build.VERSION.SDK_INT >= 31)
                            modifier.blur(18.dp, BlurredEdgeTreatment.Unbounded)
                        else modifier
                    }) {
                        key(startupModel.sessionKey) {
                            val startupDestination = startupModel.destination
                            if (startupDestination == null) {
                                StartupCheckingPage()
                            } else {
                                UnlockScreen(model.state, UnlockActions(
                                    onRequestChanged = { request -> operate { model.changeRequest(request) } },
                                    onInspect = { request -> operate { model.inspect(request) } },
                                    onPrepareTools = { operate { model.prepareTools() } },
                                    onSelectEfisp = { operate { selectEfisp.launch(arrayOf("*/*")) } },
                                    onSelectAbl = { operate { selectAbl.launch(arrayOf("*/*")) } },
                                    onSelectEfispAbl = { operate { selectEfispAbl.launch(arrayOf("*/*")) } },
                                    onResetEfispAbl = { operate { model.resetCustomImage("efisp-abl") } },
                                    onResetEfisp = { operate { model.resetCustomImage("efisp") } },
                                    onResetAbl = { operate { model.resetCustomImage("abl") } },
                                    onBuildRecovery = { request -> operate { model.prepareBundle(request) } },
                                    onExecute = { request -> operate { FlashService.start(this@MainActivity, request) } },
                                    onSharePackage = { operate { saveBundle.launch("honor_ubl-Recovery.zip") } },
                                    onShareLogs = { operate { saveLog.launch("honor_ubl-log.txt") } },
                                    onClearLogs = { operate { model.clearLogs() } },
                                    onRebootRecovery = { operate { model.rebootRecovery() } },
                                    onRebootBootloader = { operate { model.rebootBootloader() } },
                                ), backupState = BackupState.state, onBackup = { operate { BackupService.start(this@MainActivity) } },
                                    appearance = settings, onAppearanceChange = { value -> operate { appearance.update(value) } },
                                    rootBusy = rootModel.state.busy || WirelessState.state.busy || WirelessState.state.waiting,
                                    otaBusy = otaModel.state.busy,
                                    rootOpenRequest = rootOpenRequest,
                                    otaOpenRequest = otaOpenRequest,
                                    startupDestination = startupDestination,
                                    onShowSupportedDevices = { supportedDevicesVisible = true },
                                    otaContent = { padding, busy ->
                                        OtaPage(otaModel.state, busy, OtaActions(
                                            onPrepareSystemUpdate = { operate { otaModel.prepareSystemUpdate() } },
                                            onRequestChanged = { request -> operate { otaModel.requestChanged(request) } },
                                            onSelectAbl = { operate { selectOtaAbl.launch(arrayOf("*/*")) } },
                                            onSelectEfispAbl = { operate { selectOtaEfispAbl.launch(arrayOf("*/*")) } },
                                            onSelectEfisp = { operate { selectOtaEfisp.launch(arrayOf("*/*")) } },
                                            onStart = { operate { otaModel.onStart() } },
                                            onWriteEfispOnly = { operate { otaModel.onWriteEfispOnly() } },
                                            onExportEfisp = { operate { exportOtaEfisp.launch("efisp.img") } },
                                            onClearLogs = { operate { otaModel.clearLogs() } },
                                            onExportDiagnostics = { operate { exportOtaDiagnostics.launch(dev.honor.bootforge.core.OtaDiagnosticsArchive.fileName()) } },
                                            onCompletionAction = { operate { otaModel.runCompletionAction() } },
                                        ), padding)
                                    },
                                    rootContent = { padding, busy ->
                                        RootPage(rootModel.state.copy(rootReady = RunState.state.rootStatus in setOf("已提权", "Root 已取得")), WirelessState.state, busy, padding,
                                            WirelessActions(
                                                onHost = { value -> operate { WirelessState.state = WirelessState.state.copy(host = value.take(255)) } },
                                                onPairingPort = { value -> operate {
                                                    val digits = WirelessPortPolicy.digits(value, 5)
                                                    WirelessState.state = WirelessState.state.copy(pairingPort = digits, pairingPortManual = digits.isNotEmpty())
                                                } },
                                                onConnectPort = { value -> operate {
                                                    val digits = WirelessPortPolicy.digits(value, 5)
                                                    WirelessState.state = WirelessState.state.copy(connectPort = digits, connectPortManual = digits.isNotEmpty())
                                                } },
                                                onPairingCode = { value -> operate { WirelessState.state = WirelessState.state.copy(pairingCode = WirelessPortPolicy.digits(value, 6)) } },
                                                onPreparePairing = { startWireless(WirelessService.PREPARE) },
                                                onOpenSettings = { operate { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                                                onPair = { startWireless(WirelessService.PAIR) },
                                                onConnect = { startWireless(WirelessService.CONNECT) },
                                                onCheck = { startWireless(WirelessService.CHECK) },
                                                onCancel = { operate { WirelessService.start(this@MainActivity, WirelessService.CANCEL) } },
                                            ),
                                            { operate { rootModel.deploy() } },
                                            { operate { rootModel.acquire() } },
                                            { operate { rootModel.checkRoot() } },
                                            { operate { rootModel.clean() } },
                                            { chain -> operate { rootModel.selectChain(chain) } })
                                    })
                            }
                        }
                    }
                    if (consentVisible || supportedDevicesVisible) {
                        // Keep the prepared page visible through frosted glass;
                        // the modal dialog remains the only interactive window.
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background.copy(alpha = .18f)))
                    }
                    if (consentVisible) {
                        LicenseConsentScreen(licenseConsentModel.saving, licenseConsentModel.error,
                            onAgree = licenseConsentModel::accept, onExit = ::finish)
                    }
                    if (!consentVisible) {
                        if (supportedDevicesVisible) SupportedDevicesDialog(onDismiss = { supportedDevicesVisible = false })
                        else PatchRetryDialog()
                    }
                }
            }
        }
    }

    /** Prepare the ordinary page once while the modal consent window covers it. */
    private fun initializeApplication() {
        if (appReady) return
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[UnlockModel::class.java]
        rootModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[RootFrameworkModel::class.java]
        otaModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[OtaModel::class.java]
        startupModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[StartupModel::class.java]
        dispatchLaunchIntent(initial = true)
        if (restoreWirelessOnInitialize && !WirelessState.state.waiting && !WirelessState.state.busy) {
            val preferences = getSharedPreferences("wireless", 0)
            WirelessState.state = WirelessState.state.copy(host = preferences.getString("host", "127.0.0.1") ?: "127.0.0.1",
                connectPort = preferences.getString("connect_port", "") ?: "")
        }
        appReady = true
    }

    /** Page-only navigation can update the prepared page behind the modal. */
    private fun dispatchLaunchIntent(initial: Boolean) {
        val explicitRoot = intent.getBooleanExtra("open_root", false)
        val explicitOta = intent.getBooleanExtra("open_ota", false)
        if (explicitOta) {
            otaOpenRequest++
            intent.removeExtra("open_ota")
            intent.removeExtra("open_root")
            startupModel.openOta()
        } else if (explicitRoot) {
            rootOpenRequest++
            intent.removeExtra("open_root")
            if (initial) startupModel.start(true) else startupModel.openPrivilege()
        } else if (initial) startupModel.start(false)
    }

    /** User-triggered actions remain gated while the modal covers prepared pages. */
    private inline fun operate(action: () -> Unit) {
        if (canOperate) action()
    }

    private fun startWireless(action: String) {
        if (!canOperate) return
        pendingWirelessAction = action
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                add("android.permission.POST_NOTIFICATIONS")
            if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED)
                add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        if (permissions.isEmpty()) WirelessService.start(this, action)
        else wirelessPermissions.launch(permissions.toTypedArray())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (appReady) dispatchLaunchIntent(initial = false)
    }

    /**
     * 页面可见时（含解锁亮屏返回应用）若留有已保存的 Shell 端点，自动复检 Shell；
     * UID 0 复检成功会注册无线 Root；UID 2000 不自动运行提权链。
     */
    private var autoConnectInFlight = false

    override fun onResume() {
        super.onResume()
        reconnectSavedWirelessShell()
    }

    private fun reconnectSavedWirelessShell() {
        // This is the existing read-only identity/connection preparation, not a
        // user-requested deployment, backup, OTA or partition operation.
        if (!appReady) return
        if (autoConnectInFlight || RunState.state.busy || WirelessState.state.shellReady || WirelessState.state.busy || WirelessState.state.waiting) return
        val preferences = getSharedPreferences("wireless", 0)
        if (preferences.getString("connect_port", "")?.toIntOrNull() == null) return
        autoConnectInFlight = true
        RunState.state = RunState.state.copy(busy = true)
        lifecycleScope.launch {
            try {
                val checked = runCatching { withContext(Dispatchers.IO) { ShellAccess.check(application) } }
                checked.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                if (checked.isSuccess) {
                    WirelessState.connected(application)
                    WirelessState.append("页面可见时自动复检 Shell 通过")
                } else WirelessState.connectionFailed(application, checked.exceptionOrNull()?.message.orEmpty(), "无线 Shell 待重新连接")
            } finally {
                autoConnectInFlight = false
                RunState.state = RunState.state.copy(busy = false)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("wireless_action", pendingWirelessAction)
        outState.putBoolean("license_pending_ota", intent.getBooleanExtra("open_ota", false))
        outState.putBoolean("license_pending_root", intent.getBooleanExtra("open_root", false))
        outState.putBoolean("license_business_initialized", appReady)
        super.onSaveInstanceState(outState)
    }

}
