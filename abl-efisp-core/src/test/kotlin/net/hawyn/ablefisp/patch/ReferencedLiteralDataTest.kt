package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test

class ReferencedLiteralDataTest {
    private val codeRva = 0x1000L
    private val stringRva = 0x1201L
    private val pseudoAdrPc = 0x1218L
    private val optional = 0x98
    private val table = optional + 0xf0

    private fun adr(pc: Long, target: Long, register: Int): Int {
        val delta = target - pc
        require(delta in -(1L shl 20) until (1L shl 20))
        return 0x10000000 or register or ((delta and 3).toInt() shl 29) or (((delta shr 2) and 0x7ffff).toInt() shl 5)
    }

    private fun fixture(size: Int = 0x1000): ByteArray {
        val bytes = ByteArray(0x400 + size)
        bytes[0] = 0x4d; bytes[1] = 0x5a
        bytes.putWord(0x3c, 0x80); bytes.putWord(0x80, 0x4550)
        bytes[0x84] = 0x64; bytes[0x85] = 0xaa.toByte(); bytes[0x86] = 1
        bytes[0x94] = 0xf0.toByte(); bytes[optional] = 0x0b; bytes[optional + 1] = 2
        bytes.putWord(optional + 4, size); bytes.putWord(optional + 16, codeRva.toInt())
        bytes.putWord(optional + 32, 0x1000); bytes.putWord(optional + 36, 0x200)
        bytes.putWord(optional + 56, codeRva.toInt() + size); bytes.putWord(optional + 60, 0x400)
        bytes.putWord(optional + 108, 16)
        ".text".toByteArray().copyInto(bytes, table)
        bytes.putWord(table + 8, size); bytes.putWord(table + 12, codeRva.toInt())
        bytes.putWord(table + 16, size); bytes.putWord(table + 20, 0x400)
        bytes.putWord(table + 36, 0x60000020)
        put(bytes, 0x1000, 0xd503233f.toInt()) // PACIASP: an independently addressed function.
        put(bytes, 0x1004, adr(0x1004, stringRva, 19))
        put(bytes, 0x1008, 0xd503201f.toInt())
        put(bytes, 0x100c, 0xaa1303e1.toInt()) // MOV X1,X19.
        put(bytes, 0x1010, A64.branch(0x1010, 0x1080) or 0x80000000.toInt())
        put(bytes, 0x1014, 0xd65f03c0.toInt())
        put(bytes, 0x1080, 0xd503233f.toInt())
        put(bytes, 0x1084, 0x39400028) // LDRB W8,[X1].
        put(bytes, 0x1088, 0x34000048) // CBZ W8,1090: a real byte/NUL consumer.
        put(bytes, 0x108c, 0xd65f03c0.toInt())
        put(bytes, 0x1090, 0xd65f03c0.toInt())
        "Unable to get GetMultiSlotPartsList\n\u0000".toByteArray().copyInto(bytes, offset(stringRva))
        assertEquals(0x50746f6c, PeImage.parse(bytes).word(pseudoAdrPc)) // ASCII "lotP".
        return bytes
    }

    private fun offset(rva: Long): Int = (0x400 + rva - codeRva).toInt()
    private fun put(bytes: ByteArray, rva: Long, word: Int) = bytes.putWord(offset(rva), word)
    private fun classified(bytes: ByteArray): Boolean {
        val image = PeImage.parse(bytes)
        return ReferencedLiteralData(image, image.instructions().toList()).containsInstructionWord(pseudoAdrPc)
    }

    private fun flagConsumer(load: Int, test: Int, condition: Int = 0, between: List<Int> = emptyList()): ByteArray {
        val bytes = fixture()
        bytes.fill(0, offset(0x1084), offset(0x10c0))
        put(bytes, 0x1084, load)
        put(bytes, 0x1088, test)
        between.forEachIndexed { index, word -> put(bytes, 0x108cL + index * 4, word) }
        val branch = 0x108cL + between.size * 4
        put(bytes, branch, 0x54000040 or condition)
        put(bytes, branch + 4, 0xd65f03c0.toInt())
        put(bytes, branch + 8, 0xd65f03c0.toInt())
        return bytes
    }

