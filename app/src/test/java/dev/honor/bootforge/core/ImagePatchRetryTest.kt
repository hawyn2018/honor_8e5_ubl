package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class ImagePatchRetryTest {
    @Test fun successfulSelectedImageNeverPromptsOrReadsBuiltin() {
        val selected = Any()
        val result = ImagePatchRetry.run(PatchRetryTask.UNLOCK, true,
            primary = { selected }, builtin = { error("builtin must stay unused") },
            confirm = { error("successful patch must not prompt") })
        assertSame(selected, result.value)
        assertFalse(result.usedBuiltin)
    }

    @Test fun builtinOrIneligibleFailurePropagatesTheOriginalErrorWithoutPrompting() {
        val failure = IOException("builtin failed")
        val thrown = assertThrows(IOException::class.java) {
            ImagePatchRetry.run(PatchRetryTask.OTA, false,
                primary = { throw failure }, builtin = { error("no recursive attempt") },
                confirm = { error("builtin failure must not prompt") })
        }
        assertSame(failure, thrown)
    }

    @Test fun explicitApprovalRunsBuiltinExactlyOnceForEachTask() {
        PatchRetryTask.entries.forEach { task ->
            val events = mutableListOf<String>()
            val failure = IOException("selected target missing")
            val builtin = Any()
            val result = ImagePatchRetry.run(task, true,
                primary = { events += "selected"; throw failure },
                builtin = { events += "builtin"; builtin },
                confirm = { error -> assertSame(failure, error); events += "yes"; true })
            assertEquals(listOf("selected", "yes", "builtin"), events)
            assertSame(builtin, result.value)
            assertTrue(result.usedBuiltin)
        }
    }

    @Test fun explicitDenialStopsWithExactTaskMessageAndOriginalCause() {
        val expected = mapOf(
            PatchRetryTask.UNLOCK to "选定镜像修补失败，用户选择停止解锁流程",
            PatchRetryTask.OTA to "选定镜像修补失败，用户选择停止OTA流程",
        )
        expected.forEach { (task, message) ->
            val failure = IOException("selected failed")
            val events = mutableListOf<String>()
            val stopped = assertThrows(PatchRetryDeclinedException::class.java) {
                ImagePatchRetry.run(task, true,
                    primary = { events += "selected"; throw failure },
                    builtin = { error("denial must not generate an image") },
                    confirm = { events += "no"; false })
            }
            assertEquals(message, stopped.message)
            assertEquals(task, stopped.task)
            assertSame(failure, stopped.cause)
            assertEquals(listOf("selected", "no"), events)
        }
    }

    @Test fun failedBuiltinRetryPropagatesWithoutAnotherPrompt() {
        val failure = IOException("builtin target also missing")
        var confirmations = 0
        var builtinAttempts = 0
        val thrown = assertThrows(IOException::class.java) {
            ImagePatchRetry.run(PatchRetryTask.UNLOCK, true,
                primary = { throw IOException("selected failed") },
                builtin = { builtinAttempts++; throw failure },
                confirm = { confirmations++; true })
        }
        assertSame(failure, thrown)
        assertEquals(1, builtinAttempts)
        assertEquals(1, confirmations)
    }

    @Test fun cancelledPatchDoesNotPromptOrRetry() {
        val cancelled = CancellationException("task destroyed")
        val thrown = assertThrows(CancellationException::class.java) {
            ImagePatchRetry.run(PatchRetryTask.OTA, true,
                primary = { throw cancelled }, builtin = { error("must not retry cancellation") },
                confirm = { error("must not ask about cancellation") })
        }
        assertSame(cancelled, thrown)
    }

    @Test fun interruptedPatchDoesNotPromptOrRetry() {
        val interrupted = InterruptedException("worker interrupted")
        val thrown = assertThrows(InterruptedException::class.java) {
            ImagePatchRetry.run(PatchRetryTask.UNLOCK, true,
                primary = { throw interrupted }, builtin = { error("must not retry interruption") },
                confirm = { error("must not ask about interruption") })
        }
        assertSame(interrupted, thrown)
    }

    @Test fun resourceErrorsRemainErrorsRatherThanTriggeringAnotherAllocation() {
        val exhausted = OutOfMemoryError("fixture allocation failure")
        val thrown = assertThrows(OutOfMemoryError::class.java) {
            ImagePatchRetry.run(PatchRetryTask.OTA, true,
                primary = { throw exhausted }, builtin = { error("must not retry exhausted heap") },
                confirm = { error("must not open another operation") })
        }
        assertSame(exhausted, thrown)
    }

    @Test fun cancelledConfirmationIsNotRecordedAsUserDenial() {
        val cancelled = CancellationException("service destroyed while waiting")
        val thrown = assertThrows(CancellationException::class.java) {
            ImagePatchRetry.run(PatchRetryTask.UNLOCK, true,
                primary = { throw IOException("selected failed") },
                builtin = { error("cancelled wait must not retry") }, confirm = { throw cancelled })
        }
        assertSame(cancelled, thrown)
    }

    @Test fun promptUsesTheActualTaskName() {
        assertEquals("修补选定镜像失败，是否使用应用内置镜像修补，并继续完成后续解锁流程？",
            PatchRetryTask.UNLOCK.confirmationMessage)
        assertEquals("修补选定镜像失败，是否使用应用内置镜像修补，并继续完成后续OTA流程？",
            PatchRetryTask.OTA.confirmationMessage)
    }
}
