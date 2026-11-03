package dev.honor.bootforge.core

import android.content.Context
import java.io.File

/** Connect only through the installed module's app-private, authenticated local endpoint. */
object ModuleRootAccess {
    const val CLIENT_NAME = "libbootforge_module_client.so"

    fun configure(context: Context) {
        val locations = try {
            val application = context.applicationContext
            File(application.applicationInfo.nativeLibraryDir, CLIENT_NAME) to
                File(application.createDeviceProtectedStorageContext().filesDir, "hnubl-module/root.sock")
        } catch (_: Exception) {
            // An optional module or inaccessible storage must not prevent app startup.
            RootShell.configureModuleProvider(null)
            return
        }
        val (client, socket) = locations
        RootShell.configureModuleProvider { candidate(client, socket) }
    }

    internal fun candidate(
        client: File,
        socket: File,
        start: (List<String>) -> Process = { command -> ProcessBuilder(command).start() },
    ): RootProcessProvider? {
        if (!client.isFile || !client.canExecute() || !socket.exists()) return null
        val executable = client.absolutePath
        val endpoint = socket.absolutePath
        // UID/signature authentication is performed by the broker for every
        // connection; the client also requires the socket's peer to be UID 0.
        return RootProcessProvider { command -> start(listOf(executable, endpoint, "-c", command)) }
    }
}
