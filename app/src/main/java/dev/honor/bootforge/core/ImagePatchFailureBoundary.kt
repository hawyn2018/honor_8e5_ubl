package dev.honor.bootforge.core

import java.io.IOException

/** A failed local engine call is an operation failure, never a successful image. */
class ImagePatchExecutionException(error: Error) : IOException(
    "修补器执行异常（${error.javaClass.simpleName}）：${error.message.orEmpty()}；本次修补已停止，请保留输入文件与日志", error)

internal object ImagePatchFailureBoundary {
    // Only errors an unsupported/complex input or unavailable engine dependency
    // can surface at this boundary. VM death/OOM and cancellation retain their
    // own contracts; no Throwable-wide success fallback or transaction replay.
    fun reportable(error: Error): ImagePatchExecutionException? = when (error) {
        is AssertionError, is StackOverflowError, is LinkageError -> ImagePatchExecutionException(error)
        else -> null
    }

    fun <T> run(action: () -> T): T = try { action() } catch (error: Error) {
        throw (reportable(error) ?: error)
    }
}
