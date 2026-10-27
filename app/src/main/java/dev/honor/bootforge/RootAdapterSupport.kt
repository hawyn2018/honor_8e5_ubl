package dev.honor.bootforge

import android.app.Application
import dev.honor.bootforge.core.ShellResult
import dev.honor.bootforge.wireless.ShellAccess
import java.util.Base64

internal const val ROOT_FINISH_NONE = 0
internal const val ROOT_FINISH_DONE = 1
internal const val ROOT_FINISH_FAIL = 2
internal const val ROOT_CHAIN_STATE_FILE = "/data/local/tmp/honor-ubl-root-chain"

internal const val KERNEL_61258 = "6.12.58-android16-6-g3a7ac1674240-abogki541502914-4k"
internal const val KERNEL_6138 = "6.12.38-android16-5-gfde7767f6ef6-abogki481467632-4k"
internal const val ROOT98260_FAILURE_FILE = "/data/local/tmp/honor-ubl-98260-last-failure"
internal const val ROOT98260_ATTEMPTS = 32
internal const val ROOT98260_BUDGET_S = 480
internal const val ROOT98260_SOCKET_WAIT_S = 600
internal const val ROOT98260_ZOMBIE_STOP_S = 15

internal fun rootProfileForKernel(release: String): String? = when (release.trim()) {
    KERNEL_61258 -> "61258"
    KERNEL_6138 -> "6138"
    else -> null
}

internal fun rootFinishKind(chain: RootChain, log: String): Int {
    for (line in log.lines()) {
        val value = line.trim()
        if (value.startsWith("FAIL=")) return ROOT_FINISH_FAIL
        when (chain) {
            RootChain.CVE_2026_64650 -> if (value == "DONE") return ROOT_FINISH_DONE
            RootChain.CVE_2026_98260 -> when {
                value == "98260_CHAIN_END=OK" -> return ROOT_FINISH_DONE
                value.startsWith("98260_CHAIN_END=FAIL") -> return ROOT_FINISH_FAIL
            }
        }
    }
    return ROOT_FINISH_NONE
}

internal fun rootIsProofOrParked(log: String): Boolean = listOf(
    "98260_PARKED=1",
    "stage_already_started",
    "98260_BOOT_CHANGED",
    "FAIL=proof",
    "98260_CHAIN_END=FAIL code=76",
    "98260_CHAIN_END=FAIL code=77",
    "98260_CHAIN_END=FAIL code=80",
    "HINT=do NOT kill; reboot the phone and retry",
).any(log::contains)

internal fun rootSwitchBlocked(recordedState: String, currentBoot: String, selected: RootChain): Boolean {
    val fields = recordedState.trim().split(Regex("\\s+"))
    return currentBoot.isNotBlank() && fields.size == 2 && fields[0] == currentBoot && fields[1] != selected.id
}

internal fun checkRootChainAllowed(shell: ShellAccess, application: Application, selected: RootChain) {
    val boot = rootBootId(shell, application)
    val recorded = shell.run(application, "cat $ROOT_CHAIN_STATE_FILE 2>/dev/null", 10).output
    check(!rootSwitchBlocked(recorded, boot, selected)) {
        val other = if (selected == RootChain.CVE_2026_64650) "98260" else "64650"
        "本次开机已使用 $other 提权成功；切换前必须先重启设备"
    }
}

internal fun markRootChainSuccess(shell: ShellAccess, application: Application, selected: RootChain) {
    val boot = rootBootId(shell, application)
    runRootChecked(shell, application,
        "printf '%s %s\\n' '$boot' '${selected.id}' > $ROOT_CHAIN_STATE_FILE", 15, "记录提权链归属")
}

private fun rootBootId(shell: ShellAccess, application: Application): String {
    val result = runRootChecked(shell, application, "cat /proc/sys/kernel/random/boot_id", 10, "读取启动周期")
    return result.output.trim().also { check(it.isNotBlank()) { "无法读取设备启动周期" } }
}

internal fun runRootChecked(
    shell: ShellAccess,
    application: Application,
    command: String,
    timeoutSeconds: Long,
    label: String,
): ShellResult {
    val result = shell.run(application, command, timeoutSeconds)
    check(result.exitCode == 0) { "$label 失败（退出码 ${result.exitCode}）：${result.output.take(500)}" }
    return result
}

internal fun pushRootAsset(
    shell: ShellAccess,
    application: Application,
    assetPath: String,
    destination: String,
) {
    val bytes = application.assets.open(assetPath).use { it.readBytes() }
    val parent = destination.substringBeforeLast('/', "")
    if (parent.isNotEmpty()) runRootChecked(shell, application, "mkdir -p '$parent'", 15, "创建资产目录")
    val encoded = Base64.getEncoder().encodeToString(bytes)
    var offset = 0
    var first = true
    while (offset < encoded.length) {
        val end = minOf(offset + 100_000, encoded.length)
        runRootChecked(shell, application,
            "echo -n '${encoded.substring(offset, end)}' ${if (first) ">" else ">>"} '$destination.b64'",
            60, "写入资产 $assetPath")
        first = false
        offset = end
    }
    runRootChecked(shell, application,
        "base64 -d '$destination.b64' > '$destination.new' && chmod 755 '$destination.new' && " +
            "mv -f '$destination.new' '$destination' && rm '$destination.b64'",
        60, "解码资产 $assetPath")
    val size = shell.run(application, "wc -c < '$destination'", 15).output.trim().toLongOrNull()
    check(size == bytes.size.toLong()) { "资产 $assetPath 长度校验失败：$size != ${bytes.size}" }
}
