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
        val specialGate: Long? = null,
        val specialSkip: Long? = null,
        val nativeFakeSha256: String,
    )

    private val fixtures = listOf(
        Fixture(
            "old", "abl.elf", listOf(0x10f58, 0x10fb0, 0x11078, 0xe1314), 0x851f4,
            // Normal state, outgoing Keymaster byte, device_state selection,
            // check_root_stat cache and the common late-state selector.
            listOf(0x9cc70, 0x9ce34, 0x87988, 0x11178, 0x9c954),
            "a16fd1e4240219c592839363913d2aa68221d3e3edb774e46a2f0ac9676eb919",
            0x9b250, 0x9b770,
            nativeFakeSha256 = "0204b6d3402a39ec8e94e0a5b8dba3f71660b57bb47125723dcf341631dfff39",
        ),
        Fixture(
            "new", "ablnew.img", listOf(0x106ac, 0x10704, 0x1076c), 0x4ea30,
            listOf(0x4999c, 0x499fc, 0x511bc, 0x1086c, 0x48cd4),
            "545a66bb6c3e500059f933113f0bab2a11ed609ac743845c3f883a971eea841c",
            0x47574, 0x47ab0, 0x8670c, 0x86774,
            nativeFakeSha256 = "09a38e8989b95bd393e24f2101219e311002003bda612a63b7cfcf719f79bbf3",
        ),
        Fixture(
            "ota", "analysis/locked-boot-feasibility/fastboot-ota-abl.pe",
            listOf(0x106ac, 0x10704, 0x1076c), 0x4ea30,
            listOf(0x4999c, 0x499fc, 0x511bc, 0x1086c, 0x48cd4),
            "259be637ccd95cc64df29b49c18abde288deaef4ffa9a2f7ccf598f6d4fdeddb",
            0x47574, 0x47ab0, 0x8670c, 0x86774,
            nativeFakeSha256 = "3131ed9786a358360a627509dee3377a376984adb010a84ef56d6883a1dee5d5",
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

    @Test fun freedomPreservesNativeReportingWithoutAllocatingAReportStub() {
        val root = root()
        for (fixture in fixtures) {
            val input = read(root, fixture.input); val unchanged = input.copyOf()
            val source = PeImage.parse(AblEfisp.extract(input).output)
            val legacy = PeImage.parse(read(root, "analysis/fakebl-compat-universal/outputs/${fixture.name}.freedom.pe"))
            val result = legacyPatch(input, PatchMode.FREEDOM); val output = PeImage.parse(result.output)
            val special = assertSpecialSdam(result, fixture)
            assertEquals(fixture.name, PatchMode.FREEDOM, result.mode)
            assertFalse(fixture.name, result.alreadyPatched)
            assertArrayEquals("Input ownership: ${fixture.name}", unchanged, input)
            assertEquals(fixture.coreRvas, result.patches.take(fixture.coreRvas.size).map { it.rva })
            assertEquals(fixture.name, fixture.coreRvas.size + 1 + special.size, result.patches.size)
            assertEquals(fixture.updateGate, result.patches[fixture.coreRvas.size].rva)
            assertTrue(result.patches.all { hexSize(it.after) <= 4 })
            assertTrue(result.patches.none { it.name == "恢复原生 AVB hash 故障上报调用" })
            assertOriginalLayout(fixture.name, source, output)
            for (rva in fixture.preservedRvas) assertEquals("${fixture.name} preserves 0x${rva.toString(16)}", source.word(rva), output.word(rva))
            assertEquals(0x52800028, output.word(fixture.preservedRvas[0]))
            assertEquals(0x29007d28, output.word(fixture.preservedRvas[3]))
            assertEquals(0x1a9f0508, output.word(fixture.preservedRvas[4]))
            assertEquals(source.word(fixture.reportCall), legacy.word(fixture.reportCall))
            assertEquals(source.word(fixture.reportCall), output.word(fixture.reportCall))
            val originalReporter = decodeBranch(fixture.reportCall, requireNotNull(source.word(fixture.reportCall)), link = true)
            assertNativeReporterMatrix(output, fixture.reportCall, originalReporter)
            // Every archived byte remains exact except the independently
            // specified rollback/special gates. No report allocation is hidden.
            val expected = withSpecialSdamSkipped(withUpdateSkipped(legacy.bytes, fixture.updateGate, fixture.updateSkip), fixture)
            assertArrayEquals(fixture.name, expected, output.bytes)
            val permitted = arrayListOf(requireNotNull(legacy.rvaToOffset(fixture.updateGate,4)).let { it until it+4 })
            fixture.specialGate?.let { gate -> val at=requireNotNull(legacy.rvaToOffset(gate,4));permitted += at until at+4 }
            assertOnlyRangesDiffer(fixture.name, legacy.bytes, output.bytes, permitted)
        }
    }

    @Test fun archivedFreedomOutputsUpgradeAndRepatchWithoutChangingTheCallerArrays() {
        val root = root()
        for (fixture in fixtures) {
            val legacy = read(root, "analysis/fakebl-compat-universal/outputs/${fixture.name}.freedom.pe")
            val unchangedLegacy = legacy.copyOf()
            val fresh = legacyPatch(read(root, fixture.input), PatchMode.FREEDOM)
            val upgraded = legacyPatch(legacy, PatchMode.FREEDOM)
            assertFalse("${fixture.name}: upgrade has independent rollback/special edits", upgraded.alreadyPatched)
            assertArrayEquals(fixture.name, fresh.output, upgraded.output)
            assertArrayEquals(unchangedLegacy, legacy)

            val unchangedOutput = upgraded.output.copyOf()
            val repeated = legacyPatch(upgraded.output, PatchMode.FREEDOM)
            assertTrue("${fixture.name}: stable repeated import", repeated.alreadyPatched)
            assertTrue(repeated.patches.all { it.alreadyApplied })
            assertArrayEquals(fixture.name, unchangedOutput, repeated.output)
            assertArrayEquals("Caller-owned upgraded output", unchangedOutput, upgraded.output)
            assertNotSame(upgraded.output, repeated.output)
        }
    }

    @Test fun successfulFakebl124ReceivesTheIndependentRollbackAndSpecialSdamGates() {
        val root = root()
        for (fixture in fixtures) {
            val input = read(root, fixture.input)
            val unchanged = input.copyOf()
            val result = legacyPatch(input, PatchMode.FREEDOM_FAKE_LOCK)
            val baseline = read(root, "analysis/fakebl-failure2-20261026/outputs/${fixture.name}.fake.pe")
            assertEquals("${fixture.name}: immutable successful baseline", fixture.fake124Sha256, baseline.sha256())
            val source = PeImage.parse(AblEfisp.extract(input).output)
            val expected = withSpecialSdamSkipped(withUpdateSkipped(nativeReportingGolden(baseline, source, fixture), fixture.updateGate, fixture.updateSkip), fixture)
            assertEquals("${fixture.name}: independently rebuilt native-report golden", fixture.nativeFakeSha256, expected.sha256())
            assertArrayEquals("${fixture.name}: archived successful output with exact native reporting/Late relocation", expected, result.output)
            assertOriginalLayout(fixture.name, source, PeImage.parse(result.output))
            assertNativeReporterMatrix(PeImage.parse(result.output), fixture.reportCall,
                decodeBranch(fixture.reportCall, requireNotNull(source.word(fixture.reportCall)), link=true))
            assertSpecialSdam(result, fixture)
            assertArrayEquals(unchanged, input)
        }
    }

    @Test fun fakebl123UpgradeAlsoKeepsItsPreviouslyValidatedOutput() {
        val root = root()
        val legacy = read(root, "dist/efispfakeblhonor-repaired-1.2.3.img")
        val unchanged = legacy.copyOf()
        val result = legacyPatch(legacy, PatchMode.FREEDOM_FAKE_LOCK)
        assertEquals("f362f9e5e9560f4610b389a2b44556834c15f7df4cb38c1927353f44f46ee183", result.outputSha256)
        val baseline = read(root, "analysis/fakebl-failure2-20261026/outputs/previous-1.2.3.fake.pe")
        assertEquals("577feec7eeed6945a9300e1e35220caf8c94a372393eb8fe7189130fa1184998", baseline.sha256())
        assertEquals("2d545d06ad99931c34ae935f9705b8b62d3a9582c708e85b13eb7f1928f3e7e0",legacy.sha256())
        val fixture = fixtures.single { it.name == "new" }
        val source = PeImage.parse(AblEfisp.extract(read(root, fixture.input)).output)
        val historicalSource = PeImage.parse(AblEfisp.extract(legacy).output)
        val historicalStub = decodeBranch(fixture.reportCall,requireNotNull(historicalSource.word(fixture.reportCall)),link=true)
        assertEquals(0x1ec000L,historicalStub)
        val nativeReporter = decodeBranch(historicalStub+16,requireNotNull(historicalSource.word(historicalStub+16)),link=false)
        val nativeWord = branchWord(fixture.reportCall,nativeReporter) or 0x80000000.toInt()
        assertEquals(requireNotNull(source.word(fixture.reportCall)),nativeWord)
        assertEquals(0x1a9f0508,historicalSource.word(fixture.preservedRvas.last())) // no existing Late in the actual input
        // The archived successful output had allocated report then Late.
        // This input owns only the far report adapter, so native reporting frees
        // the first RX slot for the newly required Late feature. Reconstruct all
        // moved words/return branches from input zeros and the immutable golden.
        val expected = withSpecialSdamSkipped(withUpdateSkipped(
            nativeReportingGolden(baseline,historicalSource,fixture,nativeWord),fixture.updateGate,fixture.updateSkip),fixture)
        assertEquals("f362f9e5e9560f4610b389a2b44556834c15f7df4cb38c1927353f44f46ee183",expected.sha256())
        assertArrayEquals("Historical upgrade has the exact native BL, two gates and independently reconstructed new Late placement",expected,result.output)
        val sourceOrphanAt = requireNotNull(historicalSource.rvaToOffset(historicalStub,20))
        val orphanAt = requireNotNull(PeImage.parse(result.output).rvaToOffset(historicalStub,20))
        assertArrayEquals(historicalSource.bytes.copyOfRange(sourceOrphanAt,sourceOrphanAt+20),result.output.copyOfRange(orphanAt,orphanAt+20))
        assertOriginalLayout("historical123",historicalSource,PeImage.parse(result.output))
        assertNativeReporterMatrix(PeImage.parse(result.output),fixture.reportCall,nativeReporter)
        val repeated = legacyPatch(result.output,PatchMode.FREEDOM_FAKE_LOCK)
        assertTrue(repeated.alreadyPatched);assertArrayEquals(result.output,repeated.output)
        assertSpecialSdam(result, fixtures.single { it.name == "new" })
        assertArrayEquals(unchanged, legacy)
    }

    @Test fun coreFixturesWithTailReferencesPatchAndReimportWithoutChangingTheirLayout() {
        val root = root()
        for (name in listOf("already-freedom", "strings-relocated", "partition-decoy-far", "partition-decoy-near")) {
            val input = read(root, "analysis/ablpatcher-fixtures/files/$name.fixture.pe")
            val unchanged = input.copyOf()
            val source = PeImage.parse(input)
            val core = SemanticPatcher.plan(source)
            assertTrue(core.size in 3..4)
            val result = legacyPatch(input, PatchMode.FREEDOM)
            assertEquals(core.map { it.rva }, result.patches.take(core.size).map { it.rva })
            assertOriginalLayout(name, source, PeImage.parse(result.output))
            assertArrayEquals("$name input ownership", unchanged, input)
            val savedOutput = result.output.copyOf()
            val repeated = legacyPatch(result.output, PatchMode.FREEDOM)
            assertTrue("$name repeated import", repeated.alreadyPatched)
            assertArrayEquals("$name stable generated bytes", savedOutput, repeated.output)
            assertArrayEquals("$name output ownership", savedOutput, result.output)
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
            val result = legacyPatch(input, PatchMode.FREEDOM)
            assertArrayEquals("$name input ownership", unchanged, input)
            assertArrayEquals("$name reimport", result.output, legacyPatch(result.output, PatchMode.FREEDOM).output)
        }
    }

    /** Rebuild the known pre-native-report golden using immutable archived
     * bytes and original native instructions. No production locator/patcher
     * supplies these expected edits or allocation addresses. */
    private fun nativeReportingGolden(baseline: ByteArray, source: PeImage, fixture: Fixture,
                                      nativeCallWord: Int? = null): ByteArray {
        val image = PeImage.parse(baseline); assertOriginalLayout(fixture.name, source, image)
        val report = fixture.reportCall; val selector = fixture.preservedRvas.last()
        val nativeWord = nativeCallWord ?: requireNotNull(source.word(report))
        assertEquals(if (fixture.name == "old") 0x9400a36a.toInt() else 0x94017ac9.toInt(), nativeWord)
        val originalReporter = decodeBranch(report,nativeWord,link=true)
        val retired = decodeBranch(report,requireNotNull(image.word(report)),link=true)
        val previousLate = decodeBranch(selector,requireNotNull(image.word(selector)),link=false)
        assertEquals(0x34000096, image.word(retired))
        assertEquals(0x1a9f0508, source.word(selector))
        assertEquals(0xb500007a.toInt(), image.word(previousLate))
        assertEquals(0x1a9f0108, image.word(previousLate+4))
        assertEquals(0x1a9f0508, image.word(previousLate+12))
        assertEquals(selector+4,decodeBranch(previousLate+8,requireNotNull(image.word(previousLate+8)),link=false))
        assertEquals(selector+4,decodeBranch(previousLate+16,requireNotNull(image.word(previousLate+16)),link=false))
        assertRecoveryMatrix(image,retired,originalReporter) // retain the old 16-case ownership oracle
        val expected=baseline.copyOf()
        expected.putWord(requireNotNull(image.rvaToOffset(report,4)),nativeWord)
        for (rva in listOf(retired,previousLate)) {
            val from=requireNotNull(source.rvaToOffset(rva,20));val to=requireNotNull(image.rvaToOffset(rva,20))
            assertArrayEquals("${fixture.name}: original unallocated RX storage",ByteArray(20),source.bytes.copyOfRange(from,from+20))
            source.bytes.copyInto(expected,to,from,from+20)
        }
        // With no fresh report allocation, the existing 20-byte Late feature
        // occupies the first historical report slot; branch returns are exact.
        val words=intArrayOf(0xb500007a.toInt(),0x1a9f0108,
            branchWord(retired+8,selector+4),0x1a9f0508,branchWord(retired+16,selector+4))
        words.forEachIndexed { i,w -> expected.putWord(requireNotNull(image.rvaToOffset(retired+i*4L,4)),w) }
        expected.putWord(requireNotNull(image.rvaToOffset(selector,4)),branchWord(selector,retired))
        val allowed=listOf(report to 4,retired to 20,previousLate to 20,selector to 4).map { (pc,n) ->
            val at=requireNotNull(image.rvaToOffset(pc,n)); at until at+n
        }
        assertOnlyRangesDiffer("${fixture.name}: exact native report/Late relocation",baseline,expected,allowed)
        return expected
    }

    private fun branchWord(pc: Long,target: Long): Int =
        0x14000000 or (((target-pc)/4).toInt() and 0x03ffffff)

    private fun assertNativeReporterMatrix(image: PeImage,call: Long,originalReporter: Long) {
        // Every allow/error combination now dispatches the original reporter.
        // Only the BL return address changes; the call's input registers persist.
        for (allow in listOf(0,1)) for (error in listOf(-1,0,1,2,3,4,5,6)) {
            val registers=IntArray(32){0x55};registers[22]=allow;registers[26]=error
            val saved=registers.copyOf()
            val target=decodeBranch(call,requireNotNull(image.word(call)),link=true)
            registers[30]=(call+4).toInt()
            assertEquals("allow=$allow error=$error native reporter",originalReporter,target)
            assertArrayEquals(saved.copyOfRange(0,30),registers.copyOfRange(0,30))
        }
    }

    private fun assertSpecialSdam(result: PatchResult, fixture: Fixture): List<PatchMatch> {
        val edits = result.patches.filter { it.name == "special SDAM 更新事务采用原生不更新路径" }
        assertEquals(fixture.name, if (fixture.specialGate == null) 0 else 1, edits.size)
        fixture.specialGate?.let { gate ->
            val target = requireNotNull(fixture.specialSkip)
            val expected = ByteArray(4).also { it.putWord(0, 0x14000000 or (((target - gate) / 4).toInt() and 0x03ffffff)) }
            assertEquals(gate, edits.single().rva)
            assertEquals(expected.hex(), edits.single().after)
            val output = PeImage.parse(result.output)
            val at = requireNotNull(output.rvaToOffset(gate, 4))
            assertArrayEquals(expected, result.output.copyOfRange(at, at + 4))
            assertEquals(4, hexSize(edits.single().after))
        }
        return edits
    }

    private fun withSpecialSdamSkipped(baseline: ByteArray, fixture: Fixture): ByteArray =
        fixture.specialGate?.let { withUpdateSkipped(baseline, it, requireNotNull(fixture.specialSkip)) } ?: baseline.copyOf()

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
