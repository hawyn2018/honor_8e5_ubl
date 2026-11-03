package dev.honor.bootforge

import android.app.Application
import dev.honor.bootforge.core.RootProcessIO
import dev.honor.bootforge.core.RootProcessProvider
import dev.honor.bootforge.wireless.ShellAccess
import java.io.File

/** Integration lifecycle only. The four backend hooks contain no new implementation. */
abstract class NewKernelRootAdapterBase(private val chain: RootChain) : RootAdapter {
    @Volatile private var callback: ((String) -> Unit)? = null

    final override fun setLogCallback(callback: ((String) -> Unit)?) {
        this.callback = callback
    }

    protected fun log(message: String) {
        runCatching { callback?.invoke("新增内核 ${chain.id}：$message") }
    }

    protected fun pending(hook: String): Nothing = error(
        "${chain.id} / $NEW_ROOT_KERNEL_G459A 的 $hook 尚待对接；现有内核实现保持原样"
    )

    private fun checkTarget(shell: ShellAccess, application: Application) {
        val result = shell.run(application, "uname -r", 10)
        check(result.exitCode == 0 && result.output.trim() == NEW_ROOT_KERNEL_G459A) {
            "新增内核适配器仅接收完整匹配的内核：$NEW_ROOT_KERNEL_G459A"
        }
        checkRootChainAllowed(shell, application, chain)
    }

    final override suspend fun deploy(shell: ShellAccess, application: Application) {
        checkTarget(shell, application)
        deployPayload(shell, application)
    }

    final override suspend fun acquireRoot(shell: ShellAccess, application: Application): RootProcessProvider {
        checkTarget(shell, application)
        openRootSession(shell, application)
        val provider = RootProcessProvider { command -> startRootProcess(command) }
        // Verify the returned process itself; never register a textual or placeholder result.
        val stdout = File.createTempFile("new-kernel-uid-", ".out", application.cacheDir)
        val stderr = File.createTempFile("new-kernel-uid-", ".err", application.cacheDir)
        try {
            val result = RootProcessIO.collect({ provider.start("id -u") }, stdout, stderr,
                20_000, "新增内核提权进程身份检查超时")
            check(result.exitCode == 0 && stdout.readText().trim() == "0") {
                "新增内核后端尚未返回真实 UID 0 进程：${result.output}"
            }
        } finally {
            stdout.delete()
            stderr.delete()
        }
        markRootChainSuccess(shell, application, chain)
        log("命令进程 UID 0 验证通过；框架将再次验证后登记通道")
        return provider
    }

    final override suspend fun clean(shell: ShellAccess, application: Application) {
        // The dispatcher pins the original owner; cleanup must handle its own partial deployment.
        cleanPayload(shell, application)
    }

    /** Deploy only this new backend's assets, in its own working directory. */
    protected abstract suspend fun deployPayload(shell: ShellAccess, application: Application)
    /** Establish the backend; return only when startRootProcess can launch real commands. */
    protected abstract suspend fun openRootSession(shell: ShellAccess, application: Application)
    /** Promptly return one fresh Process per call; pass the command verbatim. */
    protected abstract fun startRootProcess(command: String): Process
    /** Remove only this backend's files, including any partial deployment. */
    protected abstract suspend fun cleanPayload(shell: ShellAccess, application: Application)
}
