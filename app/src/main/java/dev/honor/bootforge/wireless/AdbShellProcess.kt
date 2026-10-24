/*
 * ADB transport follows Shizuku v13.6.0 (Apache-2.0); shell v2 framing follows
 * AOSP packages/modules/adb/shell_protocol.h and daemon/shell_service.cpp.
 */
package dev.honor.bootforge.wireless

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** A noninteractive Process whose network work is confined to a dedicated IO thread. */
internal class AdbShellProcess(
    private val host: String,
    private val port: Int,
    private val command: String,
    private val identity: () -> AdbIdentity,
) : Process() {
    private val stdout = PipedInputStream(64 * 1024)
    private val stderr = PipedInputStream(64 * 1024)
    private val stdoutWriter = PipedOutputStream(stdout)
    private val stderrWriter = PipedOutputStream(stderr)
    private val completed = CountDownLatch(1)
    private val destroyed = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var result: Int? = null

    // This release has no interactive input. Closing is nonblocking and idempotent;
    // the worker always sends close_stdin as soon as ADB OPEN is acknowledged.
    private val stdin = object : OutputStream() {
        override fun write(value: Int): Unit = throw IOException("This ADB process has no interactive stdin")
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length != 0) throw IOException("This ADB process has no interactive stdin")
        }
        override fun close() = Unit
    }

    private val worker = thread(start = false, isDaemon = true, name = "BootForge-adb-shell-v2") {
        var exitCode = 125
        try {
            if (destroyed.get()) throw IOException("ADB process was destroyed")
            exitCode = runCommand()
        } catch (_: Throwable) {
            if (destroyed.get()) exitCode = 143
            else {
                // Keep diagnostics useful while never printing the command, key, token
                // or pairing code supplied by another layer.
                runCatching { stderrWriter.write("ADB Shell connection or protocol failed; reconnect wireless debugging.\n".toByteArray()) }
            }
        } finally {
            runCatching { socket?.close() }
            runCatching { stdoutWriter.close() }
            runCatching { stderrWriter.close() }
            result = if (destroyed.get()) 143 else exitCode
            completed.countDown()
        }
    }.also(Thread::start)

    private fun runCommand(): Int {
        Socket().use { plain ->
            socket = plain
            if (destroyed.get()) throw IOException("ADB process was destroyed")
            plain.connect(InetSocketAddress(host, port), 5_000)
            plain.soTimeout = 15_000
            plain.tcpNoDelay = true
            var input = DataInputStream(plain.inputStream)
            var output = DataOutputStream(plain.outputStream)
            fun send(id: Int, a0: Int = 0, a1: Int = 0, data: ByteArray = byteArrayOf()) =
                AdbPacket(id, a0, a1, data).write(output)

            send(AdbCommands.CNXN, AdbCommands.VERSION, AdbCommands.MAX_DATA,
                "host::features=shell_v2\u0000".toByteArray(Charsets.UTF_8))
            var response = AdbPacket.read(input)
            var tls: SSLSocket? = null
            try {
                if (response.command == AdbCommands.STLS) {
                    send(AdbCommands.STLS, AdbCommands.VERSION, 0)
                    tls = identity().sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
                    tls.soTimeout = 15_000
                    tls.startHandshake()
                    input = DataInputStream(tls.inputStream)
                    output = DataOutputStream(tls.outputStream)
                    response = AdbPacket.read(input)
                } else if (response.command == AdbCommands.AUTH) {
                    if (response.arg0 != 1) throw IOException("Invalid ADB authentication challenge")
                    send(AdbCommands.AUTH, 2, data = identity().signToken(response.data))
                    response = AdbPacket.read(input)
                    if (response.command == AdbCommands.AUTH && response.arg0 == 1) {
                        send(AdbCommands.AUTH, 3, data = identity().adbPublicKey)
                        response = AdbPacket.read(input)
                    }
                }
                if (response.command != AdbCommands.CNXN) throw IOException("ADB connection is not authorized")
                // Keep shell-v2 mandatory: raw stdout alone cannot carry a real exit
                // status or distinguish stderr from a binary partition backup.
                val features = response.data.toString(Charsets.UTF_8)
                if (!features.contains("shell_v2")) throw IOException("adbd does not advertise shell_v2")
                send(AdbCommands.OPEN, 1, data = "shell,v2,raw:$command\u0000".toByteArray(Charsets.UTF_8))
                response = AdbPacket.read(input)
                if (response.command != AdbCommands.OKAY || response.arg1 != 1) throw IOException("ADB shell stream did not open")
                val remoteId = response.arg0
                // shell_protocol.h: id=4, uint32 little-endian length=0.
                send(AdbCommands.WRTE, 1, remoteId, byteArrayOf(4, 0, 0, 0, 0))
                // Command lifetime belongs to Process.waitFor/destroy. A long, silent
                // task must not be cut off by the handshake's socket timeout.
                plain.soTimeout = 0
                tls?.soTimeout = 0
                val decoder = ShellV2Decoder(
                    onStdout = { bytes, offset, count -> stdoutWriter.write(bytes, offset, count) },
                    onStderr = { bytes, offset, count -> stderrWriter.write(bytes, offset, count) },
                )
                while (true) {
                    if (destroyed.get()) throw IOException("ADB process was destroyed")
                    response = AdbPacket.read(input)
                    if (response.arg0 != remoteId || response.arg1 != 1) throw IOException("Invalid ADB shell stream identifier")
                    when (response.command) {
                        AdbCommands.OKAY -> Unit // Acknowledgement of close_stdin.
                        AdbCommands.WRTE -> {
                            decoder.feed(response.data)
                            decoder.exitCode?.let { code ->
                                decoder.finish()
                                // A complete EXIT is authoritative even if adbd
                                // disconnects while we acknowledge final cleanup.
                                runCatching { send(AdbCommands.OKAY, 1, remoteId) }
                                runCatching { send(AdbCommands.CLSE, 1, remoteId) }
                                return code
                            }
                            send(AdbCommands.OKAY, 1, remoteId)
                        }
                        AdbCommands.CLSE -> {
                            send(AdbCommands.CLSE, 1, remoteId)
                            decoder.finish()
                            return checkNotNull(decoder.exitCode)
                        }
                        else -> throw IOException("Unexpected ADB shell stream packet")
                    }
                }
            } finally {
                runCatching { tls?.close() }
            }
        }
    }

    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = stderr
    override fun getOutputStream(): OutputStream = stdin
    override fun waitFor(): Int { completed.await(); return checkNotNull(result) }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = completed.await(timeout, unit)
    override fun exitValue(): Int = result ?: throw IllegalThreadStateException("ADB process is still running")
    override fun isAlive(): Boolean = completed.count != 0L
    override fun destroy() {
        if (!isAlive) return
        destroyed.set(true)
        runCatching { socket?.close() }
        worker.interrupt()
    }
    override fun destroyForcibly(): Process { destroy(); return this }
}

