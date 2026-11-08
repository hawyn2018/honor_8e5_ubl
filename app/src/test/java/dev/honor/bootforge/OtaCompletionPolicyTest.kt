package dev.honor.bootforge

import dev.honor.bootforge.core.OtaCompletionMarker
import dev.honor.bootforge.core.KernelSuHandoff
import org.junit.Assert.*
import org.junit.Test

class OtaCompletionPolicyTest {
    @Test fun completionOffersOnlyTheMatchingAction() {
        val cases = mapOf(
            OtaOperation.FULL to OtaCompletionAction.OPEN_KERNEL_SU,
            OtaOperation.EFISP_ONLY to OtaCompletionAction.REBOOT_TO_TRY_EFISP,
            OtaOperation.EXPORT_EFISP to OtaCompletionAction.NONE,
        )
        for ((operation, expected) in cases) {
            assertEquals(expected, OtaCompletionPolicy.action(operation))
            for (action in OtaCompletionAction.entries) {
                assertEquals(action == expected && action != OtaCompletionAction.NONE,
                    OtaCompletionPolicy.available(operation, action, completed = true, busy = false))
            }
        }
        assertEquals("KernelSU 对槽安装", OtaCompletionAction.OPEN_KERNEL_SU.label)
        assertEquals("重启体验新版efisp", OtaCompletionAction.REBOOT_TO_TRY_EFISP.label)
        assertFalse(OtaCompletionPolicy.stage(OtaOperation.EFISP_ONLY).contains("返回软件更新"))
    }

    @Test fun busyOrIncompleteNeverDispatches() {
        OtaOperation.entries.forEach { operation ->
            val action = OtaCompletionPolicy.action(operation)
            assertFalse(OtaCompletionPolicy.available(operation, action, completed = false, busy = false))
            assertFalse(OtaCompletionPolicy.available(operation, action, completed = true, busy = true))
        }
    }

    @Test fun successfulFullTransactionMarksBeforeOpeningKernelSu() {
        val order = mutableListOf<String>()
        val result = OtaCompletionFlow.afterSuccess(OtaOperation.FULL,
            writeMarker = { order += "marker=1"; OtaCompletionMarker.Result(true, listOf("marker proof")) },
            openKernelSu = { order += "open KernelSU"; KernelSuHandoff.Result(true, listOf("opened")) })
        assertEquals(listOf("marker=1", "open KernelSU"), order)
        assertEquals(true, result.markerReady)
        assertEquals(listOf("marker proof", "opened"), result.logs)
    }

    @Test fun markerFailureStillTriesKernelSuAndPreservesTheSuccessfulOperation() {
        var opened = 0
        val result = OtaCompletionFlow.afterSuccess(OtaOperation.FULL,
            writeMarker = { OtaCompletionMarker.Result(false, listOf("nonfatal marker warning")) },
            openKernelSu = { opened++; KernelSuHandoff.Result(false, listOf("open retry warning")) })
        assertEquals(1, opened)
        assertEquals(false, result.markerReady)
        assertEquals(listOf("nonfatal marker warning", "open retry warning"), result.logs)
        assertTrue(OtaCompletionPolicy.stage(OtaOperation.FULL, result.markerReady).contains("联动标记未就绪"))
    }

    @Test fun auxiliaryExceptionsNeverTurnAFinishedTransactionIntoFailure() {
        var opened = false
        val result = OtaCompletionFlow.afterSuccess(OtaOperation.FULL,
            writeMarker = { throw IllegalStateException("marker denied") },
            openKernelSu = { opened = true; throw IllegalStateException("manager unavailable") })
        assertTrue(opened)
        assertEquals(false, result.markerReady)
        assertTrue(result.logs.any { it.contains("marker denied") && it.contains("成功结果保留") })
        assertTrue(result.logs.any { it.contains("manager unavailable") && it.contains("成功结果保留") })
    }

    @Test fun efispOnlyAndExportDoNotWriteMarkerOrAutomaticallyOpenKernelSu() {
        for (operation in listOf(OtaOperation.EFISP_ONLY, OtaOperation.EXPORT_EFISP)) {
            val result = OtaCompletionFlow.afterSuccess(operation,
                writeMarker = { error("partial action must not grant OTA completion") },
                openKernelSu = { error("only FULL automatically opens KernelSU") })
            assertNull(result.markerReady)
            assertTrue(result.logs.isEmpty())
        }
    }
}
