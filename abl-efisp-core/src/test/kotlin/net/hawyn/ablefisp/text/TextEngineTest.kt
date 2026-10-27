package net.hawyn.ablefisp.text

import org.junit.Assert.*
import org.junit.Test

class TextEngineTest {
    @Test fun scansSemanticCopyAndResourceSlotsAtDifferentAddresses() {
        for (shift in listOf(0, 0x1000)) {
            val scan = TextEngine.scan(fixture(shift))
            val label = scan.entries.single { it.id == "fastboot-unlocked" }
            assertEquals("PHONE Unlocked", label.text)
            assertTrue(label.reason, label.editable)
            assertEquals(255, label.maxBytes)
            val warning = scan.entries.single { it.text.contains("has been unlocked") }
            assertTrue(warning.editable)
            assertEquals((0x7218 + shift).toLong(), warning.rva)
        }
    }

    @Test fun editsLongLabelWithFullCopyAndMappedStorageThenEditsAgain() {
        val source = fixture()
        val input = "device freedom   github hawyn2018"
        val result = TextEngine.apply(source, mapOf("fastboot-unlocked" to input))
        assertTrue(result.changes.any { it.id == "fastboot-unlocked" })
        val next = TextEngine.scan(result.output).entries.single { it.id == "fastboot-unlocked" }
        assertTrue(next.editable)
        assertEquals(input.replace(" ", ""), next.text.replace(" ", "").replace("\n", ""))
        val image = TextPe(result.output)
        assertEquals(next.text.length + 1, TextA64.movImmediate(image.word(0x1250), 3))
        assertTrue(TextA64.movImmediate(image.word(0x1244), 1)!! >= next.text.length + 1)
        assertTrue(next.rva != 0x3340L)
        val second = TextEngine.apply(result.output, mapOf(next.id to "another unlocked label"))
        assertEquals("another unlocked label", TextEngine.scan(second.output).entries.single { it.id == next.id }.text.replace('\n', ' '))
    }

    @Test fun singleLineLabelKeepsExactTextAndCopiesItsTerminatingNulWithinProvenCapacity() {
        val input = "device freedom (github hawyn2018)"
        assertEquals(33, input.toByteArray(Charsets.US_ASCII).size)
        val requiredBytes = input.length + 1
        assertEquals(34, requiredBytes)
        for (shift in listOf(0, 0x1000)) {
            val source = fixture(shift)
            val before = source.copyOf()
            val original = TextEngine.scan(source).entries.single { it.id == "fastboot-unlocked" }
            val result = TextEngine.apply(source, mapOf(original.id to input), autoWrapFastboot = false)
            val label = TextEngine.scan(result.output).entries.single { it.id == original.id }
            val image = TextPe(result.output)
            assertEquals(input, label.text)
            assertTrue(label.editable)
            assertArrayEquals(input.toByteArray(Charsets.US_ASCII) + byteArrayOf(0),
                result.output.copyOfRange(label.fileOffset, label.fileOffset + requiredBytes))
            val count = TextA64.movImmediate(image.word((0x1250 + shift).toLong()), 3)
            val capacity = checkNotNull(TextA64.movImmediate(image.word((0x1244 + shift).toLong()), 1))
            assertEquals(requiredBytes, count)
            assertTrue("Copy includes the NUL terminator", capacity >= requiredBytes)
            assertTrue("Capacity stays inside the proven stack buffer", capacity <= original.maxBytes + 1)
            assertArrayEquals(before, source)
        }
    }

