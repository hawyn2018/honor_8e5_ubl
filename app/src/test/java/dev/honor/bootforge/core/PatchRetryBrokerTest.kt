package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PatchRetryBrokerTest {
    @Test fun pendingRecordIsStableUntilAnExplicitAnswer() {
        val broker = PatchRetryBroker()
        val ticket = broker.open(PatchRetryTask.UNLOCK, IOException("selected missing"))
        assertSame(ticket.request, broker.pending())
        assertSame(ticket.request, broker.pending())
        assertEquals("selected missing", ticket.request.failureMessage)
        assertEquals(PatchRetryTask.UNLOCK, ticket.request.task)
        assertTrue(broker.respond(ticket.request.id, true))
        assertTrue(ticket.await())
        assertNull(broker.pending())
    }

    @Test fun backgroundWaitIsReleasedOnlyAfterAnAnswer() {
        val broker = PatchRetryBroker()
        val ticket = broker.open(PatchRetryTask.OTA, IOException("fixture"))
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        try {
            val waiting = worker.submit<Boolean> { entered.countDown(); ticket.await() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(waiting.isDone)
            assertTrue(broker.respond(ticket.request.id, false))
            assertFalse(waiting.get(5, TimeUnit.SECONDS))
        } finally {
            broker.cancel(PatchRetryTask.OTA)
            worker.shutdownNow()
        }
    }

    @Test fun staleAndDuplicateAnswersNeverResolveAnotherRequest() {
        val broker = PatchRetryBroker()
        val first = broker.open(PatchRetryTask.UNLOCK, IOException("first"))
        assertFalse(broker.respond(first.request.id + 10, true))
        assertSame(first.request, broker.pending())
        assertTrue(broker.respond(first.request.id, false))
        assertFalse(first.await())
        assertFalse(broker.respond(first.request.id, true))

        val second = broker.open(PatchRetryTask.UNLOCK, IOException("second"))
        assertTrue(second.request.id > first.request.id)
        assertFalse(broker.respond(first.request.id, true))
        assertSame(second.request, broker.pending())
        assertTrue(broker.respond(second.request.id, true))
        assertTrue(second.await())
    }

    @Test fun cancellingAnotherTaskLeavesTheActivePromptPending() {
        val broker = PatchRetryBroker()
        val ticket = broker.open(PatchRetryTask.UNLOCK, IOException("fixture"))
        assertFalse(broker.cancel(PatchRetryTask.OTA))
        assertSame(ticket.request, broker.pending())
        broker.respond(ticket.request.id, true)
        assertTrue(ticket.await())
    }

    @Test fun serviceCancellationUnblocksWaitAndIsNotAUserNo() {
        val broker = PatchRetryBroker()
        val ticket = broker.open(PatchRetryTask.OTA, IOException("fixture"))
        assertTrue(broker.cancel(PatchRetryTask.OTA))
        assertNull(broker.pending())
        assertThrows(CancellationException::class.java) { ticket.await() }
        assertFalse(broker.respond(ticket.request.id, true))
    }

    @Test fun completedWaiterCleanupCannotCancelANewerPrompt() {
        val broker = PatchRetryBroker()
        val first = broker.open(PatchRetryTask.UNLOCK, IOException("first"))
        broker.respond(first.request.id, true)
        val second = broker.open(PatchRetryTask.UNLOCK, IOException("second"))
        assertFalse(broker.cancelRequest(first.request.id))
        assertSame(second.request, broker.pending())
        assertTrue(first.await())
        assertTrue(broker.cancelRequest(second.request.id))
        assertThrows(CancellationException::class.java) { second.await() }
    }

    @Test fun overlappingPromptIsRejectedWithoutReplacingTheFirst() {
        val broker = PatchRetryBroker()
        val ticket = broker.open(PatchRetryTask.OTA, IOException("first"))
        assertThrows(IllegalStateException::class.java) {
            broker.open(PatchRetryTask.UNLOCK, IOException("second"))
        }
        assertSame(ticket.request, broker.pending())
        broker.respond(ticket.request.id, false)
        assertFalse(ticket.await())
    }
}
