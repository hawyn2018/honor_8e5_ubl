package dev.honor.bootforge

import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.honor.bootforge.ui.UnlockActions
import dev.honor.bootforge.ui.UnlockScreen
import dev.honor.bootforge.ui.AppearanceStore
import dev.honor.bootforge.ui.BootForgeTheme
import dev.honor.bootforge.ui.RootPage
import dev.honor.bootforge.ui.WirelessActions
import dev.honor.bootforge.wireless.ShellAccess
import dev.honor.bootforge.wireless.WirelessService
import dev.honor.bootforge.wireless.WirelessState
import dev.honor.bootforge.wireless.WirelessPortPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var model: UnlockModel
    private lateinit var appearance: AppearanceStore
    private lateinit var rootModel: RootFrameworkModel
    private var rootOpenRequest by mutableIntStateOf(0)
    private var pendingWirelessAction = WirelessService.PREPARE
    private val wirelessPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            WirelessState.state = WirelessState.state.copy(error = "请允许附近设备／本地网络权限后再进行无线调试配对", stage = "等待本地网络权限")
        } else {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                WirelessState.append("通知权限未开启，可分屏在应用内填写配对端口和配对码")
            WirelessService.start(this, pendingWirelessAction)
        }
    }
    private val saveBundle = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) model.saveBundle(uri)
    }
    private val saveLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) model.saveLog(uri)
    }
    private val selectEfisp = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.selectCustomImage("efisp", uri)
    }
    private val selectAbl = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.selectCustomImage("abl", uri)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingWirelessAction = savedInstanceState?.getString("wireless_action") ?: WirelessService.PREPARE
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        appearance = AppearanceStore(this)
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[UnlockModel::class.java]
        rootModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[RootFrameworkModel::class.java]
        // Shell 一旦验证为 UID 2000（配对/连接/检查成功）即自动开启 MagicRoot 提权流程；
        // 覆盖「先配对成功、后打开应用」的路径：进入页面时若 Shell 已就绪也立即触发。
        RootIntegration.onShellReady = { rootModel.onShellMatched() }
        if (WirelessState.state.shellReady) rootModel.onShellMatched()
        if (intent.getBooleanExtra("open_root", false)) { rootOpenRequest++; intent.removeExtra("open_root") }
        if (savedInstanceState == null && !WirelessState.state.waiting && !WirelessState.state.busy) {
            val preferences = getSharedPreferences("wireless", 0)
            WirelessState.state = WirelessState.state.copy(host = preferences.getString("host", "127.0.0.1") ?: "127.0.0.1",
                connectPort = preferences.getString("connect_port", "") ?: "")
        }
        setContent {
            val settings = appearance.settings
            BootForgeTheme(settings) {
            UnlockScreen(model.state, UnlockActions(
                onInspect = { model.inspect() },
                onInspectDirect = { model.inspect(false) },
                onInspectCustom = { model.inspect(true) },
                onPrepareTools = model::prepareTools,
                onSelectEfisp = { selectEfisp.launch(arrayOf("*/*")) },
                onSelectAbl = { selectAbl.launch(arrayOf("*/*")) },
                onResetEfisp = { model.resetCustomImage("efisp") },
                onResetAbl = { model.resetCustomImage("abl") },
                onBuildRecovery = { model.prepareBundle() },
                onBuildRecoveryDirect = { model.prepareBundle(false) },
                onBuildRecoveryCustom = { model.prepareBundle(true) },
                onExecute = { FlashService.start(this, true) },
                onExecuteDirect = { FlashService.start(this, false) },
                onExecuteCustom = { FlashService.start(this, true) },
                onSharePackage = { saveBundle.launch("honor_ubl-Recovery.zip") },
                onShareLogs = { saveLog.launch("honor_ubl-log.txt") },
                onClearLogs = model::clearLogs,
                onRebootRecovery = model::rebootRecovery,
            ), backupState = BackupState.state, onBackup = { BackupService.start(this) },
                appearance = settings, onAppearanceChange = appearance::update,
                rootBusy = rootModel.state.busy || WirelessState.state.busy || WirelessState.state.waiting,
                rootOpenRequest = rootOpenRequest,
                rootContent = { padding, busy ->
                    RootPage(rootModel.state, WirelessState.state, busy, padding,
                        WirelessActions(
                            onHost = { WirelessState.state = WirelessState.state.copy(host = it.take(255)) },
                            onPairingPort = { value ->
                                val digits = WirelessPortPolicy.digits(value, 5)
                                WirelessState.state = WirelessState.state.copy(pairingPort = digits, pairingPortManual = digits.isNotEmpty())
                            },
                            onConnectPort = { value ->
                                val digits = WirelessPortPolicy.digits(value, 5)
                                WirelessState.state = WirelessState.state.copy(connectPort = digits, connectPortManual = digits.isNotEmpty())
                            },
                            onPairingCode = { WirelessState.state = WirelessState.state.copy(pairingCode = WirelessPortPolicy.digits(it, 6)) },
                            onPreparePairing = { startWireless(WirelessService.PREPARE) },
                            onOpenSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                            onPair = { startWireless(WirelessService.PAIR) },
                            onConnect = { startWireless(WirelessService.CONNECT) },
                            onCheck = { startWireless(WirelessService.CHECK) },
                            onCancel = { WirelessService.start(this, WirelessService.CANCEL) },
                        ), rootModel::deploy, rootModel::acquire, rootModel::checkRoot, rootModel::clean)
                })
            }
        }
    }

    private fun startWireless(action: String) {
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
        if (intent.getBooleanExtra("open_root", false)) { rootOpenRequest++; intent.removeExtra("open_root") }
    }

    /**
     * 页面可见时（含解锁亮屏返回应用）若留有已保存的 Shell 端点，自动复检 Shell；
     * 复检通过即匹配成功，直接开启 MagicRoot 提权流程。失败静默，不影响手动配对。
     */
    private var autoConnectInFlight = false

    override fun onResume() {
        super.onResume()
        if (autoConnectInFlight || WirelessState.state.shellReady || WirelessState.state.busy || WirelessState.state.waiting) return
        val preferences = getSharedPreferences("wireless", 0)
        if (preferences.getString("connect_port", "")?.toIntOrNull() == null) return
        autoConnectInFlight = true
        lifecycleScope.launch {
            try {
                val ready = runCatching { withContext(Dispatchers.IO) { ShellAccess.check(application) } }.isSuccess
                if (ready) {
                    WirelessState.state = WirelessState.state.copy(shellReady = true, stage = "本应用 Shell 权限已就绪 · UID 2000")
                    WirelessState.append("页面可见时自动复检 Shell 通过")
                    RootIntegration.onShellReady?.invoke()
                }
            } finally { autoConnectInFlight = false }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("wireless_action", pendingWirelessAction)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) RootIntegration.onShellReady = null
        super.onDestroy()
    }
}
