package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test

class UnlockRequestTest {
    @Test fun everyModeDefaultsToRunningSlotGenerationAndDualSlotWrites() {
        UnlockMode.entries.forEach { mode ->
            val request = UnlockRequest(mode = mode)
            assertEquals(AblWriteTarget.BOTH, request.ablTarget)
            assertEquals(EfispSource.CURRENT_SLOT_ABL, request.efispSource)
            assertEquals(AblWriteSource.BUILTIN, request.ablSource)
            assertEquals(AblWriteTarget.BOTH, request.resolved().ablTarget)
            assertEquals(EfispSource.CURRENT_SLOT_ABL, request.resolved().efispSource)
        }
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

    @Test fun automaticModesAlwaysUseRunningSlotGenerationAndDualSlotWrites() {
        listOf(UnlockMode.FAKE_LOCK, UnlockMode.DIRECT).forEach { mode ->
            AblWriteTarget.entries.forEach { target ->
                val original = UnlockRequest(mode, AblWriteSource.UPLOADED, EfispSource.UPLOADED_ABL,
                    EfispPatchMode.FAKE_LOCK, target)
                val resolved = original.resolved()
                assertEquals(AblWriteTarget.BOTH, resolved.ablTarget)
                assertEquals(AblWriteSource.BUILTIN, resolved.ablSource)
                assertEquals(EfispSource.CURRENT_SLOT_ABL, resolved.efispSource)
                assertEquals(target, original.ablTarget)
                assertEquals(if (mode == UnlockMode.DIRECT) EfispPatchMode.FREEDOM else EfispPatchMode.FAKE_LOCK,
                    resolved.patchMode)
            }
        }
    }

    @Test fun switchingAutomaticPagesPreservesTheExplicitCustomSelection() {
        AblWriteTarget.entries.forEach { target ->
            EfispSource.entries.forEach { source ->
                val custom = UnlockRequest(UnlockMode.CUSTOM, AblWriteSource.UPLOADED, source,
                    EfispPatchMode.FREEDOM, target)
                val fake = custom.copy(mode = UnlockMode.FAKE_LOCK)
                assertEquals(AblWriteTarget.BOTH, fake.resolved().ablTarget)
                assertEquals(EfispSource.CURRENT_SLOT_ABL, fake.resolved().efispSource)
                val direct = fake.copy(mode = UnlockMode.DIRECT)
                assertEquals(AblWriteTarget.BOTH, direct.resolved().ablTarget)
                assertEquals(EfispSource.CURRENT_SLOT_ABL, direct.resolved().efispSource)
                assertEquals(custom, direct.copy(mode = UnlockMode.CUSTOM))
            }
        }
    }

    @Test fun bothCompletionActionsRemainBoundToTheEffectivePatchMode() {
        assertEquals(UnlockCompletion.BOOTLOADER, UnlockRequest().completion)
        assertEquals(UnlockCompletion.RECOVERY, UnlockRequest(mode = UnlockMode.DIRECT).completion)
        assertEquals(UnlockCompletion.RECOVERY,
            UnlockRequest(mode = UnlockMode.CUSTOM, patchMode = EfispPatchMode.FREEDOM).completion)
    }

    @Test fun malformedTargetAndWrongFieldCountsAreRejected() {
        listOf("CUSTOM:UPLOADED:CURRENT_SLOT_ABL", "CUSTOM:UPLOADED:CURRENT_SLOT_ABL:FAKE_LOCK:UNKNOWN",
            "CUSTOM:UPLOADED:CURRENT_SLOT_ABL:FAKE_LOCK:", "CUSTOM:UPLOADED:CURRENT_SLOT_ABL:FAKE_LOCK:BOTH:extra")
            .forEach { encoded -> assertThrows(IllegalArgumentException::class.java) { UnlockRequest.decode(encoded) } }
    }

    @Test fun aBundleForOneTargetCannotBeReusedForAnotherTarget() {
        val request = UnlockRequest(mode = UnlockMode.CUSTOM, efispSource = EfispSource.CURRENT_SLOT_ABL,
            ablTarget = AblWriteTarget.OTHER)
        val binding = mapOf("REQUEST_MODE" to "CUSTOM", "EFI_SOURCE" to "CURRENT_SLOT_ABL",
            "PATCH_MODE" to "FAKE_LOCK", "ABL_WRITE_TARGET" to "OTHER")
        assertTrue(request.matchesPayloadBinding(binding))
        assertFalse(request.copy(ablTarget = AblWriteTarget.CURRENT).matchesPayloadBinding(binding))
        assertFalse(request.copy(ablTarget = AblWriteTarget.BOTH).matchesPayloadBinding(binding))
        assertFalse(request.matchesPayloadBinding(binding - "ABL_WRITE_TARGET"))
        assertFalse(request.matchesPayloadBinding(binding + ("ABL_WRITE_TARGET" to "OTHER;reboot")))
    }

    @Test fun aLegacyBundleCanOnlyMatchTheCurrentTargetAndOriginalGenerationSelection() {
        val request = UnlockRequest(mode = UnlockMode.CUSTOM, efispSource = EfispSource.CURRENT_SLOT_ABL,
            ablTarget = AblWriteTarget.CURRENT)
        val legacy = mapOf("REQUEST_MODE" to "CUSTOM", "EFI_SOURCE" to "CURRENT_SLOT_ABL", "PATCH_MODE" to "FAKE_LOCK")
        assertTrue(request.matchesPayloadBinding(legacy))
        assertFalse(request.copy(ablTarget = AblWriteTarget.BOTH).matchesPayloadBinding(legacy))
        assertFalse(request.copy(efispSource = EfispSource.UPLOADED_ABL).matchesPayloadBinding(legacy))
        assertFalse(request.copy(patchMode = EfispPatchMode.FREEDOM).matchesPayloadBinding(legacy))
    }

    @Test fun newDefaultsNeverReuseAnOldAutomaticCurrentSlotBundle() {
        listOf(UnlockMode.FAKE_LOCK, UnlockMode.DIRECT).forEach { mode ->
            val request = UnlockRequest(mode = mode)
            val legacy = mapOf("REQUEST_MODE" to mode.name, "EFI_SOURCE" to "CURRENT_SLOT_ABL",
                "PATCH_MODE" to request.resolved().patchMode.name)
            assertFalse(request.matchesPayloadBinding(legacy))
            val previousBuiltin = mapOf("REQUEST_MODE" to mode.name, "EFI_SOURCE" to "BUILTIN_ABL",
                "PATCH_MODE" to request.resolved().patchMode.name, "ABL_WRITE_TARGET" to "BOTH")
            assertFalse(request.matchesPayloadBinding(previousBuiltin))
            val current = mapOf("REQUEST_MODE" to mode.name, "EFI_SOURCE" to "CURRENT_SLOT_ABL",
                "PATCH_MODE" to request.resolved().patchMode.name, "ABL_WRITE_TARGET" to "BOTH")
            assertTrue(request.matchesPayloadBinding(current))
        }
    }

    @Test fun allCustomSourcesAndTargetsBindDirectlyToTheirSelection() {
        EfispSource.entries.forEach { source ->
            AblWriteTarget.entries.forEach { target ->
                val request = UnlockRequest(mode = UnlockMode.CUSTOM, efispSource = source, ablTarget = target)
                val binding = mapOf("REQUEST_MODE" to "CUSTOM", "EFI_SOURCE" to source.name,
                    "PATCH_MODE" to "FAKE_LOCK", "ABL_WRITE_TARGET" to target.name)
                assertTrue(request.matchesPayloadBinding(binding))
                EfispSource.entries.filter { it != source }.forEach { changed ->
                    assertFalse(request.copy(efispSource = changed).matchesPayloadBinding(binding))
                }
                AblWriteTarget.entries.filter { it != target }.forEach { changed ->
                    assertFalse(request.copy(ablTarget = changed).matchesPayloadBinding(binding))
                }
            }
        }
    }

    @Test fun onlyActuallyPatchedExternalSourcesOfferBuiltinRetry() {
        assertTrue(EfispSource.CURRENT_SLOT_ABL.canRetryPatchWithBuiltin)
        assertTrue(EfispSource.OTHER_SLOT_ABL.canRetryPatchWithBuiltin)
        assertTrue(EfispSource.UPLOADED_ABL.canRetryPatchWithBuiltin)
        assertFalse(EfispSource.BUILTIN_ABL.canRetryPatchWithBuiltin)
        assertFalse(EfispSource.UPLOADED_EFISP.canRetryPatchWithBuiltin)
    }

    @Test fun acceptedBuiltinRetryBindsToOriginalRequestWithoutOldPartitionIdentity() {
        UnlockMode.entries.forEach { mode ->
            EfispSource.entries.filter { it.canRetryPatchWithBuiltin }.forEach { source ->
                AblWriteTarget.entries.forEach { target ->
                    EfispPatchMode.entries.forEach { patch ->
                        val request = UnlockRequest(mode, AblWriteSource.UPLOADED, source, patch, target)
                        val selected = request.resolved()
                        val binding = mapOf("REQUEST_MODE" to selected.mode.name,
                            "EFI_SOURCE" to selected.efispSource.name, "PATCH_MODE" to selected.patchMode.name,
                            "ABL_WRITE_TARGET" to selected.ablTarget.name, "PATCH_RETRY_SOURCE" to "BUILTIN_ABL",
                            "SOURCE_ABL_SHA256" to BUILTIN_ABL_SHA256)
                        assertTrue(request.matchesPayloadBinding(binding))
                        assertEquals(request, UnlockRequest.decode(request.encode()))
                        assertFalse(request.matchesPayloadBinding(binding + ("EFI_SOURCE" to "BUILTIN_ABL")))
                        listOf("SOURCE_SLOT" to "a", "SOURCE_ABL_DEVICE" to "/dev/block/test",
                            "SOURCE_ABL_MM" to "8:1", "SOURCE_ABL_CAPACITY" to "1048576").forEach { stale ->
                            assertFalse(request.matchesPayloadBinding(binding + stale))
                        }
                    }
                }
            }
        }
    }

    @Test fun invalidRetryMetadataCannotBeExportedAsTheFrozenRequest() {
        val request = UnlockRequest(mode = UnlockMode.CUSTOM, efispSource = EfispSource.CURRENT_SLOT_ABL)
        val binding = mapOf("REQUEST_MODE" to "CUSTOM", "EFI_SOURCE" to "CURRENT_SLOT_ABL",
            "PATCH_MODE" to "FAKE_LOCK", "ABL_WRITE_TARGET" to "BOTH", "PATCH_RETRY_SOURCE" to "BUILTIN_ABL",
            "SOURCE_ABL_SHA256" to BUILTIN_ABL_SHA256)
        listOf("", "CURRENT_SLOT_ABL", "UPLOADED_ABL", "UNKNOWN", "$(exit 99)").forEach { wrong ->
            assertFalse(request.matchesPayloadBinding(binding + ("PATCH_RETRY_SOURCE" to wrong)))
        }
        assertFalse(request.matchesPayloadBinding(binding - "SOURCE_ABL_SHA256"))
        assertFalse(request.matchesPayloadBinding(binding + ("SOURCE_ABL_SHA256" to "a".repeat(64))))
        listOf(EfispSource.BUILTIN_ABL, EfispSource.UPLOADED_EFISP).forEach { ineligible ->
            val other = request.copy(efispSource = ineligible)
            assertFalse(other.matchesPayloadBinding(binding + ("EFI_SOURCE" to ineligible.name)))
        }
    }
    @Test fun uploadedAblForFlashAndForEfispAreDistinctRequiredInputsAndRoundTrip() {
        val both=UnlockRequest(mode=UnlockMode.CUSTOM,ablSource=AblWriteSource.UPLOADED,efispSource=EfispSource.UPLOADED_ABL)
        assertEquals(setOf(UploadRole.ABL_FLASH,UploadRole.EFISP_ABL),both.requiredUploads)
        assertEquals(both,UnlockRequest.decode(both.encode()))
        assertEquals(setOf(UploadRole.EFISP_ABL),both.copy(ablSource=AblWriteSource.BUILTIN).requiredUploads)
        assertEquals(setOf(UploadRole.ABL_FLASH,UploadRole.EFISP_IMAGE),both.copy(efispSource=EfispSource.UPLOADED_EFISP).requiredUploads)
        assertTrue(both.copy(mode=UnlockMode.FAKE_LOCK).requiredUploads.isEmpty())
        assertTrue(both.copy(mode=UnlockMode.DIRECT).requiredUploads.isEmpty())
    }



}
