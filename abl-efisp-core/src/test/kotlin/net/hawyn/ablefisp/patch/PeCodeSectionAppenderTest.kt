package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test

class PeCodeSectionAppenderTest {
    private val optional = 0x98
    private val table = optional + 0xf0
    private fun rounded(value: Long, alignment: Int) = (value + alignment - 1) / alignment * alignment

    private fun fixture(
        fileAlignment: Int = 0x200,
        sectionAlignment: Int = 0x1000,
        codeRva: Long = 0x1000,
        count: Int = 1,
        minimumHeaders: Int = 0x400,
    ): ByteArray {
        val headers = rounded(maxOf(minimumHeaders, table + count * 40).toLong(), fileAlignment).toInt()
        val rawSize = rounded(0x400, fileAlignment).toInt()
        val data = ByteArray(headers + rawSize)
        data[0] = 0x4d; data[1] = 0x5a
        data.putWord(0x3c, 0x80); data.putWord(0x80, 0x4550)
        data.putWord(0x84, (count shl 16) or 0xaa64)
        data.putWord(0x94, 0x202200f0)
        data.putWord(optional, 0x20b)
        data.putWord(optional + 4, rawSize)
        data.putWord(optional + 16, codeRva.toInt())
        data.putWord(optional + 24, 0x40000000)
        data.putWord(optional + 28, 1)
        data.putWord(optional + 32, sectionAlignment)
        data.putWord(optional + 36, fileAlignment)
        data.putWord(optional + 56, rounded(codeRva + rawSize, sectionAlignment).toInt())
        data.putWord(optional + 60, headers)
        data.putWord(optional + 64, 0x11223344)
        data.putWord(optional + 108, 16)
        ".text".toByteArray(Charsets.US_ASCII).copyInto(data, table)
        data.putWord(table + 8, rawSize)
        data.putWord(table + 12, codeRva.toInt())
        data.putWord(table + 16, rawSize)
        data.putWord(table + 20, headers)
        data.putWord(table + 36, 0x60000020)
        data.putWord(headers, 0xd65f03c0.toInt())
        return data
    }

    @Test fun appendsOnePageRxAndPreservesEveryExistingMappingAndByteExceptRecordedHeaders() {
        val bytes = fixture(); val snapshot = bytes.copyOf(); val original = PeImage.parse(bytes)
        val imageSnapshot = original.bytes.copyOf()
        val result = PeCodeSectionAppender.append(original, 880, listOf(original.entryRva))
        val expanded = PeImage.parse(result.bytes)
        assertArrayEquals(snapshot, bytes)
        assertArrayEquals(imageSnapshot, original.bytes)
        assertEquals(original.sections, expanded.sections.take(original.sections.size))
        assertEquals(original.imageBase, expanded.imageBase)
        assertEquals(original.entryRva, expanded.entryRva)
        assertEquals(0x2000L, result.newSectionRva)
        assertEquals(result.newSectionRva, result.sectionRva)
        assertEquals(4096, result.allocationBytes)
        val section = expanded.sections.last()
        assertEquals(".textsu", section.name)
        assertEquals(0x1000, section.raw)
        assertEquals(4096L, section.virtualSize)
        assertEquals(4096, section.rawSize)
        assertEquals(0x60000020L, section.characteristics)
        assertFalse(section.writable)
        assertEquals(section.raw, expanded.rvaToOffset(result.newSectionRva, 880))
        assertTrue(expanded.isCode(result.newSectionRva, result.allocationBytes))
        assertEquals(0x3000L, result.bytes.u32(optional + 56))
        assertEquals(0x1400L, result.bytes.u32(optional + 4))
        assertEquals(2, result.bytes.u16(0x86))
        assertEquals(0L, result.bytes.u32(optional + 64))
        assertEquals(5, result.metadataEdits.size)
        assertEquals(listOf(40, 2, 4, 4, 4), result.metadataEdits.map { it.replacement.size })
        assertTrue(result.metadataEdits.all { it.name.startsWith("oem superunlock：PE映射元数据") })
        val replay = original.bytes.copyOf(result.bytes.size)
        val permitted = BooleanArray(original.bytes.size)
        result.metadataEdits.forEach {
            it.replacement.copyInto(replay, it.rva.toInt())
            for (at in it.rva.toInt() until it.rva.toInt() + it.replacement.size) permitted[at] = true
        }
        assertArrayEquals(result.bytes, replay)
        for (at in original.bytes.indices) if (!permitted[at]) assertEquals("Original byte $at", original.bytes[at], result.bytes[at])
        assertTrue(result.bytes.copyOfRange(section.raw, result.bytes.size).all { it == 0.toByte() })
    }

