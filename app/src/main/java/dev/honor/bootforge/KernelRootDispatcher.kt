package dev.honor.bootforge

import android.app.Application
import dev.honor.bootforge.core.RootProcessProvider
import dev.honor.bootforge.wireless.ShellAccess

internal const val NEW_ROOT_KERNEL_G459A = "6.12.58-android16-6-g459a4285ccf8-abogki532718632-4k"

/** An exact kernel release paired with the boot in which it was observed. */
internal data class KernelIdentity(val release: String, val bootId: String) {
    init {
        require(release.isNotBlank() && release == release.trim() && release.none { it == '\r' || it == '\n' }) {
            "完整内核版本读取无效"
        }
        require(bootId.isNotBlank() && bootId == bootId.trim() && bootId.none { it == '\r' || it == '\n' }) {
            "启动周期读取无效"
        }
    }
}

internal enum class KernelRootRoute { BUILTIN, EXTRA }

/** Existing exact releases retain their original adapters; no prefix fallback. */
internal fun kernelRootRoute(release: String): KernelRootRoute? = when {
    rootProfileForKernel(release) != null -> KernelRootRoute.BUILTIN
    release == NEW_ROOT_KERNEL_G459A -> KernelRootRoute.EXTRA
    else -> null
}

/**
 * Pure session ownership: select before a callback, retain ownership on failure,
 * and release it only after successful cleanup. No Android or process I/O here.
 */
internal class KernelDispatchSession<T>(private val choose: (KernelIdentity) -> T) {
    private class Owner<T>(val identity: KernelIdentity, val delegate: T)
    private var owner: Owner<T>? = null
    private var running = false

    suspend fun deploy(identity: KernelIdentity, action: suspend (T) -> Unit) =
        withPinned(identity, false, action)

    suspend fun <R> acquire(identity: KernelIdentity, action: suspend (T) -> R): R =
        withPinned(identity, false, action)

    suspend fun clean(identity: KernelIdentity, action: suspend (T) -> Unit) =
        withPinned(identity, true, action)

    private suspend fun <R> withPinned(
        identity: KernelIdentity,
        releaseOnSuccess: Boolean,
        action: suspend (T) -> R,
    ): R {
        val selected = synchronized(this) {
            check(!running) { "该提权接口已有操作正在运行" }
            val previous = owner
            val selected = if (previous == null) Owner(identity, choose(identity)).also { owner = it }
            else previous.also {
                check(it.identity == identity) { "内核或启动周期发生变化，请重新建立提权会话" }
            }
            running = true
            selected
        }
        try {
            val result = action(selected.delegate)
            if (releaseOnSuccess) synchronized(this) {
                check(owner === selected) { "提权会话归属发生变化" }
                owner = null
            }
            return result
        } finally {
            synchronized(this) { running = false }
        }
    }
}

/**
 * Per-chain dispatcher. The built-in implementations and their payloads are
 * delegated unchanged; the additional release has its own explicit adapter.
 * Construction/registration performs no Shell or deployment operation.
 */
class KernelDispatchRootAdapter(
    private val chain: RootChain,
    private val builtin: RootAdapter,
    private val extra: RootAdapter,
) : RootAdapter {
    @Volatile private var logCallback: ((String) -> Unit)? = null
    private val session = KernelDispatchSession<RootAdapter>(::resolveBackend)

    /** Shared by the real dispatch path and host routing tests; performs no I/O. */
    internal fun resolveBackend(identity: KernelIdentity): RootAdapter = when (kernelRootRoute(identity.release)) {
        KernelRootRoute.BUILTIN -> builtin
        KernelRootRoute.EXTRA -> extra
        null -> error("${chain.id} 未接入完整内核版本：${identity.release}")
    }

    override fun setLogCallback(callback: ((String) -> Unit)?) {
        logCallback = callback
        // ViewModel.onCleared only knows this dispatcher. Always visit both
        // delegates, including when one implementation rejects a callback.
        try { builtin.setLogCallback(callback) }
        finally { extra.setLogCallback(callback) }
    }

    override suspend fun deploy(shell: ShellAccess, application: Application) {
        val identity = readIdentity(shell, application)
        session.deploy(identity) { delegate ->
            reportRoute(identity, delegate)
            delegate.deploy(shell, application)
        }
    }

    override suspend fun acquireRoot(shell: ShellAccess, application: Application): RootProcessProvider {
        val identity = readIdentity(shell, application)
        return session.acquire(identity) { delegate ->
            reportRoute(identity, delegate)
            // Keep the real provider and its Process/streams/exit semantics.
            // RootFrameworkModel still proves id -u == 0 before registration.
            delegate.acquireRoot(shell, application)
        }
    }

    override suspend fun clean(shell: ShellAccess, application: Application) {
        val identity = readIdentity(shell, application)
        session.clean(identity) { delegate -> delegate.clean(shell, application) }
    }

    private fun readIdentity(shell: ShellAccess, application: Application): KernelIdentity {
        val before = runRootChecked(shell, application, "cat /proc/sys/kernel/random/boot_id", 10,
            "${chain.id} 读取启动周期").output.trim()
        val release = runRootChecked(shell, application, "uname -r", 10,
            "${chain.id} 读取完整内核版本").output.trim()
        val after = runRootChecked(shell, application, "cat /proc/sys/kernel/random/boot_id", 10,
            "${chain.id} 复核启动周期").output.trim()
        check(before == after) { "读取内核版本期间启动周期发生变化，请重新获取 Shell" }
        return KernelIdentity(release, after)
    }

    private fun reportRoute(identity: KernelIdentity, delegate: RootAdapter) {
        runCatching {
            logCallback?.invoke("${chain.id}：完整内核=${identity.release}；路由=${if (delegate === builtin) "原有实现" else "新增内核接口"}")
        }
    }
}
