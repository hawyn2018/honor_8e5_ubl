package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SuperUnlockPatcherTest {
    private val fixtures = linkedMapOf(
        "old" to "abl.elf", "new" to "ablnew.img",
        "winrt2" to "analysis/winrt2-abl-audit-20261028/input-winrt2_abl_b.img",
        "aph" to "analysis/aph-arb-20261029/abl.elf",
    )
    private fun root(): File = requireNotNull(generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .take(8).firstOrNull { File(it, "analysis/flashing-unlock-20261029/baseline/results.tsv").isFile }
        .also { assumeTrue("Real-file fixtures required", it != null) })
    private fun image(name: String) = PeImage.parse(AblEfisp.extract(File(root(), fixtures.getValue(name)).readBytes()).output)
    private fun plan(image: PeImage) = requireNotNull(SuperUnlockPatcher.analyze(image, FlashingUnlockPatcher.analyze(image)))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun discoversAndExportsBothModesWithNativeLayoutAndNoOldEditChanges() {
        val directory = File(root(), "analysis/canoe-unlock-integration-20261029/audit/generated").also { it.mkdirs() }
        for ((name, path) in fixtures) for (mode in PatchMode.entries) {
            val input = File(root(), path).readBytes()
            val result = AblEfisp.patch(input, mode)
            val added = result.patches.filter { it.name.startsWith("oem superunlock：") }
            assertEquals(2, added.size)
            assertEquals(listOf(4, SuperUnlockCode.TOTAL_BYTES), added.map { it.after.split(' ').size })
            val outputImage = PeImage.parse(result.output); val p = plan(outputImage)
            val allocation = requireNotNull(p.existingAllocationRva)
            assertEquals(added.last().rva, allocation)
            assertEquals(p.normalFieldOffset + 1, p.criticalFieldOffset)
            assertTrue(p.tableEndRva > p.tableStartRva)
            assertEquals(0, p.frameBytes and 15)
            assertTrue(p.frameBytes >= p.deviceInfoBytes)
            assertTrue(outputImage.bytes[requireNotNull(outputImage.rvaToOffset(allocation + SuperUnlockCode.TOTAL_BYTES - 1))] != 0.toByte())
            val modeName = if (mode == PatchMode.FREEDOM) "freedom" else "fake"
            val target = File(directory, "$name.$modeName.pe").also { it.writeBytes(result.output) }
            val metadata = SuperUnlockPatcher.metadata(p, allocation).toMutableMap()
            val numbers = metadata.entries.joinToString(",\n") { "  \"${it.key}\": ${it.value}" }
            File(directory, "$name.$modeName.json").writeText("{\n$numbers,\n  \"image\": \"${target.absolutePath.replace("\\", "\\\\")}\",\n  \"sha256\": \"${result.outputSha256}\"\n}\n")
            val repeated = AblEfisp.patch(result.output, mode)
            assertTrue(repeated.alreadyPatched)
            assertArrayEquals(result.output, repeated.output)
            val original = image(name)
            val prior = requireNotNull(FlashingUnlockPatcher.analyze(original))
            val oldEdits = FlashingUnlockPatcher.edits(prior,
                result.patches.single { it.name == "flashing unlock：保留其他操作的前端条件" }.rva,
                result.patches.single { it.name == "flashing unlock：原后端提交且不请求清数据" }.rva)
            for (edit in oldEdits) assertArrayEquals(edit.replacement,
                outputImage.bytes.copyOfRange(requireNotNull(outputImage.rvaToOffset(edit.rva)), requireNotNull(outputImage.rvaToOffset(edit.rva)) + edit.replacement.size))
        }
    }

    @Test fun reorderedTableAndRenamedIteratorRetainSemanticDiscovery() {
        val source = image("new"); val p = plan(source)
        val bytes = source.bytes.copyOf()
        val first = requireNotNull(source.rvaToOffset(p.tableStartRva, 16))
        val second = first + 16
        val a = bytes.copyOfRange(first, first + 16)
        bytes.copyOfRange(second, second + 16).copyInto(bytes, first); a.copyInto(bytes, second)
        val reordered = plan(PeImage.parse(bytes))
        assertEquals(p.registrationGateRva, reordered.registrationGateRva)
        // Rename only the loop iterator's explicit definitions/uses. This is a
        // matcher metamorphic fixture, not a runnable whole-firmware mutation.
        val iterator = p.loopIndexRegister; val renamed = 28
        val cmpAt = requireNotNull(source.rvaToOffset(p.loopCompareRva, 4))
        bytes.putWord(cmpAt, (bytes.word(cmpAt) and (31 shl 5).inv()) or (renamed shl 5))
        for (pc in p.registrationContinueRva until p.registrationGateRva + 32 step 4) {
            val off = source.rvaToOffset(pc, 4) ?: continue; val word = bytes.word(off)
            if (word and 0xffe0fc00.toInt() == 0x8b000000.toInt()) {
                val rm = (word ushr 16) and 31; val rn = (word ushr 5) and 31
                bytes.putWord(off, if (rm == iterator) (word and (31 shl 16).inv()) or (renamed shl 16)
                    else if (rn == iterator) (word and (31 shl 5).inv()) or (renamed shl 5) else word)
            }
            A64.add(word)?.takeIf { it.rd == iterator && it.rn == iterator && it.immediate == 16L }?.let {
                bytes.putWord(off, (word and (31 or (31 shl 5)).inv()) or renamed or (renamed shl 5))
            }
        }
        assertEquals(renamed, plan(PeImage.parse(bytes)).loopIndexRegister)
    }

    @Test fun rawSizeAndNativeFlagOffsetsAreNotIdentityTables() {
        val source = image("new"); val initial = requireNotNull(FlashingUnlockPatcher.analyze(source))
        val variations = listOf(
            Triple(0xd18, 13, 14), Triple(0xd18, 15, 16),
            Triple(0xd18, 15, 18), Triple(4088, 13, 14), Triple(4095, 13, 14),
        )
        for ((rawBytes, normal, critical) in variations) {
        val copy = source.bytes.copyOf()
        // The native wrapper has one W3 byte-count argument to its protocol
        // method. Derive its instruction, then change D10 -> D18 (8B aligned).
        var sizeChanges = 0
        for (pc in initial.writeBackendRva until initial.writeBackendRva + 192 step 4) {
            val off = source.rvaToOffset(pc, 4) ?: continue
            if (A64.movWImmediate(copy.word(off)) == (3 to initial.deviceInfoBytes)) {
                copy.putWord(off, (copy.word(off) and (0xffff shl 5).inv()) or (rawBytes shl 5)); sizeChanges++
            }
        }
        assertEquals(1, sizeChanges)
        var flagsChanged = 0
        for ((pc, word) in source.instructions()) {
            if (word and 0xffc003ff.toInt() != 0x39000020 || source.word(pc + 4) != 0x52800020) continue
            val oldOffset = (word ushr 10) and 4095
            val newOffset = when (oldOffset) { initial.normalFieldOffset -> normal; initial.criticalFieldOffset -> critical; else -> continue }
            copy.putWord(requireNotNull(source.rvaToOffset(pc)), (word and (4095 shl 10).inv()) or (newOffset shl 10)); flagsChanged++
        }
        assertEquals(2, flagsChanged)
        val p = plan(PeImage.parse(copy))
        assertEquals(rawBytes, p.deviceInfoBytes)
        assertEquals(normal, p.normalFieldOffset); assertEquals(critical, p.criticalFieldOffset)
        assertEquals((p.deviceInfoBytes + 15) and -16, p.frameBytes)
        // Generated ABI-variant output is supplied to the independent ARM64
        // oracle; the actual wrapper still passes its raw D18 size.
        val result = AblEfisp.patch(copy, PatchMode.FREEDOM_FAKE_LOCK)
        val newImage = PeImage.parse(result.output); val resultPlan = plan(newImage)
        val at = requireNotNull(resultPlan.existingAllocationRva)
        val folder = File(root(), "analysis/canoe-unlock-integration-20261029/audit/generated")
        val fixtureName = "abi-${rawBytes.toString(16)}-$normal-$critical.fake"
        folder.mkdirs(); val target = File(folder, "$fixtureName.pe").also { it.writeBytes(result.output) }
        val text = SuperUnlockPatcher.metadata(resultPlan, at).entries.joinToString(",\n") { "  \"${it.key}\": ${it.value}" }
        File(folder, "$fixtureName.json").writeText("{\n$text,\n  \"image\": \"${target.absolutePath.replace("\\", "\\\\")}\",\n  \"sha256\": \"${result.outputSha256}\"\n}\n")
        assertArrayEquals(result.output, AblEfisp.patch(result.output, PatchMode.FREEDOM_FAKE_LOCK).output)
        }
    }

    @Test fun equivalentReverseConditionUsesTheSameRegistrationBackend() {
        val source = image("new"); val original = plan(source); val gate = original.registrationGateRva
        val copy = source.bytes.copyOf()
        val moved = (0..4).map { n -> (gate + 4 + n * 4) to requireNotNull(source.word(gate + 4 + n * 4)) }
        copy.putWord(requireNotNull(source.rvaToOffset(gate)), 0x54000041) // B.NE body
        copy.putWord(requireNotNull(source.rvaToOffset(gate + 4)), A64.branch(gate + 4, gate + 28))
        for ((pc, word) in moved) {
            val newPc = pc + 4
            val adjusted = A64.blTarget(pc, word)?.let { A64.branch(newPc, it) or 0x80000000.toInt() }
                ?: A64.bTarget(pc, word)?.let { A64.branch(newPc, it) } ?: word
            copy.putWord(requireNotNull(source.rvaToOffset(newPc)), adjusted)
        }
        val candidate = plan(PeImage.parse(copy))
        assertEquals(original.nativeRegisterRva, candidate.nativeRegisterRva)
        assertEquals(gate + 8, candidate.registrationContinueRva)
        assertEquals(gate + 4, candidate.registrationExitRva)
        assertEquals(1, candidate.registrationOriginalWord and 15)
    }

    @Test fun alteredExistingModuleIsRejectedRatherThanReapplied() {
        val result = AblEfisp.patch(image("new").bytes, PatchMode.FREEDOM_FAKE_LOCK)
        val copy = result.output.copyOf()
        val block = result.patches.single { it.name.startsWith("oem superunlock：BLTools") }
        assertNotEquals(0, copy[block.fileOffset + SuperUnlockCode.TOTAL_BYTES - 1].toInt())
        copy[block.fileOffset + SuperUnlockCode.TOTAL_BYTES - 1] = 0
        val snapshot = copy.copyOf()
        val altered = PeImage.parse(copy)
        val backend = requireNotNull(FlashingUnlockPatcher.analyze(altered))
        assertThrows(PatchException::class.java) { SuperUnlockPatcher.analyze(altered, backend) }
        assertArrayEquals("Direct rejection preserves caller input", snapshot, copy)

        val skipped = AblEfisp.patch(copy, PatchMode.FREEDOM_FAKE_LOCK)
        assertArrayEquals("Default API optional skip preserves caller input", snapshot, copy)
        assertTrue(skipped.notes.joinToString("\n"), skipped.notes.any {
            it.contains("已跳过可选功能「oem superunlock 增强」") && it.contains("保留该项原有代码")
        })
        assertTrue("Skipping a tampered module is not repairing or enabling it",
            skipped.patches.none { it.name.contains("oem superunlock", ignoreCase = true) })
        val after = PeImage.parse(skipped.output)
        for (prior in result.patches.filter { it.name.contains("oem superunlock", ignoreCase = true) }) {
            val size = prior.after.replace(" ", "").length / 2
            val beforeAt = requireNotNull(altered.rvaToOffset(prior.rva, size))
            val afterAt = requireNotNull(after.rvaToOffset(prior.rva, size))
            assertArrayEquals("Supplied optional block/registration remains unchanged, including the damaged byte",
                snapshot.copyOfRange(beforeAt, beforeAt + size), skipped.output.copyOfRange(afterAt, afterAt + size))
        }
        for (name in listOf("OEMINFO 读取结果分支", "rdmode 日志循环分支", "rdmode 验证结果分支",
            "正常启动状态报告 green", "Keymaster 输入 unlocked 字节", "Android 启动属性 device_state=locked")) {
            val patch = skipped.patches.single { it.name == name }
            val expected = patch.after.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val at = requireNotNull(after.rvaToOffset(patch.rva, expected.size))
            assertArrayEquals("Required core/fake-lock policy still applies: $name", expected,
                skipped.output.copyOfRange(at, at + expected.size))
        }
    }

    @Test fun fullRxTailGetsItsOwnMappingWithoutMovingOriginalSections() {
        val inputFile = File(root(), "analysis/factory-restore-20261027/current_efisp.img")
        assumeTrue("Existing finished image with custom text", inputFile.isFile)
        val input = AblEfisp.extract(inputFile.readBytes()).output
        val unchanged = input.copyOf(); val before = PeImage.parse(input)
        for (mode in PatchMode.entries) {
            val result = AblEfisp.patch(input, mode); val after = PeImage.parse(result.output)
            assertTrue(result.output.size > input.size)
            assertEquals(before.entryRva, after.entryRva)
            assertEquals(before.imageBase, after.imageBase)
            assertEquals(before.sections, after.sections.take(before.sections.size))
            assertEquals(before.sections.size + 1, after.sections.size)
            val own = after.sections.last()
            assertEquals(".textsu", own.name)
            assertTrue(own.executable && !own.writable)
            val proof = plan(after); val allocation = requireNotNull(proof.existingAllocationRva)
            assertEquals(own.rva, allocation)
            assertTrue(own.rawSize >= SuperUnlockCode.TOTAL_BYTES)
            val mapping = result.patches.filter { it.name.startsWith("oem superunlock：PE映射元数据") }
            assertTrue(mapping.isNotEmpty())
            val allowed = result.patches.flatMap { it.fileOffset until it.fileOffset + it.after.split(' ').size }.toSet()
            assertTrue(input.indices.all { input[it] == result.output[it] || it in allowed })
            assertArrayEquals(unchanged, input)
            val repeated = AblEfisp.patch(result.output, mode)
            assertTrue(repeated.alreadyPatched); assertArrayEquals(result.output, repeated.output)
            val folder = File(root(), "analysis/canoe-unlock-integration-20261029/audit/generated").also { it.mkdirs() }
            val name = "expanded-text.${if (mode == PatchMode.FREEDOM) "freedom" else "fake"}"
            val target = File(folder, "$name.pe").also { it.writeBytes(result.output) }
            val metadata = SuperUnlockPatcher.metadata(proof, allocation).entries.joinToString(",\n") { "  \"${it.key}\": ${it.value}" }
            File(folder, "$name.json").writeText("{\n$metadata,\n  \"image\": \"${target.absolutePath.replace("\\", "\\\\")}\",\n  \"sha256\": \"${result.outputSha256}\"\n}\n")
        }
    }
}
