package dev.honor.bootforge.core

import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class CustomSuRootShellTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun prepare() {
        RootShell.configureCustomSuSettings(null)
        RootShell.configureModuleProvider(null)
    }

    @After fun restore() {
        RootShell.configureCustomSuSettings(null)
        RootShell.configureModuleProvider(null)
        RootShell.startSuProcess = { ProcessBuilder(it).start() }
    }

    @Test fun `custom executable and command are separate argv elements and uid proof precedes task`() {
        val path = "/bin/custom su; ' $(literal)"
        RootShell.configureCustomSuSettings { CustomSuSettings(true, path) }
        val calls = mutableListOf<List<String>>()
        val command = "dd if='/fixture source' of='/fixture target'; echo \"$\""
        RootShell.startSuProcess = { argv ->
            calls += argv
            FixtureProcess(if (argv.last() == "id -u") "0\n" else "task done\n", "su notice\n")
        }
        val result = RootShell.run(command, file("task.log"))
        assertEquals(0, result.exitCode)
        assertEquals(listOf(listOf(path, "-c", "id -u"), listOf(path, "-c", command)), calls)
        assertTrue(result.output.contains("task done"))
    }

    @Test fun `custom mode revokes old integrated wireless and module selection rather than falling back`() {
        var selected = CustomSuSettings()
        RootShell.configureCustomSuSettings { selected }
        val oldCalls = mutableListOf<String>()
        RootShell.setIntegratedProvider(provider("integrated", oldCalls), file("integrated.log"))
        RootShell.setWirelessProvider(provider("wireless", oldCalls), file("wireless.log"))
        var discoveries = 0
        RootShell.configureModuleProvider { discoveries++; provider("module", oldCalls) }
        assertEquals(0, RootShell.checkModule(file("module.log"), 1_000)!!.exitCode)
        val baseline = oldCalls.toList()
        selected = CustomSuSettings(true, "/bin/666")
        var customCalls = 0
        RootShell.startSuProcess = { customCalls++; FixtureProcess("2000\n") }
        assertNotEquals(0, RootShell.check(file("custom.log")).exitCode)
        assertTrue(runCatching { RootShell.run("single-write", file("write.log")) }.isFailure)
        assertEquals(2, customCalls)
        assertEquals(baseline, oldCalls)
        assertEquals(1, discoveries)
        assertFalse(RootShell.hasIntegratedProvider())
        assertFalse(RootShell.hasWirelessProvider())
        assertFalse(RootShell.hasModuleProvider())
    }

    @Test fun `background registration cannot take priority over saved custom selection`() {
        RootShell.configureCustomSuSettings { CustomSuSettings(true, "/bin/666") }
        var launches = 0
        val old = RootProcessProvider { launches++; FixtureProcess("0") }
        assertNotEquals(0, RootShell.setIntegratedProvider(old, file("integrated.log")).exitCode)
        assertNotEquals(0, RootShell.setWirelessProvider(old, file("wireless.log")).exitCode)
        assertEquals(0, launches)
        assertFalse(RootShell.hasIntegratedProvider())
        assertFalse(RootShell.hasWirelessProvider())
    }

    @Test fun `changed persisted path discards previous proof and off rediscovers the original module`() {
        val store = CustomSuSettingsStore.inDirectory(temporary.root)
        store.save(CustomSuSettings(true, "/bin/one"))
        RootShell.configureCustomSuSettings(store::read)
        val calls = mutableListOf<List<String>>()
        RootShell.startSuProcess = { calls += it; FixtureProcess(if (it.last() == "id -u") "0" else "done") }
        RootShell.run("first", file("first.log"))
        store.save(CustomSuSettings(true, "/bin/two"))
        RootShell.run("second", file("second.log"))
        assertEquals(listOf("/bin/one", "/bin/one", "/bin/two", "/bin/two"), calls.map { it.first() })
        val moduleCalls = mutableListOf<String>()
        RootShell.configureModuleProvider { provider("module", moduleCalls) }
        store.save(CustomSuSettings(false, "/bin/two"))
        RootShell.run("third", file("third.log"))
        assertEquals(listOf("module:id -u", "module:third"), moduleCalls)
        assertEquals(4, calls.size)
    }

    @Test fun `disabled selection preserves wireless before integrated before module ordering`() {
        RootShell.configureCustomSuSettings { CustomSuSettings(false, "/unused/custom") }
        RootShell.startSuProcess = { error("custom executable should remain idle") }
        val calls = mutableListOf<String>()
        RootShell.setIntegratedProvider(provider("integrated", calls), file("integrated.log"))
        RootShell.setWirelessProvider(provider("wireless", calls), file("wireless.log"))
        RootShell.configureModuleProvider { provider("module", calls) }
        RootShell.run("task", file("task.log"))
        assertEquals(listOf("integrated:id -u", "wireless:id -u", "wireless:task"), calls)
    }

    @Test fun `nonzero or malformed identity and failed exit reject custom root without dispatching a task`() {
        RootShell.configureCustomSuSettings { CustomSuSettings(true, "/bin/666") }
        listOf("2000" to 0, "uid=0" to 0, "banner\n0" to 0, "0" to 1).forEachIndexed { index, (output, exit) ->
            var calls = 0
            RootShell.startSuProcess = { argv ->
                calls++
                assertEquals("id -u", argv.last())
                FixtureProcess(output, exit = exit)
            }
            assertTrue(runCatching { RootShell.run("write", file("denied-$index.log")) }.isFailure)
            assertEquals(1, calls)
        }
    }

    @Test fun `custom task failure is returned once without identity retry or provider replay`() {
        RootShell.configureCustomSuSettings { CustomSuSettings(true, "/bin/666") }
        val calls = mutableListOf<String>()
        val write = FixtureProcess("partial write", "write error", exit = 9, timeout = true)
        RootShell.startSuProcess = {
            calls += it.last()
            if (it.last() == "id -u") FixtureProcess("0") else write
        }
        val result = RootShell.run("write-fixture", file("write.log"))
        assertEquals(9, result.exitCode)
        assertEquals(listOf("id -u", "write-fixture"), calls)
        assertEquals(1, write.unboundedWaits)
        assertEquals(0, write.timedWaits)
        assertFalse(write.destroyed)
    }

    @Test fun `custom read timeout kills only the one read process and never replays it`() {
        RootShell.configureCustomSuSettings { CustomSuSettings(true, "/bin/666") }
        val calls = mutableListOf<String>()
        val read = FixtureProcess("", timeout = true)
        RootShell.startSuProcess = {
            calls += it.last()
            if (it.last() == "id -u") FixtureProcess("0") else read
        }
        assertTrue(runCatching { RootShell.capture("read-fixture", file("image"), file("read.log"), 1) }
            .exceptionOrNull() is RootProcessTimeout)
        assertEquals(listOf("id -u", "read-fixture"), calls)
        assertTrue(read.destroyed)
    }

    @Test fun `binary custom capture separates image bytes from su and dd diagnostics`() {
        RootShell.configureCustomSuSettings { CustomSuSettings(true, "/bin/666") }
        val image = ByteArray(256 * 1024) { it.toByte() }
        RootShell.startSuProcess = { argv ->
            if (argv.last() == "id -u") FixtureProcess("0", "su notice")
            else FixtureProcess(image, "dd diagnostic".toByteArray())
        }
        val target = file("image")
        val result = RootShell.capture("read-fixture", target, file("read.log"))
        assertArrayEquals(image, target.readBytes())
        assertEquals("dd diagnostic", result.output)
    }

    @Test fun `external startup check verifies the configured executable instead of discovering other channels`() {
        RootShell.configureCustomSuSettings { CustomSuSettings(true, "/bin/666") }
        RootShell.configureModuleProvider { error("custom mode must not discover the module") }
        var calls = 0
        RootShell.startSuProcess = { argv ->
            calls++
            assertEquals(listOf("/bin/666", "-c", "id -u"), argv)
            FixtureProcess("0", "manager notice")
        }
        assertEquals(ExternalRootProbe.Result(true, "/bin/666"), ExternalRootProbe.check(file("startup.log")))
        assertEquals(1, calls)
        assertTrue(file("startup.log").readText().contains("ROOT_PROVIDER=custom-su"))
    }

    @Test fun `unreadable configuration revokes previous cached providers and blocks dispatch`() {
        var broken = false
        RootShell.configureCustomSuSettings { if (broken) error("read failed") else CustomSuSettings() }
        val calls = mutableListOf<String>()
        RootShell.setIntegratedProvider(provider("old", calls), file("register.log"))
        broken = true
        assertTrue(runCatching { RootShell.run("task", file("task.log")) }.isFailure)
        broken = false
        assertFalse(RootShell.hasIntegratedProvider())
        assertEquals(listOf("old:id -u"), calls)
    }

    @Test fun `failed log opening never starts a custom write`() {
        RootShell.configureCustomSuSettings { CustomSuSettings(true, "/bin/666") }
        val calls = mutableListOf<String>()
        RootShell.startSuProcess = { calls += it.last(); FixtureProcess("0") }
        RootShell.check(file("proof.log"))
        assertTrue(runCatching { RootShell.run("write", File(temporary.root, "absent/task.log")) }.isFailure)
        assertEquals(listOf("id -u"), calls)
    }

    @Test fun `a path changed during authorization never dispatches a task on the old launcher`() {
        var selected = CustomSuSettings(true, "/bin/first")
        RootShell.configureCustomSuSettings { selected }
        val calls = mutableListOf<List<String>>()
        RootShell.startSuProcess = {
            calls += it
            selected = selected.copy(path = "/bin/second")
            FixtureProcess("0")
        }
        val failure = runCatching { RootShell.run("write", file("task.log")) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf(listOf("/bin/first", "-c", "id -u")), calls)
    }

    @Test fun `external startup denial never falls back to module or another su candidate`() {
        RootShell.configureCustomSuSettings { CustomSuSettings(true, "/bin/666") }
        var discoveries = 0
        RootShell.configureModuleProvider { discoveries++; provider("module", mutableListOf()) }
        var calls = 0
        RootShell.startSuProcess = { calls++; FixtureProcess("2000") }
        assertEquals(ExternalRootProbe.Result(false), ExternalRootProbe.check(file("startup.log")))
        assertEquals(1, calls)
        assertEquals(0, discoveries)
    }

    private fun file(name: String) = File(temporary.root, name)
    private fun provider(name: String, calls: MutableList<String>) = RootProcessProvider {
        calls += "$name:$it"
        FixtureProcess(if (it == "id -u") "0" else name)
    }

    private class FixtureProcess(
        stdout: ByteArray,
        stderr: ByteArray = ByteArray(0),
        private val exit: Int = 0,
        private val timeout: Boolean = false,
    ) : Process() {
        constructor(stdout: String, stderr: String = "", exit: Int = 0, timeout: Boolean = false) :
            this(stdout.toByteArray(), stderr.toByteArray(), exit, timeout)
        private val input = ByteArrayInputStream(stdout)
        private val error = ByteArrayInputStream(stderr)
        private val output = ByteArrayOutputStream()
        private var alive = true
        var destroyed = false
            private set
        var unboundedWaits = 0
            private set
        var timedWaits = 0
            private set
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun getOutputStream() = output
        override fun waitFor(): Int { unboundedWaits++; alive = false; return exit }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            timedWaits++
            if (this.timeout && alive) return false
            alive = false
            return true
        }
        override fun exitValue(): Int { if (alive) throw IllegalThreadStateException(); return exit }
        override fun isAlive() = alive
        override fun destroy() { destroyed = true; alive = false }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
