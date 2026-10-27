package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test

class PeExecutableSectionTest {
    private fun fixture(): ByteArray {
        val data = ByteArray(0x800)
        data[0] = 0x4d; data[1] = 0x5a
        data.putWord(0x3c, 0x80)
        data.putWord(0x80, 0x4550)
        data[0x84] = 0x64; data[0x85] = 0xaa.toByte(); data[0x86] = 1
        data[0x94] = 0xf0.toByte()
        val optional = 0x98
        data[optional] = 0x0b; data[optional + 1] = 2
        data.putWord(optional + 4, 0x400)
        data.putWord(optional + 16, 0x1000)
        data.putWord(optional + 32, 0x1000)
        data.putWord(optional + 36, 0x200)
        data.putWord(optional + 56, 0x2000)
        data.putWord(optional + 60, 0x400)
        data.putWord(optional + 108, 16)
        val section = optional + 0xf0
        ".text".toByteArray().copyInto(data, section)
        data.putWord(section + 8, 0x400); data.putWord(section + 12, 0x1000)
        data.putWord(section + 16, 0x400); data.putWord(section + 20, 0x400)
        data.putWord(section + 36, 0x60000020)
        data.putWord(0x400, 0xd65f03c0.toInt())
        return data
    }

    @Test fun appendPreservesOriginalMappingsAndUsesInputAlignments() {
        val bytes = fixture(); val unchanged = bytes.copyOf(); val source = PeImage.parse(bytes)
        val added = PeExecutableSection.append(source, 20)
        assertArrayEquals(unchanged, bytes)
        assertEquals(source.sections, added.image.sections.take(source.sections.size))
        assertEquals(source.entryRva, added.image.entryRva)
        assertEquals(0x2000L, added.rva)
        val section = added.image.sections.last()
        assertEquals(0x800, section.raw)
        assertEquals(0x200, section.rawSize)
        assertEquals(0x60000020L, section.characteristics)
        assertEquals(section.raw, added.image.rvaToOffset(added.rva, 20))
        assertArrayEquals(bytes.copyOfRange(0x400, 0x800), added.image.bytes.copyOfRange(0x400, 0x800))
        assertEquals(0x3000L, added.image.bytes.u32(0x98 + 56))
    }

    @Test fun occupiedHeaderPaddingIsNotOverwritten() {
        val bytes = fixture(); bytes[0x98 + 0xf0 + 40] = 7
        val before = bytes.copyOf()
        assertThrows(PatchException::class.java) { PeExecutableSection.append(PeImage.parse(bytes), 20) }
        assertArrayEquals(before, bytes)
    }

    @Test fun directoryOwnedZeroHeaderBytesAreNotOverwritten() {
        val bytes = fixture(); val header = 0x98 + 0xf0 + 40
        bytes.putWord(0x98 + 112 + 6 * 8, header)
        bytes.putWord(0x98 + 116 + 6 * 8, 28)
        val before = bytes.copyOf()
        assertThrows(PatchException::class.java) { PeExecutableSection.append(PeImage.parse(bytes), 20) }
        assertArrayEquals(before, bytes)
    }
}
