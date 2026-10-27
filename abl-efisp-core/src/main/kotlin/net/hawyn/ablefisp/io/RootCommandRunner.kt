package net.hawyn.ablefisp.io

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class RootCommandResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)

internal fun interface RootCommandRunner {
    fun run(command: String, maxBytes: Int, timeoutMillis: Long): RootCommandResult
}

/** Keeps binary stdout separate from diagnostics, and drains both pipes concurrently. */
internal class SuCommandRunner(private val executable: String = "su") : RootCommandRunner {
    override fun run(command: String, maxBytes: Int, timeoutMillis: Long): RootCommandResult {
        require(timeoutMillis > 0)
        val started = System.nanoTime()
        val process = try {
            ProcessBuilder(executable, "-c", command).start()
        } catch (error: Exception) {
            throw AblReadException("未找到可用的 su，请检查 Root 管理器", error)
        }
        val pool = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "AblPatcher-root-pipe").apply { isDaemon = true }
        }
        fun remaining(): Long = (timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).coerceAtLeast(0)
        try {
            process.outputStream.close()
            val stdout = pool.submit<ByteArray> {
                try {
                    process.inputStream.use { ImageIo.readBounded(it, maxBytes) }
                } catch (error: Exception) {
                    process.destroyForcibly()
                    throw error
                }
            }
            val stderr = pool.submit<String> {
                process.errorStream.use { readDiagnostics(it) }
            }
            if (!process.waitFor(remaining(), TimeUnit.MILLISECONDS)) {
                throw AblReadException("Root 操作超时，请检查授权状态后重试")
            }
            val budget = remaining()
            if (budget <= 0) throw AblReadException("Root 操作超时，请检查授权状态后重试")
            val bytes = stdout.get(budget, TimeUnit.MILLISECONDS)
            val diagnosticBudget = remaining()
            if (diagnosticBudget <= 0) throw AblReadException("Root 操作超时，请检查授权状态后重试")
            return RootCommandResult(process.exitValue(), bytes, stderr.get(diagnosticBudget, TimeUnit.MILLISECONDS))
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            if (cause is AblReadException) throw cause
            throw AblReadException("读取 Root 输出失败", cause)
        } catch (error: TimeoutException) {
            throw AblReadException("Root 操作超时，请检查授权状态后重试", error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AblReadException("Root 操作已取消", error)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            pool.shutdownNow()
        }
    }

    /** Retain a small summary while continuing to drain all stderr to avoid pipe deadlocks. */
    private fun readDiagnostics(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0 && output.size() < 16 * 1024) {
                output.write(buffer, 0, minOf(count, 16 * 1024 - output.size()))
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
