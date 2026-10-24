/* Wireless ADB pairing protocol adapted from RikkaApps/Shizuku v13.6.0, Apache-2.0. */
package dev.honor.bootforge.wireless

import moe.shizuku.manager.adb.PairingContext
import org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.SSLSocket

internal class AdbPairing(private val identity: AdbIdentity) : Closeable {
    private val sockets = PairingSocketOwner()

    fun pair(host: String, port: Int, code: String) {
        Socket().use { plain ->
            sockets.replace(plain)
            atStage(PairingStage.CONNECTING) {
                plain.connect(InetSocketAddress(host, port), 5_000)
                plain.soTimeout = 15_000
                plain.tcpNoDelay = true
            }
            atStage(PairingStage.TLS_HANDSHAKE) {
                (identity.sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket).also {
                    sockets.replace(it)
                }
            }.use { tls ->
                val exported = atStage(PairingStage.TLS_HANDSHAKE) {
                    tls.soTimeout = 15_000
                    tls.startHandshake()
                    Conscrypt.exportKeyingMaterial(tls, "adb-label\u0000", null, 64)
                        ?: throw IOException("ADB TLS session key export failed")
                }
                val password = code.toByteArray(Charsets.US_ASCII) + exported
                try {
                    atStage(PairingStage.KEY_EXCHANGE) { PairingContext.create(password) }.use { context ->
                        val input = DataInputStream(tls.inputStream)
                        val output = DataOutputStream(tls.outputStream)
                        atStage(PairingStage.KEY_EXCHANGE) {
                            write(output, 0, context.message)
                            val peerMessage = read(input, 0)
                            if (!context.initializeCipher(peerMessage)) throw IOException("ADB pairing key exchange failed")
                        }
                        atStage(PairingStage.CONFIRMING) {
                            val publicKey = identity.adbPublicKey
                            if (publicKey.size > PEER_INFO_SIZE - 1) throw IOException("ADB public key exceeds peer info limit")
                            val peerInfo = ByteArray(PEER_INFO_SIZE)
                            // type 0: ADB_RSA_PUB_KEY; the remaining bytes are zero-padded.
                            publicKey.copyInto(peerInfo, 1)
                            val encrypted = context.encrypt(peerInfo) ?: throw IOException("ADB pairing encryption failed")
                            write(output, 1, encrypted)
                            val peerEncrypted = read(input, 1)
                            val peer = context.decrypt(peerEncrypted) ?: throw IOException("ADB pairing confirmation failed")
                            if (peer.size != PEER_INFO_SIZE) throw IOException("Invalid ADB pairing peer info")
                        }
                    }
                } finally {
                    password.fill(0)
                    exported.fill(0)
                }
            }
        }
    }

    // Closing the socket interrupts blocking connect/TLS/read on cancellation. The
    // native SPAKE2 context stays on its worker thread and is released by use().
    override fun close() = sockets.close()

    private inline fun <T> atStage(stage: PairingStage, block: () -> T): T = try {
        sockets.checkActive()
        block()
    } catch (error: Exception) {
        throw AdbPairingException(stage, error)
    }

    private fun write(output: DataOutputStream, type: Int, payload: ByteArray) {
        output.writeByte(1)
        output.writeByte(type)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun read(input: DataInputStream, expectedType: Int): ByteArray {
        // The pairing header uses network byte order, unlike normal ADB packets.
        val header = ByteArray(6)
        input.readFully(header)
        val buffer = ByteBuffer.wrap(header)
        val version = buffer.get().toInt() and 0xff
        val type = buffer.get().toInt() and 0xff
        val length = buffer.int
        if (version != 1 || type != expectedType || length !in 1..(PEER_INFO_SIZE * 2)) {
            throw IOException("Invalid ADB pairing response")
        }
        return ByteArray(length).also(input::readFully)
    }

    companion object { private const val PEER_INFO_SIZE = 8192 }
}

internal enum class PairingStage(val failureMessage: String) {
    CONNECTING("连接配对端口失败，请保持系统配对窗口打开并核对配对端口"),
    TLS_HANDSHAKE("无线调试 TLS 握手未完成，请检查配对窗口和无线调试服务"),
    KEY_EXCHANGE("配对密钥交换未完成，请保持系统配对窗口打开"),
    CONFIRMING("配对确认未完成，配对码可能已更新或连接被中断"),
}

/** Show the stage, never a password, key or an unfiltered protocol payload. */
internal class AdbPairingException(val stage: PairingStage, cause: Exception) :
    IOException(stage.failureMessage, cause)

/** Tracks the plain/TLS socket across cancellation, including close-before-install. */
internal class PairingSocketOwner : Closeable {
    private val lock = Any()
    private var closed = false
    private var socket: Socket? = null

    fun replace(next: Socket) {
        val rejected = synchronized(lock) {
            if (closed) true else { socket = next; false }
        }
        if (rejected) {
            runCatching { next.close() }
            throw InterruptedIOException("ADB pairing was cancelled")
        }
    }

    fun checkActive() {
        if (synchronized(lock) { closed }) throw InterruptedIOException("ADB pairing was cancelled")
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            socket.also { socket = null }
        }
        runCatching { active?.close() }
    }
}
