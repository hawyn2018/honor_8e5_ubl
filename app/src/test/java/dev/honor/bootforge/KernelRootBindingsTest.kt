package dev.honor.bootforge

import org.junit.Assert.*
import org.junit.Test

/** Select real registrations without deploying or executing any backend. */
class KernelRootBindingsTest {
    @Test fun bothChainsKeepBothExistingKernelsAndSelectTheNewBackend() {
        val routes = KernelRootBindings.create()
        assertEquals(setOf(RootChain.CVE_2026_98260, RootChain.CVE_2026_64650), routes.keys)
        for ((chain, route) in routes) {
            val builtin = if (chain == RootChain.CVE_2026_98260) Root98260Adapter else Root64650Adapter
            val extra = if (chain == RootChain.CVE_2026_98260) NewKernel98260Adapter else NewKernel64650Adapter
            assertSame(builtin, route.resolveBackend(KernelIdentity(KERNEL_61258, "boot-a")))
            assertSame(builtin, route.resolveBackend(KernelIdentity(KERNEL_6138, "boot-a")))
            assertSame(extra, route.resolveBackend(KernelIdentity(NEW_ROOT_KERNEL_G459A, "boot-a")))
        }
    }

    @Test fun bothChainsRejectAnUnknownBuildInsteadOfFallingBack() {
        val unknown = NEW_ROOT_KERNEL_G459A.replace("532718632", "532718633")
        for ((_, route) in KernelRootBindings.create()) {
            assertThrows(IllegalStateException::class.java) {
                route.resolveBackend(KernelIdentity(unknown, "boot-a"))
            }
        }
    }

    @Test fun registrationConstructionDoesNotPinOrLaunchABackend() {
        for ((_, route) in KernelRootBindings.create()) {
            // The same dispatcher can inspect every candidate before an explicit operation pins it.
            assertNotSame(route.resolveBackend(KernelIdentity(KERNEL_61258, "boot-a")),
                route.resolveBackend(KernelIdentity(NEW_ROOT_KERNEL_G459A, "boot-a")))
            assertSame(route.resolveBackend(KernelIdentity(KERNEL_61258, "boot-a")),
                route.resolveBackend(KernelIdentity(KERNEL_6138, "boot-b")))
        }
    }
}
