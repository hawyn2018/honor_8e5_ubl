package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SystemUpdaterHandoffTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun log() = File(temporary.newFolder(), "updater-open.log")

    @Test fun verifiedUpdaterComponentOpensOnceWithoutRebootOrMarkerCommands() {
        val commands = mutableListOf<String>()
        val result = SystemUpdaterHandoff.open(log(), { "com.hihonor.ouc/.Main" }) { command, _ ->
            commands += command; ShellResult(0, "Starting: Intent\nStatus: ok\n")
        }
        assertTrue(result.opened)
        assertEquals(listOf("am start -W -n ${shellQuote("com.hihonor.ouc/.Main")}"), commands)
        assertFalse(commands.any { it.contains("reboot") || it.contains("freeotaok") || it.contains("CONSUME") || it.contains("force-stop") })
    }

    @Test fun zeroExitAmErrorUsesPackageBoundSettingsFallback() {
        val commands = mutableListOf<String>()
        val result = SystemUpdaterHandoff.open(log(), { "com.hihonor.ouc/.Removed" }) { command, _ ->
            commands += command
            if (commands.size == 1) ShellResult(0, "Error type 3\nError: Activity class does not exist.\n")
            else ShellResult(0, "Status: ok\n")
        }
        assertTrue(result.opened)
        assertEquals(2, commands.size)
        assertEquals("am start -W -a ${shellQuote(SystemUpdaterHandoff.UPDATE_ACTION)} -p ${shellQuote(SystemUpdaterHandoff.PACKAGE)}", commands.last())
    }

    @Test fun absentLauncherUsesSettingsEntryAndNeverAnUnboundIntent() {
        val commands = mutableListOf<String>()
        val result = SystemUpdaterHandoff.open(log(), { null }) { command, _ -> commands += command; ShellResult(0, "Status: ok") }
        assertTrue(result.opened)
        assertEquals(1, commands.size)
        assertTrue(commands.single().contains(" -p ${shellQuote("com.hihonor.ouc")}"))
        assertFalse(commands.single().contains(" -n "))
    }

    @Test fun otherPackagesAndMalformedComponentsAreNeverDispatched() {
        for (component in listOf("other.package/.Main", "com.hihonor.ouc/.Main;reboot", "com.hihonor.ouc/.Main extra", "com.hihonor.ouc/")) {
            val commands = mutableListOf<String>()
            val result = SystemUpdaterHandoff.open(log(), { component }) { command, _ -> commands += command; ShellResult(0, "Status: ok") }
            assertTrue(result.opened)
            assertEquals(1, commands.size)
            assertFalse(commands.single().contains(" -n "))
        }
    }

    @Test fun resolverAndRootFailuresReturnWarningsAndAllowALaterOpenRetry() {
        var attempts = 0
        val file = log()
        val failed = SystemUpdaterHandoff.open(file, { throw IllegalStateException("PM unavailable") }) { _, _ ->
            attempts++; throw IllegalStateException("root disconnected")
        }
        assertFalse(failed.opened)
        assertEquals(1, attempts)
        assertTrue(failed.logs.any { it.contains("成功结果保留") })
        val retried = SystemUpdaterHandoff.open(file, { null }) { command, _ ->
            attempts++; assertTrue(command.startsWith("am start ")); ShellResult(0, "Status: ok")
        }
        assertTrue(retried.opened)
        assertEquals(2, attempts)
    }

    @Test fun amFailureTextOverridesExitZeroAndAlreadyRunningSuccessIsAccepted() {
        for (output in listOf("Error: missing", "Exception occurred while executing start", "java.lang.SecurityException: denied",
            "Permission Denial: activity", "Status: timeout", "unable to resolve Intent"))
            assertFalse(output, SystemUpdaterHandoff.accepted(ShellResult(0, output)))
        assertFalse(SystemUpdaterHandoff.accepted(ShellResult(1, "Status: ok")))
        assertTrue(SystemUpdaterHandoff.accepted(ShellResult(0, "Warning: Activity not started, intent delivered to top-most instance.\nStatus: ok")))
    }
}
