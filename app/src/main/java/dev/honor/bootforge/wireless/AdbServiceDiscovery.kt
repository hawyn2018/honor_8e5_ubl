package dev.honor.bootforge.wireless

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
    private var running = false
    private var generation = 0L
    private val browsers = mutableMapOf<String, Browser>()
    private val pending = ArrayDeque<Candidate>()
    private val ports = AdbDiscoveryPorts()
    private var resolving: Candidate? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() = onMain {
        if (running) return@onMain
        if (Build.VERSION.SDK_INT < 30) {
            onMessage("无线调试自动发现需要 Android 11 或更新版本")
            return@onMain
        }
        if (manager == null) {
            onMessage("系统未提供网络服务发现，请手动填写端口")
            return@onMain
        }
        running = true
        generation += 1
        ports.reset(generation)
        acquireMulticastLock()
        for ((kind, type) in SERVICE_TYPES) {
            val browser = Browser(kind, type, generation)
            browsers[kind] = browser
            try {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, browser)
            } catch (error: RuntimeException) {
                browser.finish()
                onMessage("${kindLabel(kind)}端口自动发现未启动：${errorText(error)}；可手动填写")
            }
        }
        finishIfNoBrowsers()
        pumpResolveQueue()
    }

    fun stop() = onMain {
        if (!running && browsers.isEmpty()) return@onMain
        running = false
        generation += 1
        clearPending()
        ports.reset(generation)
        // Classic resolve has no cancellation API on Android 11–13. Keep its slot
        // occupied until its callback, even if start() begins another session.
        resolving?.cancelled = true
        browsers.values.toList().forEach { it.requestStop() }
        browsers.clear()
        releaseMulticastLock()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
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
                // Unknown/unregistered listener is already released by NsdManager.
                finish()
            }
        }

        fun finish() {
            if (finished) return
            finished = true
            if (browsers[kind] === this) browsers.remove(kind)
        }

        override fun onDiscoveryStarted(serviceType: String) = onMain {
            if (current()) onMessage("正在查找本机${kindLabel(kind)}端口")
            else requestStop()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = onMain {
            val report = current()
            // Android's classic API requires an explicit stop on start failure.
            requestStop()
            finish()
            if (report) onMessage("${kindLabel(kind)}端口发现失败（$errorCode），可手动填写")
            finishIfNoBrowsers()
        }

        override fun onDiscoveryStopped(serviceType: String) = onMain {
            finish()
            finishIfNoBrowsers()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = onMain {
            // Retry the same listener, rather than create another discovery that
            // could leak the original registration or receive stale callbacks.
            stopping = false
            if (!finished && stopRetries++ < 1) requestStop() else finish()
            finishIfNoBrowsers()
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) = onMain {
            if (!current()) return@onMain
            if (serviceInfo.serviceType.trimEnd('.') != type.trimEnd('.')) return@onMain
            if (pending.size >= 64) return@onMain
            val ticket = ports.found(kind, serviceKey(kind, serviceInfo), session) ?: return@onMain
            enqueue(Candidate(ticket, serviceInfo))
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = onMain {
            if (!current()) return@onMain
            val key = serviceKey(kind, serviceInfo)
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().key == key) iterator.remove()
            }
            if (resolving?.key == key && resolving?.session == session) resolving?.cancelled = true
            ports.lost(key, session)?.let { change ->
                onPort(change.kind, change.port)
                onMessage(if (change.port == null) "本机${kindLabel(kind)}服务已关闭，正在等待新端口；也可手动填写"
                    else "已切换本机${kindLabel(kind)}端口：${change.port}")
            }
        }
    }

    private inner class Candidate(val ticket: AdbDiscoveryPorts.Ticket, val info: NsdServiceInfo) {
        val kind get() = ticket.kind
        val session get() = ticket.session
        val key get() = ticket.key
        var cancelled = false
        var retries = 0
    }

    private fun serviceKey(kind: String, info: NsdServiceInfo) =
        "$kind:${info.serviceName}:${info.serviceType.trimEnd('.')}"

    private fun enqueue(candidate: Candidate) {
        // Other devices may advertise these types on the same Wi-Fi network.
        // Resolve sequentially, with a bounded queue, before filtering addresses.
        if (pending.size >= 64) return
        pending.addLast(candidate)
        pumpResolveQueue()
    }

    private fun pumpResolveQueue() {
        if (!running || manager == null || resolving != null) return
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
                        } else if (error != null) onMessage(error)
                    }
                    pumpResolveQueue()
                }

                private val slowResolveMessage = Runnable {
                    if (!completed && running && ports.isCurrent(candidate.ticket) && !candidate.cancelled) {
                        onMessage("端口解析较慢，仍可手动填写配对端口和连接端口")
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
                    complete(null, "${kindLabel(candidate.kind)}端口解析失败（$errorCode），可手动填写",
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
        if (info.port !in 1..65535) return
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val local = try {
            addresses.any { isLocalAddress(it) }
        } catch (error: Exception) {
            onMessage("本机网络地址检查失败：${errorText(error)}；可手动填写")
            false
        }
        ports.resolved(candidate.ticket, info.port, local)?.let { change ->
            onPort(change.kind, change.port)
            if (change.port != null) onMessage("已找到本机${kindLabel(candidate.kind)}端口：${change.port}")
        }
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

    private fun finishIfNoBrowsers() {
        if (running && browsers.isEmpty()) {
            running = false
            generation += 1
            clearPending()
            ports.reset(generation)
            resolving?.cancelled = true
            releaseMulticastLock()
        }
    }

    private fun clearPending() {
        pending.clear()
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
