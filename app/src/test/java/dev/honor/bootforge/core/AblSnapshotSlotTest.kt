package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test

class AblSnapshotSlotTest {
    @Test fun sourceSlotsResolveFromFreshRunningSlotEvidence() {
        listOf("a", "_a").forEach { slot ->
            assertEquals("a", ablSnapshotSourceSlot(slot, false))
            assertEquals("b", ablSnapshotSourceSlot(slot, true))
        }
        listOf("b", "_b").forEach { slot ->
            assertEquals("b", ablSnapshotSourceSlot(slot, false))
            assertEquals("a", ablSnapshotSourceSlot(slot, true))
        }
    }

    @Test fun unknownOrConflictingSlotTextStopsSourceSelection() {
        listOf("", "unknown", "a,b", "A", "_c", "_a ", "b\na", "a; reboot").forEach { slot ->
            listOf(false, true).forEach { other ->
                assertThrows(IllegalArgumentException::class.java) { ablSnapshotSourceSlot(slot, other) }
            }
        }
    }

    @Test fun oppositeSlotEfispSourceIsIndependentOfTheAblWriteDestination() {
        AblWriteTarget.entries.forEach { target ->
            val selected = UnlockRequest(mode = UnlockMode.CUSTOM, efispSource = EfispSource.OTHER_SLOT_ABL,
                ablTarget = target).resolved()
            assertEquals(EfispSource.OTHER_SLOT_ABL, selected.efispSource)
            assertEquals(target, selected.ablTarget)
            assertEquals("a", ablSnapshotSourceSlot("b", selected.efispSource == EfispSource.OTHER_SLOT_ABL))
        }
    }

    @Test fun oppositeSourceRoundTripsAndCannotMatchCurrentSourceBinding() {
        val selected = UnlockRequest(mode = UnlockMode.CUSTOM, efispSource = EfispSource.OTHER_SLOT_ABL,
            ablTarget = AblWriteTarget.OTHER)
        assertEquals(selected, UnlockRequest.decode(selected.encode()))
        val binding = mapOf("REQUEST_MODE" to "CUSTOM", "EFI_SOURCE" to "OTHER_SLOT_ABL",
            "PATCH_MODE" to "FAKE_LOCK", "ABL_WRITE_TARGET" to "OTHER")
        assertTrue(selected.matchesPayloadBinding(binding))
        assertFalse(selected.copy(efispSource = EfispSource.CURRENT_SLOT_ABL).matchesPayloadBinding(binding))
    }

    @Test fun automaticModesRestoreCurrentSlotSourceWithoutMutatingCustomChoices() {
        val custom = UnlockRequest(mode = UnlockMode.CUSTOM, efispSource = EfispSource.OTHER_SLOT_ABL,
            ablTarget = AblWriteTarget.BOTH)
        listOf(UnlockMode.FAKE_LOCK, UnlockMode.DIRECT).forEach { mode ->
            val automatic = custom.copy(mode = mode).resolved()
            assertEquals(EfispSource.CURRENT_SLOT_ABL, automatic.efispSource)
            assertEquals(AblWriteTarget.CURRENT, automatic.ablTarget)
        }
        assertEquals(EfispSource.OTHER_SLOT_ABL, custom.efispSource)
        assertEquals(AblWriteTarget.BOTH, custom.ablTarget)
    }
}