    @Test fun aLiteralRequiresReachableAddressFlowIntoADirectCallArgument() {
        assertTrue(classified(fixture()))
        for (pc in listOf(0x1004L, 0x1010L)) {
            val bytes = fixture(); put(bytes, pc, 0xd503201f.toInt())
            assertFalse(classified(bytes))
        }
        val clobbered = fixture(); put(clobbered, 0x100c, 0xaa1f03e1.toInt())
        assertFalse(classified(clobbered))
        val unreachable = fixture(); put(unreachable, 0x1000, 0xd65f03c0.toInt())
        assertFalse(classified(unreachable))
    }

    @Test fun literalEvidenceFollowsRegisterRenamingAndAPrivateSpillReload() {
        val bytes = fixture()
        put(bytes, 0x1008, 0xf90013f3.toInt()) // STR X19,[SP,#32].
        put(bytes, 0x100c, 0xaa1f03f3.toInt()) // Clobber original X19.
        put(bytes, 0x1010, 0xf94013e1.toInt()) // LDR X1,[SP,#32].
        put(bytes, 0x1014, A64.branch(0x1014, 0x1080) or 0x80000000.toInt())
        put(bytes, 0x1018, 0xd65f03c0.toInt())
        assertTrue(classified(bytes))
        put(bytes, 0x100c, 0xf90013ff.toInt()) // Overwrite private slot with XZR.
        assertFalse(classified(bytes))
    }

    @Test fun literalEvidenceAcceptsAdrpAddWithIndependentInstructions() {
        val bytes = fixture()
        put(bytes, 0x1004, 0x90000014.toInt()) // ADRP X20,page1000.
        put(bytes, 0x1008, 0x52800800) // An independent W0 argument.
        put(bytes, 0x100c, 0x91080681.toInt()) // ADD X1,X20,#201.
        assertTrue(classified(bytes))
        put(bytes, 0x1008, 0xaa1f03f4.toInt()) // Clobber page X20 before ADD.
        assertFalse(classified(bytes))
    }

    @Test fun independentCodeOrStoredPointerEntriesKeepPrintableBytesAmbiguous() {
        val branch = fixture(); put(branch, 0x1084, A64.branch(0x1084, pseudoAdrPc))
        assertFalse(classified(branch))
        val pointer = fixture(); pointer.putWord(offset(0x1180), stringRva.toInt())
        assertFalse(classified(pointer))
        val writable = fixture(); writable.putWord(table + 36, 0xe0000020.toInt())
        assertFalse(classified(writable))
    }

    @Test fun aRootedFunctionFallingThroughIntoPrintableInstructionsIsNotData() {
        val bytes = fixture()
        bytes.fill(0, offset(0x11f4), offset(0x1240))
        put(bytes, 0x1004, adr(0x1004, 0x11ff, 19))
        put(bytes, 0x1100, A64.branch(0x1100, 0x11f4) or 0x80000000.toInt())
        put(bytes, 0x11f4, 0xd503233f.toInt())
        put(bytes, 0x11f8, 0xd503201f.toInt())
        put(bytes, 0x11fc, 0x50000000) // Legal ADR, ending with ASCII P.
        put(bytes, 0x1200, 0x50746f6c) // Legal ADR, ASCII lotP.
        put(bytes, 0x1204, 0x50746f6c)
        put(bytes, 0x1208, 0x10000000) // Legal ADR; its first byte terminates the apparent string.
        put(bytes, 0x120c, 0xd65f03c0.toInt())
        val image = PeImage.parse(bytes)
        assertFalse(ReferencedLiteralData(image, image.instructions().toList()).containsInstructionWord(0x1200))
    }

