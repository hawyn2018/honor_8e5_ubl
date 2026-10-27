package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test

class UnlockRequestTest {
    @Test fun currentSlotIsTheDefaultAndLabelsMatchTheRequestedUi() {
        assertEquals(AblWriteTarget.CURRENT, UnlockRequest().ablTarget)
        assertEquals("当槽刷写", AblWriteTarget.CURRENT.label)
        assertEquals("对槽刷写", AblWriteTarget.OTHER.label)
        assertEquals("双槽刷写", AblWriteTarget.BOTH.label)
    }

    @Test fun targetsResolveRelativeToTheActualRunningSlot() {
        listOf("a", "_a").forEach { slot ->
            assertEquals(listOf("abl_a"), AblWriteTarget.CURRENT.partitionNames(slot))
            assertEquals(listOf("abl_b"), AblWriteTarget.OTHER.partitionNames(slot))
            assertEquals(listOf("abl_a", "abl_b"), AblWriteTarget.BOTH.partitionNames(slot))
        }
        listOf("b", "_b").forEach { slot ->
            assertEquals(listOf("abl_b"), AblWriteTarget.CURRENT.partitionNames(slot))
            assertEquals(listOf("abl_a"), AblWriteTarget.OTHER.partitionNames(slot))
            assertEquals(listOf("abl_b", "abl_a"), AblWriteTarget.BOTH.partitionNames(slot))
        }
    }

    @Test fun anUnknownOrMalformedSlotNeverGuessesEvenForBoth() {
        listOf("", "unknown", "A", "_c", "_a ", "a\nb", "abl_a", "a; reboot").forEach { slot ->
            AblWriteTarget.entries.forEach { assertTrue(it.partitionNames(slot).isEmpty()) }
        }
    }

    @Test fun customSelectionsRoundTripWithTheFrozenWriteTarget() {
        AblWriteTarget.entries.forEach { target ->
            val request = UnlockRequest(UnlockMode.CUSTOM, AblWriteSource.UPLOADED,
                EfispSource.UPLOADED_ABL, EfispPatchMode.FREEDOM, target)
            assertEquals(5, request.encode().split(':').size)
            assertEquals(request, UnlockRequest.decode(request.encode()))
            assertSame(request, request.resolved())
            assertEquals(EfispSource.UPLOADED_ABL, request.resolved().efispSource)
        }
    }

    @Test fun legacyFourFieldRecordsKeepCurrentSlotWrites() {
        val old = UnlockRequest.decode("CUSTOM:UPLOADED:CURRENT_SLOT_ABL:FAKE_LOCK")
        assertEquals(AblWriteTarget.CURRENT, old.ablTarget)
        assertEquals(AblWriteSource.UPLOADED, old.ablSource)
        assertEquals(EfispSource.CURRENT_SLOT_ABL, old.efispSource)
    }

    @Test fun automaticModesCannotInheritOtherOrBothSlotWrites() {
        listOf(UnlockMode.FAKE_LOCK, UnlockMode.DIRECT).forEach { mode ->
            AblWriteTarget.entries.forEach { target ->
                val original = UnlockRequest(mode, AblWriteSource.UPLOADED, EfispSource.UPLOADED_ABL,
                    EfispPatchMode.FAKE_LOCK, target)
                val resolved = original.resolved()
                assertEquals(AblWriteTarget.CURRENT, resolved.ablTarget)
                assertEquals(AblWriteSource.BUILTIN, resolved.ablSource)
                assertEquals(EfispSource.CURRENT_SLOT_ABL, resolved.efispSource)
                assertEquals(target, original.ablTarget)
                assertEquals(if (mode == UnlockMode.DIRECT) EfispPatchMode.FREEDOM else EfispPatchMode.FAKE_LOCK,
                    resolved.patchMode)
            }
        }
    }

    @Test fun malformedTargetAndWrongFieldCountsAreRejected() {
        listOf("CUSTOM:UPLOADED:CURRENT_SLOT_ABL", "CUSTOM:UPLOADED:CURRENT_SLOT_ABL:FAKE_LOCK:UNKNOWN",
            "CUSTOM:UPLOADED:CURRENT_SLOT_ABL:FAKE_LOCK:", "CUSTOM:UPLOADED:CURRENT_SLOT_ABL:FAKE_LOCK:BOTH:extra")
            .forEach { encoded -> assertThrows(IllegalArgumentException::class.java) { UnlockRequest.decode(encoded) } }
    }

    @Test fun aBundleForOneTargetCannotBeReusedForAnotherTarget() {
        val request = UnlockRequest(mode = UnlockMode.CUSTOM, ablTarget = AblWriteTarget.OTHER)
        val binding = mapOf("REQUEST_MODE" to "CUSTOM", "EFI_SOURCE" to "CURRENT_SLOT_ABL",
            "PATCH_MODE" to "FAKE_LOCK", "ABL_WRITE_TARGET" to "OTHER")
        assertTrue(request.matchesPayloadBinding(binding))
        assertFalse(request.copy(ablTarget = AblWriteTarget.CURRENT).matchesPayloadBinding(binding))
        assertFalse(request.copy(ablTarget = AblWriteTarget.BOTH).matchesPayloadBinding(binding))
        assertFalse(request.matchesPayloadBinding(binding - "ABL_WRITE_TARGET"))
        assertFalse(request.matchesPayloadBinding(binding + ("ABL_WRITE_TARGET" to "OTHER;reboot")))
    }

    @Test fun aLegacyBundleCanOnlyMatchTheCurrentTargetAndOriginalGenerationSelection() {
        val request = UnlockRequest(mode = UnlockMode.CUSTOM)
        val legacy = mapOf("REQUEST_MODE" to "CUSTOM", "EFI_SOURCE" to "CURRENT_SLOT_ABL", "PATCH_MODE" to "FAKE_LOCK")
        assertTrue(request.matchesPayloadBinding(legacy))
        assertFalse(request.copy(ablTarget = AblWriteTarget.BOTH).matchesPayloadBinding(legacy))
        assertFalse(request.copy(efispSource = EfispSource.UPLOADED_ABL).matchesPayloadBinding(legacy))
        assertFalse(request.copy(patchMode = EfispPatchMode.FREEDOM).matchesPayloadBinding(legacy))
    }
}
