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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RootFrameworkModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(RootUiState(stage = "Shell 就绪后，请手动点击执行提权流程"))
        private set
    private val app get() = getApplication<Application>()
    private var deployedBinding: RootBinding? = null
    private var activeBinding: RootBinding?
        get() = RootIntegration.activeBinding
        set(value) { RootIntegration.activeBinding = value }
    private val loggedAdapters = mutableSetOf<RootAdapter>()

    fun selectChain(chain: RootChain) {
        if (RunState.state.busy || state.busy || state.selectedChain == chain) return
        deployedBinding = null
        state = state.copy(selectedChain = chain, deployed = false, error = null,
            stage = "已选择 ${chain.id}，请手动执行提权流程")
    }

    fun deploy() = step("部署提权资产") { binding -> deploy(binding) }

    /** The execution button is the only entry to deployment + elevation + UID-0 registration. */
    fun acquire() = step("执行提权流程") { binding ->
        if (deployedBinding != binding) deploy(binding)
        state = state.copy(stage = "${binding.chain.id}：正在执行提权链，请耐心等待")
        val provider = withContext(Dispatchers.IO) { binding.adapter.acquireRoot(ShellAccess, app) }
        registerProvider(binding, provider)
    }

    private suspend fun deploy(binding: RootBinding) {
        deployedBinding = null
        state = state.copy(deployed = false, stage = "${binding.chain.id}：正在部署提权资产")
        withContext(Dispatchers.IO) { binding.adapter.deploy(ShellAccess, app) }
        deployedBinding = binding
        state = state.copy(deployed = true, stage = "${binding.chain.id}：资产部署完成")
    }

    private suspend fun registerProvider(binding: RootBinding, provider: RootProcessProvider) {
        val result = withContext(Dispatchers.IO) {
            RootShell.setIntegratedProvider(provider, File(app.filesDir, "root-provider-check.log"))
        }
        check(result.exitCode == 0) { result.output }
        activeBinding = binding
        state = state.copy(rootReady = true, stage = "${binding.chain.id}：本应用已完成提权，可前往备份或解锁页")
        RunState.state = RunState.state.copy(rootStatus = "已提权")
        append(result.output)
    }

    fun clean() {
        // A selection change must not redirect cleanup away from the actual live provider.
        val binding = activeBinding ?: RootIntegration.adapterFor(state.selectedChain)?.let { RootBinding(state.selectedChain, it) }
        step("清理提权临时文件", binding) { owner ->
            withContext(Dispatchers.IO) { owner.adapter.clean(ShellAccess, app) }
            if (activeBinding == owner) {
                withContext(Dispatchers.IO) { RootShell.invalidate() }
                activeBinding = null
                state = state.copy(rootReady = false)
                RunState.state = RunState.state.copy(rootStatus = "提权待授权")
            }
            if (deployedBinding == owner) {
                deployedBinding = null
                state = state.copy(deployed = false)
            }
            state = state.copy(stage = "${owner.chain.id}：提权临时文件已清理")
        }
    }

    fun checkRoot() = task("正在检查本应用提权状态") {
        val result = withContext(Dispatchers.IO) { RootShell.check(File(app.filesDir, "root-readonly-check.log")) }
        val ready = result.exitCode == 0
        if (!ready) activeBinding = null
        state = state.copy(rootReady = ready, stage = if (ready) "本应用提权通道已就绪" else "本应用尚未完成提权")
        append(result.output)
        RunState.state = RunState.state.copy(rootStatus = if (ready) "已提权" else "提权待授权")
    }

    private fun step(label: String, pinned: RootBinding? = null, operation: suspend (RootBinding) -> Unit) {
        if (RunState.state.busy || state.busy) return
        val chain = state.selectedChain
        val binding = pinned ?: RootIntegration.adapterFor(chain)?.let { RootBinding(chain, it) }
        if (binding == null) {
            state = state.copy(stage = "${chain.id}：接口待对接", error = "请先接入 ${chain.id} 的提权实现")
            append("${chain.id}：$label 等待接入提权接口")
            return
        }
        // Pin both the option and backend before launching any asynchronous work.
        task("${binding.chain.id}：$label") {
            val callback: (String) -> Unit = { message ->
                viewModelScope.launch { append("[${binding.chain.id}] $message") }
            }
            loggedAdapters += binding.adapter
            binding.adapter.setLogCallback(callback)
            (binding.adapter as? MagicRootAdapter)?.onLog = callback
            withContext(Dispatchers.IO) { ShellAccess.check(app) }
            operation(binding)
        }
    }

    private fun task(label: String, operation: suspend () -> Unit) {
        if (RunState.state.busy || state.busy) return
        RunState.state = RunState.state.copy(busy = true)
        state = state.copy(busy = true, stage = label, error = null)
        viewModelScope.launch {
            try { operation() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                state = state.copy(error = error.message ?: error.javaClass.simpleName, stage = "$label 已停止")
                append(state.error.orEmpty())
            } finally {
                state = state.copy(busy = false)
                RunState.state = RunState.state.copy(busy = false)
            }
        }
    }

    override fun onCleared() {
        loggedAdapters.forEach { adapter ->
            adapter.setLogCallback(null)
            (adapter as? MagicRootAdapter)?.onLog = null
        }
        super.onCleared()
    }

    private fun append(message: String) { state = state.copy(logs = state.logs + message.lines()) }
}
