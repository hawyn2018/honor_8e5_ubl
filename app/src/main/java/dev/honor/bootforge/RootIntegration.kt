package dev.honor.bootforge

import android.app.Application
import dev.honor.bootforge.core.RootProcessProvider
import dev.honor.bootforge.wireless.ShellAccess
import java.util.EnumMap

/** Implement and register each backend independently. Nothing runs on Shell connection. */
interface RootAdapter {
    suspend fun deploy(shell: ShellAccess, application: Application)
    suspend fun acquireRoot(shell: ShellAccess, application: Application): RootProcessProvider
    suspend fun clean(shell: ShellAccess, application: Application)
    fun setLogCallback(callback: ((String) -> Unit)?) {}
}

/** Shares the live provider's owner across ViewModel recreation in the same process. */
internal data class RootBinding(val chain: RootChain, val adapter: RootAdapter)

object RootIntegration {
    private val adapters = EnumMap<RootChain, RootAdapter>(RootChain::class.java)
    @Volatile internal var activeBinding: RootBinding? = null

    @Synchronized
    fun register(chain: RootChain, adapter: RootAdapter?) {
        if (adapter == null) adapters.remove(chain) else adapters[chain] = adapter
    }

    @Synchronized
    fun adapterFor(chain: RootChain): RootAdapter? = adapters[chain]

    /** Compatibility for older single-adapter registration; always targets 98260. */
    @Deprecated("Register an explicit chain with RootIntegration.register")
    var adapter: RootAdapter?
        get() = adapterFor(RootChain.DEFAULT)
        set(value) = register(RootChain.DEFAULT, value)
}
