package dev.honor.bootforge.core

import android.content.Context
import android.content.Intent
import java.io.File

internal object KernelSuHandoff {
    private val managers = listOf("me.weishu.kernelsu", "com.rifsxd.ksunext", "com.sukisu.ultra",
        "com.resukisu.resukisu", "me.yuki.fina")

    data class Result(val opened: Boolean, val logs: List<String>)

    fun openFromService(context: Context, log: File): Result = open { packageName ->
        val intent = launchIntent(context, packageName) ?: return@open false
        log.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "创建联动日志目录失败" } }
        // A foreground service may be treated as background for activity starts.
        // Reuse the authorized OTA shell and require Activity Manager's result.
        val result = RootShell.run(
            "am start -W --user current ${shellQuote(intent.toUri(Intent.URI_INTENT_SCHEME))}", log, 20)
        SystemUpdaterHandoff.accepted(result) && result.output.lineSequence().any {
            it.trim().equals("Status: ok", ignoreCase = true)
        }
    }

    fun openFromForeground(context: Context): Result = open { packageName ->
        val intent = launchIntent(context, packageName) ?: return@open false
        context.startActivity(intent)
        true
    }

    // Launcher filters can omit CATEGORY_DEFAULT, so start the resolved component.
    private fun launchIntent(context: Context, packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)
            ?.takeIf { it.component?.packageName == packageName }

    internal fun open(launch: (String) -> Boolean): Result {
        val logs = mutableListOf<String>()
        for (packageName in managers) {
            val opened = try { launch(packageName) }
            catch (failure: Exception) {
                logs += "[更新联动] ${packageName} 启动失败：${failure.message ?: failure.javaClass.simpleName}"
                false
            }
            if (opened) {
                logs += "[更新联动] 已打开 KernelSU（$packageName）。安装到未使用的槽位后，请回软件更新点击立即重启"
                return Result(true, logs)
            }
        }
        logs += "[更新联动] 未确认打开 KernelSU，OTA 成功结果保留。可重试或手动打开 KernelSU"
        return Result(false, logs)
    }
}
