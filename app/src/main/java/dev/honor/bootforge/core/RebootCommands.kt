package dev.honor.bootforge.core

import java.io.File

/** Select one available channel before sending. A failed request is never replayed. */
object RebootCommands {
    fun request(completion: UnlockCompletion, log: File, timeoutSeconds: Long = 30,
        shellReady: Boolean = false, shell: ((String, Long) -> ShellResult)? = null): ShellResult =
        dispatch(completion.rebootCommand, completion.destination, log, timeoutSeconds, shellReady, shell)

    fun requestSystem(log: File, timeoutSeconds: Long = 30,
        shellReady: Boolean = false, shell: ((String, Long) -> ShellResult)? = null): ShellResult =
        dispatch("reboot", "系统", log, timeoutSeconds, shellReady, shell)

    private fun dispatch(command: String, destination: String, log: File, timeoutSeconds: Long,
        shellReady: Boolean, shell: ((String, Long) -> ShellResult)?): ShellResult {
        val result = if (shellReady && shell != null && !RootShell.customSuSettings().enabled)
            shell(command, timeoutSeconds)
            else RootShell.run(command, log, timeoutSeconds)
        check(result.exitCode == 0) {
            "$destination 重启请求失败（退出码 ${result.exitCode}）" +
                result.output.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
        }
        return result
    }
}
