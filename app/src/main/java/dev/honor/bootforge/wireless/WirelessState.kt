package dev.honor.bootforge.wireless

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.honor.bootforge.RunState
import dev.honor.bootforge.core.RootShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class WirelessUiState(
    val host: String = "127.0.0.1",
    val pairingPort: String = "",
    val connectPort: String = "",
    val pairingCode: String = "",
    val pairingPortManual: Boolean = false,
    val connectPortManual: Boolean = false,
    val paired: Boolean = false,
    val waiting: Boolean = false,
    val busy: Boolean = false,
    val shellReady: Boolean = false,
    val shellUid: Int? = null,
    val stage: String = "通过无线调试配对，获取本应用的 Shell 权限",
    val discovery: String = "等待发现无线调试服务",
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

object WirelessState {
    var state by mutableStateOf(WirelessUiState())
    internal var serviceOwner: Any? = null
    /** Called on the main thread only, after ShellAccess has verified the live channel. */
    suspend fun connected(context: Context) {
        val uid = checkNotNull(ShellAccess.uid)
        state = state.copy(shellReady = true, shellUid = uid,
            stage = "Shell 已连接 · UID $uid，正在请求 su 授权")
        // Request the app's own su even when adbd itself is already root.
        val suReady = requestSu(context)
        val rootReady = suReady || (uid == 0 && RootShell.hasWirelessProvider())
        state = state.copy(shellReady = true, shellUid = uid,
            stage = if (rootReady) "已 Root，可进行备份解锁操作 · ${if (uid == 0) "UID 0" else "现有 Root 通道"}"
                else "Shell 通道已就绪 · UID $uid，未获得 su 授权，请手动执行提权流程")
        RunState.state = RunState.state.copy(rootStatus = if (rootReady) "已提权" else "提权待授权")
    }

    suspend fun connectionFailed(context: Context, error: String, stage: String) {
        val previousUid = state.shellUid
        val retained = ShellAccess.hasSession()
        val otherRootReady = if (!retained && previousUid == 0) checkOtherRoot(context) else null
        state = state.copy(error = error, shellReady = retained, shellUid = ShellAccess.uid,
            pairingCode = "", stage = stage)
        if (!retained && previousUid == 0)
            RunState.state = RunState.state.copy(rootStatus = if (otherRootReady == true) "已提权" else "提权待授权")
    }

    /** Only a new read-only proof can keep another backend marked ready after ADB drops. */
    private suspend fun checkOtherRoot(context: Context): Boolean = withContext(Dispatchers.IO) {
        try { RootShell.check(File(context.filesDir, "root-wireless-fallback-check.log"), 10).exitCode == 0 }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }
    }

    private suspend fun requestSu(context: Context): Boolean {
        val (ready, diagnostic) = withContext(Dispatchers.IO) {
            try {
                val granted = RootShell.requestExternalSu(File(context.filesDir, "root-wireless-su-check.log"))
                val accepted = granted.exitCode == 0 || (RootShell.hasIntegratedProvider() &&
                    RootShell.check(File(context.filesDir, "root-wireless-existing-check.log"), 10).exitCode == 0)
                accepted to granted.output
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { false to "SU_CHECK_FAILED: ${error.message.orEmpty()}" }
        }
        append(diagnostic)
        return ready
    }

    fun append(message: String) {
        state = state.copy(logs = state.logs + message.lines())
    }
}
