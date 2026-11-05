package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Full default-output contracts, independent of the legacy feature-view helpers. */
class FlashingUnlockPatcherTest {
    private data class Fixture(val name: String, val path: String, val front: Long, val ability: Long, val transaction: Long,
        val specialGate: Long? = null, val specialSkip: Long? = null)
    private val fixtures = listOf(
        Fixture("old", "abl.elf", 0x21524, 0x2159c, 0x227a0),
        Fixture("new", "ablnew.img", 0x20d84, 0x20dfc, 0x21fe8, 0x8670c, 0x86774),
        Fixture("ota", "analysis/locked-boot-feasibility/fastboot-ota-abl.pe", 0x20d84, 0x20dfc, 0x21fe8, 0x8670c, 0x86774),
        Fixture("winrt2", "analysis/winrt2-abl-audit-20261028/input-winrt2_abl_b.img", 0x20d84, 0x20dfc, 0x21fe8, 0x8685c, 0x868c4),
    )
    private fun root(): File {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(7).firstOrNull { File(it, "analysis/flashing-unlock-20261029/baseline/results.tsv").isFile }
        assumeTrue("Independent retained-core outputs required", root != null)
        return requireNotNull(root)
    }
    private fun raw(f: Fixture): ByteArray = File(root(), f.path).readBytes()
    private fun pe(f: Fixture): PeImage = PeImage.parse(AblEfisp.extract(raw(f)).output)
    private fun mutate(image: PeImage, edits: Map<Long, Int>): ByteArray = image.bytes.copyOf().also { copy ->
        edits.forEach { (rva, word) -> copy.putWord(requireNotNull(image.rvaToOffset(rva, 4)), word) }
    }

    @Test fun fourLayoutsBothModesRetainEveryByteOutsideTheFiveNewEdits() {
        for (f in fixtures) for (mode in PatchMode.entries) {
            val source = raw(f); val snapshot = source.copyOf()
            val finalResult = AblEfisp.patch(source, mode)
            // Validate/remove the independently executed VM receipt and exact
            // key span before comparing this historical per-feature baseline.
            val result = finalResult.preVmHistoricalView()
            val historical = File(root(), "analysis/flashing-unlock-20261029/baseline/${f.name}.${if (mode == PatchMode.FREEDOM) "freedom" else "fake"}.pe").readBytes()
            val baseline = nativeReportingGolden(f, mode, historical)
            assertTrue(result.patches.none { it.name == "恢复原生 AVB hash 故障上报调用" })
            assertEquals(nativeOracle(f).nativeCall, requireNotNull(PeImage.parse(result.output).word(nativeOracle(f).reportCall)))
            val added = result.patches.filter { it.name.startsWith("flashing unlock：") }
            assertEquals(5, added.size)
            assertEquals(listOf(f.front, f.ability, f.transaction), added.take(3).map { it.rva })
            assertEquals(listOf(16, 104), added.drop(3).map { it.after.split(' ').size })
            val independent = result.patches.filter { it.name.startsWith("oem superunlock：") }
            assertEquals(2, independent.size)
            val dataHash = result.patches.filter { it.name.startsWith("AVB HashDescriptor") }
            assertEquals(1, dataHash.size)
            assertEquals(4, dataHash.single().after.split(' ').size)
            val pss = result.patches.filter { it.name == "共用 RSA-PSS 验证入口固定成功" }
            assertEquals(1, pss.size)
            assertEquals(12, pss.single().after.split(' ').size)
            val special = assertSpecialSdam(result, f)
            val compatibility = result.patches.filter { isCompatibilityPolicy(it.name) }
            assertEquals(10, compatibility.size)
            assertTrue(compatibility.all { it.after.split(' ').size == 4 })
            val allowed = (added + independent + dataHash + compatibility + pss + special)
                .flatMap { it.fileOffset until it.fileOffset + it.after.split(' ').size }.toSet()
            assertEquals(baseline.size, result.output.size)
            assertTrue("${f.name}/$mode changed an unrelated byte", baseline.indices.all {
                baseline[it] == result.output[it] || it in allowed
            })
            val before = PeImage.parse(baseline); val after = PeImage.parse(result.output)
            assertEquals(before.sections, after.sections)
            assertEquals(before.entryRva, after.entryRva)
            assertArrayEquals(baseline.copyOfRange(0, before.headersSize), result.output.copyOfRange(0, after.headersSize))
            for (stub in added.drop(3)) assertArrayEquals(ByteArray(stub.after.split(' ').size),
                baseline.copyOfRange(stub.fileOffset, stub.fileOffset + stub.after.split(' ').size))
            val repeated = AblEfisp.patch(finalResult.output, mode)
            assertTrue(repeated.alreadyPatched)
            assertArrayEquals(finalResult.output, repeated.output)
            assertArrayEquals(snapshot, source)
        }
    }

