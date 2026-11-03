package dev.honor.bootforge

import dev.honor.bootforge.core.OtaCompletionMarker
import dev.honor.bootforge.core.SystemUpdaterHandoff
import org.junit.Assert.*
import org.junit.Test

class OtaCompletionPolicyTest {
    @Test fun fullAndEfispOnlyBothOfferReturnToUpdater() {
        for (operation in listOf(OtaOperation.FULL, OtaOperation.EFISP_ONLY)) {
            assertEquals(OtaCompletionAction.RETURN_TO_UPDATER, OtaCompletionPolicy.action(operation))
            assertTrue(OtaCompletionPolicy.available(operation, OtaCompletionAction.RETURN_TO_UPDATER, true, false))
            assertFalse(OtaCompletionPolicy.stage(operation).contains("Fastboot"))
        }
    }

    @Test fun exportHasNoCompletionButtonAndBusyOrIncompleteNeverDispatches() {
        OtaOperation.entries.forEach { operation ->
            assertFalse(OtaCompletionPolicy.available(operation, OtaCompletionAction.RETURN_TO_UPDATER, false, false))
            assertFalse(OtaCompletionPolicy.available(operation, OtaCompletionAction.RETURN_TO_UPDATER, true, true))
        }
        assertEquals(OtaCompletionAction.NONE, OtaCompletionPolicy.action(OtaOperation.EXPORT_EFISP))
        assertFalse(OtaCompletionPolicy.available(OtaOperation.EXPORT_EFISP, OtaCompletionAction.RETURN_TO_UPDATER, true, false))
    }

    @Test fun successfulFullTransactionMarksBeforeOpeningTheUpdater() {
        val order = mutableListOf<String>()
        val result = OtaCompletionFlow.afterSuccess(OtaOperation.FULL,
            writeMarker = { order += "marker=1"; OtaCompletionMarker.Result(true, listOf("marker proof")) },
            openUpdater = { order += "open updater"; SystemUpdaterHandoff.Result(true, listOf("opened")) })
        assertEquals(listOf("marker=1", "open updater"), order)
        assertEquals(true, result.markerReady)
        assertEquals(listOf("marker proof", "opened"), result.logs)
    }

    @Test fun markerFailureStillOpensUpdaterAndPreservesTheSuccessfulOperation() {
        var opened = 0
        val result = OtaCompletionFlow.afterSuccess(OtaOperation.FULL,
            writeMarker = { OtaCompletionMarker.Result(false, listOf("nonfatal marker warning")) },
            openUpdater = { opened++; SystemUpdaterHandoff.Result(false, listOf("open retry warning")) })
        assertEquals(1, opened)
        assertEquals(false, result.markerReady)
        assertEquals(listOf("nonfatal marker warning", "open retry warning"), result.logs)
        assertTrue(OtaCompletionPolicy.stage(OtaOperation.FULL, result.markerReady).contains("联动标记未就绪"))
    }

    @Test fun auxiliaryExceptionsNeverTurnAFinishedTransactionIntoFailure() {
        var opened = false
        val result = OtaCompletionFlow.afterSuccess(OtaOperation.FULL,
            writeMarker = { throw IllegalStateException("marker denied") },
            openUpdater = { opened = true; throw IllegalStateException("updater unavailable") })
        assertTrue(opened)
        assertEquals(false, result.markerReady)
        assertTrue(result.logs.any { it.contains("marker denied") && it.contains("成功结果保留") })
        assertTrue(result.logs.any { it.contains("updater unavailable") && it.contains("成功结果保留") })
    }

    @Test fun efispOnlyAndExportDoNotWriteMarkerOrAutomaticallyOpenUpdater() {
        for (operation in listOf(OtaOperation.EFISP_ONLY, OtaOperation.EXPORT_EFISP)) {
            val result = OtaCompletionFlow.afterSuccess(operation,
                writeMarker = { error("partial action must not grant OTA completion") },
                openUpdater = { error("only FULL automatically returns") })
            assertNull(result.markerReady)
            assertTrue(result.logs.isEmpty())
        }
    }
}
