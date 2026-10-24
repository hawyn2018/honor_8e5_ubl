package dev.honor.bootforge.wireless

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbPacketsTest {
    private fun encoded(packet: AdbPacket): ByteArray = ByteArrayOutputStream().also {
        packet.write(DataOutputStream(it))
    }.toByteArray()

    private fun decode(bytes: ByteArray) = AdbPacket.read(DataInputStream(ByteArrayInputStream(bytes)))

    @Test fun adbPacketUsesLittleEndianAndUnsignedByteChecksum() {
        val bytes = encoded(AdbPacket(AdbCommands.WRTE, 12, 1, byteArrayOf(0xff.toByte(), 2)))
        assertArrayEquals(byteArrayOf('W'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte()), bytes.copyOfRange(0, 4))
        assertEquals(257, ByteBuffer.wrap(bytes, 16, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        val decoded = decode(bytes)
        assertEquals(12, decoded.arg0)
        assertEquals(1, decoded.arg1)
        assertArrayEquals(byteArrayOf(0xff.toByte(), 2), decoded.data)
    }

    @Test fun damagedHeaderRejectedBeforePayloadRead() {
        val bytes = encoded(AdbPacket(AdbCommands.CNXN, 0, 0))
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { decode(bytes) }
    }

    @Test fun unreasonablePayloadLengthRejectedBeforeAllocation() {
        for (length in intArrayOf(-1, 1024 * 1024 + 1, Int.MAX_VALUE)) {
            val bytes = encoded(AdbPacket(AdbCommands.WRTE, 1, 1))
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, length)
            assertThrows(IOException::class.java) { decode(bytes) }
        }
    }

    @Test fun alteredPayloadChecksumRejected() {
        val bytes = encoded(AdbPacket(AdbCommands.WRTE, 1, 1, byteArrayOf(3)))
        bytes[24] = 4
        assertThrows(IOException::class.java) { decode(bytes) }
    }

    @Test fun incompletePayloadFailsInsteadOfReturningPartialData() {
        val bytes = encoded(AdbPacket(AdbCommands.WRTE, 1, 1, byteArrayOf(3, 4, 5)))
        assertThrows(EOFException::class.java) { decode(bytes.copyOf(bytes.size - 1)) }
    }
}
