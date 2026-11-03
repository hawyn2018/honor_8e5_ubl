package dev.honor.bootforge.core

import java.io.File
import java.util.concurrent.TimeUnit

/** A fresh module/external-su check; never consults or starts the integrated backend. */
object ExternalRootProbe {
    const val DEFAULT_TIMEOUT_MILLIS = 3_000L

    data class Result(val granted: Boolean, val suPath: String? = null, val timedOut: Boolean = false)

    /** Run on an IO dispatcher. A root manager may show its ordinary authorization prompt. */
    fun check(log: File, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Result {
        require(timeoutMillis in 1..10_000) { "External Root probe timeout is out of range" }
        val directory = checkNotNull(log.absoluteFile.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "External Root probe log directory is unavailable" }
        val custom = RootShell.checkCustomSu(log, timeoutMillis)
        if (custom != null) return Result(custom.result.exitCode == 0,
            if (custom.result.exitCode == 0) custom.path else null,
            timedOut = custom.result.exitCode == 124)
        val started = System.nanoTime()
        val module = RootShell.checkModule(log, timeoutMillis)
        if (module?.exitCode == 0) return Result(true)
        val remaining = timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        if (remaining <= 0) return Result(false, timedOut = true)
        return check(log, remaining, RootAccess.candidatePaths(System.getenv("PATH")),
            System::nanoTime, { command -> ProcessBuilder(command).start() })
    }

    internal fun check(
        log: File,
        timeoutMillis: Long,
        candidates: List<String>,
        nanoTime: () -> Long,
        start: (List<String>) -> Process,
    ): Result {
        require(timeoutMillis in 1..10_000) { "External Root probe timeout is out of range" }
        val directory = checkNotNull(log.absoluteFile.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "External Root probe log directory is unavailable" }
        val diagnostics = mutableListOf<String>()
        val result = select(candidates, timeoutMillis, nanoTime) { path, remaining ->
            val stdout = File.createTempFile("external-root-out-", ".log", directory)
            val stderr = File.createTempFile("external-root-err-", ".log", directory)
            try {
                val process = RootProcessIO.collect({ start(listOf(path, "-c", "id -u")) }, stdout, stderr,
                    remaining, "External Root authorization check timed out")
                val output = stdout.readText()
                val error = stderr.readText()
                diagnostics += "$path: exit=${process.exitCode}\nstdout=$output\nstderr=$error"
                Attempt(process.exitCode, output)
            } catch (error: RootProcessTimeout) {
                diagnostics += "$path: authorization check timed out"
                Attempt(124, "", timedOut = true)
            } finally {
                stdout.delete()
                stderr.delete()
            }
        }
        log.writeText(buildString {
            append(if (result.granted) "ROOT_UID=0\nROOT_PROVIDER=external-su\nSU_PATH=${result.suPath}\n"
                else "ROOT_UID=unknown\nROOT_PROVIDER=external-su\n")
            append("TIMED_OUT=${result.timedOut}\n")
            append(diagnostics.joinToString("\n"))
        })
        return result
    }

    internal data class Attempt(val exitCode: Int, val stdout: String, val timedOut: Boolean = false)

    internal fun select(
        candidates: List<String>,
        timeoutMillis: Long,
        nanoTime: () -> Long,
        probe: (String, Long) -> Attempt,
    ): Result {
        require(timeoutMillis in 1..10_000)
        val started = nanoTime()
        val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        for (path in candidates.distinct()) {
            val remaining = budget - (nanoTime() - started)
            if (remaining <= 0) return Result(false, timedOut = true)
            val attempt = try {
                probe(path, TimeUnit.NANOSECONDS.toMillis(remaining - 1) + 1)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result(false)
            } catch (_: Exception) {
                continue
            }
            if (attempt.timedOut || attempt.exitCode == 124 || nanoTime() - started >= budget)
                return Result(false, timedOut = true)
            if (attempt.exitCode == 0 && attempt.stdout.trim() == "0") return Result(true, path)
        }
        return Result(false)
    }
}
