package dev.honor.bootforge.core

import java.io.File

/** A best-effort notification of an already successful FULL OTA transaction. */
internal object OtaCompletionMarker {
    const val PATH = "/data/adb/hnubl/freeotaok"
    // /data/adb is root-owned. A private temporary file and same-directory rename
    // make the consumer see either the previous value or a complete one-byte 1.
    const val WRITE_COMMAND = "umask 077; " +
        "[ ! -L /data/adb/hnubl ] || exit 1; " +
        "mkdir -p /data/adb/hnubl && chmod 700 /data/adb/hnubl || exit 1; " +
        "tmp=\$(mktemp /data/adb/hnubl/.freeotaok.XXXXXX) || exit 1; " +
        "trap 'rm -f \"\$tmp\"' EXIT; " +
        "printf 1 > \"\$tmp\" && chmod 600 \"\$tmp\" || exit 1; " +
        "(sync -f \"\$tmp\" 2>/dev/null || sync) || exit 1; " +
        "mv -f \"\$tmp\" /data/adb/hnubl/freeotaok || exit 1; " +
        "(sync -f /data/adb/hnubl 2>/dev/null || sync) || exit 1; " +
        "[ \"\$(cat /data/adb/hnubl/freeotaok)\" = 1 ] || exit 1; " +
        "printf 'HNUBL_FREEOTAOK=1\\n'"

    data class Result(val marked: Boolean, val logs: List<String>)

    fun write(
        log: File,
        runRoot: (String, File) -> ShellResult = { command, destination -> RootShell.run(command, destination, 20) },
    ): Result = try {
        log.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "创建联动日志目录失败" } }
        val result = runRoot(WRITE_COMMAND, log)
        val marked = result.exitCode == 0 && result.output.lineSequence().any { it == "HNUBL_FREEOTAOK=1" }
        Result(marked, buildList {
            add(if (marked) "[更新联动] 保留锁态 OTA 已成功，freeotaok=1"
                else "[更新联动] 警告：完成标记未写入或未获确认，OTA 成功结果保留；系统更新重启按钮仍可能返回本应用")
            if (result.output.isNotBlank()) addAll(result.output.lines())
        })
    } catch (error: Exception) {
        Result(false, listOf("[更新联动] 警告：完成标记写入失败，OTA 成功结果保留：${error.message ?: error.javaClass.simpleName}"))
    }
}
