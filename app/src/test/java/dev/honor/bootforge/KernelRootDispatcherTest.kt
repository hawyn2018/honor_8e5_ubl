package dev.honor.bootforge

import android.app.Application
import dev.honor.bootforge.core.RootProcessProvider
import dev.honor.bootforge.wireless.ShellAccess
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

class KernelRootDispatcherTest {
    private val existing = KernelIdentity(KERNEL_61258, "boot-a")
    private val additional = KernelIdentity(NEW_ROOT_KERNEL_G459A, "boot-a")

    private fun <T> complete(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return checkNotNull(outcome) { "This test callback must complete synchronously" }.getOrThrow()
    }

    private class Target(val name: String)

    private fun session(builtin: Target, extra: Target, resolutions: MutableList<KernelIdentity>) =
        KernelDispatchSession<Target> { identity ->
            resolutions += identity
            when (kernelRootRoute(identity.release)) {
                KernelRootRoute.BUILTIN -> builtin
                KernelRootRoute.EXTRA -> extra
                null -> error("No exact kernel route")
            }
        }

    @Test fun bothExistingFullReleasesKeepTheOriginalRoute() {
        assertEquals("61258", rootProfileForKernel(KERNEL_61258))
        assertEquals("6138", rootProfileForKernel(KERNEL_6138))
        assertEquals(KernelRootRoute.BUILTIN, kernelRootRoute(KERNEL_61258))
        assertEquals(KernelRootRoute.BUILTIN, kernelRootRoute(KERNEL_6138))
        assertEquals(KernelRootRoute.EXTRA, kernelRootRoute(NEW_ROOT_KERNEL_G459A))
    }

    @Test fun unknownBuildsNeverMatchByKernelPrefixOrPartialHash() {
        for (release in listOf("6.12.58", "6.12.58-android16-6", KERNEL_61258 + "-other",
            NEW_ROOT_KERNEL_G459A.replace("532718632", "532718633"),
            NEW_ROOT_KERNEL_G459A.replace("g459a4285ccf8", "g459a4285ccf9"),
            "6.12.38-android16-5-unknown", "")) {
            assertNull(release, kernelRootRoute(release))
        }
    }

    @Test fun malformedIdentityCannotBePinned() {
        for (release in listOf("", " ", " $KERNEL_61258", "$KERNEL_61258\nsecond", "$KERNEL_61258\rsecond")) {
            assertThrows(IllegalArgumentException::class.java) { KernelIdentity(release, "boot-a") }
        }
        for (boot in listOf("", " ", " boot-a", "boot-a\nboot-b")) {
            assertThrows(IllegalArgumentException::class.java) { KernelIdentity(KERNEL_61258, boot) }
        }
    }

    @Test fun deployPinsTheOwnerAndAcquisitionReturnsTheRealProviderUnchanged() {
        val builtin = Target("original"); val extra = Target("new"); val resolutions = mutableListOf<KernelIdentity>()
        val session = session(builtin, extra, resolutions)
        val binary = byteArrayOf(0, -1, 13, 10, 1)
        val process = FixtureProcess(binary, byteArrayOf(69, 82, 82), 23)
        val commands = mutableListOf<String>()
        val provider = RootProcessProvider { command -> commands += command; process }
        val events = mutableListOf<String>()
        complete { session.deploy(existing) { events += "deploy:${it.name}" } }
        val returned = complete { session.acquire(existing) { events += "acquire:${it.name}"; provider } }
        assertSame(provider, returned)
        val command = "printf 'literal command with spaces'\nsecond-line"
        val returnedProcess = returned.start(command)
        assertSame(process, returnedProcess)
        assertEquals(listOf(command), commands)
        assertArrayEquals(binary, returnedProcess.inputStream.readBytes())
        assertArrayEquals(byteArrayOf(69, 82, 82), returnedProcess.errorStream.readBytes())
        assertEquals(23, returnedProcess.waitFor())
        assertEquals(listOf(existing), resolutions)
        assertEquals(listOf("deploy:original", "acquire:original"), events)
    }

