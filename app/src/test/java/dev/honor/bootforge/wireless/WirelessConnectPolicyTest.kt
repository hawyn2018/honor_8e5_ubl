package dev.honor.bootforge.wireless

import org.junit.Assert.*
import org.junit.Test

class WirelessConnectPolicyTest {
    @Test fun bothAdvertisedPortsAreTriedInsteadOfRepeatingTheNewestOne() {
        val policy = WirelessConnectPolicy(0)
        val endpoints = listOf(37053, 33607)

        assertEquals(37053, policy.select(endpoints))
        assertEquals(33607, policy.select(endpoints))
        assertEquals(37053, policy.select(endpoints))
        assertEquals(33607, policy.select(endpoints))
    }

    @Test fun discoveryReorderingDoesNotStarveTheUntriedEndpoint() {
        val policy = WirelessConnectPolicy(0)
        assertEquals(33607, policy.select(listOf(33607, 37053)))

        assertEquals(37053, policy.select(listOf(37053, 33607)))
        assertEquals(33607, policy.select(listOf(37053, 33607)))
    }

    @Test fun fourQuickRefusalsLeaveTimeToUseANewlyDiscoveredPort() {
        val policy = WirelessConnectPolicy(0)
        repeat(4) { assertEquals(33607, policy.select(listOf(33607))) }

        // Four fast failures used to exhaust the whole connection task in ~5s.
        assertEquals(40_000L, policy.remaining(5_000))
        assertEquals(37053, policy.select(listOf(33607, 37053)))
    }

    @Test fun connectionPortMayArriveTwentySecondsAfterPairing() {
        val policy = WirelessConnectPolicy(1_000)
        val none = WirelessConnectPolicy.candidates("33607", false, emptyList(), true)
        assertTrue(none.isEmpty())
        assertNull(policy.select(none))
        assertEquals(25_000L, policy.remaining(21_000))

        val discovered = WirelessConnectPolicy.candidates("33607", false, listOf(37053), true)
        assertEquals(37053, policy.select(discovered))
    }

    @Test fun justPairedSessionNeverUsesAnOldAutomaticHint() {
        assertTrue(WirelessConnectPolicy.candidates("33607", false, emptyList(), true).isEmpty())
        assertEquals(listOf(37053), WirelessConnectPolicy.candidates("33607", false, listOf(37053), true))
        assertEquals(listOf(33607), WirelessConnectPolicy.candidates("33607", false, emptyList(), false))
    }

    @Test fun manuallyEnteredPortIsUsedAloneEvenWhenOtherServicesAreDiscovered() {
        assertEquals(listOf(37053), WirelessConnectPolicy.candidates("37053", true, listOf(33607), true))
        assertEquals(listOf(37053), WirelessConnectPolicy.candidates("37053", true, listOf(33607), false))
        for (invalid in listOf("", "0", "65536", "bad")) {
            assertTrue(WirelessConnectPolicy.candidates(invalid, true, listOf(33607), true).isEmpty())
        }
    }

    @Test fun refreshIsRateLimitedAcrossRepeatedPollingAndAttempts() {
        val policy = WirelessConnectPolicy(1_000)
        assertFalse(policy.refreshDue(1_000))
        assertFalse(policy.refreshDue(8_999))
        assertTrue(policy.refreshDue(9_000))
        repeat(10) {
            policy.select(listOf(37053, 33607))
            assertFalse(policy.refreshDue(9_000))
        }
        assertFalse(policy.refreshDue(16_999))
        assertTrue(policy.refreshDue(17_000))
    }

    @Test fun expiredConnectionTaskDoesNotScheduleMoreRefreshes() {
        val policy = WirelessConnectPolicy(1_000)
        assertEquals(1L, policy.remaining(45_999))
        assertEquals(0L, policy.remaining(46_000))
        assertEquals(0L, policy.remaining(90_000))
        assertFalse(policy.refreshDue(46_000))
        assertFalse(policy.refreshDue(90_000))
    }

    @Test fun aNewEndpointIsPrioritizedWhenThePreviouslyTriedOneDisappearsAndReturns() {
        val policy = WirelessConnectPolicy(0)
        assertEquals(33607, policy.select(listOf(33607)))
        assertNull(policy.select(emptyList()))

        assertEquals(37053, policy.select(listOf(33607, 37053)))
        assertEquals(33607, policy.select(listOf(33607, 37053)))
    }

    @Test fun invalidAndDuplicateEndpointsNeverConsumeRotationSlots() {
        val policy = WirelessConnectPolicy(0)
        val endpoints = listOf(0, 37053, 37053, 65536, 33607, -1)
        assertEquals(37053, policy.select(endpoints))
        assertEquals(33607, policy.select(endpoints))
        assertEquals(listOf(37053, 33607),
            WirelessConnectPolicy.candidates("", false, endpoints, true))
    }
}
