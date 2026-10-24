package dev.honor.bootforge.wireless

import org.junit.Assert.*
import org.junit.Test

class WirelessPortPolicyTest {
    @Test fun lostAutoPortIsRemoved() {
        assertEquals("", WirelessPortPolicy.discovered("37001", false, null))
    }

    @Test fun userPortSurvivesDiscoveryAndLoss() {
        assertEquals("38001", WirelessPortPolicy.discovered("38001", true, 39001))
        assertEquals("38001", WirelessPortPolicy.discovered("38001", true, null))
    }

    @Test fun freshDiscoveryReplacesCachedPort() {
        assertEquals("39001", WirelessPortPolicy.discovered("37001", false, 39001))
        assertEquals(39001, WirelessPortPolicy.connectionPort("37001", false, 39001, true))
    }

    @Test fun pairingDoesNotConnectUsingOldPreferences() {
        assertNull(WirelessPortPolicy.connectionPort("37001", false, null, true))
        assertEquals(37001, WirelessPortPolicy.connectionPort("37001", false, null, false))
    }

    @Test fun newPairingServiceDoesNotUseAnOldAutomaticPairingPort() {
        assertNull(WirelessPortPolicy.pairingPort("37001", false, null))
        assertEquals(39001, WirelessPortPolicy.pairingPort("37001", false, 39001))
        assertEquals(38001, WirelessPortPolicy.pairingPort("38001", true, 39001))
    }

    @Test fun explicitConnectionPortHasPriority() {
        assertEquals(38001, WirelessPortPolicy.connectionPort("38001", true, 39001, true))
        assertNull(WirelessPortPolicy.connectionPort("", true, 39001, true))
    }

    @Test fun unicodeDigitsBecomeTheSameAsciiCode() {
        assertEquals("012345", WirelessPortPolicy.digits("０１２ ３４５", 6))
        assertEquals("012345", WirelessPortPolicy.digits("٠١٢٣٤٥", 6))
        assertEquals("123456", WirelessPortPolicy.digits("1234567", 6))
    }

    @Test fun invalidPortsAreNotConnectionCandidates() {
        for (value in listOf("", "0", "65536", "-1", "port")) {
            assertNull(WirelessPortPolicy.connectionPort(value, true, 39001, true))
        }
    }
}
