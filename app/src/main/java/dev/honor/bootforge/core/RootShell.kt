package dev.honor.bootforge.core

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ShellResult(val exitCode: Int, val output: String)

internal data class CustomSuProof(val path: String, val result: ShellResult)

fun interface RootProcessProvider {
    /** Start a fresh process promptly, with distinct stdout and stderr streams. */
    fun start(command: String): Process
}

object RootShell {
    @Volatile private var customSu = CustomSuSettings()
    @Volatile private var customSuReader: (() -> CustomSuSettings)? = null
    private var configurationRevision = 0L
    internal var startSuProcess: (List<String>) -> Process = { ProcessBuilder(it).start() }
    private val access = RootAccess({ RootAccess.candidatePaths(System.getenv("PATH"), customSu) })
    @Volatile private var integratedProvider: RootProcessProvider? = null
    @Volatile private var wirelessProvider: RootProcessProvider? = null
    @Volatile private var moduleProvider: RootProcessProvider? = null
    @Volatile private var moduleCandidate: (() -> RootProcessProvider?)? = null
    @Volatile private var preferExternalSu = false

    /** Every app/service process installs the same private-file reader at Application startup. */
    @Synchronized
    fun configureCustomSuSettings(reader: (() -> CustomSuSettings)?) {
        customSuReader = reader
        customSu = CustomSuSettings()
        configurationRevision++
        invalidate()
    }

    @Synchronized
    fun customSuSettings(): CustomSuSettings {
        val current = try { customSuReader?.invoke() ?: CustomSuSettings() }
        catch (error: Exception) {
            configurationRevision++
            invalidate()
            throw error
        }
        if (current != customSu) {
            customSu = current
            configurationRevision++
            invalidate()
        }
        require(current.validationError() == null) { current.validationError().orEmpty() }
        return current
    }

    /** Custom mode is exclusive: a failed proof never falls back to an old provider. */
    @Synchronized
    internal fun checkCustomSu(log: File, timeoutMillis: Long): CustomSuProof? {
        val settings = customSuSettings()
        if (!settings.enabled) return null
        val checked = access.checkMillis(timeoutMillis) { path, remaining -> probe(path, remaining, log) }
        val result = ShellResult(checked.exitCode, checked.output + "ROOT_PROVIDER=custom-su\n")
        try { log.writeText(result.output) }
        catch (error: Exception) { access.invalidate(); throw error }
        return CustomSuProof(settings.path, result)
    }

    /** Each check starts a new process; a failed operation is never replayed. */
    @Synchronized
    fun check(log: File, timeoutSeconds: Long = 20): ShellResult {
        require(timeoutSeconds > 0)
        checkCustomSu(log, TimeUnit.SECONDS.toMillis(timeoutSeconds))?.let { return it.result }
        val wireless = wirelessProvider
        val provider = wireless ?: if (preferExternalSu) null else integratedProvider
        val result = if (provider != null) {
            access.invalidate()
            val checked = probe({ provider.start("id -u") }, TimeUnit.SECONDS.toMillis(timeoutSeconds), log)
            if (checked.exitCode == 0 && checked.output.trim() == "0") providerSuccess(wireless != null)
            else {
                // A failed proof is not reused by a subsequent partition operation.
                if (wireless != null) {
                    if (wirelessProvider === provider) wirelessProvider = null
                } else if (integratedProvider === provider) integratedProvider = null
                ShellResult(if (checked.exitCode == 0) 1 else checked.exitCode,
                    "ROOT_UID=unknown\n${checked.output}\n")
            }
        } else {
            val module = checkModule(log, TimeUnit.SECONDS.toMillis(timeoutSeconds))
            if (module?.exitCode == 0) module
            else access.check(timeoutSeconds) { path, timeoutMillis -> probe(path, timeoutMillis, log) }
        }
        if (wireless == null && provider == null && result.exitCode != 0) preferExternalSu = false
        log.writeText(result.output)
        return result
    }