    @Test fun existingTwoArgumentApiStillWrapsWhileExplicitLineBreaksCanBePreserved() {
        val source = fixture()
        val input = "device freedom (github hawyn2018)"
        val wrapped = TextEngine.apply(source, mapOf("fastboot-unlocked" to input))
        val label = TextEngine.scan(wrapped.output).entries.single { it.id == "fastboot-unlocked" }
        assertTrue("Default API keeps its original automatic wrapping behavior", label.text.contains('\n'))
        assertEquals(input, label.text.replace('\n', ' '))

        val explicit = "device freedom\n(github hawyn2018)"
        val preserved = TextEngine.apply(source, mapOf("fastboot-unlocked" to explicit), autoWrapFastboot = false)
        val lines = TextEngine.scan(preserved.output).entries.single { it.id == "fastboot-unlocked" }
        assertEquals(explicit, lines.text)
        assertEquals(explicit.length + 1, TextA64.movImmediate(TextPe(preserved.output).word(0x1250), 3))
        assertEquals(0.toByte(), preserved.output[lines.fileOffset + explicit.length])
    }

    @Test fun singleLineModeRejectsAnUnprovenLargerBufferButAcceptsItsExactBoundary() {
        val source = fixture()
        put32(source, 0x1128, TextA64.movW(2, 32).toLong())
        val before = source.copyOf()
        val entry = TextEngine.scan(source).entries.single { it.id == "fastboot-unlocked" }
        assertEquals(31, entry.maxBytes)
        fails { TextEngine.apply(source, mapOf(entry.id to "device freedom (github hawyn2018)"), autoWrapFastboot = false) }
        assertArrayEquals(before, source)

        val boundary = "x".repeat(31)
        val result = TextEngine.apply(source, mapOf(entry.id to boundary), autoWrapFastboot = false)
        val label = TextEngine.scan(result.output).entries.single { it.id == entry.id }
        val image = TextPe(result.output)
        assertEquals(boundary, label.text)
        assertEquals(32, TextA64.movImmediate(image.word(0x1250), 3))
        assertEquals(32, TextA64.movImmediate(image.word(0x1244), 1))
        assertEquals(0.toByte(), result.output[label.fileOffset + 31])
        assertArrayEquals(before, source)
    }

    @Test fun editedTitleIsStillLocatedByItsRenderReference() {
        val source = fixture()
        val title = TextEngine.scan(source).entries.single { it.title == "Fastboot 标题" && it.editable }
        val first = TextEngine.apply(source, mapOf(title.id to "Different Title"))
        val rescanned = TextEngine.scan(first.output)
        assertEquals(1, rescanned.entries.count { it.title == "Fastboot 标题" })
        val found = rescanned.entries.single { it.id == title.id }
        assertEquals("Different Title", found.text)
        assertTrue(found.editable)
        val second = TextEngine.apply(first.output, mapOf(title.id to "Third title"))
        assertEquals("Third title", TextEngine.scan(second.output).entries.single { it.id == title.id }.text)
    }

    @Test fun fullResourceCapacityPreservesMetadataAndOtherEntries() {
        val source = fixture()
        val entry = TextEngine.scan(source).entries.single { it.text.contains("has been unlocked") }
        val text = "a".repeat(255)
        val result = TextEngine.apply(source, mapOf(entry.id to text))
        assertEquals(text, TextEngine.scan(result.output).entries.single { it.id == entry.id }.text)
        assertEquals(0.toByte(), result.output[entry.fileOffset + 255])
        assertArrayEquals(source.copyOfRange(entry.fileOffset + 256, entry.fileOffset + 280), result.output.copyOfRange(entry.fileOffset + 256, entry.fileOffset + 280))
        assertArrayEquals(source.copyOfRange(0x7330, 0x7448), result.output.copyOfRange(0x7330, 0x7448))
    }

    @Test fun rejectsOverflowAndUnsupportedCharactersWithoutTouchingSource() {
        val source = fixture(); val old = source.copyOf()
        val entry = TextEngine.scan(source).entries.single { it.text.contains("has been unlocked") }
        for (text in listOf("x".repeat(256), "中文", "tab\ttext", "del" + 127.toChar(), "nul\u0000text")) {
            fails { TextEngine.apply(source, mapOf(entry.id to text)) }
            assertArrayEquals(old, source)
        }
    }

