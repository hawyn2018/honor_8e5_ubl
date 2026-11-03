package dev.honor.bootforge

import android.app.Application
import dev.honor.bootforge.wireless.ShellAccess
import kotlinx.coroutines.delay
import java.util.Base64
import java.util.concurrent.TimeUnit

object NewKernel98260Adapter : NewKernelRootAdapterBase(RootChain.CVE_2026_98260) {
    override suspend fun deployPayload(shell: ShellAccess, application: Application) {
        if (channelAlive(shell, application)) {
            log("rootd 通道仍存活，跳过资产部署")
            return
        }
        runRootChecked(shell, application,
            "mkdir -p $WORK_DIR/io $WORK_DIR/98260-aph112 && chmod 777 $WORK_DIR $WORK_DIR/io $WORK_DIR/98260-aph112",
            30, "初始化 APH112 工作目录")
        for (name in SUPPORT_ASSETS) {
            pushRootAsset(shell, application, "$ASSET_ROOT/$name", "$WORK_DIR/$name")
        }
        for (name in PROFILE_ASSETS) {
            pushRootAsset(shell, application, "$ASSET_ROOT/98260-aph112/$name", "$WORK_DIR/98260-aph112/$name")
        }
        runRootChecked(shell, application,
            "chmod 755 $WORK_DIR/* $WORK_DIR/98260-aph112/* 2>/dev/null || true", 30, "设置 APH112 资产权限")
        log("APH112 资产部署完成")
    }