    /** Check installed-module delegation first, then ask the existing su manager from the app UID. */
    @Synchronized
    fun requestExternalSu(log: File, timeoutSeconds: Long = 20): ShellResult {
        require(timeoutSeconds > 0)
        checkCustomSu(log, TimeUnit.SECONDS.toMillis(timeoutSeconds))?.let { return it.result }
        val module = checkModule(log, TimeUnit.SECONDS.toMillis(timeoutSeconds))
        if (module?.exitCode == 0) return module
        val checked = access.check(timeoutSeconds) { path, timeoutMillis -> probe(path, timeoutMillis, log) }
        val result = ShellResult(checked.exitCode, checked.output + "ROOT_PROVIDER=external-su\n")
        try { log.writeText(result.output) }
        catch (error: Exception) { preferExternalSu = false; access.invalidate(); throw error }
        preferExternalSu = result.exitCode == 0
        return result
    }

    /** Discovery is unprivileged; a candidate becomes a launcher only after a fresh UID proof. */
    @Synchronized
    fun configureModuleProvider(candidate: (() -> RootProcessProvider?)?) {
        moduleCandidate = candidate
        moduleProvider = null
    }

    /** Only the read-only identity probe may fall back to an existing authorization path. */
    @Synchronized
    internal fun checkModule(log: File, timeoutMillis: Long): ShellResult? {
        require(timeoutMillis > 0)
        if (customSuSettings().enabled) return null
        val candidate = moduleProvider ?: try { moduleCandidate?.invoke() } catch (_: Exception) { null }
            ?: return null
        val checked = probe({ candidate.start("id -u") }, timeoutMillis, log)
        val granted = checked.exitCode == 0 && checked.output.trim() == "0"
        val result = if (granted) ShellResult(0, "ROOT_UID=0\nROOT_PROVIDER=module\n") else ShellResult(
            if (checked.exitCode == 0) 1 else checked.exitCode, "ROOT_UID=unknown\n${checked.output}\n")
        if (!granted && moduleProvider === candidate) moduleProvider = null
        // Do not publish a proof whose diagnostic file could not be recorded.
        log.writeText(result.output)
        if (granted) {
            moduleProvider = candidate
            access.invalidate()
        }
        return result
    }

    /** Register only after this app starts its own fresh, verified uid-0 process. */
    @Synchronized
    fun setIntegratedProvider(provider: RootProcessProvider, log: File, timeoutSeconds: Long = 20): ShellResult {
        require(timeoutSeconds > 0)
        if (customSuSettings().enabled) return customProviderSelected()
        val checked = probe({ provider.start("id -u") }, TimeUnit.SECONDS.toMillis(timeoutSeconds), log)
        val granted = checked.exitCode == 0 && checked.output.trim() == "0"
        val result = if (granted) providerSuccess(false) else ShellResult(if (checked.exitCode == 0) 1 else checked.exitCode,
            "ROOT_UID=unknown\n${checked.output}\n")
        log.writeText(result.output)
        if (granted) {
            access.invalidate()
            integratedProvider = provider
            preferExternalSu = false
        }
        return result
    }

    /** The supplied provider owns a fixed, already authenticated ADB endpoint. */
    @Synchronized
    fun setWirelessProvider(provider: RootProcessProvider, log: File, timeoutSeconds: Long = 20): ShellResult {
        require(timeoutSeconds > 0)
        if (customSuSettings().enabled) return customProviderSelected()
        val checked = probe({ provider.start("id -u") }, TimeUnit.SECONDS.toMillis(timeoutSeconds), log)
        val granted = checked.exitCode == 0 && checked.output.trim() == "0"
        val result = if (granted) providerSuccess(true) else ShellResult(
            if (checked.exitCode == 0) 1 else checked.exitCode, "ROOT_UID=unknown\n${checked.output}\n")
        // Record the proof before publishing a replacement, just like integrated providers.
        log.writeText(result.output)
        if (granted) {
            access.invalidate()
            wirelessProvider = provider
        }
        return result
    }

    @Synchronized
    fun clearWirelessProvider(provider: RootProcessProvider): Boolean {
        if (wirelessProvider !== provider) return false
        wirelessProvider = null
        return true
    }

