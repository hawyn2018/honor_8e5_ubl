package dev.honor.bootforge.core

import android.content.Context
import java.io.File

/** Open only the system updater. The original updater still owns consume/slot/reboot. */
internal object SystemUpdaterHandoff {
    const val PACKAGE = "com.hihonor.ouc"
    const val UPDATE_ACTION = "android.settings.SYSTEM_UPDATE_SETTINGS"
    data class Result(val opened: Boolean, val logs: List<String>)

    fun open(context: Context, log: File): Result = open(log,
        launchComponent = { context.packageManager.getLaunchIntentForPackage(PACKAGE)?.component?.flattenToString() })

    internal fun open(log: File, launchComponent: () -> String?,
        runRoot: (String, File) -> ShellResult = { command, destination -> RootShell.run(command, destination, 20) }): Result {
        val logs = mutableListOf<String>()
        val component = try { launchComponent() }
        catch (failure: Exception) {
            logs += "[更新联动] 软件更新启动入口查询失败，尝试系统更新入口：${failure.message ?: failure.javaClass.simpleName}"
            null
        }
        val commands = buildList {
            if (component != null && safeComponent(component)) add("am start -W -n ${shellQuote(component)}")
            else if (component != null) logs += "[更新联动] 已忽略非软件更新应用的启动入口"
            // Package binding prevents the fallback from handing control to an
            // unrelated app when a vendor build omits the launcher activity.
            add("am start -W -a ${shellQuote(UPDATE_ACTION)} -p ${shellQuote(PACKAGE)}")
        }
        for ((index, command) in commands.withIndex()) {
            val result = try {
                log.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "创建联动日志目录失败" } }
                runRoot(command, if (index == 0) log else File(log.parentFile, "${log.nameWithoutExtension}-fallback-$index.log"))
            } catch (failure: Exception) {
                logs += "[更新联动] 软件更新入口打开未完成：${failure.message ?: failure.javaClass.simpleName}"
                continue
            }
            if (result.output.isNotBlank()) logs += result.output.lines()
            if (accepted(result)) {
                logs += "[更新联动] 已返回软件更新，请使用其原有立即重启按钮"
                return Result(true, logs)
            }
            logs += "[更新联动] 软件更新入口未确认打开（退出码 ${result.exitCode}）"
        }
        logs += "[更新联动] 打开软件更新未完成，OTA 成功结果保留；可再次点击返回软件更新，或手动打开软件更新"
        return Result(false, logs)
    }

    internal fun safeComponent(value: String): Boolean {
        val parts = value.split('/')
        if (parts.size != 2 || parts[0] != PACKAGE) return false
        val name = parts[1].removePrefix(".")
        return Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*").matches(name)
    }

    internal fun accepted(result: ShellResult): Boolean = result.exitCode == 0 && result.output.lineSequence().none {
        val line = it.trim()
        line.startsWith("Error", ignoreCase = true) || line.contains("Exception", ignoreCase = true) ||
            line.contains("Permission Denial", ignoreCase = true) ||
            line.contains("unable to resolve Intent", ignoreCase = true) ||
            (line.startsWith("Status:", ignoreCase = true) && !line.substringAfter(':').trim().equals("ok", ignoreCase = true))
    }
}
