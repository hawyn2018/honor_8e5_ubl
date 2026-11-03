package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class EfispArbFallbackRetryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file(name: String) = File(temporary.root, name).apply { writeText(name) }
    private fun positive() = AblRollbackInspector.Result.Parsed(AblRollbackInspector.Format.SIGNED_ELF,
        listOf(AblRollbackInspector.Counter(AblRollbackInspector.Authority.OEM, 1, "fixture", 0)), emptyList())
    private val absent = AblRollbackInspector.Result.NoMetadata(AblRollbackInspector.Format.EFI_PE, emptyList())
    private fun result(source: File, destination: File, mode: Boolean): EfispImagePatcher.Result {
        destination.writeText(source.readText() + ":$mode")
        return EfispImagePatcher.Result(destination.length(), Payloads.sha256(destination), Payloads.sha256(source),
            "fixture", mode, 1, 0, emptyList())
    }

    @Test fun consentRetriesBuiltinOnceKeepsModeAndReportsActualSource() {
        listOf(false, true).forEach { mode ->
            val selected = file("selected-$mode.img")
            val current = file("current-$mode.img")
            val builtin = file("builtin-$mode.img")
            val inspected = mutableListOf<File>()
            val patched = mutableListOf<File>()
            var prompts = 0
            val failure = IOException("selected current patch failed")
            val prepared = EfispArbFallback.prepareWith(selected, { current }, { builtin }, temporary.root,
                mode, {}, { inspected += it; positive() }, { source, output, actualMode, _ ->
                    patched += source
                    assertEquals(mode, actualMode)
                    if (source == current) throw failure
                    result(source, output, actualMode)
                }, onPatchFailure = { prompts++; assertSame(failure, it); true })
            assertEquals(1, prompts)
            assertEquals(listOf(current, builtin), patched)
            assertEquals(listOf(selected), inspected)
            assertEquals(EfispArbFallback.Source.BUILTIN_ABL, prepared.source)
            assertEquals("builtin-unchecked", prepared.arbBinding)
            assertEquals(Payloads.sha256(builtin), prepared.sourceSha256)
            assertEquals("${builtin.name}:$mode", prepared.file.readText())
        }
    }

    @Test fun refusalUsesExactOtaMessageAndNeverReadsBuiltin() {
        val selected = file("selected.img"); val current = file("current.img")
        var prompts = 0
        val stopped = assertThrows(PatchRetryDeclinedException::class.java) {
            EfispArbFallback.prepareWith(selected, { current }, { error("builtin must stay unused") },
                temporary.root, true, {}, { positive() }, { _, _, _, _ -> throw IOException("patch failed") },
                onPatchFailure = { prompts++; false })
        }
        assertEquals(1, prompts)
        assertEquals("选定镜像修补失败，用户选择停止OTA流程", stopped.message)
    }

    @Test fun builtinFailureIsPropagatedWithoutSecondPromptOrRetry() {
        val selected = file("selected.img"); val current = file("current.img"); val builtin = file("builtin.img")
        val failure = IOException("builtin also failed")
        var prompts = 0; var attempts = 0
        val actual = assertThrows(IOException::class.java) {
            EfispArbFallback.prepareWith(selected, { current }, { builtin }, temporary.root, false,
                {}, { positive() }, { source, _, _, _ ->
                    attempts++
                    if (source == current) throw IOException("current failed") else throw failure
                }, onPatchFailure = { prompts++; true })
        }
        assertSame(failure, actual)
        assertEquals(1, prompts)
        assertEquals(2, attempts)
    }

    @Test fun currentSourceReadFailureDoesNotBecomePatchConsent() {
        val selected = file("selected.img")
        val failure = IOException("partition read failed")
        val actual = assertThrows(IOException::class.java) {
            EfispArbFallback.prepareWith(selected, { throw failure }, { error("no builtin read") },
                temporary.root, true, {}, { positive() }, { _, _, _, _ -> error("no patch") },
                onPatchFailure = { error("read failure must not ask for patch retry") })
        }
        assertSame(failure, actual)
    }

    @Test fun absenceOfUiCallbackKeepsOriginalFailureBehavior() {
        val selected = file("selected.img"); val current = file("current.img")
        val failure = IOException("patch failed")
        val actual = assertThrows(IOException::class.java) {
            EfispArbFallback.prepareWith(selected, { current }, { error("no implicit retry") }, temporary.root,
                true, {}, { positive() }, { _, _, _, _ -> throw failure })
        }
        assertSame(failure, actual)
    }

    @Test fun successfulCurrentCandidateDoesNotAskForPermission() {
        val selected = file("selected.img"); val current = file("current.img")
        val prepared = EfispArbFallback.prepareWith(selected, { current }, { error("unused builtin") },
            temporary.root, true, {}, { if (it == selected) positive() else absent },
            { source, output, mode, _ -> result(source, output, mode) },
            onPatchFailure = { error("successful patch must not show a prompt") })
        assertEquals(EfispArbFallback.Source.CURRENT_SLOT_ABL, prepared.source)
        assertEquals("absent", prepared.arbBinding)
    }

    @Test fun normalArbBuiltinFallbackDoesNotGainAnExtraUserPrompt() {
        val selected = file("selected.img"); val current = file("current.img"); val builtin = file("builtin.img")
        val patched = mutableListOf<File>()
        val prepared = EfispArbFallback.prepareWith(selected, { current }, { builtin }, temporary.root, false,
            {}, { positive() }, { source, output, mode, _ -> patched += source; result(source, output, mode) },
            onPatchFailure = { error("no failed patch") })
        assertEquals(listOf(current, builtin), patched)
        assertEquals(EfispArbFallback.Source.BUILTIN_ABL, prepared.source)
    }
}
