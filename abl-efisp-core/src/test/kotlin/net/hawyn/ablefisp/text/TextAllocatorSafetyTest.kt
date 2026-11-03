package net.hawyn.ablefisp.text

import net.hawyn.ablefisp.api.AblEfisp
import net.hawyn.ablefisp.patch.PatchMode
import net.hawyn.ablefisp.patch.PeImage
import net.hawyn.ablefisp.patch.BootFailurePolicyPatcher
import net.hawyn.ablefisp.patch.sha256
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
        assertEquals(".texttxt", TextPe(allocator.data).sections.single { storage.offset in it.raw until it.raw + it.size }.name)
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
                val fitsOldTail = startRva + width.size <= tail.rva + end - tail.raw
                if (fitsOldTail) assertEquals(startRva, width.rva)
                else {
                    assertTrue("Full old tail gets a new mapping, rather than overwriting code/text", width.rva >= image.sections.maxOf { it.rva + maxOf(it.size, it.virtualSize) })
                    val grown = TextPe(allocator.data)
                    assertEquals(width.offset, grown.rvaToOffset(width.rva, width.size))
                    for (old in image.sections) assertTrue(grown.sections.any { it.rva == old.rva && it.raw == old.raw && it.size == old.size })
                    assertArrayEquals(pe.copyOfRange(image.sizeOfHeaders, pe.size), allocator.data.copyOfRange(image.sizeOfHeaders, pe.size))
                }
                val context = FastbootTextDiscovery.discover(image)
                TextLayoutStubs.widthPayload(width.rva, checkNotNull(context.fontCharZero), context.lineBudget)
                    .copyInto(allocator.data, width.offset)
                var afterPayload = end
                while (afterPayload > tail.raw && allocator.data[afterPayload - 1] == 0.toByte()) afterPayload--
                val legacyNext = alignText(maxOf(afterPayload.toLong() + 16, (width.offset + width.size).toLong()), 16)
                val label = allocator.reserve(34)
                if (fitsOldTail) {
                    assertEquals(tail.rva + legacyNext - tail.raw, label.rva)
                    assertEquals(pe.size, allocator.data.size)
                } else {
                    assertTrue(label.rva >= width.rva + width.size)
                    assertEquals(label.offset, TextPe(allocator.data).rvaToOffset(label.rva, label.size))
                }
            }
        }
    }

    @Test fun oldPatchedImageRetainsItsTextWhileReceivingTheNewCommandPolicy() {
        val file = File(localRoot(), "analysis/factory-restore-20261027/current_efisp.img")
        assumeTrue("Local old finished efisp", file.isFile)
        val raw = file.readBytes()
        assertEquals("367240ce5f77720612677224ecfbc397f53577b11354f30b4a3538ffed5e68ab",raw.sha256())
        val pe = AblEfisp.extract(raw).output
        for (mode in PatchMode.entries) {
            val patched = AblEfisp.patch(pe, mode)
            val core = patched.output
            val flashingEdits = patched.patches.filter { it.name.startsWith("flashing unlock：") }
            val independent = patched.patches.filter { it.name.startsWith("oem superunlock：") }
            assertEquals(5, flashingEdits.size)
            assertTrue(independent.size >= 2)
            val dataHash = patched.patches.filter { it.name.startsWith("AVB HashDescriptor") }
            assertEquals(1, dataHash.size)
            assertEquals(4, dataHash.single().after.split(' ').size)
            val pss = patched.patches.filter { it.name == "共用 RSA-PSS 验证入口固定成功" }
            assertEquals(1, pss.size)
            assertEquals(12, pss.single().after.split(' ').size)
            val compatibility = patched.patches.filter { it.name.startsWith("AVB 低索引") ||
                it.name.startsWith("OEM 版本") || it.name.startsWith("BRINGUPAUTH") || it.name.startsWith("AVB 版本兼容") }
            assertEquals(10, compatibility.size)
            assertTrue(compatibility.all { it.after.split(' ').size == 4 })
            // This particular imported PE owns one historical 20-byte report
            // adapter. Locate its actual mapping first; derive the native BL
            // independently from the adapter's final unconditional branch.
            val importedImage = PeImage.parse(pe)
            val reportPlan = BootFailurePolicyPatcher.analyze(importedImage).single()
            val oldStub = requireNotNull(reportPlan.existingStubRva)
            val oldAt = requireNotNull(importedImage.rvaToOffset(oldStub,20))
            assertEquals(0x34000080 or reportPlan.allowRegister, importedImage.word(oldStub))
            assertEquals(0x71000c1f or (reportPlan.errorRegister shl 5), importedImage.word(oldStub+4))
            assertEquals(0x54000041, importedImage.word(oldStub+8))
            assertEquals(0xd65f03c0.toInt(), importedImage.word(oldStub+12))
            val tail = requireNotNull(importedImage.word(oldStub+16))
            assertEquals(0x14000000, tail and 0xfc000000.toInt())
            val reporter = oldStub+16+(((tail and 0x03ffffff) shl 6 shr 6).toLong()*4)
            assertEquals(0xadf9cL, reporter) // immutable fixture's native reporter, not a production table
            assertEquals(reportPlan.originalTarget, reporter)
            val oldCall = requireNotNull(importedImage.word(reportPlan.callRva))
            assertEquals(0x94000000.toInt(),oldCall and 0xfc000000.toInt())
            assertEquals(oldStub,reportPlan.callRva+(((oldCall and 0x03ffffff) shl 6 shr 6).toLong()*4))
            val nativeWord = 0x94000000.toInt() or (((reporter-reportPlan.callRva)/4).toInt() and 0x03ffffff)
            val nativeBytes = ByteArray(4) { n -> (nativeWord ushr (n*8)).toByte() }
            val nativeRestore = patched.patches.filter { it.name == "恢复原生 AVB hash 故障上报调用" }
            assertEquals(1,nativeRestore.size)
            val restoredCall = nativeRestore.single()
            val callAt = requireNotNull(importedImage.rvaToOffset(reportPlan.callRva,4))
            assertEquals(reportPlan.callRva,restoredCall.rva)
            assertEquals(callAt,restoredCall.fileOffset)
            assertEquals(4,restoredCall.after.split(' ').size)
            val oldCallBytes = ByteArray(4) { n -> (oldCall ushr (n*8)).toByte() }
            assertEquals(oldCallBytes.joinToString(" ") { "%02x".format(it.toInt() and 255) },restoredCall.before)
            assertEquals(nativeBytes.joinToString(" ") { "%02x".format(it.toInt() and 255) },restoredCall.after)
            val outputImage = PeImage.parse(core)
            val restoredAt = requireNotNull(outputImage.rvaToOffset(reportPlan.callRva,4))
            assertArrayEquals(nativeBytes,core.copyOfRange(restoredAt,restoredAt+4))
            val orphanAt = requireNotNull(outputImage.rvaToOffset(oldStub,20))
            assertArrayEquals("Historical report adapter bytes stay untouched and unreferenced by their old call",
                pe.copyOfRange(oldAt,oldAt+20),core.copyOfRange(orphanAt,orphanAt+20))
            assertEquals(nativeWord,outputImage.word(reportPlan.callRva))
            val newEdits = flashingEdits + independent + dataHash + compatibility + pss + nativeRestore
            val allowed = newEdits.flatMap { it.fileOffset until it.fileOffset + it.after.split(' ').size }.toSet()
            assertTrue(core.size >= pe.size)
            assertTrue(pe.indices.all { pe[it] == core[it] || it in allowed })
            assertEquals(AblEfisp.scanText(pe).entries.associate { it.id to it.text },
                AblEfisp.scanText(core).entries.associate { it.id to it.text })
            val result = TextEngine.apply(core,
                mapOf("fastboot-unlocked" to "device freedom (github hawyn2018)"), autoWrapFastboot = false)
            assertTrue(result.changes.isEmpty())
            assertArrayEquals(core, result.output)
            val imported = AblEfisp.patch(core, mode)
            assertTrue(imported.alreadyPatched)
            assertArrayEquals(core, imported.output)
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