    @Test fun newKernelUsesOnlyTheExtraDelegate() {
        val builtin = Target("original"); val extra = Target("new"); val resolutions = mutableListOf<KernelIdentity>()
        val session = session(builtin, extra, resolutions)
        val used = mutableListOf<Target>()
        complete { session.deploy(additional) { used += it } }
        complete { session.acquire(additional) { used += it } }
        complete { session.clean(additional) { used += it } }
        assertEquals(3, used.size)
        assertTrue(used.all { it === extra })
        assertFalse(used.contains(builtin))
        assertEquals(listOf(additional), resolutions)
    }

    @Test fun failedDeploymentRetainsItsDelegateForCleanup() {
        val builtin = Target("original"); val extra = Target("new"); val resolutions = mutableListOf<KernelIdentity>()
        val session = session(builtin, extra, resolutions)
        val used = mutableListOf<Target>()
        assertThrows(IllegalStateException::class.java) {
            complete { session.deploy(additional) { used += it; error("Not integrated") } }
        }
        complete { session.clean(additional) { used += it } }
        assertEquals(listOf(extra, extra), used)
        assertEquals(listOf(additional), resolutions)
        complete { session.acquire(existing) { assertSame(builtin, it) } }
        assertEquals(listOf(additional, existing), resolutions)
    }

    @Test fun failedAcquisitionKeepsTheOwnerAndAllowsSameBootRetry() {
        val builtin = Target("original"); val extra = Target("new"); val resolutions = mutableListOf<KernelIdentity>()
        val session = session(builtin, extra, resolutions)
        complete { session.deploy(existing) {} }
        assertThrows(IllegalStateException::class.java) {
            complete { session.acquire(existing) { assertSame(builtin, it); error("Backend failure") } }
        }
        val token = Any()
        assertSame(token, complete { session.acquire(existing) { assertSame(builtin, it); token } })
        assertEquals(1, resolutions.size)
    }

    @Test fun failedCleanupRetainsTheOwnerUntilCleanupActuallySucceeds() {
        val builtin = Target("original"); val extra = Target("new"); val resolutions = mutableListOf<KernelIdentity>()
        val session = session(builtin, extra, resolutions)
        complete { session.deploy(existing) {} }
        assertThrows(IllegalStateException::class.java) {
            complete { session.clean(existing) { assertSame(builtin, it); error("Cleanup failure") } }
        }
        assertThrows(IllegalStateException::class.java) { complete { session.acquire(additional) { fail("Wrong owner") } } }
        assertEquals(1, resolutions.size)
        complete { session.clean(existing) { assertSame(builtin, it) } }
        complete { session.deploy(additional) { assertSame(extra, it) } }
        assertEquals(listOf(existing, additional), resolutions)
    }

    @Test fun changedKernelStopsBeforeDeploymentAcquisitionOrCleanupCallbacks() {
        val resolutions = mutableListOf<KernelIdentity>()
        val session = session(Target("original"), Target("new"), resolutions)
        complete { session.deploy(existing) {} }
        assertThrows(IllegalStateException::class.java) { complete { session.deploy(additional) { fail("Wrong kernel") } } }
        assertThrows(IllegalStateException::class.java) { complete { session.acquire(additional) { fail("Wrong kernel") } } }
        assertThrows(IllegalStateException::class.java) { complete { session.clean(additional) { fail("Wrong kernel") } } }
        assertEquals(listOf(existing), resolutions)
    }

    @Test fun changedBootStopsEvenWhenKernelReleaseIsIdentical() {
        val resolutions = mutableListOf<KernelIdentity>()
        val session = session(Target("original"), Target("new"), resolutions)
        complete { session.deploy(existing) {} }
        val rebooted = existing.copy(bootId = "boot-b")
        assertThrows(IllegalStateException::class.java) { complete { session.acquire(rebooted) { fail("Wrong boot") } } }
        assertThrows(IllegalStateException::class.java) { complete { session.clean(rebooted) { fail("Wrong boot") } } }
        assertEquals(listOf(existing), resolutions)
    }

    @Test fun cleanupBeforeDeploymentUsesTheExactKernelRoute() {
        val builtin = Target("original"); val extra = Target("new"); val resolutions = mutableListOf<KernelIdentity>()
        val session = session(builtin, extra, resolutions)
        complete { session.clean(additional) { assertSame(extra, it) } }
        complete { session.deploy(existing) { assertSame(builtin, it) } }
        assertEquals(listOf(additional, existing), resolutions)
    }

