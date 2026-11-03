package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.json.JSONObject

class OtaUpdatePreparationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = OtaModuleVersionFixtures.zip()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun archive(): File = File(File(temporary.newFolder(), "folder with ' quote").apply { mkdirs() }, "module ' test.zip")
        .apply { writeBytes(payload) }
    private val rootProof = { _: File -> ShellResult(0, "ROOT_UID=0\nROOT_PROVIDER=integrated\n") }
    private val readyActivation = { _: File, _: (String, File) -> ShellResult ->
        OtaUpdatePreparation.Activation(true, "active", listOf("activation verified"))
    }

    @Test fun verifiedBundleInstallsOnceWithQuotedPathThenActivates() {
        val file = archive()
        val calls = mutableListOf<String>()
        val result = OtaUpdatePreparation.execute(temporary.newFolder(), file, hash(payload),
            checkRoot = { calls += "root-proof"; rootProof(it) },
            runRoot = { command, _ -> calls += command; OtaModuleVersionFixtures.response(command) },
            activate = { _, _ -> calls += "activate"; readyActivation(file) { _, _ -> error("unused") } })
        assertTrue(result.successful)
        assertEquals(listOf("root-proof", OtaUpdatePreparation.VERSION_QUERY_COMMAND, OtaUpdatePreparation.CHECK_COMMAND,
            "/data/adb/ksud module install ${shellQuote(file.canonicalPath)}", "activate"), calls)
        assertTrue(result.logs.contains("activation verified"))
        assertFalse(calls.any { it.contains("reboot") || it.startsWith("setprop ") })
    }

    @Test fun modifiedBundleNeverRequestsRootOrRunsAnInstaller() {
        var rootCalls = 0
        var commandCalls = 0
        val file = archive().apply { appendText("modified") }
        val result = OtaUpdatePreparation.execute(temporary.newFolder(), file, hash(payload),
            checkRoot = { rootCalls++; rootProof(it) },
            runRoot = { _, _ -> commandCalls++; error("Unexpected command") }, activate = readyActivation)
        assertFalse(result.successful)
        assertEquals(0, rootCalls)
        assertEquals(0, commandCalls)
    }

    @Test fun stagedAssetsAreHashCheckedBeforeReplacingThePrivateArchive() {
        val directory = temporary.newFolder()
        val previous = File(directory, "hnubl-ota.zip").apply { writeText("previous") }
        val assets = mapOf(OtaUpdatePreparation.MODULE_ASSET to payload,
            OtaUpdatePreparation.HASH_ASSET to "${hash(payload)}  hnubl-ota.zip\n".toByteArray())
        val staged = OtaUpdatePreparation.stage(directory) { ByteArrayInputStream(assets.getValue(it)) }
        assertArrayEquals(payload, staged.archive.readBytes())
        assertEquals(hash(payload), staged.sha256)
        val bad = assets + (OtaUpdatePreparation.MODULE_ASSET to "bad".toByteArray())
        assertThrows(IllegalStateException::class.java) {
            OtaUpdatePreparation.stage(directory) { ByteArrayInputStream(bad.getValue(it)) }
        }
        assertArrayEquals(payload, previous.readBytes())
        assertEquals(listOf("hnubl-ota.zip"), directory.list()!!.toList())
    }

    @Test fun deniedRootOrMissingUidZeroProofNeverRunsTheInstaller() {
        for (proof in listOf(ShellResult(1, "permission denied"), ShellResult(0, "ROOT_UID=2000\n"))) {
            var commands = 0
            val result = OtaUpdatePreparation.execute(temporary.newFolder(), archive(), hash(payload),
                checkRoot = { proof }, runRoot = { _, _ -> commands++; error("Unexpected command") },
                activate = readyActivation)
            assertFalse(result.successful)
            assertEquals(0, commands)
        }
    }

    @Test fun missingInstallerOrMissingReadinessProofNeverInstallsOrActivates() {
        for (check in listOf(ShellResult(127, "missing"), ShellResult(0, ""))) {
            val commands = mutableListOf<String>()
            var activations = 0
            val result = OtaUpdatePreparation.execute(temporary.newFolder(), archive(), hash(payload),
                checkRoot = rootProof,
                runRoot = { command, _ -> commands += command
                    if (command == OtaUpdatePreparation.VERSION_QUERY_COMMAND) OtaModuleVersionFixtures.response(command) else check },
                activate = { _, _ -> activations++; error("Unexpected activation") })
            assertFalse(result.successful)
            assertEquals(listOf(OtaUpdatePreparation.VERSION_QUERY_COMMAND, OtaUpdatePreparation.CHECK_COMMAND), commands)
            assertEquals(0, activations)
        }
    }

    @Test fun installationFailureIsNotRetriedOrActivated() {
        val commands = mutableListOf<String>()
        var activations = 0
        val result = OtaUpdatePreparation.execute(temporary.newFolder(), archive(), hash(payload),
            checkRoot = rootProof,
            runRoot = { command, _ -> commands += command
                if (command == OtaUpdatePreparation.CHECK_COMMAND || command == OtaUpdatePreparation.VERSION_QUERY_COMMAND)
                    OtaModuleVersionFixtures.response(command)
                else ShellResult(1, "install failed") },
            activate = { _, _ -> activations++; error("Unexpected activation") })
        assertFalse(result.successful)
        assertEquals(3, commands.size)
        assertEquals(0, activations)
        assertTrue(result.logs.any { it.contains("install failed") })
    }

    @Test fun installedModuleIsNotReportedActiveUntilActivationIsConfirmed() {
        val result = OtaUpdatePreparation.execute(temporary.newFolder(), archive(), hash(payload),
            checkRoot = rootProof,
            runRoot = { command, _ -> OtaModuleVersionFixtures.response(command) },
            activate = { _, _ -> OtaUpdatePreparation.Activation(false, "still waiting for hook", listOf("not ready")) })
        assertFalse(result.successful)
        assertEquals("still waiting for hook", result.message)
        assertTrue(result.logs.contains("not ready"))
    }

    @Test fun activationRequiresBothSuccessfulCommandAndReadyStatus() {
        for ((exit, ready, expected) in listOf(Triple(0, true, true), Triple(0, false, false), Triple(1, true, false))) {
            val commands = mutableListOf<String>()
            val result = OtaUpdatePreparation.activateInstalled(temporary.newFolder(),
                runRoot = { command, _ -> commands += command; ShellResult(exit, "status document") },
                readReadiness = { assertEquals("status document", it); OtaUpdatePreparation.ActivationReadiness(ready) })
            assertEquals(expected, result.active)
            assertEquals(listOf(OtaUpdatePreparation.ACTIVATE_COMMAND), commands)
        }
    }

    @Test fun malformedStatusAndActivationExceptionsAreReportedWithoutFakeSuccess() {
        val invalid = OtaUpdatePreparation.activateInstalled(temporary.newFolder(),
            runRoot = { _, _ -> ShellResult(0, "invalid json") }, readReadiness = { error("malformed") })
        assertFalse(invalid.active)
        val failed = OtaUpdatePreparation.activateInstalled(temporary.newFolder(),
            runRoot = { _, _ -> error("launch failed") })
        assertFalse(failed.active)
        assertTrue(failed.logs.any { it.contains("launch failed") })
    }

    @Test fun matchingActiveAndIdleControllerCompleteActivationWithDifferentMessages() {
        for (idle in listOf(false, true)) {
            val result = OtaUpdatePreparation.activateInstalled(temporary.newFolder(),
                runRoot = { _, _ -> ShellResult(0, activationOutput(idle = idle)) })
            assertTrue(result.active)
            assertTrue(result.message.contains(if (idle) "下次打开软件更新时生效" else "软件更新保护已就绪"))
        }
    }

    @Test fun staleUnboundOrErrorStatusNeverCompletesActivation() {
        val valid = activationOutput()
        val cases = listOf(
            valid.substringAfter('\n'), // An unbound ready status is not this request's proof.
            valid.replace("HNUBL_ACTIVATION_ID=new-id", "HNUBL_ACTIVATION_ID=other-id"),
            activationOutput(change = { put("activation_id", "old-id") }),
            activationOutput(change = { put("error", "Broker endpoint is unavailable") }),
            activationOutput(change = { remove("error") }),
            activationOutput(change = { put("error", false) }),
        )
        for (output in cases) {
            val result = OtaUpdatePreparation.activateInstalled(temporary.newFolder(),
                runRoot = { _, _ -> ShellResult(0, output) })
            assertFalse(output, result.active)
        }
    }

    @Test fun malformedOrTruthyProofDoesNotMeetReadiness() {
        val invalid = mutableListOf("invalid json", "HNUBL_ACTIVATION_ID=new-id\nnot-json")
        for (field in listOf("controller_ready", "critical_ready", "broker_ready", "hook_ready", "updater_running")) {
            invalid += activationOutput(change = { remove(field) })
            invalid += activationOutput(change = { put(field, "true") })
            invalid += activationOutput(change = { put(field, false) })
        }
        invalid += activationOutput(idle = true, change = { put("broker_ready", false) })
        invalid += activationOutput(idle = true, change = { put("waiting_for_updater", "true") })
        invalid += activationOutput(idle = true, change = { put("hook_ready", true) })
        invalid += activationOutput(change = { put("waiting_for_updater", true) })
        for (output in invalid) {
            assertFalse(output, OtaUpdatePreparation.activateInstalled(temporary.newFolder(),
                runRoot = { _, _ -> ShellResult(0, output) }).active)
        }
    }

    @Test fun optionalWarningsPreserveConfirmedCriticalProtection() {
        val output = activationOutput(change = { put("warnings", listOf("Optional version filter not installed")) })
        val result = OtaUpdatePreparation.activateInstalled(temporary.newFolder(),
            runRoot = { _, _ -> ShellResult(0, output) })
        assertTrue(result.active)
        assertTrue(result.logs.any { it.contains("Optional version filter not installed") })
        assertFalse(OtaUpdatePreparation.activateInstalled(temporary.newFolder(),
            runRoot = { _, _ -> ShellResult(1, output) }).active)
    }

    @Test fun installationPublishesStagesBeforeLongActionsAndDoesNotDuplicateRetainedLogs() {
        val observed = mutableListOf<String>()
        val file = archive()
        val result = OtaUpdatePreparation.execute(temporary.newFolder(), file, hash(payload),
            checkRoot = {
                assertTrue(observed.last().contains("正在检查 Root"))
                rootProof(it)
            },
            runRoot = { command, _ ->
                when (command) {
                    OtaUpdatePreparation.VERSION_QUERY_COMMAND -> assertTrue(observed.last().contains("正在读取"))
                    OtaUpdatePreparation.CHECK_COMMAND -> assertTrue(observed.last().contains("正在检查 KernelSU"))
                    else -> assertTrue(observed.last().contains("正在安装模块"))
                }
                OtaModuleVersionFixtures.response(command)
            },
            activate = { _, _ ->
                assertTrue(observed.last().contains("正在即时启用"))
                OtaUpdatePreparation.Activation(true, "active", listOf("activation evidence"))
            }, onLog = { observed += it })
        assertTrue(result.successful)
        assertEquals(result.logs, observed)
        assertEquals(1, observed.count { it == "activation evidence" })
    }

    @Test fun observerFailureCannotReplayOrAbortAnInstaller() {
        val calls = mutableListOf<String>()
        val file = archive()
        val result = OtaUpdatePreparation.execute(temporary.newFolder(), file, hash(payload),
            checkRoot = rootProof,
            runRoot = { command, _ -> calls += command; OtaModuleVersionFixtures.response(command) },
            activate = readyActivation, onLog = { throw IllegalStateException("view observer disposed") })
        assertTrue(result.successful)
        assertEquals(1, calls.count { it.startsWith("/data/adb/ksud module install ") })
        assertTrue(result.logs.any { it.contains("安装模块") })
    }

    private fun activationOutput(idle: Boolean = false, change: JSONObject.() -> Unit = {}): String =
        OtaUpdatePreparation.ACTIVATION_ID_PREFIX + "new-id\n" + JSONObject()
            .put("activation_id", "new-id").put("controller_ready", true).put("broker_ready", true)
            .put("critical_ready", !idle).put("updater_running", !idle).put("hook_ready", !idle)
            .put("waiting_for_updater", idle).put("error", "").apply(change).toString()
}
