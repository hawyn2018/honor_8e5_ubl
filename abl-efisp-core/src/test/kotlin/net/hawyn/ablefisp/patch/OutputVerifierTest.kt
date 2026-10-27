package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class OutputVerifierTest {
    private fun sample(): ByteArray {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "analysis/ablpatcher-fixtures/files/stock-inner.fixture.pe").isFile }
        assumeTrue("Firmware fixture required for delivery verifier integration", root != null)
        return File(root!!, "analysis/ablpatcher-fixtures/files/stock-inner.fixture.pe").readBytes()
    }

    @Test fun corePatchPassesStrictAllowlist() {
        val source = sample(); val result = PatchEngine.analyzeAndPatch(source)
        val verification = OutputVerifier.verifyCore(source, result.output, result.patches)
        assertTrue(verification.summary, verification.allPassed)
        assertTrue(OutputVerifier.verifyStructure(result.output, result.output.copyOf()).allPassed)
    }

    @Test fun threeCoreOnlyOutputPassesDynamicAllowlist() {
        val source = sample()
        val name = source.occurrences("efisp\u0000".toByteArray(Charsets.UTF_16LE)).single()
        source[name] = 'k'.code.toByte()
        val result = PatchEngine.analyzeAndPatch(source)
        assertEquals(6, result.patches.size)
        val verification = OutputVerifier.verifyCore(source, result.output, result.patches)
        assertTrue(verification.summary, verification.allPassed)
        assertTrue(verification.checks.any { it.name == "字节差异严格限制在6个目标" && it.passed })
    }

    @Test fun extraCodeChangeFailsAllowlist() {
        val source = sample(); val result = PatchEngine.analyzeAndPatch(source)
        val altered = result.output.copyOf().also { it[0x2000] = (it[0x2000].toInt() xor 1).toByte() }
        val verification = OutputVerifier.verifyCore(source, altered, result.patches)
        assertFalse(verification.allPassed)
        assertTrue(verification.checks.any { it.name == "字节差异严格限制在7个目标" && !it.passed })
    }

    @Test fun revertedBranchFailsFinalFunctionCheck() {
        val source = sample(); val result = PatchEngine.analyzeAndPatch(source)
        val reverted = result.output.copyOf().also { source.copyInto(it, 0x11078, 0x11078, 0x1107c) }
        assertFalse(OutputVerifier.verifyCore(source, reverted, result.patches).allPassed)
    }

    @Test fun relocationCorruptionFailsStructureCheck() {
        val patched = PatchEngine.analyzeAndPatch(sample()).output
        val damaged = patched.copyOf().also { it[0x1ea000] = (it[0x1ea000].toInt() xor 1).toByte() }
        val verification = OutputVerifier.verifyStructure(patched, damaged)
        assertFalse(verification.allPassed)
        assertTrue(verification.checks.any { it.name == "原重定位目录及重定位数据保持" && !it.passed })
    }

    @Test fun badEntryProducesControlledFailure() {
        val source = sample(); val damaged = source.copyOf()
        val optional = damaged.u32(0x3c).toInt() + 24
        damaged.putWord(optional + 16, 0x1e7400)
        assertFalse(OutputVerifier.verifyStructure(source, damaged).allPassed)
    }

    @Test fun invalidTextCallTargetFailsStructureCheck() {
        val source = PatchEngine.analyzeAndPatch(sample()).output
        val damaged = source.copyOf()
        // This is the display width call, outside the three state branches.
        damaged.putWord(0x14ecc, 0x94000000.toInt() or ((0x300000 - 0x14ecc) / 4))
        val result = OutputVerifier.verifyStructure(source, damaged)
        assertFalse(result.allPassed)
        assertTrue(result.checks.any { it.name == "新增及改写分支目标映射" && !it.passed })
    }
}
