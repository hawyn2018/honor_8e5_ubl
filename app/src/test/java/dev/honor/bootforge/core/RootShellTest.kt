package dev.honor.bootforge.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RootShellTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun clearProvider() { RootShell.invalidate() }

    @Test fun `integrated provider is verified before commands and binary capture separates stderr`() {
        val commands = mutableListOf<String>()
        val binary = ByteArray(256 * 1024) { it.toByte() }
        val provider = RootProcessProvider { command ->
            commands += command
            if (command == "id -u") FixtureProcess("0\n".toByteArray(), "root notice".toByteArray())
            else FixtureProcess(binary, "dd diagnostic\n".toByteArray())
        }
        assertEquals(0, RootShell.setIntegratedProvider(provider, file("root.log")).exitCode)
        val image = file("image.img")
        val result = RootShell.capture("read-fixture", image, file("error.log"))
        assertArrayEquals(binary, image.readBytes())
        assertEquals("dd diagnostic\n", result.output)
        assertEquals(listOf("id -u", "read-fixture"), commands)
    }

    @Test fun `nonzero task is returned once without replay or implicit provider switch`() {
        val commands = mutableListOf<String>()
        val provider = RootProcessProvider { command ->
            commands += command
            if (command == "id -u") FixtureProcess("0".toByteArray())
            else FixtureProcess("task output\n".toByteArray(), "task error\n".toByteArray(), exit = 9)
        }
        RootShell.setIntegratedProvider(provider, file("root.log"))
        val result = RootShell.run("single-task", file("task.log"))
        assertEquals(9, result.exitCode)
        assertTrue(result.output.contains("task output"))
        assertTrue(result.output.contains("task error"))
        assertEquals(listOf("id -u", "single-task"), commands)
    }

    @Test fun `explicit check verifies a registered provider with another fresh process`() {
        var launches = 0
        val provider = RootProcessProvider { launches++; FixtureProcess("0\n".toByteArray()) }
        RootShell.setIntegratedProvider(provider, file("register.log"))
        assertEquals(ShellResult(0, "ROOT_UID=0\nROOT_PROVIDER=integrated\n"), RootShell.check(file("check.log")))
        assertEquals(2, launches)
    }

    @Test fun `a non-root provider is rejected and its output has no success marker`() {
        val result = RootShell.setIntegratedProvider(RootProcessProvider {
            FixtureProcess("2000\n".toByteArray())
        }, file("denied.log"))
        assertNotEquals(0, result.exitCode)
        assertFalse(result.output.contains("ROOT_UID=0"))
    }

    @Test fun `timed out check destroys the launched process and reports failure`() {
        val process = FixtureProcess(ByteArray(0), timeout = true)
        val result = RootShell.setIntegratedProvider(RootProcessProvider { process }, file("timeout.log"), 1)
        assertEquals(124, result.exitCode)
        assertTrue(process.destroyed)
        assertFalse(result.output.contains("ROOT_UID=0"))
    }

    @Test fun `log creation failure does not launch the task`() {
        var launches = 0
        val provider = RootProcessProvider { launches++; FixtureProcess("0".toByteArray()) }
        RootShell.setIntegratedProvider(provider, file("root.log"))
        val missingParent = File(temporary.root, "missing/parent/task.log")
        assertTrue(runCatching { RootShell.run("task", missingParent) }.isFailure)
        assertEquals(1, launches)
    }

    @Test fun `writing tasks use the unbounded wait and are never killed by a check deadline`() {
        val task = FixtureProcess("written\n".toByteArray(), timeout = true)
        RootShell.setIntegratedProvider(RootProcessProvider { command ->
            if (command == "id -u") FixtureProcess("0".toByteArray()) else task
        }, file("root.log"))
        assertEquals(0, RootShell.run("write-fixture", file("write.log")).exitCode)
        assertEquals(1, task.unboundedWaits)
        assertEquals(0, task.timedWaits)
        assertFalse(task.destroyed)
    }

    @Test fun `read task timeout closes and destroys its one process without replay`() {
        val commands = mutableListOf<String>()
        val task = FixtureProcess(ByteArray(0), timeout = true)
        RootShell.setIntegratedProvider(RootProcessProvider { command ->
            commands += command
            if (command == "id -u") FixtureProcess("0".toByteArray()) else task
        }, file("root.log"))
        val failure = runCatching { RootShell.capture("read-fixture", file("image.img"), file("error.log"), 1) }
        assertTrue(failure.exceptionOrNull() is RootProcessTimeout)
        assertTrue(task.destroyed)
        assertEquals(listOf("id -u", "read-fixture"), commands)
    }

    @Test fun `a start failure is not retried or switched to another root mechanism`() {
        val commands = mutableListOf<String>()
        RootShell.setIntegratedProvider(RootProcessProvider { command ->
            commands += command
            if (command == "id -u") FixtureProcess("0".toByteArray())
            else throw java.io.IOException("bridge disconnected")
        }, file("root.log"))
        assertTrue(runCatching { RootShell.run("single-task", file("task.log")) }.isFailure)
        assertEquals(listOf("id -u", "single-task"), commands)
    }

    @Test fun `binary capture rejects aliases of the error log before starting a task`() {
        var launches = 0
        RootShell.setIntegratedProvider(RootProcessProvider {
            launches++
            FixtureProcess("0".toByteArray())
        }, file("root.log"))
        val image = file("image.img")
        val alias = File(temporary.root, "./image.img")
        assertTrue(runCatching { RootShell.capture("read-fixture", image, alias) }.isFailure)
        assertEquals(1, launches)
    }

    @Test fun `capture log creation failure does not start its read process`() {
        var launches = 0
        RootShell.setIntegratedProvider(RootProcessProvider {
            launches++
            FixtureProcess("0".toByteArray())
        }, file("root.log"))
        assertTrue(runCatching {
            RootShell.capture("read-fixture", file("image.img"), File(temporary.root, "missing/error.log"))
        }.isFailure)
        assertEquals(1, launches)
    }

    @Test fun `registration requires exact uid zero and successful exit in a new process`() {
        listOf("2000", "10", "uid=0", "ROOT_UID=0", "banner\n0", "").forEachIndexed { index, uid ->
            val commands = mutableListOf<String>()
            val result = RootShell.setIntegratedProvider(RootProcessProvider { command ->
                commands += command
                FixtureProcess(uid.toByteArray())
            }, file("denied-$index.log"))
            assertNotEquals(0, result.exitCode)
            assertEquals(listOf("id -u"), commands)
        }
        assertNotEquals(0, RootShell.setIntegratedProvider(RootProcessProvider {
            FixtureProcess("0".toByteArray(), exit = 1)
        }, file("failed-zero.log")).exitCode)
    }

    @Test fun `a proof that cannot be recorded does not replace the active provider`() {
        val commands = mutableListOf<String>()
        RootShell.setIntegratedProvider(RootProcessProvider { command ->
            commands += "original:$command"
            FixtureProcess(if (command == "id -u") "0".toByteArray() else "retained".toByteArray())
        }, file("root.log"))
        assertTrue(runCatching {
            RootShell.setIntegratedProvider(RootProcessProvider { command ->
                commands += "replacement:$command"
                FixtureProcess("0".toByteArray())
            }, temporary.root)
        }.isFailure)
        assertEquals("retained", RootShell.run("task", file("task.log")).output)
        assertEquals(listOf("original:id -u", "replacement:id -u", "original:task"), commands)
    }

    @Test fun `wireless registration and fresh check report their own source and preserve binary stdout`() {
        val commands = mutableListOf<String>()
        val binary = ByteArray(256 * 1024 + 17) { it.toByte() }
        val provider = RootProcessProvider { command ->
            commands += command
            if (command == "id -u") FixtureProcess("0\n".toByteArray(), "ADB notice\n".toByteArray())
            else FixtureProcess(binary, "read diagnostic\n".toByteArray())
        }
        val expected = ShellResult(0, "ROOT_UID=0\nROOT_PROVIDER=wireless-adb\n")
        assertEquals(expected, RootShell.setWirelessProvider(provider, file("wireless-register.log")))
        assertTrue(RootShell.hasWirelessProvider())
        assertEquals(expected, RootShell.check(file("wireless-check.log")))
        val destination = file("wireless-image.img")
        val result = RootShell.capture("read-exact-fixture", destination, file("wireless-read.log"))
        assertArrayEquals(binary, destination.readBytes())
        assertEquals("read diagnostic\n", result.output)
        assertEquals(listOf("id -u", "id -u", "read-exact-fixture"), commands)
    }

    @Test fun `wireless registration rejects anything except exact uid zero with a successful exit`() {
        listOf("2000", "10", "uid=0", "ROOT_UID=0", "banner\n0", "", "0\n2000").forEachIndexed { index, value ->
            val commands = mutableListOf<String>()
            val result = RootShell.setWirelessProvider(RootProcessProvider { command ->
                commands += command
                FixtureProcess(value.toByteArray())
            }, file("wireless-denied-$index.log"))
            assertNotEquals(0, result.exitCode)
            assertEquals("ROOT_UID=unknown", result.output.lineSequence().first())
            assertFalse(result.output.contains("ROOT_PROVIDER=wireless-adb"))
            assertFalse(RootShell.hasWirelessProvider())
            assertEquals(listOf("id -u"), commands)
        }
        val result = RootShell.setWirelessProvider(RootProcessProvider {
            FixtureProcess("0\n".toByteArray(), exit = 1)
        }, file("wireless-nonzero-exit.log"))
        assertNotEquals(0, result.exitCode)
        assertFalse(RootShell.hasWirelessProvider())
    }

    @Test fun `clearing wireless root compares provider identity rather than value equality`() {
        class EqualProvider : RootProcessProvider {
            override fun start(command: String): Process = FixtureProcess("0\n".toByteArray())
            override fun equals(other: Any?) = other is EqualProvider
            override fun hashCode() = 1
        }
        val previous = EqualProvider()
        val current = EqualProvider()
        assertEquals(previous, current)
        assertNotSame(previous, current)
        RootShell.setWirelessProvider(previous, file("wireless-old.log"))
        RootShell.setWirelessProvider(current, file("wireless-new.log"))
        assertFalse(RootShell.clearWirelessProvider(previous))
        assertTrue(RootShell.hasWirelessProvider())
        assertTrue(RootShell.clearWirelessProvider(current))
        assertFalse(RootShell.hasWirelessProvider())
        assertFalse(RootShell.clearWirelessProvider(current))
    }

    @Test fun `registering the same wireless provider again still verifies a fresh process`() {
        val commands = mutableListOf<String>()
        val provider = RootProcessProvider { command ->
            commands += command
            FixtureProcess("0\n".toByteArray())
        }
        RootShell.setWirelessProvider(provider, file("wireless-first.log"))
        RootShell.setWirelessProvider(provider, file("wireless-second.log"))
        assertEquals(listOf("id -u", "id -u"), commands)
        assertTrue(RootShell.clearWirelessProvider(provider))
        assertFalse(RootShell.hasWirelessProvider())
    }

    @Test fun `wireless root takes precedence without discarding the integrated provider`() {
        val commands = mutableListOf<String>()
        val integrated = recordingProvider("integrated", commands)
        val wireless = recordingProvider("wireless", commands)
        RootShell.setWirelessProvider(wireless, file("wireless.log"))
        // Later registration of an integrated provider must not replace wireless routing.
        RootShell.setIntegratedProvider(integrated, file("integrated.log"))
        assertEquals("wireless", RootShell.run("first-task", file("first-task.log")).output)
        assertTrue(RootShell.clearWirelessProvider(wireless))
        assertEquals("integrated", RootShell.run("second-task", file("second-task.log")).output)
        assertEquals(listOf("wireless:id -u", "integrated:id -u", "wireless:first-task", "integrated:second-task"), commands)
    }

    @Test fun `clearing wireless ownership preserves an integrated registration of the identical provider`() {
        val commands = mutableListOf<String>()
        val shared = recordingProvider("shared", commands)
        RootShell.setIntegratedProvider(shared, file("integrated.log"))
        RootShell.setWirelessProvider(shared, file("wireless.log"))
        assertTrue(RootShell.clearWirelessProvider(shared))
        assertFalse(RootShell.hasWirelessProvider())
        assertEquals(ShellResult(0, "ROOT_UID=0\nROOT_PROVIDER=integrated\n"), RootShell.check(file("remaining-integrated.log")))
        assertEquals("shared", RootShell.run("retained-task", file("retained-task.log")).output)
        assertEquals(listOf("shared:id -u", "shared:id -u", "shared:id -u", "shared:retained-task"), commands)
    }

    @Test fun `integrated invalidation leaves the wireless root channel available`() {
        val commands = mutableListOf<String>()
        RootShell.setIntegratedProvider(recordingProvider("integrated", commands), file("integrated.log"))
        val wireless = recordingProvider("wireless", commands)
        RootShell.setWirelessProvider(wireless, file("wireless.log"))
        RootShell.invalidateIntegrated()
        assertTrue(RootShell.hasWirelessProvider())
        assertEquals(ShellResult(0, "ROOT_UID=0\nROOT_PROVIDER=wireless-adb\n"), RootShell.check(file("check.log")))
        assertEquals("wireless", RootShell.run("retained-task", file("retained-task.log")).output)
        assertEquals(listOf("integrated:id -u", "wireless:id -u", "wireless:id -u", "wireless:retained-task"), commands)
    }

    @Test fun `full invalidation also removes wireless provider ownership`() {
        val commands = mutableListOf<String>()
        val wireless = recordingProvider("wireless", commands)
        RootShell.setWirelessProvider(wireless, file("wireless.log"))
        RootShell.invalidate()
        assertFalse(RootShell.hasWirelessProvider())
        assertFalse(RootShell.clearWirelessProvider(wireless))
        assertEquals(listOf("wireless:id -u"), commands)
    }

    @Test fun `failed fresh wireless proof clears only wireless and does not retry the same check`() {
        val commands = mutableListOf<String>()
        RootShell.setIntegratedProvider(recordingProvider("integrated", commands), file("integrated.log"))
        var wirelessProofs = 0
        val wireless = RootProcessProvider { command ->
            commands += "wireless:$command"
            FixtureProcess((if (++wirelessProofs == 1) "0\n" else "2000\n").toByteArray())
        }
        RootShell.setWirelessProvider(wireless, file("wireless.log"))
        val failed = RootShell.check(file("wireless-failed.log"))
        assertNotEquals(0, failed.exitCode)
        assertFalse(failed.output.contains("ROOT_UID=0"))
        assertFalse(RootShell.hasWirelessProvider())
        assertEquals(listOf("integrated:id -u", "wireless:id -u", "wireless:id -u"), commands)
        assertEquals(ShellResult(0, "ROOT_UID=0\nROOT_PROVIDER=integrated\n"), RootShell.check(file("subsequent-check.log")))
        assertEquals("integrated:id -u", commands.last())
        assertEquals("integrated", RootShell.run("later-task", file("later-task.log")).output)
    }

    @Test fun `nonzero wireless task executes once without falling back to integrated root`() {
        val commands = mutableListOf<String>()
        RootShell.setIntegratedProvider(recordingProvider("integrated", commands), file("integrated.log"))
        RootShell.setWirelessProvider(RootProcessProvider { command ->
            commands += "wireless:$command"
            if (command == "id -u") FixtureProcess("0\n".toByteArray())
            else FixtureProcess("partial output\n".toByteArray(), "task failed\n".toByteArray(), exit = 9)
        }, file("wireless.log"))
        val result = RootShell.run("single-task", file("task.log"))
        assertEquals(9, result.exitCode)
        assertTrue(result.output.contains("partial output"))
        assertTrue(result.output.contains("task failed"))
        assertEquals(listOf("integrated:id -u", "wireless:id -u", "wireless:single-task"), commands)
    }

    @Test fun `wireless process start failure is not replayed through another provider`() {
        val commands = mutableListOf<String>()
        RootShell.setIntegratedProvider(recordingProvider("integrated", commands), file("integrated.log"))
        RootShell.setWirelessProvider(RootProcessProvider { command ->
            commands += "wireless:$command"
            if (command == "id -u") FixtureProcess("0\n".toByteArray())
            else throw java.io.IOException("wireless endpoint disconnected")
        }, file("wireless.log"))
        assertTrue(runCatching { RootShell.run("single-task", file("task.log")) }.isFailure)
        assertEquals(listOf("integrated:id -u", "wireless:id -u", "wireless:single-task"), commands)
    }

    @Test fun `wireless read timeout destroys one process without integrated replay`() {
        val commands = mutableListOf<String>()
        val task = FixtureProcess(ByteArray(0), timeout = true)
        RootShell.setIntegratedProvider(recordingProvider("integrated", commands), file("integrated.log"))
        RootShell.setWirelessProvider(RootProcessProvider { command ->
            commands += "wireless:$command"
            if (command == "id -u") FixtureProcess("0\n".toByteArray()) else task
        }, file("wireless.log"))
        val failed = runCatching { RootShell.capture("read-once", file("image.img"), file("read.log"), 1) }
        assertTrue(failed.exceptionOrNull() is RootProcessTimeout)
        assertTrue(task.destroyed)
        assertEquals(listOf("integrated:id -u", "wireless:id -u", "wireless:read-once"), commands)
    }

    @Test fun `rejected wireless replacement preserves both previous wireless and integrated routes`() {
        val commands = mutableListOf<String>()
        val original = recordingProvider("original", commands)
        RootShell.setIntegratedProvider(recordingProvider("integrated", commands), file("integrated.log"))
        RootShell.setWirelessProvider(original, file("original.log"))
        val replacement = RootProcessProvider { command ->
            commands += "replacement:$command"
            FixtureProcess("2000\n".toByteArray())
        }
        assertNotEquals(0, RootShell.setWirelessProvider(replacement, file("replacement.log")).exitCode)
        assertFalse(RootShell.clearWirelessProvider(replacement))
        assertEquals("original", RootShell.run("retained-task", file("retained.log")).output)
        assertTrue(RootShell.clearWirelessProvider(original))
        assertEquals("integrated", RootShell.run("restored-task", file("restored.log")).output)
        assertEquals(listOf("integrated:id -u", "original:id -u", "replacement:id -u", "original:retained-task", "integrated:restored-task"), commands)
    }

    @Test fun `unrecorded wireless proof does not replace the previous verified provider`() {
        val commands = mutableListOf<String>()
        val original = recordingProvider("original", commands)
        RootShell.setWirelessProvider(original, file("original.log"))
        val replacement = recordingProvider("replacement", commands)
        assertTrue(runCatching { RootShell.setWirelessProvider(replacement, temporary.root) }.isFailure)
        assertTrue(RootShell.hasWirelessProvider())
        assertFalse(RootShell.clearWirelessProvider(replacement))
        assertEquals("original", RootShell.run("retained-task", file("retained.log")).output)
        assertEquals(listOf("original:id -u", "replacement:id -u", "original:retained-task"), commands)
        assertTrue(RootShell.clearWirelessProvider(original))
    }

    @Test fun `wireless registration timeout destroys the candidate and preserves the previous provider`() {
        val commands = mutableListOf<String>()
        val original = recordingProvider("original", commands)
        RootShell.setWirelessProvider(original, file("original.log"))
        val process = FixtureProcess(ByteArray(0), timeout = true)
        var attempts = 0
        val candidate = RootProcessProvider { command ->
            assertEquals("id -u", command)
            attempts++
            process
        }
        val failed = RootShell.setWirelessProvider(candidate, file("candidate.log"), 1)
        assertEquals(124, failed.exitCode)
        assertEquals(1, attempts)
        assertTrue(process.destroyed)
        assertTrue(RootShell.hasWirelessProvider())
        assertFalse(RootShell.clearWirelessProvider(candidate))
        assertEquals("original", RootShell.run("retained-task", file("retained.log")).output)
        assertEquals(listOf("original:id -u", "original:retained-task"), commands)
    }

    @Test fun `external su request with a blocked log parent preserves existing provider ownership`() {
        val commands = mutableListOf<String>()
        val integrated = recordingProvider("integrated", commands)
        val wireless = recordingProvider("wireless", commands)
        RootShell.setIntegratedProvider(integrated, file("integrated.log"))
        RootShell.setWirelessProvider(wireless, file("wireless.log"))
        val blockedParent = temporary.newFile("not-a-directory")
        // Every su probe must create its capture files in this parent before
        // launching a process. A regular file cannot become that directory.
        // This exercises request failure without invoking any host su binary.
        val failure = runCatching {
            RootShell.requestExternalSu(File(blockedParent, "request.log"))
        }
        assertTrue(failure.isFailure)
        assertTrue(RootShell.hasWirelessProvider())
        assertTrue(RootShell.hasIntegratedProvider())
        assertEquals("wireless", RootShell.run("retained-wireless", file("retained-wireless.log")).output)
        assertTrue(RootShell.clearWirelessProvider(wireless))
        assertEquals("integrated", RootShell.run("retained-integrated", file("retained-integrated.log")).output)
        assertEquals(listOf("integrated:id -u", "wireless:id -u", "wireless:retained-wireless", "integrated:retained-integrated"), commands)
    }

    @Test fun `interrupted cleanup preserves original cancellation and closes both output files`() {
        val cleanupEntered = CountDownLatch(1)
        val cleanupDone = CountDownLatch(1)
        val forced = AtomicBoolean()
        val alive = AtomicBoolean(true)
        val original = InterruptedException("original task cancellation")
        val process = object : Process() {
            override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun waitFor(): Int = throw original
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                cleanupEntered.countDown()
                return cleanupDone.await(timeout, unit)
            }
            override fun exitValue(): Int = if (alive.get()) throw IllegalThreadStateException() else 143
            override fun isAlive() = alive.get()
            override fun destroy() = Unit // Deliberately needs force after the grace period.
            override fun destroyForcibly(): Process {
                forced.set(true)
                alive.set(false)
                cleanupDone.countDown()
                return this
            }
        }
        val closed = listOf(AtomicBoolean(), AtomicBoolean())
        var opened = 0
        val failure = AtomicReference<Throwable>()
        val restoredInterrupt = AtomicBoolean()
        val runner = thread(name = "root-cleanup-interruption-fixture") {
            try {
                RootProcessIO.collect({ process }, file("stdout.log"), file("stderr.log"), 0, "unused") { target ->
                    val marker = closed[opened++]
                    object : FileOutputStream(target) {
                        override fun close() { try { super.close() } finally { marker.set(true) } }
                    }
                }
            } catch (error: Throwable) { failure.set(error) }
            finally { restoredInterrupt.set(Thread.currentThread().isInterrupted) }
        }
        try {
            assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))
            // Interrupt the real cleanup wait, not the task wait which already failed.
            runner.interrupt()
            runner.join(2_000)
            assertFalse(runner.isAlive)
            assertSame(original, failure.get())
            assertTrue(forced.get())
            assertTrue(restoredInterrupt.get())
            assertEquals(2, opened)
            assertTrue(closed.all { it.get() })
        } finally {
            cleanupDone.countDown()
            runner.interrupt()
            runner.join(2_000)
        }
    }

    private fun file(name: String) = File(temporary.root, name)

    private fun recordingProvider(name: String, commands: MutableList<String>) = RootProcessProvider { command ->
        commands += "$name:$command"
        FixtureProcess((if (command == "id -u") "0\n" else name).toByteArray())
    }

    private class FixtureProcess(
        stdout: ByteArray,
        stderr: ByteArray = ByteArray(0),
        private val exit: Int = 0,
        private val timeout: Boolean = false,
    ) : Process() {
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
        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException()
            return exit
        }
        override fun isAlive() = alive
        override fun destroy() { destroyed = true; alive = false }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