    @Test fun discoversThePersistentBackendAndItsIndependentReadSize() {
        for (f in fixtures) {
            val input = pe(f)
            val p = requireNotNull(FlashingUnlockPatcher.analyze(input))
            assertEquals(f.front, p.frontGateRva)
            assertEquals(f.ability, p.abilityGateRva)
            assertEquals(f.transaction, p.transactionGateRva)
            assertEquals(0xd, p.normalFieldOffset)
            assertEquals(0xd10, p.deviceInfoBytes)
            assertTrue(input.isWritable(p.deviceInfoRva, p.deviceInfoBytes))
            assertEquals(if (f.name == "old") 0xd8d4L else 0xd03cL, p.writeBackendRva)
        }
    }

    @Test fun displayAndInfoWordingSitesDoNotDeterminePersistentUnlockSuccess() {
        val source = pe(fixtures.last())
        val original = requireNotNull(FlashingUnlockPatcher.analyze(source))
        val changed = PeImage.parse(mutate(source, listOf(0x15044L, 0x357e0L, 0x359e8L).associateWith { 0xd503201f.toInt() }))
        val candidate = requireNotNull(FlashingUnlockPatcher.analyze(changed))
        assertEquals(original, candidate)
    }

    @Test fun rejectsWipeEntryPretendingToBeTheErrorEpilogue() {
        val source = pe(fixtures[1])
        val pc = 0x22008L; val wipe = 0x2203cL
        val bad = mutate(source, mapOf(pc to (0xb5000000.toInt() or (((wipe - pc) / 4).toInt() shl 5))))
        val snapshot = bad.copyOf()
        assertThrows(PatchException::class.java) { FlashingUnlockPatcher.analyze(PeImage.parse(bad)) }
        assertArrayEquals(snapshot, bad)
    }

    @Test fun rejectsChangedVerifiedBootIdentityOrBackendMethod() {
        val source = pe(fixtures[1])
        val badGuid = source.bytes.copyOf()
        badGuid[requireNotNull(source.rvaToOffset(0x1b4e44, 16))] = 0
        assertThrows(PatchException::class.java) { FlashingUnlockPatcher.analyze(PeImage.parse(badGuid)) }
        val badMethod = mutate(source, mapOf(0xd0a0L to (requireNotNull(source.word(0xd0a0)) + 0x400)))
        assertThrows(PatchException::class.java) { FlashingUnlockPatcher.analyze(PeImage.parse(badMethod)) }
    }

