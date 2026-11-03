package dev.honor.bootforge.wireless

import dev.honor.bootforge.core.RootProcessProvider
import dev.honor.bootforge.core.ShellResult
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class WirelessShellSessionTest {
    private val first = WirelessEndpoint("192.0.2.10", 37001)
    private val second = WirelessEndpoint("192.0.2.20", 37002)

    @Test fun `an unconnected session has no identity and starts no process`() {
        val fixture = Fixture()
        assertNull(fixture.session.endpoint)
        assertNull(fixture.session.uid)
        assertFalse(fixture.session.hasSession())
        assertTrue(runCatching { fixture.session.start("fixture-task") }.isFailure)
        assertTrue(fixture.launches.isEmpty())
    }

    @Test fun `ordinary adb shell remains usable without becoming a root provider`() {
        val fixture = Fixture().identity(first, "2000\n")
        assertEquals(2000, fixture.session.connect(first))
        assertEquals(first, fixture.session.endpoint)
        assertEquals(2000, fixture.session.uid ?: -1)
        assertTrue(fixture.session.hasSession())
        assertTrue(fixture.registrations.isEmpty())
        assertNull(fixture.activeRoot)

        fixture.session.start("printf shell-fixture")
        assertEquals(listOf(Launch(first, "printf shell-fixture")), fixture.launches)
    }

    @Test fun `root adb is registered as a provider instead of being rejected`() {
        val fixture = Fixture().identity(first, "0\n")
        assertEquals(0, fixture.session.connect(first))
        assertEquals(0, fixture.session.uid ?: -1)
        assertEquals(first, fixture.session.endpoint)
        assertSame(fixture.registrations.single(), fixture.activeRoot)
    }

    @Test fun `root registration can be disabled while the uid zero shell remains usable`() {
        val fixture = Fixture().identity(first, "0\n")
        fixture.registerRootEnabled = false
        assertEquals(0, fixture.session.connect(first))
        assertEquals(0, fixture.session.uid ?: -1)
        assertTrue(fixture.session.hasSession())
        assertNull(fixture.activeRoot)
        assertTrue(fixture.registrations.isEmpty())
        fixture.session.start("plain-shell-fixture")
        assertEquals(listOf(Launch(first, "plain-shell-fixture")), fixture.launches)

        // Disabling the external selection restores the original registration path.
        fixture.registerRootEnabled = true
        assertEquals(0, fixture.session.check())
        val previous = checkNotNull(fixture.activeRoot)
        assertEquals(1, fixture.registrations.size)
        // Re-enabling it revokes the old provider without discarding the Shell session.
        fixture.registerRootEnabled = false
        assertEquals(0, fixture.session.check())
        assertTrue(fixture.session.hasSession())
        assertNull(fixture.activeRoot)
        assertEquals(listOf(previous), fixture.revocations)
        assertEquals(1, fixture.registrations.size)
    }

    @Test fun `identity surrounding whitespace is accepted for both supported uids`() {
        listOf(" \n0\r\n" to 0, "\t2000 \n" to 2000).forEach { (output, expected) ->
            val fixture = Fixture().identity(first, output)
            assertEquals(expected, fixture.session.connect(first))
            assertEquals(expected, fixture.session.uid ?: -1)
        }
    }

    @Test fun `uid-like substrings and other users do not establish a session`() {
        listOf("", "00", "02000", "1000", "10000", "uid=0", "ROOT_UID=0", "banner\n0", "0\n2000").forEach { output ->
            val fixture = Fixture().identity(first, output)
            assertTrue(output, runCatching { fixture.session.connect(first) }.isFailure)
            assertNull(fixture.session.endpoint)
            assertNull(fixture.session.uid)
            assertFalse(fixture.session.hasSession())
            assertTrue(fixture.registrations.isEmpty())
            assertTrue(fixture.launches.isEmpty())
        }
    }

    @Test fun `a failing identity command never grants either supported uid`() {
        listOf("0\n", "2000\n").forEach { output ->
            val fixture = Fixture().identity(first, output, exitCode = 1)
            assertTrue(runCatching { fixture.session.connect(first) }.isFailure)
            assertFalse(fixture.session.hasSession())
            assertTrue(fixture.registrations.isEmpty())
        }
    }

    @Test fun `an initial connection exception leaves no partially connected endpoint`() {
        val fixture = Fixture()
        fixture.identities[first] = { throw IOException("connection refused") }
        assertTrue(runCatching { fixture.session.connect(first) }.isFailure)
        assertNull(fixture.session.endpoint)
        assertNull(fixture.session.uid)
        assertFalse(fixture.session.hasSession())
        assertTrue(fixture.registrations.isEmpty())
    }

    @Test fun `a failed candidate keeps the previous ordinary shell endpoint`() {
        val fixture = Fixture().identity(first, "2000").identity(second, "denied")
        fixture.session.connect(first)
        assertTrue(runCatching { fixture.session.connect(second) }.isFailure)
        assertEquals(first, fixture.session.endpoint)
        assertEquals(2000, fixture.session.uid ?: -1)
        fixture.session.start("still-first")
        assertEquals(listOf(Launch(first, "still-first")), fixture.launches)
    }

    @Test fun `a failed candidate keeps the previous verified root provider`() {
        val fixture = Fixture().identity(first, "0").identity(second, "2000", exitCode = 1)
        fixture.session.connect(first)
        val previous = checkNotNull(fixture.activeRoot)
        assertTrue(runCatching { fixture.session.connect(second) }.isFailure)
        assertEquals(first, fixture.session.endpoint)
        assertEquals(0, fixture.session.uid ?: -1)
        assertSame(previous, fixture.activeRoot)
        assertFalse(fixture.revocations.any { it === previous })
    }

    @Test fun `failed root registration rolls back the candidate rather than replacing a working root`() {
        val fixture = Fixture().identity(first, "0").identity(second, "0")
        fixture.session.connect(first)
        val previous = checkNotNull(fixture.activeRoot)
        fixture.registrationResult = ShellResult(1, "fresh root proof failed")

        assertTrue(runCatching { fixture.session.connect(second) }.isFailure)
        assertEquals(first, fixture.session.endpoint)
        assertEquals(0, fixture.session.uid ?: -1)
        assertSame(previous, fixture.activeRoot)
        assertFalse(fixture.revocations.any { it === previous })
        assertEquals(listOf(first, second), fixture.identityReads)
        assertTrue(fixture.launches.isEmpty())
    }

    @Test fun `an exception during root registration keeps the old connection intact`() {
        val fixture = Fixture().identity(first, "2000").identity(second, "0")
        fixture.session.connect(first)
        fixture.registrationFailure = IOException("fresh root transport failed")

        assertTrue(runCatching { fixture.session.connect(second) }.isFailure)
        assertEquals(first, fixture.session.endpoint)
        assertEquals(2000, fixture.session.uid ?: -1)
        assertNull(fixture.activeRoot)
        fixture.session.start("old-shell-task")
        assertEquals(listOf(Launch(first, "old-shell-task")), fixture.launches)
    }

    @Test fun `reconnecting as uid 2000 revokes this sessions old root provider`() {
        val fixture = Fixture().identity(first, "0").identity(second, "2000")
        fixture.session.connect(first)
        val previous = checkNotNull(fixture.activeRoot)

        assertEquals(2000, fixture.session.connect(second))
        assertEquals(second, fixture.session.endpoint)
        assertEquals(2000, fixture.session.uid ?: -1)
        assertNull(fixture.activeRoot)
        assertTrue(fixture.revocations.any { it === previous })
        fixture.session.start("ordinary-task")
        assertEquals(listOf(Launch(second, "ordinary-task")), fixture.launches)
    }

    @Test fun `an ordinary connection can be replaced by verified root adb`() {
        val fixture = Fixture().identity(first, "2000").identity(second, "0")
        fixture.session.connect(first)
        assertEquals(0, fixture.session.connect(second))
        assertEquals(second, fixture.session.endpoint)
        assertEquals(0, fixture.session.uid ?: -1)
        assertNotNull(fixture.activeRoot)
        fixture.session.start("new-endpoint-task")
        assertEquals(listOf(Launch(second, "new-endpoint-task")), fixture.launches)
    }

    @Test fun `a previously registered root provider remains bound to its own verified endpoint`() {
        val fixture = Fixture().identity(first, "0").identity(second, "0")
        fixture.session.connect(first)
        val firstProvider = checkNotNull(fixture.activeRoot)
        fixture.session.connect(second)
        val secondProvider = checkNotNull(fixture.activeRoot)

        firstProvider.start("printf first-fixture")
        secondProvider.start("printf second-fixture")
        assertEquals(listOf(first, second), fixture.launches.map { it.endpoint })
        assertTrue(fixture.launches[0].command.contains("printf first-fixture"))
        assertTrue(fixture.launches[1].command.contains("printf second-fixture"))
    }

    @Test fun `checking an existing session ignores an unrelated restore endpoint`() {
        val fixture = Fixture().identity(first, "2000").identity(second, "0")
        fixture.session.connect(first)
        assertEquals(2000, fixture.session.check(second))
        assertEquals(first, fixture.session.endpoint)
        assertEquals(listOf(first, first), fixture.identityReads)
        assertTrue(fixture.registrations.isEmpty())
    }

    @Test fun `a fresh check notices root becoming ordinary shell and revokes stale root`() {
        val fixture = Fixture().identity(first, "0")
        fixture.session.connect(first)
        val previous = checkNotNull(fixture.activeRoot)
        fixture.identity(first, "2000")

        assertEquals(2000, fixture.session.check())
        assertEquals(first, fixture.session.endpoint)
        assertEquals(2000, fixture.session.uid ?: -1)
        assertNull(fixture.activeRoot)
        assertTrue(fixture.revocations.any { it === previous })
    }

    @Test fun `a disconnected root check clears the session and its matching root provider`() {
        val fixture = Fixture().identity(first, "0")
        fixture.session.connect(first)
        val previous = checkNotNull(fixture.activeRoot)
        fixture.identities[first] = { throw IOException("wireless adb disconnected") }

        assertTrue(runCatching { fixture.session.check() }.isFailure)
        assertNull(fixture.session.endpoint)
        assertNull(fixture.session.uid)
        assertFalse(fixture.session.hasSession())
        assertNull(fixture.activeRoot)
        assertTrue(fixture.revocations.any { it === previous })
        assertTrue(runCatching { fixture.session.start("must-not-run") }.isFailure)
        assertTrue(fixture.launches.isEmpty())
    }

    @Test fun `invalid identity during recheck clears an ordinary shell session`() {
        val fixture = Fixture().identity(first, "2000")
        fixture.session.connect(first)
        fixture.identity(first, "banner\n2000")
        assertTrue(runCatching { fixture.session.check() }.isFailure)
        assertNull(fixture.session.endpoint)
        assertNull(fixture.session.uid)
        assertFalse(fixture.session.hasSession())
    }

    @Test fun `a nonzero recheck exit clears root even when stdout still says zero`() {
        val fixture = Fixture().identity(first, "0")
        fixture.session.connect(first)
        fixture.identity(first, "0", exitCode = 7)
        assertTrue(runCatching { fixture.session.check() }.isFailure)
        assertFalse(fixture.session.hasSession())
        assertNull(fixture.activeRoot)
    }

    @Test fun `check can restore and verify a saved root adb endpoint`() {
        val fixture = Fixture().identity(second, "0\n")
        assertEquals(0, fixture.session.check(second))
        assertEquals(second, fixture.session.endpoint)
        assertEquals(0, fixture.session.uid ?: -1)
        assertNotNull(fixture.activeRoot)
        assertEquals(listOf(second), fixture.identityReads)
    }

    @Test fun `check without a live or saved endpoint does not guess a connection`() {
        val fixture = Fixture()
        assertTrue(runCatching { fixture.session.check() }.isFailure)
        assertFalse(fixture.session.hasSession())
        assertTrue(fixture.identityReads.isEmpty())
        assertTrue(fixture.launches.isEmpty())
    }

    @Test fun `a rejected saved endpoint does not leave a restorable session marked ready`() {
        val fixture = Fixture().identity(second, "1000")
        assertTrue(runCatching { fixture.session.check(second) }.isFailure)
        assertNull(fixture.session.endpoint)
        assertNull(fixture.session.uid)
        assertFalse(fixture.session.hasSession())
        assertTrue(fixture.registrations.isEmpty())
    }

    @Test fun `a failed process start is propagated once without reconnect or command replay`() {
        val fixture = Fixture().identity(first, "2000")
        fixture.session.connect(first)
        fixture.startFailure = IOException("transport closed")

        assertTrue(runCatching { fixture.session.start("single-fixture-task") }.isFailure)
        assertEquals(listOf(Launch(first, "single-fixture-task")), fixture.launches)
        assertEquals(listOf(first), fixture.identityReads)
        assertTrue(fixture.registrations.isEmpty())
    }

    @Test fun `root provider starts a fresh guarded process for each command without replay`() {
        val fixture = Fixture().identity(first, "0")
        fixture.session.connect(first)
        val provider = checkNotNull(fixture.activeRoot)

        val one = provider.start("printf first-task")
        val two = provider.start("printf second-task")
        assertNotSame(one, two)
        assertEquals(2, fixture.launches.size)
        assertEquals(listOf(first, first), fixture.launches.map { it.endpoint })
        fixture.launches.forEach {
            assertTrue(it.command.contains("id -u"))
            assertTrue(it.command.contains("126"))
        }
        assertTrue(fixture.launches[0].command.contains("printf first-task"))
        assertTrue(fixture.launches[1].command.contains("printf second-task"))
        assertEquals(listOf(first), fixture.identityReads)
    }

    @Test fun `after disconnection a new verified endpoint establishes a new independent provider`() {
        val fixture = Fixture().identity(first, "0").identity(second, "0")
        fixture.session.connect(first)
        val oldProvider = checkNotNull(fixture.activeRoot)
        fixture.identities[first] = { throw IOException("old endpoint gone") }
        assertTrue(runCatching { fixture.session.check() }.isFailure)

        assertEquals(0, fixture.session.connect(second))
        val newProvider = checkNotNull(fixture.activeRoot)
        assertNotSame(oldProvider, newProvider)
        assertEquals(second, fixture.session.endpoint)
        assertEquals(0, fixture.session.uid ?: -1)
        assertFalse(fixture.revocations.any { it === newProvider })
        newProvider.start("after-reconnect")
        assertEquals(second, fixture.launches.single().endpoint)
    }

    private data class Launch(val endpoint: WirelessEndpoint, val command: String)

    private class Fixture {
        val identities = mutableMapOf<WirelessEndpoint, () -> ShellResult>()
        val identityReads = mutableListOf<WirelessEndpoint>()
        val launches = mutableListOf<Launch>()
        val registrations = mutableListOf<RootProcessProvider>()
        val revocations = mutableListOf<RootProcessProvider>()
        var activeRoot: RootProcessProvider? = null
        var registrationResult = ShellResult(0, "ROOT_UID=0\n")
        var registrationFailure: Exception? = null
        var startFailure: Exception? = null
        var registerRootEnabled = true

        val session = WirelessShellSession(
            readIdentity = { endpoint ->
                identityReads += endpoint
                checkNotNull(identities[endpoint]) { "unexpected endpoint $endpoint" }.invoke()
            },
            startProcess = { endpoint, command ->
                launches += Launch(endpoint, command)
                startFailure?.let { throw it }
                FixtureProcess()
            },
            registerRoot = { provider ->
                registrations += provider
                registrationFailure?.let { throw it }
                registrationResult.also { if (it.exitCode == 0) activeRoot = provider }
            },
            revokeRoot = { provider ->
                revocations += provider
                if (activeRoot === provider) activeRoot = null
            },
            shouldRegisterRoot = { registerRootEnabled },
        )

        fun identity(endpoint: WirelessEndpoint, output: String, exitCode: Int = 0): Fixture = apply {
            identities[endpoint] = { ShellResult(exitCode, output) }
        }
    }

    private class FixtureProcess : Process() {
        private val input = ByteArrayInputStream(ByteArray(0))
        private val error = ByteArrayInputStream(ByteArray(0))
        private val output = ByteArrayOutputStream()
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun getOutputStream() = output
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() = Unit
    }
}