    @Synchronized fun hasWirelessProvider(): Boolean = !customSuSettings().enabled && wirelessProvider != null
    @Synchronized fun hasIntegratedProvider(): Boolean = !customSuSettings().enabled && integratedProvider != null
    @Synchronized fun hasModuleProvider(): Boolean = !customSuSettings().enabled && moduleProvider != null

    /** Cleaning an old integrated backend must not revoke an independent ADB grant. */
    @Synchronized
    fun invalidateIntegrated() {
        integratedProvider = null
    }

    /** Explicit invalidation clears both cached su and the in-process provider proof. */
    @Synchronized
    fun invalidate() {
        preferExternalSu = false
        wirelessProvider = null
        integratedProvider = null
        moduleProvider = null
        access.invalidate()
    }

    fun capture(command: String, destination: File, errorLog: File, timeoutSeconds: Long = 60): ShellResult {
        require(timeoutSeconds > 0)
        require(destination.canonicalFile != errorLog.canonicalFile) { "二进制输出和错误日志必须使用不同文件" }
        // Authorization may prompt the user. Resolve it before files open or the task deadline starts.
        val start = resolveLauncher(command, errorLog)
        return RootProcessIO.collect(start, destination, errorLog,
            TimeUnit.SECONDS.toMillis(timeoutSeconds), "备份读取超时，请检查日志")
    }
    // Read-only inspection has a timeout. Writing deliberately has no process-kill timeout.
    fun run(command: String, log: File, timeoutSeconds: Long = 0): ShellResult {
        require(timeoutSeconds >= 0)
        val start = resolveLauncher(command, log)
        return RootProcessIO.collect(start, log, log,
            if (timeoutSeconds > 0) TimeUnit.SECONDS.toMillis(timeoutSeconds) else 0,
            "提权检查超时，请检查超级用户授权状态")
    }

    @Synchronized
    private fun resolveLauncher(command: String, log: File): () -> Process {
        val custom = customSuSettings()
        val revision = configurationRevision
        if (custom.enabled) {
            val su = access.resolve { path, timeoutMillis -> probe(path, timeoutMillis, log) }
            return pinned(revision) { startSuProcess(suCommand(su, command)) }
        }
        val wireless = wirelessProvider
        if (wireless != null) return pinned(revision) { wireless.start(command) }
        val integrated = integratedProvider
        if (!preferExternalSu && integrated != null) return pinned(revision) { integrated.start(command) }
        val existingModule = moduleProvider
        if (existingModule != null) return pinned(revision) { existingModule.start(command) }
        // Discovery can submit only id -u here. The requested task starts exactly
        // once, after the provider has been pinned; its failures are never replayed.
        if (checkModule(log, TimeUnit.SECONDS.toMillis(20))?.exitCode == 0) {
            val module = checkNotNull(moduleProvider)
            return pinned(revision) { module.start(command) }
        }
        val su = access.resolve { path, timeoutMillis -> probe(path, timeoutMillis, log) }
        // Pin exactly one launcher. A failed task is never replayed using a different provider.
        return pinned(revision) { startSuProcess(suCommand(su, command)) }
    }

    private fun pinned(revision: Long, start: () -> Process): () -> Process = {
        synchronized(this) {
            customSuSettings()
            check(revision == configurationRevision) { "提权设置已变更，请重新执行操作" }
            start()
        }
    }

    internal fun suCommand(path: String, command: String): List<String> = listOf(path, "-c", command)

    private fun customProviderSelected() = ShellResult(1,
        "ROOT_UID=unknown\nROOT_PROVIDER=custom-su\n已启用自定义 su，请通过所选路径检查提权\n")

    private fun providerSuccess(wireless: Boolean) = ShellResult(0,
        "ROOT_UID=0\nROOT_PROVIDER=${if (wireless) "wireless-adb" else "integrated"}\n")

    private fun probe(path: String, timeoutMillis: Long, log: File): ShellResult =
        probe({ startSuProcess(suCommand(path, "id -u")) }, timeoutMillis, log)

