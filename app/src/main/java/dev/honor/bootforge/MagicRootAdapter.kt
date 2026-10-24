package dev.honor.bootforge

import android.app.Application
import dev.honor.bootforge.core.RootProcessProvider
import dev.honor.bootforge.wireless.ShellAccess
import kotlinx.coroutines.delay
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * MagicRoot（CVE-2026-64560）提权链的 BootForge 接入实现。
 *
 * 部署：`assets/magic/` 经无线 ADB Shell（UID 2000）以 base64 分块写入
 * `/data/local/tmp/magic`（该 Shell 通道不支持交互 stdin，只能逐条追加）。
 *
 * 提权：`run.sh` 复现 MagicRoot 七步链路（KASLR → 修补 KernelSU 模块 →
 * SELinux permissive → cred 定位 → fsuid 清零 → 两阶段写加载模块 →
 * bfexec 自检），在后台执行并轮询 `run.log` 的 DONE / FAIL= 标志。
 *
 * Root 通道：每条 Root 命令经同一无线 ADB Shell 执行
 * `/data/local/tmp/bootforge/bfexec <base64(命令)>`；bfexec 通过 KernelSU 的
 * reboot 钩子 + GRANT_ROOT 把自身提为 root 后 exec `sh -c 命令`。
 * 不安装 ksud、不加冕 KernelSU 管理器：GRANT_ROOT 仅放行 UID 2000
 * （模块参数 allow_shell=1），即 root 只属于本应用持有的 ADB 身份。
 */
class MagicRootAdapter : RootAdapter {
    /** 部署与提权进度回调，由 RootFrameworkModel 挂接到 Root 日志。 */
    @Volatile var onLog: ((String) -> Unit)? = null

    override suspend fun deploy(shell: ShellAccess, application: Application) {
        // 进程清理由 run.sh 脚本内部完成：pkill -f 的模式串绝不能出现在 ADB 命令行里，
        // 否则该模式会匹配到执行它的 sh -c 自身（实测把自身 SIGKILL，退出码 137）。
        runChecked(shell, application, "rm -rf $WORK_DIR && mkdir -p $WORK_DIR/honor", 30, "初始化工作目录")
        for (name in ASSETS) pushAsset(shell, application, name)
        runChecked(shell, application, "chmod 755 $WORK_DIR/* 2>/dev/null || true", 30, "设置资产权限")
        log("资产部署完成（${ASSETS.size} 项 → $WORK_DIR）")
    }

    /** Root 执行通道（bfexec）当前是否仍可用：模块存活期内、应用进程重启后可直接复用。 */
    suspend fun channelAlive(shell: ShellAccess, application: Application): Boolean {
        val quick = runCatching { shell.run(application, "$ROOT_BIN 2>/dev/null", 15).output }.getOrDefault("")
        return quick.contains("uid=0")
    }

    override suspend fun acquireRoot(shell: ShellAccess, application: Application): RootProcessProvider {
        // 快速通道：上次提权的执行通道仍存活（如应用被杀后重进）时直接复用，不重复触发竞态。
        if (channelAlive(shell, application)) {
            log("Root 执行通道仍存活，跳过提权链直接接入")
            return rootProvider()
        }
        val staged = shell.run(application, "test -f $WORK_DIR/run.sh && echo OK || echo MISSING", 15)
        if (!staged.output.contains("OK")) throw IllegalStateException("提权资产尚未部署，请先执行部署")
        runChecked(shell, application,
            "cd $WORK_DIR && rm -f run.log && (nohup sh run.sh > run.log 2>&1 &) && echo STARTED",
            20, "启动提权链")
        log("提权链已启动（竞态命中可能需要数分钟），轮询 run.log …")

        // 覆盖最坏情况：permissive 阶段上限 300 次尝试 × 约 10~12 秒，外加其余阶段。
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(70)
        var lastTail = ""
        while (System.nanoTime() < deadline) {
            delay(3000)
            val tail = runCatching {
                shell.run(application, "tail -n 12 $WORK_DIR/run.log 2>/dev/null", 20).output
            }.getOrDefault("")
            if (tail.isNotBlank() && tail != lastTail) {
                lastTail = tail
                log(tail.trim())
            }
            when (finishKind(tail)) {
                FINISH_DONE -> {
                    val proof = shell.run(application, "cat $WORK_DIR/root_id 2>/dev/null", 15).output
                    if (!proof.contains("uid=0")) throw IllegalStateException("提权链结束但 bfexec 自检失败：$proof")
                    log("Root 自检：${proof.trim()}")
                    // 成功后清理工作目录；Root 通道 /data/local/tmp/bootforge 保留至重启或手动清理。
                    runCatching {
                        shell.run(application, "rm -rf $WORK_DIR /data/local/tmp/.magicroot-exec" +
                            " /data/local/tmp/honor /data/local/tmp/root_stage.sh /data/local/tmp/bl_text.txt", 20)
                    }
                    runCatching { shell.run(application, "rm -f /data/local/tmp/bl_*.bin", 20) }
                    log("工作目录已清理；Root 执行通道驻留 $ROOT_BIN")
                    return rootProvider()
                }
                FINISH_FAIL -> throw IllegalStateException("提权链报告 ${tail.lineSequence().map(String::trim).firstOrNull { it.startsWith("FAIL=") }}，工作目录已保留用于排查")
            }
        }
        throw IllegalStateException("提权链等待超时（设备上 run.sh 可能仍在运行，可稍后重新执行提权）。最后日志：${lastTail.takeLast(400)}")
    }

