package dev.honor.bootforge.wireless

import org.junit.Assert.*
import org.junit.Test

class AdbDiscoveryPortsTest {
    private val ports = AdbDiscoveryPorts().apply { reset(1) }
    private fun found(key: String, kind: String = "pairing", session: Long = 1) =
        checkNotNull(ports.found(kind, "$kind:$key", session))

    @Test fun lostSelectedEndpointPublishesInvalidation() {
        val service = found("first")
        assertEquals(AdbDiscoveryPorts.Change("pairing", 12345), ports.resolved(service, 12345, true))
        assertEquals(AdbDiscoveryPorts.Change("pairing", null), ports.lost(service.key, 1))
        assertFalse(ports.isCurrent(service))
        assertNull(ports.resolved(service, 12345, true))
    }

    @Test fun lateOldResolutionDoesNotReplaceNewPairingWindow() {
        val old = found("old")
        val fresh = found("fresh")
        assertEquals(AdbDiscoveryPorts.Change("pairing", 33333), ports.resolved(fresh, 33333, true))
        assertNull(ports.resolved(old, 11111, true))
        assertNull(ports.lost(old.key, 1))
        assertEquals(AdbDiscoveryPorts.Change("pairing", null), ports.lost(fresh.key, 1))
    }

    @Test fun unrelatedLostAndRemoteServicesNeverClearLocalEndpoint() {
        val local = found("local")
        val remote = found("other-phone")
        assertEquals(AdbDiscoveryPorts.Change("pairing", 44444), ports.resolved(local, 44444, true))
        assertNull(ports.resolved(remote, 55555, false))
        assertNull(ports.lost(remote.key, 1))
        assertNull(ports.lost("pairing:unknown", 1))
    }

    @Test fun oldSessionCallbacksAreIgnoredAfterRestart() {
        val old = found("same")
        ports.reset(2)
        assertNull(ports.found("pairing", old.key, 1))
        val fresh = found("same", session = 2)
        assertNull(ports.resolved(old, 11111, true))
        assertNull(ports.lost(old.key, 1))
        assertEquals(AdbDiscoveryPorts.Change("pairing", 22222), ports.resolved(fresh, 22222, true))
    }

    @Test fun lostThenRefoundSameNameRejectsOldInFlightCallback() {
        val old = found("same")
        assertNull(ports.lost(old.key, 1))
        val fresh = found("same")
        assertNotEquals(old, fresh)
        assertNull(ports.resolved(old, 11111, true))
        assertEquals(AdbDiscoveryPorts.Change("pairing", 22222), ports.resolved(fresh, 22222, true))
    }

    @Test fun lossSelectsRemainingNewestValidEndpoint() {
        val first = found("first", "connect")
        val second = found("second", "connect")
        ports.resolved(first, 11111, true)
        ports.resolved(second, 22222, true)
        assertEquals(AdbDiscoveryPorts.Change("connect", 11111), ports.lost(second.key, 1))
    }

    @Test fun duplicateFindAndResolveDoNotGenerateExtraPortEvents() {
        val service = found("first")
        assertNull(ports.found("pairing", service.key, 1))
        assertEquals(AdbDiscoveryPorts.Change("pairing", 12345), ports.resolved(service, 12345, true))
        assertNull(ports.resolved(service, 12345, true))
    }

    @Test fun pairingAndConnectSelectionsStayIndependent() {
        val pairing = found("first")
        val connect = found("first", "connect")
        ports.resolved(pairing, 12345, true)
        assertEquals(AdbDiscoveryPorts.Change("connect", 54321), ports.resolved(connect, 54321, true))
        assertEquals(AdbDiscoveryPorts.Change("pairing", null), ports.lost(pairing.key, 1))
        assertTrue(ports.isCurrent(connect))
    }

    @Test fun invalidPortsNeverBecomeEndpoints() {
        val invalid = found("invalid")
        assertNull(ports.resolved(invalid, 0, true))
        assertNull(ports.resolved(invalid, 65536, true))
        assertEquals(AdbDiscoveryPorts.Change("pairing", 65535), ports.resolved(invalid, 65535, true))
    }

