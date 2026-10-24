package dev.honor.bootforge.wireless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.honor.bootforge.MainActivity
import dev.honor.bootforge.R
import dev.honor.bootforge.RootIntegration
import dev.honor.bootforge.RunState
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Keeps pairing alive while the user reads the code in system Settings. */
class WirelessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var work: Job? = null
    private var expiry: Job? = null
    private var locked = false
    private lateinit var discovery: AdbServiceDiscovery
    private var discoveredPairingPort: Int? = null
    private var discoveredConnectPort: Int? = null

    override fun onCreate() {
        super.onCreate()
        discovery = AdbServiceDiscovery(this, { kind, port ->
            val current = WirelessState.state
            if (kind == "pairing") {
                discoveredPairingPort = port
                WirelessState.state = current.copy(pairingPort = WirelessPortPolicy.discovered(
                    current.pairingPort, current.pairingPortManual, port))
            } else {
                discoveredConnectPort = port
                WirelessState.state = current.copy(connectPort = WirelessPortPolicy.discovered(
                    current.connectPort, current.connectPortManual, port))
            }
            diagnostic("DISCOVERY kind=$kind port=${port ?: "lost"}")
        }, { message -> WirelessState.state = WirelessState.state.copy(discovery = message) })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY.also { stopSelf(startId) }
        if (action == CANCEL) {
            if (work?.isCompleted == false) {
                WirelessState.state = WirelessState.state.copy(stage = "正在结束连接任务，请稍候")
                work?.cancel()
            } else finish()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < 30) {
            WirelessState.state = WirelessState.state.copy(error = "无线调试配对需要 Android 11 或更新版本")
            stopSelf(startId); return START_NOT_STICKY
        }
        if (!locked) {
            if (RunState.state.busy) {
                WirelessState.state = WirelessState.state.copy(error = "其他任务正在运行，请等待完成")
                stopSelf(startId); return START_NOT_STICKY
            }
            locked = true
            WirelessState.serviceOwner = this
            RunState.state = RunState.state.copy(busy = true)
        }
        foreground()
        if (action == PREPARE) {
            if (work?.isCompleted == false) return START_NOT_STICKY
            discoveredPairingPort = null
            discoveredConnectPort = null
            WirelessState.state = WirelessState.state.copy(waiting = true, busy = false, error = null,
                pairingPort = "", connectPort = "", pairingCode = "", paired = false, shellReady = false,
                pairingPortManual = false, connectPortManual = false,
                stage = "等待配对：打开开发者选项的无线调试，选择使用配对码配对设备")
            discovery.stop()
            discovery.start()
            armExpiry()
            diagnostic("PREPARE cleared previous endpoint hints")
            return START_NOT_STICKY
        }
        if (work?.isCompleted == false) return START_NOT_STICKY
        val remoteCode = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString()
        if (remoteCode != null) WirelessState.state = WirelessState.state.copy(pairingCode = WirelessPortPolicy.digits(remoteCode, 6))
        if (action == PAIR && !WirelessState.state.pairingCode.matches(Regex("[0-9]{6}"))) {
            WirelessState.state = WirelessState.state.copy(waiting = true, busy = false,
                error = "请输入系统当前配对窗口的 6 位配对码")
            discovery.start()
            armExpiry()
            return START_NOT_STICKY
        }
        expiry?.cancel()
        WirelessState.state = WirelessState.state.copy(busy = true, waiting = false, error = null)
        if (action != CHECK) discovery.start()
        work = scope.launch {
            var pairedThisAttempt = false
            var keepPairingOpen = false
            try {
                when (action) {
                    PAIR -> {
                        WirelessState.state = WirelessState.state.copy(paired = false, shellReady = false,
                            stage = "正在等待当前配对端口，请保持系统配对窗口打开")
                        val pairingPort = awaitPairingPort()
                        WirelessState.state = WirelessState.state.copy(stage = "正在与无线调试配对")
                        val input = WirelessState.state
                        diagnostic("PAIR_START port=$pairingPort")
                        WirelessAdbTransport(this@WirelessService).pairCancellable(input.host.trim(), pairingPort, input.pairingCode)
                        pairedThisAttempt = true
                        WirelessState.state = WirelessState.state.copy(pairingCode = "", paired = true,
                            stage = "配对成功，正在获取 Shell 权限")
                        diagnostic("PAIR_SUCCESS")
                        connectShell(afterPairing = true)
                    }
                    CONNECT -> connectShell()
                    CHECK -> {
                        val result = runInterruptible(Dispatchers.IO) { ShellAccess.check(this@WirelessService) }
                        WirelessState.state = WirelessState.state.copy(shellReady = true, stage = "本应用 Shell 权限已就绪 · UID 2000")
                        diagnostic(result.output)
                        // Shell 复检通过即匹配成功，直接开启 MagicRoot 提权流程（幂等）。
                        RootIntegration.onShellReady?.invoke()
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                val detail = error.message ?: error.javaClass.simpleName
                keepPairingOpen = action == PAIR && !pairedThisAttempt
                val stage = when {
                    pairedThisAttempt -> "配对已成功，Shell 连接尚未完成；可直接点击连接并获取 Shell"
                    action == PAIR -> "配对尚未完成，请核对当前窗口的配对端口和配对码"
                    action == CHECK -> "Shell 检查未通过，请重新连接无线调试"
                    else -> "Shell 连接未完成，请核对无线调试主页的连接端口"
                }
                WirelessState.state = WirelessState.state.copy(error = detail, shellReady = false,
                    pairingCode = "", stage = stage)
                diagnostic("${if (pairedThisAttempt) "CONNECT_AFTER_PAIR_FAILED" else "WIRELESS_FAILED"} ${error.javaClass.simpleName}: $detail")
            } finally {
                if (keepPairingOpen) {
                    WirelessState.state = WirelessState.state.copy(busy = false, waiting = true)
                    discovery.start()
                    armExpiry()
                } else finish(cancelWork = false)
            }
        }
        return START_NOT_STICKY
    }

    @RequiresApi(30)
    private suspend fun connectShell(afterPairing: Boolean = false) {
        WirelessState.state = WirelessState.state.copy(stage = "正在连接无线调试并验证本应用 Shell 通道")
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        var attempts = 0
        var lastError: Exception? = null
        while (SystemClock.elapsedRealtime() < deadline && attempts < 4) {
            currentCoroutineContext().ensureActive()
            val input = WirelessState.state
            val connectPort = WirelessPortPolicy.connectionPort(input.connectPort, input.connectPortManual,
                discoveredConnectPort, afterPairing)
            if (connectPort == null) {
                delay(200)
                continue
            }
            attempts++
            diagnostic("CONNECT_ATTEMPT=$attempts port=$connectPort afterPairing=$afterPairing")
            try {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                val connected = withTimeoutOrNull(minOf(5_000L, remaining)) {
                    runInterruptible(Dispatchers.IO) {
                        ShellAccess.connect(this@WirelessService, input.host.trim(), connectPort)
                    }
                } ?: error("连接超时，正在等待无线调试服务就绪")
                check(connected.exitCode == 0)
                WirelessState.state = WirelessState.state.copy(shellReady = true, paired = true,
                    stage = "本应用 Shell 权限已就绪 · UID 2000",
                    discovery = "已验证本机连接端口 $connectPort")
                diagnostic("SHELL_SUCCESS port=$connectPort uid=2000")
                // 与开发者选项匹配成功：直接开启 MagicRoot 提权流程（幂等，Root 已取得时为空操作）。
                RootIntegration.onShellReady?.invoke()
                return
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                lastError = error
                diagnostic("CONNECT_RETRY port=$connectPort ${error.javaClass.simpleName}")
            }
            delay(500L * attempts)
        }
        val detail = lastError?.message
        error(if (afterPairing) "配对已成功，连接端口暂未就绪或连接未完成。请保持无线调试开启，填写主页连接端口后点击连接并获取 Shell。${detail?.let { " $it" }.orEmpty()}"
            else detail ?: "尚未发现连接端口，请填写无线调试主页的连接端口")
    }

    private suspend fun awaitPairingPort(): Int {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            val input = WirelessState.state
            WirelessPortPolicy.pairingPort(input.pairingPort, input.pairingPortManual,
                discoveredPairingPort)?.let { return it }
            delay(200)
        }
        error("尚未发现当前配对端口，请保持系统配对窗口打开，或在应用内填写该窗口的配对端口")
    }

    private fun armExpiry() {
        expiry?.cancel()
        expiry = scope.launch { delay(10 * 60 * 1000L); finish() }
    }

    /** Endpoints and phases only: never log the pairing code, keys or command payloads. */
    private fun diagnostic(message: String) {
        WirelessState.append(message)
        Log.i("HonorWireless", message)
        runCatching {
            val log = File(filesDir, "wireless-pairing.log")
            log.appendText("${System.currentTimeMillis()} $message\n")
        }
    }

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "无线调试配对", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 60, Intent(this, MainActivity::class.java)
            .putExtra("open_root", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val reply = PendingIntent.getService(this, 61, Intent(this, WirelessService::class.java).setAction(PAIR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val stop = PendingIntent.getService(this, 62, Intent(this, WirelessService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val input = RemoteInput.Builder(CODE).setLabel("输入系统显示的 6 位配对码").build()
        val action = Notification.Action.Builder(null, "输入配对码", reply).addRemoteInput(input).build()
        startForeground(43, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("honor_ubl · 无线调试配对")
            .setContentText("保持系统配对窗口打开，展开此通知输入配对码")
            .setContentIntent(open).addAction(action)
            .addAction(Notification.Action.Builder(null, "结束配对", stop).build())
            .setOngoing(true).setOnlyAlertOnce(true).build())
    }

    private fun finish(cancelWork: Boolean = true) {
        if (cancelWork) work?.cancel()
        expiry?.cancel()
        discovery.stop()
        if (WirelessState.serviceOwner === this) {
            WirelessState.serviceOwner = null
            WirelessState.state = WirelessState.state.copy(busy = false, waiting = false, pairingCode = "")
            if (locked) RunState.state = RunState.state.copy(busy = false)
        }
        locked = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int) { finish() }
    override fun onTimeout(startId: Int, fgsType: Int) { finish() }
    override fun onDestroy() {
        discovery.stop(); scope.cancel()
        if (WirelessState.serviceOwner === this) {
            WirelessState.serviceOwner = null
            WirelessState.state = WirelessState.state.copy(busy = false, waiting = false, pairingCode = "")
            if (locked) RunState.state = RunState.state.copy(busy = false)
        }
        locked = false
        super.onDestroy()
    }

    companion object {
        const val PREPARE = dev.honor.bootforge.BuildConfig.APPLICATION_ID + ".wireless.PREPARE"
        const val PAIR = dev.honor.bootforge.BuildConfig.APPLICATION_ID + ".wireless.PAIR"
        const val CONNECT = dev.honor.bootforge.BuildConfig.APPLICATION_ID + ".wireless.CONNECT"
        const val CHECK = dev.honor.bootforge.BuildConfig.APPLICATION_ID + ".wireless.CHECK"
        const val CANCEL = dev.honor.bootforge.BuildConfig.APPLICATION_ID + ".wireless.CANCEL"
        private const val CODE = "pairing_code"
        private const val CHANNEL = "wireless_pairing"
        private fun port(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..65535 }
        fun start(context: Context, action: String) {
            context.startForegroundService(Intent(context, WirelessService::class.java).setAction(action))
        }
    }
}