    @Test fun clearWarningKeepsItsEditableResourceIdentity() {
        val source = fixture()
        val entry = TextEngine.scan(source).entries.single { it.text.contains("has been unlocked") }
        val first = TextEngine.apply(source, mapOf(entry.id to ""))
        val empty = TextEngine.scan(first.output).entries.single { it.id == entry.id }
        assertEquals("", empty.text)
        val second = TextEngine.apply(first.output, mapOf(entry.id to "Restored warning"))
        assertEquals("Restored warning", TextEngine.scan(second.output).entries.single { it.id == entry.id }.text)
    }

    @Test fun addsMappedSectionWhenOriginalTextPaddingIsOccupied() {
        val source = fixture().also { it[0x5fff] = 0x7f }
        val result = TextEngine.apply(source, mapOf("fastboot-unlocked" to "a longer unlocked device freedom label"))
        val image = TextPe(result.output)
        assertTrue(image.sections.any { it.name == ".eftxt" })
        val entry = TextEngine.scan(result.output).entries.single { it.id == "fastboot-unlocked" }
        assertTrue(entry.editable)
        assertEquals(entry.fileOffset, image.rvaToOffset(entry.rva, entry.text.length + 1))
    }

    @Test fun repeatedEditsGrowOneMappedStorageSectionWithoutMovingOriginalSections() {
        var output = fixture().also { it[0x5fff] = 0x7f }
        repeat(25) { index ->
            val text = "Editable passage $index " + "wide content ".repeat(5)
            output = TextEngine.apply(output, mapOf("fastboot-unlocked" to text)).output
            assertTrue(TextEngine.scan(output).entries.single { it.id == "fastboot-unlocked" }.editable)
        }
        val image = TextPe(output)
        val added = image.sections.filter { it.name == ".eftxt" }
        assertEquals(1, added.size)
        assertTrue(added.single().size > 4096)
        assertEquals(0x1000, image.sections.first { it.name == ".text" }.raw)
        assertEquals(0x6000, image.sections.first { it.name == ".data" }.raw)
    }

    @Test fun textCanGrowTheRecoveryTailWithNoAdditionalSectionHeader() {
        val initial = TextEngine.apply(fixture().also { it[0x5fff] = 0x7f },
            mapOf("fastboot-unlocked" to "First longer label needing extra storage")).output
        val initialPe = TextPe(initial)
        val tail = initialPe.sections.single { it.name == ".eftxt" }
        // Model a shared recovery/text tail whose existing contents must survive.
        initial.fill(0, tail.header, tail.header + 8)
        ".bfpol".toByteArray().copyInto(initial, tail.header)
        val protected = initial.copyOfRange(tail.raw, tail.raw + tail.size)
        var output = initial
        repeat(35) { index ->
            output = TextEngine.apply(output, mapOf("fastboot-unlocked" to
                "Custom label $index " + "wide content ".repeat(5))).output
        }
        val after = TextPe(output)
        assertEquals(initialPe.sections.size, after.sections.size)
        assertTrue(after.sections.single { it.name == ".bfpol" }.size > tail.size)
        // Later edits may consume zero padding, but must preserve every prior payload.
        protected.indices.filter { protected[it] != 0.toByte() }.forEach { offset ->
            assertEquals(protected[offset], output[tail.raw + offset])
        }
        assertEquals(initialPe.sections.first { it.name == ".text" }, after.sections.first { it.name == ".text" })
        assertEquals(initialPe.sections.first { it.name == ".data" }, after.sections.first { it.name == ".data" })
    }

    @Test fun multilineStubUsesLongestLineAndCanBeRecognizedAfterRelocation() {
        val source = fixture(); val image = TextPe(source)
        val context = FastbootTextDiscovery.discover(image)
        val payload = TextLayoutStubs.widthPayload(0x5000, context.fontCharZero!!, context.lineBudget)
        val copy = source.copyOf(); payload.copyInto(copy, 0x5000)
        assertEquals(context.fontCharZero to context.lineBudget, TextLayoutStubs.readWidth(TextPe(copy), 0x5010))
        assertTrue(context.wrap(image, "a".repeat(100)).contains('\n'))
        assertTrue(context.width(image, context.wrap(image, "a".repeat(100))) <= context.lineBudget)
    }

