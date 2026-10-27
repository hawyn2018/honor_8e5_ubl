package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CheckRootPolicyPatcherTest {
    private fun sample(path: String = "analysis/ablnew-compat/ablnew-inner.pe"): PeImage {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "ablnew.img").isFile }
        assumeTrue("Local read-only firmware fixture", root != null)
        val file = File(root, path)
        assumeTrue("Fixture $path", file.isFile)
        return PeImage.parse(file.readBytes())
    }

    private fun changed(source: PeImage, vararg words: Pair<Long, Int>): PeImage {
        val data = source.bytes.copyOf()
        for ((pc, word) in words) data.putWord(requireNotNull(source.rvaToOffset(pc, 4)), word)
        return PeImage.parse(data)
    }

    @Test fun oldNewAndOtaUseTheSameSourceAndSinkRelations() {
        for ((file, store) in listOf(
            "analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe" to 0x11178L,
            "analysis/ablnew-compat/ablnew-inner.pe" to 0x1086cL,
            "analysis/locked-boot-feasibility/fastboot-ota-abl.pe" to 0x1086cL,
        )) {
            val image = sample(file); val before = image.bytes.copyOf()
            val plan = CheckRootPolicyPatcher.analyze(image)
            assertEquals(file, 1, plan.edits.size)
            assertEquals(store, plan.edits.single().rva)
            val original = requireNotNull(A64.stpW(requireNotNull(image.word(store))))
            val after = requireNotNull(A64.stpW(plan.edits.single().replacement.word(0)))
            assertEquals(31, after.rt); assertEquals(31, after.rt2)
            assertEquals(original.rn, after.rn); assertEquals(original.offset, after.offset)
            assertArrayEquals(before, image.bytes)
        }
    }

    @Test fun fakeblAndPatchedInputsAreIdempotentWithoutAnotherSourceWrite() {
        for (file in listOf("analysis/fakebl-compat-universal/outputs/new.fake.pe",
            "analysis/fakebl-repair-20261026/outputs/new.fake.pe")) {
            val image = sample(file)
            val edit = CheckRootPolicyPatcher.analyze(image).edits.single()
            val patched = changed(image, edit.rva to edit.replacement.word(0))
            val again = CheckRootPolicyPatcher.analyze(patched).edits.single()
            assertEquals(edit.rva, again.rva)
            assertArrayEquals(edit.replacement, again.replacement)
            assertEquals(patched.word(again.rva), again.replacement.word(0))
        }
    }

    @Test fun reorderedLoadsAndRenamedBooleanOrAddressRegistersStillMatch() {
        val source = sample()
        val scheduled = changed(source, 0x10808L to requireNotNull(source.word(0x1080c)),
            0x1080cL to requireNotNull(source.word(0x10808)))
        assertEquals(0x1086cL, CheckRootPolicyPatcher.analyze(scheduled).edits.single().rva)
        val renamed = changed(source,
            0x10818L to ((requireNotNull(source.word(0x10818)) and -32) or 10),
            0x1085cL to ((requireNotNull(source.word(0x1085c)) and -32) or 10),
            0x1086cL to ((requireNotNull(source.word(0x1086c)) and -32) or 10))
        assertEquals(0x1086cL, CheckRootPolicyPatcher.analyze(renamed).edits.single().rva)
        val address = changed(source,
            0x10860L to ((requireNotNull(source.word(0x10860)) and -32) or 11),
            0x10864L to ((requireNotNull(source.word(0x10864)) and 0xfffffc00.toInt()) or (11 shl 5) or 11),
            0x1086cL to ((requireNotNull(source.word(0x1086c)) and 0xfffffc1f.toInt()) or (11 shl 5)))
        assertEquals(0x1086cL, CheckRootPolicyPatcher.analyze(address).edits.single().rva)
    }

    @Test fun wrongRecordLengthFieldOrPredicateStopsInsteadOfGuessing() {
        val source = sample()
        for (candidate in listOf(
            changed(source, 0x107f4L to 0x52800840), // OEMINFO 0x42
            changed(source, 0x107f8L to 0x52800202), // sixteen bytes
            changed(source, 0x10808L to (requireNotNull(source.word(0x10808)) + (1 shl 10))), // field+8
            changed(source, 0x10814L to (requireNotNull(source.word(0x10814)) + (1 shl 16))), // CCMP #2
            changed(source, 0x1085cL to 0x52800028), // read-error value no longer zero
        )) assertThrows(PatchException::class.java) { CheckRootPolicyPatcher.analyze(candidate) }
    }

    @Test fun anAdditionalGlobalWriterIsRejected() {
        val source = sample()
        val section = source.sections.first { it.executable }
        val at = section.rva + section.rawSize - 0x100
        // Additional local function writes the same root cache independently.
        val candidate = changed(source,
            at to 0xd503233f.toInt(),
            at + 4 to A64.adrpWord(at + 4, 0x1e7000, 14),
            at + 8 to (0x91000000.toInt() or (0x60c shl 10) or (14 shl 5) or 14),
            at + 12 to 0x5280002d,
            at + 16 to (0xb9000000.toInt() or (14 shl 5) or 13),
            at + 20 to 0xd65f03c0.toInt(),
            at + 24 to 0xd503233f.toInt(),
            at + 28 to 0xd65f03c0.toInt())
        assertThrows(PatchException::class.java) { CheckRootPolicyPatcher.analyze(candidate) }
    }

    @Test fun changingTheIndependentByteConsumerBreaksTheProof() {
        val source = sample()
        // The HTEE byte channel now reads check_sign_stat, not root status.
        val candidate = changed(source, 0x544e8L to (requireNotNull(source.word(0x544e8)) + (1 shl 10)))
        assertThrows(PatchException::class.java) { CheckRootPolicyPatcher.analyze(candidate) }
    }

    private fun indexedWriter(source: PeImage, index: Int?, store: Int = 0xb82f69cd.toInt(), baseDelta: Int = 0): PeImage {
        val section = source.sections.first { it.executable }
        val at = section.rva + section.rawSize - 0x100
        val data = source.bytes.copyOf()
        val base = 0x1e760cL + baseDelta
        val words = listOf(0xd503233f.toInt(), A64.adrpWord(at + 4, base, 14),
            0x91000000.toInt() or (((base and 0xfff).toInt()) shl 10) or (14 shl 5) or 14,
            0x5280002d, index ?: 0xd503201f.toInt(), store, 0xd65f03c0.toInt(),
            0xd503233f.toInt(), 0xd65f03c0.toInt())
        words.forEachIndexed { i, word -> data.putWord(requireNotNull(source.rvaToOffset(at + i * 4, 4)), word) }
        return PeImage.parse(data)
    }

    @Test fun indexedScalarAndVectorWritersCannotHideFromTheUniqueSinkProof() {
        val source = sample()
        for (store in listOf(0xb82f69cd.toInt(), 0xf82f79cd.toInt(), 0x3caf79cd)) {
            // MOV X15,XZR; STR W/X/Q,[X14,X15{,LSL #size}].
            val zeroIndex = indexedWriter(source, 0xaa1f03ef.toInt(), store)
            assertThrows(PatchException::class.java) { CheckRootPolicyPatcher.analyze(zeroIndex) }
            val unknownIndex = indexedWriter(source, null, store)
            assertThrows(PatchException::class.java) { CheckRootPolicyPatcher.analyze(unknownIndex) }
        }
        // MOVN X15,#0 creates -1: the unscaled write straddles root-1..root+2.
        val negative = indexedWriter(source, 0x9280000f.toInt())
        assertThrows(PatchException::class.java) { CheckRootPolicyPatcher.analyze(negative) }
        // A different ADRP page plus an exact -4096 index resolves to the cache.
        val crossPage = indexedWriter(source, 0x9281ffef.toInt(), baseDelta = 0x1000)
        assertThrows(PatchException::class.java) { CheckRootPolicyPatcher.analyze(crossPage) }
    }

    @Test fun provenDisjointIndexedWritesDoNotCauseAnUnnecessaryRejection() {
        val source = sample()
        // Constant X15=8 writes after the four-byte root cache.
        val constant = indexedWriter(source, 0xd280010f.toInt())
        assertEquals(0x1086cL, CheckRootPolicyPatcher.analyze(constant).edits.single().rva)
        // An unknown UXTW offset is nonnegative; base=root+8 cannot reach back.
        val positiveRange = indexedWriter(source, null, 0xb82f49cd.toInt(), 8)
        assertEquals(0x1086cL, CheckRootPolicyPatcher.analyze(positiveRange).edits.single().rva)
        // The same UXTW range at the root itself may overwrite it and is rejected.
        val overlappingRange = indexedWriter(source, null, 0xb82f49cd.toInt())
        assertThrows(PatchException::class.java) { CheckRootPolicyPatcher.analyze(overlappingRange) }
        val signedRange = indexedWriter(source, null, 0xb82fc9cd.toInt(), 8)
        assertThrows(PatchException::class.java) { CheckRootPolicyPatcher.analyze(signedRange) }
        // UBFIZ X15,X27,#4,#32 is nonnegative regardless of the entry X27 value.
        val masked = indexedWriter(source, 0xd37c7f6f.toInt(), baseDelta = 8)
        assertEquals(0x1086cL, CheckRootPolicyPatcher.analyze(masked).edits.single().rva)
    }

    @Test fun aFullWidthRootPointerSpillDoesNotHideAnotherWriter() {
        val source = sample()
        val section = source.sections.first { it.executable }
        val at = section.rva + section.rawSize - 0x100
        val words = listOf(0xd503233f.toInt(), 0xd10043ff.toInt(),
            A64.adrpWord(at + 8, 0x1e7000, 14),
            0x91000000.toInt() or (0x60c shl 10) or (14 shl 5) or 14,
            0xf90003ee.toInt(), // STR X14,[SP]
            0xf94003f0.toInt(), // LDR X16,[SP]
            0x5280002d, 0xaa1f03ef.toInt(),
            0xb82f6a0d.toInt(), // STR W13,[X16,X15]
            0x910043ff.toInt(), 0xd65f03c0.toInt(),
            0xd503233f.toInt(), 0xd65f03c0.toInt())
        val data = source.bytes.copyOf()
        words.forEachIndexed { i, word -> data.putWord(requireNotNull(source.rvaToOffset(at + i * 4, 4)), word) }
        assertThrows(PatchException::class.java) { CheckRootPolicyPatcher.analyze(PeImage.parse(data)) }
    }

    @Test fun byteConsumerCanLoadTheSameLowByteAtDifferentWidths() {
        val source = sample()
        for ((opcode, scale) in listOf(0x39400000 to 1, 0x79400000 to 2,
            0xb9400000.toInt() to 4)) {
            // The actual field is not eight-byte aligned; byte/halfword/word
            // unsigned loads all address it exactly and feed the same STRB.
            val load = opcode or ((0x60c / scale) shl 10) or (22 shl 5) or 9
            assertEquals(0x1086cL, CheckRootPolicyPatcher.analyze(changed(source, 0x544e8L to load)).edits.single().rva)
        }
        val unscaledX = 0xf8400000.toInt() or (22 shl 5) or 9
        // Use an exact base alias plus LDUR X (the field itself is only 4-aligned).
        val nearbyBase = changed(source, 0x544e4L to
            (0x91000000.toInt() or (0x60c shl 10) or (22 shl 5) or 17),
            0x544e8L to ((unscaledX and 0xfffffc1f.toInt()) or (17 shl 5)))
        assertEquals(0x1086cL, CheckRootPolicyPatcher.analyze(nearbyBase).edits.single().rva)
    }

    private fun A64.adrpWord(pc: Long, target: Long, register: Int): Int {
        val pages = ((target and -4096L) - (pc and -4096L)) shr 12
        return (0x90000000L or ((pages and 3) shl 29) or (((pages shr 2) and 0x7ffff) shl 5) or register.toLong()).toInt()
    }
}
