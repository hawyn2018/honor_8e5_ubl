package dev.honor.bootforge.wireless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Socket
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AdbPairingLifecycleTest {
    private class TrackedSocket : Socket() {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun cancellationClosesOnlyTheActiveTlsSocketAndIsIdempotent() {
        val owner = PairingSocketOwner()
        val plain = TrackedSocket()
        val tls = TrackedSocket()
        owner.replace(plain)
        owner.replace(tls)
        owner.close()
        owner.close()
        assertEquals(0, plain.closes)
        assertEquals(1, tls.closes)
        assertThrows(InterruptedIOException::class.java) { owner.checkActive() }
    }

    @Test fun cancellationBeforeConnectClosesTheLateSocket() {
        val owner = PairingSocketOwner()
        owner.close()
        val socket = TrackedSocket()
        assertThrows(InterruptedIOException::class.java) { owner.replace(socket) }
        assertEquals(1, socket.closes)
    }

    @Test fun cancellationBetweenPlainAndTlsInstallClosesBoth() {
        val owner = PairingSocketOwner()
        val plain = TrackedSocket()
        owner.replace(plain)
        owner.close()
        val tls = TrackedSocket()
        assertThrows(InterruptedIOException::class.java) { owner.replace(tls) }
        assertEquals(1, plain.closes)
        assertEquals(1, tls.closes)
    }

    @Test fun cancellationUnblocksARealSocketReadWithoutWaitingForReadTimeout() {
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { client ->
                server.accept().use {
                    client.soTimeout = 15_000
                    val owner = PairingSocketOwner().apply { replace(client) }
                    val entered = CountDownLatch(1)
                    val stopped = CountDownLatch(1)
                    val reader = thread(isDaemon = true) {
                        entered.countDown()
                        try { client.getInputStream().read() } catch (_: IOException) { }
                        finally { stopped.countDown() }
                    }
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    owner.close()
                    assertTrue(stopped.await(2, TimeUnit.SECONDS))
                    reader.join(2_000)
                }
            }
        }
    }

    @Test fun stageFailureDoesNotExposeSensitiveCauseInItsDisplayedMessage() {
        val cause = IOException("sample-private-material-123456")
        for (stage in PairingStage.entries) {
            val error = AdbPairingException(stage, cause)
            assertEquals(stage, error.stage)
            assertEquals(stage.failureMessage, error.message)
            assertSame(cause, error.cause)
            assertFalse(error.message.orEmpty().contains("sample-private-material"))
            assertFalse(error.message.orEmpty().contains("123456"))
        }
    }
}