    @Test fun incompleteCapacityProofKeepsOriginalCapacity() {
        val source = fixture()
        put32(source, 0x1128, TextA64.movW(2, 32).toLong())
        val entry = TextEngine.scan(source).entries.single { it.id == "fastboot-unlocked" }
        assertEquals(31, entry.maxBytes)
        fails { TextEngine.apply(source, mapOf(entry.id to "a".repeat(32))) }
    }

    @Test fun findsHoistedCopyArgumentsAcrossFlagOnlyCompareAndConditionalBranch() {
        val source = hoistedCopyFixture(conflictingCapacity = false)
        val entry = TextEngine.scan(source).entries.single { it.id == "fastboot-unlocked" }
        assertTrue(entry.editable)
        assertEquals(255, entry.maxBytes)
        val edited = TextEngine.apply(source, mapOf(entry.id to "device freedom   github hawyn2018"))
        val next = TextEngine.scan(edited.output).entries.single { it.id == entry.id }
        assertTrue(next.editable)
        assertEquals(next.text.length + 1, TextA64.movImmediate(TextPe(edited.output).word(0x1250), 3))
        assertTrue(TextA64.movImmediate(TextPe(edited.output).word(0x1224), 1)!! >= next.text.length + 1)
    }

    @Test fun rejectsHoistedCapacityWhenIncomingPathsHaveDifferentDefinitions() {
        val source = hoistedCopyFixture(conflictingCapacity = true)
        assertFalse(TextEngine.scan(source).entries.any { it.id == "fastboot-unlocked" && it.editable })
        val clobbered = hoistedCopyFixture(conflictingCapacity = false)
        put32(clobbered, 0x1234, 0xd3407c00) // UBFM X0,X0,#0,#31 replaces its pointer.
        assertFalse(TextEngine.scan(clobbered).entries.any { it.id == "fastboot-unlocked" && it.editable })
    }

    private fun hoistedCopyFixture(conflictingCapacity: Boolean): ByteArray {
        val source = fixture()
        put32(source, 0x1220, 0x910ac3e0)
        put32(source, 0x1224, TextA64.movW(1, 32).toLong())
        put32(source, 0x1228, 0xd503201f)
        put32(source, 0x1234, 0x7a410920) // CCMP writes NZCV; its low bits are not X0.
        put32(source, 0x1238, 0x54000080) // B.EQ 0x1248
        put32(source, 0x123c, if (conflictingCapacity) TextA64.movW(1, 64).toLong() else TextA64.branch(0x123c, 0x1800, true).toLong())
        put32(source, 0x1240, TextA64.branch(0x1240, if (conflictingCapacity) 0x1248 else 0x123c).toLong())
        put32(source, 0x1244, TextA64.branch(0x1244, 0x123c).toLong())
        return source
    }

    private fun fails(block: () -> Unit) {
        try { block(); fail("Expected edit rejection") } catch (_: TextEditException) { }
    }

