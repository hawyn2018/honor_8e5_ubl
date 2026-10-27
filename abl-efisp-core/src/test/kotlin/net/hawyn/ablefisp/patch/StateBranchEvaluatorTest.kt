package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class StateBranchEvaluatorTest {
    private fun evaluate(vararg words: Int): List<StateBranchEvaluator.Result> {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe").isFile }
        assumeTrue("Local original instruction oracle", root != null)
        val bytes = File(root!!, "analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe").readBytes()
        val pe = PeImage.parse(bytes)
        words.forEachIndexed { index, word -> bytes.putWord(requireNotNull(pe.rvaToOffset(0x9cc70 + index * 4L, 4)), word) }
        return StateBranchEvaluator.evaluate(PeImage.parse(bytes), 0x9b060, 0x9d6e8, 0x9cc70, 21, true)
    }

    @Test fun anUnmodeledFeasiblePathDoesNotDisappear() {
        assertTrue(evaluate(0x34000089, 0x52800028, 0xb9044268.toInt(), 0xd503201f.toInt(),
            0x4a080108, 0x52800068, 0xb9044268.toInt()).isEmpty())
    }

    @Test fun bitZeroOfTwoIsZero() {
        val result = evaluate(0x52800048, 0x36000068, 0x52800028, 0x14000002, 0x52800008, 0xb9044268.toInt())
        assertEquals(setOf(0), result.single().values)
    }

    @Test fun unsupportedShiftDoesNotGetInterpretedAsLeftShift() {
        assertTrue(evaluate(0x52800048, 0x52800029, 0x53010129, 0x0b497d08, 0xb9044268.toInt()).isEmpty())
    }

    @Test fun contextCopiesKeepTheOriginalObjectIdentity() {
        assertEquals(19, evaluate(0xaa1303f4.toInt(), 0x52800028, 0xb9044288.toInt()).single().base)
    }

    @Test fun independentBooleansWithTheSameRangeAreNotTheSameValue() {
        val result = evaluate(0x39400308, 0x7100011f, 0x1a9f07e8,
            0x39400329, 0x7100013f, 0x1a9f07e9, 0x0b090108, 0xb9044268.toInt())
        assertEquals(setOf(0, 1, 2), result.single().values)
    }
}
