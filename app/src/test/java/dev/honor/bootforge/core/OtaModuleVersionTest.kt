package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object OtaModuleVersionFixtures {
    fun properties(code: String = "65", version: String = "2.7.4") =
        "id=hnubl_ota\nname=OTA fixture\nversion=$version\nversionCode=$code\nauthor=fixture\n"
    fun zip(properties: String = properties(), entryName: String = "module.prop"): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { archive ->
                archive.putNextEntry(ZipEntry(entryName).apply { time = 0 })
                archive.write(properties.toByteArray())
                archive.closeEntry()
            }
            output.toByteArray()
        }
    fun query(installed: String? = null, pending: String? = null): String = buildString {
        append("HNUBL_MODULE_QUERY_V1\n")
        for ((location, metadata) in listOf("installed" to installed, "pending" to pending)) {
            append("HNUBL_MODULE_BEGIN=$location\n")
            append("HNUBL_MODULE_STATE=${if (metadata == null) "absent" else "present"}\n")
            if (metadata != null) append(metadata).append('\n')
            append("HNUBL_MODULE_END=$location\n")
        }
        append("HNUBL_MODULE_QUERY_END\n")
    }
    fun response(command: String, versions: String = query()) = when (command) {
        OtaUpdatePreparation.VERSION_QUERY_COMMAND -> ShellResult(0, versions)
        OtaUpdatePreparation.CHECK_COMMAND -> ShellResult(0, "HNUBL_KSUD_READY=1\n")
        else -> ShellResult(0, "installed")
    }
}

class OtaModuleVersionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val f = OtaModuleVersionFixtures
    private val rootProof = { _: File -> ShellResult(0, "ROOT_UID=0\nROOT_PROVIDER=module\n") }
    private fun runCase(versions: ShellResult, bytes: ByteArray = f.zip(), calls: MutableList<String>): OtaUpdatePreparation.Result {
        val archive = temporary.newFile().apply { writeBytes(bytes) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return OtaUpdatePreparation.execute(temporary.newFolder(), archive, hash, checkRoot = rootProof,
            runRoot = { command, _ -> calls += command
                if (command == OtaUpdatePreparation.VERSION_QUERY_COMMAND) versions else f.response(command) },
            activate = { _, _ -> calls += "activate"; OtaUpdatePreparation.Activation(true, "active", emptyList()) })
    }

    @Test fun lowerInstalledOrPendingVersionsInstallNewerBundleExactlyOnce() {
        for (versions in listOf(f.query(f.properties("64")), f.query(pending = f.properties("63")),
            f.query(f.properties("63"), f.properties("64")))) {
            val calls = mutableListOf<String>()
            val result = runCase(ShellResult(0, versions), calls = calls)
            assertTrue(result.successful)
            assertEquals(1, calls.count { it.startsWith("/data/adb/ksud module install ") })
            assertEquals(1, calls.count { it == "activate" })
            assertTrue(result.logs.any { it.contains("内置模块版本较新") })
        }
    }

    @Test fun equalOrNewerInstalledVersionDoesNotInstallOrActivate() {
        for (code in listOf("65", "66", "999999999999999999999999999999999999999")) {
            val calls = mutableListOf<String>()
            val result = runCase(ShellResult(0, f.query(f.properties(code))), calls = calls)
            assertTrue(result.successful)
            assertEquals("已安装最新版模块，无需更新", result.message)
            assertEquals(listOf(OtaUpdatePreparation.VERSION_QUERY_COMMAND), calls)
        }
    }

    @Test fun pendingSameOrNewerVersionIsIncludedAndNeverDowngraded() {
        for (versions in listOf(f.query(f.properties("64"), f.properties("65")), f.query(f.properties("65"), f.properties("66")),
            f.query(pending = f.properties("66")))) {
            val calls = mutableListOf<String>()
            val result = runCase(ShellResult(0, versions), calls = calls)
            assertTrue(result.successful)
            assertEquals(OtaUpdatePreparation.LATEST_MESSAGE, result.message)
            assertTrue(result.logs.any { it.contains("待更新目录") })
            assertEquals(listOf(OtaUpdatePreparation.VERSION_QUERY_COMMAND), calls)
        }
    }

    @Test fun olderPendingModuleDoesNotCauseDowngradeOfNewerInstalledModule() {
        val calls = mutableListOf<String>()
        val result = runCase(ShellResult(0, f.query(f.properties("66"), f.properties("64"))), calls = calls)
        assertTrue(result.successful)
        assertEquals(OtaUpdatePreparation.LATEST_MESSAGE, result.message)
        assertTrue(result.logs.any { it.contains("避免下次重启后降级") })
        assertEquals(listOf(OtaUpdatePreparation.VERSION_QUERY_COMMAND), calls)
    }

    @Test fun zipModuleVersionRatherThanAppVersionDeterminesTheDecision() {
        val calls = mutableListOf<String>()
        val result = runCase(ShellResult(0, f.query(f.properties("313", "old"))),
            f.zip(f.properties("314", "independent-module-release")), calls)
        assertTrue(result.successful)
        assertTrue(calls.any { it.startsWith("/data/adb/ksud module install ") })
        assertTrue(result.logs.any { it.contains("independent-module-release") && it.contains("314") })
    }

    @Test fun invalidExistingMetadataDoesNotReportLatestOrOverwrite() {
        val bad = listOf(f.properties().replace("versionCode=65", "versionCode=wrong"),
            f.properties().replace("versionCode=65", "versionCode=-1"), f.properties().replace("versionCode=65\n", ""),
            f.properties() + "versionCode=66\n", f.properties().replace("id=hnubl_ota", "id=another_module"), "")
        for (metadata in bad) for (versions in listOf(f.query(metadata), f.query(f.properties("64"), metadata))) {
            val calls = mutableListOf<String>()
            val result = runCase(ShellResult(0, versions), calls = calls)
            assertFalse(result.successful)
            assertFalse(result.message == OtaUpdatePreparation.LATEST_MESSAGE)
            assertEquals(listOf(OtaUpdatePreparation.VERSION_QUERY_COMMAND), calls)
        }
    }

    @Test fun failedTruncatedOrUnframedVersionQueryNeverInstalls() {
        for (queryResult in listOf(ShellResult(1, "permission/read failed"),
            ShellResult(0, f.query().removeSuffix("HNUBL_MODULE_QUERY_END\n")),
            ShellResult(0, ""), ShellResult(0, "noise\n" + f.query()))) {
            val calls = mutableListOf<String>()
            val result = runCase(queryResult, calls = calls)
            assertFalse(result.successful)
            assertFalse(result.message == OtaUpdatePreparation.LATEST_MESSAGE)
            assertEquals(listOf(OtaUpdatePreparation.VERSION_QUERY_COMMAND), calls)
        }
    }

    @Test fun literalPropertyParsingSupportsBomCrLfWhitespaceAndLargeDecimalCodes() {
        val version = OtaUpdatePreparation.parseVersion("\uFEFF# fixture\r\n id = hnubl_ota \r\n" +
            "version = human name\r\n versionCode = 000999999999999999999999 \r\n", "fixture")
        assertEquals("human name", version.version)
        assertEquals(BigInteger("999999999999999999999"), version.versionCode)
        assertEquals(2, OtaUpdatePreparation.parseInstalledVersions(
            f.query(f.properties("64"), f.properties("65")).replace("\n", "\r\n")).size)
    }

    @Test fun invalidBundledModuleMetadataOrZipNeverRequestsRoot() {
        for (bytes in listOf("not a zip".toByteArray(), f.zip(entryName = "nested/module.prop"),
            f.zip(f.properties("bad")), f.zip(f.properties().replace("id=hnubl_ota", "id=wrong")))) {
            val archive = temporary.newFile().apply { writeBytes(bytes) }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val result = OtaUpdatePreparation.execute(temporary.newFolder(), archive, hash,
                checkRoot = { error("Unexpected root request") }, runRoot = { _, _ -> error("Unexpected command") })
            assertFalse(result.successful)
        }
    }
}