    override suspend fun openRootSession(shell: ShellAccess, application: Application) {
        if (channelAlive(shell, application)) {
            log("rootd 通道仍存活，直接接入")
            clearFailureRecord(shell, application)
            return
        }
        val staged = shell.run(application,
            "test -f $RUNNER_PATH && test -x $WORK_DIR/root-command.sh && echo OK || echo MISSING", 15)
        if (!staged.output.contains("OK")) throw IllegalStateException("APH112 提权资产尚未部署")

        val boot = currentBoot(shell, application)
        val runId = "${boot.take(8)}-${System.currentTimeMillis()}"
        val runDir = "$WORK_DIR/history/$runId"
        var lastTail = ""
        try {
            archiveRunLogs(shell, application, "$WORK_DIR/history/recovered-$runId")
            log("开始只读预检；本轮日志目录 $runDir")
            val preflight = runRootChecked(shell, application,
                "cd $WORK_DIR && MAGICROOT_98260_PREFLIGHT=1 MAGICROOT_98260_KSU=0 sh $RUNNER_PATH",
                120, "APH112 只读预检")
            log(preflight.output.trim())
            check(preflight.output.contains("98260_CHAIN_END=PREFLIGHT code=0")) {
                "APH112 只读预检未通过：${preflight.output.takeLast(500)}"
            }

            runRootChecked(shell, application,
                "rm -f $WORK_DIR/run.log $WORK_DIR/98260.log $WORK_DIR/98260-verify.log $WORK_DIR/98260-verify.raw",
                15, "初始化 APH112 本轮日志")
            runRootChecked(shell, application,
                "cd $WORK_DIR && (nohup env MAGICROOT_ENABLE_98260=1 " +
                    "MAGICROOT_98260_PREFLIGHT=0 MAGICROOT_98260_KSU=0 " +
                    "MAGICROOT_98260_ATTEMPTS=$ROOT98260_ATTEMPTS " +
                    "MAGICROOT_98260_BUDGET_S=$ROOT98260_BUDGET_S " +
                    "MAGICROOT_98260_SOCKET_WAIT_S=$ROOT98260_SOCKET_WAIT_S " +
                    "MAGICROOT_98260_ZOMBIE_STOP_S=$ROOT98260_ZOMBIE_STOP_S " +
                    "sh $RUNNER_PATH > run.log 2>&1 &) && echo STARTED",
                20, "启动 APH112 提权链")
            log("正式提权链已启动 attempts=$ROOT98260_ATTEMPTS budget=$ROOT98260_BUDGET_S socket_wait=$ROOT98260_SOCKET_WAIT_S zombie_stop=$ROOT98260_ZOMBIE_STOP_S")
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30)
            while (System.nanoTime() < deadline) {
                delay(2000)
                val tail = runCatching {
                    shell.run(application,
                        "tail -n 30 $WORK_DIR/run.log 2>/dev/null; tail -n 12 $WORK_DIR/98260.log 2>/dev/null", 20).output
                }.getOrDefault("")
                if (tail.isNotBlank() && tail != lastTail) {
                    lastTail = tail
                    log(tail.trim())
                }
                when (rootFinishKind(RootChain.CVE_2026_98260, tail)) {
                    ROOT_FINISH_DONE -> {
                        log("APH112 rootd 已建立，UID 0 验证通过")
                        clearFailureRecord(shell, application)
                        return
                    }
                    ROOT_FINISH_FAIL -> {
                        val proofFailure = rootIsProofOrParked(tail)
                        if (proofFailure) recordFailure(shell, application, "proof_failure")
                        throw IllegalStateException("APH112 提权失败：${tail.takeLast(700)}" +
                            if (proofFailure) "；98260建议重启再试" else "")
                    }
                }
            }
            recordFailure(shell, application, "wait_timeout")
            throw IllegalStateException("APH112 提权等待超时；98260建议重启再试：${lastTail.takeLast(700)}")
        } finally {
            archiveRunLogs(shell, application, runDir)
        }
    }

    override fun startRootProcess(command: String): Process {
        val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        return ShellAccess.start("sh $WORK_DIR/root-command.sh '$encoded'")
    }

    override suspend fun cleanPayload(shell: ShellAccess, application: Application) {
        val result = shell.run(application,
            "if [ -f $WORK_DIR/clean.sh ]; then sh $WORK_DIR/clean.sh; else rm -rf $WORK_DIR; echo CLEAN_OK; fi", 90)
        check(result.exitCode == 0 && result.output.contains("CLEAN_OK")) { "APH112 清理失败：${result.output}" }
        log(result.output.trim())
    }

    private suspend fun channelAlive(shell: ShellAccess, application: Application): Boolean {
        val result = runCatching {
            shell.run(application, "sh $WORK_DIR/root-command.sh aWQgLXU=", 20)
        }.getOrNull() ?: return false
        return result.exitCode == 0 && result.output.trim() == "0"
    }

    private suspend fun currentBoot(shell: ShellAccess, application: Application): String =
        runRootChecked(shell, application, "cat /proc/sys/kernel/random/boot_id", 10, "读取启动周期").output.trim()

    private suspend fun recordFailure(shell: ShellAccess, application: Application, reason: String) {
        val boot = currentBoot(shell, application)
        runRootChecked(shell, application,
            "printf '%s %s\\n' '$boot' '$reason' > $ROOT98260_FAILURE_FILE", 15, "记录 APH112 失败现场")
        log("失败已记录，可重试或换链 reason=$reason")
    }

    private suspend fun clearFailureRecord(shell: ShellAccess, application: Application) {
        runRootChecked(shell, application, "rm -f $ROOT98260_FAILURE_FILE", 15, "清除旧失败记录")
    }

    private suspend fun archiveRunLogs(shell: ShellAccess, application: Application, directory: String) {
        runCatching {
            runRootChecked(shell, application,
                "mkdir -p '$directory'; for name in run.log 98260.log 98260-check.json 98260-verify.log 98260-verify.raw; do " +
                    "if [ -f $WORK_DIR/\$name ]; then cp -f $WORK_DIR/\$name '$directory'/; fi; done; " +
                    "if [ -f $ROOT98260_FAILURE_FILE ]; then cp -f $ROOT98260_FAILURE_FILE '$directory/last-failure.txt'; fi; " +
                    "chmod 644 '$directory'/* 2>/dev/null || true",
                30, "保存 APH112 分轮日志")
            log("日志已保存到 $directory")
        }.onFailure { log("日志保存失败：${it.message}") }
    }

    private const val WORK_DIR = "/data/local/tmp/hnubl-g459a-98260"
    private const val RUNNER_PATH = "$WORK_DIR/run98260.sh"
    private const val ASSET_ROOT = "root_extra/g459a/98260"
    private val SUPPORT_ASSETS = listOf(
        "kernel_profiles.sh", "ko_patch", "ksu-template.ko", "syms_aph112.txt",
        "run98260.sh", "root-command.sh", "clean.sh",
    )
    private val PROFILE_ASSETS = listOf(
        "exploit-android-perf", "exploit-android-perf-chain", "exploit-android-key-ptr",
        "pc98260-su", "target.sh", "SHA256SUMS.txt",
    )
}
