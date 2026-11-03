package dev.honor.bootforge.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class RebootCommandsTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun clearProvider() {
        RootShell.configureCustomSuSettings(null)
        RootShell.startSuProcess = { ProcessBuilder(it).start() }
    }

    @Test fun customSuOverridesAReadyWirelessShellAndDoesNotReplayAFailedReboot() {
        RootShell.configureCustomSuSettings { CustomSuSettings(true, "/bin/666") }
        val customCalls = mutableListOf<List<String>>()
        var deny = false
        RootShell.startSuProcess = { argv ->
            customCalls += argv
            ProcessFixture(if (argv.last() == "id -u") "0" else "", if (deny) 9 else 0)
        }
        var wirelessCalls = 0
        val wireless: (String, Long) -> ShellResult = { _, _ -> wirelessCalls++; ShellResult(0, "") }
        assertEquals(0, RebootCommands.request(UnlockCompletion.BOOTLOADER,
            temporary.newFile("custom-bootloader.log"), shellReady = true, shell = wireless).exitCode)
        deny = true
        val error = assertThrows(IllegalStateException::class.java) {
            RebootCommands.request(UnlockCompletion.RECOVERY,
                temporary.newFile("custom-recovery.log"), shellReady = true, shell = wireless)
        }
        assertTrue(error.message.orEmpty().contains("9"))
        assertEquals(0, wirelessCalls)
        assertEquals(listOf(listOf("/bin/666", "-c", "id -u"),
            listOf("/bin/666", "-c", "reboot bootloader"),
            listOf("/bin/666", "-c", "reboot recovery")), customCalls)
    }

    @Test fun absentWirelessShellUsesTheRegisteredRootProviderWithoutSuOrAnotherProbe() {
        val commands = mutableListOf<String>()
        RootShell.setIntegratedProvider(RootProcessProvider { command ->
            commands += command
            ProcessFixture(if (command == "id -u") "0\n" else "")
        }, temporary.newFile("root.log"))
        assertEquals(0, RebootCommands.request(UnlockCompletion.BOOTLOADER, temporary.newFile("bootloader.log")).exitCode)
        assertEquals(0, RebootCommands.request(UnlockCompletion.RECOVERY, temporary.newFile("recovery.log")).exitCode)
        assertEquals(listOf("id -u", "reboot bootloader", "reboot recovery"), commands)
    }

    @Test fun aReadyWirelessShellReceivesBothCommandsWithoutEnteringAnyRootChannel() {
        val rootCommands = mutableListOf<String>()
        RootShell.setIntegratedProvider(RootProcessProvider { command ->
            rootCommands += command
            ProcessFixture("0")
        }, temporary.newFile("root.log"))
        val shellCommands = mutableListOf<String>()
        UnlockCompletion.entries.forEach { completion ->
            val result = RebootCommands.request(completion, temporary.newFile("${completion.name}.log"),
                timeoutSeconds = 17, shellReady = true, shell = { command, timeout ->
                    shellCommands += command
                    assertEquals(17L, timeout)
                    ShellResult(0, "")
                })
            assertEquals(0, result.exitCode)
        }
        assertEquals(listOf("reboot bootloader", "reboot recovery"), shellCommands)
        assertEquals(listOf("id -u"), rootCommands)
    }

    @Test fun aNonreadyWirelessChannelIsNotSentACommand() {
        val rootCommands = mutableListOf<String>()
        RootShell.setIntegratedProvider(RootProcessProvider { command ->
            rootCommands += command
            ProcessFixture(if (command == "id -u") "0" else "")
        }, temporary.newFile("root.log"))
        assertEquals(0, RebootCommands.request(UnlockCompletion.RECOVERY, temporary.newFile("reboot.log"),
            shellReady = false, shell = { _, _ -> error("Wireless Shell must stay unused") }).exitCode)
        assertEquals(listOf("id -u", "reboot recovery"), rootCommands)
    }

    @Test fun wirelessFailureNeverReplaysTheRebootOverRootOrSu() {
        val rootCommands = mutableListOf<String>()
        RootShell.setIntegratedProvider(RootProcessProvider { command ->
            rootCommands += command
            ProcessFixture("0")
        }, temporary.newFile("root.log"))
        val shellCommands = mutableListOf<String>()
        val denied = assertThrows(IllegalStateException::class.java) {
            RebootCommands.request(UnlockCompletion.BOOTLOADER, temporary.newFile("denied.log"),
                shellReady = true, shell = { command, _ ->
                    shellCommands += command
                    ShellResult(13, "Shell permission denied")
                })
        }
        assertTrue(denied.message.orEmpty().contains("13"))
        assertTrue(denied.message.orEmpty().contains("Shell permission denied"))
        assertThrows(java.io.IOException::class.java) {
            RebootCommands.request(UnlockCompletion.RECOVERY, temporary.newFile("disconnected.log"),
                shellReady = true, shell = { command, _ ->
                    shellCommands += command
                    throw java.io.IOException("Shell disconnected after dispatch")
                })
        }
        assertEquals(listOf("reboot bootloader", "reboot recovery"), shellCommands)
        assertEquals(listOf("id -u"), rootCommands)
    }

    @Test fun aDeniedCommandIsAnExplicitFailureAndIsNeverRetriedThroughSu() {
        val commands = mutableListOf<String>()
        RootShell.setIntegratedProvider(RootProcessProvider { command ->
            commands += command
            if (command == "id -u") ProcessFixture("0") else ProcessFixture("permission denied", exitCode = 13)
        }, temporary.newFile("root.log"))
        val failure = assertThrows(IllegalStateException::class.java) {
            RebootCommands.request(UnlockCompletion.RECOVERY, temporary.newFile("recovery.log"))
        }
        assertTrue(failure.message.orEmpty().contains("13"))
        assertTrue(failure.message.orEmpty().contains("permission denied"))
        assertEquals(listOf("id -u", "reboot recovery"), commands)
    }

    @Test fun aDisconnectedShellPropagatesFailureWithoutReportingAccepted() {
        val commands = mutableListOf<String>()
        RootShell.setIntegratedProvider(RootProcessProvider { command ->
            commands += command
            if (command == "id -u") ProcessFixture("0") else throw java.io.IOException("Shell disconnected")
        }, temporary.newFile("root.log"))
        val failure = assertThrows(java.io.IOException::class.java) {
            RebootCommands.request(UnlockCompletion.BOOTLOADER, temporary.newFile("bootloader.log"))
        }
        assertEquals("Shell disconnected", failure.message)
        assertEquals(listOf("id -u", "reboot bootloader"), commands)
    }

    private class ProcessFixture(private val output: String, private val exitCode: Int = 0) : Process() {
        override fun getInputStream() = ByteArrayInputStream(output.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = exitCode
        override fun destroy() = Unit
        override fun isAlive() = false
    }
}
