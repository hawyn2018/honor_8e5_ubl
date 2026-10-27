package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Local firmware integration contracts; firmware is deliberately not bundled with the core. */
class FreedomRecoveryTest {
    private data class Fixture(
        val name: String,
        val input: String,
        val coreRvas: List<Long>,
        val reportCall: Long,
        val preservedRvas: List<Long>,
        val fake124Sha256: String,
        val updateGate: Long,
        val updateSkip: Long,
    )

    private val fixtures = listOf(
        Fixture(
            "old", "abl.elf", listOf(0x10f58, 0x10fb0, 0x11078, 0xe1314), 0x851f4,
            // Normal state, outgoing Keymaster byte, device_state selection,
            // check_root_stat cache and the common late-state selector.
            listOf(0x9cc70, 0x9ce34, 0x87988, 0x11178, 0x9c954),
            "a16fd1e4240219c592839363913d2aa68221d3e3edb774e46a2f0ac9676eb919",
            0x9b250, 0x9b770,
        ),
        Fixture(
            "new", "ablnew.img", listOf(0x106ac, 0x10704, 0x1076c), 0x4ea30,
            listOf(0x4999c, 0x499fc, 0x511bc, 0x1086c, 0x48cd4),
            "545a66bb6c3e500059f933113f0bab2a11ed609ac743845c3f883a971eea841c",
            0x47574, 0x47ab0,
        ),
        Fixture(
            "ota", "analysis/locked-boot-feasibility/fastboot-ota-abl.pe",
            listOf(0x106ac, 0x10704, 0x1076c), 0x4ea30,
            listOf(0x4999c, 0x499fc, 0x511bc, 0x1086c, 0x48cd4),
            "259be637ccd95cc64df29b49c18abde288deaef4ffa9a2f7ccf598f6d4fdeddb",
            0x47574, 0x47ab0,
        ),
    )