    @Test fun honorsLargerInputFileAndSectionAlignment() {
        val source = PeImage.parse(fixture(0x2000, 0x4000, 0x4000))
        val result = PeCodeSectionAppender.append(source, 880)
        val section = PeImage.parse(result.bytes).sections.last()
        assertEquals(0x4000, section.raw)
        assertEquals(0x2000, section.rawSize)
        assertEquals(0x8000L, section.rva)
        assertEquals(0xc000L, result.bytes.u32(optional + 56))
        assertEquals(0, section.raw % 0x2000)
        assertEquals(0L, section.rva % 0x4000)
    }

    @Test fun existingCertificateOverlayAndItsPointersRemainAtOriginalFileOffsets() {
        var bytes = fixture().copyOf(0x930)
        for (at in 0x800 until bytes.size) bytes[at] = (at % 251 + 1).toByte()
        bytes.putWord(optional + 112 + 4 * 8, 0x900)
        bytes.putWord(optional + 116 + 4 * 8, 0x30)
        val snapshot = bytes.copyOf()
        val original = PeImage.parse(bytes)
        assertEquals(bytes.size, original.bytes.size)
        val result = PeCodeSectionAppender.append(original, 880)
        val section = PeImage.parse(result.bytes).sections.last()
        assertTrue(section.raw >= original.bytes.size)
        assertEquals(0x1000, section.raw)
        assertArrayEquals(snapshot, bytes)
        assertArrayEquals(snapshot.copyOfRange(0x800, 0x930), result.bytes.copyOfRange(0x800, 0x930))
        assertArrayEquals(snapshot.copyOfRange(optional + 144, optional + 152), result.bytes.copyOfRange(optional + 144, optional + 152))
    }

    @Test fun existingRelocationDirectoryAndAbsolutePointerBytesAreUnchanged() {
        val bytes = fixture()
        bytes.putWord(optional + 112 + 5 * 8, 0x1100)
        bytes.putWord(optional + 116 + 5 * 8, 12)
        bytes.putWord(0x500, 0x1000); bytes.putWord(0x504, 12)
        bytes[0x508] = 0x80.toByte(); bytes[0x509] = 0xa0.toByte()
        bytes.putWord(0x480, 0x40001080); bytes.putWord(0x484, 1)
        val original = PeImage.parse(bytes)
        val result = PeCodeSectionAppender.append(original, 880)
        assertEquals(0x140000000L, original.imageBase)
        assertArrayEquals(bytes.copyOfRange(0x400, 0x800), result.bytes.copyOfRange(0x400, 0x800))
        assertArrayEquals(bytes.copyOfRange(optional + 152, optional + 160), result.bytes.copyOfRange(optional + 152, optional + 160))
    }

    @Test fun occupiedHeaderSlotIsRejectedWithoutMutation() {
        val bytes = fixture(); bytes[table + 40] = 7
        val snapshot = bytes.copyOf()
        assertThrows(PatchException::class.java) { PeCodeSectionAppender.append(PeImage.parse(bytes), 880) }
        assertArrayEquals(snapshot, bytes)
    }

    @Test fun zeroHeaderSlotOwnedByDirectoryIsRejectedWithoutMutation() {
        val bytes = fixture()
        bytes.putWord(optional + 112 + 6 * 8, table + 40)
        bytes.putWord(optional + 116 + 6 * 8, 28)
        val snapshot = bytes.copyOf()
        assertThrows(PatchException::class.java) { PeCodeSectionAppender.append(PeImage.parse(bytes), 880) }
        assertArrayEquals(snapshot, bytes)
    }

