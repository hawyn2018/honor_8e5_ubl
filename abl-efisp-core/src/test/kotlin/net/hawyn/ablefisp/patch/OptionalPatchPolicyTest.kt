package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OptionalPatchPolicyTest {
    private fun input(): ByteArray {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "analysis/ablnew-compat/ablnew-inner.pe").isFile }
        return File(root, "analysis/ablnew-compat/ablnew-inner.pe").readBytes()
    }

    @Test fun optionalBackendMismatchLeavesTheRequiredUnlockAndFakeLockPlansAvailable() {
        val original = input()
        val pe = PeImage.parse(original)
        // Same independent real-fixture corruption as the backend-specific
        // regression: make that optional backend's identity unprovable.
        val changed = original.copyOf()
        changed[requireNotNull(pe.rvaToOffset(0x1b4e44, 16))] = 0
        val snapshot = changed.copyOf()
        assertThrows(PatchException::class.java) { FlashingUnlockPatcher.analyze(PeImage.parse(changed)) }
        val result = AblEfisp.patch(changed, PatchMode.FREEDOM_FAKE_LOCK)
        assertTrue(result.patches.any { it.name == "正常启动状态报告 green" })
        assertTrue(result.patches.any { it.name == "Keymaster 输入 unlocked 字节" })
        assertTrue(result.patches.any { it.name == "共用 RSA-PSS 验证入口固定成功" })
        assertFalse(result.patches.any { it.name.startsWith("flashing unlock：") || it.name.startsWith("oem superunlock：") })
        assertTrue(result.notes.any { it.contains("已跳过可选功能") && it.contains("flashing unlock") })
        assertArrayEquals(snapshot, changed)
    }

    @Test fun requiredKblDiscoveryIsNotSwallowedByTheOptionalFeaturePolicy() {
        val changed = input()
        val matches = changed.occurrences("download:\u0000".toByteArray(Charsets.US_ASCII))
        assertTrue(matches.isNotEmpty())
        matches.forEach { changed[it] = 'X'.code.toByte() }
        // Core changes can still be generated, but missing KBL's command
        // producer/consumer contract must not produce a supposedly KBL image.
        assertTrue(AblEfisp.patch(changed, PatchMode.FREEDOM_FAKE_LOCK).patches.isNotEmpty())
        val snapshot = changed.copyOf()
        assertThrows(PatchException::class.java) { AblEfisp.patchWithKbl(changed, PatchMode.FREEDOM_FAKE_LOCK) }
        assertArrayEquals(snapshot, changed)
    }

    @Test fun coreInputFailureAndUnexpectedImplementationErrorsRemainFailures() {
        assertThrows(PatchException::class.java) { AblEfisp.patchWithKbl(byteArrayOf(1, 2, 3), PatchMode.FREEDOM_FAKE_LOCK) }
        val notes = arrayListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            OptionalPatchPolicy.analyze("unrelated bug", Unit, notes) { error("unexpected implementation failure") }
        }
        assertTrue(notes.isEmpty())
    }
}
