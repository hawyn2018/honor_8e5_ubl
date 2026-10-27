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
import dev.honor.bootforge.core.SlotPartitionBackup
import dev.honor.bootforge.core.SlotBackupFailure
import dev.honor.bootforge.core.DualSlotBackupFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.TimeZone

class BackupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BackupState.state.busy) return START_NOT_STICKY
        if (RunState.state.busy) {
            BackupState.state = BackupState.state.copy(error = "其他任务正在运行，请等待完成")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val requestedAt = Date(intent?.getLongExtra(EXTRA_REQUESTED_AT, System.currentTimeMillis()) ?: System.currentTimeMillis())
        val requestedZone = TimeZone.getTimeZone(intent?.getStringExtra(EXTRA_TIME_ZONE) ?: TimeZone.getDefault().id)
        val channel = "slot_backup"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "分区与分区表备份", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(42, Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("honor_ubl 正在备份")
            .setContentText("备份双槽 init_boot、abl 和 GPT 分区表")
            .setContentIntent(open).setOngoing(true).build())
        // Both services enter on the main thread and share this busy flag.
        RunState.state = RunState.state.copy(busy = true)
        BackupState.state = BackupUiState(busy = true, stage = "正在准备双槽备份并识别当前槽位", status = "running")
        scope.launch {
            val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BootForge:backup")
            try {
                wake.acquire(2 * 60 * 60 * 1000L)
                val result = withContext(Dispatchers.IO) {
                    SlotPartitionBackup(this@BackupService, requestedAt, requestedZone).run { progress ->
                        withContext(Dispatchers.Main) {
                            BackupState.state = BackupState.state.copy(stage = progress.stage, progress = progress.progress,
                                slot = progress.slot.ifEmpty { BackupState.state.slot },
                                outputDirectory = progress.directory.ifEmpty { BackupState.state.outputDirectory },
                                files = progress.files.ifEmpty { BackupState.state.files },
                                status = progress.status, warnings = progress.warnings, diskStatuses = progress.diskStatuses)
                        }
                    }
                }
                BackupState.state = BackupState.state.copy(
                    stage = if (result.status == "complete") "分区镜像与主备 GPT 备份完成"
                        else "原始镜像已保存，请查看 GPT 备份提示",
                    slot = result.slot, outputDirectory = result.directory, files = result.files, progress = 1f,
                    status = result.status, warnings = result.warnings, diskStatuses = result.diskStatuses)
            } catch (error: Exception) {
                (error as? SlotBackupFailure)?.result?.let { result ->
                    BackupState.state = BackupState.state.copy(slot = result.slot, outputDirectory = result.directory,
                        files = result.files, status = result.status, warnings = result.warnings, diskStatuses = result.diskStatuses)
                }
                val originalsSaved = DualSlotBackupFiles.originalsSaved(BackupState.state.slot,
                    BackupState.state.files.map { it.name }) && BackupState.state.outputDirectory.isNotEmpty()
                BackupState.state = BackupState.state.copy(
                    stage = if (originalsSaved) "原始备份已保存，后续备份或保存未完成" else "备份已停止",
                    error = error.message ?: error.javaClass.simpleName, status = "partial")
            } finally {
                if (wake.isHeld) wake.release()
                BackupState.state = BackupState.state.copy(busy = false)
                // Preserve prepared jobs, export state and the existing Recovery action.
                RunState.state = RunState.state.copy(busy = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_REQUESTED_AT = "backup_requested_at"
        private const val EXTRA_TIME_ZONE = "backup_requested_time_zone"
        fun start(context: Context) {
            // Capture the button event, before Android schedules the service or dd starts.
            val requestedAt = System.currentTimeMillis()
            val requestedZone = TimeZone.getDefault().id
            context.startForegroundService(Intent(context, BackupService::class.java)
                .putExtra(EXTRA_REQUESTED_AT, requestedAt).putExtra(EXTRA_TIME_ZONE, requestedZone))
        }
    }
}