    @Test fun unknownConsumersAndCodePointersDoNotProveAStringLiteral() {
        val opaque = fixture(); put(opaque, 0x1084, 0xd65f03c0.toInt())
        assertFalse(classified(opaque))
        val executed = fixture(); put(executed, 0x1084, 0xd63f0020.toInt()) // BLR X1.
        assertFalse(classified(executed))
        val forwarded = fixture(); put(forwarded, 0x1084, A64.branch(0x1084, 0x1180) or 0x80000000.toInt())
        put(forwarded, 0x1180, 0xd503233f.toInt()); put(forwarded, 0x1184, 0xd61f0020.toInt()) // BR X1.
        assertFalse(classified(forwarded))
        val offsetCode = fixture()
        put(offsetCode, 0x1088, 0x340000c8) // NUL exits to10a0 instead of bypassing the ADD definition.
        put(offsetCode, 0x108c, 0x91001029.toInt()) // ADD X9,X1,#4 after a valid byte/NUL use.
        put(offsetCode, 0x1090, 0xaa1f03e1.toInt()) // Clear the exact original argument alias.
        put(offsetCode, 0x1094, 0xd63f0120.toInt()) // BLR X9 still executes the entry object's region.
        put(offsetCode, 0x1098, 0xd65f03c0.toInt())
        put(offsetCode, 0x10a0, 0xd65f03c0.toInt())
        assertFalse(classified(offsetCode))
    }

    @Test fun scalarAndVectorWritesThroughTheCandidateAreNotReadOnlyStringUses() {
        for (store in listOf(0x39000020, 0x3d800020)) { // STRB W0,[X1] / STR Q0,[X1].
            val bytes = fixture()
            put(bytes, 0x1084, store)
            put(bytes, 0x1088, 0x39400028)
            put(bytes, 0x108c, 0x34000048)
            put(bytes, 0x1090, 0xd65f03c0.toInt())
            put(bytes, 0x1094, 0xd65f03c0.toInt())
            assertFalse(classified(bytes))
        }
    }

    @Test fun signedAndUnsignedBytesAcceptEquivalentFlagBasedZeroTests() {
        for (load in listOf(0x39400028, 0x39800028, 0x39c00028)) { // LDRB W8 / LDRSB X8 / LDRSB W8.
            for (test in listOf(
                0x7100011f, 0xf100011f.toInt(), // CMP W8/X8,#0.
                0x6a08011f, 0xea08011f.toInt(), // TST W8/X8 with itself.
                0x72001d1f, 0xf2401d1f.toInt(), // TST W8/X8,#ff.
                0x72003d1f, 0xf2403d1f.toInt(), // A larger mask still covers every low byte bit.
            )) for (condition in 0..1) {
                assertTrue("load=${load.toUInt().toString(16)} test=${test.toUInt().toString(16)} condition=$condition",
                    classified(flagConsumer(load, test, condition)))
            }
            val direct = fixture(); put(direct, 0x1084, load)
            assertTrue(classified(direct))
        }
        assertTrue(classified(flagConsumer(0x39400028, 0x7100011f,
            between = listOf(0xd503201f.toInt(), 0x2a1f03e8)))) // NOP then clear W8; flags still carry the original byte test.
        val renamed = fixture()
        put(renamed, 0x1088, 0x2a0803e9) // MOV W9,W8.
        put(renamed, 0x108c, 0x7100013f) // CMP W9,#0.
        put(renamed, 0x1090, 0x54000040)
        put(renamed, 0x1094, 0xd65f03c0.toInt())
        put(renamed, 0x1098, 0xd65f03c0.toInt())
        assertTrue(classified(renamed))
    }

