package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LateBootNormalPathCompatibilityTest {
    private fun image(): PeImage {
        val root = requireNotNull(generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(9).firstOrNull { File(it, "analysis/ablpatcher-fixtures/manifest.json").isFile })
        return PeImage.parse(File(root, "analysis/bkqan90-abl-patch-failure-20261102/fakebl138/input-inner.pe").readBytes())
    }

    private fun facts(image: PeImage) = requireNotNull(FakeLockPatcher.analyze(image,
        requireNotNull(SemanticPatcher.analyze(image).stateGlobal)).normalBootState)

    private fun changed(image: PeImage, pc: Long, word: Int): PeImage = PeImage.parse(image.bytes.copyOf().also {
        it.putWord(requireNotNull(image.rvaToOffset(pc, 4)), word)
    })

    @Test fun bkq108ProvesNormalContextAndSuccessfulCleanupWithoutInitialBooleanHistory() {
        val source = image(); val saved = source.bytes.copyOf(); val facts = facts(source)
        val flow = A64ValueOrigins(source, facts.functionStart, facts.functionEnd)
        assertFalse(flow.derivesFromOrZero(0x9b0c0, 24, facts.policyDefinitionPc, facts.policyRegister))
        val plan = LateBootStatePatcher.analyze(source, facts).single()
        assertEquals(0x9b0c4L, plan.selectorRva)
        assertEquals(0x9b0c8L, plan.storeRva)
        assertEquals(26, plan.errorRegister)
        assertEquals(8, plan.stateRegister)
        assertEquals(24, plan.allowRegister)
        assertEquals(source.word(plan.selectorRva), plan.originalSelector)
        assertNull(plan.existingStubRva)
        assertArrayEquals(saved, source.bytes)
    }

    @Test fun overwritingTheNormalContextBeforeCleanupStillRejects() {
        val source = image(); val facts = facts(source)
        // Replace the first normal formatter call with an explicit second write
        // to the same context field. No normal definition can reach cleanup.
        val overwrite = 0xb9000000.toInt() or ((0x440 / 4) shl 10) or (19 shl 5) or 9
        val candidate = changed(source, 0x9b4a0, overwrite)
        assertThrows(PatchException::class.java) { LateBootStatePatcher.analyze(candidate, facts) }
    }

    @Test fun missingSuccessfulZeroAndChangedFailureOrStateRolesStillReject() {
        val source = image(); val facts = facts(source)
        val branch = requireNotNull(source.word(0x9b0cc))
        for (candidate in listOf(
            changed(source, 0x9c3b8, 0xd280003a.toInt()), // MOV X26,#1, not a success definition
            changed(source, 0x9b0cc, branch xor 0x01000000), // reverse pending-error outcome
            changed(source, 0x9b0cc, branch and 0x7fffffff), // truncating a 64-bit error is not equivalent
            changed(source, 0x9b0c8, requireNotNull(source.word(0x9b0c8)) + (1 shl 10)), // another context field
        )) {
            val saved = candidate.bytes.copyOf()
            assertThrows(PatchException::class.java) { LateBootStatePatcher.analyze(candidate, facts) }
            assertArrayEquals(saved, candidate.bytes)
        }
    }

    @Test fun actualCoreApiIncludesLateStateAndReimportsWithoutChangingInput() {
        val source = image().bytes; val saved = source.copyOf()
        val first = AblEfisp.patch(source, PatchMode.FREEDOM_FAKE_LOCK)
        assertEquals(0x9b47cL, first.patches.single { it.name == "正常启动状态报告 green" }.rva)
        assertEquals(0x9bafcL, first.patches.single { it.name == "Keymaster 输入 unlocked 字节" }.rva)
        assertEquals(0x9b0c4L, first.patches.single { it.name == "正常完成时保留 green 状态" }.rva)
        assertTrue(first.patches.any { it.name == "保留失败回退的条件状态选择" })
        val repeated = AblEfisp.patch(first.output, PatchMode.FREEDOM_FAKE_LOCK)
        assertArrayEquals(first.output, repeated.output)
        assertTrue(repeated.alreadyPatched)
        assertArrayEquals(saved, source)
    }

    @Test fun actualKblApiIncludesLateStateAndReimportsWithoutChangingInput() {
        val source = image().bytes; val saved = source.copyOf()
        val first = AblEfisp.patchWithKbl(source, PatchMode.FREEDOM_FAKE_LOCK)
        assertEquals(0x9b0c4L, first.patches.single { it.name == "正常完成时保留 green 状态" }.rva)
        assertTrue(first.patches.any { it.name == "KBL 显式启动命令注册" })
        val repeated = AblEfisp.patchWithKbl(first.output, PatchMode.FREEDOM_FAKE_LOCK)
        assertArrayEquals(first.output, repeated.output)
        assertTrue(repeated.alreadyPatched)
        assertArrayEquals(saved, source)
    }
}
