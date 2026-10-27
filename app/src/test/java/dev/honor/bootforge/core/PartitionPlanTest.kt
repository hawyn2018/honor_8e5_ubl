package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PartitionPlanTest {
    private val diskGuid = "11111111-1111-1111-1111-111111111111"
    private val linuxType = "0FC63DAF-8483-4772-8E79-3D69D8477DE4"
    private val userdataGuid = "22222222-2222-2222-2222-222222222222"
    private val superGuid = "33333333-3333-3333-3333-333333333333"
    private val efispGuid = "44444444-4444-4444-4444-444444444444"

    private fun partition(number: Int, start: Long, end: Long, name: String, guid: String) =
        GptPartition(number, start, end, name, linuxType, guid, "8000000000000001")

    private fun snapshot(sector: Int = 4096): GptSnapshot = GptSnapshot(
        diskGuid, sector, 6, 249_423_839, 128,
        listOf(
            partition(1, 1024, 15_037_439, "super", superGuid),
            partition(65, 15_037_440, 249_423_839, "userdata", userdataGuid),
        ),
    )

    private fun invalid(snapshot: GptSnapshot): PartitionPlanningException =
        assertThrows(PartitionPlanningException::class.java) { PartitionPlanner.plan(snapshot, efispGuid) }

    @Test fun observed4096GeometryCreatesOnlyInRemovedUserdataTail() {
        val input = snapshot()
        val plan = PartitionPlanner.plan(input, efispGuid) as CreateEfispPlan
        assertEquals(249_407_455L, plan.userdata.endLba)
        assertEquals(249_407_456L, plan.efisp.startLba)
        assertEquals(249_423_839L, plan.efisp.endLba)
        assertEquals(67_108_864L, plan.shrinkBytes)
        assertEquals(2, plan.efisp.number)
        assertEquals(input.partitions[1], plan.originalUserdata)
        assertEquals(input.partitions[1].copy(endLba = plan.userdata.endLba), plan.userdata)
        assertEquals(input.partitions[0], plan.resultSnapshot.partitions.single { it.name == "super" })
        assertEquals(input.diskGuid, plan.resultSnapshot.diskGuid)
        assertEquals(input, plan.originalSnapshot)
        assertEquals(249_423_839L, input.partitions[1].endLba)
    }

    @Test fun sector512UsesLogicalLbasAndExactByteReduction() {
        val input = snapshot(512)
        val plan = PartitionPlanner.plan(input, efispGuid) as CreateEfispPlan
        assertEquals(input.partitions[1].endLba - 131_072, plan.userdata.endLba)
        assertEquals(131_072L, plan.efisp.endLba - plan.efisp.startLba + 1)
        assertEquals(PartitionPlanner.EFISP_BYTES, (plan.efisp.endLba - plan.efisp.startLba + 1) * 512)
    }

    @Test fun creationAppliesCommonMetadataWithoutImportingDeviceSpecificFlags() {
        assertEquals("efisp-common-v1", EfispProfile.ID)
        assertEquals("EF00", EfispProfile.TYPE_CODE)
        for (sector in listOf(512, 4096)) {
            val plan = PartitionPlanner.plan(snapshot(sector), efispGuid) as CreateEfispPlan
            assertEquals("efisp", plan.efisp.name)
            assertEquals("C12A7328-F81F-11D2-BA4B-00A0C93EC93B", plan.efisp.typeGuid)
            assertEquals("0000000000000000", plan.efisp.attributes)
            assertEquals(efispGuid, plan.efisp.uniqueGuid)
        }
    }

    @Test fun freshPlansGenerateIndependentRandomPartitionIdentities() {
        val input = snapshot()
        val first = PartitionPlanner.plan(input) as CreateEfispPlan
        val second = PartitionPlanner.plan(input) as CreateEfispPlan
        assertNotEquals(first.efisp.uniqueGuid, second.efisp.uniqueGuid)
        for (plan in listOf(first, second)) {
            val identity = UUID.fromString(plan.efisp.uniqueGuid)
            assertEquals(4, identity.version())
            assertEquals(2, identity.variant())
            assertFalse(input.partitions.any { it.uniqueGuid.equals(plan.efisp.uniqueGuid, ignoreCase = true) })
        }
    }

    @Test fun firstUnusedEntryIsChosenFromUnsortedNumbers() {
        val input = snapshot().copy(partitions = listOf(
            partition(3, 400, 600, "boot", "55555555-5555-5555-5555-555555555555"),
            snapshot().partitions[1], snapshot().partitions[0],
        ))
        assertEquals(2, (PartitionPlanner.plan(input, efispGuid) as CreateEfispPlan).efisp.number)
    }

    @Test fun existingEfispAtFrontIsReusedWithoutChangingAnyGeometry() {
        val input = snapshot().copy(partitions = snapshot().partitions + GptPartition(
            66, 200, 16_583, "efisp", PartitionPlanner.EFI_SYSTEM_TYPE_GUID.lowercase(), efispGuid,
            "0000000000000000",
        ))
        // Make a real front extent for efisp without overlapping super.
        val valid = input.copy(partitions = input.partitions.map {
            if (it.name == "super") it.copy(startLba = 20_000) else it
        })
        val plan = PartitionPlanner.plan(valid) as ReusePlan
        assertEquals(valid, plan.originalSnapshot)
        assertSame(plan.originalSnapshot, plan.resultSnapshot)
        assertEquals(valid.partitions.single { it.name == "userdata" }, plan.userdata)
        assertEquals(valid.partitions, plan.resultSnapshot.partitions)
    }

    @Test fun existingEfispAfterUserdataAlsoReusesWithoutShrinking() {
        val input = snapshot().let { original -> original.copy(partitions = original.partitions.map {
            if (it.name == "userdata") it.copy(endLba = 249_400_000) else it
        } + GptPartition(66, 249_400_001, 249_416_384, "efisp", PartitionPlanner.EFI_SYSTEM_TYPE_GUID,
            efispGuid, "0000000000000000")) }
        assertTrue(PartitionPlanner.plan(input) is ReusePlan)
    }

    @Test fun reusePreservesExistingBit60AndEveryOtherEntryField() {
        val base = snapshot().copy(lastUsable = 249_444_346)
        val existing = GptPartition(66, 249_423_840, 249_440_223, "efisp",
            PartitionPlanner.EFI_SYSTEM_TYPE_GUID.lowercase(), efispGuid, "1000000000000000")
        val input = base.copy(partitions = base.partitions + existing)
        val plan = PartitionPlanner.plan(input) as ReusePlan
        assertEquals(existing, plan.efisp)
        assertEquals("1000000000000000", plan.efisp.attributes)
        assertEquals(input, plan.resultSnapshot)
        assertSame(plan.originalSnapshot, plan.resultSnapshot)
    }

    @Test fun existingEfispFlexibleSizeIsReusedAndWrongTypeIsRejected() {
        val base = snapshot().copy(lastUsable = 249_444_346)
        val efisp = GptPartition(66, 249_423_840, 249_440_223, "efisp",
            PartitionPlanner.EFI_SYSTEM_TYPE_GUID, efispGuid, "0000000000000000")
        assertTrue(PartitionPlanner.plan(base.copy(partitions = base.partitions + efisp.copy(endLba = efisp.endLba - 1))) is ReusePlan)
        invalid(base.copy(partitions = base.partitions + efisp.copy(typeGuid = linuxType)))
    }

    @Test fun overlapIncludingBoundaryTouchIsRejected() {
        val base = snapshot()
        invalid(base.copy(partitions = base.partitions.map {
            if (it.name == "userdata") it.copy(startLba = base.partitions[0].endLba) else it
        }))
    }

    @Test fun fullEntryTableIsRejectedForCreationButAllowedForReuse() {
        val base = snapshot().copy(entryCapacity = 2, partitions = snapshot().partitions.map {
            if (it.name == "userdata") it.copy(number = 2) else it
        })
        assertTrue(invalid(base).message.orEmpty().contains("unused"))
        val reused = base.copy(lastUsable = 249_444_346, entryCapacity = 3, partitions = base.partitions + GptPartition(
            3, 249_423_840, 249_440_223, "efisp", PartitionPlanner.EFI_SYSTEM_TYPE_GUID, efispGuid,
            "0000000000000000",
        ))
        assertTrue(PartitionPlanner.plan(reused) is ReusePlan)
    }

    @Test fun middleUserdataTailCreatesEfispWithoutChangingFollowingPartitions() {
        for (sector in listOf(512, 4096)) {
            val base = snapshot(sector).copy(lastUsable = 249_444_346)
            val following = listOf(
                partition(66, 249_423_840, 249_424_000, "tail", "55555555-5555-5555-5555-555555555555"),
                partition(67, 249_430_000, base.lastUsable, "last", "66666666-6666-6666-6666-666666666666"),
            )
            // The first later partition starts immediately after userdata.
            // There is no free space between them to borrow for efisp.
            val input = base.copy(partitions = following.reversed() + base.partitions)
            val plan = PartitionPlanner.plan(input, efispGuid) as CreateEfispPlan
            val originalUserdata = base.partitions.single { it.name == "userdata" }
            assertEquals(originalUserdata.endLba, plan.efisp.endLba)
            assertEquals(plan.userdata.endLba + 1, plan.efisp.startLba)
            assertEquals(PartitionPlanner.EFISP_BYTES,
                (plan.efisp.endLba - plan.efisp.startLba + 1) * sector)
            assertEquals(following.first().startLba - 1, plan.efisp.endLba)
            assertEquals(input.partitions.filter { it.name != "userdata" },
                plan.resultSnapshot.partitions.filter { it.name !in setOf("userdata", "efisp") })
            assertEquals(input.copy(partitions = plan.resultSnapshot.partitions), plan.resultSnapshot)
            assertEquals(input, plan.originalSnapshot)
        }
    }

    @Test fun creationDoesNotRequireASuperPartition() {
        val base = snapshot()
        val input = base.copy(partitions = base.partitions.filter { it.name == "userdata" })
        val plan = PartitionPlanner.plan(input, efispGuid) as CreateEfispPlan
        assertEquals(2, plan.resultSnapshot.partitions.size)
        assertEquals(setOf("userdata", "efisp"), plan.resultSnapshot.partitions.map { it.name }.toSet())
        assertEquals(input.partitions.single().copy(endLba = plan.userdata.endLba), plan.userdata)
        assertEquals(PartitionPlanner.EFISP_BYTES, plan.shrinkBytes)
        assertEquals(1, plan.efisp.number)
    }

    @Test fun unsupportedOrUnknownLogicalSectorIsRejected() {
        listOf(0, -512, 1024, 2048, 8192).forEach { invalid(snapshot(it)) }
    }

    @Test fun overflowingExclusiveEndAndByteOffsetAreRejected() {
        assertTrue(invalid(snapshot().copy(lastUsable = Long.MAX_VALUE)).cause is ArithmeticException)
        assertTrue(invalid(snapshot().copy(lastUsable = Long.MAX_VALUE / 4096)).cause is ArithmeticException)
    }

    @Test fun malformedOrZeroGuidsAndDuplicateIdentityAreRejected() {
        invalid(snapshot().copy(diskGuid = "invalid"))
        invalid(snapshot().copy(diskGuid = "00000000-0000-0000-0000-000000000000"))
        val base = snapshot()
        invalid(base.copy(partitions = base.partitions.map {
            if (it.name == "userdata") it.copy(uniqueGuid = superGuid) else it
        }))
        invalid(base.copy(partitions = base.partitions.map {
            if (it.name == "userdata") it.copy(typeGuid = "EF00") else it
        }))
        assertThrows(PartitionPlanningException::class.java) { PartitionPlanner.plan(base, userdataGuid) }
        assertThrows(PartitionPlanningException::class.java) { PartitionPlanner.plan(base, "invalid") }
    }

    @Test fun invalidRangesAttributesAndDuplicateNumbersAreRejected() {
        val base = snapshot()
        invalid(base.copy(firstUsable = 0))
        invalid(base.copy(firstUsable = base.lastUsable + 1))
        invalid(base.copy(entryCapacity = 0))
        listOf(
            base.partitions[1].copy(startLba = 1),
            base.partitions[1].copy(endLba = base.lastUsable + 1),
            base.partitions[1].copy(endLba = base.partitions[1].startLba - 1),
            base.partitions[1].copy(attributes = "0"),
            base.partitions[1].copy(number = 1),
            base.partitions[1].copy(number = 129),
            base.partitions[1].copy(name = "bad\u0000name"),
            base.partitions[1].copy(name = "x".repeat(37)),
        ).forEach { invalid(base.copy(partitions = listOf(base.partitions[0], it))) }
    }

    @Test fun emptyAndRepeatedUnrelatedLabelsArePreservedByEntryNumber() {
        val base = snapshot()
        val otherEntries = listOf(
            partition(66, 10, 20, "", "55555555-5555-5555-5555-555555555555"),
            partition(67, 30, 40, "", "66666666-6666-6666-6666-666666666666"),
            partition(68, 50, 60, "same label", "77777777-7777-7777-7777-777777777777"),
            partition(69, 70, 80, "same label", "88888888-8888-8888-8888-888888888888"),
        )
        val input = base.copy(partitions = base.partitions + otherEntries)
        val plan = PartitionPlanner.plan(input, efispGuid) as CreateEfispPlan
        assertEquals(input.partitions.filter { it.name != "userdata" },
            plan.resultSnapshot.partitions.filter { it.name !in setOf("userdata", "efisp") })
        otherEntries.forEach { original ->
            assertEquals(original, plan.resultSnapshot.partitions.single { it.number == original.number })
        }
    }

    @Test fun duplicateCriticalUserdataOrEfispLabelsAreRejected() {
        val base = snapshot()
        val duplicateUserdata = partition(66, 10, 20, "userdata", "55555555-5555-5555-5555-555555555555")
        assertTrue(invalid(base.copy(partitions = base.partitions + duplicateUserdata))
            .message.orEmpty().contains("unambiguous"))
        val duplicateEfisp = listOf(
            partition(66, 10, 20, "efisp", "55555555-5555-5555-5555-555555555555"),
            partition(67, 30, 40, "efisp", "66666666-6666-6666-6666-666666666666"),
        )
        assertTrue(invalid(base.copy(partitions = base.partitions + duplicateEfisp))
            .message.orEmpty().contains("unambiguous"))
    }

    @Test fun labelLimitCountsUtf16CodeUnitsAndPreservesCaseAndControlCharacters() {
        val base = snapshot()
        val labels = listOf("SUPER", "  ", "line\nbreak", "\uD83D\uDE00".repeat(18))
        labels.forEach { label ->
            val other = partition(66, 10, 20, label, "55555555-5555-5555-5555-555555555555")
            val input = base.copy(partitions = base.partitions + other)
            val plan = PartitionPlanner.plan(input, efispGuid)
            assertEquals(other, plan.resultSnapshot.partitions.single { it.number == 66 })
        }
        val tooLong = partition(66, 10, 20, "\uD83D\uDE00".repeat(18) + "a", "55555555-5555-5555-5555-555555555555")
        assertTrue(invalid(base.copy(partitions = base.partitions + tooLong))
            .message.orEmpty().contains("UTF-16"))
    }

    @Test fun userdataMustRetainAtLeastOneLogicalSector() {
        val base = snapshot()
        val userdata = base.partitions[1]
        invalid(base.copy(lastUsable = userdata.startLba + 16_384 - 1, partitions = listOf(base.partitions[0],
            userdata.copy(endLba = userdata.startLba + 16_384 - 1))))
        val positive = base.copy(lastUsable = userdata.startLba + 16_384, partitions = listOf(base.partitions[0],
            userdata.copy(endLba = userdata.startLba + 16_384)))
        val plan = PartitionPlanner.plan(positive, efispGuid) as CreateEfispPlan
        assertEquals(plan.userdata.startLba, plan.userdata.endLba)
    }

    @Test fun missingUserdataIsRejected() {
        invalid(snapshot().copy(partitions = snapshot().partitions.filter { it.name != "userdata" }))
    }
    @Test fun measuredFilesystemUsesFull68MiBTailAndPreservesOtherEntries() {
        for (sector in listOf(512, 4096)) {
            val input = snapshot(sector)
            val old = input.partitions.single { it.name == "userdata" }
            val oldBytes = (old.endLba - old.startLba + 1) * sector
            val released = 68L * 1024 * 1024
            val plan = PartitionPlanner.plan(input, efispGuid, oldBytes - released, 2_015_232) as CreateEfispPlan
            assertEquals(released, plan.shrinkBytes)
            assertEquals(old.startLba, plan.userdata.startLba)
            assertEquals(plan.userdata.endLba + 1, plan.efisp.startLba)
            assertEquals(old.endLba, plan.efisp.endLba)
            assertEquals(input.partitions[0], plan.resultSnapshot.partitions.single { it.name == "super" })
        }
    }

    @Test fun measuredFilesystemRejectsUnalignedBoundaryOrImageLargerThanTail() {
        val input = snapshot()
        val old = input.partitions.single { it.name == "userdata" }
        val oldBytes = (old.endLba - old.startLba + 1) * 4096
        assertThrows(PartitionPlanningException::class.java) { PartitionPlanner.plan(input, efispGuid, oldBytes - 1) }
        assertThrows(PartitionPlanningException::class.java) { PartitionPlanner.plan(input, efispGuid, oldBytes - 4096, 8192) }
        assertThrows(PartitionPlanningException::class.java) { PartitionPlanner.plan(input, efispGuid, oldBytes) }
    }

    @Test fun defaultPlanUsesExistingExternalTailBeforeProposingAnyUserdataReduction() {
        for (sector in listOf(512, 4096)) {
            val original = snapshot(sector)
            val old = original.partitions.single { it.name == "userdata" }
            for (gapBytes in listOf(16L, 64L, 96L).map { it * 1024 * 1024 }) {
                val input = original.copy(lastUsable = old.endLba + gapBytes / sector)
                val plan = PartitionPlanner.plan(input, efispGuid) as CreateEfispPlan
                val reduction = maxOf(0L, PartitionPlanner.EFISP_BYTES - gapBytes)
                assertEquals(reduction, plan.shrinkBytes)
                assertEquals(-reduction, plan.userdataSizeChangeBytes)
                assertEquals(maxOf(gapBytes, PartitionPlanner.EFISP_BYTES), plan.efispBytes)
                assertEquals(input.lastUsable, plan.efisp.endLba)
                assertEquals(old.endLba - reduction / sector, plan.userdata.endLba)
                assertEquals(plan.userdata.endLba + 1, plan.efisp.startLba)
            }
        }
    }

    @Test fun measuredFilesystemCanShrinkGrowOrPreserveUserdataWithinOneContinuousExtent() {
        for (sector in listOf(512, 4096)) {
            val original = snapshot(sector)
            val old = original.partitions.single { it.name == "userdata" }
            val gapBytes = 128L * 1024 * 1024
            val input = original.copy(lastUsable = old.endLba + gapBytes / sector)
            val oldBytes = (old.endLba - old.startLba + 1) * sector
            for (change in listOf(-68L, 0L, 64L).map { it * 1024 * 1024 }) {
                val plan = PartitionPlanner.plan(input, efispGuid, oldBytes + change, 2_015_232) as CreateEfispPlan
                assertEquals(change, plan.userdataSizeChangeBytes)
                assertEquals(-change, plan.shrinkBytes)
                assertEquals(gapBytes - change, plan.efispBytes)
                assertEquals(old.endLba + change / sector, plan.userdata.endLba)
                assertEquals(input.lastUsable, plan.efisp.endLba)
                assertEquals(old.copy(endLba = plan.userdata.endLba), plan.userdata)
                assertEquals(input.partitions.filter { it.number != old.number },
                    plan.resultSnapshot.partitions.filter { it.number !in setOf(old.number, plan.efisp.number) })
            }
        }
    }

    @Test fun measuredFilesystemAndExternalGapAreCombinedForEfispCapacity() {
        val original = snapshot()
        val old = original.partitions.single { it.name == "userdata" }
        val sector = original.logicalSectorBytes
        val oldBytes = (old.endLba - old.startLba + 1) * sector
        val gapBytes = 40L * 1024 * 1024
        val input = original.copy(lastUsable = old.endLba + gapBytes / sector)
        val plan = PartitionPlanner.plan(input, efispGuid, oldBytes - 28L * 1024 * 1024, 2_015_232) as CreateEfispPlan
        assertEquals(68L * 1024 * 1024, plan.efispBytes)
        assertEquals(28L * 1024 * 1024, plan.shrinkBytes)
        assertEquals(input.lastUsable, plan.efisp.endLba)
    }

    @Test fun followingPhysicalPartitionBoundsTheTailRegardlessOfEntryNumbersAndOtherHoles() {
        val original = snapshot()
        val old = original.partitions.single { it.name == "userdata" }
        val oldBytes = (old.endLba - old.startLba + 1) * 4096
        val contiguousGap = 80L * 1024 * 1024
        val following = partition(2, old.endLba + contiguousGap / 4096 + 1,
            old.endLba + contiguousGap / 4096 + 10, "tail", "55555555-5555-5555-5555-555555555555")
        val input = original.copy(lastUsable = following.endLba + 1_000_000,
            partitions = listOf(following) + original.partitions.reversed())
        val plan = PartitionPlanner.plan(input, efispGuid, oldBytes, 2_015_232) as CreateEfispPlan
        assertEquals(old, plan.userdata)
        assertEquals(following.startLba - 1, plan.efisp.endLba)
        assertEquals(contiguousGap, plan.efispBytes)
        assertEquals(following, plan.resultSnapshot.partitions.single { it.number == following.number })
        assertThrows(PartitionPlanningException::class.java) {
            PartitionPlanner.plan(input, efispGuid, oldBytes + contiguousGap)
        }
        assertThrows(PartitionPlanningException::class.java) {
            PartitionPlanner.plan(input, efispGuid, oldBytes + contiguousGap + 4096)
        }
    }

    @Test fun exactContiguousImageFitIsAcceptedButOneSectorTooLargeIsRejected() {
        val original = snapshot()
        val old = original.partitions.single { it.name == "userdata" }
        val oldBytes = (old.endLba - old.startLba + 1) * 4096
        val gapBytes = 2L * 1024 * 1024
        val input = original.copy(lastUsable = old.endLba + gapBytes / 4096)
        val plan = PartitionPlanner.plan(input, efispGuid, oldBytes, gapBytes) as CreateEfispPlan
        assertEquals(gapBytes, plan.efispBytes)
        assertThrows(PartitionPlanningException::class.java) {
            PartitionPlanner.plan(input, efispGuid, oldBytes, gapBytes + 4096)
        }
    }

}
