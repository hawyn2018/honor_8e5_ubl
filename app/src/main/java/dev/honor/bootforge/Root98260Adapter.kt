package dev.honor.bootforge

import android.app.Application
import dev.honor.bootforge.core.RootProcessProvider
import dev.honor.bootforge.wireless.ShellAccess
import kotlinx.coroutines.delay
import java.util.Base64
import java.util.concurrent.TimeUnit

object Root98260Adapter : RootAdapter {
    @Volatile private var logCallback: ((String) -> Unit)? = null

    override fun setLogCallback(callback: ((String) -> Unit)?) {
        logCallback = callback
    }

    override suspend fun deploy(shell: ShellAccess, application: Application) {
        checkRootChainAllowed(shell, application, RootChain.CVE_2026_98260)
        checkKernel(shell, application)
        if (channelAlive(shell, application)) {
            log("98260：rootd 通道仍存活，跳过资产部署")
            return
        }
        runRootChecked(shell, application,
            "mkdir -p $WORK_DIR/io $WORK_DIR/98260 $WORK_DIR/98260-6138 && " +
                "chmod 777 $WORK_DIR $WORK_DIR/io $WORK_DIR/98260 $WORK_DIR/98260-6138",
            30, "98260 初始化工作目录")
        for (name in SUPPORT_ASSETS) {
            pushRootAsset(shell, application, "$ASSET_ROOT/$name", "$WORK_DIR/$name")
        }
        pushRootAsset(shell, application, "$ASSET_ROOT/run98260.sh", "$WORK_DIR/98260/run98260.sh")
        for (profile in PROFILES) {
            for (name in PROFILE_ASSETS) {
                pushRootAsset(shell, application, "$ASSET_ROOT/$profile/$name", "$WORK_DIR/$profile/$name")
            }
            log("98260：$profile 资产部署完成")
        }
        runRootChecked(shell, application,
            "chmod 755 $WORK_DIR/* $WORK_DIR/98260/* $WORK_DIR/98260-6138/* 2>/dev/null || true",
            30, "98260 设置资产权限")
        log("98260：双内核资产部署完成")
    }

