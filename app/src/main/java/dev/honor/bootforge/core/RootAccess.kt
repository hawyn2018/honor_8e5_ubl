package dev.honor.bootforge.core

import java.io.File
import java.util.concurrent.TimeUnit

/** Caches only an executable that a fresh process verified as uid 0. Never retries a task. */
internal class RootAccess(
    private val candidates: () -> List<String>,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var validatedPath: String? = null

    @Synchronized
    fun invalidate() { validatedPath = null }

    @Synchronized
    fun check(timeoutSeconds: Long, probe: (String, Long) -> ShellResult): ShellResult {
        require(timeoutSeconds > 0) { "提权检查超时必须大于零" }
        // An explicit check always verifies again, including after a revoked permission.
        validatedPath = null
        val started = nanoTime()
        val budget = TimeUnit.SECONDS.toNanos(timeoutSeconds)
        val diagnostics = mutableListOf<String>()
        var lastCode = 1
        for (path in candidates().distinct()) {
            val remaining = budget - (nanoTime() - started)
            if (remaining <= 0) return ShellResult(124, failureOutput(diagnostics, "提权检查超时"))
            // Round up a partial millisecond, but share one deadline between all candidates.
            val timeoutMillis = TimeUnit.NANOSECONDS.toMillis(remaining - 1) + 1
            val result = try {
                probe(path, timeoutMillis)
            } catch (error: Exception) {
                ShellResult(1, error.message ?: error.javaClass.simpleName)
            }
            if (result.exitCode == 0 && result.output.trim() == "0") {
                validatedPath = path
                return ShellResult(0, "ROOT_UID=0\nSU_PATH=$path\n")
            }
            lastCode = if (result.exitCode == 0) 1 else result.exitCode
            diagnostics += "$path: ${result.output.trim().ifEmpty { "退出码 ${result.exitCode}" }}"
            if (result.exitCode == 124) return ShellResult(124, failureOutput(diagnostics, "提权检查超时"))
        }
        return ShellResult(lastCode, failureOutput(diagnostics, "尚未完成提权"))
    }

    @Synchronized
    fun resolve(probe: (String, Long) -> ShellResult): String {
        validatedPath?.let { return it }
        val result = check(20, probe)
        check(result.exitCode == 0) { result.output }
        return checkNotNull(validatedPath)
    }

    private fun failureOutput(diagnostics: List<String>, message: String): String =
        (listOf("ROOT_UID=unknown", message) + diagnostics).joinToString("\n", postfix = "\n")

    companion object {
        /** Resolve PATH's su first so the cached selection is normally an absolute path. */
        fun candidatePaths(
            path: String?,
            executable: (String) -> Boolean = { File(it).let { file -> file.isFile && file.canExecute() } },
        ): List<String> {
            val fromPath = path.orEmpty().split(File.pathSeparatorChar).asSequence()
                .filter(String::isNotEmpty)
                .map { File(it, "su").absolutePath }
                .firstOrNull(executable)
            return (listOf(fromPath ?: "su") + listOf(
                "/data/adb/ksu/bin/su",
                "/system/xbin/su",
                "/system/bin/su",
                "/sbin/su",
                "/su/bin/su",
                "/debug_ramdisk/su",
                "/data/adb/magisk/su",
                "/data/adb/ap/bin/su",
            )).distinct()
        }
    }
}
