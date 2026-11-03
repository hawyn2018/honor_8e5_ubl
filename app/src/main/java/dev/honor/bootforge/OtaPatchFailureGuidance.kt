package dev.honor.bootforge

import java.io.IOException
import java.util.concurrent.CancellationException

/** Marks only an actual patch failure, not extraction, export-provider or partition I/O. */
internal class OtaImagePatchFailure(cause: Exception) : IOException(cause.message, cause)

internal object OtaPatchFailureGuidance {
    fun <T> patch(operation: OtaOperation, action: () -> T): T = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedException) {
        throw interrupted
    } catch (failure: Exception) {
        if (operation == OtaOperation.FULL || failure is OtaImagePatchFailure) throw failure
        throw OtaImagePatchFailure(failure)
    }

    fun decide(operation: OtaOperation, failure: Exception, confirm: (Exception) -> Boolean): Boolean =
        if (operation == OtaOperation.FULL) confirm(failure)
        else throw (failure as? OtaImagePatchFailure ?: OtaImagePatchFailure(failure))

    fun message(operation: OtaOperation, failure: Exception): String {
        if (failure !is OtaImagePatchFailure) return ""
        return when (operation) {
            OtaOperation.FULL -> ""
            OtaOperation.EFISP_ONLY -> "刷写efisp失败，可选择内置ABL修补选项后重试刷写流程"
            OtaOperation.EXPORT_EFISP -> "导出efisp失败，可选择内置ABL修补选项后重试导出流程"
        }
    }
}
