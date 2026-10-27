package dev.honor.bootforge

import android.app.Application
import dev.honor.bootforge.core.RootProcessProvider
import dev.honor.bootforge.wireless.ShellAccess
import kotlinx.coroutines.delay
import java.util.Base64
import java.util.concurrent.TimeUnit

object Root64650Adapter : RootAdapter {
    @Volatile private var logCallback: ((String) -> Unit)? = null

    override fun setLogCallback(callback: ((String) -> Unit)?) {
        logCallback = callback
    }

    override suspend fun deploy(shell: ShellAccess, application: Application) {
        checkRootChainAllowed(shell, application, RootChain.CVE_2026_64650)
        if (channelAlive(shell, application)) {
            log("64650：Root 通道仍存活，跳过资产部署")
            return
        }
        runRootChecked(shell, application,
            "rm -rf $WORK_DIR && mkdir -p $WORK_DIR/marks $ROOT_DIR && chmod 777 $WORK_DIR $WORK_DIR/marks $ROOT_DIR",
            30, "64650 初始化工作目录")
        for (name in ASSETS) {
            pushRootAsset(shell, application, "$ASSET_ROOT/$name", "$WORK_DIR/$name")
            log("64650：已部署 $name")
        }
        pushRootAsset(shell, application, "magic/bfexec", "$WORK_DIR/bfexec")
        runRootChecked(shell, application,
            "chmod 755 $WORK_DIR/* $WORK_DIR/bfexec 2>/dev/null || true", 30, "64650 设置资产权限")
        log("64650：${ASSETS.size + 1} 项资产部署完成（含双内核 profile）")
    }

    override suspend fun acquireRoot(shell: ShellAccess, application: Application): RootProcessProvider {
        checkRootChainAllowed(shell, application, RootChain.CVE_2026_64650)
        val kernel = runRootChecked(shell, application, "uname -r", 10, "64650 读取内核").output.trim()
        val profile = checkNotNull(rootProfileForKernel(kernel)) { "64650 不支持内核 $kernel" }
        log("64650：kernel=$kernel profile=$profile")
        if (channelAlive(shell, application)) {
            log("64650：Root 通道仍存活，直接接入")
            markRootChainSuccess(shell, application, RootChain.CVE_2026_64650)
            return rootProvider()
        }
        val staged = shell.run(application, "test -f $WORK_DIR/run.sh && echo OK || echo MISSING", 15)
        if (!staged.output.contains("OK")) throw IllegalStateException("64650 提权资产尚未部署")
        runRootChecked(shell, application,
            "cd $WORK_DIR && rm -f run.log && (nohup env MAGICROOT_CHAIN=64560 MAGICROOT_KSU_FLAVOR=kernelsu " +
                "MAGICROOT_ALLOW_SHELL=1 MAGICROOT_SU_COMPAT=0 MAGICROOT_SU_OVERLAY=0 MAGICROOT_SU_ENTRY=0 " +
                "MAGICROOT_ADB_ROOT=0 sh run.sh > run.log 2>&1 &) && echo STARTED",
            20, "64650 启动提权链")
        log("64650：提权链已启动，正在等待 DONE / FAIL")
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(90)
        var lastTail = ""
        while (System.nanoTime() < deadline) {
            delay(3000)
            val tail = runCatching {
                shell.run(application, "tail -n 30 $WORK_DIR/run.log 2>/dev/null", 20).output
            }.getOrDefault("")
            if (tail.isNotBlank() && tail != lastTail) {
                lastTail = tail
                log(tail.trim())
            }
            when (rootFinishKind(RootChain.CVE_2026_64650, tail)) {
                ROOT_FINISH_DONE -> {
                    val proof = shell.run(application, "$ROOT_BIN 2>/dev/null", 20)
                    check(proof.exitCode == 0 && proof.output.contains("uid=0")) {
                        "64650 结束但 bfexec 自检失败：${proof.output}"
                    }
                    log("64650：${proof.output.trim()}")
                    markRootChainSuccess(shell, application, RootChain.CVE_2026_64650)
                    return rootProvider()
                }
                ROOT_FINISH_FAIL -> {
                    val detail = tail.lineSequence().firstOrNull { it.trim().startsWith("FAIL=") } ?: tail.takeLast(500)
                    throw IllegalStateException("64650 提权失败：$detail；已保留日志，可重试或换链")
                }
            }
        }
        throw IllegalStateException("64650 提权等待超时，日志保留在 $WORK_DIR/run.log：${lastTail.takeLast(500)}")
    }

    override suspend fun clean(shell: ShellAccess, application: Application) {
        val result = shell.run(application,
            "if [ -f $WORK_DIR/clean.sh ]; then sh $WORK_DIR/clean.sh; else " +
                "rm -rf $WORK_DIR $ROOT_DIR; echo CLEAN_OK; fi", 90)
        check(result.exitCode == 0 && result.output.contains("CLEAN_OK")) { "64650 清理失败：${result.output}" }
        log(result.output.trim())
    }

    private suspend fun channelAlive(shell: ShellAccess, application: Application): Boolean {
        val result = runCatching { shell.run(application, "$ROOT_BIN 2>/dev/null", 15) }.getOrNull() ?: return false
        return result.exitCode == 0 && result.output.contains("uid=0")
    }

    private fun rootProvider(): RootProcessProvider = RootProcessProvider { command ->
        val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        ShellAccess.start("$ROOT_BIN '$encoded'")
    }

    private fun log(message: String) {
        runCatching { logCallback?.invoke(message) }
    }

    private const val WORK_DIR = "/data/local/tmp/magic"
    private const val ROOT_DIR = "/data/local/tmp/honor-64650"
    private const val ROOT_BIN = "$ROOT_DIR/bfexec"
    private const val ASSET_ROOT = "magic104/64650"
    private val ASSETS = listOf(
        "kaslr_slide_61258", "kaslr_slide_6138", "syms_61258.txt", "syms_6138.txt",
        "kernel_profiles.sh", "ko_patch", "addrcalc", "t64560_v36", "cred_target", "t64560_cred",
        "ksu-template.ko", "ksu_flavor", "forker", "grab.sh", "grab_legacy.sh", "lib.sh",
        "run.sh", "wait_helper", "clean.sh",
    )
}
