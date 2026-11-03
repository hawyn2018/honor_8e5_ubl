package dev.honor.bootforge

import dev.honor.bootforge.core.UploadRole
import dev.honor.bootforge.core.EfispPatchMode
import org.junit.Assert.*
import org.junit.Test

class OtaRequestTest {
    @Test fun initialSelectionUsesExperimentalAndOtherSlotEfisp() {
        val request = OtaRequest()
        assertEquals(OtaPreset.EXPERIMENTAL, request.preset)
        assertEquals(OtaAblSource.BUILTIN, request.ablSource)
        assertEquals(OtaEfispSource.OTHER_SLOT_ABL, request.efispSource)
        assertEquals(EfispPatchMode.FAKE_LOCK, request.patchMode)
    }

    @Test fun standardBindsSourcesAndRetainsPatchMode() {
        val previous = OtaRequest(OtaAblSource.UPLOADED, OtaEfispSource.EXISTING_EFISP,
            EfispPatchMode.FREEDOM, OtaPreset.CUSTOM)
        val selected = previous.withPreset(OtaPreset.STANDARD).editedFrom(previous)
        assertEquals(OtaPreset.STANDARD, selected.preset)
        assertEquals(OtaAblSource.BUILTIN, selected.ablSource)
        assertEquals(OtaEfispSource.CURRENT_SLOT_ABL, selected.efispSource)
        assertEquals(EfispPatchMode.FREEDOM, selected.patchMode)
    }

    @Test fun experimentalBindsOtherEfispAndBuiltinParent() {
        val previous = OtaRequest().withPreset(OtaPreset.STANDARD)
        val selected = previous.withPreset(OtaPreset.EXPERIMENTAL).editedFrom(previous)
        assertEquals(OtaPreset.EXPERIMENTAL, selected.preset)
        assertEquals(OtaAblSource.BUILTIN, selected.ablSource)
        assertEquals(OtaEfispSource.OTHER_SLOT_ABL, selected.efispSource)
    }

    @Test fun customButtonDoesNotAlterAnySourcesOrPatchMode() {
        for (abl in OtaAblSource.entries) for (efisp in OtaEfispSource.entries) for (mode in EfispPatchMode.entries) {
            val previous = OtaRequest(abl, efisp, mode, OtaPreset.EXPERIMENTAL)
            val selected = previous.withPreset(OtaPreset.CUSTOM).editedFrom(previous)
            assertEquals(previous.copy(preset = OtaPreset.CUSTOM), selected)
        }
    }

    @Test fun manualAblEditFromEachPresetBecomesCustomWithoutChangingEfisp() {
        for (preset in listOf(OtaPreset.STANDARD, OtaPreset.EXPERIMENTAL)) {
            val previous = OtaRequest().withPreset(preset)
            for (source in OtaAblSource.entries.filter { it != previous.ablSource }) {
                val edited = previous.copy(ablSource = source).editedFrom(previous)
                assertEquals(OtaPreset.CUSTOM, edited.preset)
                assertEquals(source, edited.ablSource)
                assertEquals(previous.efispSource, edited.efispSource)
            }
        }
    }

    @Test fun manualEfispEditFromEachPresetBecomesCustomIncludingTheOtherPresetSources() {
        for (preset in listOf(OtaPreset.STANDARD, OtaPreset.EXPERIMENTAL)) {
            val previous = OtaRequest().withPreset(preset)
            for (source in OtaEfispSource.entries.filter { it != previous.efispSource }) {
                val edited = previous.copy(efispSource = source).editedFrom(previous)
                assertEquals(OtaPreset.CUSTOM, edited.preset)
                assertEquals(source, edited.efispSource)
                assertEquals(previous.ablSource, edited.ablSource)
            }
        }
    }

    @Test fun choosingSameSourceIsNoOpForEveryPreset() {
        for (preset in OtaPreset.entries) {
            val previous = OtaRequest().withPreset(preset)
            assertEquals(previous, previous.copy(ablSource = previous.ablSource).editedFrom(previous))
            assertEquals(previous, previous.copy(efispSource = previous.efispSource).editedFrom(previous))
        }
    }

    @Test fun patchModeEditPreservesSelectedPresetAndBothSources() {
        for (preset in OtaPreset.entries) {
            val previous = OtaRequest().withPreset(preset)
            val edited = previous.copy(patchMode = EfispPatchMode.FREEDOM).editedFrom(previous)
            assertEquals(previous.copy(patchMode = EfispPatchMode.FREEDOM), edited)
        }
    }

    @Test fun returningToPresetSourcesManuallyStaysCustomUntilPresetClicked() {
        val original = OtaRequest()
        val custom = original.copy(ablSource = OtaAblSource.CURRENT_SLOT_ABL).editedFrom(original)
        val returned = custom.copy(ablSource = OtaAblSource.BUILTIN).editedFrom(custom)
        assertEquals(OtaPreset.CUSTOM, returned.preset)
        assertEquals(OtaPreset.STANDARD, returned.withPreset(OtaPreset.STANDARD).editedFrom(returned).preset)
    }

