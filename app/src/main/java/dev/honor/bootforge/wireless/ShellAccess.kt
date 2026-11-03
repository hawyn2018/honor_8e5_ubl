package dev.honor.bootforge.wireless

import android.content.Context
import android.os.Build
import dev.honor.bootforge.core.RootProcessIO
import dev.honor.bootforge.core.RootShell
import dev.honor.bootforge.core.ShellResult
import java.io.File

/** UID 2000 remains an ordinary Shell; verified UID 0 also supplies a Root provider. */
object ShellAccess {
    @Volatile private var session: WirelessShellSession? = null
    val uid: Int? get() = session?.uid

    /** Read the registered channel only; availability checks never send a command. */
    fun hasSession(): Boolean = Build.VERSION.SDK_INT >= 30 && session?.hasSession() == true

    fun start(command: String): Process {
        if (Build.VERSION.SDK_INT < 30) error("无线调试需要 Android 11 或更新版本")
        return checkNotNull(session) { "请先获取 Shell 权限" }.start(command)
    }

    fun run(context: Context, command: String, timeoutSeconds: Long = 30): ShellResult =
        collect(context, { start(command) }, timeoutSeconds, identityOnly = false)

    fun connect(context: Context, host: String, port: Int): ShellResult {
        val uid = channel(context).connect(WirelessEndpoint(host, port))
        context.getSharedPreferences("wireless", 0).edit().putString("host", host)
            .putString("connect_port", port.toString()).apply()
        return success(uid)
    }

    fun check(context: Context): ShellResult {
        val preferences = context.getSharedPreferences("wireless", 0)
        val host = preferences.getString("host", "127.0.0.1") ?: "127.0.0.1"
        val port = preferences.getString("connect_port", "")?.toIntOrNull()?.takeIf { it in 1..65535 }
        val restore = port?.let { WirelessEndpoint(host, it) }
        return success(channel(context).check(restore))
    }

    @Synchronized
    private fun channel(context: Context): WirelessShellSession {
        check(Build.VERSION.SDK_INT >= 30) { "无线调试需要 Android 11 或更新版本" }
        session?.let { return it }
        val app = context.applicationContext
        val transport = WirelessAdbTransport(app)
        return WirelessShellSession(
            readIdentity = { target -> collect(app,
                { transport.startProcess(target.host, target.port, "id -u") }, 10, identityOnly = true) },
            startProcess = { target, command -> transport.startProcess(target.host, target.port, command) },
            registerRoot = { provider -> RootShell.setWirelessProvider(provider,
                File(app.filesDir, "root-wireless-check.log"), 10) },
            revokeRoot = { provider -> RootShell.clearWirelessProvider(provider) },
            shouldRegisterRoot = { !RootShell.customSuSettings().enabled },
        ).also { session = it }
    }

    private fun success(uid: Int) = ShellResult(0, "SHELL_UID=$uid\n" +
        if (uid == 0 && RootShell.hasWirelessProvider()) "ROOT_UID=0\nROOT_PROVIDER=wireless-adb\n" else "")

    private fun collect(context: Context, start: () -> Process, timeoutSeconds: Long, identityOnly: Boolean): ShellResult {
        require(timeoutSeconds >= 0)
        val directory = File(context.filesDir, "wireless-logs").apply { mkdirs() }
        val out = File.createTempFile("shell-out-", ".log", directory)
        val err = File.createTempFile("shell-err-", ".log", directory)
        try {
            val result = RootProcessIO.collect(start, out, err, timeoutSeconds * 1000, "Shell 命令执行超时")
            val output = out.readText()
            // Notices on stderr do not change a successful numeric identity.
            return ShellResult(result.exitCode, output +
                if (identityOnly && result.exitCode == 0) "" else result.output)
        } finally { out.delete(); err.delete() }
    }
}
