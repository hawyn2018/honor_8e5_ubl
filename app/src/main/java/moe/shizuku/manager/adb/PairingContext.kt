/*
 * Adapted from RikkaApps/Shizuku v13.6.0, Apache-2.0.
 * Package, class and native signatures must match the official libadb.so JNI ABI.
 */
package moe.shizuku.manager.adb

import androidx.annotation.Keep
import java.io.Closeable

@Keep
internal class PairingContext private constructor(private var nativePtr: Long) : Closeable {
    val message: ByteArray get() = nativeMsg(nativePtr)
    fun initializeCipher(peerMessage: ByteArray) = nativeInitCipher(nativePtr, peerMessage)
    fun encrypt(plain: ByteArray) = nativeEncrypt(nativePtr, plain)
    fun decrypt(encrypted: ByteArray) = nativeDecrypt(nativePtr, encrypted)

    override fun close() {
        if (nativePtr != 0L) {
            nativeDestroy(nativePtr)
            nativePtr = 0
        }
    }

    private external fun nativeMsg(nativePtr: Long): ByteArray
    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean
    private external fun nativeEncrypt(nativePtr: Long, plain: ByteArray): ByteArray?
    private external fun nativeDecrypt(nativePtr: Long, encrypted: ByteArray): ByteArray?
    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        fun create(password: ByteArray): PairingContext {
            PairingNative.ensureLoaded()
            val pointer = nativeConstructor(true, password)
            check(pointer != 0L) { "Failed to initialize ADB SPAKE2 pairing" }
            return PairingContext(pointer)
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}

private object PairingNative {
    init { System.loadLibrary("adb") }
    fun ensureLoaded() = Unit
}
