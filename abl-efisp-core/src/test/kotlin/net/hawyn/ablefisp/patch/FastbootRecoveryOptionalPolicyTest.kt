package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/** Native fixtures exercise selective feature fallback; no device operation. */
class FastbootRecoveryOptionalPolicyTest {
    private fun fixture(relative: String): ByteArray {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, relative).isFile }
        assumeNotNull("Local firmware fixture $relative", root)
        return File(requireNotNull(root), relative).readBytes()
    }

    private fun native(relative: String = "analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe"): ByteArray {
        val source = fixture(relative)
        val base = PatchEngine.analyzeAndPatch(source, PatchMode.FREEDOM)
        return KblPatcher.patch(base.output, BundledKblDriver.load()).bytes
    }

    private fun assertKblRetained(before: PeImage, output: ByteArray) {
        val after = PeImage.parse(output)
        val sections = before.sections.filter { it.name in setOf(".textkbl", ".kblctx", ".kblimg") }
        assertEquals(3, sections.size)
        for (section in sections) {
            assertEquals(section, after.sections.single { it.name == section.name })
            assertArrayEquals(section.name, before.bytes.copyOfRange(section.raw, section.raw + section.rawSize),
                output.copyOfRange(section.raw, section.raw + section.rawSize))
        }
        val config = sections.single { it.name == ".kblimg" }
        val hook = before.bytes.u32(config.raw + 128 + 16)
        assertEquals("KBL explicit command gate", before.word(hook), after.word(hook))
        val restored = FastbootRecoveryPatcher.remove(output)
        val checked = KblPatcher.patch(restored, BundledKblDriver.load())
        assertTrue(checked.unchanged)
        assertArrayEquals(restored, checked.bytes)
    }

    @Test fun oneMarkNativeVariantKeepsTheCompleteKblAndRecoveryPipeline() {
        val bytes = native("analysis/bkqan90-abl-patch-failure-20261102/fakebl138/input-inner.pe")
        val image = PeImage.parse(bytes)
        val slots = FastbootSlotRecoveryFacts.analyze(image)
        assertTrue(slots.automaticCallSites.filter {
            it.kind == FastbootSlotRecoveryFacts.Kind.HANDLE_ACTIVE_UNBOOTABLE
        }.all { it.nativeMutationEvidence.size == 1 })
        val result = FastbootRecoveryPatcher.patch(bytes)
        assertEquals(true, result.metadata["recoveryApplied"])
        assertFalse((result.metadata["autoSlotSites"] as List<*>).isEmpty())
        assertKblRetained(image, result.bytes)
        assertArrayEquals(result.bytes, FastbootRecoveryPatcher.patch(result.bytes).bytes)
        for (window in slots.retryDecrementWindows) {
            assertEquals(image.word(window.mutation.storeRva), PeImage.parse(result.bytes).word(window.mutation.storeRva))
            assertEquals(image.word(window.mutation.commitCallRva), PeImage.parse(result.bytes).word(window.mutation.commitCallRva))
        }
    }

    @Test fun aMissingOptionalSlotProofDoesNotBlockOtherRecoveryOrWrapManualCommands() {
        val original = native()
        val before = PeImage.parse(original)
        val slots = FastbootSlotRecoveryFacts.analyze(before)
        val reset = requireNotNull(slots.evidence["handlerResetCall"])
        val changed = original.copyOf()
        changed.putWord(requireNotNull(before.rvaToOffset(reset, 4)), 0xd63f0000.toInt() or (9 shl 5))
        val image = PeImage.parse(changed)
        assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(image) }
        val result = FastbootRecoveryPatcher.patch(changed)
        assertEquals(true, result.metadata["recoveryApplied"])
        assertTrue(result.notes.any { it.contains("自动切槽恢复") && it.contains("保留") })
        assertTrue((result.metadata["autoSlotSites"] as List<*>).isEmpty())
        assertTrue((result.metadata["defaultSlotSites"] as List<*>).isEmpty())
        assertFalse(result.patches.any { it.name == "保留手动 set_active 作用域" })
        assertTrue(result.patches.any { it.name == "阻断引导后的原生 Fastboot 入口" })
        val table = requireNotNull(before.rvaToOffset(slots.commandTableStartRva))
        val tableEnd = requireNotNull(before.rvaToOffset(slots.commandTableEndRva - 1)) + 1
        assertArrayEquals(changed.copyOfRange(table, tableEnd), result.bytes.copyOfRange(table, tableEnd))
        val rx = PeImage.parse(result.bytes).sections.single { it.name == ".textfr" }
        assertEquals(0L, result.bytes.u64(rx.raw + FastbootRecoveryModule.CONFIG_OFFSET.toInt() + 6 * 8))
        assertKblRetained(image, result.bytes)
        assertArrayEquals(result.bytes, FastbootRecoveryPatcher.patch(result.bytes).bytes)
    }

    @Test fun aMissingBaseAbiKeepsIndependentVolumeKeyEditsAndTheExistingKbl() {
        val original = native()
        val before = PeImage.parse(original)
        val facts = FastbootRecoveryFacts.analyze(before)
        // Remove the required continue flag store in memory. The independent
        // key-reader and the KBL code/driver/gate are left intact.
        val changed = original.copyOf()
        changed.putWord(requireNotNull(before.rvaToOffset(facts.continueFlow.flagStoreRva, 4)), 0xd503201f.toInt())
        val image = PeImage.parse(changed)
        val key = FastbootKeyPolicyPatcher.analyze(image)
        assertFalse(key.edits.isEmpty())
        val base = runCatching { FastbootRecoveryFacts.analyze(image) }.getOrNull()
        assertTrue(base == null || !base.prerequisites.earlyJumpProven ||
            !base.entryPrefix.tailRestoresEntryFrame || !base.frame.returnRestoresFrame)
        val result = FastbootRecoveryPatcher.patch(changed)
        assertEquals(false, result.metadata["recoveryApplied"])
        assertEquals(true, result.metadata["keyOnly"])
        assertEquals(key.edits.map { it.name }, result.patches.map { it.name })
        val owned = hashSetOf<Int>()
        key.edits.forEach { edit ->
            val at = requireNotNull(image.rvaToOffset(edit.rva, edit.replacement.size))
            assertArrayEquals(edit.replacement, result.bytes.copyOfRange(at, at + edit.replacement.size))
            (at until at + edit.replacement.size).forEach(owned::add)
        }
        assertEquals(changed.size, result.bytes.size)
        changed.indices.filterNot { it in owned }.forEach { assertEquals(changed[it], result.bytes[it]) }
        assertKblRetained(image, result.bytes)
        assertArrayEquals(result.bytes, FastbootRecoveryPatcher.patch(result.bytes).bytes)
    }

    @Test fun malformedPeAndBrokenOwnedReceiptAreStillErrors() {
        assertThrows(PatchException::class.java) { FastbootRecoveryPatcher.patch(byteArrayOf(0x4d, 0x5a)) }
        val result = FastbootRecoveryPatcher.patch(native())
        val image = PeImage.parse(result.bytes)
        val section = image.sections.single { it.name == ".textfr" }
        val footer = section.raw + section.rawSize - FastbootRecoverySections.FOOTER_BYTES
        val damaged = result.bytes.copyOf()
        damaged[footer] = (damaged[footer].toInt() xor 1).toByte()
        val error = assertThrows(PatchException::class.java) { FastbootRecoveryPatcher.patch(damaged) }
        assertTrue(error.message.orEmpty().contains("恢复清单"))
    }
}
