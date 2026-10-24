package dev.honor.bootforge

import android.content.Context
import dev.honor.bootforge.core.Payloads
import dev.honor.bootforge.core.Probe
import dev.honor.bootforge.core.RootShell
import dev.honor.bootforge.core.scriptCommand
import dev.honor.bootforge.core.shellQuote
import java.io.File
import java.util.UUID

data class PreparedJob(
    val directory: String,
    val manifestSha256: String,
    val slot: String,
    val payloadManifestSha256: String,
    val engineSha256: String,
    val archive: File,
)

/** RAM backups are copied into app storage before any partition mutation. */
internal object JobBackups {
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val jobPattern = Regex("/dev/bootforge-[A-Za-z0-9._-]+")
    fun archiveFile(context: Context, basename: String): File =
        File(File(context.filesDir, "backups").apply { mkdirs() }, "$basename-backup.tar")

    fun load(context: Context): PreparedJob? {
        val prefs = context.getSharedPreferences("prepared_job", 0)
        val directory = prefs.getString("directory", "").orEmpty()
        val hash = prefs.getString("manifest_sha256", "").orEmpty()
        val slot = prefs.getString("slot", "").orEmpty()
        if (!jobPattern.matches(directory) || !hashPattern.matches(hash) || slot !in listOf("a", "b")) return null
        return PreparedJob(directory, hash, slot, prefs.getString("payload_manifest_sha256", "").orEmpty(), prefs.getString("engine_sha256", "").orEmpty(),
            archiveFile(context, directory.substringAfterLast('/')))
    }

    fun prepare(context: Context, directory: File): PreparedJob {
        val result = RootShell.run(scriptCommand(File(directory, "online-create.sh").absolutePath,
            "--execute", listOf("--prepare")), File(context.filesDir, "prepare.log"), 120)
        val info = Probe.parse(result.output)
        check(result.exitCode == 0 && info.values["BACKUP_READY"] == "1") { result.output }
        val jobDirectory = info.values["JOB_DIR"].orEmpty()
        val hash = info.values["MANIFEST_SHA256"].orEmpty()
        check(jobPattern.matches(jobDirectory) && hashPattern.matches(hash) && info.slot.isNotEmpty()) { "RAM 备份任务信息无效" }
        val job = PreparedJob(jobDirectory, hash, info.slot, Payloads.sha256(File(directory, "payloads.sha256")), Payloads.engineHash(directory),
            archiveFile(context, jobDirectory.substringAfterLast('/')))
        check(context.getSharedPreferences("prepared_job", 0).edit()
            .putString("directory", job.directory).putString("manifest_sha256", job.manifestSha256)
            .putString("slot", job.slot).putString("payload_manifest_sha256", job.payloadManifestSha256)
            .putString("engine_sha256", job.engineSha256).commit()) { "备份任务记录保存失败" }
        return job
    }

    fun present(context: Context, job: PreparedJob): Boolean {
        val result = RootShell.run("if [ -d ${shellQuote(job.directory)} ]; then printf 'JOB_EXISTS=1\\n'; else printf 'JOB_EXISTS=0\\n'; fi",
            File(context.filesDir, "prepared-presence.log"), 30)
        val value = Probe.parse(result.output).values["JOB_EXISTS"]
        check(result.exitCode == 0 && value in listOf("0", "1")) { "读取原 RAM 任务状态失败\n${result.output}" }
        if (value == "0") clear(context)
        return value == "1"
    }

    fun save(context: Context, directory: File, job: PreparedJob) {
        saveDirectory(context, directory, job.directory, job.archive)
    }

    fun started(context: Context, job: PreparedJob): Boolean {
        val journal = shellQuote("${job.directory}/journal.txt")
        val result = RootShell.run("if grep -qx 'STATE=execution-start' $journal; then printf 'JOB_STARTED=1\\n'; " +
            "elif [ -f $journal ]; then printf 'JOB_STARTED=0\\n'; else exit 1; fi",
            File(context.filesDir, "prepared-state.log"), 30)
        val value = Probe.parse(result.output).values["JOB_STARTED"]
        check(result.exitCode == 0 && value in listOf("0", "1")) { "读取原任务阶段失败，原日志及备份保留\n${result.output}" }
        return value == "1"
    }

    /** Archive a stopped attempt and release only its validated RAM lock.
     * The next attempt always plans from current direct filesystem/GPT reads. */
    fun releaseForNewPlan(context: Context, directory: File, job: PreparedJob): String {
        if (!started(context, job)) {
            cancel(context, directory, job)
            return "原准备任务已取消，下次按当前实际布局重新规划"
        }
        val evidence = File(job.archive.parentFile, "${job.directory.substringAfterLast('/')}-interrupted-${UUID.randomUUID()}.tar")
        check(!evidence.exists()) { "中断任务证据文件已存在" }
        saveDirectory(context, directory, job.directory, evidence)
        val archiveHash = Payloads.sha256(evidence)
        val result = RootShell.run(scriptCommand(File(directory, "online-create.sh").absolutePath,
            "--execute", listOf("--retire-interrupted", job.directory,
                "--backup-saved", job.manifestSha256, "--evidence-saved", archiveHash)),
            File(context.filesDir, "retire-interrupted.log"), 120)
        check(result.exitCode == 0 && Probe.parse(result.output).values["RESULT"] == "retired") {
            "原任务状态尚未通过核对，证据已保存在 ${evidence.name}\n${result.output}"
        }
        clear(context)
        return "原中断任务及备份已保留：${evidence.name}；将按当前 GPT 和 F2FS 从头规划"
    }

    fun saveDirectory(context: Context, directory: File, sourceDirectory: String, destination: File) {
        val arguments = "-cf - -C ${shellQuote(sourceDirectory)} ."
        val errorLog = File(context.filesDir, "backup-copy.log")
        var result = RootShell.capture("tar $arguments", destination, errorLog, 120)
        if (result.exitCode != 0) {
            result = RootShell.capture("${shellQuote(File(directory, "tools/busybox").absolutePath)} tar $arguments",
                destination, errorLog, 120)
        }
        check(result.exitCode == 0 && destination.length() > 0) { "备份副本保存失败：${result.output}" }
        java.io.RandomAccessFile(destination, "rw").use { it.fd.sync() }
    }

    fun cancel(context: Context, directory: File, job: PreparedJob) {
        val result = RootShell.run(scriptCommand(File(directory, "online-create.sh").absolutePath,
            "--execute", listOf("--cancel-prepared", job.directory, "--backup-saved", job.manifestSha256)),
            File(context.filesDir, "cancel-prepared.log"), 60)
        check(result.exitCode == 0 && Probe.parse(result.output).values["RESULT"] == "cancelled") {
            "原任务已开始或状态有变化，请保留备份并查看日志\n${result.output}"
        }
        clear(context)
    }

    fun clear(context: Context) {
        context.getSharedPreferences("prepared_job", 0).edit().clear().apply()
    }
}
