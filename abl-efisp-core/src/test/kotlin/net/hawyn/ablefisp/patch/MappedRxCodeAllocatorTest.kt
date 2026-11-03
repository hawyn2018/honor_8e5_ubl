package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MappedRxCodeAllocatorTest {
    private val optional = 0x98
    private val table = optional + 0xf0

    private fun fixture(codeRva: Int = 0x1000, rawShift: Int = 0): ByteArray {
        val codeRaw = 0x400 + rawShift
        val dataRaw = codeRaw + 0x1000
        val data = ByteArray(dataRaw + 0x400)
        data[0] = 0x4d; data[1] = 0x5a
        data.putWord(0x3c, 0x80); data.putWord(0x80, 0x4550)
        data[0x84] = 0x64; data[0x85] = 0xaa.toByte(); data[0x86] = 2
        data[0x94] = 0xf0.toByte(); data[optional] = 0x0b; data[optional + 1] = 2
        data.putWord(optional + 4, 0x1000); data.putWord(optional + 16, codeRva)
        data.putWord(optional + 32, 0x1000); data.putWord(optional + 36, 0x200)
        data.putWord(optional + 56, codeRva + 0x4000); data.putWord(optional + 60, 0x400)
        data.putWord(optional + 108, 16)
        ".rxcode".toByteArray().copyInto(data, table)
        data.putWord(table + 8, 0x1000); data.putWord(table + 12, codeRva)
        data.putWord(table + 16, 0x1000); data.putWord(table + 20, codeRaw)
        data.putWord(table + 36, 0x60000020)
        ".table".toByteArray().copyInto(data, table + 40)
        data.putWord(table + 48, 0x400); data.putWord(table + 52, codeRva + 0x2000)
        data.putWord(table + 56, 0x400); data.putWord(table + 60, dataRaw)
        data.putWord(table + 76, 0xc0000040.toInt())
        data.putWord(codeRaw, 0xd65f03c0.toInt())
        return data
    }

    private fun allocate(bytes: ByteArray, size: Int = 20): MappedRxCodeAllocator.Allocation {
        val pe = PeImage.parse(bytes)
        return MappedRxCodeAllocator.allocate(pe, listOf(MappedRxCodeAllocator.Request(pe.entryRva, size))).single()
    }

    private fun putCode(bytes: ByteArray, relative: Int, word: Int) {
        val pe = PeImage.parse(bytes)
        bytes.putWord(requireNotNull(pe.rvaToOffset(pe.entryRva + relative, 4)), word)
    }

    private fun assertTailAllocation(bytes: ByteArray, expectedRva: Long) {
        val original = bytes.copyOf()
        val image = PeImage.parse(bytes)
        val slot = allocate(bytes)
        assertEquals(expectedRva, slot.rva)
        assertEquals(20, slot.size)
        assertTrue(slot.rva and 15 == 0L && image.isCode(slot.rva, slot.size))
        assertArrayEquals(ByteArray(slot.size), bytes.copyOfRange(
            requireNotNull(image.rvaToOffset(slot.rva)), requireNotNull(image.rvaToOffset(slot.rva)) + slot.size))
        assertArrayEquals(original, bytes)
    }

    @Test fun reservesOnlyExistingZeroTailAndDoesNotMutateHeadersOrInput() {
        for ((rva, shift) in listOf(0x1000 to 0, 0x7000 to 0x600)) {
            val bytes = fixture(rva, shift); val original = bytes.copyOf(); val pe = PeImage.parse(bytes)
            val slots = MappedRxCodeAllocator.allocate(pe, listOf(
                MappedRxCodeAllocator.Request(rva.toLong(), 20), MappedRxCodeAllocator.Request(rva.toLong(), 20),
            ))
            assertEquals(listOf(rva + 0x20L, rva + 0x40L), slots.map { it.rva })
            assertArrayEquals(original, bytes)
            assertEquals(pe.sections, PeImage.parse(bytes).sections)
            assertTrue(slots.all { it.rva and 15 == 0L && pe.isCode(it.rva, it.size) })
        }
    }

    @Test fun aZeroInstructionIsNotAcceptedAsAnExistingCaller() {
        val source = fixture(); val before = source.copyOf(); val pe = PeImage.parse(source)
        assertThrows(PatchException::class.java) {
            MappedRxCodeAllocator.allocate(pe, listOf(MappedRxCodeAllocator.Request(pe.entryRva + 0x20, 20)))
        }
        assertArrayEquals(before, source)
    }

    @Test fun alignedDisjointReservationsUseTheTailWithoutChangingReferences() {
        val bytes = fixture()
        val delta = 0x1057L - 0x1004
        putCode(bytes, 4, 0x10000012 or (((delta and 3) shl 29).toInt()) or (((delta shr 2) and 0x7ffff).toInt() shl 5))
        val original = bytes.copyOf(); val pe = PeImage.parse(bytes)
        val allocations = MappedRxCodeAllocator.allocate(pe, listOf(
            MappedRxCodeAllocator.Request(0x1000, 20), MappedRxCodeAllocator.Request(0x1000, 20),
        ))
        assertEquals(listOf(0x1020L, 0x1040L), allocations.map { it.rva })
        assertArrayEquals(original, bytes)
    }

    @Test fun refusesOccupiedTailWritableAndDiscardableMappings() {
        for (kind in 0..2) {
            val bytes = fixture()
            if (kind == 0) bytes[0x13ff] = 7
            else bytes.putWord(table + 36, if (kind == 1) 0xe0000020.toInt() else 0x62000020)
            val unchanged = bytes.copyOf()
            assertThrows(PatchException::class.java) { allocate(bytes) }
            assertArrayEquals(unchanged, bytes)
        }
    }

    @Test fun rawOnlyPaddingIsNotAnExistingMappedCodeReservation() {
        val bytes = fixture(); bytes.putWord(table + 8, 0x10)
        assertThrows(PatchException::class.java) { allocate(bytes) }
    }

    @Test fun branchAdrAndLiteralReferencesDoNotBlockTheZeroTail() {
        val branch = A64.branch(0x1004, 0x1030)
        val adrDelta = 0x1030L - 0x1004
        val adr = 0x10000008 or (((adrDelta and 3) shl 29).toInt()) or (((adrDelta shr 2) and 0x7ffff).toInt() shl 5)
        val literal = 0x58000000 or (((0x1030 - 0x1004) / 4) shl 5)
        for (word in listOf(branch, adr, literal)) {
            val bytes = fixture(); putCode(bytes, 4, word)
            assertTailAllocation(bytes, 0x1020)
        }
    }

    @Test fun unresolvedAdrpAndResolvedTailAccessDoNotBlockAllocation() {
        for (kind in 0..3) {
            val bytes = fixture(); putCode(bytes, 4, 0x90000008.toInt()) // ADRP X8, page1000
            when (kind) {
                0 -> putCode(bytes, 8, 0xd65f03c0.toInt())
                1 -> putCode(bytes, 8, 0x9100c108.toInt()) // ADD X8,X8,#30
                2 -> putCode(bytes, 8, 0xf9401908.toInt()) // LDR X8,[X8,#30]
                3 -> {
                    putCode(bytes, 8, 0x91000108.toInt()) // ADD X8,X8,#0, exact address before padding
                    putCode(bytes, 12, 0xf9401900.toInt()) // LDR X0,[X8,#30] reaches zero tail
                }
            }
            assertTailAllocation(bytes, 0x1020)
        }
    }

    @Test fun directoryAndAbsolutePointerValuesDoNotBlockTheZeroTail() {
        val directory = fixture().also {
            it.putWord(optional + 112 + 6 * 8, 0x1030); it.putWord(optional + 116 + 6 * 8, 16)
        }
        val pointer = fixture().also { it.putWord(0x1500, 0x1030) }
        for (bytes in listOf(directory, pointer)) {
            assertTailAllocation(bytes, 0x1020)
        }
    }

    @Test fun relocationWritesAndTargetsDoNotChangeTheTailAllocationPolicy() {
        for (target in listOf(false, true)) {
            val bytes = fixture()
            bytes.putWord(optional + 112 + 5 * 8, 0x3000); bytes.putWord(optional + 116 + 5 * 8, 12)
            bytes.putWord(0x1400, if (target) 0x3000 else 0x1000); bytes.putWord(0x1404, 12)
            val entry = if (target) 0xa100 else 0xa030
            bytes[0x1408] = entry.toByte(); bytes[0x1409] = (entry ushr 8).toByte()
            if (target) bytes.putWord(0x1500, 0x1030)
            assertTailAllocation(bytes, 0x1020)
        }
    }

    @Test fun guardsAndAlignedReservationsMustFitWithoutGrowingTheFile() {
        val bytes = fixture()
        assertThrows(PatchException::class.java) { allocate(bytes, 0x1000) }
        assertThrows(PatchException::class.java) { allocate(bytes, 0) }
    }

    @Test fun materializedIndexedAccessesDoNotBlockAllocation() {
        for (known in listOf(false, true)) {
            val bytes = fixture()
            putCode(bytes, 0x100, 0xd65f03c0.toInt()) // Last initialized object ends1104; candidate1120.
            putCode(bytes, 4, 0x90000008.toInt())
            putCode(bytes, 8, 0x91040108.toInt()) // ADD X8,X8,#100 -> live object1100.
            putCode(bytes, 12, if (known) 0x52800409 else 0xd503201f.toInt()) // index32 or unknown
            putCode(bytes, 16, 0x3869690a) // LDRB W10,[X8,X9] can reach1120.
            assertTailAllocation(bytes, 0x1120)
        }
    }

    @Test fun unscaledAndPairAccessesDoNotBlockAllocation() {
        for (memory in listOf(0xf8420100.toInt(), 0xa9420500.toInt())) { // LDUR X0,[X8,#32] / LDP X0,X1,[X8,#32]
            val bytes = fixture(); putCode(bytes, 0x100, 0xd65f03c0.toInt())
            putCode(bytes, 4, 0x90000008.toInt()); putCode(bytes, 8, 0x91040108.toInt())
            putCode(bytes, 12, memory)
            assertTailAllocation(bytes, 0x1120)
        }
    }

    @Test fun referencedZeroObjectsAndOpaqueUsesDoNotBlockAllocation() {
        for (escape in listOf(false, true)) {
            val bytes = fixture()
            putCode(bytes, 0x100, 0xd65f03c0.toInt()) // candidate1120, referenced zero object1110
            putCode(bytes, 4, 0x90000008.toInt()); putCode(bytes, 8, 0x91044108.toInt())
            putCode(bytes, 12, if (escape) A64.branch(0x100c, 0x1080) or 0x80000000.toInt() else 0xd65f03c0.toInt())
            assertTailAllocation(bytes, 0x1120)
        }
    }

    @Test fun wAndXIndexWidthsDoNotAffectTheTailReservation() {
        for (xIndex in listOf(false, true)) {
            val bytes = fixture(); putCode(bytes, 0x100, 0xd65f03c0.toInt())
            putCode(bytes, 4, 0x7100113f) // CMP W9,#4
            putCode(bytes, 8, 0x540000a8) // B.HI1020, outside read at1014
            putCode(bytes, 12, 0x90000008.toInt()); putCode(bytes, 16, 0x91040108.toInt())
            putCode(bytes, 20, if (xIndex) 0xb869790a.toInt() else 0xb869590a.toInt()) // LDR W10,[X8,X9/ W9 UXTW,#2]
            assertTailAllocation(bytes, 0x1120)
        }
    }

    @Test fun unreadableMappingAndUndersizedImageHeaderAreRejected() {
        val noRead = fixture().also { it.putWord(table + 36, 0x20000020) }
        val shortImage = fixture().also { it.putWord(optional + 56, 0x1010) }
        for (bytes in listOf(noRead, shortImage)) assertThrows(PatchException::class.java) { allocate(bytes) }
    }

    @Test fun loopInitializationStepAndEntryPatternsDoNotGateAllocation() {
        for (variant in 0..3) {
            val bytes = fixture(); putCode(bytes, 0x100, 0xd65f03c0.toInt())
            putCode(bytes, 4, if (variant == 1) 0xd2800129.toInt() else 0xaa1f03e9.toInt()) // X9=9 or0
            putCode(bytes, 8, 0xf1000d3f.toInt()) // CMP X9,#3
            putCode(bytes, 12, 0x54000140) // B.EQ1034
            putCode(bytes, 16, 0x90000008.toInt()); putCode(bytes, 20, 0x91040108.toInt())
            putCode(bytes, 24, 0x3869690a)
            putCode(bytes, 28, if (variant == 2) 0x91000929.toInt() else 0x91000529.toInt()) // +2 / +1
            putCode(bytes, 32, A64.branch(0x1020, 0x1008))
            putCode(bytes, 52, 0xd65f03c0.toInt())
            if (variant == 3) putCode(bytes, 64, A64.branch(0x1040, 0x1008))
            assertTailAllocation(bytes, 0x1120)
        }
    }

    @Test fun loopSpillAliasPatternsDoNotGateAllocation() {
        for (overlap in listOf(false, true)) {
            val bytes = fixture(); putCode(bytes, 0x100, 0xd65f03c0.toInt())
            putCode(bytes, 4, 0xaa1f03e9.toInt())
            putCode(bytes, 8, 0xf1000d3f.toInt())
            putCode(bytes, 12, 0x54000200) // B.EQ104c
            putCode(bytes, 16, 0xf90037e9.toInt()) // STR X9,[SP,#68]
            putCode(bytes, 20, 0x90000008.toInt()); putCode(bytes, 24, 0x91040108.toInt())
            putCode(bytes, 28, 0xd2800129.toInt()) // Temporary X9 argument clobber
            putCode(bytes, 32, 0x9101a3ea.toInt()) // ADD X10,SP,#68
            putCode(bytes, 36, if (overlap) 0xf900015f.toInt() else 0xd503201f.toInt())
            putCode(bytes, 40, 0xf94037e9.toInt())
            putCode(bytes, 44, 0x3869690a)
            putCode(bytes, 48, 0x91000529.toInt())
            putCode(bytes, 52, A64.branch(0x1034, 0x1008))
            putCode(bytes, 76, 0xd65f03c0.toInt())
            assertTailAllocation(bytes, 0x1120)
        }
    }

    @Test fun argumentAndStoredPointerEscapesDoNotBlockAllocation() {
        for (kind in 0..3) {
            val bytes = fixture(); putCode(bytes, 0x100, 0xd65f03c0.toInt())
            putCode(bytes, 4, 0x90000008.toInt()); putCode(bytes, 8, 0x91040108.toInt())
            when (kind) {
                0 -> {
                    putCode(bytes, 12, 0xaa0803e0.toInt()) // MOV X0,X8
                    putCode(bytes, 16, A64.branch(0x1010, 0x1080) or 0x80000000.toInt())
                }
                1 -> putCode(bytes, 12, 0xf90023e8.toInt()) // STR X8,[SP,#40]
                2 -> putCode(bytes, 12, 0xa9047fe8.toInt()) // STP X8,XZR,[SP,#40]
                else -> repeat(30) { putCode(bytes, 12 + it * 4, 0xd503201f.toInt()) }
            }
            assertTailAllocation(bytes, 0x1120)
        }
    }

    @Test fun actualInitializedBytesDetermineTheTailRatherThanReferenceBoundaries() {
        for (initialized in listOf(false, true)) {
            val bytes = fixture()
            putCode(bytes, 0x100, 0xd65f03c0.toInt())
            if (initialized) putCode(bytes, 0x140, 0xd65f03c0.toInt())
            putCode(bytes, 4, 0x90000008.toInt()); putCode(bytes, 8, 0x91040108.toInt())
            putCode(bytes, 12, 0xaa0803e0.toInt())
            putCode(bytes, 16, A64.branch(0x1010, 0x1080) or 0x80000000.toInt())
            val delta = 0x1140L - 0x1040
            putCode(bytes, 64, 0x1000000a or (((delta and 3) shl 29).toInt()) or (((delta shr 2) and 0x7ffff).toInt() shl 5))
            assertTailAllocation(bytes, if (initialized) 0x1160 else 0x1120)
        }
    }

    @Test fun farMaterializedBasesDoNotBlockTheCurrentImagesTail() {
        val bytes = fixture(0x17000)
        putCode(bytes, 0x100, 0xd65f03c0.toInt()) // Last initialized end17104; reservation17120.
        val delta = (0x1000L - 0x17000) shr 12
        val encoded = delta and 0x1fffff
        putCode(bytes, 4, 0x90000008.toInt() or ((encoded and 3).toInt() shl 29) or (((encoded shr 2) and 0x7ffff).toInt() shl 5))
        putCode(bytes, 8, 0x91000108.toInt()) // ADD X8,X8,#0 -> base1000, over64KiB away.
        putCode(bytes, 12, 0x52800009 or (0xb090 shl 5))
        putCode(bytes, 16, 0x7869790a) // LDRH W10,[X8,X9,LSL#1] ->1000+B090*2=17120.
        assertTailAllocation(bytes, 0x17120)
    }

    @Test fun wideAndWrappingIndexValuesDoNotGateAllocation() {
        for (wrapsIntoTail in listOf(false, true)) {
            val bytes = fixture(); putCode(bytes, 0x100, 0xd65f03c0.toInt())
            putCode(bytes, 4, 0x90000008.toInt()); putCode(bytes, 8, 0x91040108.toInt()) // base1100
            putCode(bytes, 12, if (wrapsIntoTail) 0xd2f00009.toInt() else 0xd2a00029.toInt()) // X9=2^63 / 2^32
            if (wrapsIntoTail) putCode(bytes, 16, 0xf2800209.toInt()) // MOVK X9,#16 => 2^63+16
            else putCode(bytes, 16, 0xd503201f.toInt())
            putCode(bytes, 20, 0x7869790a) // LDRH [X8,X9,LSL1]: wrap=>1120; other=>200001100
            putCode(bytes, 24, 0xd65f03c0.toInt())
            assertTailAllocation(bytes, 0x1120)
        }
    }

    @Test fun dynamicIndexIntervalsDoNotGateAllocation() {
        for (reachesTail in listOf(false, true)) {
            val bytes = fixture(); putCode(bytes, 0x100, 0xd65f03c0.toInt())
            // LSR X9,X10,#28 has a 36-bit upper bound. The interval from3100
            // never reaches the lower1120 reservation; from1100 it includes it.
            putCode(bytes, 4, if (reachesTail) 0x90000008.toInt() else 0xd0000008.toInt())
            putCode(bytes, 8, 0x91040108.toInt())
            putCode(bytes, 12, 0xd35cfd49.toInt())
            putCode(bytes, 16, 0x3869690a); putCode(bytes, 20, 0xd65f03c0.toInt())
            assertTailAllocation(bytes, 0x1120)
        }
    }

    @Test fun indexDefinitionDominanceDoesNotGateAllocation() {
        for (bypass in listOf(false, true)) {
            val bytes = fixture(); putCode(bytes, 0x100, 0xd65f03c0.toInt())
            putCode(bytes, 4, 0xb940004a.toInt()) // LDR W10,[X2] zero-extends X10
            putCode(bytes, 8, 0xd35cfd49.toInt()) // LSR X9,X10,#28 =>0..15
            putCode(bytes, 12, 0x90000008.toInt()); putCode(bytes, 16, 0x91040108.toInt())
            putCode(bytes, 20, 0x3869690a); putCode(bytes, 24, 0xd65f03c0.toInt())
            if (bypass) putCode(bytes, 64, A64.branch(0x1040, 0x1008))
            assertTailAllocation(bytes, 0x1120)
        }
    }

    @Test fun initializedContentSelectsTheTailWithOrWithoutDynamicObjectReferences() {
        for (variant in 0..2) {
            val bytes = fixture(); putCode(bytes, 0x100, 0xd65f03c0.toInt())
            if (variant != 1) putCode(bytes, 0x140, 0xd65f03c0.toInt())
            putCode(bytes, 4, 0x90000008.toInt()); putCode(bytes, 8, 0x91040108.toInt())
            putCode(bytes, 12, if (variant == 2) 0x52800c09 else 0xd35cfd49.toInt()) // X9=96 / unknown X10>>28
            putCode(bytes, 16, 0x3869690a); putCode(bytes, 20, 0xd65f03c0.toInt())
            val delta = 0x1140L - 0x1040
            putCode(bytes, 64, 0x1000000a or (((delta and 3) shl 29).toInt()) or (((delta shr 2) and 0x7ffff).toInt() shl 5))
            assertTailAllocation(bytes, if (variant == 1) 0x1120 else 0x1160)
        }
    }

    @Test fun eachImageFindsItsOwnTailAfterInitializedContentAndRawRelocation() {
        for ((rva, rawShift) in listOf(0x1000 to 0, 0x9000 to 0x600)) {
            for ((lastByte, expectedOffset) in listOf(0x20 to 0x40, 0x125 to 0x140, 0x345 to 0x360)) {
                val bytes = fixture(rva, rawShift)
                val image = PeImage.parse(bytes)
                bytes[requireNotNull(image.rvaToOffset(rva + lastByte.toLong()))] = 1
                assertTailAllocation(bytes, rva + expectedOffset.toLong())
            }
        }
    }

    @Test fun independentRxMappingsReceiveAlignedDisjointReservationsForTheirOwnCallers() {
        val bytes = fixture()
        bytes.putWord(table + 76, 0x60000020) // The second mapping is also RX.
        bytes.putWord(0x1400, 0xd65f03c0.toInt())
        val original = bytes.copyOf()
        val image = PeImage.parse(bytes)
        val slots = MappedRxCodeAllocator.allocate(image, listOf(
            MappedRxCodeAllocator.Request(0x1000, 20),
            MappedRxCodeAllocator.Request(0x3000, 20),
            MappedRxCodeAllocator.Request(0x1000, 20),
        ))
        assertEquals(listOf(0x1020L, 0x3020L, 0x1040L), slots.map { it.rva })
        assertEquals(listOf(0x400L, 0x1400L), image.sections.map { it.raw.toLong() })
        assertTrue(slots.all { it.rva and 15 == 0L && image.isCode(it.rva, it.size) })
        assertArrayEquals(original, bytes)
    }

    @Test fun realOldNewAndOtaCallSitesStayInTheirOriginalRxMappings() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "ablnew.img").isFile }
        assumeTrue("Local real firmware samples", root != null)
        for (path in listOf(
            "analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe",
            "analysis/ablnew-compat/ablnew-inner.pe",
            "analysis/locked-boot-feasibility/fastboot-ota-abl.pe",
        )) {
            val bytes = File(root, path).readBytes(); val original = bytes.copyOf(); val pe = PeImage.parse(bytes)
            val call = BootFailurePolicyPatcher.analyze(pe).single().callRva
            val slot = MappedRxCodeAllocator.allocate(pe, listOf(MappedRxCodeAllocator.Request(call, 20))).single()
            val owner = pe.sections.single { call in it.rva until it.rva + it.rawSize }
            assertTrue(slot.rva >= owner.rva && slot.rva + slot.size <= owner.rva + minOf(owner.virtualSize, owner.rawSize.toLong()))
            val offset = requireNotNull(pe.rvaToOffset(slot.rva, slot.size))
            assertTrue(pe.bytes.copyOfRange(offset, offset + slot.size).all { it == 0.toByte() })
            assertArrayEquals(original, bytes)
        }
    }
}
