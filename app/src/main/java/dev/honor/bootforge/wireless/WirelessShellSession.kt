package dev.honor.bootforge.wireless

import dev.honor.bootforge.core.RootProcessProvider
import dev.honor.bootforge.core.ShellResult

internal data class WirelessEndpoint(val host: String, val port: Int)

/** A command uses the endpoint it was granted, never an editable UI address. */
internal class WirelessRootProvider(private val startProcess: (String) -> Process) : RootProcessProvider {
    override fun start(command: String): Process {
        require(command.isNotBlank() && '\u0000' !in command) { "Invalid ADB shell command" }
        // The guard and payload run in ONE remote shell. No stdout banners, su, or replay.
        val guarded = """
            __hnubl_wireless_uid=${'$'}(id -u) || exit 126
            if [ "${'$'}__hnubl_wireless_uid" != 0 ]; then
                printf '%s\n' 'HNUBL_WIRELESS_ROOT_REQUIRED: reconnect wireless debugging with UID 0' >&2
                exit 126
            fi
            unset __hnubl_wireless_uid
        """.trimIndent() + "\n" + command
        return startProcess(guarded)
    }
}

/** Transactional endpoint changes, separate from Android UI and transport lifetimes. */
internal class WirelessShellSession(
    private val readIdentity: (WirelessEndpoint) -> ShellResult,
    private val startProcess: (WirelessEndpoint, String) -> Process,
    private val registerRoot: (RootProcessProvider) -> ShellResult,
    private val revokeRoot: (RootProcessProvider) -> Unit,
    private val shouldRegisterRoot: () -> Boolean = { true },
) {
    private data class State(val endpoint: WirelessEndpoint, val uid: Int, val root: RootProcessProvider?)
    @Volatile private var state: State? = null
    val endpoint: WirelessEndpoint? get() = state?.endpoint
    val uid: Int? get() = state?.uid
    fun hasSession(): Boolean = state != null

    fun start(command: String): Process {
        val current = checkNotNull(state) { "请先获取 Shell 权限" }
        return startProcess(current.endpoint, command)
    }

    @Synchronized
    fun connect(target: WirelessEndpoint): Int {
        val identity = readIdentity(target)
        val uid = when {
            identity.exitCode != 0 -> null
            identity.output.trim() == "0" -> 0
            identity.output.trim() == "2000" -> 2000
            else -> null
        }
        check(uid != null) { "Shell 身份校验未通过：${identity.output}" }
        val provider = if (uid == 0 && shouldRegisterRoot()) {
            WirelessRootProvider { command -> startProcess(target, command) }.also { candidate ->
                val proof = registerRoot(candidate)
                check(proof.exitCode == 0) { proof.output }
            }
        } else null
        // Verification and registration complete before the old session changes.
        val previous = state
        state = State(target, uid, provider)
        previous?.root?.let(revokeRoot)
        return uid
    }

    @Synchronized
    fun check(restore: WirelessEndpoint? = null): Int {
        val previous = state
        val target = previous?.endpoint ?: checkNotNull(restore) { "请先配对并获取 Shell 权限" }
        return try { connect(target) }
        catch (error: Exception) {
            state = null
            previous?.root?.let(revokeRoot)
            throw error
        }
    }
}