    private fun fixture(shift: Int = 0): ByteArray {
        val b = ByteArray(0xb000)
        fun word(pc: Int, value: Int) = put32(b, pc + shift, value.toLong())
        fun reference(pc: Int, register: Int, target: Int) {
            word(pc, TextA64.adrp(register, (pc + shift).toLong(), (target + shift).toLong()))
            word(pc + 4, TextA64.add(register, (target + shift).toLong()))
        }
        fun call(pc: Int, target: Int) = word(pc, TextA64.branch((pc + shift).toLong(), (target + shift).toLong(), true))
        fun text(at: Int, value: String) { (value.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)).copyInto(b, at + shift) }
        fun resource(at: Int, value: String, color: Int) {
            text(at, value)
            intArrayOf(if (value.contains("<!>")) 2 else 1, color, 1, 0, 0, 9).forEachIndexed { index, number -> put32(b, at + shift + 256 + index * 4, number.toLong()) }
        }
        b[0] = 0x4d; b[1] = 0x5a; put32(b, 0x3c, 0x80); put32(b, 0x80, 0x4550)
        put16(b, 0x84, 0xaa64); put16(b, 0x86, 2); put16(b, 0x94, 0xf0)
        val o = 0x98; put16(b, o, 0x20b); put32(b, o + 16, 0x1100L + shift)
        put32(b, o + 32, 0x1000); put32(b, o + 36, 0x1000); put32(b, o + 56, 0xb000); put32(b, o + 60, 0x1000)
        val h = o + 0xf0
        ".text".toByteArray().copyInto(b, h); put32(b, h + 8, 0x5000); put32(b, h + 12, 0x1000); put32(b, h + 16, 0x5000); put32(b, h + 20, 0x1000); put32(b, h + 36, 0x60000020)
        ".data".toByteArray().copyInto(b, h + 40); put32(b, h + 48, 0x5000); put32(b, h + 52, 0x6000); put32(b, h + 56, 0x5000); put32(b, h + 60, 0x6000); put32(b, h + 76, 0xc0000040)
        word(0x1100, 0xd503233f.toInt())
        word(0x1120, 0x910ac3e0.toInt()); word(0x1124, 0x2a1f03e1); word(0x1128, TextA64.movW(2, 256)); call(0x112c, 0x1a00)
        reference(0x1180, 0, 0x3300); call(0x1188, 0x2200)
        reference(0x1200, 0, 0x3300); call(0x1208, 0x2000)
        reference(0x1210, 0, 0x3340); call(0x1218, 0x2000)
        word(0x122c, 0x6f00e400); word(0x1230, 0xad1583e0.toInt())
        word(0x1240, 0x910ac3e0.toInt()); word(0x1244, TextA64.movW(1, 32)); reference(0x1248, 2, 0x3340)
        word(0x1250, TextA64.movW(3, "PHONE Unlocked".length + 1)); call(0x1254, 0x1800)
        word(0x1258, (0xb4000000L or (((0x12a0 - 0x1258) / 4).toLong() shl 5)).toInt())
        reference(0x1260, 9, 0x3400)
        word(0x12a0, TextA64.movW(0, 10)); call(0x12a4, 0x1900); word(0x12a8, 0x910ac3e0.toInt()); call(0x12ac, 0x2200)
        word(0x2000, 0xd503233f.toInt()); reference(0x2004, 22, 0x6500)
        word(0x2200, 0xd503233f.toInt())
        word(0x2204, TextA64.adrp(8, (0x2204 + shift).toLong(), (0x8810 + shift).toLong()))
        word(0x2208, (0xf9400108L or ((((0x8810 + shift) and 0xfff) / 8).toLong() shl 10)).toInt())
        word(0x220c, 0xb40002a8.toInt()); word(0x2210, TextA64.movW(11, 32)); word(0x2214, TextA64.movW(12, 53))
        word(0x2218, 0x7100297f); word(0x221c, 0x54000020)
        reference(0x2220, 12, 0x8800); word(0x2228, 0xb940058b.toInt()); word(0x222c, 0x0b09016d); word(0x2230, 0x2900359f); word(0x2234, 0xd65f03c0.toInt())
        text(0x3300, "FASTBOOT&RESCUE MODE"); text(0x3340, "PHONE Unlocked"); text(0x3400, "PHONE_UNLOCKED strncpy_s error\n")
        for (c in 32..126) b[0x6300 + shift + c * 16] = (5 + c % 11).toByte()
        resource(0x7100, "\n\n<!>", 2); resource(0x7218, "\n\n Your device has been unlocked and\n trust state\n", 0); resource(0x7330, "\n\n Press Power key to continue", 2)
        return b
    }
}
