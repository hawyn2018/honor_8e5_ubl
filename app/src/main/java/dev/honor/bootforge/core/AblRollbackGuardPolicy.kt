package dev.honor.bootforge.core

import java.io.File

object AblRollbackGuardPolicy {
    sealed class Decision {
        data object Allowed : Decision()
        data object Unsupported : Decision()
    }
    fun finalEfisp(result: AblRollbackInspector.Result): Decision = decide(result)
    private fun decide(result: AblRollbackInspector.Result): Decision = when (result) {
        is AblRollbackInspector.Result.Parsed -> if (result.counters.any { it.value >= 1 }) Decision.Unsupported
            else Decision.Allowed
        is AblRollbackInspector.Result.NoMetadata, is AblRollbackInspector.Result.Invalid -> Decision.Allowed
    }

    fun bindingValue(result: AblRollbackInspector.Result): String =
        if (result is AblRollbackInspector.Result.Parsed && result.counters.isNotEmpty() &&
            result.counters.all { it.value == 0L }) "0" else "absent"

    /** Parsing an unknown format is permitted; a failed read is a separate, fatal operation. */
    fun inspectReadable(file: File): AblRollbackInspector.Result {
        check(file.isFile) { "防回滚检查源文件缺失：${file.name}" }
        val length = file.length()
        check(length in 1..AblRollbackInspector.MAX_IMAGE_BYTES.toLong()) { "防回滚检查源文件大小无效：${file.name}" }
        val bytes = ByteArray(length.toInt())
        file.inputStream().use { input ->
            var at = 0
            while (at < bytes.size) {
                val read = input.read(bytes, at, bytes.size - at)
                check(read > 0) { "防回滚检查源文件读取不完整：${file.name}" }
                at += read
            }
            check(input.read() == -1 && file.length() == length) { "防回滚检查源文件读取时大小变化：${file.name}" }
        }
        return AblRollbackInspector.inspect(bytes)
    }
}