    private fun root(): File {
        val found = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "abl.elf").isFile }
        assumeTrue("Local firmware integration input", found != null)
        return requireNotNull(found)
    }

    private fun read(root: File, path: String): ByteArray {
        val file = File(root, path)
        assumeTrue("Local fixture $path", file.isFile)
        return file.readBytes()
    }

    @Test fun freedomAddsOnlySelectiveRecoveryToTheExistingUnlockedOutput() {
        val root = root()
        for (fixture in fixtures) {
            val input = read(root, fixture.input)
            val unchanged = input.copyOf()
            val source = PeImage.parse(AblEfisp.extract(input).output)
            val legacy = PeImage.parse(read(root, "analysis/fakebl-compat-universal/outputs/${fixture.name}.freedom.pe"))
            val result = AblEfisp.patch(input, PatchMode.FREEDOM)
            val output = PeImage.parse(result.output)

            assertEquals(fixture.name, PatchMode.FREEDOM, result.mode)
            assertFalse(fixture.name, result.alreadyPatched)
            assertArrayEquals("Input ownership: ${fixture.name}", unchanged, input)
            assertEquals(fixture.coreRvas, result.patches.take(fixture.coreRvas.size).map { it.rva })
            assertEquals(fixture.name, fixture.coreRvas.size + 3, result.patches.size)
            assertEquals(fixture.reportCall, result.patches[fixture.coreRvas.size].rva)
            assertEquals(4, hexSize(result.patches[fixture.coreRvas.size].after))
            val recoveryStub = result.patches[fixture.coreRvas.size + 1]
            assertEquals(20, hexSize(recoveryStub.after))
            assertEquals(fixture.updateGate, result.patches.last().rva)

            assertOriginalLayout(fixture.name, source, output)
            for (rva in fixture.preservedRvas) {
                assertEquals("${fixture.name} preserves 0x${rva.toString(16)}", source.word(rva), output.word(rva))
            }
            // These independent sample contracts also check that the source is
            // the original orange/unlocked producer and cache store, not a fake fixture.
            assertEquals(0x52800028, output.word(fixture.preservedRvas[0]))
            assertEquals(0x29007d28, output.word(fixture.preservedRvas[3]))
            assertEquals(0x1a9f0508, output.word(fixture.preservedRvas[4]))

            val stub = decodeBranch(fixture.reportCall, requireNotNull(output.word(fixture.reportCall)), link = true)
            assertEquals(stub, recoveryStub.rva)
            val callerSection = source.sections.single { section ->
                fixture.reportCall >= section.rva && fixture.reportCall + 4 <= section.rva + section.rawSize
            }
            val mappedLength = minOf(callerSection.rawSize.toLong(), callerSection.virtualSize)
            assertTrue("${fixture.name}: original RX mapping", callerSection.executable && !callerSection.writable)
            assertEquals(0L, callerSection.characteristics and 0x02000000L)
            assertTrue(stub >= callerSection.rva && stub + 20 <= callerSection.rva + mappedLength)
            val stubOffset = requireNotNull(source.rvaToOffset(stub, 20))
            assertArrayEquals("${fixture.name}: unused original storage", ByteArray(20), source.bytes.copyOfRange(stubOffset, stubOffset + 20))

            // An earlier, independently archived Freedom output is the oracle:
            // every other byte, including original handoff and root logic, stays exact.
            // The separately verified rollback-update gate is now another intended edit.
            val callerOffset = requireNotNull(legacy.rvaToOffset(fixture.reportCall, 4))
            val gateOffset = requireNotNull(legacy.rvaToOffset(fixture.updateGate, 4))
            assertOnlyRangesDiffer(fixture.name, legacy.bytes, output.bytes,
                listOf(callerOffset until callerOffset + 4, stubOffset until stubOffset + 20,
                    gateOffset until gateOffset + 4))
            val originalReporter = decodeBranch(fixture.reportCall, requireNotNull(source.word(fixture.reportCall)), link = true)
            assertRecoveryMatrix(output, stub, originalReporter)
        }
    }

    @Test fun archivedFreedomOutputsUpgradeAndRepatchWithoutChangingTheCallerArrays() {
        val root = root()
        for (fixture in fixtures) {
            val legacy = read(root, "analysis/fakebl-compat-universal/outputs/${fixture.name}.freedom.pe")
            val unchangedLegacy = legacy.copyOf()
            val fresh = AblEfisp.patch(read(root, fixture.input), PatchMode.FREEDOM)
            val upgraded = AblEfisp.patch(legacy, PatchMode.FREEDOM)
            assertFalse("${fixture.name}: upgrade has new recovery edits", upgraded.alreadyPatched)
            assertArrayEquals(fixture.name, fresh.output, upgraded.output)
            assertArrayEquals(unchangedLegacy, legacy)

            val unchangedOutput = upgraded.output.copyOf()
            val repeated = AblEfisp.patch(upgraded.output, PatchMode.FREEDOM)
            assertTrue("${fixture.name}: stable repeated import", repeated.alreadyPatched)
            assertTrue(repeated.patches.all { it.alreadyApplied })
            assertArrayEquals(fixture.name, unchangedOutput, repeated.output)
            assertArrayEquals("Caller-owned upgraded output", unchangedOutput, upgraded.output)
            assertNotSame(upgraded.output, repeated.output)
        }
    }

    @Test fun successfulFakebl124ReceivesOnlyTheRollbackUpdateGateForOldNewAndOtaFirmware() {
        val root = root()
        for (fixture in fixtures) {
            val input = read(root, fixture.input)
            val unchanged = input.copyOf()
            val result = AblEfisp.patch(input, PatchMode.FREEDOM_FAKE_LOCK)
            val baseline = read(root, "analysis/fakebl-failure2-20261026/outputs/${fixture.name}.fake.pe")
            assertEquals("${fixture.name}: immutable successful baseline", fixture.fake124Sha256, baseline.sha256())
            assertArrayEquals("${fixture.name}: archived successful output",
                withUpdateSkipped(baseline, fixture.updateGate, fixture.updateSkip), result.output)
            assertArrayEquals(unchanged, input)
        }
    }

    @Test fun fakebl123UpgradeAlsoKeepsItsPreviouslyValidatedOutput() {
        val root = root()
        val legacy = read(root, "dist/efispfakeblhonor-repaired-1.2.3.img")
        val unchanged = legacy.copyOf()
        val result = AblEfisp.patch(legacy, PatchMode.FREEDOM_FAKE_LOCK)
        assertEquals("c14d3ef97b2bd4ad4e6aa543702205265775126ca372db89dbfffea6421104da", result.outputSha256)
        val baseline = read(root, "analysis/fakebl-failure2-20261026/outputs/previous-1.2.3.fake.pe")
        assertEquals("577feec7eeed6945a9300e1e35220caf8c94a372393eb8fe7189130fa1184998", baseline.sha256())
        assertArrayEquals(withUpdateSkipped(baseline, 0x47574, 0x47ab0), result.output)
        assertArrayEquals(unchanged, legacy)
    }

    @Test fun coreDiscoveryDoesNotOverrideAnUnsafeRecoveryStorageDecision() {
        val root = root()
        for (name in listOf("already-freedom", "strings-relocated", "partition-decoy-far", "partition-decoy-near")) {
            val input = read(root, "analysis/ablpatcher-fixtures/files/$name.fixture.pe")
            val unchanged = input.copyOf()
            // The original core-only fixture is still discoverable. Adding a new
            // recovery adapter must also respect the tail references it contains.
            assertTrue(SemanticPatcher.plan(PeImage.parse(input)).size in 3..4)
            val failure = assertThrows(PatchException::class.java) { AblEfisp.patch(input, PatchMode.FREEDOM) }
            assertTrue("$name: ${failure.message}", failure.message.orEmpty().startsWith("原映射代码分配："))
            assertArrayEquals("$name input ownership", unchanged, input)
        }
    }

    @Test fun provenPathLiteralBytesDoNotInventTailReferencesInRelocatedFixtures() {
        val root = root()
        for (name in listOf("code-relocated", "raw-and-code-relocated")) {
            val input = read(root, "analysis/ablpatcher-fixtures/files/$name.fixture.pe")
            val unchanged = input.copyOf()
            val image = PeImage.parse(input)
            // Independent regression evidence: the former blocker was ASCII
            // "comp" inside a diagnostic source path, not a live ADR instruction.
            assertEquals(0x706d6f63, image.word(0xcd004))
            assertEquals(3 to 0x1a7df3L, A64.adr(0xcd004, requireNotNull(image.word(0xcd004))))
            assertTrue(ReferencedLiteralData(image, image.instructions().toList()).containsInstructionWord(0xcd004))
            val result = AblEfisp.patch(input, PatchMode.FREEDOM)
            assertArrayEquals("$name input ownership", unchanged, input)
            assertArrayEquals("$name reimport", result.output, AblEfisp.patch(result.output, PatchMode.FREEDOM).output)
        }
    }

    /** Independent fixed sample oracle; not used by production discovery. */
    private fun withUpdateSkipped(baseline: ByteArray, gate: Long, skip: Long): ByteArray {
        val image = PeImage.parse(baseline)
        val offset = requireNotNull(image.rvaToOffset(gate, 4))
        val expected = baseline.copyOf()
        expected.putWord(offset, 0x14000000 or (((skip - gate) / 4).toInt() and 0x03ffffff))
        return expected
    }

    private fun assertOriginalLayout(label: String, source: PeImage, output: PeImage) {
        assertEquals("$label file length", source.bytes.size, output.bytes.size)
        assertEquals("$label headers size", source.headersSize, output.headersSize)
        assertArrayEquals("$label original headers including SizeOfImage and section table",
            source.bytes.copyOfRange(0, source.headersSize), output.bytes.copyOfRange(0, output.headersSize))
        assertEquals("$label section mappings and attributes", source.sections, output.sections)
        assertEquals("$label entry", source.entryRva, output.entryRva)
        assertEquals("$label image base", source.imageBase, output.imageBase)
    }

    private fun assertOnlyRangesDiffer(label: String, before: ByteArray, after: ByteArray, permitted: List<IntRange>) {
        assertEquals(label, before.size, after.size)
        val unexpected = before.indices.firstOrNull { before[it] != after[it] && permitted.none { range -> it in range } }
        assertNull("$label unexpected changed offset ${unexpected?.toString(16)}", unexpected)
        for (range in permitted) assertTrue("$label expected edit at ${range.first.toString(16)}",
            range.any { before[it] != after[it] })
    }

    private fun hexSize(value: String): Int = value.trim().split(Regex("\\s+")).size

    // Independent decoding/execution is limited to the emitted 20-byte AArch64
    // conditional call adapter. This is not an emulator for the complete boot chain.
    private fun decodeBranch(pc: Long, word: Int, link: Boolean): Long {
        assertEquals(if (link) 0x94000000.toInt() else 0x14000000, word and 0xfc000000.toInt())
        return pc + signed(word and 0x03ffffff, 26) * 4
    }

    private fun signed(value: Int, bits: Int): Long = (value shl (32 - bits) shr (32 - bits)).toLong()

    private enum class RecoveryOutcome { ORIGINAL_REPORTER, RETURN_TO_CALLER }

    private fun assertRecoveryMatrix(image: PeImage, stub: Long, originalReporter: Long) {
        // Register numbers here are independent contracts of these three source
        // firmwares, rather than values supplied by the patch matcher.
        for (allow in listOf(0, 1)) for (error in listOf(-1, 0, 1, 2, 3, 4, 5, 6)) {
            val registers = IntArray(32) { 0x55 }
            registers[22] = allow
            registers[26] = error
            val expected = if (allow == 1 && error == 3) RecoveryOutcome.RETURN_TO_CALLER else RecoveryOutcome.ORIGINAL_REPORTER
            assertEquals("allow=$allow error=$error", expected, runRecoveryStub(image, stub, originalReporter, registers))
        }
    }

    private fun runRecoveryStub(image: PeImage, stub: Long, reporter: Long, registers: IntArray): RecoveryOutcome {
        var pc = stub
        var zero = false
        repeat(8) {
            if (pc == reporter) return RecoveryOutcome.ORIGINAL_REPORTER
            assertTrue("Adapter escaped its original call target", pc in stub until stub + 20)
            val word = requireNotNull(image.word(pc))
            pc = when {
                word and 0xff000000.toInt() == 0x34000000 -> {
                    val target = pc + signed((word ushr 5) and 0x7ffff, 19) * 4
                    if (registers[word and 31] == 0) target else pc + 4
                }
                word and 0xffc0001f.toInt() == 0x7100001f -> {
                    zero = registers[(word ushr 5) and 31] == ((word ushr 10) and 0xfff)
                    pc + 4
                }
                word and 0xff00001f.toInt() == 0x54000001 ->
                    if (!zero) pc + signed((word ushr 5) and 0x7ffff, 19) * 4 else pc + 4
                word == 0xd65f03c0.toInt() -> return RecoveryOutcome.RETURN_TO_CALLER
                word and 0xfc000000.toInt() == 0x14000000 -> decodeBranch(pc, word, link = false)
                else -> throw AssertionError("Unexpected adapter instruction 0x${word.toUInt().toString(16)}")
            }
        }
        throw AssertionError("Recovery adapter did not return or call the original reporter")
    }
}
