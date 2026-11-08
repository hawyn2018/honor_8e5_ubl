package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test

class KernelSuHandoffTest {
    @Test fun officialManagerTakesPriorityAndStopsTheSearch() {
        val attempted = mutableListOf<String>()
        val result = KernelSuHandoff.open { packageName ->
            attempted += packageName
            true
        }
        assertTrue(result.opened)
        assertEquals(listOf("me.weishu.kernelsu"), attempted)
    }

    @Test fun unavailableManagerDoesNotPreventTryingTheNextInstalledVariant() {
        val attempted = mutableListOf<String>()
        val result = KernelSuHandoff.open { packageName ->
            attempted += packageName
            when (packageName) {
                "me.weishu.kernelsu" -> throw IllegalStateException("no launcher")
                "com.sukisu.ultra" -> true
                else -> false
            }
        }
        assertTrue(result.opened)
        assertEquals(listOf("me.weishu.kernelsu", "com.rifsxd.ksunext", "com.sukisu.ultra"), attempted)
    }

    @Test fun noLaunchableManagerLeavesAUsefulManualFallback() {
        val attempted = mutableListOf<String>()
        val result = KernelSuHandoff.open { packageName ->
            attempted += packageName
            false
        }
        assertFalse(result.opened)
        assertEquals(listOf("me.weishu.kernelsu", "com.rifsxd.ksunext", "com.sukisu.ultra",
            "com.resukisu.resukisu", "me.yuki.fina"), attempted)
        assertTrue(result.logs.any { it.contains("手动打开") })
    }
}
