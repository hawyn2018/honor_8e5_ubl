package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OtaCompletionMarkerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun confirmedWriteReportsTheCompletionMarker() {
        var calls = 0
        val result = OtaCompletionMarker.write(File(temporary.newFolder(), "marker.log")) { command, _ ->
            calls++
            assertEquals(OtaCompletionMarker.WRITE_COMMAND, command)
            ShellResult(0, "HNUBL_FREEOTAOK=1\n")
        }
        assertTrue(result.marked)
        assertEquals(1, calls)
        assertTrue(result.logs.any { it.contains("freeotaok=1") })
    }

    @Test fun zeroExitWithoutWriteProofIsOnlyANonFatalWarning() {
        val result = OtaCompletionMarker.write(File(temporary.newFolder(), "marker.log")) { _, _ -> ShellResult(0, "") }
        assertFalse(result.marked)
        assertTrue(result.logs.any { it.contains("OTA 成功结果保留") })
    }

    @Test fun deniedWriteIsNotRetriedAndRetainsTheSuccessfulOtaResult() {
        var calls = 0
        val result = OtaCompletionMarker.write(File(temporary.newFolder(), "marker.log")) { _, _ ->
            calls++; ShellResult(1, "permission denied")
        }
        assertFalse(result.marked)
        assertEquals(1, calls)
        assertTrue(result.logs.any { it.contains("permission denied") })
        assertTrue(result.logs.any { it.contains("OTA 成功结果保留") })
    }

    @Test fun outputFileErrorsAndExecutorExceptionsRemainNonFatal() {
        val parentFile = temporary.newFile()
        var calls = 0
        val fileError = OtaCompletionMarker.write(File(parentFile, "marker.log")) { _, _ ->
            calls++; error("Must not execute with an unusable log path")
        }
        assertFalse(fileError.marked)
        assertEquals(0, calls)
        val processError = OtaCompletionMarker.write(File(temporary.newFolder(), "marker.log")) { _, _ ->
            error("root channel disconnected")
        }
        assertFalse(processError.marked)
        assertTrue(processError.logs.any { it.contains("root channel disconnected") && it.contains("OTA 成功结果保留") })
    }
}