    @Test fun unknownKernelNeverInvokesAnAdapterAndDoesNotPoisonNextResolution() {
        val builtin = Target("original"); val extra = Target("new"); val resolutions = mutableListOf<KernelIdentity>()
        val session = session(builtin, extra, resolutions)
        val unknown = KernelIdentity("6.12.58-unknown-build", "boot-a")
        assertThrows(IllegalStateException::class.java) { complete { session.deploy(unknown) { fail("Unknown must stop") } } }
        complete { session.deploy(existing) { assertSame(builtin, it) } }
        assertEquals(listOf(unknown, existing), resolutions)
    }

    @Test fun overlappingOperationIsRejectedWithoutChangingSessionOwnership() {
        val builtin = Target("original"); val extra = Target("new"); val resolutions = mutableListOf<KernelIdentity>()
        val session = session(builtin, extra, resolutions)
        var suspended: Continuation<Unit>? = null
        var completed: Result<Unit>? = null
        val deploy: suspend () -> Unit = { session.deploy(existing) { suspendCoroutine<Unit> { suspended = it } } }
        deploy.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { completed = result }
        })
        assertNull(completed)
        assertNotNull(suspended)
        assertThrows(IllegalStateException::class.java) { complete { session.clean(existing) { fail("Busy") } } }
        assertThrows(IllegalStateException::class.java) { complete { session.acquire(existing) { fail("Busy") } } }
        checkNotNull(suspended).resumeWith(Result.success(Unit))
        checkNotNull(completed).getOrThrow()
        complete { session.acquire(existing) { assertSame(builtin, it) } }
        assertEquals(listOf(existing), resolutions)
    }

    @Test fun registeringAndForwardingCallbacksNeverDeploysEitherImplementation() {
        for (chain in RootChain.entries) {
            val builtin = CallbackAdapter(); val extra = CallbackAdapter()
            val dispatch = KernelDispatchRootAdapter(chain, builtin, extra)
            val messages = mutableListOf<String>()
            val callback: (String) -> Unit = { messages += it }
            dispatch.setLogCallback(callback)
            assertSame(callback, builtin.callback)
            assertSame(callback, extra.callback)
            builtin.callback?.invoke("old"); extra.callback?.invoke("new")
            assertEquals(listOf("old", "new"), messages)
            dispatch.setLogCallback(null)
            assertNull(builtin.callback)
            assertNull(extra.callback)
            assertEquals(0, builtin.operations)
            assertEquals(0, extra.operations)
        }
    }

    @Test fun callbackCleanupStillVisitsExtraWhenBuiltinThrows() {
        val builtin = CallbackAdapter(); val extra = CallbackAdapter()
        val dispatch = KernelDispatchRootAdapter(RootChain.DEFAULT, builtin, extra)
        dispatch.setLogCallback { }
        builtin.failNullCallback = true
        assertThrows(IllegalStateException::class.java) { dispatch.setLogCallback(null) }
        assertNull(builtin.callback)
        assertNull(extra.callback)
    }

    private class CallbackAdapter : RootAdapter {
        var callback: ((String) -> Unit)? = null
        var failNullCallback = false
        var operations = 0
        override fun setLogCallback(callback: ((String) -> Unit)?) {
            this.callback = callback
            if (callback == null && failNullCallback) error("Callback failure")
        }
        override suspend fun deploy(shell: ShellAccess, application: Application) { operations++; error("Unexpected deployment") }
        override suspend fun acquireRoot(shell: ShellAccess, application: Application): RootProcessProvider { operations++; error("Unexpected acquisition") }
        override suspend fun clean(shell: ShellAccess, application: Application) { operations++; error("Unexpected cleanup") }
    }

    private class FixtureProcess(stdout: ByteArray, stderr: ByteArray, private val code: Int) : Process() {
        private val output = ByteArrayOutputStream()
        private val input = ByteArrayInputStream(stdout)
        private val error = ByteArrayInputStream(stderr)
        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = error
        override fun waitFor(): Int = code
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = code
        override fun destroy() {}
        override fun destroyForcibly(): Process = this
        override fun isAlive(): Boolean = false
    }
}
