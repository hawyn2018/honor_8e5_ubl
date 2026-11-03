package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PatchEngineTest {
    // These fixture coordinates are independent native reporter oracles.
    // Fresh calls stay native; the retired redirect and allocated stub are not
    // counted as active patches and must not reappear in a repeated import.
    private fun assertNativeReporterRetained(source:ByteArray,result:PatchResult,call:Long) {
        val native=PeImage.parse(PatchEngine.extract(source).output)
        val output=PeImage.parse(result.output)
        val word=requireNotNull(native.word(call))
        assertEquals(0x94000000.toInt(),word and 0xfc000000.toInt())
        assertEquals(word,requireNotNull(output.word(call)))
        val retired=setOf("AVB hash 错误的条件恢复调用","仅允许验签错误时跳过厂商恢复")
        assertTrue(result.patches.none{it.name in retired})
    }

    private fun workspace(): File {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "analysis/ablpatcher-fixtures/manifest.json").isFile }
        assumeTrue("Run the independent fixture generator for local firmware integration coverage", root != null)
        return root!!
    }
    @Test fun branchEncodingsRespectSignedOffsets() {
        val cases = listOf(0x1000L to 0x1004L, 0x2000L to 0x1000L, 0x1000L to 0x3000L)
        cases.forEach { (pc, target) -> assertEquals(target, A64.bTarget(pc, A64.branch(pc, target))) }
        assertEquals(0x110a8L, A64.cbzW0(0x11078, 0x34000180))
        assertEquals(0x110b0L, A64.tbnzW0Bit31(0x10f58, 0x37f80ac0))
        assertNull(A64.cbzW0(0x11078, 0x35000180)) // CBNZ has the opposite decision.
        assertNull(A64.tbnzW0Bit31(0x10f58, 0x36f80ac0)) // TBZ is a different condition.
    }

    @Test fun branchEncoderRejectsUnalignedAndOutOfRangeTargets() {
        assertThrows(PatchException::class.java) { A64.branch(0x1000, 0x1001) }
        assertThrows(PatchException::class.java) { A64.branch(0, 0x08000000) }
    }

    @Test fun malformedContainersProduceControlledErrors() {
        listOf(byteArrayOf(), byteArrayOf(0x7f, 0x45, 0x4c, 0x46), ByteArray(128), byteArrayOf(0x4d, 0x5a)).forEach {
            assertThrows(PatchException::class.java) { legacyEnginePatch(it) }
        }
    }

    @Test fun independentRelocationAndFailureFixtures() {
        val outcomes = EngineHarness.run(File(workspace(), "analysis/ablpatcher-fixtures/manifest.json"))
        assertTrue(outcomes.joinToString("\n") { "${it.name}: ${it.detail}" }, outcomes.all { it.passed })
        assertTrue(outcomes.size >= 19)
    }

    @Test fun absentCoreAndIncompleteCoreAreDifferentResults() {
        val root = workspace()
        val bytes = File(root, "analysis/ablpatcher-fixtures/files/stock-inner.fixture.pe").readBytes()
        val completeContext = "fastboot flash:rdmodev1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 1)
        val partial = bytes.copyOf()
        partial.occurrences(completeContext).forEach { at -> partial[at + completeContext.lastIndex] = 2 }
        val incomplete = assertThrows(PatchException::class.java) { legacyEnginePatch(partial) }
        assertEquals(PatchFailureKind.INCOMPLETE_CORE_FEATURE, incomplete.kind)
        val absent = bytes.copyOf()
        for (marker in listOf("fastboot flash:rdmodev1", "common_check_rdmod_v1_state")) {
            absent.occurrences(marker.toByteArray(Charsets.US_ASCII)).forEach { absent[it] = 0 }
        }
        val noCore = assertThrows(PatchException::class.java) { legacyEnginePatch(absent) }
        assertEquals(PatchFailureKind.NO_CORE_FEATURE, noCore.kind)
    }

    @Test fun ambiguousAndAlreadyPatchedInputsStayDistinct() {
        val root = workspace()
        val ambiguous = File(root, "analysis/ablpatcher-fixtures/files/ambiguous-clusters.fixture.pe").readBytes()
        assertEquals(PatchFailureKind.AMBIGUOUS_CORE_FEATURE,
            assertThrows(PatchException::class.java) { legacyEnginePatch(ambiguous) }.kind)
        val editedReference = File(root, "analysis/ablpatcher-fixtures/files/already-core.fixture.pe").readBytes()
        val upgraded = legacyEnginePatch(editedReference)
        assertTrue(upgraded.patches.take(4).all { it.alreadyApplied })
        assertTrue(legacyEnginePatch(upgraded.output).alreadyPatched)
    }

    @Test fun buildWithoutExternalLoaderStillPatchesThreeCoreFunctions() {
        val sourceFile = File(workspace(), "ablnew.img")
        assumeTrue("Additional firmware integration sample required", sourceFile.isFile)
        val source = sourceFile.readBytes()
        val unchanged = source.copyOf()
        val result = legacyEnginePatch(source)
        assertNativeReporterRetained(source,result,0x4ea30L)
        assertEquals(listOf(0x106acL, 0x10704L, 0x1076cL), result.patches.take(3).map { it.rva })
        assertTrue(result.notes.any { it.contains("已保留原名称") })
        assertArrayEquals(unchanged, source)
        // Independent native oracle for this additional firmware fixture.
        val special = result.patches.single { it.name == "special SDAM 更新事务采用原生不更新路径" }
        assertEquals(0x8670cL, special.rva)
        assertEquals("41 03 00 54", special.before)
        assertEquals("1a 00 00 14", special.after)
        val patched = PeImage.parse(result.output)
        assertEquals(A64.branch(0x8670cL, 0x86774L), patched.word(0x8670cL))
        val second = legacyEnginePatch(result.output)
        assertTrue(second.alreadyPatched)
        assertNativeReporterRetained(result.output,second,0x4ea30L)
        assertEquals(5, second.patches.size) // two retired report-suppression edits
        assertArrayEquals(result.output, second.output)
    }

    @Test fun unrecognizedExternalPartitionDoesNotGateCoreOrGuessRename() {
        val source = File(workspace(), "analysis/ablpatcher-fixtures/files/stock-inner.fixture.pe").readBytes()
        val name = "efisp\u0000".toByteArray(Charsets.UTF_16LE)
        val originalName = source.occurrences(name).single()
        source[originalName] = 'k'.code.toByte()
        val result = legacyEnginePatch(source)
        assertNativeReporterRetained(source,result,0x851f4L)
        assertEquals(4, result.patches.size) // two retired report-suppression edits
        assertTrue(result.notes.any { it.contains("已保留原名称") })
        assertArrayEquals(source.copyOfRange(originalName, originalName + name.size),
            result.output.copyOfRange(originalName, originalName + name.size))
        val allowed = result.patches.flatMap { patch -> (patch.fileOffset until patch.fileOffset + patch.after.split(' ').size).toList() }.toSet()
        assertTrue(source.indices.all { source[it] == result.output[it] || it in allowed })
    }

    @Test fun overwrittenC8MethodPointerDoesNotBecomeOptionalRenameTarget() {
        val source = File(workspace(), "analysis/ablpatcher-fixtures/files/stock-inner.fixture.pe").readBytes()
        // C8 is now an intermediate object pointer, then a different method at +58.
        source.putWord(0x11948, 0xf9402d29.toInt()) // LDR X9,[X9,#0x58]
        source.putWord(0x1194c, 0xd63f0120.toInt()) // BLR X9
        val result = legacyEnginePatch(source)
        assertNativeReporterRetained(source,result,0x851f4L)
        assertEquals(4, result.patches.size) // two retired report-suppression edits
        val originalName = source.occurrences("efisp\u0000".toByteArray(Charsets.UTF_16LE)).single()
        assertEquals('e'.code.toByte(), result.output[originalName])
    }

    @Test fun diagnosticWordingIsNotACoreDiscoveryGate() {
        val source = File(workspace(), "analysis/ablpatcher-fixtures/files/stock-inner.fixture.pe").readBytes()
        val message = "%a:deal fastboot write rdmode\n".toByteArray(Charsets.US_ASCII)
        val at = source.occurrences(message).single()
        "x".repeat(message.size).toByteArray(Charsets.US_ASCII).copyInto(source, at)
        val result = legacyEnginePatch(source)
        assertNativeReporterRetained(source,result,0x851f4L)
        assertEquals(5, result.patches.size) // two retired report-suppression edits
        assertEquals(0x10fb0L, result.patches[1].rva)
    }

    @Test fun firstAuthenticationPayloadIsOpaque() {
        val source = File(workspace(), "analysis/ablpatcher-fixtures/files/stock-inner.fixture.pe").readBytes()
        val payload = "0XEED6030008".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 1)
        val at = source.occurrences(payload).single()
        ByteArray(payload.size) { (it * 17 + 3).toByte() }.copyInto(source, at)
        val result = legacyEnginePatch(source)
        assertNativeReporterRetained(source,result,0x851f4L)
        assertEquals(5, result.patches.size) // two retired report-suppression edits
        assertEquals(listOf(0x10f58L, 0x10fb0L, 0x11078L), result.patches.take(3).map { it.rva })
    }
}
