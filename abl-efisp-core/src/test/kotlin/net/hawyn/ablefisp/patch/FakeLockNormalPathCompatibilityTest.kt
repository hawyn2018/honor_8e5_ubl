package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class FakeLockNormalPathCompatibilityTest {
    private fun workspace(): File = requireNotNull(generateSequence(
        File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.take(9)
        .firstOrNull { File(it, "analysis/ablpatcher-fixtures/manifest.json").isFile })

    private fun sample(path: String): PeImage = PeImage.parse(File(workspace(), path).readBytes())
    private fun changed(image: PeImage, pc: Long, word: Int): PeImage = PeImage.parse(
        image.bytes.copyOf().also { it.putWord(requireNotNull(image.rvaToOffset(pc, 4)), word) })

    private fun apply(image: PeImage, edits: List<PlannedEdit>): PeImage = PeImage.parse(
        image.bytes.copyOf().also { data -> edits.forEach { e ->
            e.replacement.copyInto(data, requireNotNull(image.rvaToOffset(e.rva, e.replacement.size)))
        } })

    private fun fixture(words: Map<Long, Int>): PeImage {
        val data = ByteArray(0xc00)
        fun half(at: Int, value: Int) { data[at] = value.toByte(); data[at + 1] = (value ushr 8).toByte() }
        data[0] = 77; data[1] = 90; data.putWord(0x3c, 0x80); data.putWord(0x80, 0x4550)
        half(0x84, 0xaa64); half(0x86, 1); half(0x94, 0xf0); half(0x98, 0x20b)
        data.putWord(0xa8, 0x1000); data.putWord(0xb8, 0x1000); data.putWord(0xbc, 0x200)
        data.putWord(0xd0, 0x2000); data.putWord(0xd4, 0x400)
        ".text".toByteArray().copyInto(data, 0x188)
        data.putWord(0x190, 0x800); data.putWord(0x194, 0x1000)
        data.putWord(0x198, 0x800); data.putWord(0x19c, 0x400); data.putWord(0x1ac, 0x60000020)
        for (raw in 0x400 until data.size step 4) data.putWord(raw, NOP)
        words.forEach { (pc, word) -> data.putWord((pc - 0x1000 + 0x400).toInt(), word) }
        return PeImage.parse(data)
    }

    private fun store(register: Int, offset: Int = 0x440, width: Int = 4): Int =
        (if (width == 4) 0xb9000000.toInt() else 0x39000000) or
            ((offset / width) shl 10) or (19 shl 5) or register

    private fun load(register: Int) = 0xb9400000.toInt() or ((0x440 / 4) shl 10) or (19 shl 5) or register
    private fun cbz(pc: Long, target: Long) = 0x34000000 or ((((target - pc) / 4).toInt() and 0x7ffff) shl 5)

    @Test fun conditionalCompareMultiplyAndVectorImmediateHaveTheirActualWriteSets() {
        for (word in listOf(CCMP, CCMN)) {
            assertFalse(StraightLineLiveness.mayWriteRegister(word, 19))
            val image = fixture(mapOf(0x1000L to word, 0x1004L to RET))
            assertTrue(StraightLineLiveness.preserves(image, 0x1000, 0x1004, setOf(19)))
            assertFalse("Conditional compares overwrite NZCV", StraightLineLiveness.preserves(image, 0x1000, 0x1004, flags = true))
        }
        for (word in listOf(MADD, MSUB)) {
            assertTrue(StraightLineLiveness.mayWriteRegister(word, 8))
            assertFalse(StraightLineLiveness.mayWriteRegister(word, 19))
        }
        for (register in 0..30) assertFalse(StraightLineLiveness.mayWriteRegister(MOVI, register))
        assertTrue("An unmodelled instruction remains conservative", StraightLineLiveness.mayWriteRegister(0, 19))
    }

    @Test fun supportedNonBaseInstructionsDoNotCreatePhantomContextDefinitions() {
        val image = fixture(mapOf(0x1000L to store(8), 0x1004L to CCMP, 0x1008L to MADD,
            0x100cL to MOVI, 0x1010L to load(9), 0x1014L to RET))
        val flow = CompletionFlow(image, 0x1000, 0x1018)
        assertTrue(flow.hasOnlyDefinition(0x1010, 0x1000, 19, 0x440, 4))
        assertTrue(flow.hasDefinitionPath(0x1010, 0x1000, 19, 0x440, 4))
    }

    @Test fun normalDefinitionRouteIsDistinctFromWholeFunctionUniqueness() {
        val image = fixture(mapOf(0x1000L to cbz(0x1000, 0x1010), 0x1004L to store(8),
            0x1008L to A64.branch(0x1008, 0x1020), 0x1010L to store(9),
            0x1014L to A64.branch(0x1014, 0x1020), 0x1020L to load(10), 0x1024L to RET))
        val flow = CompletionFlow(image, 0x1000, 0x1028)
        assertFalse(flow.hasOnlyDefinition(0x1020, 0x1004, 19, 0x440, 4))
        assertTrue(flow.hasDefinitionPath(0x1020, 0x1004, 19, 0x440, 4))
        assertTrue(flow.hasDefinitionPath(0x1020, 0x1010, 19, 0x440, 4))
    }

    @Test fun overlappingWritesBaseRedefinitionsAndUnknownEffectsStopTheRoute() {
        for (middle in listOf(store(9), store(9, 0x441, 1), 0xaa0003f3.toInt(), 0)) {
            val image = fixture(mapOf(0x1000L to store(8), 0x1004L to middle,
                0x1008L to load(9), 0x100cL to RET))
            assertFalse("word=${middle.toUInt().toString(16)}", CompletionFlow(image, 0x1000, 0x1010)
                .hasDefinitionPath(0x1008, 0x1000, 19, 0x440, 4))
        }
        val adjacent = fixture(mapOf(0x1000L to store(8), 0x1004L to store(9, 0x444),
            0x1008L to load(9), 0x100cL to RET))
        assertTrue(CompletionFlow(adjacent, 0x1000, 0x1010).hasDefinitionPath(0x1008, 0x1000, 19, 0x440, 4))
    }

    @Test fun anUnreachableDefinitionOrWritesOnEveryRouteCannotSupplyTheNormalValue() {
        val dead = fixture(mapOf(0x1000L to A64.branch(0x1000, 0x1020), 0x1004L to store(8),
            0x1020L to load(9), 0x1024L to RET))
        assertFalse(CompletionFlow(dead, 0x1000, 0x1028).hasDefinitionPath(0x1020, 0x1004, 19, 0x440, 4))
        val overwritten = fixture(mapOf(0x1000L to store(8), 0x1004L to cbz(0x1004, 0x1014),
            0x1008L to store(9), 0x100cL to A64.branch(0x100c, 0x1020),
            0x1014L to store(10), 0x1018L to A64.branch(0x1018, 0x1020),
            0x1020L to load(11), 0x1024L to RET))
        assertFalse(CompletionFlow(overwritten, 0x1000, 0x1028).hasDefinitionPath(0x1020, 0x1000, 19, 0x440, 4))
        val notAStore = fixture(mapOf(0x1000L to 0, 0x1004L to load(9), 0x1008L to RET))
        assertFalse(CompletionFlow(notAStore, 0x1000, 0x100c).hasDefinitionPath(0x1004, 0x1000, 19, 0x440, 4))
    }

    @Test fun bkq108DiscoversTheActualNormalStateByteAndPropertyAndReimports() {
        val image = sample(BKQ108)
        val original = image.bytes.copyOf()
        assertEquals("84a9c04ac039c09f8c8c6f017f907dbac1e78e5618ecbe66f8acee7ec849986c",
            MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it.toInt() and 255) })
        val semantic = SemanticPatcher.analyze(image)
        val plan = FakeLockPatcher.analyze(image, requireNotNull(semantic.stateGlobal))
        assertEquals(3, plan.edits.size)
        assertEquals(0x9b47cL, plan.edits.single { it.name == "正常启动状态报告 green" }.rva)
        assertEquals(0x9bafcL, plan.edits.single { it.name == "Keymaster 输入 unlocked 字节" }.rva)
        val facts = requireNotNull(plan.normalBootState)
        assertEquals(0x999f4L, facts.policyDefinitionPc)
        assertEquals(19, facts.contextBaseRegister)
        assertEquals(0x440L, facts.contextFieldOffset)
        val output = apply(image, plan.edits)
        val allowed = plan.edits.flatMap { e ->
            val at = requireNotNull(image.rvaToOffset(e.rva, e.replacement.size))
            (at until at + e.replacement.size).toList()
        }.toSet()
        assertTrue(original.indices.all { original[it] == output.bytes[it] || it in allowed })
        val again = FakeLockPatcher.analyze(output, requireNotNull(semantic.stateGlobal))
        assertArrayEquals(output.bytes, apply(output, again.edits).bytes)
        assertArrayEquals(original, image.bytes)
    }

    @Test fun aDifferentByteSourceKeepsTheProvedConsumerField() {
        val image = sample(BKQ108)
        val global = requireNotNull(SemanticPatcher.analyze(image).stateGlobal)
        val byte = requireNotNull(image.word(0x9bafc))
        val candidate = changed(image, 0x9bafc, (byte and -32) or 9)
        val plan = FakeLockPatcher.analyze(candidate, global)
        assertEquals(3, plan.edits.size)
        val outgoing = plan.edits.single { it.name == "Keymaster 输入 unlocked 字节" }
        assertEquals(0x9bafcL, outgoing.rva)
        assertEquals((byte and -32) or 31, outgoing.replacement.word(0))
        assertEquals(0x9b49cL, requireNotNull(plan.normalBootState).normalStateStore)
    }

    @Test fun aWrongFieldConsumerLayoutOrMultipleReachingByteDefinitionsStillRejectsBkq108() {
        val image = sample(BKQ108)
        val global = requireNotNull(SemanticPatcher.analyze(image).stateGlobal)
        val byte = requireNotNull(image.word(0x9bafc))
        val consumerByte = requireNotNull(image.word(0x88008))
        // 0x9bb08 already branches either through 0x9bb0c or directly to
        // 0x9bb18. An extra store on only the former route makes the input's
        // byte field have two reaching definitions at the same consumer.
        val extraByte = 0x39000000 or (0x50 shl 10) or (31 shl 5) or 9
        for (candidate in listOf(changed(image, 0x9bafc, byte + (1 shl 10)),
            changed(image, 0x88008, consumerByte + (1 shl 10)),
            changed(image, 0x9bb0c, extraByte))) {
            val snapshot = candidate.bytes.copyOf()
            assertThrows(PatchException::class.java) { FakeLockPatcher.analyze(candidate, global) }
            assertArrayEquals(snapshot, candidate.bytes)
        }
    }

    @Test fun previousIndependentFamiliesKeepUniqueThreeFieldPlans() {
        for (path in listOf("analysis/builtin-vs-bkq-stock-20261030/builtin-inner.pe",
            "analysis/bkq150-newabl-bootloop-20261102/fallback-audit/source-inner.pe",
            "analysis/winrt2-blackboot-20261029/firmware/modules/abl.elf-0x1064-0xb8.pe",
            "analysis/canoe-unlock-integration-20261029/audit/runtime/aph-source.inner.pe")) {
            val image = sample(path); val saved = image.bytes.copyOf()
            val global = requireNotNull(SemanticPatcher.analyze(image).stateGlobal)
            val plan = FakeLockPatcher.analyze(image, global)
            assertEquals(path, 3, plan.edits.size)
            if (path.endsWith("builtin-inner.pe")) assertEquals(
                listOf(0x9cc70L, 0x9ce34L, 0x87988L), plan.edits.map { it.rva })
            val output = apply(image, plan.edits)
            val again = FakeLockPatcher.analyze(output, global)
            assertArrayEquals(path, output.bytes, apply(output, again.edits).bytes)
            assertArrayEquals(saved, image.bytes)
        }
    }

    private companion object {
        const val BKQ108 = "analysis/bkqan90-abl-patch-failure-20261102/fakebl138/input-inner.pe"
        const val NOP = 0xd503201f.toInt()
        const val RET = 0xd65f03c0.toInt()
        const val CCMP = 0x7a410920
        const val CCMN = 0x3a410920
        const val MADD = 0x9b0826c8.toInt()
        const val MSUB = 0x9b08a6c8.toInt()
        const val MOVI = 0x6f00e400
    }
}