    @Test fun oldCompletedImagesUpgradeWithoutOverwritingTheirSource() {
        for (mode in PatchMode.entries) {
            val baseline = File(root(), "analysis/flashing-unlock-20261029/baseline/new.${if (mode == PatchMode.FREEDOM) "freedom" else "fake"}.pe").readBytes()
            val snapshot = baseline.copyOf()
            val finalUpgrade = AblEfisp.patch(baseline, mode)
            val upgrade = finalUpgrade.preVmHistoricalView()
            assertFalse(upgrade.alreadyPatched)
            val dataHash = upgrade.patches.filter { it.name.startsWith("AVB HashDescriptor") }
            assertEquals(1, dataHash.size)
            val compatibility = upgrade.patches.filter { isCompatibilityPolicy(it.name) }
            assertEquals(10, compatibility.size)
            assertTrue(compatibility.all { it.after.split(' ').size == 4 })
            val pss = upgrade.patches.filter { it.name == "共用 RSA-PSS 验证入口固定成功" }
            assertEquals(1, pss.size)
            assertEquals(12, pss.single().after.split(' ').size)
            val fixture = fixtures.single { it.name == "new" }
            val nativeReporting = assertNativeRestoration(upgrade, fixture, mode, baseline)
            val special = assertSpecialSdam(upgrade, fixture)
            assertEquals(7 + dataHash.count { !it.alreadyApplied } + compatibility.count { !it.alreadyApplied } + pss.count { !it.alreadyApplied } + special.count { !it.alreadyApplied } + nativeReporting.count { !it.alreadyApplied },
                upgrade.patches.count { !it.alreadyApplied })
            assertArrayEquals(snapshot, baseline)
            assertTrue(AblEfisp.patch(finalUpgrade.output, mode).alreadyPatched)
        }
    }

    private fun isCompatibilityPolicy(name: String) = name.startsWith("AVB 低索引") ||
        name.startsWith("OEM 版本") || name.startsWith("BRINGUPAUTH") || name.startsWith("AVB 版本兼容")

    /** Independent per-fixture branch oracle; no matcher supplies expected bytes. */
    private fun assertSpecialSdam(result: PatchResult, fixture: Fixture): List<PatchMatch> {
        val edits = result.patches.filter { it.name == "special SDAM 更新事务采用原生不更新路径" }
        val gate = fixture.specialGate
        assertEquals(fixture.name, if (gate == null) 0 else 1, edits.size)
        if (gate != null) {
            val target = requireNotNull(fixture.specialSkip)
            val expected = ByteArray(4).also { it.putWord(0, 0x14000000 or (((target - gate) / 4).toInt() and 0x03ffffff)) }
            assertEquals(gate, edits.single().rva)
            assertEquals(expected.hex(), edits.single().after)
            val output = PeImage.parse(result.output)
            val at = requireNotNull(output.rvaToOffset(gate, 4))
            assertArrayEquals(expected, result.output.copyOfRange(at, at + 4))
            assertEquals(4, edits.single().after.split(' ').size)
        }
        return edits
    }

    private data class NativeOracle(val reportCall: Long, val reportTarget: Long, val retiredStub: Long,
        val oldLate: Long, val selector: Long, val nativeCall: Int, val redirectedCall: Int)

    /** Fixture measurements from retained historical bytes, not production matcher output. */
    private fun nativeOracle(fixture: Fixture): NativeOracle = when (fixture.name) {
        "old" -> NativeOracle(0x851f4, 0xadf9c, 0x1a7ba0, 0x1a7bc0, 0x9c954, 0x9400a36a.toInt(), 0x94048a6b.toInt())
        "new" -> NativeOracle(0x4ea30, 0xad554, 0x1a7100, 0x1a7120, 0x48cd4, 0x94017ac9.toInt(), 0x940561b4.toInt())
        "ota" -> NativeOracle(0x4ea30, 0xad554, 0x1a7140, 0x1a7160, 0x48cd4, 0x94017ac9.toInt(), 0x940561c4.toInt())
        "winrt2" -> NativeOracle(0x4ea38, 0xad6a4, 0x1a7280, 0x1a72a0, 0x48cd8, 0x94017b1b.toInt(), 0x94056212.toInt())
        else -> error("Unlisted independent fixture")
    }

    private fun oracleBranch(from: Long, target: Long): Int {
        val distance = target - from
        check(distance % 4L == 0L && distance / 4 in -(1L shl 25) until (1L shl 25))
        return 0x14000000 or ((distance / 4).toInt() and 0x03ffffff)
    }

    private fun lateWords(stub: Long, selector: Long) = intArrayOf(
        0xb500007a.toInt(), 0x1a9f0108, oracleBranch(stub + 8, selector + 4),
        0x1a9f0508, oracleBranch(stub + 16, selector + 4))

