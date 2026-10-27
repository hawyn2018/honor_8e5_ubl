package dev.honor.bootforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import dev.honor.bootforge.core.UnlockCompletion
import dev.honor.bootforge.core.UnlockRequest
import dev.honor.bootforge.core.Payloads
import dev.honor.bootforge.core.Probe
import dev.honor.bootforge.core.RootShell
import dev.honor.bootforge.core.scriptCommand
import dev.honor.bootforge.core.WorkflowGate
import dev.honor.bootforge.core.WorkflowStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FlashService : Service() {
    companion object {
        private const val EXTRA_REQUEST = "unlock_request"
        fun start(context: Context, request: UnlockRequest) {
            context.startForegroundService(Intent(context, FlashService::class.java)
                .putExtra(EXTRA_REQUEST, request.resolved().encode()))
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        if (!BuildConfig.ALLOW_PARTITION_WRITES || !RunState.state.canExecute || RunState.state.busy) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val request = try { UnlockRequest.decode(checkNotNull(intent?.getStringExtra(EXTRA_REQUEST))).resolved() }
        catch (_: Exception) { stopSelf(startId); return START_NOT_STICKY }
        val completion = request.completion
        val channel = "partition_task"
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(channel, "分区任务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(41, Notification.Builder(this, channel)
            .setSmallIcon(dev.honor.bootforge.R.drawable.ic_launcher).setContentTitle("honor_ubl 正在运行")
            .setContentText("正在检查并写入分区，请保持设备供电")
            .setContentIntent(open).setOngoing(true).build())
        running = true
        RunState.state = RunState.state.copy(busy = true, canExecute = false, canRebootRecovery = false, canRebootBootloader = false, completedRequest = null, recoveryPackageReady = false, stage = "提取 ABL 并生成 efisp，准备工具与备份", progress = 0.1f)
        scope.launch {
            val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BootForge:partition")
            try {
                wake.acquire(2 * 60 * 60 * 1000L)
                val dir = withGenerationLogs { log -> Payloads.stage(this@FlashService, request, onLog = log) }
                val report = withContext(Dispatchers.IO) { RuntimeTools.inspect(this@FlashService, dir) }
                RunState.tools(report)
                RunState.append(report.checks.joinToString("\n") { "${it.label} · ${it.source} · ${it.detail}" })
                check(report.coreReady) { "所需工具有缺失或运行错误，请查看工具警告" }
                val mode = withContext(Dispatchers.IO) { RuntimeTools.mode(this@FlashService) }
                val online = mode == "online-create"
                RunState.nextScript = if (online) "online-create.sh" else "unlock.sh"
                RunState.state = RunState.state.copy(backendMode = mode)
                var prepared = RunState.preparedJob
                if (!online && prepared != null) {
                    val old = prepared
                    if (withContext(Dispatchers.IO) { JobBackups.present(this@FlashService, old) }) {
                        val released = withContext(Dispatchers.IO) { JobBackups.releaseForNewPlan(this@FlashService, dir, old) }
                        RunState.append(released)
                    }
                    RunState.clearPrepared()
                    prepared = null
                }
                if (online) {
                    check(report.onlineReady) { "在线分区工具缺失，请查看工具警告" }
                    val inputHash = withContext(Dispatchers.IO) { Payloads.sha256(File(dir, "payloads.sha256")) }
                    val engineHash = withContext(Dispatchers.IO) { Payloads.engineHash(dir) }
                    val previous = prepared
                    if (previous != null && !withContext(Dispatchers.IO) { JobBackups.present(this@FlashService, previous) }) {
                        RunState.clearPrepared()
                        prepared = null
                    }
                    if (prepared != null && (prepared.payloadManifestSha256 != inputHash || prepared.engineSha256 != engineHash ||
                            withContext(Dispatchers.IO) { JobBackups.started(this@FlashService, checkNotNull(prepared)) })) {
                        val old = prepared
                        val released = withContext(Dispatchers.IO) { JobBackups.releaseForNewPlan(this@FlashService, dir, old) }
                        RunState.append(released)
                        RunState.clearPrepared()
                        prepared = null
                    }
                    if (prepared == null) prepared = withContext(Dispatchers.IO) { JobBackups.prepare(this@FlashService, dir) }
                    val job = checkNotNull(prepared)
                    RunState.preparedJob = job
                    val ablTargets = request.ablTarget.partitionNames(job.slot).joinToString("、")
                    RunState.state = RunState.state.copy(slot = job.slot, stage = "保存 GPT 与 ABL 备份（$ablTargets）", progress = 0.2f)
                    withContext(Dispatchers.IO) {
                        JobBackups.save(this@FlashService, dir, job)
                        Payloads.bundle(this@FlashService, job.archive, request)
                    }
                    RunState.preparedArchive = job.archive
                    RunState.state = RunState.state.copy(recoveryPackageReady = true)
                }
                RunState.state = RunState.state.copy(stage = "按流程调整分区并依次写入 ABL（${request.ablTarget.label}）、efisp", progress = 0.4f)
                val resumeJob = if (online) checkNotNull(prepared) else null
                val result = withContext(Dispatchers.IO) {
                    val arguments = if (resumeJob != null) listOf("--resume", resumeJob.directory, "--backup-saved", resumeJob.manifestSha256) else emptyList()
                    RootShell.run(scriptCommand(File(dir, RunState.nextScript).absolutePath, "--execute", arguments), File(filesDir, "execute.log"))
                }
                RunState.append(result.output)
                if (!online) {
                    val backupDirectory = Probe.parse(result.output).values["BACKUP_DIR"].orEmpty()
                    if (backupDirectory.startsWith("${dir.absolutePath}/backups/")) {
                        val archive = JobBackups.archiveFile(this@FlashService, backupDirectory.substringAfterLast('/'))
                        withContext(Dispatchers.IO) {
                            JobBackups.saveDirectory(this@FlashService, dir, backupDirectory, archive)
                            Payloads.bundle(this@FlashService, archive, request)
                        }
                        RunState.preparedArchive = archive
                        RunState.state = RunState.state.copy(recoveryPackageReady = true)
                    }
                }
                check(result.exitCode == 0 && Probe.parse(result.output).values["RESULT"] == "success") {
                    "流程已停止（退出码 ${result.exitCode}），请保留日志与备份并核对当前状态"
                }
                if (online) {
                    var gate = WorkflowGate.startCreate()
                    result.output.lineSequence().filter { it.startsWith("PROOF=") }.forEach { line ->
                        gate = gate.recordProof(WorkflowStage.valueOf(line.removePrefix("PROOF=").trim()))
                    }
                    gate.awaitRebootChoice().requireRebootChoiceAllowed()
                    JobBackups.clear(this@FlashService)
                    RunState.preparedJob = null
                }
                RunState.state = RunState.state.copy(
                    stage = if (completion == UnlockCompletion.BOOTLOADER) "刷写完成" else "刷写完成，可导出备份并选择是否进入 Recovery",
                    slot = Probe.parse(result.output).slot, progress = 1f, completedRequest = request,
                    canRebootRecovery = completion == UnlockCompletion.RECOVERY,
                    canRebootBootloader = completion == UnlockCompletion.BOOTLOADER)

            } catch (e: Exception) {
                RunState.append(e.message ?: e.javaClass.simpleName)
                RunState.state = RunState.state.copy(stage = "执行已停止，请查看日志并核对分区状态", canRebootRecovery = false, canRebootBootloader = false, completedRequest = null)
            } finally {
                if (wake.isHeld) wake.release()
                running = false
                RunState.state = RunState.state.copy(busy = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
}
