package dev.honor.bootforge.core

import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** On-demand, read-only snapshot. Missing root evidence never changes OTA state. */
internal object OtaDiagnosticsArchive {
    val rootFiles = listOf(
        "/data/adb/hnubl/freeotaok",
        "/data/adb/hnubl/deferred-slot.json",
        "/data/adb/hnubl/status.json",
        "/data/adb/hnubl/discovery.json",
        "/data/adb/hnubl/ota-options.conf",
        "/data/adb/hnubl/ota-options.active.conf",
        "/data/adb/hnubl/activation.id",
        "/data/adb/hnubl/logs/ota-events.jsonl.1",
        "/data/adb/hnubl/logs/ota-events.jsonl",
        "/data/adb/hnubl/service.log",
        "/data/adb/modules/hnubl_ota/module.prop",
        "/data/adb/modules_update/hnubl_ota/module.prop",
    )

    // No executable paths, arbitrary input, broad directory walks or data writes.
    val snapshotCommand: String = buildString {
        append("hnubl_diag_error=0; printf 'HNUBL_OTA_DIAGNOSTICS_V1\\n'; date; id; ")
        append("printf '\\n=== BOOT ID ===\\n'; cat /proc/sys/kernel/random/boot_id || hnubl_diag_error=1; ")
        for (property in listOf("ro.boot.slot_suffix", "ro.boot.slot", "ro.build.display.id", "ro.build.version.release", "ro.build.version.sdk"))
            append("printf '\\n=== $property ===\\n'; getprop $property || hnubl_diag_error=1; ")
        for (path in rootFiles) {
            append("printf '\\n=== $path ===\\n'; ")
            append("if [ -L '$path' ]; then printf 'SKIPPED: symbolic link\\n'; ")
            append("elif [ -f '$path' ]; then ls -ln '$path'; cat '$path' || hnubl_diag_error=1; ")
            append("else printf 'ABSENT OR UNREADABLE\\n'; fi; ")
        }
        append("printf '\\n=== MARKER PREFIX HEX ===\\n'; ")
        append("if [ -f /data/adb/hnubl/freeotaok ] && [ ! -L /data/adb/hnubl/freeotaok ]; then ")
        append("od -An -tx1 -N16 /data/adb/hnubl/freeotaok || hnubl_diag_error=1; fi; ")
        append("printf '\\n=== TAGGED ANDROID LOGCAT ===\\n'; ")
        append("logcat -d -v threadtime -s 'hnubl-ota:*' 'hnubl-module:*' || hnubl_diag_error=1; ")
        append("printf '\\nHNUBL_OTA_DIAGNOSTICS_END\\n'; exit \"\$hnubl_diag_error\"")
    }

    fun fileName(now: Date = Date(), zone: TimeZone = TimeZone.getDefault()): String =
        "hnubl_ota_logs_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = zone }.format(now) + ".zip"

    data class Result(val archive: File, val rootEvidenceComplete: Boolean)

    private fun completionCycle(snapshot: File): String? {
        if (!snapshot.isFile) return null
        return snapshot.bufferedReader().useLines { lines ->
            var inPlan = false
            var cycle: String? = null
            for (line in lines) {
                if (line == "=== /data/adb/hnubl/deferred-slot.json ===") inPlan = true
                else if (inPlan && line.startsWith("===")) break
                else if (inPlan && line.startsWith("{")) {
                    cycle = runCatching { JSONObject(line).optString("cycle_id") }.getOrNull()
                    break
                }
            }
            cycle?.takeIf { Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(it) }
        }
    }