    @Test fun invalidPresetSourceCombinationIsDisplayedAsCustom() {
        val previous = OtaRequest().withPreset(OtaPreset.CUSTOM)
        val invalid = previous.copy(preset = OtaPreset.EXPERIMENTAL, efispSource = OtaEfispSource.BUILTIN_ABL)
        assertEquals(OtaPreset.CUSTOM, invalid.editedFrom(previous).preset)
    }

    @Test fun versionTwoRoundTripRetainsAllSourceModesAndExplicitPresetButtons() {
        for (abl in OtaAblSource.entries) for (efisp in OtaEfispSource.entries) for (mode in EfispPatchMode.entries) {
            val request = OtaRequest(abl, efisp, mode, OtaPreset.CUSTOM)
            assertEquals(request, OtaRequest.decode(request.encode()))
        }
        for (preset in OtaPreset.entries) {
            val request = OtaRequest().withPreset(preset)
            assertEquals(request, OtaRequest.decode(request.encode()))
        }
    }

    @Test fun legacyAutoFollowsCurrentPresetBindingsWithoutLosingEfispOrMode() {
        val old = OtaRequest.decode("false:AUTO:OTHER_SLOT_ABL:FREEDOM")
        assertEquals(OtaAblSource.BUILTIN, old.ablSource)
        assertEquals(OtaEfispSource.OTHER_SLOT_ABL, old.efispSource)
        assertEquals(OtaPreset.EXPERIMENTAL, old.preset)
        assertEquals(EfispPatchMode.FREEDOM, old.patchMode)
        assertEquals(OtaRequest().withPreset(OtaPreset.STANDARD), OtaRequest.decode("false:AUTO:CURRENT_SLOT_ABL:FAKE_LOCK"))
    }

    @Test fun legacyCustomAndInactiveSourceControlPreservePriorSelectionMeaning() {
        assertEquals(OtaRequest(OtaAblSource.UPLOADED, OtaEfispSource.NONE,
            EfispPatchMode.FREEDOM, OtaPreset.CUSTOM), OtaRequest.decode("true:UPLOADED:NONE:FREEDOM"))
        assertEquals(OtaRequest(OtaAblSource.CURRENT_SLOT_ABL, OtaEfispSource.NONE,
            EfispPatchMode.FREEDOM, OtaPreset.CUSTOM), OtaRequest.decode("true:CURRENT_SLOT_ABL:NONE:FREEDOM"))
        // The retired custom toggle was off, so its hidden source value was ignored.
        val old = OtaRequest.decode("false:UPLOADED:CURRENT_SLOT_ABL:FAKE_LOCK")
        assertEquals(OtaRequest().withPreset(OtaPreset.STANDARD), old)
    }

    @Test fun malformedRequestsAreRejectedBeforeTheyReachEngine() {
        for (value in listOf("", "v3:STANDARD:CURRENT_SLOT_ABL:CURRENT_SLOT_ABL:FAKE_LOCK",
            "v2:STANDARD:UNKNOWN:NONE:FAKE_LOCK", "v2:STANDARD:CURRENT_SLOT_ABL:NONE:UNKNOWN",
            "v2:STANDARD:CURRENT_SLOT_ABL:NONE:FAKE_LOCK:extra", "false:UNKNOWN:NONE:FAKE_LOCK")) {
            assertTrue(value, runCatching { OtaRequest.decode(value) }.isFailure)
        }
    }

    @Test fun sourceLabelsUseRequestedTerminology() {
        assertEquals(listOf("当前槽位ABL", "应用内置ABL", "ABL自定义"), OtaAblSource.entries.map { it.label })
        assertEquals("直接刷入上传的 efisp", OtaEfispSource.UPLOADED_EFISP.label)
    }
    @Test fun uploadPurposesStayIndependentAcrossOperationAndSourceSwitches() {
        val both=OtaRequest(OtaAblSource.UPLOADED,OtaEfispSource.UPLOADED_ABL,preset=OtaPreset.CUSTOM)
        assertEquals(setOf(UploadRole.ABL_FLASH,UploadRole.EFISP_ABL),both.requiredUploads(OtaOperation.FULL))
        assertEquals(setOf(UploadRole.EFISP_ABL),both.requiredUploads(OtaOperation.EXPORT_EFISP))
        assertEquals(setOf(UploadRole.EFISP_ABL),both.requiredUploads(OtaOperation.EFISP_ONLY))
        for (source in listOf(OtaEfispSource.UPLOADED_EFISP,OtaEfispSource.UPLOADED_EFISP_UPGRADE)) {
            val selected=both.copy(efispSource=source)
            assertEquals(setOf(UploadRole.EFISP_IMAGE),selected.requiredUploads(OtaOperation.EXPORT_EFISP))
            assertEquals(selected,OtaRequest.decode(selected.encode()))
        }
        assertTrue(both.withPreset(OtaPreset.STANDARD).requiredUploads(OtaOperation.FULL).isEmpty())
        assertTrue(both.withPreset(OtaPreset.EXPERIMENTAL).requiredUploads(OtaOperation.FULL).isEmpty())
    }

}