/** Incremental shell-v2 decoder: ADB packets may split any header or payload byte. */
internal class ShellV2Decoder(
    private val onStdout: (ByteArray, Int, Int) -> Unit,
    private val onStderr: (ByteArray, Int, Int) -> Unit,
) {
    private val header = ByteArray(5)
    private var headerUsed = 0
    private var id = -1
    private var remaining = 0
    var exitCode: Int? = null
        private set

    fun feed(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (exitCode != null) throw IOException("Data after ADB shell exit")
            if (headerUsed < header.size) {
                val count = minOf(header.size - headerUsed, bytes.size - offset)
                bytes.copyInto(header, headerUsed, offset, offset + count)
                headerUsed += count
                offset += count
                if (headerUsed != header.size) continue
                id = header[0].toInt() and 0xff
                remaining = (header[1].toInt() and 0xff) or ((header[2].toInt() and 0xff) shl 8) or
                    ((header[3].toInt() and 0xff) shl 16) or ((header[4].toInt() and 0xff) shl 24)
                if (id !in 1..3 || remaining !in 0..(16 * 1024 * 1024) || (id == 3 && remaining != 1)) {
                    throw IOException("Invalid ADB shell-v2 frame")
                }
                if (remaining == 0) { headerUsed = 0; continue }
            }
            val count = minOf(remaining, bytes.size - offset)
            if (count == 0) continue
            when (id) {
                1 -> onStdout(bytes, offset, count)
                2 -> onStderr(bytes, offset, count)
                3 -> exitCode = bytes[offset].toInt() and 0xff
            }
            offset += count
            remaining -= count
            if (remaining == 0) headerUsed = 0
        }
    }

    fun finish() {
        if (headerUsed != 0 || remaining != 0 || exitCode == null) throw IOException("ADB shell ended without a complete exit status")
    }
}
