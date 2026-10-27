package dev.honor.bootforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAdapterSupportTest {
    @Test fun everyChainSupportsBothKernelProfiles() {
        for (chain in RootChain.entries) {
            assertEquals("61258", rootProfileForKernel(KERNEL_61258))
            assertEquals("6138", rootProfileForKernel(KERNEL_6138))
            assertEquals(ROOT_FINISH_NONE, rootFinishKind(chain, ""))
        }
    }

    @Test fun unknownKernelOrModelIsNotAProfile() {
        assertNull(rootProfileForKernel("6.12.38-other-build"))
        assertNull(rootProfileForKernel("BKQ-AN10"))
        assertNull(rootProfileForKernel(""))
    }

    @Test fun terminalMarkersAreChainSpecific() {
        assertEquals(ROOT_FINISH_DONE, rootFinishKind(RootChain.CVE_2026_64650, "MODULE_LOADED\nDONE"))
        assertEquals(ROOT_FINISH_NONE, rootFinishKind(RootChain.CVE_2026_98260, "DONE"))
        assertEquals(ROOT_FINISH_DONE, rootFinishKind(RootChain.CVE_2026_98260, "98260_SUCCESS=1\n98260_CHAIN_END=OK"))
        assertEquals(ROOT_FINISH_NONE, rootFinishKind(RootChain.CVE_2026_64650, "98260_CHAIN_END=OK"))
        assertEquals(ROOT_FINISH_FAIL, rootFinishKind(RootChain.CVE_2026_64650, "FAIL=phase2"))
        assertEquals(ROOT_FINISH_FAIL, rootFinishKind(RootChain.CVE_2026_98260, "98260_CHAIN_END=FAIL code=80"))
        assertEquals(ROOT_FINISH_NONE, rootFinishKind(RootChain.CVE_2026_98260, "98260_CHAIN_END=PREFLIGHT code=0"))
    }

    @Test fun proofAndParkedFailuresAreClassifiedForLogging() {
        assertTrue(rootIsProofOrParked("98260_PARKED=1"))
        assertTrue(rootIsProofOrParked("stage_already_started"))
        assertTrue(rootIsProofOrParked("98260_BOOT_CHANGED"))
        assertTrue(rootIsProofOrParked("FAIL=proof no_rootd_socket_after_600s"))
        assertTrue(rootIsProofOrParked("98260_CHAIN_END=FAIL code=80"))
        assertFalse(rootIsProofOrParked("FAIL=kernel_unsupported code=71"))
        assertFalse(rootIsProofOrParked("98260_CHAIN_END=OK"))
    }

    @Test fun switchingChainRequiresANewBoot() {
        assertTrue(rootSwitchBlocked("boot-a 64650", "boot-a", RootChain.CVE_2026_98260))
        assertFalse(rootSwitchBlocked("boot-a 64650", "boot-a", RootChain.CVE_2026_64650))
        assertFalse(rootSwitchBlocked("boot-a 64650", "boot-b", RootChain.CVE_2026_98260))
        assertFalse(rootSwitchBlocked("", "boot-a", RootChain.CVE_2026_98260))
        assertFalse(rootSwitchBlocked("boot-a 64650", "", RootChain.CVE_2026_98260))
    }

    @Test fun retryLimitsAreBoundedAndCoherent() {
        assertEquals(32, ROOT98260_ATTEMPTS)
        assertEquals(480, ROOT98260_BUDGET_S)
        assertEquals(600, ROOT98260_SOCKET_WAIT_S)
        assertEquals(15, ROOT98260_ZOMBIE_STOP_S)
        assertTrue(ROOT98260_ATTEMPTS in 13..64)
        assertTrue(ROOT98260_BUDGET_S in 360..480)
        assertTrue(ROOT98260_SOCKET_WAIT_S > ROOT98260_BUDGET_S)
    }
}
