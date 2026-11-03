package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LicenseConsentPolicyTest {
    @Test fun firstRunAndOldLicenseRequireNewAcknowledgment() {
        for (stored in listOf(null, "", "GPL-3.0", "AGPL-2.0", "agpl-3.0", " AGPL-3.0")) {
            assertFalse(LicenseConsentPolicy({ stored }) { true }.accepted)
        }
    }

    @Test fun currentLicenseIsReadWithoutWritingOnEveryLaunch() {
        var writes = 0
        val policy = LicenseConsentPolicy({ "AGPL-3.0" }) { writes++; false }
        assertTrue(policy.accepted)
        assertTrue(policy.accept())
        assertEquals(0, writes)
    }

    @Test fun persistExactLicenseBeforeOpeningAndRetainAcrossNewInstances() {
        var stored: String? = null
        lateinit var policy: LicenseConsentPolicy
        policy = LicenseConsentPolicy({ stored }) { id ->
            assertFalse(policy.accepted)
            assertEquals("AGPL-3.0", id)
            stored = id
            true
        }
        assertFalse(policy.accepted)
        assertTrue(policy.accept())
        assertTrue(policy.accepted)
        assertTrue(LicenseConsentPolicy({ stored }) { false }.accepted)
    }

    @Test fun failedWriteNeverOpensEvenWhenPreferencesChangedInMemory() {
        var stored: String? = null
        val policy = LicenseConsentPolicy({ stored }) { stored = it; false }
        assertFalse(policy.accept())
        assertNull(stored)
        assertFalse(policy.accepted)
        assertFalse(LicenseConsentPolicy({ stored }) { false }.accepted)
    }

    @Test fun failedWriteRestoresPreviousLicenseInsteadOfClearingIt() {
        var stored: String? = "GPL-3.0"
        val policy = LicenseConsentPolicy({ stored }) { stored = it; false }
        assertFalse(policy.accept())
        assertEquals("GPL-3.0", stored)
        assertFalse(policy.accepted)
    }

    @Test fun persistenceExceptionDoesNotGrantAndCanBeRetried() {
        var attempts = 0
        val policy = LicenseConsentPolicy({ null }) {
            if (++attempts == 1) throw IOException("storage unavailable")
            true
        }
        assertFalse(policy.accept())
        assertFalse(policy.accepted)
        assertTrue(policy.accept())
        assertTrue(policy.accepted)
        assertEquals(3, attempts)
    }

    @Test fun invalidPreferenceTypeOrReadFailureRequiresAcknowledgment() {
        val policy = LicenseConsentPolicy({ throw ClassCastException("invalid preference type") }) { true }
        assertFalse(policy.accepted)
        assertTrue(policy.accept())
    }

    @Test fun acceptanceDoesNotBecomeVisibleUntilDiskCommitReturns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val policy = LicenseConsentPolicy({ null }) {
            entered.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            true
        }
        val worker = Thread { policy.accept() }
        worker.start()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        try { assertFalse(policy.accepted) }
        finally { release.countDown(); worker.join(3_000) }
        assertFalse(worker.isAlive)
        assertTrue(policy.accepted)
    }

    @Test fun duplicateConcurrentAcceptsPersistOnlyOnce() {
        val writes = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val start = CountDownLatch(1)
        val policy = LicenseConsentPolicy({ null }) { writes.incrementAndGet(); true }
        val workers = List(8) { Thread {
            runCatching { start.await(); assertTrue(policy.accept()) }.onFailure { failure.compareAndSet(null, it) }
        } }
        workers.forEach(Thread::start)
        start.countDown()
        workers.forEach { it.join(3_000); assertFalse(it.isAlive) }
        assertTrue(policy.accepted)
        assertEquals(1, writes.get())
        assertNull(failure.get())
    }

    @Test fun clearingApplicationDataRequiresAcknowledgmentAgain() {
        var stored: String? = "AGPL-3.0"
        assertTrue(LicenseConsentPolicy({ stored }) { true }.accepted)
        stored = null
        assertFalse(LicenseConsentPolicy({ stored }) { true }.accepted)
    }
}
