package net.hawyn.ablefisp.text

import net.hawyn.ablefisp.api.AblEfisp
import net.hawyn.ablefisp.patch.PatchMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TextAllocatorSafetyTest {
    @Test fun anUnalignedReferencedZeroObjectKeepsItsLaterVectorReadIntact() {
        // X1 names an unaligned zero object; LDR Q0,[X1,#96] reads its suffix.
        // The old point-only skip reserved [0x1230,0x129c), overlapping that read.
        val source = fixture(target = 0x1213)
        val allocator = TextAllocator(source, TextA64.refs(TextPe(source)))
        val storage = allocator.allocate(ByteArray(108) { 0x5a })
        assertFalse(overlaps(storage, 0x1273, 0x1283))
        assertEquals(".eftxt", TextPe(allocator.data).sections.single { storage.offset in it.raw until it.raw + it.size }.name)
        assertArrayEquals(source.copyOfRange(0x1213, 0x3000), allocator.data.copyOfRange(0x1213, 0x3000))
    }

    @Test fun sufficientPrefixBeforeTheFirstReferenceStaysInTheOriginalMapping() {
        val source = fixture(target = 0x1303)
        val allocator = TextAllocator(source, TextA64.refs(TextPe(source)))
        val storage = allocator.reserve(108)
        assertEquals(0x1210L, storage.rva)
        assertEquals(source.size, allocator.data.size)
        assertArrayEquals(source, allocator.data)
    }

    @Test fun aReferenceInTheAlignmentGuardProtectsTheFollowingUnknownObject() {
        val source = fixture(target = 0x1203)
        val allocator = TextAllocator(source, TextA64.refs(TextPe(source)))
        val storage = allocator.reserve(108)
        assertTrue(storage.rva >= 0x3000)
    }

    @Test fun relocationTargetsHaveTheSameUnknownZeroObjectProtection() {
        val source = fixture(target = null)
        // DIR64 relocation stores a pointer at 0x1180 to the zero object.
        put32(source, 0x1180, 0x1213)
        val directory = 0x98 + 112 + 5 * 8
        put32(source, directory, 0x1150); put32(source, directory + 4, 12)
        put32(source, 0x1150, 0x1000); put32(source, 0x1154, 12)
        put16(source, 0x1158, 0xa180); put16(source, 0x115a, 0)
        val allocator = TextAllocator(source, emptyList())
        val storage = allocator.reserve(108)
        assertTrue(storage.rva >= 0x3000)
    }

    @Test fun noReferencesKeepTheOriginalAllocationAndPayloadBytes() {
        val source = fixture(target = null)
        val allocator = TextAllocator(source, emptyList())
        val payload = ByteArray(108) { (it + 1).toByte() }
        val storage = allocator.allocate(payload)
        assertEquals(0x1210L, storage.rva)
        assertEquals(0x1210, storage.offset)
        val expected = source.copyOf().also { payload.copyInto(it, 0x1210) }
        assertArrayEquals(expected, allocator.data)
    }

    @Test fun reservationsRemainDisjointEvenBeforeTheirPayloadsAreWritten() {
        val source = fixture(target = null)
        val allocator = TextAllocator(source, emptyList())
        val first = allocator.reserve(108)
        val second = allocator.reserve(34)
        val third = allocator.reserve(20)
        assertEquals(0x1210L, first.rva)
        assertEquals(0x1280L, second.rva)
        assertEquals(0x12b0L, third.rva)
        assertTrue(first.rva + first.size <= second.rva)
        assertTrue(second.rva + second.size <= third.rva)
        assertArrayEquals(source, allocator.data)
    }

    @Test fun repeatedReservationsNeverCrossAnUnknownReferencedSuffix() {
        val source = fixture(target = 0x1303)
        val allocator = TextAllocator(source, TextA64.refs(TextPe(source)))
        val first = allocator.reserve(108)
        val second = allocator.reserve(108)
        val third = allocator.reserve(108)
        assertEquals(0x1210L, first.rva)
        assertEquals(0x1280L, second.rva)
        assertTrue(second.rva + second.size <= 0x1303)
        assertTrue(third.rva >= 0x3000)
    }

    @Test fun localNewAndOldFirmwareRetainTheirUnreferencedLegacyTextRanges() {
        val root = localRoot()
        for (path in listOf("analysis/custom-flash-error-v244-20261027/source-other-abl.img",
            "analysis/factory-restore-20261027/after_abl_b.img")) {
            val source = File(root, path)
            assumeTrue("Local firmware integration input: $path", source.isFile)
            for (mode in PatchMode.entries) {
                val pe = AblEfisp.patch(source.readBytes(), mode).output
                val image = TextPe(pe)
                val refs = TextA64.refs(image)
                val tail = image.sections.last { it.flags and 0x60000000L == 0x60000000L }
                val end = tail.raw + minOf(tail.size, tail.virtualSize.takeIf { it > 0 } ?: tail.size)
                var last = end
                while (last > tail.raw && pe[last - 1] == 0.toByte()) last--
                val originalStart = alignText(last.toLong() + 16, 16).toInt()
                val startRva = tail.rva + originalStart - tail.raw
                assertTrue(refs.none { it.target in startRva until tail.rva + end - tail.raw })
                val allocator = TextAllocator(pe, refs)
                val width = allocator.reserve(TextLayoutStubs.WIDTH_BYTES)
                assertEquals(startRva, width.rva)
                val context = FastbootTextDiscovery.discover(image)
                TextLayoutStubs.widthPayload(width.rva, checkNotNull(context.fontCharZero), context.lineBudget)
                    .copyInto(allocator.data, width.offset)
                var afterPayload = end
                while (afterPayload > tail.raw && allocator.data[afterPayload - 1] == 0.toByte()) afterPayload--
                val legacyNext = alignText(maxOf(afterPayload.toLong() + 16, (width.offset + width.size).toLong()), 16)
                val label = allocator.reserve(34)
                assertEquals(tail.rva + legacyNext - tail.raw, label.rva)
                assertEquals(pe.size, allocator.data.size)
            }
        }
    }

    @Test fun oldPatchedImageReimportsTheSameTextWithoutGrowthOrByteChanges() {
        val file = File(localRoot(), "analysis/factory-restore-20261027/current_efisp.img")
        assumeTrue("Local old finished efisp", file.isFile)
        val pe = AblEfisp.extract(file.readBytes()).output
        for (mode in PatchMode.entries) {
            val core = AblEfisp.patch(pe, mode).output
            assertArrayEquals(pe, core)
            val result = TextEngine.apply(core,
                mapOf("fastboot-unlocked" to "device freedom (github hawyn2018)"), autoWrapFastboot = false)
            assertTrue(result.changes.isEmpty())
            assertArrayEquals(pe, result.output)
        }
    }

    private fun localRoot(): File {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "abl.elf").isFile }
        assumeTrue("Local firmware integration inputs", root != null)
        return root!!
    }

    private fun overlaps(storage: TextStorage, start: Long, end: Long) = storage.rva < end && storage.rva + storage.size > start

    private fun fixture(target: Int?): ByteArray {
        val b = ByteArray(0x3000)
        b[0] = 0x4d; b[1] = 0x5a; put32(b, 0x3c, 0x80); put32(b, 0x80, 0x4550)
        put16(b, 0x84, 0xaa64); put16(b, 0x86, 1); put16(b, 0x94, 0xf0)
        val o = 0x98; put16(b, o, 0x20b); put32(b, o + 16, 0x1100)
        put32(b, o + 32, 0x1000); put32(b, o + 36, 0x1000); put32(b, o + 56, 0x3000); put32(b, o + 60, 0x1000)
        val h = o + 0xf0
        ".text".toByteArray().copyInto(b, h); put32(b, h + 8, 0x2000); put32(b, h + 12, 0x1000)
        put32(b, h + 16, 0x2000); put32(b, h + 20, 0x1000); put32(b, h + 36, 0x60000020)
        put32(b, 0x1100, 0xd503233f)
        if (target != null) {
            put32(b, 0x1104, TextA64.adrp(1, 0x1104, target.toLong()).toLong())
            put32(b, 0x1108, TextA64.add(1, target.toLong()).toLong())
            put32(b, 0x110c, 0x3dc01820) // LDR Q0,[X1,#96]
        }
        put32(b, 0x1110, 0xd65f03c0)
        b[0x11ff] = 0x5a
        return b
    }
}
