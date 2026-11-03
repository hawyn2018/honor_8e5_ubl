package dev.honor.bootforge

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import dev.honor.bootforge.core.ImagePatchRetry
import dev.honor.bootforge.core.PatchRetryTask
import dev.honor.bootforge.core.PatchRetryDeclinedException
import java.util.concurrent.CancellationException
import dev.honor.bootforge.core.AblRollbackInspector
import dev.honor.bootforge.core.EfispArbFallback
import dev.honor.bootforge.core.EfispImagePatcher
import dev.honor.bootforge.core.Payloads
import java.io.File
import java.nio.file.Files

class OtaPatchFailureGuidanceTest {
    @Test fun standaloneBuiltinPatchFailureStillProducesTheInlineHint() {
        listOf(OtaOperation.EFISP_ONLY, OtaOperation.EXPORT_EFISP).forEach { operation ->
            val original = IOException("builtin patch failed")
            val failure = assertThrows(OtaImagePatchFailure::class.java) {
                ImagePatchRetry.run(PatchRetryTask.OTA, false,
                    primary = { OtaPatchFailureGuidance.patch(operation) { throw original } },
                    builtin = { error("builtin must not loop") }, confirm = { error("no standalone dialog") })
            }
            assertSame(original, failure.cause)
            assertTrue(OtaPatchFailureGuidance.message(operation, failure).isNotBlank())
        }
    }

    @Test fun finalPolicyBuiltinFailureUsesTheSameActualPatchAdapter() {
        listOf(OtaOperation.EFISP_ONLY, OtaOperation.EXPORT_EFISP).forEach { operation ->
            val directory = Files.createTempDirectory("ota-standalone-patch").toFile()
            try {
                val selected = File(directory, "selected.img").apply { writeText("selected") }
                val current = File(directory, "current.img").apply { writeText("current") }
                val builtin = File(directory, "builtin.img").apply { writeText("builtin") }
                val failure = IOException("final builtin patch failed")
                val patched = mutableListOf<File>()
                val marked = assertThrows(OtaImagePatchFailure::class.java) {
                    EfispArbFallback.prepareWith(selected, { current }, { builtin }, directory, true, {},
                        { AblRollbackInspector.Result.Parsed(AblRollbackInspector.Format.SIGNED_ELF,
                            listOf(AblRollbackInspector.Counter(AblRollbackInspector.Authority.OEM, 1, "fixture", 0)), emptyList()) },
                        { source, output, mode, _ -> OtaPatchFailureGuidance.patch(operation) {
                            patched += source
                            if (source == builtin) throw failure
                            output.writeText("product")
                            EfispImagePatcher.Result(output.length(), Payloads.sha256(output), Payloads.sha256(source),
                                "fixture", mode, 1, 0, emptyList())
                        } }, onPatchFailure = { cause -> OtaPatchFailureGuidance.decide(operation, cause) { error("no standalone dialog") } })
                }
                assertSame(failure, marked.cause)
                assertEquals(listOf(current, builtin), patched)
                assertTrue(OtaPatchFailureGuidance.message(operation, marked).isNotBlank())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun patchAdapterPreservesSuccessFullFailuresAndCancellation() {
        OtaOperation.entries.forEach { assertEquals("product", OtaPatchFailureGuidance.patch(it) { "product" }) }
        val original = IOException("full patch failed")
        assertSame(original, assertThrows(IOException::class.java) {
            OtaPatchFailureGuidance.patch(OtaOperation.FULL) { throw original }
        })
        OtaOperation.entries.forEach { operation ->
            val cancelled = CancellationException("cancelled")
            assertSame(cancelled, assertThrows(CancellationException::class.java) {
                OtaPatchFailureGuidance.patch(operation) { throw cancelled }
            })
            val interrupted = InterruptedException("interrupted")
            assertSame(interrupted, assertThrows(InterruptedException::class.java) {
                OtaPatchFailureGuidance.patch(operation) { throw interrupted }
            })
        }
    }

    @Test fun standaloneActionsNeverOpenDialogOrPatchBuiltinAutomatically() {
        listOf(OtaOperation.EFISP_ONLY, OtaOperation.EXPORT_EFISP).forEach { operation ->
            val original = IOException("patch failed")
            val failure = assertThrows(OtaImagePatchFailure::class.java) {
                ImagePatchRetry.run(PatchRetryTask.OTA, true,
                    primary = { throw original }, builtin = { error("no automatic builtin retry") },
                    confirm = { cause -> OtaPatchFailureGuidance.decide(operation, cause) { error("no dialog") } })
            }
            assertSame(original, failure.cause)
            assertTrue(OtaPatchFailureGuidance.message(operation, failure).isNotBlank())
        }
    }

    @Test fun fullOtaUsesExplicitChoiceAndCanContinueWithBuiltin() {
        val original = IOException("patch failed"); var prompts = 0; var builtinCalls = 0
        val result = ImagePatchRetry.run(PatchRetryTask.OTA, true,
            primary = { throw original }, builtin = { builtinCalls++; "builtin product" },
            confirm = { error -> OtaPatchFailureGuidance.decide(OtaOperation.FULL, error) {
                prompts++; assertSame(original, it); true
            } })
        assertEquals(1, prompts); assertEquals(1, builtinCalls)
        assertTrue(result.usedBuiltin)
        assertEquals("builtin product", result.value)
    }

    @Test fun fullOtaRefusalStopsWithTheExactLogMessage() {
        val failure = assertThrows(PatchRetryDeclinedException::class.java) {
            ImagePatchRetry.run(PatchRetryTask.OTA, true,
                primary = { throw IOException("patch failed") }, builtin = { error("no retry after refusal") },
                confirm = { error -> OtaPatchFailureGuidance.decide(OtaOperation.FULL, error) { false } })
        }
        assertEquals("选定镜像修补失败，用户选择停止OTA流程", failure.message)
    }

    @Test fun writeAndExportFailuresUseTheRequestedAction() {
        val failure = OtaImagePatchFailure(IllegalStateException("unrecognized patch target"))
        assertEquals("刷写efisp失败，可选择内置ABL修补选项后重试刷写流程",
            OtaPatchFailureGuidance.message(OtaOperation.EFISP_ONLY, failure))
        assertEquals("导出efisp失败，可选择内置ABL修补选项后重试导出流程",
            OtaPatchFailureGuidance.message(OtaOperation.EXPORT_EFISP, failure))
    }

    @Test fun fullOtaDoesNotShowTheStandaloneFailureHint() {
        assertEquals("", OtaPatchFailureGuidance.message(OtaOperation.FULL,
            OtaImagePatchFailure(IllegalStateException("patch failed"))))
    }

    @Test fun realReadWriteAndDocumentErrorsAreNotReportedAsPatchFailures() {
        OtaOperation.entries.forEach { operation ->
            assertEquals("", OtaPatchFailureGuidance.message(operation, IOException("document provider failed")))
            assertEquals("", OtaPatchFailureGuidance.message(operation, IllegalStateException("readback mismatch")))
        }
    }

    @Test fun wrapperPreservesTheActualPatchReasonAndOriginalCause() {
        val original = IllegalArgumentException("missing required feature")
        val failure = OtaImagePatchFailure(original)
        assertSame(original, failure.cause)
        assertEquals(original.message, failure.message)
    }
}
