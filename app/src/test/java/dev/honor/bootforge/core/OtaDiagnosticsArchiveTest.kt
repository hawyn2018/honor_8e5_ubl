package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date
import java.util.Properties
import java.util.TimeZone
import java.util.zip.ZipFile

class OtaDiagnosticsArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun fullEvidencePreservesExactTextAndControlledEntries() {
        var calls = 0
        val result = OtaDiagnosticsArchive.collect(temporary.root, "app=2.9.7\n中文", "DENY: native plan DEFERRED", 123L,
            TimeZone.getTimeZone("Asia/Shanghai")) { command, output, errors ->
            calls++
            assertEquals(OtaDiagnosticsArchive.snapshotCommand, command)
            output.writeText("marker=1\nphase=DEFERRED\n=== /data/adb/hnubl/service.log ===\n[OUC 12267] ready\nHNUBL_OTA_DIAGNOSTICS_END\n")
            errors.writeText("")
            ShellResult(0, "")
        }
        assertTrue(result.rootEvidenceComplete)
        assertEquals(1, calls)
        ZipFile(result.archive).use { zip ->
            assertEquals(setOf("README.txt", "collection.properties", "application.txt", "ota-console.txt",
                "root-snapshot.txt", "root-capture.stderr.txt"), zip.entries().asSequence().map { it.name }.toSet())
            assertEquals("app=2.9.7\n中文", zip.getInputStream(zip.getEntry("application.txt")).reader().readText())
            assertTrue(zip.getInputStream(zip.getEntry("root-snapshot.txt")).reader().readText().contains("[OUC 12267] ready"))
            val metadata = Properties().apply { zip.getInputStream(zip.getEntry("collection.properties")).reader().use { load(it) } }
            assertEquals("123", metadata.getProperty("requested_at_epoch_ms"))
            assertEquals("Asia/Shanghai", metadata.getProperty("timezone"))
            assertEquals("true", metadata.getProperty("root_snapshot_complete"))
        }
        assertEquals(listOf(result.archive), temporary.root.listFiles()!!.toList())
    }

    @Test fun deniedRootStillExportsApplicationEvidenceAndExplainsFailure() {
        val result = OtaDiagnosticsArchive.collect(temporary.root, "app", "console", 1L, TimeZone.getDefault()) { _, _, _ ->
            throw IllegalStateException("Root permission denied")
        }
        assertFalse(result.rootEvidenceComplete)
        ZipFile(result.archive).use { zip ->
            assertNotNull(zip.getEntry("ota-console.txt"))
            val metadata = Properties().apply { zip.getInputStream(zip.getEntry("collection.properties")).reader().use { load(it) } }
            assertEquals("-1", metadata.getProperty("root_capture_exit"))
            assertTrue(metadata.getProperty("root_capture_exception").contains("Root permission denied"))
        }
    }

    @Test fun truncatedAndFailedCaptureRetainsItsPartialEvidence() {
        for ((exit, ended) in listOf(0 to false, 124 to false, 1 to true)) {
            val result = OtaDiagnosticsArchive.collect(temporary.root, "", "", 1L, TimeZone.getDefault()) { _, out, err ->
                out.writeText("last request=DENY; marker=1\n" + if (ended) "HNUBL_OTA_DIAGNOSTICS_END\n" else "")
                err.writeText("transport disconnected\n")
                ShellResult(exit, "")
            }
            assertFalse(result.rootEvidenceComplete)
            ZipFile(result.archive).use { zip ->
                assertTrue(zip.getInputStream(zip.getEntry("root-snapshot.txt")).reader().readText().contains("marker=1"))
                assertEquals("transport disconnected\n", zip.getInputStream(zip.getEntry("root-capture.stderr.txt")).reader().readText())
            }
        }
    }

    @Test fun fileNameUsesRequestedDeviceTimeZoneAndNumericDate() {
        assertEquals("hnubl_ota_logs_19700101_080001.zip", OtaDiagnosticsArchive.fileName(Date(1000), TimeZone.getTimeZone("Asia/Shanghai")))
        assertEquals("hnubl_ota_logs_19700101_000001.zip", OtaDiagnosticsArchive.fileName(Date(1000), TimeZone.getTimeZone("UTC")))
    }

    @Test fun readOnlyCommandContainsNoMutationOrCredentialSources() {
        val command = OtaDiagnosticsArchive.snapshotCommand
        for (mutation in listOf("CONSUME", "RESET", "BEGIN", "setprop", "reboot", "dd ", "chmod", "chown", "su -", "auth.token", "socket.token", "nonce"))
            assertFalse(mutation, command.contains(mutation))
        for (path in OtaDiagnosticsArchive.rootFiles) assertTrue(command.contains("cat '$path'"))
        assertTrue(command.contains("logcat -d"))
        assertTrue(command.contains("|| hnubl_diag_error=1"))
        assertTrue(command.endsWith("exit \"\$hnubl_diag_error\""))
    }

    @Test fun largeSnapshotIsStreamedAndRetainedWithoutTruncation() {
        val size = 3 * 1024 * 1024
        val result = OtaDiagnosticsArchive.collect(temporary.root, "", "", 1L, TimeZone.getDefault()) { _, out, _ ->
            out.outputStream().use { stream ->
                val buffer = ByteArray(8192) { 65 }
                repeat(size / buffer.size) { stream.write(buffer) }
                stream.write("\nHNUBL_OTA_DIAGNOSTICS_END\n".toByteArray())
            }
            ShellResult(0, "")
        }
        assertTrue(result.rootEvidenceComplete)
        ZipFile(result.archive).use { zip ->
            assertTrue(zip.getEntry("root-snapshot.txt").size > size)
            zip.getInputStream(zip.getEntry("root-snapshot.txt")).use { stream ->
                var count = 0L
                val buffer = ByteArray(8192)
                while (true) { val n = stream.read(buffer); if (n < 0) break; count += n }
                assertEquals(zip.getEntry("root-snapshot.txt").size, count)
            }
        }
    }

    @Test fun completionEvidenceUsesOnlyTheExactValidatedPlanCycle() {
        val valid = "12ab4567-1234-1234-1234-123456789abc"
        for (cycle in listOf(valid, "../../credential", "x; reboot", "not-a-cycle")) {
            var calls = 0
            val result = OtaDiagnosticsArchive.collect(temporary.root, "", "", 1L, TimeZone.getDefault()) { command, out, _ ->
                calls++
                if (calls == 1) out.writeText("=== /data/adb/hnubl/deferred-slot.json ===\n" +
                    "{\"cycle_id\":\"$cycle\"}\nHNUBL_OTA_DIAGNOSTICS_END\n")
                else {
                    assertEquals(valid, cycle)
                    assertTrue(command.contains("/data/misc/update_engine/hnubl-module/completion-$valid.json"))
                    assertFalse(command.contains("*"))
                    out.writeText("{\"event\":\"DONE\"}\nHNUBL_OTA_COMPLETION_END\n")
                }
                ShellResult(0, "")
            }
            assertEquals(if (cycle == valid) 2 else 1, calls)
            ZipFile(result.archive).use { zip -> assertEquals(cycle == valid, zip.getEntry("native-completion.txt") != null) }
        }
    }
}
