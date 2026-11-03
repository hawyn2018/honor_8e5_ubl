package dev.honor.bootforge

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OtaEfispInputsTest {
    @Test fun fileSourcesSelectOnlyTheirFileCallbackWithoutAnyRootDependency() {
        val sources = listOf(OtaEfispSource.BUILTIN_ABL, OtaEfispSource.UPLOADED_ABL, OtaEfispSource.UPLOADED_EFISP_UPGRADE)
        for (source in sources) {
            val calls = mutableListOf<String>()
            val output = OtaEfispInputs.resolve(source,
                { calls += "builtin"; File("builtin") }, { calls += "efisp-abl"; File("efisp-abl") },
                { calls += "efisp"; File("efisp") }, { error("Root/current source must not run") },
                { error("Root/other source must not run") }, { error("Root/device efisp must not run") })
            val expected = when (source) { OtaEfispSource.BUILTIN_ABL -> "builtin"; OtaEfispSource.UPLOADED_ABL -> "efisp-abl"; else -> "efisp" }
            assertEquals(File(expected), output)
            assertEquals(listOf(expected), calls)
        }
    }

    @Test fun deviceReadsAreDeferredUntilTheSelectedSourceIsActuallyRequested() {
        for (source in listOf(OtaEfispSource.CURRENT_SLOT_ABL, OtaEfispSource.OTHER_SLOT_ABL, OtaEfispSource.EXISTING_EFISP)) {
            val calls = mutableListOf<String>()
            val output = OtaEfispInputs.resolve(source, { error("unrelated builtin") }, { error("unrelated uploaded ABL") },
                { error("unrelated uploaded efisp") }, { calls += "current"; File("current") },
                { calls += "other"; File("other") }, { calls += "device-efisp"; File("device-efisp") })
            assertEquals(1, calls.size)
            assertEquals(File(calls.single()), output)
        }
    }

    @Test fun localDocumentPermissionFailureIsPreservedRatherThanRelabeledAsRoot() {
        val expected = SecurityException("SAF document grant expired")
        val actual = assertThrows(SecurityException::class.java) {
            OtaEfispInputs.resolve(OtaEfispSource.UPLOADED_ABL, { error("builtin") }, { throw expected },
                { error("efisp") }, { error("root") }, { error("root") }, { error("root") })
        }
        assertSame(expected, actual)
    }

    @Test fun noEfispSourceHasNoFileOrRootSideEffects() {
        val unexpected = { error("No source callback expected") }
        assertNull(OtaEfispInputs.resolve(OtaEfispSource.NONE, unexpected, unexpected, unexpected, unexpected, unexpected, unexpected))
    }

    @Test fun twoUploadedAblRolesHaveIndependentKeysFilesAndSnapshotCaches() {
        val calls = mutableListOf<Pair<String, String>>()
        val inputs = OtaInputSnapshots { key, name -> calls += key to name; File(name) }
        val write = inputs.ablForWrite
        val efisp = inputs.ablForEfisp
        assertNotEquals(write, efisp)
        assertSame(write, inputs.ablForWrite)
        assertSame(efisp, inputs.ablForEfisp)
        assertEquals(listOf("abl" to "uploaded-abl.img", "efisp-abl" to "uploaded-efisp-source-abl.img"), calls)
    }

    @Test fun missingUnrelatedWriteUploadCannotGateAnEfispSourceAndFailureKeepsOtherRole() {
        val write = File("already-fixed-write-abl")
        val calls = mutableListOf<String>()
        val inputs = OtaInputSnapshots { key, _ -> calls += key; if (key == "abl") write else throw java.io.IOException("patch source missing") }
        assertSame(write, inputs.ablForWrite)
        assertThrows(java.io.IOException::class.java) { inputs.ablForEfisp }
        assertSame(write, inputs.ablForWrite)
        assertEquals(listOf("abl", "efisp-abl"), calls)
        val efispOnly = OtaInputSnapshots { key, name -> check(key == "efisp-abl"); File(name) }
        assertEquals(File("uploaded-efisp-source-abl.img"), OtaEfispInputs.resolve(OtaEfispSource.UPLOADED_ABL,
            { error("builtin") }, { efispOnly.ablForEfisp }, { error("efisp") }, { error("root") }, { error("root") }, { error("root") }))
    }
}
