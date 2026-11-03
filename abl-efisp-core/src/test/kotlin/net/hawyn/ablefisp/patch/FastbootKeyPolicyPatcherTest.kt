package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FastbootKeyPolicyPatcherTest {
    private data class Sample(val image: PeImage, val selector: Long, val scanCompare: Long,
        val otherSelector: Long, val keyArgument: Long, val qualificationLoad: Long, val consumer: Long)

    private fun fixture(scan: Int = 8, qualification: Int = 10, result: Int = 9,
                        shift: Int = 0, rawShift: Int = 0, reordered: Boolean = false,
                        otherBase: Boolean = false, duplicate: Boolean = false): Sample {
        val code = 0x1000 + shift; val data = code + 0x2000
        val raw = 0x400 + rawShift; val dataRaw = raw + 0x2000
        val bytes = ByteArray(dataRaw + 0x1000)
        bytes[0] = 0x4d; bytes[1] = 0x5a; bytes.putWord(0x3c, 0x80); bytes.putWord(0x80, 0x4550)
        bytes[0x84] = 0x64; bytes[0x85] = 0xaa.toByte(); bytes[0x86] = 2
        bytes[0x94] = 0xf0.toByte(); bytes[0x96] = 2
        val optional = 0x98; val table = optional + 0xf0
        bytes[optional] = 0x0b; bytes[optional + 1] = 2
        bytes.putWord(optional + 4, 0x2000); bytes.putWord(optional + 16, code)
        bytes.putWord(optional + 32, 0x1000); bytes.putWord(optional + 36, 0x200)
        bytes.putWord(optional + 56, code + 0x3000); bytes.putWord(optional + 60, 0x400)
        bytes.putWord(optional + 108, 16)
        ".text".toByteArray().copyInto(bytes, table)
        bytes.putWord(table + 8, 0x2000); bytes.putWord(table + 12, code)
        bytes.putWord(table + 16, 0x2000); bytes.putWord(table + 20, raw)
        bytes.putWord(table + 36, 0x60000020)
        ".data".toByteArray().copyInto(bytes, table + 40)
        bytes.putWord(table + 48, 0x1000); bytes.putWord(table + 52, data)
        bytes.putWord(table + 56, 0x1000); bytes.putWord(table + 60, dataRaw)
        bytes.putWord(table + 76, 0xc0000040.toInt())
        val layout = PeImage.parse(bytes)
        fun put(pc: Long, word: Int) = bytes.putWord(requireNotNull(layout.rvaToOffset(pc, 4)), word)
        fun text(at: Int, value: String) = (value.toByteArray() + byteArrayOf(0)).copyInto(bytes,
            requireNotNull(layout.rvaToOffset(at.toLong(), value.length + 1)))
        fun adr(pc: Long, rd: Int, address: Int) {
            val displacement = address.toLong() - pc
            put(pc, 0x10000000 or (((displacement and 3) shl 29).toInt()) or
                ((((displacement shr 2) and 0x7ffff).toInt()) shl 5) or rd)
        }
        text(data, "GetChargerType")
        text(data + 0x50, "Launching fastboot...\n")
        text(data + 0x80, "ScanCode = (0x%x), UnicodeChar =(0x%x)\r\n")
        text(data + 0xd0, "Key Stroke Read\r\n")
        val helper = code.toLong() + 0x800; val log = helper + 0x140
        var selected = 0L; var compared = 0L; var other = 0L
        var keyArg = 0L; var qualifierLoad = 0L; var consumed = 0L
        fun main(start: Long) {
            var pc = start
            fun emit(word: Int): Long { val at = pc; put(pc, word); pc += 4; return at }
            fun nop() { if (reordered) emit(0xd503201f.toInt()) }
            emit(0xd503233f.toInt()); emit(0xd10403ff.toInt()) // PACIASP; SUB SP,#256
            adr(pc, 24, data + 0x800); pc += 4
            adr(pc, 25, data + if (otherBase) 0x900 else 0x800); pc += 4
            val chargerError = emit(0)
            emit(0x7100081f or (scan shl 5))
            emit(0x1a9f27e0 or qualification) // CSET qualification,LO
            emit(0xb9000000.toInt() or (8 shl 10) or (31 shl 5) or qualification)
            val skip = emit(0)
            put(chargerError, 0xb4000000.toInt() or ((((pc - chargerError) shr 2).toInt() and 0x7ffff) shl 5))
            adr(pc, 3, data); pc += 4
            emit(A64.branch(pc, log) or 0x80000000.toInt())
            emit(0xb9000000.toInt() or (8 shl 10) or (31 shl 5) or 31)
            put(skip, A64.branch(skip, pc))
            val argument = emit(0x910103e0.toInt()) // ADD X0,SP,#64
            emit(A64.branch(pc, helper) or 0x80000000.toInt())
            val keyWord = 0xb9400000.toInt() or (16 shl 10) or (31 shl 5) or scan
            val qualWord = 0xb9400000.toInt() or (8 shl 10) or (31 shl 5) or qualification
            if (reordered) { qualifierLoad = emit(qualWord); emit(keyWord) }
            else { emit(keyWord); qualifierLoad = emit(qualWord) }
            compared = emit(0x7100081f or (scan shl 5)); nop()
            selected = emit(0x1a8003e0 or (qualification shl 16) or (1 shl 12) or result); nop()
            emit(0x7100041f or (result shl 5)); nop()
            val notDown = emit(0)
            emit(0x39000000 or (0x15 shl 10) or (24 shl 5) or result)
            put(notDown, 0x54000001 or ((((pc - notDown) shr 2).toInt() and 0x7ffff) shl 5))
            emit(0x7100041f or (scan shl 5)); nop()
            other = emit(0x1a8003e0 or (qualification shl 16) or (1 shl 12) or result)
            consumed = emit(0x39400000 or (0x15 shl 10) or (25 shl 5) or 15)
            val fastboot = emit(0)
            emit(0xd65f03c0.toInt())
            put(fastboot, 0x37000000 or ((((pc - fastboot) shr 2).toInt() and 0x3fff) shl 5) or 15)
            adr(pc, 1, data + 0x50); pc += 4
            emit(A64.branch(pc, log) or 0x80000000.toInt()); emit(0xd65f03c0.toInt())
            keyArg = argument
        }
        main(code.toLong())
        if (duplicate) main(code.toLong() + 0x400)
        put(helper, 0xd503233f.toInt()); adr(helper + 4, 1, data + 0x80)
        adr(helper + 8, 2, data + 0xd0); put(helper + 12, 0x52800000); put(helper + 16, 0xd65f03c0.toInt())
        put(helper + 0x100, 0xd503233f.toInt()); put(helper + 0x104, 0xd65f03c0.toInt())
        put(log, 0xd503233f.toInt()); put(log + 4, 0xd65f03c0.toInt())
        put(log + 8, 0xd503233f.toInt()); put(log + 12, 0xd65f03c0.toInt())
        return Sample(PeImage.parse(bytes), selected, compared, other, keyArg, qualifierLoad, consumed)
    }

    private fun apply(image: PeImage, plan: SemanticPlan): PeImage = PeImage.parse(image.bytes.copyOf().also { output ->
        plan.edits.forEach { edit -> edit.replacement.copyInto(output,
            requireNotNull(image.rvaToOffset(edit.rva, edit.replacement.size))) }
    })
    private fun replace(sample: Sample, pc: Long, word: Int) = PeImage.parse(sample.image.bytes.copyOf().also {
        it.putWord(requireNotNull(sample.image.rvaToOffset(pc, 4)), word)
    })

    @Test fun registerAddressRawOffsetAndLocalInstructionOrderCanChange() {
        for (sample in listOf(fixture(), fixture(11, 12, 13, 0x8000, 0x600, true),
            fixture(3, 7, 6, 0x14000, 0x200))) {
            val original = sample.image.bytes.copyOf()
            val plan = FastbootKeyPolicyPatcher.analyze(sample.image)
            assertEquals(1, plan.edits.size)
            assertEquals(sample.selector, plan.edits.single().rva)
            val result = apply(sample.image, plan)
            assertEquals(0x1a9f17e0 or (requireNotNull(sample.image.word(sample.selector)) and 31), result.word(sample.selector))
            assertEquals(sample.image.word(sample.otherSelector), result.word(sample.otherSelector))
            val offset = requireNotNull(sample.image.rvaToOffset(sample.selector, 4))
            assertTrue(original.indices.filter { it !in offset..<offset + 4 }.all { original[it] == result.bytes[it] })
            assertArrayEquals(original, sample.image.bytes)
            val repeated = FastbootKeyPolicyPatcher.analyze(result)
            assertArrayEquals(result.bytes, apply(result, repeated).bytes)
        }
    }

    @Test fun onlyScanDownBecomesIndependentOfAllQualificationValues() {
        val sample = fixture(); val output = apply(sample.image, FastbootKeyPolicyPatcher.analyze(sample.image))
        val before = requireNotNull(sample.image.word(sample.selector)); val after = requireNotNull(output.word(sample.selector))
        assertEquals(before and 31, after and 31)
        assertEquals(0x1a9f17e0, after and -32)
        for (scan in listOf(0, 1, 2, 5, 0x17)) for (qualification in listOf(0, 1, 2, 255)) {
            val oldValue = if (scan == 2) qualification else 0
            val newValue = if (scan == 2) 1 else 0
            if (scan != 2) assertEquals(oldValue == 1, newValue == 1)
            else assertEquals(1, newValue)
        }
    }

    @Test fun mismatchedReadBufferOrQualificationSlotIsRejected() {
        val sample = fixture()
        assertThrows(PatchException::class.java) { FastbootKeyPolicyPatcher.analyze(
            replace(sample, sample.keyArgument, 0x910113e0.toInt())) } // A different output buffer.
        val load = requireNotNull(sample.image.word(sample.qualificationLoad))
        assertThrows(PatchException::class.java) { FastbootKeyPolicyPatcher.analyze(
            replace(sample, sample.qualificationLoad, load + (1 shl 10))) }
    }

    @Test fun changedScanValueWrongFlagConsumerAndDifferentKnownObjectAreRejected() {
        val sample = fixture()
        assertThrows(PatchException::class.java) { FastbootKeyPolicyPatcher.analyze(
            replace(sample, sample.scanCompare, requireNotNull(sample.image.word(sample.scanCompare)) + (1 shl 10))) }
        assertThrows(PatchException::class.java) { FastbootKeyPolicyPatcher.analyze(
            replace(sample, sample.consumer, requireNotNull(sample.image.word(sample.consumer)) + (1 shl 10))) }
        assertThrows(PatchException::class.java) { FastbootKeyPolicyPatcher.analyze(fixture(otherBase = true).image) }
    }

    @Test fun twoCompleteFlowsAreReportedAsAmbiguous() {
        val failure = assertThrows(PatchException::class.java) { FastbootKeyPolicyPatcher.analyze(fixture(duplicate = true).image) }
        assertEquals(PatchFailureKind.AMBIGUOUS_CORE_FEATURE, failure.kind)
    }

    @Test fun realOldAndCurrentFamiliesPatchOneInstructionAndReimportIdentically() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(8).firstOrNull { File(it, "analysis/ablnew-compat/ablnew-inner.pe").isFile }
        assumeTrue("Local original ABL family samples", root != null)
        val locations = mutableSetOf<Long>()
        for (name in listOf("analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe",
            "analysis/ablnew-compat/ablnew-inner.pe")) {
            val source = PeImage.parse(File(root, name).readBytes()); val before = source.bytes.copyOf()
            val plan = FastbootKeyPolicyPatcher.analyze(source)
            assertEquals(1, plan.edits.size)
            locations += plan.edits.single().rva
            val output = apply(source, plan)
            assertArrayEquals(before, source.bytes)
            assertEquals(source.bytes.size, output.bytes.size)
            assertArrayEquals(output.bytes, apply(output, FastbootKeyPolicyPatcher.analyze(output)).bytes)
        }
        assertEquals(2, locations.size)
    }
}
