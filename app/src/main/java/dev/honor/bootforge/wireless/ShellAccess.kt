package dev.honor.bootforge.wireless

import android.content.Context
import android.os.Build
import dev.honor.bootforge.core.RootProcessIO
import dev.honor.bootforge.core.ShellResult
import java.io.File

/** Shell commands retain uid 2000. This object never registers a Root provider. */
object ShellAccess {
    private data class Endpoint(val host: String, val port: Int)
    @Volatile private var endpoint: Endpoint? = null
    @Volatile private var applicationContext: Context? = null

    fun start(command: String): Process {
        if (Build.VERSION.SDK_INT < 30) error("无线调试需要 Android 11 或更新版本")
        val target = checkNotNull(endpoint) { "请先获取 Shell 权限" }
        return WirelessAdbTransport(checkNotNull(applicationContext)).startProcess(target.host, target.port, command)
    }

    fun run(context: Context, command: String, timeoutSeconds: Long = 30): ShellResult {
        applicationContext = context.applicationContext
        val directory = File(context.filesDir, "wireless-logs").apply { mkdirs() }
        val out = File.createTempFile("shell-out-", ".log", directory)
        val err = File.createTempFile("shell-err-", ".log", directory)
        try {
            val result = RootProcessIO.collect({ start(command) }, out, err, timeoutSeconds * 1000, "Shell 命令执行超时")
            return ShellResult(result.exitCode, out.readText() + result.output)
        } finally { out.delete(); err.delete() }
    }

    fun connect(context: Context, host: String, port: Int): ShellResult {
        applicationContext = context.applicationContext
        val previous = endpoint
        endpoint = Endpoint(host, port)
        return try {
            val result = run(context, "id -u", 10)
            check(result.exitCode == 0 && result.output.trim() == "2000") { "Shell 身份校验未通过：${result.output}" }
            context.getSharedPreferences("wireless", 0).edit().putString("host", host).putString("connect_port", port.toString()).apply()
            result
        } catch (error: Exception) { endpoint = previous; throw error }
    }

    fun check(context: Context): ShellResult {
        applicationContext = context.applicationContext
        if (endpoint == null) {
            val preferences = context.getSharedPreferences("wireless", 0)
            val host = preferences.getString("host", "127.0.0.1") ?: "127.0.0.1"
            val port = preferences.getString("connect_port", "")?.toIntOrNull()?.takeIf { it in 1..65535 }
            check(port != null) { "请先配对并获取 Shell 权限" }
            endpoint = Endpoint(host, port)
        }
        val result = run(context, "id -u", 10)
        check(result.exitCode == 0 && result.output.trim() == "2000") { "Shell 会话已结束，请重新获取权限" }
        return ShellResult(0, "SHELL_UID=2000\n")
    }
}
