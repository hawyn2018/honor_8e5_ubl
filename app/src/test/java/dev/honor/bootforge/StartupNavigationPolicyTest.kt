package dev.honor.bootforge

import org.junit.Assert.*
import org.junit.Test

class StartupNavigationPolicyTest {
    @Test fun externalRootGrantOpensOtaOnOrdinaryLaunch() {
        assertEquals(StartupDestination.OTA, StartupNavigationPolicy.destination(externalRootGranted = true))
    }

    @Test fun launchWithoutExternalRootGrantOpensPrivilegePage() {
        assertEquals(StartupDestination.PRIVILEGE, StartupNavigationPolicy.destination(externalRootGranted = false))
    }

    @Test fun explicitRootEntryWinsOverEveryExternalGrantState() {
        for (granted in listOf(false, true)) {
            assertEquals(StartupDestination.PRIVILEGE,
                StartupNavigationPolicy.destination(externalRootGranted = granted, explicitRoot = true))
        }
    }

}