    override suspend fun acquireRoot(shell: ShellAccess, application: Application): RootProcessProvider {
        checkRootChainAllowed(shell, application, RootChain.CVE_2026_98260)
        checkKernel(shell, application)
        if (channelAlive(shell, application)) {
            log("98260：rootd 通道仍存活，直接接入")
            clearFailureRecord(shell, application)
            markRootChainSuccess(shell, application, RootChain.CVE_2026_98260)
            return rootProvider()
        }
        val staged = shell.run(application,
            "test -f $WORK_DIR/98260/run98260.sh && test -x $WORK_DIR/root-command.sh && echo OK || echo MISSING", 15)
        if (!staged.output.contains("OK")) throw IllegalStateException("98260 提权资产尚未部署")

        val boot = currentBoot(shell, application)
        val runId = "${boot.take(8)}-${System.currentTimeMillis()}"
        val runDir = "$WORK_DIR/history/$runId"
        var lastTail = ""
        try {
            archiveRunLogs(shell, application, "$WORK_DIR/history/recovered-$runId")
            log("98260：开始只读预检；本轮日志目录 $runDir")
            val preflight = runRootChecked(shell, application,
                "cd $WORK_DIR && MAGICROOT_98260_PREFLIGHT=1 MAGICROOT_98260_KSU=0 sh $WORK_DIR/98260/run98260.sh",
                120, "98260 只读预检")
            log(preflight.output.trim())
            check(preflight.output.contains("98260_CHAIN_END=PREFLIGHT code=0")) {
                "98260 只读预检未通过：${preflight.output.takeLast(500)}"
            }

            runRootChecked(shell, application,
                "rm -f $WORK_DIR/run.log $WORK_DIR/98260.log $WORK_DIR/98260-verify.log $WORK_DIR/98260-verify.raw",
                15, "98260 初始化本轮日志")
            runRootChecked(shell, application,
                "cd $WORK_DIR && (nohup env MAGICROOT_ENABLE_98260=1 " +
                    "MAGICROOT_98260_PREFLIGHT=0 MAGICROOT_98260_KSU=0 " +
                    "MAGICROOT_98260_ATTEMPTS=$ROOT98260_ATTEMPTS " +
                    "MAGICROOT_98260_BUDGET_S=$ROOT98260_BUDGET_S " +
                    "MAGICROOT_98260_SOCKET_WAIT_S=$ROOT98260_SOCKET_WAIT_S " +
                    "MAGICROOT_98260_ZOMBIE_STOP_S=$ROOT98260_ZOMBIE_STOP_S " +
                    "sh $WORK_DIR/98260/run98260.sh > run.log 2>&1 &) && echo STARTED",
                20, "98260 启动提权链")
            log("98260：正式提权链已启动 attempts=$ROOT98260_ATTEMPTS budget=$ROOT98260_BUDGET_S socket_wait=$ROOT98260_SOCKET_WAIT_S zombie_stop=$ROOT98260_ZOMBIE_STOP_S")
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
                        val proof = runRootChecked(shell, application,
                            "sh $WORK_DIR/root-command.sh aWQgLXU=", 20, "98260 Root 自检")
                        check(proof.output.trim() == "0") { "98260 rootd 返回的身份不是 UID 0：${proof.output}" }
                        log("98260：ROOT_UID=0")
                        clearFailureRecord(shell, application)
                        markRootChainSuccess(shell, application, RootChain.CVE_2026_98260)
                        return rootProvider()
                    }
                    ROOT_FINISH_FAIL -> {
                        val proofFailure = rootIsProofOrParked(tail)
                        if (proofFailure) recordFailure(shell, application, "proof_failure")
                        throw IllegalStateException("98260 提权失败：${tail.takeLast(700)}" +
                            if (proofFailure) "；98260建议重启再试" else "")
                    }
                }
            }
            recordFailure(shell, application, "wait_timeout")
            throw IllegalStateException("98260 提权等待超时；98260建议重启再试：${lastTail.takeLast(700)}")
        } finally {
            archiveRunLogs(shell, application, runDir)
        }
    }

    private suspend fun currentBoot(shell: ShellAccess, application: Application): String =
        runRootChecked(shell, application, "cat /proc/sys/kernel/random/boot_id", 10, "98260 读取启动周期").output.trim()

    private suspend fun recordFailure(shell: ShellAccess, application: Application, reason: String) {
        val boot = currentBoot(shell, application)
        runRootChecked(shell, application,
            "printf '%s %s\\n' '$boot' '$reason' > $ROOT98260_FAILURE_FILE", 15, "98260 记录失败现场")
        log("98260：失败已记录，可重试或换链 reason=$reason")
    }

    private suspend fun clearFailureRecord(shell: ShellAccess, application: Application) {
        runRootChecked(shell, application, "rm -f $ROOT98260_FAILURE_FILE", 15, "98260 清除旧失败记录")
    }

    private suspend fun archiveRunLogs(shell: ShellAccess, application: Application, directory: String) {
        runCatching {
            runRootChecked(shell, application,
                "mkdir -p '$directory'; for name in run.log 98260.log 98260-check.json 98260-verify.log 98260-verify.raw; do " +
                    "if [ -f $WORK_DIR/\$name ]; then cp -f $WORK_DIR/\$name '$directory'/; fi; done; " +
                    "if [ -f $ROOT98260_FAILURE_FILE ]; then cp -f $ROOT98260_FAILURE_FILE '$directory/last-failure.txt'; fi; " +
                    "chmod 644 '$directory'/* 2>/dev/null || true",
                30, "98260 保存分轮日志")
            log("98260：日志已保存到 $directory")
        }.onFailure { log("98260：日志保存失败：${it.message}") }
    }

    override suspend fun clean(shell: ShellAccess, application: Application) {
        val result = shell.run(application, "sh $WORK_DIR/clean.sh", 90)
        check(result.exitCode == 0 && result.output.contains("CLEAN_OK")) {
            "98260 清理失败：${result.output}"
        }
        log(result.output.trim())
    }

    private suspend fun checkKernel(shell: ShellAccess, application: Application) {
        val kernel = runRootChecked(shell, application, "uname -r", 10, "98260 读取内核").output.trim()
        val profile = checkNotNull(rootProfileForKernel(kernel)) { "98260 不支持内核 $kernel" }
        log("98260：kernel=$kernel profile=$profile")
    }

    private suspend fun channelAlive(shell: ShellAccess, application: Application): Boolean {
        val result = runCatching {
            shell.run(application, "sh $WORK_DIR/root-command.sh aWQgLXU=", 20)
        }.getOrNull() ?: return false
        return result.exitCode == 0 && result.output.trim() == "0"
    }

    private fun rootProvider(): RootProcessProvider = RootProcessProvider { command ->
        val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        ShellAccess.start("sh $WORK_DIR/root-command.sh '$encoded'")
    }

    private fun log(message: String) {
        runCatching { logCallback?.invoke(message) }
    }

    private const val WORK_DIR = "/data/local/tmp/magic-98260"
    private const val ASSET_ROOT = "magic104/98260"
    private val PROFILES = listOf("98260", "98260-6138")
    private val SUPPORT_ASSETS = listOf(
        "kernel_profiles.sh", "ko_patch", "ksu-template.ko", "syms_61258.txt", "syms_6138.txt",
        "root-command.sh", "clean.sh",
    )
    private val PROFILE_ASSETS = listOf(
        "exploit-android-perf", "exploit-android-perf-chain", "exploit-android-key-ptr",
        "pc98260-su", "target.sh", "SHA256SUMS.txt",
    )
}