    fun collect(
        directory: File,
        applicationReport: String,
        console: String,
        requestedAt: Long,
        zone: TimeZone,
        captureRoot: (String, File, File) -> ShellResult,
    ): Result {
        check(directory.isDirectory || directory.mkdirs()) { "创建诊断目录失败" }
        val staging = Files.createTempDirectory(directory.toPath(), "ota-diagnostics-").toFile()
        try {
            val output = File(staging, "root-snapshot.txt")
            val errors = File(staging, "root-capture.stderr.txt")
            var failure = ""
            val exit = try { captureRoot(snapshotCommand, output, errors).exitCode }
            catch (error: Exception) {
                failure = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                -1
            }
            // A partially collected snapshot remains valuable after a timeout or a
            // permission failure. Report it explicitly instead of claiming success.
            var complete = exit == 0 && output.isFile && output.useLines { lines ->
                lines.any { it == "HNUBL_OTA_DIAGNOSTICS_END" }
            }
            var completionEvidence = "no_valid_cycle_in_snapshot"
            // Read only this snapshot's UUID-named evidence, never scan other
            // service files or interpolate an unchecked JSON value into a shell.
            completionCycle(output)?.let { cycle ->
                val path = "/data/misc/update_engine/hnubl-module/completion-$cycle.json"
                val evidence = File(staging, "native-completion.txt")
                val evidenceErrors = File(staging, "native-completion.stderr.txt")
                val command = "hnubl_diag_error=0; printf '=== $path ===\\n'; " +
                    "if [ -L '$path' ]; then printf 'SKIPPED: symbolic link\\n'; " +
                    "elif [ -f '$path' ]; then ls -ln '$path'; cat '$path' || hnubl_diag_error=1; " +
                    "else printf 'ABSENT OR UNREADABLE\\n'; fi; printf '\\nHNUBL_OTA_COMPLETION_END\\n'; exit \"\$hnubl_diag_error\""
                try {
                    val result = captureRoot(command, evidence, evidenceErrors)
                    val ended = evidence.isFile && evidence.useLines { lines -> lines.any { it == "HNUBL_OTA_COMPLETION_END" } }
                    completionEvidence = if (result.exitCode == 0 && ended) "capture_completed" else "partial"
                    if (completionEvidence == "partial") complete = false
                } catch (error: Exception) {
                    completionEvidence = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                    complete = false
                }
            }
            val metadata = Properties().apply {
                setProperty("schema", "1")
                setProperty("requested_at_epoch_ms", requestedAt.toString())
                setProperty("completed_at_epoch_ms", System.currentTimeMillis().toString())
                setProperty("timezone", zone.id)
                setProperty("root_capture_exit", exit.toString())
                setProperty("root_snapshot_complete", complete.toString())
                setProperty("root_capture_exception", failure)
                setProperty("native_completion_capture", completionEvidence)
            }
            File(staging, "collection.properties").writer(Charsets.UTF_8).use { metadata.store(it, "hnubl OTA diagnostics") }
            File(staging, "application.txt").writeText(applicationReport)
            File(staging, "ota-console.txt").writeText(console)
            File(staging, "README.txt").writeText(
                "hnubl OTA diagnostic snapshot\n" +
                "Collected on request; this action does not change freeotaok, slots or partitions.\n" +
                "root-snapshot.txt contains marker bytes, native plan, discovery/status, module versions, service.log and tagged logcat.\n" +
                "native-completion.txt, when available, contains only the current plan's UUID-named completion evidence.\n" +
                "The private journal is event-driven and rotated (two files, approximately 2 MiB in total).\n" +
                "Look for request_id/attempt_id/diagnostic_id, CONSUME decisions, native DONE acknowledgement and plan phase.\n" +
                "freeotaok=1 is the business permission; pending official actions and their real results are recorded separately.\n" +
                "ABSENT OR UNREADABLE and collection.properties explain missing or partial evidence.\n" +
                "No signing key, authentication token, root payload or partition image is collected.\n"
            )
            val archive = File.createTempFile("hnubl-ota-diagnostics-", ".zip", directory)
            try {
                ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                    for (name in listOf("README.txt", "collection.properties", "application.txt", "ota-console.txt", "root-snapshot.txt", "root-capture.stderr.txt",
                        "native-completion.txt", "native-completion.stderr.txt")) {
                        val input = File(staging, name)
                        if (!input.isFile) continue
                        zip.putNextEntry(ZipEntry(name))
                        input.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                return Result(archive, complete)
            } catch (error: Throwable) { archive.delete(); throw error }
        } finally {
            // Only known files inside this fresh private directory are removed.
            staging.listFiles()?.forEach { it.delete() }
            staging.delete()
        }
    }
}
