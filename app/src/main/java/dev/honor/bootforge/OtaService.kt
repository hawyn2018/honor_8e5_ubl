package dev.honor.bootforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone
import java.io.File
import java.security.MessageDigest
import dev.honor.bootforge.core.Payloads
import dev.honor.bootforge.core.ImagePatchFailureBoundary
import java.util.concurrent.CancellationException
import dev.honor.bootforge.core.OtaCompletionMarker
import dev.honor.bootforge.core.PatchRetryTask
import dev.honor.bootforge.core.SystemUpdaterHandoff

class OtaService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        if (OtaState.state.busy || RunState.state.busy || BackupState.state.busy) {
            stopSelf(startId); return START_NOT_STICKY
        }
        if (RunState.preparedJob != null || JobBackups.load(this) != null) {
            OtaState.state = OtaState.state.copy(error = "存在待执行或待释放的解锁任务，请先完成原任务清理")
            stopSelf(startId); return START_NOT_STICKY
        }
        val request = runCatching { OtaRequest.decode(intent?.getStringExtra("request").orEmpty()) }.getOrElse {
            stopSelf(startId); return START_NOT_STICKY
        }
        val operation = runCatching { OtaOperation.valueOf(intent?.getStringExtra("operation") ?: OtaOperation.FULL.name) }.getOrElse {
            stopSelf(startId); return START_NOT_STICKY
        }
        if (operation != OtaOperation.EXPORT_EFISP && !BuildConfig.ALLOW_PARTITION_WRITES) {
            stopSelf(startId); return START_NOT_STICKY
        }
        val exportUri = intent?.getStringExtra("exportUri")?.let(Uri::parse)
        if (operation == OtaOperation.EXPORT_EFISP && exportUri?.scheme != "content") {
            OtaState.state = OtaState.state.copy(error = "请先选择 efisp 导出文件")
            stopSelf(startId); return START_NOT_STICKY
        }
        val requestedAt = intent?.getLongExtra("requestedAt", 0) ?: 0
        val zone = TimeZone.getTimeZone(intent?.getStringExtra("zone") ?: TimeZone.getDefault().id)
        val channel = "ota_preserve"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "OTA 保持解锁", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(44, Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("honor_ubl · ${operationLabel(operation)}").setContentText("正在固定来源并处理 efisp")
            .setContentIntent(open).setOngoing(true).build())
        RunState.state = RunState.state.copy(busy = true)
        running = true
        OtaState.state = OtaState.state.copy(busy = true, request = request, operation = operation,
            completed = false, completionMarkerReady = null, error = "", efispPatchFailureHint = "", progress = 0f, outputDirectory = "", exportFilePath = "")
        scope.launch {
            var wake: PowerManager.WakeLock? = null
            var transactionSucceeded = false
            try {
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BootForge:ota")
                checkNotNull(wake).acquire(2 * 60 * 60 * 1000L)
                val exported = withContext(Dispatchers.IO) {
                    OtaEngine(this@OtaService, requestedAt, zone, request, operation,
                        onPatchFailure = { failure ->
                            OtaPatchFailureGuidance.decide(operation, failure) {
                                PatchRetryPrompt.awaitChoice(PatchRetryTask.OTA, it)
                            }
                        }).run { update ->
                        withContext(Dispatchers.Main) {
                            OtaState.state = OtaState.state.copy(stage = update.stage.ifBlank { OtaState.state.stage },
                                progress = if (update.stage.isNotBlank()) update.progress else OtaState.state.progress,
                                slot = update.slot.ifBlank { OtaState.state.slot }, outputDirectory = update.directory.ifBlank { OtaState.state.outputDirectory })
                            if (update.log.isNotEmpty()) OtaState.append(update.log)
                        }
                    }
                }
                if (operation == OtaOperation.EXPORT_EFISP) {
                    val product = checkNotNull(exported) { "efisp 导出成品缺失" }
                    // Retain the fixed private product for a retry even if the document provider fails.
                    OtaState.state = OtaState.state.copy(exportFilePath = product.absolutePath)
                    withContext(Dispatchers.IO) { exportProduct(product, checkNotNull(exportUri)) }
                    OtaState.append("[导出] SHA-256=${Payloads.sha256(product)} · $exportUri")
                    transactionSucceeded = true
                    OtaState.state = OtaState.state.copy(completed = true, stage = OtaCompletionPolicy.stage(operation),
                        outputDirectory = exportUri.toString(), progress = 1f)
                } else {
                    transactionSucceeded = true
                    OtaState.state = OtaState.state.copy(completed = true, stage = OtaCompletionPolicy.stage(operation), progress = 1f)
                    if (operation == OtaOperation.FULL) {
                        // run() returned only after all writes, readbacks and the public
                        // completion manifest succeeded. Auxiliary handoff is non-fatal.
                        val completion = withContext(Dispatchers.IO) {
                            OtaCompletionFlow.afterSuccess(operation,
                                writeMarker = { OtaCompletionMarker.write(File(filesDir, "ota-module/freeotaok.log")) },
                                openUpdater = { SystemUpdaterHandoff.open(this@OtaService, File(filesDir, "ota-module/updater-open.log")) })
                        }
                        completion.logs.forEach(OtaState::append)
                        OtaState.state = OtaState.state.copy(completionMarkerReady = completion.markerReady,
                            stage = OtaCompletionPolicy.stage(operation, completion.markerReady))
                    }
                }
            } catch (e: CancellationException) {
                if (transactionSucceeded) OtaState.append("[更新联动] 完成后的联动已中断，操作成功结果保留")
                else operationFailed(e)
                throw e
            } catch (e: InterruptedException) {
                if (transactionSucceeded) OtaState.append("[更新联动] 完成后的联动已中断，操作成功结果保留")
                else operationFailed(e)
                throw CancellationException("操作已中断").also { it.initCause(e) }
            } catch (e: OutOfMemoryError) {
                // Let the platform decide heap capacity. Do not retry a partition transaction.
                val message = "系统分配给应用的内存不足，本次操作已停止，请保留备份与日志"
                OtaState.append("[内存] $message")
                OtaState.state = OtaState.state.copy(stage = "OTA 操作已停止，请保留备份与日志", error = message, completed = false)
            } catch (e: Exception) {
                if (transactionSucceeded) OtaState.append("[更新联动] 完成后的联动未完成，操作成功结果保留：${e.message ?: e.javaClass.simpleName}")
                else operationFailed(e, operation)
            } catch (e: Error) {
                operationFailed(ImagePatchFailureBoundary.reportable(e) ?: throw e, operation)
            } finally {
                RunState.state = RunState.state.copy(busy = false)
                running = false
                OtaState.state = OtaState.state.copy(busy = false)
                PatchRetryPrompt.cancel(PatchRetryTask.OTA)
                try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) { /* busy was already reset */ }
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun operationFailed(error: Exception, operation: OtaOperation = OtaOperation.FULL) {
        OtaState.append(error.message ?: error.javaClass.simpleName)
        OtaState.state = OtaState.state.copy(stage = "OTA 操作已停止，请保留备份与日志", error = error.message ?: error.javaClass.simpleName,
            completed = false, efispPatchFailureHint = OtaPatchFailureGuidance.message(operation, error))
    }

    override fun onDestroy() {
        PatchRetryPrompt.cancel(PatchRetryTask.OTA)
        super.onDestroy()
    }

    companion object {
        private fun operationLabel(operation: OtaOperation) = when (operation) {
            OtaOperation.FULL -> "保留锁态 OTA"
            OtaOperation.EFISP_ONLY -> "仅刷写 efisp"
            OtaOperation.EXPORT_EFISP -> "导出 efisp"
        }
        fun start(context: Context, request: OtaRequest, operation: OtaOperation = OtaOperation.FULL, exportUri: Uri? = null) {
            val clicked = System.currentTimeMillis()
            context.startForegroundService(Intent(context, OtaService::class.java).putExtra("request", request.encode())
                .putExtra("operation", operation.name).putExtra("exportUri", exportUri?.toString())
                .putExtra("requestedAt", clicked).putExtra("zone", TimeZone.getDefault().id))
        }
    }
    private fun exportProduct(file: File, uri: Uri) {
        val expected = Payloads.sha256(file)
        checkNotNull(contentResolver.openOutputStream(uri, "wt")) { "导出文件打开失败" }.use { output ->
            file.inputStream().use { it.copyTo(output) }; output.flush()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        checkNotNull(contentResolver.openInputStream(uri)) { "导出文件回读失败" }.use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count); bytes += count
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        check(bytes == file.length() && actual == expected) { "导出文件 SHA-256 校验失败；私有成品已保留" }
    }
}