    private fun probe(start: () -> Process, timeoutMillis: Long, log: File): ShellResult {
        val directory = checkNotNull(log.absoluteFile.parentFile)
        val stdout = File.createTempFile("root-check-out-", ".log", directory)
        val stderr = File.createTempFile("root-check-err-", ".log", directory)
        try {
            val result = RootProcessIO.collect(start, stdout, stderr, timeoutMillis,
                "提权检查超时，请检查超级用户授权状态")
            val output = stdout.readText()
            // Parse stdout alone: su notices on stderr must not invalidate a real uid 0.
            return ShellResult(result.exitCode, if (result.exitCode == 0 && output.trim() == "0") output
                else listOf(output, result.output).filter(String::isNotBlank).joinToString("\n"))
        } catch (error: RootProcessTimeout) {
            return ShellResult(124, error.message.orEmpty())
        } catch (error: Exception) {
            return ShellResult(1, error.message ?: error.javaClass.simpleName)
        } finally {
            stdout.delete()
            stderr.delete()
        }
    }
}

internal class RootProcessTimeout(message: String) : IllegalStateException(message)

/** Drain both pipes concurrently; dd's binary stdout never shares a stream with stderr. */
internal object RootProcessIO {
    fun collect(
        start: () -> Process,
        destination: File,
        errorLog: File,
        timeoutMillis: Long,
        timeoutMessage: String,
        openOutput: (File) -> OutputStream = { FileOutputStream(it) },
    ): ShellResult {
        val failures = ConcurrentLinkedQueue<Throwable>()
        // Open output files before any task starts; an unwritable log must not launch a write.
        val stdout = openOutput(destination)
        val stderr = try {
            if (destination.canonicalFile == errorLog.canonicalFile) stdout else openOutput(errorLog)
        } catch (error: Exception) { stdout.close(); throw error }
        fun drain(input: InputStream, output: OutputStream) = thread(name = "BootForge-root-pipe", isDaemon = true) {
            try {
                input.use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        synchronized(output) { output.write(buffer, 0, count) }
                    }
                }
            } catch (error: Throwable) { failures += error }
        }
        var process: Process? = null
        var completed = false
        val started = System.nanoTime()
        fun remainingMillis(): Long =
            (timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).coerceAtLeast(0)
        try {
            val running = start().also { process = it }
            running.outputStream.close()
            val outputThread = drain(running.inputStream, stdout)
            val errorThread = drain(running.errorStream, stderr)
            completed = if (timeoutMillis > 0) running.waitFor(remainingMillis(), TimeUnit.MILLISECONDS)
                else { running.waitFor(); true }
            if (completed) {
                if (timeoutMillis > 0) {
                    val outputWait = remainingMillis()
                    if (outputWait > 0) outputThread.join(outputWait)
                    val errorWait = remainingMillis()
                    if (errorWait > 0) errorThread.join(errorWait)
                    completed = !outputThread.isAlive && !errorThread.isAlive
                } else {
                    outputThread.join()
                    errorThread.join()
                }
            }
            if (!completed) {
                stop(running)
                // A provider retaining an inherited pipe must not defeat the check timeout.
                running.inputStream.close()
                running.errorStream.close()
                outputThread.join(250)
                errorThread.join(250)
                throw RootProcessTimeout(timeoutMessage)
            }
            failures.peek()?.let { throw IllegalStateException("读取提权进程输出失败", it) }
            stdout.flush()
            if (stderr !== stdout) stderr.flush()
            return ShellResult(running.exitValue(), errorLog.readText())
        } finally {
            // No timeout is imposed on an active writing task. Cleanup only follows completion/error.
            try {
                process?.let { if (!completed && it.isAlive) stop(it) }
            } finally {
                try { stdout.close() }
                finally { if (stderr !== stdout) stderr.close() }
            }
        }
    }

    private fun stop(process: Process) {
        // A cancellation can interrupt this cleanup too. Keep its bounded waits,
        // finish destruction, then restore the flag without replacing the original error.
        var interrupted = Thread.interrupted()
        fun awaitExit(): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return !process.isAlive
                try { return process.waitFor(remaining, TimeUnit.NANOSECONDS) }
                catch (_: InterruptedException) { interrupted = true }
            }
        }
        try {
            process.destroy()
            if (!awaitExit()) {
                process.destroyForcibly()
                awaitExit()
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