    @Test fun bothLocalConnectPortsRemainAvailableWhenNewestPortIsNotReady() {
        val first = found("first", "connect")
        val second = found("second", "connect")
        ports.resolved(first, 37053, true)
        ports.resolved(second, 33607, true)

        // The selected badge must not hide an older service that still accepts ADB.
        assertEquals(listOf(33607, 37053), ports.candidates("connect"))
        assertTrue(ports.candidates("pairing").isEmpty())
    }

    @Test fun candidatesIgnoreRemoteServicesAndDuplicatePorts() {
        val first = found("first", "connect")
        val duplicate = found("same-port", "connect")
        val remote = found("other-phone", "connect")
        ports.resolved(first, 37053, true)
        ports.resolved(duplicate, 37053, true)
        ports.resolved(remote, 33607, false)

        assertEquals(listOf(37053), ports.candidates("connect"))
        ports.lost(duplicate.key, 1)
        assertEquals(listOf(37053), ports.candidates("connect"))
    }

    @Test fun failedResolutionReleasesNameAndRejectsItsLateCallback() {
        val failed = found("same", "connect")
        assertNull(ports.failed(failed))
        assertFalse(ports.isCurrent(failed))
        val fresh = found("same", "connect")
        assertNotEquals(failed, fresh)

        assertNull(ports.resolved(failed, 37053, true))
        assertNull(ports.unavailable(failed))
        assertNull(ports.failed(failed))
        assertTrue(ports.isCurrent(fresh))
        assertEquals(AdbDiscoveryPorts.Change("connect", 33607), ports.resolved(fresh, 33607, true))
        assertEquals(listOf(33607), ports.candidates("connect"))
    }

    @Test fun failedSelectedServiceFallsBackToRemainingCandidate() {
        val first = found("first", "connect")
        val second = found("second", "connect")
        ports.resolved(first, 37053, true)
        ports.resolved(second, 33607, true)

        assertEquals(AdbDiscoveryPorts.Change("connect", 37053), ports.failed(second))
        assertEquals(listOf(37053), ports.candidates("connect"))
        assertEquals(AdbDiscoveryPorts.Change("connect", null), ports.failed(first))
        assertTrue(ports.candidates("connect").isEmpty())
    }

    @Test fun subscriptionMayLoseEndpointThenPublishItAgainWithoutNewDiscovery() {
        val service = found("continuous", "connect")
        ports.resolved(service, 37053, true)

        assertEquals(AdbDiscoveryPorts.Change("connect", null), ports.unavailable(service))
        assertTrue(ports.isCurrent(service))
        assertTrue(ports.candidates("connect").isEmpty())
        assertNull(ports.unavailable(service))
        assertEquals(AdbDiscoveryPorts.Change("connect", 33607), ports.resolved(service, 33607, true))
        assertEquals(listOf(33607), ports.candidates("connect"))
    }

    @Test fun unavailableNewestSubscriptionTemporarilyFallsBackThenRecovers() {
        val first = found("first", "connect")
        val second = found("second", "connect")
        ports.resolved(first, 37053, true)
        ports.resolved(second, 33607, true)

        assertEquals(AdbDiscoveryPorts.Change("connect", 37053), ports.unavailable(second))
        assertEquals(listOf(37053), ports.candidates("connect"))
        assertEquals(AdbDiscoveryPorts.Change("connect", 33607), ports.resolved(second, 33607, true))
        assertEquals(listOf(33607, 37053), ports.candidates("connect"))
    }

    @Test fun previousSessionFailuresCannotClearFreshEndpoint() {
        val old = found("same", "connect")
        ports.resolved(old, 37053, true)
        ports.reset(2)
        assertTrue(ports.candidates("connect").isEmpty())
        val fresh = found("same", "connect", 2)
        ports.resolved(fresh, 33607, true)

        assertNull(ports.failed(old))
        assertNull(ports.unavailable(old))
        assertNull(ports.resolved(old, 37053, true))
        assertEquals(listOf(33607), ports.candidates("connect"))
        assertTrue(ports.isCurrent(fresh))
    }
}