    private fun wordBytes(words: IntArray) = ByteArray(words.size * 4).also { bytes ->
        words.forEachIndexed { index, word -> bytes.putWord(index * 4, word) }
    }

    /** Normalize only the historical 4B reporting redirect and its exact 20B
     * allocation, plus the independently measured fake-mode Late relocation.
     * Fresh native calls have no one-time reporting restoration patch. */
    private fun nativeReportingGolden(fixture: Fixture, mode: PatchMode, historical: ByteArray): ByteArray {
        val original = pe(fixture)
        val retained = PeImage.parse(historical)
        val item = nativeOracle(fixture)
        assertEquals(item.nativeCall, requireNotNull(original.word(item.reportCall)))
        assertEquals(item.redirectedCall, requireNotNull(retained.word(item.reportCall)))
        val oldReport = wordBytes(intArrayOf(0x34000096, 0x71000f5f, 0x54000041, 0xd65f03c0.toInt(),
            oracleBranch(item.retiredStub + 16, item.reportTarget)))
        val retiredAt = requireNotNull(retained.rvaToOffset(item.retiredStub, 20))
        assertArrayEquals(oldReport, historical.copyOfRange(retiredAt, retiredAt + 20))
        val expected = historical.copyOf()
        expected.putWord(requireNotNull(retained.rvaToOffset(item.reportCall, 4)), item.nativeCall)
        fun reclaimExactPadding(rva: Long) {
            val sourceAt = requireNotNull(original.rvaToOffset(rva, 20))
            val destinationAt = requireNotNull(retained.rvaToOffset(rva, 20))
            val padding = original.bytes.copyOfRange(sourceAt, sourceAt + 20)
            assertArrayEquals(ByteArray(20), padding)
            padding.copyInto(expected, destinationAt)
        }
        reclaimExactPadding(item.retiredStub)
        if (mode == PatchMode.FREEDOM_FAKE_LOCK) {
            assertEquals(0x1a9f0508, requireNotNull(original.word(item.selector)))
            assertEquals(oracleBranch(item.selector, item.oldLate), requireNotNull(retained.word(item.selector)))
            val oldLateAt = requireNotNull(retained.rvaToOffset(item.oldLate, 20))
            assertArrayEquals(wordBytes(lateWords(item.oldLate, item.selector)), historical.copyOfRange(oldLateAt, oldLateAt + 20))
            reclaimExactPadding(item.oldLate)
            wordBytes(lateWords(item.retiredStub, item.selector)).copyInto(expected, retiredAt)
            expected.putWord(requireNotNull(retained.rvaToOffset(item.selector, 4)), oracleBranch(item.selector, item.retiredStub))
        }
        return expected
    }

    private fun assertNativeRestoration(upgrade: PatchResult, fixture: Fixture, mode: PatchMode,
        historical: ByteArray): List<PatchMatch> {
        val item = nativeOracle(fixture)
        val expected = ByteArray(4).also { it.putWord(0, item.nativeCall) }
        val edits = upgrade.patches.filter { it.name == "恢复原生 AVB hash 故障上报调用" }
        assertEquals(1, edits.size)
        assertEquals(item.reportCall, edits.single().rva)
        assertEquals(expected.hex(), edits.single().after)
        assertFalse(edits.single().alreadyApplied)
        val before = PeImage.parse(historical)
        val after = PeImage.parse(upgrade.output)
        assertEquals(item.nativeCall, requireNotNull(after.word(item.reportCall)))
        val retiredAt = requireNotNull(before.rvaToOffset(item.retiredStub, 20))
        assertArrayEquals(historical.copyOfRange(retiredAt, retiredAt + 20), upgrade.output.copyOfRange(retiredAt, retiredAt + 20))
        if (mode == PatchMode.FREEDOM_FAKE_LOCK) {
            assertEquals(before.word(item.selector), after.word(item.selector))
            val oldLateAt = requireNotNull(before.rvaToOffset(item.oldLate, 20))
            assertArrayEquals(historical.copyOfRange(oldLateAt, oldLateAt + 20), upgrade.output.copyOfRange(oldLateAt, oldLateAt + 20))
        }
        return edits
    }

}
