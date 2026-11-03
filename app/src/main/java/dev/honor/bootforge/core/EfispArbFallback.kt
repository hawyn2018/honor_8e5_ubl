package dev.honor.bootforge.core

import java.io.File

/** Only the two generated candidates are inspected; the last built-in candidate is adopted directly. */
object EfispArbFallback {
    enum class Source { SELECTED, CURRENT_SLOT_ABL, BUILTIN_ABL }
    data class Prepared(
        val file: File,
        val source: Source,
        val sourceSha256: String?,
        val rollback: AblRollbackInspector.Result?,
        val arbBinding: String,
        val patchResult: EfispImagePatcher.Result? = null,
    )

    fun prepare(initial: File, currentAbl: () -> File, builtinAbl: () -> File,
        outputDirectory: File, fakeLock: Boolean, onLog: (String) -> Unit = {}): Prepared =
        prepareWith(initial, currentAbl, builtinAbl, outputDirectory, fakeLock, onLog,
            AblRollbackGuardPolicy::inspectReadable, EfispImagePatcher::patch)

    fun prepareWithRetry(initial: File, currentAbl: () -> File, builtinAbl: () -> File,
        outputDirectory: File, fakeLock: Boolean, onLog: (String) -> Unit,
        onPatchFailure: ((Exception) -> Boolean)?,
        patch: (File, File, Boolean, (String) -> Unit) -> EfispImagePatcher.Result = EfispImagePatcher::patch): Prepared =
        prepareWith(initial, currentAbl, builtinAbl, outputDirectory, fakeLock, onLog,
            AblRollbackGuardPolicy::inspectReadable, patch, onPatchFailure)

    internal fun prepareWith(initial: File, currentAbl: () -> File, builtinAbl: () -> File,
        outputDirectory: File, fakeLock: Boolean, onLog: (String) -> Unit,
        inspect: (File) -> AblRollbackInspector.Result,
        patch: (File, File, Boolean, (String) -> Unit) -> EfispImagePatcher.Result,
        onPatchFailure: ((Exception) -> Boolean)? = null): Prepared {
        fun log(message: String) { runCatching { onLog(message) } }
        fun checked(file: File, source: Source, patched: EfispImagePatcher.Result? = null): Prepared? {
            val result = inspect(file)
            when (result) {
                is AblRollbackInspector.Result.Parsed -> {
                    if (result.counters.isEmpty()) log("[防回滚] $source 成品未读到计数，按无计数采用")
                    result.counters.forEach { log("[防回滚] $source 成品 · ${it.authority}=${it.value} · ${it.source}") }
                }
                is AblRollbackInspector.Result.NoMetadata -> log("[防回滚] $source 成品无防回滚计数，直接采用")
                is AblRollbackInspector.Result.Invalid -> log("[防回滚] $source 成品未读到计数，按无计数采用：${result.reason}")
            }
            if (AblRollbackGuardPolicy.finalEfisp(result) == AblRollbackGuardPolicy.Decision.Unsupported) return null
            return Prepared(file, source, patched?.sourceSha256, result,
                AblRollbackGuardPolicy.bindingValue(result), patched)
        }
        log("[防回滚] 检查所选来源生成或上传的最终 efisp 成品")
        checked(initial, Source.SELECTED)?.let { return it }
        log("[防回滚回退] 所选成品计数 >=1；使用已固定的当前槽位 ABL 重新生成，保留原修补模式与文字定制")
        val currentOutput = File(outputDirectory, "efisp-current-fallback.img")
        // Source acquisition errors remain I/O failures; only a failed patch is retryable.
        val currentSource = currentAbl()
        val builtinOutput = File(outputDirectory, "efisp-builtin-fallback.img")
        val current = ImagePatchRetry.run(PatchRetryTask.OTA, onPatchFailure != null,
            primary = { patch(currentSource, currentOutput, fakeLock, onLog) },
            builtin = {
                val builtinSource = builtinAbl()
                log("[修补重试] 用户选择内置 ABL，继续本次 OTA 流程并保留原修补模式")
                patch(builtinSource, builtinOutput, fakeLock, onLog)
            }, confirm = { checkNotNull(onPatchFailure)(it) })
        if (current.usedBuiltin) return Prepared(builtinOutput, Source.BUILTIN_ABL,
            current.value.sourceSha256, null, "builtin-unchecked", current.value)
        checked(currentOutput, Source.CURRENT_SLOT_ABL, current.value)?.let { return it }
        log("[防回滚回退] 当前槽位生成成品计数仍 >=1；使用内置 ABL 重新生成并直接采用，不再检查最终防回滚计数")
        val builtin = patch(builtinAbl(), builtinOutput, fakeLock, onLog)
        return Prepared(builtinOutput, Source.BUILTIN_ABL, builtin.sourceSha256, null, "builtin-unchecked", builtin)
    }
}
