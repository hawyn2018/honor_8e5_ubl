package net.hawyn.ablefisp.io

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class RootAblExtractorTest {
    private fun metadata(
        suffix: String = "_b",
        slot: String = "b",
        bootctl: String = "1",
        partitions: String = "PART|abl_a|/dev/block/by-name/abl_a|/dev/block/sde1|4\nPART|abl_b|/dev/block/by-name/abl_b|/dev/block/sde2|4",
    ) = "ROOT=0\nSLOT_SUFFIX=$suffix\nSLOT=$slot\nBOOTCTL=$bootctl\n$partitions\n"

    private fun select(text: String) = AblSourceSelection.parse(text).select()

    @Test fun selectsCurrentBWithoutAssumingA() {
        val result = select(metadata())
        assertEquals("_b", result.slotSuffix)
        assertEquals("abl_b", result.partition.name)
        assertEquals("/dev/block/sde2", result.partition.resolvedPath)
    }

    @Test fun supportsBootctlFallback() {
        assertEquals("_b", select(metadata(suffix = "", slot = "")).slotSuffix)
    }

    @Test fun conflictingSlotEvidenceStopsSelection() {
        fails { select(metadata(slot = "a")) }
        fails { select(metadata(bootctl = "0")) }
    }

    @Test fun unknownSlotDoesNotChooseOneOfTwoPartitions() {
        fails { select(metadata(suffix = "", slot = "", bootctl = "")) }
    }

    @Test fun unknownSlotEncodingDoesNotBecomeSharedAbl() {
        fails { select(metadata(suffix = "_c", partitions = "PART|abl|/dev/block/by-name/abl|/dev/block/sde1|4")) }
    }

    @Test fun acceptsGenuinelyUnslottedAbl() {
        val result = select(metadata(suffix = "", slot = "", bootctl = "", partitions = "PART|abl|/dev/block/by-name/abl|/dev/block/sde1|4"))
        assertNull(result.slotSuffix)
        assertEquals("abl", result.partition.name)
    }

    @Test fun acceptsSharedAblWhenAndroidHasSlotsAndAblDoesNot() {
        val result = select(metadata(partitions = "PART|abl|/dev/block/by-name/abl|/dev/block/sde1|4"))
        assertNull(result.slotSuffix)
        assertEquals("abl", result.partition.name)
    }

    @Test fun missingCurrentPartitionDoesNotUseOtherSlotOrSharedAlias() {
        fails { select(metadata(partitions = "PART|abl_a|/dev/block/by-name/abl_a|/dev/block/sde1|4\nPART|abl|/dev/block/by-name/abl|/dev/block/sde1|4")) }
    }

    @Test fun equivalentByNameAliasesAreDeduplicated() {
        val text = metadata(partitions = "PART|abl_b|/dev/block/by-name/abl_b|/dev/block/sde2|4\nPART|abl_b|/dev/block/bootdevice/by-name/abl_b|/dev/block/sde2|4")
        assertEquals("/dev/block/sde2", select(text).partition.resolvedPath)
    }

    @Test fun differentBlockTargetsForSameSlotAreRejected() {
        fails { select(metadata(partitions = "PART|abl_b|/dev/block/by-name/abl_b|/dev/block/sde2|4\nPART|abl_b|/dev/block/bootdevice/by-name/abl_b|/dev/block/sde3|4")) }
    }

    @Test fun malformedPathsAndOversizedPartitionsAreRejected() {
        fails { select(metadata(partitions = "PART|abl_b|/dev/block/by-name/abl_b|/dev/block/../data/private|4")) }
        fails { select(metadata(partitions = "PART|abl_b|/dev/block/by-name/abl_b|/data/private|4")) }
        fails { select(metadata(partitions = "PART|abl_b|/dev/block/by-name/abl_b|/dev/block/sde2|67108865")) }
        fails { select(metadata(partitions = "PART|abl_b|/dev/block/by-name/abl_b|/dev/block/sde2|0")) }
    }

    @Test fun duplicateOrUnprivilegedMetadataIsRejected() {
        fails { select(metadata() + "ROOT=0\n") }
        fails { select(metadata().replace("ROOT=0", "ROOT=2000")) }
    }

    @Test fun extractUsesFreshRootProbeAndReturnsExactBinaryAndHash() {
        val binary = byteArrayOf(0x7f, 0x45, 0, -1)
        val commands = mutableListOf<String>()
        val extractor = RootAblExtractor { command, maxBytes, timeout ->
            commands += command
            assertTrue(timeout > 0)
            if (commands.size == 1) {
                assertEquals(64 * 1024, maxBytes)
                RootCommandResult(0, metadata().toByteArray(), "permission notice")
            } else {
                assertTrue(command.contains("[ -b"))
                assertTrue(command.contains("exec cat"))
                assertTrue(command.contains("/dev/block/sde2"))
                assertFalse(command.contains("of="))
                RootCommandResult(0, binary, "binary stdout is separate")
            }
        }
        val output = extractor.extractCurrentAbl()
        assertArrayEquals(binary, output.bytes)
        assertEquals(ImageIo.sha256(binary), output.sha256)
        assertEquals("abl_b.img", output.sourceName)
        assertEquals(2, commands.size)
    }

    @Test fun shortOrFailedReadDoesNotReturnAnExtraction() {
        var calls = 0
        val short = RootAblExtractor { _, _, _ ->
            if (++calls == 1) RootCommandResult(0, metadata().toByteArray(), "")
            else RootCommandResult(0, byteArrayOf(1), "")
        }
        fails { short.extractCurrentAbl() }
        val denied = RootAblExtractor { _, _, _ -> RootCommandResult(60, byteArrayOf(), "denied") }
        fails { denied.extractCurrentAbl() }
        assertFalse(denied.checkRoot().available)
    }

    @Test fun rootStatusRequiresUidZeroOnStdout() {
        val granted = RootAblExtractor { _, _, _ -> RootCommandResult(0, "0\n".toByteArray(), "su notice") }
        assertTrue(granted.checkRoot().available)
        val shell = RootAblExtractor { _, _, _ -> RootCommandResult(0, "2000\n".toByteArray(), "") }
        assertFalse(shell.checkRoot().available)
    }

    @Test fun boundedImportAcceptsExactLimitAndRejectsExtraByte() {
        assertArrayEquals(byteArrayOf(1, 2, 3), ImageIo.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3))
        fails { ImageIo.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 3) }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ImageIo.sha256(byteArrayOf()))
    }

    private fun fails(block: () -> Unit) {
        try {
            block()
            fail("Expected rejected extraction")
        } catch (_: AblReadException) {
            // Expected: no ambiguous or incomplete result is delivered to the patch engine.
        }
    }
}
