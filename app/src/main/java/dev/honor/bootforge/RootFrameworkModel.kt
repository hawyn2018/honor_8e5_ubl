package dev.honor.bootforge

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.honor.bootforge.core.RootProcessProvider
import dev.honor.bootforge.core.RootShell
import dev.honor.bootforge.ui.RootUiState
import dev.honor.bootforge.wireless.ShellAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Implement these hooks to connect a Root backend. The integrated backend is MagicRoot. */
interface RootAdapter {
    suspend fun deploy(shell: ShellAccess, application: Application)
    suspend fun acquireRoot(shell: ShellAccess, application: Application): RootProcessProvider
    suspend fun clean(shell: ShellAccess, application: Application)
}

object RootIntegration {
    var adapter: RootAdapter? = MagicRootAdapter()
    /** 无线 Shell 验证为 UID 2000 后触发一次；由 MainActivity 挂接到 RootFrameworkModel。 */
    @Volatile var onShellReady: (() -> Unit)? = null
}

class RootFrameworkModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(RootUiState(stage = "MagicRoot 提权链已接入，无线 Shell 就绪后自动提权"))
        private set
    private val app get() = getApplication<Application>()
    private var autoRootAttempted = false

    init {
        (RootIntegration.adapter as? MagicRootAdapter)?.onLog = { append(it) }
    }

    /**
     * 无线调试配对 / Shell 检查通过（UID 2000）后直接开启提权流程：
     * 部署资产 → 执行 MagicRoot 提权链 → 注册本应用 Root 通道。
     * 已取得 Root 或已有任务运行时忽略；失败允许下次 Shell 就绪时重试。
     */
    fun onShellMatched() {
        if (state.rootReady || autoRootAttempted) return
        if (RootIntegration.adapter == null) return
        autoRootAttempted = true
        // 无线服务在持有 RunState.busy 期间就会回调本方法；先等它释放，再启动任务。
        viewModelScope.launch {
            var waited = 0L
            while ((RunState.state.busy || state.busy) && waited < 30_000) {
                kotlinx.coroutines.delay(500)
                waited += 500
            }
            if (RunState.state.busy || state.busy || state.rootReady) {
                autoRootAttempted = false
                return@launch
            }
            runAutoChain()
        }
    }

    private fun runAutoChain() {
        task("自动提权流程", onError = { autoRootAttempted = false }) {
            withContext(Dispatchers.IO) { ShellAccess.check(app) }
            val adapter = checkNotNull(RootIntegration.adapter)
            // Root 执行通道仍存活（应用进程重启、上次提权未完成注册等）时跳过部署与竞态。
            val alive = withContext(Dispatchers.IO) {
                (adapter as? MagicRootAdapter)?.channelAlive(ShellAccess, app) == true
            }
            if (alive) {
                state = state.copy(stage = "自动提权：Root 通道仍存活，直接接入")
            } else {
                state = state.copy(stage = "自动提权：正在部署提权资产")
                withContext(Dispatchers.IO) { adapter.deploy(ShellAccess, app) }
                state = state.copy(deployed = true, stage = "自动提权：正在执行提权链（数秒到数分钟）")
            }
            val provider = withContext(Dispatchers.IO) { adapter.acquireRoot(ShellAccess, app) }
            val result = withContext(Dispatchers.IO) { RootShell.setIntegratedProvider(provider, File(app.filesDir, "root-provider-check.log")) }
            check(result.exitCode == 0) { result.output }
            state = state.copy(rootReady = true, stage = "本应用已取得 Root，可开始备份或解锁")
            RunState.state = RunState.state.copy(rootStatus = "已提权")
        }
    }

    fun deploy() = step("部署资产") { adapter ->
        withContext(Dispatchers.IO) { adapter.deploy(ShellAccess, app) }
        state = state.copy(deployed = true, stage = "资产部署完成")
    }
    fun acquire() = step("开始提权") { adapter ->
        val provider = withContext(Dispatchers.IO) { adapter.acquireRoot(ShellAccess, app) }
        val result = withContext(Dispatchers.IO) { RootShell.setIntegratedProvider(provider, File(app.filesDir, "root-provider-check.log")) }
        check(result.exitCode == 0) { result.output }
        state = state.copy(rootReady = true, stage = "本应用已完成提权，可手动前往备份或解锁页")
        RunState.state = RunState.state.copy(rootStatus = "已提权")
    }
    fun clean() = step("清理临时文件") { adapter ->
        withContext(Dispatchers.IO) { adapter.clean(ShellAccess, app) }
        // 清理会删除 bfexec 执行通道，已注册的集成 Root 通道一并失效。
        withContext(Dispatchers.IO) { RootShell.invalidate() }
        autoRootAttempted = false
        state = state.copy(deployed = false, rootReady = false, stage = "提权临时文件已清理")
        RunState.state = RunState.state.copy(rootStatus = "提权待授权")
    }
    fun checkRoot() = task("正在检查本应用提权状态") {
        val result = withContext(Dispatchers.IO) { RootShell.check(File(app.filesDir, "root-readonly-check.log")) }
        val ready = result.exitCode == 0
        state = state.copy(rootReady = ready, stage = if (ready) "本应用提权通道已就绪" else "本应用尚未完成提权")
        append(result.output)
        RunState.state = RunState.state.copy(rootStatus = if (ready) "已提权" else "提权待授权")
    }

    private fun step(label: String, operation: suspend (RootAdapter) -> Unit) {
        val adapter = RootIntegration.adapter
        if (adapter == null) {
            state = state.copy(stage = "$label 接口待适配", error = "此版本保留提权操作框架，尚未接入提权实现")
            append("$label：等待接入提权接口")
            return
        }
        task(label) {
            withContext(Dispatchers.IO) { ShellAccess.check(app) }
            operation(adapter)
        }
    }

    private fun task(label: String, onError: (() -> Unit)? = null, operation: suspend () -> Unit) {
        if (RunState.state.busy || state.busy) return
        RunState.state = RunState.state.copy(busy = true)
        state = state.copy(busy = true, stage = label, error = null)
        viewModelScope.launch {
            try { operation() }
            catch (error: Exception) {
                state = state.copy(error = error.message ?: error.javaClass.simpleName, stage = "$label 已停止")
                append(state.error.orEmpty())
                onError?.invoke()
            } finally {
                state = state.copy(busy = false)
                RunState.state = RunState.state.copy(busy = false)
            }
        }
    }
    private fun append(message: String) { state = state.copy(logs = state.logs + message.lines()) }
}
