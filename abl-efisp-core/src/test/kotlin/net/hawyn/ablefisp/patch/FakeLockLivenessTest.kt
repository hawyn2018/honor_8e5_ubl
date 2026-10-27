package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FakeLockLivenessTest {
    private fun source(): ByteArray {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe").isFile }
        assumeTrue("Optional local ABL integration sample required", root != null)
        return File(root!!, "analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe").readBytes()
    }

    private fun rejectsOverwrite(rva: Long, instruction: Int) {
        val original = source()
        val candidate = original.copyOf()
        candidate.putWord(requireNotNull(PeImage.parse(candidate).rvaToOffset(rva, 4)), instruction)
        val unchanged = candidate.copyOf()
        val error = assertThrows(PatchException::class.java) {
            PatchEngine.analyzeAndPatch(candidate, PatchMode.FREEDOM_FAKE_LOCK)
        }
        assertEquals(PatchFailureKind.INCOMPLETE_CORE_FEATURE, error.kind)
        assertArrayEquals(unchanged, candidate)
        assertFalse(original.contentEquals(candidate))
    }

    @Test fun rejectsOverwrittenFlagsBeforePolicySelection() =
        rejectsOverwrite(0x9b0a8, 0x7100093f) // CMP W9,#2 replaces a flag-preserving store.

    @Test fun rejectsOverwrittenInitValueBeforePolicySelection() =
        rejectsOverwrite(0x9b09c, 0x52800008) // MOV W8,#0 replaces a stack initialization.

    @Test fun rejectsOverwrittenRuntimeStateBeforeComparison() =
        rejectsOverwrite(0x9b09c, 0x52800009) // MOV W9,#0 invalidates the global-state read.

    @Test fun rejectsOverwrittenLockedPointerBeforePropertySelection() =
        rejectsOverwrite(0x87984, 0xaa0903ea.toInt()) // MOV X10,X9 replaces the comparison.

    @Test fun rejectsOverwrittenPropertyNameBeforeAppend() =
        rejectsOverwrite(0x87980, 0xaa1f03e1.toInt()) // MOV X1,XZR replaces MOV X0,X19.

    @Test fun rejectsUnknownControlFlowDuringPolicySelection() =
        rejectsOverwrite(0x9b0a8, 0x14000001) // Even an adjacent branch needs a separate CFG proof.

    @Test fun javaConstructorWithoutModeRemainsAvailable() {
        val constructor = PatchResult::class.java.getConstructor(
            ByteArray::class.java, String::class.java, String::class.java, String::class.java,
            Int::class.javaPrimitiveType, List::class.java, List::class.java, Boolean::class.javaPrimitiveType,
        )
        val result = constructor.newInstance(byteArrayOf(), "PE", "source", "output", 0, emptyList<PatchMatch>(), emptyList<String>(), true)
        assertEquals(PatchMode.FREEDOM, result.mode)
    }
}
