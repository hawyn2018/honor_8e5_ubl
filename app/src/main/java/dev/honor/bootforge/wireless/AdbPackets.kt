/* ADB wire protocol adapted from RikkaApps/Shizuku v13.6.0, Apache-2.0. */
package dev.honor.bootforge.wireless

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = byteArrayOf()) {
    fun write(output: DataOutputStream) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(checksum(data)).putInt(command xor -1).array()
        output.write(header)
        output.write(data)
        output.flush()
    }

    companion object {
        private const val MAX_RECEIVE_PAYLOAD = 1024 * 1024
        private fun checksum(data: ByteArray): Int = data.fold(0) { sum, byte -> sum + (byte.toInt() and 0xff) }

        fun read(input: DataInputStream): AdbPacket {
            val bytes = ByteArray(24)
            input.readFully(bytes)
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val length = header.int
            val expectedChecksum = header.int
            val magic = header.int
            // Validate allocation bounds and magic before allocating/reading payload.
            if (magic != (command xor -1) || length !in 0..MAX_RECEIVE_PAYLOAD) throw IOException("Invalid ADB packet header")
            val data = ByteArray(length)
            input.readFully(data)
            if (checksum(data) != expectedChecksum) throw IOException("Invalid ADB packet checksum")
            return AdbPacket(command, arg0, arg1, data)
        }
    }
}

internal object AdbCommands {
    const val CNXN = 0x4e584e43
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257
    const val STLS = 0x534c5453
    // Protocol v1 deliberately keeps checksums enabled on both sides.
    const val VERSION = 0x01000000
    // Advertise ADB's standard 1 MiB payload: MagicRoot asset deploy commands carry
    // 100 KiB base64 chunks, and adbd on Android 11+ accepts 1 MiB packets.
    // The receive bound stays at MAX_RECEIVE_PAYLOAD (1 MiB) either way.
    const val MAX_DATA = 1024 * 1024
}
