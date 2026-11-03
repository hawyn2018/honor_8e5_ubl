package dev.honor.bootforge.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class ModuleRootAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @After fun clearAuthorization() {
        RootShell.configureModuleProvider(null)
        RootShell.invalidate()
    }

    @Test fun startupUsesTheVerifiedModuleWithoutInvokingIntegratedOrExternalSu() {
        val commands = mutableListOf<String>()
        RootShell.setIntegratedProvider(recording("integrated", commands), log("integrated.log"))
        commands.clear()
        RootShell.configureModuleProvider { recording("module", commands) }
        val result = ExternalRootProbe.check(log("startup.log"), 500)
        assertTrue(result.granted)
        assertNull(result.suPath)
        assertEquals(listOf("module:id -u"), commands)
        assertEquals("ROOT_UID=0\nROOT_PROVIDER=module\n", log("startup.log").readText())
        assertTrue(RootShell.hasIntegratedProvider())
        assertTrue(RootShell.hasModuleProvider())
    }

    @Test fun explicitAuthorizationUsesModuleAndRoutesTheTaskOnlyOnce() {
        val commands = mutableListOf<String>()
        RootShell.configureModuleProvider { recording("module", commands) }
        assertEquals(ShellResult(0, "ROOT_UID=0\nROOT_PROVIDER=module\n"),
            RootShell.requestExternalSu(log("grant.log")))
        assertEquals("module", RootShell.run("task", log("task.log")).output)
        assertEquals(listOf("module:id -u", "module:task"), commands)
    }

    @Test fun everyExplicitCheckObtainsAFreshUidProof() {
        val commands = mutableListOf<String>()
        RootShell.configureModuleProvider { recording("module", commands) }
        repeat(2) {
            assertEquals(ShellResult(0, "ROOT_UID=0\nROOT_PROVIDER=module\n"),
                RootShell.check(log("check-$it.log")))
        }
        assertEquals(listOf("module:id -u", "module:id -u"), commands)
    }

    @Test fun absentModuleDoesNotPretendToHaveAnAuthorization() {
        RootShell.configureModuleProvider { null }
        assertNull(RootShell.checkModule(log("absent.log"), 500))
        assertFalse(RootShell.hasModuleProvider())
    }

    @Test fun malformedOrFailedIdentityProofNeverRegistersTheModule() {
        val invalid = listOf("2000", "uid=0", "ROOT_UID=0", "banner\n0", "", "0\n2000")
        invalid.forEachIndexed { index, uid ->
            RootShell.configureModuleProvider { RootProcessProvider { FixtureProcess(uid.toByteArray()) } }
            val result = checkNotNull(RootShell.checkModule(log("invalid-$index.log"), 500))
            assertNotEquals(0, result.exitCode)
            assertFalse(RootShell.hasModuleProvider())
        }
        RootShell.configureModuleProvider {
            RootProcessProvider { FixtureProcess("0".toByteArray(), exit = 126) }
        }
        assertNotEquals(0, RootShell.checkModule(log("invalid-exit.log"), 500)?.exitCode)
        assertFalse(RootShell.hasModuleProvider())
    }

    @Test fun aSubmittedCommandWithUnknownResultIsNotReplayedOrReauthorized() {
        val commands = mutableListOf<String>()
        RootShell.configureModuleProvider {
            RootProcessProvider { command ->
                commands += command
                if (command == "id -u") FixtureProcess("0".toByteArray())
                else FixtureProcess(ByteArray(0), "[hnubl-module] submitted; result unknown".toByteArray(), 125)
            }
        }
        RootShell.checkModule(log("proof.log"), 500)
        val result = RootShell.run("write-once", log("write.log"))
        assertEquals(125, result.exitCode)
        assertTrue(result.output.contains("result unknown"))
        assertEquals(listOf("id -u", "write-once"), commands)
    }

    @Test fun moduleBinaryCaptureKeepsStderrSeparateFromTheImage() {
        val bytes = ByteArray(128 * 1024 + 3) { it.toByte() }
        RootShell.configureModuleProvider {
            RootProcessProvider { command ->
                if (command == "id -u") FixtureProcess("0\n".toByteArray())
                else FixtureProcess(bytes, "dd notice\n".toByteArray())
            }
        }
        RootShell.checkModule(log("proof.log"), 500)
        val destination = log("readback.img")
        val result = RootShell.capture("read", destination, log("read.log"), 1)
        assertArrayEquals(bytes, destination.readBytes())
        assertEquals("dd notice\n", result.output)
    }

    @Test fun failedModuleRecheckRevokesOnlyTheModuleProof() {
        val commands = mutableListOf<String>()
        var uid = "0"
        RootShell.configureModuleProvider {
            RootProcessProvider { FixtureProcess(uid.toByteArray()) }
        }
        RootShell.checkModule(log("module.log"), 500)
        RootShell.setIntegratedProvider(recording("integrated", commands), log("integrated.log"))
        RootShell.setWirelessProvider(recording("wireless", commands), log("wireless.log"))
        uid = "2000"
        assertNotEquals(0, RootShell.checkModule(log("revoked.log"), 500)?.exitCode)
        assertFalse(RootShell.hasModuleProvider())
        assertTrue(RootShell.hasIntegratedProvider())
        assertTrue(RootShell.hasWirelessProvider())
        assertEquals("wireless", RootShell.run("task", log("task.log")).output)
        assertEquals(listOf("integrated:id -u", "wireless:id -u", "wireless:task"), commands)
    }

    @Test fun unrecordableProofIsNotPublishedAndDoesNotReplaceAnotherChannel() {
        val commands = mutableListOf<String>()
        RootShell.setIntegratedProvider(recording("integrated", commands), log("integrated.log"))
        RootShell.configureModuleProvider { recording("module", commands) }
        assertTrue(runCatching { RootShell.checkModule(temporary.root, 500) }.isFailure)
        assertFalse(RootShell.hasModuleProvider())
        assertTrue(RootShell.hasIntegratedProvider())
        assertEquals("integrated", RootShell.run("task", log("task.log")).output)
        assertEquals(listOf("integrated:id -u", "module:id -u", "integrated:task"), commands)
    }

    @Test fun nativeClientUsesArgumentBoundariesAndDiscoveryStartsNoProcess() {
        val client = temporary.newFile("client.exe")
        client.setExecutable(true)
        val socket = temporary.newFile("root.sock")
        val launches = mutableListOf<List<String>>()
        val candidate = checkNotNull(ModuleRootAccess.candidate(client, socket) { argv ->
            launches += argv
            FixtureProcess(ByteArray(0))
        })
        assertTrue(launches.isEmpty())
        val command = "printf '%s' 'space and quote'\n# Unicode: \u8363\u8000"
        candidate.start(command)
        assertEquals(listOf(listOf(client.absolutePath, socket.absolutePath, "-c", command)), launches)
        assertNull(ModuleRootAccess.candidate(client, File(temporary.root, "missing.sock")))
        assertNull(ModuleRootAccess.candidate(File(temporary.root, "missing-client"), socket))
    }

    private fun log(name: String) = File(temporary.root, name)

    private fun recording(name: String, commands: MutableList<String>) = RootProcessProvider { command ->
        commands += "$name:$command"
        FixtureProcess((if (command == "id -u") "0\n" else name).toByteArray())
    }

    private class FixtureProcess(
        stdout: ByteArray,
        stderr: ByteArray = ByteArray(0),
        private val exit: Int = 0,
    ) : Process() {
        private val input = ByteArrayInputStream(stdout)
        private val error = ByteArrayInputStream(stderr)
        private val output = ByteArrayOutputStream()
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun getOutputStream() = output
        override fun waitFor() = exit
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = exit
        override fun destroy() = Unit
        override fun isAlive() = false
    }
}
