package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Actual fixture instructions and isolated policy edits; no device or crypto execution. */
class PssVerificationPatcherTest {
    private data class Original(val name: String, val path: String, val sha256: String)

    private fun workspace(): File = requireNotNull(
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(9).firstOrNull { File(it, "analysis/ablpatcher-fixtures/manifest.json").isFile },
    ) { "Required independent PSS fixtures are missing; this suite must not silently skip." }

    private fun bytes(path: String, expected: String? = null): ByteArray {
        val file = File(workspace(), path)
        check(file.isFile) { "Required fixture $path" }
        return file.readBytes().also { if (expected != null) assertEquals(path, expected, hash(it)) }
    }

    private fun hash(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun originals() = listOf(
        Original("builtin", "analysis/builtin-vs-bkq-stock-20261030/builtin-inner.pe",
            "00b12cd4179d907c2097b6942fcf47dc928372622d494166fcb340a8dc5f07bf"),
        Original("233110-Magic8", "analysis/bkq150-newabl-bootloop-20261102/fallback-audit/source-inner.pe",
            "bc5fca84d3f5dbfceaba29ec1cd17783190ccc6abaaca1b235c070ca5219ae0e"),
        Original("BRT", "analysis/winrt2-blackboot-20261029/firmware/modules/abl.elf-0x1064-0xb8.pe",
            "7dbd7fe4585daee71f36ac72bb734232e6267fe87d6a738b5f340f6ee17bea5b"),
        Original("APH136", "analysis/canoe-unlock-integration-20261029/audit/runtime/aph-source.inner.pe",
            "f16f54464e4fd7f3f6f55a4569cd72c762262573b6a3a8da4fa7641010841b2c"),
    )

    private fun apply(image: PeImage, edits: List<PlannedEdit>): PeImage = PeImage.parse(
        image.bytes.copyOf().also { output ->
            for (edit in edits) edit.replacement.copyInto(output,
                requireNotNull(image.rvaToOffset(edit.rva, edit.replacement.size)))
        },
    )

    private fun changed(image: PeImage, vararg words: Pair<Long, Int>): PeImage = PeImage.parse(
        image.bytes.copyOf().also { output ->
            for ((pc, word) in words) output.putWord(requireNotNull(image.rvaToOffset(pc, 4)), word)
        },
    )

    private fun callers(image: PeImage, entry: Long) = image.instructions()
        .filter { (pc, word) -> A64.blTarget(pc, word) == entry }.map { it.first }.toList()

    private fun pageAddress(pc: Long, target: Long, register: Int): Int {
        val pages = ((target and -4096L) - (pc and -4096L)) shr 12
        return 0x90000000.toInt() or ((pages.toInt() and 3) shl 29) or
            (((pages shr 2).toInt() and 0x7ffff) shl 5) or register
    }

    private fun assertOnlyReportedBytesChanged(source: PeImage, output: PeImage, edits: List<PlannedEdit>) {
        val allowed = edits.flatMap { edit ->
            val at = requireNotNull(source.rvaToOffset(edit.rva, edit.replacement.size))
            (at until at + edit.replacement.size).toList()
        }.toSet()
        assertEquals(source.bytes.size, output.bytes.size)
        assertArrayEquals(source.bytes.copyOfRange(0, source.headersSize),
            output.bytes.copyOfRange(0, output.headersSize))
        assertTrue("A non-policy byte changed (including other RSA/AVB/logger/data)",
            source.bytes.indices.all { source.bytes[it] == output.bytes[it] || it in allowed })
    }

    /** Symbolic ABI contract for this tiny instruction set, not hardware PAC/BTI validation. */
    private fun assertBalancedEarlyReturn(image: PeImage, entry: Long) {
        var signedWith: Int? = null
        var zeroResult = false
        var pc = entry
        repeat(9) {
            when (val word = requireNotNull(image.word(pc))) {
                BTI_C, BTI_J, BTI_JC -> Unit
                PAC_A, PAC_B -> {
                    assertNull("Nested signing before early return", signedWith)
                    signedWith = word
                }
                AUT_A, AUT_B -> {
                    assertEquals("Authentication must match the retained signing prefix",
                        if (word == AUT_A) PAC_A else PAC_B, signedWith)
                    signedWith = null
                }
                MOV_ZERO -> zeroResult = true
                RET -> {
                    assertNull("Returning a signed LR without authentication", signedWith)
                    assertTrue("Return value was not established as zero", zeroResult)
                    return
                }
                else -> fail("Unexpected instruction before return at 0x${pc.toString(16)}: ${word.toUInt().toString(16)}")
            }
            pc += 4
        }
        fail("No bounded early return")
    }

    @Test fun fourUniqueOriginalLayoutsKeepEveryCallerAndEveryNonPolicyByte() {
        assertEquals(4, originals().map { it.sha256 }.toSet().size)
        for (fixture in originals()) {
            val source = PeImage.parse(bytes(fixture.path, fixture.sha256))
            val snapshot = source.bytes.copyOf()
            val core = SemanticPatcher.analyze(source)
            val entry = requireNotNull(core.stateVerifierRva)
            val beforeCallers = callers(source, entry)
            assertTrue(fixture.name, beforeCallers.size >= 2)
            val plan = PssVerificationPatcher.analyze(source, entry)
            assertEquals(fixture.name, 1, plan.edits.size)
            val edit = plan.edits.single()
            assertEquals(POLICY_NAME, edit.name)
            assertTrue(edit.rva in entry..entry + 16)
            val output = apply(source, plan.edits)
            assertOnlyReportedBytesChanged(source, output, plan.edits)
            assertEquals(beforeCallers, callers(output, entry))
            beforeCallers.forEach { assertEquals(source.word(it), output.word(it)) }
            assertBalancedEarlyReturn(output, entry)
            // Body references survive, allowing a strict repeat import. This is
            // separate from the symbolic entry contract and from full-engine tests.
            val rediscovered = SemanticPatcher.analyze(output)
            assertEquals(entry, rediscovered.stateVerifierRva)
            val second = PssVerificationPatcher.analyze(output, rediscovered.stateVerifierRva)
            assertEquals(edit.rva, second.edits.single().rva)
            assertArrayEquals(edit.replacement, second.edits.single().replacement)
            assertArrayEquals(output.bytes, apply(output, second.edits).bytes)
            assertArrayEquals(snapshot, source.bytes)
        }
    }

    @Test fun realEngineReportsPolicyInBothModesAndRetainsRepeatImportIdentity() {
        for (fixture in originals().take(2)) for (mode in PatchMode.entries) {
            val input = bytes(fixture.path, fixture.sha256)
            val original = input.copyOf()
            val once = PatchEngine.analyzeAndPatch(input, mode)
            val policy = once.patches.single { it.name == POLICY_NAME }
            assertFalse(policy.alreadyApplied)
            val output = PeImage.parse(once.output)
            assertBalancedEarlyReturn(output, requireNotNull(SemanticPatcher.analyze(output).stateVerifierRva))
            val twice = PatchEngine.analyzeAndPatch(once.output, mode)
            assertTrue(twice.patches.single { it.name == POLICY_NAME }.alreadyApplied)
            assertTrue(twice.alreadyPatched)
            assertArrayEquals(once.output, twice.output)
            assertArrayEquals(original, input)
        }
    }

    @Test fun completed288ImagesHaveAnIsolatedPolicySpanAndStableRealApiReimport() {
        for ((path, expected) in listOf(
            "analysis/bkq150-newabl-bootloop-20261102/matrix-audit/offline-builtin-fake-lock-hnubl-2.8.8.img" to
                "f67714c854a198ce71d11acd72208dfd739fb7bf25e1e141e45b38155c8177c4",
            "analysis/bkq150-newabl-bootloop-20261102/fallback-audit/offline-233110-fake-lock-hnubl-2.8.8.img" to
                "9fb899b632266ca580c42316f6244a8b4d652432e542154276fca659550b666b",
        )) {
            val input = bytes(path, expected)
            val original = input.copyOf()
            // Isolate the new policy to prove its byte scope. The actual product
            // API legitimately removes/rebuilds its owned KBL/recovery mappings,
            // so a whole API output is not incorrectly claimed to be one span.
            val source = PeImage.parse(input)
            val entry = requireNotNull(SemanticPatcher.analyze(source).stateVerifierRva)
            val isolated = PssVerificationPatcher.analyze(source, entry)
            assertEquals(1, isolated.edits.size)
            assertOnlyReportedBytesChanged(source, apply(source, isolated.edits), isolated.edits)
            val originalLabel = AblEfisp.scanText(input).entries.single { it.id == "fastboot-unlocked" }.text
            val once = AblEfisp.patchWithKbl(input, PatchMode.FREEDOM_FAKE_LOCK)
            assertFalse(once.patches.single { it.name == POLICY_NAME }.alreadyApplied)
            assertBalancedEarlyReturn(PeImage.parse(once.output), entry)
            assertEquals(originalLabel,
                AblEfisp.scanText(once.output).entries.single { it.id == "fastboot-unlocked" }.text)
            assertTrue(KblPatcher.removeAdapterDetails(FastbootRecoveryPatcher.remove(once.output)).hadAdapter)
            val twice = AblEfisp.patchWithKbl(once.output, PatchMode.FREEDOM_FAKE_LOCK)
            assertTrue(twice.alreadyPatched)
            assertTrue(twice.patches.single { it.name == POLICY_NAME }.alreadyApplied)
            assertArrayEquals(once.output, twice.output)
            assertArrayEquals(original, input)
        }
    }

    @Test fun completed137UsesTheOwnedAdapterRebuildAndHasAStablePolicyOnReimport() {
        val input = bytes("analysis/efisp-fastboot-recovery-137-20261031/hardware/efisp-1.3.7-current-abl-a-ram.img")
        val original = input.copyOf()
        val once = AblEfisp.patchWithKbl(input, PatchMode.FREEDOM_FAKE_LOCK)
        val after = PeImage.parse(once.output)
        assertTrue(after.isCode(after.entryRva))
        assertTrue(KblPatcher.removeAdapterDetails(FastbootRecoveryPatcher.remove(once.output)).hadAdapter)
        assertEquals(1, once.patches.count { it.name == POLICY_NAME })
        assertBalancedEarlyReturn(after, requireNotNull(SemanticPatcher.analyze(after).stateVerifierRva))
        val twice = AblEfisp.patchWithKbl(once.output, PatchMode.FREEDOM_FAKE_LOCK)
        assertTrue(twice.patches.single { it.name == POLICY_NAME }.alreadyApplied)
        assertArrayEquals(once.output, twice.output)
        assertArrayEquals(original, input)
    }

    /** Synthetic API-anchor fixtures supplement real SemanticPatcher proofs. */
    private data class Synthetic(val image: PeImage, val entry: Long, val stringRvas: List<Long>)

    private fun synthetic(prefix: List<Int> = listOf(PAC_A), pss: Boolean = true): Synthetic {
        val data = ByteArray(0x1400)
        fun half(at: Int, n: Int) { data[at] = n.toByte(); data[at + 1] = (n ushr 8).toByte() }
        data[0] = 77; data[1] = 90; data.putWord(0x3c, 0x80); data.putWord(0x80, 0x4550)
        half(0x84, 0xaa64); half(0x86, 2); half(0x94, 0xf0); half(0x98, 0x20b)
        data.putWord(0xa8, 0x1000); data.putWord(0xb8, 0x1000); data.putWord(0xbc, 0x200)
        data.putWord(0xd0, 0x4000); data.putWord(0xd4, 0x400); data.putWord(0x104, 16)
        fun section(at: Int, name: String, rva: Int, raw: Int, flags: Int) {
            name.toByteArray().copyInto(data, at)
            data.putWord(at + 8, 0x800); data.putWord(at + 12, rva)
            data.putWord(at + 16, 0x800); data.putWord(at + 20, raw); data.putWord(at + 36, flags)
        }
        section(0x188, ".text", 0x1000, 0x400, 0x60000020)
        section(0x1b0, ".rdata", 0x3000, 0xc00, 0x40000040)
        fun raw(rva: Long): Int = if (rva < 0x3000) (rva - 0x1000 + 0x400).toInt() else (rva - 0x3000 + 0xc00).toInt()
        fun put(pc: Long, word: Int) = data.putWord(raw(pc), word)
        for (pc in 0x1000L until 0x1800L step 4) put(pc, NOP)
        val entry = 0x1200L
        put(0x1000, RET)
        for (pc in listOf(0x1040L, 0x1050L)) put(pc, A64.branch(pc, entry) or 0x80000000.toInt())
        prefix.forEachIndexed { i, word -> put(entry + i * 4, word) }
        val messages = if (pss) listOf("sec_rsa_pss_verify", "sec_data_pss: pss signature verify failed",
            "sec_data_pss: pss signature verify success", "sec_data_pss: input parameter invalid")
        else listOf("rsa_pkcs1_verify", "rsa signature failed", "rsa signature success", "rsa parameter invalid")
        val stringRvas = messages.indices.map { 0x3040L + it * 0x80 }
        messages.forEachIndexed { index, message ->
            (message + "\u0000").toByteArray().copyInto(data, raw(stringRvas[index]))
            val pc = entry + 0x40 + index * 16
            put(pc, pageAddress(pc, stringRvas[index], 8))
            put(pc + 4, 0x91000000.toInt() or ((stringRvas[index].toInt() and 0xfff) shl 10) or (8 shl 5) or 8)
        }
        put(entry + 0x100, RET)
        put(0x1400, PAC_A); put(0x1404, RET)
        return Synthetic(PeImage.parse(data), entry, stringRvas)
    }

    @Test fun btiAndBothPacKeysKeepALeafBalancedReturnBeforeAnyFrameOrMemoryAccess() {
        val prefixes = listOf(emptyList(), listOf(PAC_A), listOf(PAC_B), listOf(BTI_C),
            listOf(BTI_C, PAC_A), listOf(BTI_C, PAC_B), listOf(BTI_J, PAC_A), listOf(BTI_JC, PAC_B))
        for (prefix in prefixes) {
            val fixture = synthetic(prefix)
            val before = fixture.image.bytes.copyOf()
            val edit = PssVerificationPatcher.analyze(fixture.image, fixture.entry).edits.single()
            val output = apply(fixture.image, listOf(edit))
            assertEquals(fixture.entry + prefix.size * 4, edit.rva)
            prefix.forEachIndexed { index, word -> assertEquals(word, output.word(fixture.entry + index * 4)) }
            assertBalancedEarlyReturn(output, fixture.entry)
            assertOnlyReportedBytesChanged(fixture.image, output, listOf(edit))
            val again = PssVerificationPatcher.analyze(output, fixture.entry).edits.single()
            assertEquals(edit.rva, again.rva)
            assertArrayEquals(edit.replacement, again.replacement)
            assertArrayEquals(before, fixture.image.bytes)
        }
    }

    @Test fun missingAnchorOrMappedCodeAndInsufficientCallersStopWithoutMutatingInput() {
        val fixture = synthetic()
        val original = fixture.image.bytes.copyOf()
        assertThrows(PatchException::class.java) { PssVerificationPatcher.analyze(fixture.image, null) }
        assertThrows(PatchException::class.java) { PssVerificationPatcher.analyze(fixture.image, 0x3040) }
        assertThrows(PatchException::class.java) { PssVerificationPatcher.analyze(fixture.image, fixture.entry + 2) }
        val oneCaller = changed(fixture.image, 0x1050L to NOP)
        val snapshot = oneCaller.bytes.copyOf()
        assertThrows(PatchException::class.java) { PssVerificationPatcher.analyze(oneCaller, fixture.entry) }
        assertArrayEquals(snapshot, oneCaller.bytes)
        assertArrayEquals(original, fixture.image.bytes)
    }

    @Test fun nonPssAlgorithmAndEvidenceBeyondTheNextFunctionArePreserved() {
        val otherAlgorithm = synthetic(pss = false)
        val original = otherAlgorithm.image.bytes.copyOf()
        assertTrue(PssVerificationPatcher.analyze(otherAlgorithm.image, otherAlgorithm.entry).edits.isEmpty())
        assertArrayEquals(original, otherAlgorithm.image.bytes)
        val fixture = synthetic()
        // All PSS references now belong beyond an intervening function entry.
        val bounded = changed(fixture.image, fixture.entry + 0x20 to PAC_A)
        val snapshot = bounded.bytes.copyOf()
        val noChange = try { PssVerificationPatcher.analyze(bounded, fixture.entry).edits.isEmpty() }
            catch (_: PatchException) { true }
        assertTrue("Evidence from the next function must not qualify this entry", noChange)
        assertArrayEquals(snapshot, bounded.bytes)
    }

    @Test fun btiOnlyNeighbourAndUnreachablePssReferencesDoNotQualifyTheSharedEntry() {
        val fixture = synthetic()
        val neighbour = fixture.entry + 0x30
        val candidates = listOf(
            // A returning API followed by an independently called BTI-only
            // function. The latter owns the PSS strings, not the original API.
            changed(fixture.image, fixture.entry + 8 to RET, neighbour to BTI_C,
                0x1060L to (A64.branch(0x1060, neighbour) or 0x80000000.toInt())),
            // No new function marker: references are still dead after this RET.
            changed(fixture.image, fixture.entry + 8 to RET),
            // The cold-looking text block has no feasible incoming edge.
            changed(fixture.image, fixture.entry + 4 to A64.branch(fixture.entry + 4, fixture.entry + 0x100)),
        )
        for (candidate in candidates) {
            val snapshot = candidate.bytes.copyOf()
            val noChange = try { PssVerificationPatcher.analyze(candidate, fixture.entry).edits.isEmpty() }
                catch (_: PatchException) { true }
            assertTrue("A different or unreachable function supplied the PSS evidence", noChange)
            assertArrayEquals(snapshot, candidate.bytes)
        }
    }

    @Test fun eachMissingPssInterfaceEvidenceRejectsInsteadOfProducingAPartialPolicy() {
        val fixture = synthetic()
        for (rva in fixture.stringRvas) {
            val changed = fixture.image.bytes.copyOf()
            val offset = requireNotNull(fixture.image.rvaToOffset(rva))
            var end = offset
            while (changed[end] != 0.toByte()) end++
            changed.fill('x'.code.toByte(), offset, end)
            val candidate = PeImage.parse(changed)
            val snapshot = candidate.bytes.copyOf()
            assertThrows(PatchException::class.java) { PssVerificationPatcher.analyze(candidate, fixture.entry) }
            assertArrayEquals(snapshot, candidate.bytes)
        }
    }

    private companion object {
        const val POLICY_NAME = "共用 RSA-PSS 验证入口固定成功"
        const val PAC_A = 0xd503233f.toInt()
        const val PAC_B = 0xd503237f.toInt()
        const val AUT_A = 0xd50323bf.toInt()
        const val AUT_B = 0xd50323ff.toInt()
        const val BTI_C = 0xd503245f.toInt()
        const val BTI_J = 0xd503249f.toInt()
        const val BTI_JC = 0xd50324df.toInt()
        const val MOV_ZERO = 0x2a1f03e0
        const val RET = 0xd65f03c0.toInt()
        const val NOP = 0xd503201f.toInt()
    }
}
