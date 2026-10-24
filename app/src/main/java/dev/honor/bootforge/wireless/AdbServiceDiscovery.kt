package dev.honor.bootforge.wireless

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Discovers this device's wireless-debugging ports; manual entry stays independent. */
@Suppress("DEPRECATION")
class AdbServiceDiscovery(
    context: Context,
    private val onPort: (String, Int?) -> Unit,
    private val onMessage: (String) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executor { action -> main.post(action) }
    private var running = false
    private var generation = 0L
    private val browsers = mutableMapOf<String, Browser>()
    private val browserRecovery = mutableMapOf<String, Runnable>()
    private val browserAttempts = mutableMapOf<String, Int>()
    private val watches = mutableMapOf<String, ServiceWatch>()
    private val pending = ArrayDeque<Candidate>()
    private val ports = AdbDiscoveryPorts()
    private var resolving: Candidate? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() = onMain { startSession() }

    fun stop() = onMain { stopSession() }

    /** The caller rate-limits refreshes; each refresh owns entirely new listeners. */
    fun restart() = onMain {
        stopSession()
        startSession()
    }

    /** Called on the main thread: try every local endpoint, including older live services. */
    fun connectionPorts(): List<Int> = ports.candidates("connect")

    private fun startSession() {
        if (Build.VERSION.SDK_INT < 30) {
            onMessage("无线调试自动发现需要 Android 11 或更新版本")
            return
        }
        if (manager == null) {
            onMessage("系统未提供网络服务发现，请手动填写端口")
            return
        }
        if (!running) {
            running = true
            generation += 1
            ports.reset(generation)
            browserAttempts.clear()
            acquireMulticastLock()
        }
        // Recover a missing browser even while the other service type is active.
        for ((kind, type) in SERVICE_TYPES) {
            if (kind !in browsers && kind !in browserRecovery) startBrowser(kind, type, generation)
        }
        pumpResolveQueue()
    }

    private fun stopSession() {
        if (!running && browsers.isEmpty() && watches.isEmpty() && browserRecovery.isEmpty()) return
        running = false
        generation += 1
        browserRecovery.values.forEach { main.removeCallbacks(it) }
        browserRecovery.clear()
        browserAttempts.clear()
        pending.clear()
        ports.reset(generation)
        // Android 11–13 cannot cancel classic resolve. Keep its platform slot
        // owned until the real callback; API 34+ has independent watches only.
        resolving?.cancelled = true
        if (Build.VERSION.SDK_INT >= 34) watches.values.toList().forEach { it.requestStop() }
        watches.clear()
        browsers.values.toList().forEach { it.requestStop() }
        browsers.clear()
        releaseMulticastLock()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
    }

    private fun startBrowser(kind: String, type: String, session: Long) {
        if (!running || generation != session || kind in browsers) return
        val browser = Browser(kind, type, session)
        browsers[kind] = browser
        try {
            manager?.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, browser)
        } catch (error: RuntimeException) {
            browser.finish()
            onMessage("${kindLabel(kind)}端口自动发现未启动：${errorText(error)}；可手动填写")
            if (error !is SecurityException) recoverBrowser(kind, type, session)
        }
    }

    private fun recoverBrowser(kind: String, type: String, session: Long) {
        if (!running || generation != session || kind in browsers || kind in browserRecovery) return
        val attempt = browserAttempts[kind] ?: 0
        if (attempt >= 3) return
        browserAttempts[kind] = attempt + 1
        val recovery = object : Runnable {
            override fun run() {
                if (generation != session || browserRecovery[kind] !== this) return
                browserRecovery.remove(kind)
                if (running) startBrowser(kind, type, session)
            }
        }
        browserRecovery[kind] = recovery
        main.postDelayed(recovery, 500L shl attempt)
    }

    private inner class Browser(val kind: String, val type: String, val session: Long) :
        NsdManager.DiscoveryListener {
        private var stopping = false
        private var finished = false
        private var stopRetries = 0

        private fun current() = running && generation == session && browsers[kind] === this

        fun requestStop() {
            if (finished || stopping) return
            stopping = true
            try {
                manager?.stopServiceDiscovery(this)
            } catch (_: RuntimeException) {
                finish()
            }
        }

        fun finish() {
            if (finished) return
            finished = true
            if (browsers[kind] === this) browsers.remove(kind)
        }

        override fun onDiscoveryStarted(serviceType: String) = onMain {
            if (current()) {
                browserAttempts.remove(kind)
                onMessage("正在查找本机${kindLabel(kind)}端口")
            } else requestStop()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = onMain {
            val report = current()
            // Explicit cleanup also covers implementations retaining a failed
            // discovery listener; unknown listeners are handled by requestStop.
            requestStop()
            finish()
            if (report) {
                onMessage("${kindLabel(kind)}端口发现失败：${nsdError(errorCode)}；可手动填写")
                if (!permissionFailure(errorCode)) recoverBrowser(kind, type, session)
            }
        }

        override fun onDiscoveryStopped(serviceType: String) = onMain {
            val recover = current() && !stopping
            finish()
            if (recover) recoverBrowser(kind, type, session)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = onMain {
            val recover = current() && !stopping
            stopping = false
            if (!finished && stopRetries++ < 1) requestStop() else finish()
            if (recover) recoverBrowser(kind, type, session)
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) = onMain {
            if (!current()) return@onMain
            if (!serviceInfo.serviceType.trimEnd('.').equals(type.trimEnd('.'), ignoreCase = true)) return@onMain
            if (pending.size + watches.size >= 64) return@onMain
            val ticket = ports.found(kind, serviceKey(kind, serviceInfo), session) ?: return@onMain
            resolve(Candidate(ticket, serviceInfo))
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = onMain {
            if (!current()) return@onMain
            val key = serviceKey(kind, serviceInfo)
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().key == key) iterator.remove()
            }
            if (resolving?.key == key && resolving?.session == session) resolving?.cancelled = true
            if (Build.VERSION.SDK_INT >= 34) watches.remove(key)?.requestStop()
            reportUnavailable(kind, ports.lost(key, session))
        }
    }

    private inner class Candidate(val ticket: AdbDiscoveryPorts.Ticket, val info: NsdServiceInfo) {
        val kind get() = ticket.kind
        val session get() = ticket.session
        val key get() = ticket.key
        var cancelled = false
        var retries = 0
        var lastLocalPort: Int? = null
    }

    private fun serviceKey(kind: String, info: NsdServiceInfo): String {
        val network = if (Build.VERSION.SDK_INT >= 33) info.network?.networkHandle else null
        return "$kind:${info.serviceName}:${info.serviceType.trimEnd('.')}:$network"
    }

    private fun resolve(candidate: Candidate) {
        if (Build.VERSION.SDK_INT >= 34) {
            val watch = ServiceWatch(candidate)
            watches[candidate.key] = watch
            watch.begin()
        } else enqueue(candidate)
    }

    /** A service that never responds must not block the other service type. */
    @TargetApi(34)
    private inner class ServiceWatch(private val candidate: Candidate) : NsdManager.ServiceInfoCallback {
        private var stopping = false
        private var finished = false
        private var receivedUpdate = false
        private val slowMessage = Runnable {
            if (current() && !receivedUpdate) {
                onMessage("${kindLabel(candidate.kind)}服务尚未返回地址和端口，正在继续等待；也可手动填写")
            }
        }

        private fun current() = !finished && !stopping && running &&
            generation == candidate.session && watches[candidate.key] === this &&
            !candidate.cancelled && ports.isCurrent(candidate.ticket)

        fun begin() {
            main.postDelayed(slowMessage, 12_000L)
            try {
                manager?.registerServiceInfoCallback(candidate.info, callbackExecutor, this)
            } catch (error: RuntimeException) {
                fail("${kindLabel(candidate.kind)}端口订阅未启动：${errorText(error)}；可手动填写",
                    error !is SecurityException)
            }
        }

        fun requestStop() {
            if (finished || stopping) return
            stopping = true
            candidate.cancelled = true
            main.removeCallbacks(slowMessage)
            try {
                manager?.unregisterServiceInfoCallback(this)
            } catch (_: RuntimeException) {
                finish()
            }
        }

        private fun finish() {
            finished = true
            main.removeCallbacks(slowMessage)
            if (watches[candidate.key] === this) watches.remove(candidate.key)
        }

        private fun fail(message: String, retryable: Boolean) {
            val report = current()
            finish()
            // Registration failure is terminal: NsdManager already removed its
            // listener, so there will be no Unregistered callback to wait for.
            if (!report) return
            reportUnavailable(candidate.kind, ports.failed(candidate.ticket))
            onMessage(message)
            if (retryable && candidate.retries < 2) {
                main.postDelayed({
                    if (running && generation == candidate.session && candidate.key !in watches) {
                        ports.found(candidate.kind, candidate.key, candidate.session)?.let { ticket ->
                            resolve(Candidate(ticket, candidate.info).also { it.retries = candidate.retries + 1 })
                        }
                    }
                }, 500L shl candidate.retries)
            }
        }

        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = onMain {
            fail("${kindLabel(candidate.kind)}端口订阅失败：${nsdError(errorCode)}；可手动填写",
                !permissionFailure(errorCode))
        }

        override fun onServiceInfoCallbackUnregistered() = onMain {
            if (current()) {
                fail("${kindLabel(candidate.kind)}端口订阅已停止，正在重新查找；也可手动填写", true)
            } else finish()
        }

        override fun onServiceLost() = onMain {
            if (current()) reportUnavailable(candidate.kind, ports.unavailable(candidate.ticket))
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = onMain {
            if (!current()) return@onMain
            val expectedNetwork = candidate.info.network
            if (expectedNetwork != null && serviceInfo.network != expectedNetwork) return@onMain
            reportUnavailable(candidate.kind, ports.unavailable(candidate.ticket))
        }

        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = onMain {
            if (!current()) return@onMain
            receivedUpdate = true
            main.removeCallbacks(slowMessage)
            reportResolved(candidate, serviceInfo)
        }
    }

    private fun enqueue(candidate: Candidate) {
        if (pending.size >= 64) {
            ports.failed(candidate.ticket)
            return
        }
        pending.addLast(candidate)
        pumpResolveQueue()
    }

    /** Legacy Android 11–13 fallback; Android 14+ never enters this queue. */
    private fun pumpResolveQueue() {
        if (Build.VERSION.SDK_INT >= 34 || !running || manager == null || resolving != null) return
        while (pending.isNotEmpty()) {
            val candidate = pending.removeFirst()
            if (candidate.cancelled || !ports.isCurrent(candidate.ticket)) continue
            resolving = candidate
            val listener = object : NsdManager.ResolveListener {
                private var completed = false

                private fun complete(info: NsdServiceInfo?, error: String?, retryable: Boolean = false) = onMain {
                    if (completed) return@onMain
                    completed = true
                    main.removeCallbacks(slowResolveMessage)
                    if (resolving === candidate) resolving = null
                    if (running && ports.isCurrent(candidate.ticket) && !candidate.cancelled) {
                        if (info != null) reportResolved(candidate, info)
                        else if (retryable && candidate.retries < 2) {
                            val delay = 300L shl candidate.retries++
                            main.postDelayed({
                                if (running && !candidate.cancelled && ports.isCurrent(candidate.ticket)) enqueue(candidate)
                            }, delay)
                        } else {
                            reportUnavailable(candidate.kind, ports.failed(candidate.ticket))
                            if (error != null) onMessage(error)
                        }
                    }
                    pumpResolveQueue()
                }

                private val slowResolveMessage = Runnable {
                    if (!completed && running && ports.isCurrent(candidate.ticket) && !candidate.cancelled) {
                        onMessage("${kindLabel(candidate.kind)}端口解析较慢，仍可手动填写配对端口和连接端口")
                    }
                }

                fun begin() {
                    main.postDelayed(slowResolveMessage, 12_000L)
                    try {
                        manager.resolveService(candidate.info, this)
                    } catch (error: RuntimeException) {
                        complete(null, "${kindLabel(candidate.kind)}端口解析未启动：${errorText(error)}；可手动填写")
                    }
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    complete(null, "${kindLabel(candidate.kind)}端口解析失败：${nsdError(errorCode)}；可手动填写",
                        errorCode == NsdManager.FAILURE_ALREADY_ACTIVE || errorCode == NsdManager.FAILURE_INTERNAL_ERROR)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    complete(serviceInfo, null)
                }
            }
            listener.begin()
            return
        }
    }

    private fun reportResolved(candidate: Candidate, info: NsdServiceInfo) {
        if (!running || candidate.cancelled || !ports.isCurrent(candidate.ticket)) return
        if (info.port !in 1..65535) {
            candidate.lastLocalPort = null
            reportUnavailable(candidate.kind, ports.unavailable(candidate.ticket))
            return
        }
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val local = try {
            addresses.any { isLocalAddress(it) }
        } catch (error: Exception) {
            onMessage("本机网络地址检查失败：${errorText(error)}；可手动填写")
            false
        }
        val change = ports.resolved(candidate.ticket, info.port, local)
        if (local && candidate.lastLocalPort != info.port && change == null) {
            onMessage("已发现本机${kindLabel(candidate.kind)}候选端口：${info.port}")
        }
        candidate.lastLocalPort = info.port.takeIf { local }
        change?.let {
            onPort(change.kind, change.port)
            if (change.port != null) onMessage("已找到本机${kindLabel(candidate.kind)}端口：${change.port}")
        }
    }

    private fun reportUnavailable(kind: String, change: AdbDiscoveryPorts.Change?) {
        if (change == null) return
        onPort(change.kind, change.port)
        onMessage(if (change.port == null) "本机${kindLabel(kind)}服务已关闭或正在更新，正在等待新端口；也可手动填写"
            else "已切换本机${kindLabel(kind)}端口：${change.port}")
    }

    private fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
        while (interfaces.hasMoreElements()) {
            val addresses = interfaces.nextElement().inetAddresses
            while (addresses.hasMoreElements()) {
                // Comparing bytes ignores IPv6 scope IDs while retaining the IP.
                if (address.address.contentEquals(addresses.nextElement().address)) return true
            }
        }
        return false
    }

    private fun acquireMulticastLock() {
        if (Build.VERSION.SDK_INT !in 30..32 || multicastLock != null) return
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = wifi.createMulticastLock("bootforge-wireless-adb").apply { setReferenceCounted(false) }
            lock.acquire()
            multicastLock = lock
        } catch (error: RuntimeException) {
            onMessage("组播接收未启用：${errorText(error)}；可手动填写端口")
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock
        multicastLock = null
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (_: RuntimeException) {
            // Already released by the system.
        }
    }

    @SuppressLint("InlinedApi")
    private fun permissionFailure(errorCode: Int) = errorCode == NsdManager.FAILURE_PERMISSION_DENIED

    private fun nsdError(errorCode: Int) = if (permissionFailure(errorCode))
        "本地网络或网络发现权限未授予（$errorCode）" else "系统错误码 $errorCode"

    private fun errorText(error: Exception) = when (error) {
        is SecurityException -> "请检查本地网络及网络发现权限"
        else -> error.message?.take(160) ?: error.javaClass.simpleName
    }

    private fun kindLabel(kind: String) = if (kind == "pairing") "配对" else "连接"

    private companion object {
        val SERVICE_TYPES = listOf(
            "pairing" to "_adb-tls-pairing._tcp",
            "connect" to "_adb-tls-connect._tcp",
        )
    }
}