    @Test fun flagBasedByteEvidenceRejectsOtherValuesPartialMasksAndLostFlags() {
        for (test in listOf(
            0x7100051f, // CMP W8,#1 is not a NUL test.
            0x7200191f, 0x7200011f, // TST W8,#7f / #1 lose byte bits.
            0x6a09011f, 0x6a08051f, // Unrelated operand / shifted self are not equality-to-zero.
            0x7100013f, // CMP unrelated W9,#0.
            0x71c0011f, // Reserved ADD/SUB-immediate bit23 is not a valid CMP.
            0x72401d1f, // N=1 with a 32-bit logical immediate is reserved.
        )) assertFalse(classified(flagConsumer(0x39400028, test)))
        assertFalse(classified(flagConsumer(0x39400028, 0x7100011f, condition = 8))) // B.HI is not eq/ne.
        for (clobber in listOf(0x7100001f, 0x31000400, 0xd51b4200.toInt())) {
            // Another CMP / ADDS / MSR NZCV cannot carry earlier flags evidence.
            assertFalse(classified(flagConsumer(0x39400028, 0x7100011f, between = listOf(clobber))))
        }
    }

    @Test fun flagsEvidenceRequiresEveryBranchPredecessorToPassTheSameZeroTest() {
        val bytes = fixture()
        bytes.fill(0, offset(0x1084), offset(0x10c0))
        put(bytes, 0x1084, 0x39400028)
        put(bytes, 0x1088, 0x34000060) // CBZ W0,1094 bypasses the CMP.
        put(bytes, 0x108c, 0x7100011f)
        put(bytes, 0x1090, 0xd503201f.toInt())
        put(bytes, 0x1094, 0x54000040)
        put(bytes, 0x1098, 0xd65f03c0.toInt())
        put(bytes, 0x109c, 0xd65f03c0.toInt())
        assertFalse(classified(bytes))
    }

    @Test fun allocatorIgnoresOnlyTheProvenLiteralSourceNotAnUnalignedRealReference() {
        val target = requireNotNull(A64.adr(pseudoAdrPc, 0x50746f6c)).second
        val size = (((target + 0x1000 - codeRva) + 0xfff) and -0x1000L).toInt()
        val bytes = fixture(size)
        bytes[offset(target - 0x20)] = 1 // Candidate prefix begins six bytes before the pseudo ADR target.
        val original = bytes.copyOf()
        val image = PeImage.parse(bytes)
        val slot = MappedRxCodeAllocator.allocate(image, listOf(MappedRxCodeAllocator.Request(codeRva, 20))).single()
        assertTrue(target in slot.rva until slot.rva + slot.size)
        assertArrayEquals(original, bytes)

        put(bytes, 0x1100, adr(0x1100, target + 1, 8)) // Real, legally unaligned data pointer.
        assertThrows(PatchException::class.java) {
            MappedRxCodeAllocator.allocate(PeImage.parse(bytes), listOf(MappedRxCodeAllocator.Request(codeRva, 20)))
        }
    }

    @Test fun xzrAddressDefinitionsHaveNoLivePointerButOtherDestinationsStayProtected() {
        for (page in listOf(false, true)) {
            val bytes = fixture()
            put(bytes, 0x1004, 0xd503201f.toInt()) // Isolate XZR from the separate opaque-last-object escape guard.
            // Keep an initialized object at1200 so the candidate starts1240.
            put(bytes, 0x1220, 0xd65f03c0.toInt())
            val target = 0x1241L
            val word = if (page) 0x9000001f.toInt() else adr(0x1100, target, 31)
            put(bytes, 0x1100, word)
            val image = PeImage.parse(bytes)
            assertTrue(A64.references(image).none { it.pc == 0x1100L })
            MappedRxCodeAllocator.allocate(image, listOf(MappedRxCodeAllocator.Request(codeRva, 20)))
            put(bytes, 0x1100, word and 31.inv() or 8)
            assertThrows(PatchException::class.java) {
                MappedRxCodeAllocator.allocate(PeImage.parse(bytes), listOf(MappedRxCodeAllocator.Request(codeRva, 20)))
            }
        }
    }
}