    @Test fun zeroHeaderSlotOwnedByCoffSymbolsIsRejectedWithoutMutation() {
        val bytes = fixture()
        bytes.putWord(0x80 + 12, table + 40); bytes.putWord(0x80 + 16, 1)
        val snapshot = bytes.copyOf()
        assertThrows(PatchException::class.java) { PeCodeSectionAppender.append(PeImage.parse(bytes), 880) }
        assertArrayEquals(snapshot, bytes)
    }

    @Test fun noFreeHeaderRecordAndMaximumSectionCountAreRejected() {
        for (bytes in listOf(fixture(count = 3, minimumHeaders = 0x200), fixture(count = 64))) {
            val snapshot = bytes.copyOf()
            assertThrows(PatchException::class.java) { PeCodeSectionAppender.append(PeImage.parse(bytes), 880) }
            assertArrayEquals(snapshot, bytes)
        }
    }

    @Test fun declaredImageGapIsRespectedRatherThanReused() {
        val bytes = fixture(); bytes.putWord(optional + 56, 0x6000)
        val result = PeCodeSectionAppender.append(PeImage.parse(bytes), 880)
        assertEquals(0x6000L, result.newSectionRva)
        assertEquals(0x7000L, result.bytes.u32(optional + 56))
    }

    @Test fun invalidSizesAndSixtyMiBLimitAreRejectedBeforeAllocatingOutput() {
        val source = PeImage.parse(fixture())
        for (size in listOf(0, -1, Int.MAX_VALUE, PeCodeSectionAppender.MAX_FILE_BYTES,
                PeCodeSectionAppender.MAX_FILE_BYTES - 4096)) {
            val snapshot = source.bytes.copyOf()
            assertThrows(PatchException::class.java) { PeCodeSectionAppender.append(source, size) }
            assertArrayEquals(snapshot, source.bytes)
        }
    }

    @Test fun malformedAlignmentsAndOverflowedPeLengthFieldsAreRejected() {
        val cases = listOf(
            fixture().also { it.putWord(optional + 36, 0) },
            fixture().also { it.putWord(optional + 36, 0x300) },
            fixture().also { it.putWord(optional + 32, 0) },
            fixture().also { it.putWord(optional + 32, 0x100) },
            fixture().also { it.putWord(optional + 4, -1) },
            fixture().also { it.putWord(optional + 56, 0xfffff000.toInt()) },
        )
        for (bytes in cases) {
            val snapshot = bytes.copyOf()
            assertThrows(PatchException::class.java) { PeCodeSectionAppender.append(PeImage.parse(bytes), 880) }
            assertArrayEquals(snapshot, bytes)
        }
    }

    @Test fun verifiesBidirectionalArm64BranchReachAndMappedAlignedAnchors() {
        val far = fixture().also { it.putWord(optional + 56, 0x09000000) }
        assertThrows(PatchException::class.java) { PeCodeSectionAppender.append(PeImage.parse(far), 880) }
        val source = PeImage.parse(fixture())
        assertThrows(PatchException::class.java) { PeCodeSectionAppender.append(source, 880, listOf(0x400)) }
        assertThrows(PatchException::class.java) { PeCodeSectionAppender.append(source, 880, listOf(0x1001)) }
        val near = fixture().also { it.putWord(optional + 56, 0x07fff000) }
        assertEquals(0x07fff000L, PeCodeSectionAppender.append(PeImage.parse(near), 880).newSectionRva)
    }

    @Test fun highAbsoluteRvaIsAllowedWhenRelativeBranchesAreReachable() {
        val original = PeImage.parse(fixture(codeRva = 0x90000000L))
        val added = PeCodeSectionAppender.append(original, 880, listOf(original.entryRva))
        assertEquals(0x90001000L, added.newSectionRva)
        assertEquals(original.sections, PeImage.parse(added.bytes).sections.take(original.sections.size))
    }
}