    /** 每条 Root 命令经无线 ADB Shell 执行 bfexec，由 KernelSU GRANT_ROOT 新鲜提权。 */
    private fun rootProvider(): RootProcessProvider = RootProcessProvider { command ->
        val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        ShellAccess.start("$ROOT_BIN '$encoded'")
    }

    override suspend fun clean(shell: ShellAccess, application: Application) {
        // 优先走脚本文件清理（其内 pkill -f 安全）；兜底分支只用按进程名的 pkill，
        // 避免模式串出现在命令行而误杀自身。
        val result = shell.run(application,
            "if [ -f $WORK_DIR/clean.sh ]; then sh $WORK_DIR/clean.sh; else " +
                "pkill -9 t64560 2>/dev/null; pkill -9 cred_target 2>/dev/null; " +
                "rm -rf $WORK_DIR $ROOT_DIR /data/local/tmp/.magicroot-exec /data/local/tmp/honor" +
                " /data/local/tmp/root_stage.sh /storage/emulated/0/Download/MagicRoot; " +
                "rm -f /data/local/tmp/bl_*.bin /data/local/tmp/bl_text.txt; echo CLEAN_OK; fi", 60)
        log(result.output.trim().ifEmpty { "清理完成" })
    }

    /** ADB Shell 无交互 stdin：base64 分块经 `echo -n … >>` 落盘，再整体解码并按字节数校验。 */
    private fun pushAsset(shell: ShellAccess, application: Application, name: String) {
        val bytes = application.assets.open("magic/$name").use { it.readBytes() }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        var offset = 0
        var first = true
        while (offset < encoded.length) {
            val end = minOf(offset + CHUNK_CHARS, encoded.length)
            runChecked(shell, application,
                "echo -n '${encoded.substring(offset, end)}' ${if (first) ">" else ">>"} $WORK_DIR/$name.b64",
                60, "写入资产 $name")
            first = false
            offset = end
        }
        runChecked(shell, application,
            "base64 -d $WORK_DIR/$name.b64 > $WORK_DIR/$name && rm $WORK_DIR/$name.b64", 60, "解码资产 $name")
        val size = shell.run(application, "wc -c < $WORK_DIR/$name", 15).output.trim().toLongOrNull()
        check(size == bytes.size.toLong()) { "资产 $name 长度校验失败：$size != ${bytes.size}" }
        log("已部署 $name（${bytes.size} B）")
    }

    private fun runChecked(shell: ShellAccess, application: Application, command: String, timeoutSeconds: Long, label: String) {
        val result = shell.run(application, command, timeoutSeconds)
        check(result.exitCode == 0) { "$label 失败（退出码 ${result.exitCode}）：${result.output.take(300)}" }
    }

    private fun log(message: String) {
        val callback = onLog ?: return
        runCatching { callback(message) }
    }

    companion object {
        private const val WORK_DIR = "/data/local/tmp/magic"
        private const val ROOT_DIR = "/data/local/tmp/bootforge"
        private const val ROOT_BIN = "$ROOT_DIR/bfexec"
        /** 低于内核单参数 128 KiB 上限，也低于 ADB 单包上限。 */
        private const val CHUNK_CHARS = 100_000
        internal const val FINISH_NONE = 0
        internal const val FINISH_DONE = 1
        internal const val FINISH_FAIL = 2

        /** 只认整行 DONE / 行首 FAIL=，避免 PHASE2_DONE、LAYOUT_DONE 等中间标志误判。 */
        internal fun finishKind(tail: String): Int {
            for (line in tail.lines()) {
                val s = line.trim()
                if (s == "DONE") return FINISH_DONE
                if (s.startsWith("FAIL=")) return FINISH_FAIL
            }
            return FINISH_NONE
        }

        private val ASSETS = listOf(
            "kaslr_slide", "ko_patch", "addrcalc", "t64560_v36", "cred_target", "t64560_cred",
            "bfexec", "forker", "ksu-template.ko", "syms.txt", "grab.sh", "grab_legacy.sh", "lib.sh", "run.sh", "clean.sh",
        )
    }
}
