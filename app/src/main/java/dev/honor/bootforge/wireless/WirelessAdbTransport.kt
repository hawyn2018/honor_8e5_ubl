package dev.honor.bootforge.wireless

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * App-owned wireless ADB client. Call these blocking methods on Dispatchers.IO.
 * Pairing is real Android SPAKE2/TLS pairing; commands run as adbd's shell UID.
 * It neither installs Shizuku nor changes the application's Linux UID.
 */
@RequiresApi(Build.VERSION_CODES.R)
class WirelessAdbTransport(context: Context) {
    private val appContext = context.applicationContext
    private val identity: AdbIdentity get() = sharedIdentity(appContext)

    fun pair(host: String, pairPort: Int, code: String) {
        validateEndpoint(host, pairPort)
        require(code.matches(Regex("[0-9]{6}"))) { "Pairing code must contain six digits" }
        AdbPairing(identity).use { it.pair(host, pairPort, code) }
    }

    /** Cancellation closes the active socket rather than waiting for its timeout. */
    suspend fun pairCancellable(host: String, pairPort: Int, code: String) {
        validateEndpoint(host, pairPort)
        require(code.matches(Regex("[0-9]{6}"))) { "Pairing code must contain six digits" }
        withContext(Dispatchers.IO) {
            AdbPairing(identity).use { pairing ->
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { pairing.close() }
                    try {
                        pairing.pair(host, pairPort, code)
                        continuation.resume(Unit)
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }
            }
        }
    }

    /**
     * Starts a noninteractive shell-v2 process without waiting for a network handshake.
     * Both output streams are binary, separate and bounded. stdin is closed remotely
     * once the stream opens. Network/protocol failures exit 125; destroy exits 143.
     */
    fun startProcess(host: String, connectPort: Int, command: String): Process {
        validateEndpoint(host, connectPort)
        require(command.isNotBlank() && '\u0000' !in command) { "Invalid ADB shell command" }
        require("shell,v2,raw:$command\u0000".toByteArray(Charsets.UTF_8).size <= AdbCommands.MAX_DATA) {
            "ADB shell command is too long"
        }
        return AdbShellProcess(host, connectPort, command) { identity }
    }

    /** Executes one shell command and returns its combined stdout/stderr (shell v1). */
    fun execute(host: String, connectPort: Int, command: String): String {
        validateEndpoint(host, connectPort)
        require(command.isNotBlank() && '\u0000' !in command) { "Invalid ADB shell command" }
        val request = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
        require(request.size <= AdbCommands.MAX_DATA) { "ADB shell command is too long" }
        Socket().use { plain ->
            plain.connect(InetSocketAddress(host, connectPort), 5_000)
            plain.soTimeout = 15_000
            plain.tcpNoDelay = true
            var input = DataInputStream(plain.inputStream)
            var output = DataOutputStream(plain.outputStream)
            fun send(commandId: Int, a0: Int = 0, a1: Int = 0, data: ByteArray = byteArrayOf()) =
                AdbPacket(commandId, a0, a1, data).write(output)
            send(AdbCommands.CNXN, AdbCommands.VERSION, AdbCommands.MAX_DATA, "host::\u0000".toByteArray())
            var response = AdbPacket.read(input)
            var tls: SSLSocket? = null
            try {
                if (response.command == AdbCommands.STLS) {
                    send(AdbCommands.STLS, AdbCommands.VERSION)
                    tls = identity.sslContext.socketFactory.createSocket(plain, host, connectPort, true) as SSLSocket
                    tls.soTimeout = 15_000
                    tls.startHandshake()
                    input = DataInputStream(tls.inputStream)
                    output = DataOutputStream(tls.outputStream)
                    response = AdbPacket.read(input)
                } else if (response.command == AdbCommands.AUTH) {
                    if (response.arg0 != 1) throw IOException("Invalid ADB authentication challenge")
                    send(AdbCommands.AUTH, 2, data = identity.signToken(response.data))
                    response = AdbPacket.read(input)
                    if (response.command == AdbCommands.AUTH && response.arg0 == 1) {
                        send(AdbCommands.AUTH, 3, data = identity.adbPublicKey)
                        response = AdbPacket.read(input)
                    }
                }
                if (response.command != AdbCommands.CNXN) throw IOException("ADB connection was not authorized; pair again")
                send(AdbCommands.OPEN, 1, data = request)
                response = AdbPacket.read(input)
                if (response.command != AdbCommands.OKAY || response.arg1 != 1) throw IOException("ADB shell stream did not open")
                val remoteId = response.arg0
                val result = ByteArrayOutputStream()
                while (true) {
                    response = AdbPacket.read(input)
                    if (response.arg0 != remoteId || response.arg1 != 1) throw IOException("Invalid ADB shell stream identifier")
                    when (response.command) {
                        AdbCommands.WRTE -> {
                            if (result.size() + response.data.size > 1024 * 1024) throw IOException("ADB command output exceeds limit")
                            result.write(response.data)
                            send(AdbCommands.OKAY, 1, remoteId)
                        }
                        AdbCommands.CLSE -> {
                            send(AdbCommands.CLSE, 1, remoteId)
                            return result.toString(Charsets.UTF_8.name())
                        }
                        else -> throw IOException("Unexpected ADB shell stream packet")
                    }
                }
            } finally {
                runCatching { tls?.close() }
            }
        }
    }

    companion object {
        @Volatile private var cachedIdentity: AdbIdentity? = null
        @Synchronized private fun sharedIdentity(context: Context): AdbIdentity =
            cachedIdentity ?: AdbIdentity(context).also { cachedIdentity = it }

        private fun validateEndpoint(host: String, port: Int) {
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "Wireless debugging requires Android 11 or later" }
            require(host.isNotBlank() && host.length <= 255 && host.none(Char::isWhitespace)) { "Invalid ADB address" }
            require(port in 1..65535) { "ADB port must be between 1 and 65535" }
        }
    }
}
