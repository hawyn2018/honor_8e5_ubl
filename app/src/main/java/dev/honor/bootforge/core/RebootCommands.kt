package dev.honor.bootforge.core

import java.io.File

/** Select one available channel before sending. A failed request is never replayed. */
object RebootCommands {
    fun request(completion: UnlockCompletion, log: File, timeoutSeconds: Long = 30,
        shellReady: Boolean = false, shell: ((String, Long) -> ShellResult)? = null): ShellResult {
        val result = if (shellReady && shell != null && !RootShell.customSuSettings().enabled)
            shell(completion.rebootCommand, timeoutSeconds)
            else RootShell.run(completion.rebootCommand, log, timeoutSeconds)
        check(result.exitCode == 0) {
            "${completion.destination} 重启请求失败（退出码 ${result.exitCode}）" +
                result.output.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
        }
        return result
    }
}
