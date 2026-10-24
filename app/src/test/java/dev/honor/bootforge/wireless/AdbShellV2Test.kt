package dev.honor.bootforge.wireless

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AdbShellV2Test {
    private fun frame(id: Int, bytes: ByteArray) = ByteBuffer.allocate(5 + bytes.size)
        .order(ByteOrder.LITTLE_ENDIAN).put(id.toByte()).putInt(bytes.size).put(bytes).array()

    @Test fun framesCanSplitAtEveryByteAndKeepBinaryStreamsSeparate() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val decoder = ShellV2Decoder(stdout::write, stderr::write)
        val expectedOut = byteArrayOf(0, 0xff.toByte(), 13, 10, 0, 100)
        val expectedErr = byteArrayOf(0xff.toByte(), 0, 13)
        val data = frame(1, expectedOut) + frame(2, expectedErr) + frame(1, byteArrayOf()) + frame(3, byteArrayOf(217.toByte()))
        data.forEach { decoder.feed(byteArrayOf(it)) }
        decoder.finish()
        assertArrayEquals(expectedOut, stdout.toByteArray())
        assertArrayEquals(expectedErr, stderr.toByteArray())
        assertEquals(217, decoder.exitCode)
    }

    @Test fun malformedOrIncompleteExitIsNeverReportedAsSuccess() {
        fun decoder() = ShellV2Decoder({ _, _, _ -> }, { _, _, _ -> })
        assertThrows(IOException::class.java) { decoder().finish() }
        assertThrows(IOException::class.java) { decoder().feed(frame(3, byteArrayOf(0, 0, 0, 0))) }
        assertThrows(IOException::class.java) { decoder().feed(frame(5, byteArrayOf())) }
        val incomplete = decoder()
        incomplete.feed(byteArrayOf(1, 5))
        assertThrows(IOException::class.java) { incomplete.finish() }
        assertThrows(IOException::class.java) { decoder().feed(frame(3, byteArrayOf(0)) + frame(1, byteArrayOf(1))) }
    }

    @Test fun processUsesShellV2ClosesStdinAndReturnsActualExitWithBinaryOutput() {
        ServerSocket(0).use { server ->
            val expectedOut = ByteArray(130_000) { (it * 71).toByte() }
            val expectedErr = ByteArray(91_000) { (it * 37).toByte() }
            val failures = mutableListOf<Throwable>()
            val fixture = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = DataInputStream(socket.inputStream)
                        val output = DataOutputStream(socket.outputStream)
                        val cnxn = AdbPacket.read(input)
                        assertEquals(AdbCommands.CNXN, cnxn.command)
                        assertTrue(cnxn.data.toString(Charsets.UTF_8).contains("shell_v2"))
                        AdbPacket(AdbCommands.CNXN, AdbCommands.VERSION, 4096, "device::features=shell_v2\u0000".toByteArray()).write(output)
                        val open = AdbPacket.read(input)
                        assertEquals(AdbCommands.OPEN, open.command)
                        assertEquals("shell,v2,raw:fixture-command\u0000", open.data.toString(Charsets.UTF_8))
                        AdbPacket(AdbCommands.OKAY, 77, 1).write(output)
                        val closeStdin = AdbPacket.read(input)
                        assertEquals(AdbCommands.WRTE, closeStdin.command)
                        assertArrayEquals(byteArrayOf(4, 0, 0, 0, 0), closeStdin.data)
                        AdbPacket(AdbCommands.OKAY, 77, 1).write(output)
                        val frames = frame(1, expectedOut) + frame(2, expectedErr) + frame(3, byteArrayOf(42))
                        // Deliberately choose odd boundaries that split shell-v2 headers.
                        frames.asList().chunked(3079).forEach { chunk ->
                            AdbPacket(AdbCommands.WRTE, 77, 1, chunk.toByteArray()).write(output)
                            assertEquals(AdbCommands.OKAY, AdbPacket.read(input).command)
                        }
                        assertEquals(AdbCommands.CLSE, AdbPacket.read(input).command)
                    }
                } catch (error: Throwable) { synchronized(failures) { failures += error } }
            }
            val process = AdbShellProcess("127.0.0.1", server.localPort, "fixture-command") { error("Fixture does not require TLS identity") }
            process.outputStream.close()
            val actualOut = ByteArrayOutputStream()
            val actualErr = ByteArrayOutputStream()
            val outputReader = thread { process.inputStream.use { it.copyTo(actualOut) } }
            val errorReader = thread { process.errorStream.use { it.copyTo(actualErr) } }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            outputReader.join(2000)
            errorReader.join(2000)
            fixture.join(2000)
            failures.firstOrNull()?.let { throw AssertionError("Fixture failed", it) }
            assertEquals(42, process.exitValue())
            assertFalse(process.isAlive)
            assertArrayEquals(expectedOut, actualOut.toByteArray())
            assertArrayEquals(expectedErr, actualErr.toByteArray())
        }
    }

    @Test fun processReturnsBeforeHandshakeAndDestroyCancelsBlockedRead() {
        ServerSocket(0).use { server ->
            var accepted: Socket? = null
            val fixture = thread(isDaemon = true) { runCatching { accepted = server.accept() } }
            val process = AdbShellProcess("127.0.0.1", server.localPort, "fixture-command") { error("Unexpected identity lookup") }
            assertFalse(process.waitFor(100, TimeUnit.MILLISECONDS))
            assertThrows(IllegalThreadStateException::class.java) { process.exitValue() }
            process.destroy()
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
            assertEquals(143, process.exitValue())
            fixture.join(2000)
            accepted?.close()
        }
    }

    @Test fun transportFailureUses125AndOnlyStderr() {
        val port = ServerSocket(0).use { it.localPort }
        val process = AdbShellProcess("127.0.0.1", port, "fixture-command") { error("Unexpected identity lookup") }
        assertTrue(process.waitFor(3, TimeUnit.SECONDS))
        assertEquals(125, process.exitValue())
        assertArrayEquals(byteArrayOf(), process.inputStream.readBytes())
        assertTrue(process.errorStream.readBytes().toString(Charsets.UTF_8).contains("ADB Shell"))
    }
}
