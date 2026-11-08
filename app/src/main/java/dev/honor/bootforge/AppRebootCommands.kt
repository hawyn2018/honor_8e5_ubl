package dev.honor.bootforge

import android.content.Context
import dev.honor.bootforge.core.RebootCommands
import dev.honor.bootforge.core.ShellResult
import dev.honor.bootforge.core.UnlockCompletion
import dev.honor.bootforge.wireless.ShellAccess
import dev.honor.bootforge.wireless.WirelessState
import java.io.File

/** Prefer the existing ADB Shell (UID 2000 or 0); do not replay a reboot elsewhere. */
object AppRebootCommands {
    fun request(context: Context, completion: UnlockCompletion, log: File, timeoutSeconds: Long = 30): ShellResult =
        RebootCommands.request(completion, log, timeoutSeconds, shellReady(), wirelessShell(context, log))

    fun requestSystem(context: Context, log: File, timeoutSeconds: Long = 30): ShellResult =
        RebootCommands.requestSystem(log, timeoutSeconds, shellReady(), wirelessShell(context, log))

    private fun shellReady(): Boolean = WirelessState.state.shellReady && ShellAccess.hasSession()

    private fun wirelessShell(context: Context, log: File): (String, Long) -> ShellResult =
        { command, timeout ->
            // Open the log before dispatch. Any error after dispatch stays on this channel.
            log.writeText("REBOOT_CHANNEL=wireless-shell\nSHELL_UID=${ShellAccess.uid}\n")
            ShellAccess.run(context, command, timeout).also { result ->
                log.appendText("EXIT_CODE=${result.exitCode}\n${result.output}")
            }
        }
}
