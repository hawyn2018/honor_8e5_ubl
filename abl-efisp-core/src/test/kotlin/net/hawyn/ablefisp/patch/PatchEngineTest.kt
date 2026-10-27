package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PatchEngineTest {
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
            assertThrows(PatchException::class.java) { PatchEngine.analyzeAndPatch(it) }
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
        val incomplete = assertThrows(PatchException::class.java) { PatchEngine.analyzeAndPatch(partial) }
        assertEquals(PatchFailureKind.INCOMPLETE_CORE_FEATURE, incomplete.kind)
        val absent = bytes.copyOf()
        for (marker in listOf("fastboot flash:rdmodev1", "common_check_rdmod_v1_state")) {
            absent.occurrences(marker.toByteArray(Charsets.US_ASCII)).forEach { absent[it] = 0 }
        }
        val noCore = assertThrows(PatchException::class.java) { PatchEngine.analyzeAndPatch(absent) }
        assertEquals(PatchFailureKind.NO_CORE_FEATURE, noCore.kind)
    }

    @Test fun ambiguousAndAlreadyPatchedInputsStayDistinct() {
        val root = workspace()
        val ambiguous = File(root, "analysis/ablpatcher-fixtures/files/ambiguous-clusters.fixture.pe").readBytes()
        assertEquals(PatchFailureKind.AMBIGUOUS_CORE_FEATURE,
            assertThrows(PatchException::class.java) { PatchEngine.analyzeAndPatch(ambiguous) }.kind)
        val editedReference = File(root, "analysis/ablpatcher-fixtures/files/already-core.fixture.pe").readBytes()
        val upgraded = PatchEngine.analyzeAndPatch(editedReference)
        assertTrue(upgraded.patches.take(4).all { it.alreadyApplied })
        assertTrue(PatchEngine.analyzeAndPatch(upgraded.output).alreadyPatched)
    }

    @Test fun buildWithoutExternalLoaderStillPatchesThreeCoreFunctions() {
        val sourceFile = File(workspace(), "ablnew.img")
        assumeTrue("Additional firmware integration sample required", sourceFile.isFile)
        val source = sourceFile.readBytes()
        val unchanged = source.copyOf()
        val result = PatchEngine.analyzeAndPatch(source)
        assertEquals(listOf(0x106acL, 0x10704L, 0x1076cL), result.patches.take(3).map { it.rva })
        assertTrue(result.notes.any { it.contains("已保留原名称") })
        assertArrayEquals(unchanged, source)
        val second = PatchEngine.analyzeAndPatch(result.output)
        assertTrue(second.alreadyPatched)
        assertEquals(6, second.patches.size)
        assertArrayEquals(result.output, second.output)
    }

    @Test fun unrecognizedExternalPartitionDoesNotGateCoreOrGuessRename() {
        val source = File(workspace(), "analysis/ablpatcher-fixtures/files/stock-inner.fixture.pe").readBytes()
        val name = "efisp\u0000".toByteArray(Charsets.UTF_16LE)
        val originalName = source.occurrences(name).single()
        source[originalName] = 'k'.code.toByte()
        val result = PatchEngine.analyzeAndPatch(source)
        assertEquals(6, result.patches.size)
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
        val result = PatchEngine.analyzeAndPatch(source)
        assertEquals(6, result.patches.size)
        val originalName = source.occurrences("efisp\u0000".toByteArray(Charsets.UTF_16LE)).single()
        assertEquals('e'.code.toByte(), result.output[originalName])
    }

    @Test fun diagnosticWordingIsNotACoreDiscoveryGate() {
        val source = File(workspace(), "analysis/ablpatcher-fixtures/files/stock-inner.fixture.pe").readBytes()
        val message = "%a:deal fastboot write rdmode\n".toByteArray(Charsets.US_ASCII)
        val at = source.occurrences(message).single()
        "x".repeat(message.size).toByteArray(Charsets.US_ASCII).copyInto(source, at)
        val result = PatchEngine.analyzeAndPatch(source)
        assertEquals(7, result.patches.size)
        assertEquals(0x10fb0L, result.patches[1].rva)
    }

    @Test fun firstAuthenticationPayloadIsOpaque() {
        val source = File(workspace(), "analysis/ablpatcher-fixtures/files/stock-inner.fixture.pe").readBytes()
        val payload = "0XEED6030008".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 1)
        val at = source.occurrences(payload).single()
        ByteArray(payload.size) { (it * 17 + 3).toByte() }.copyInto(source, at)
        val result = PatchEngine.analyzeAndPatch(source)
        assertEquals(7, result.patches.size)
        assertEquals(listOf(0x10f58L, 0x10fb0L, 0x11078L), result.patches.take(3).map { it.rva })
    }
}
