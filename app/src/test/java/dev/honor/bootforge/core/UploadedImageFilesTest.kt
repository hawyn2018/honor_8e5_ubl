package dev.honor.bootforge.core

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadedImageFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun install(files: UploadedImageFiles, selections: MutableMap<UploadRole,SavedUpload>, role: UploadRole,
                        bytes: ByteArray, name: String = "same-name.img"): SavedUpload =
        files.install(role,name,selections[role],open={ByteArrayInputStream(bytes)},publish={ selections[role]=it;true })

    @Test fun differentAblInputsSurviveReconstructionAndStageToDifferentDestinations() {
        val folder=temporary.newFolder();val files=UploadedImageFiles(folder);val saved=mutableMapOf<UploadRole,SavedUpload>()
        val flash=install(files,saved,UploadRole.ABL_FLASH,byteArrayOf(1,2,3))
        val patch=install(files,saved,UploadRole.EFISP_ABL,byteArrayOf(9,8,7))
        assertNotEquals(flash.fileName,patch.fileName);assertNotEquals(flash.sha256,patch.sha256)
        val rebuilt=UploadedImageFiles(folder)
        val out=temporary.newFolder()
        assertArrayEquals(byteArrayOf(1,2,3),rebuilt.snapshot(UploadRole.ABL_FLASH,flash,File(out,"abl.img")).readBytes())
        assertArrayEquals(byteArrayOf(9,8,7),rebuilt.snapshot(UploadRole.EFISP_ABL,patch,File(out,"efisp-source.img")).readBytes())
    }

    @Test fun replacingOnePurposePreservesTheOtherTwoAndTheirPublishedMetadata() {
        val files=UploadedImageFiles(temporary.newFolder());val saved=mutableMapOf<UploadRole,SavedUpload>()
        UploadRole.entries.forEachIndexed { i,role -> install(files,saved,role,byteArrayOf(i.toByte())) }
        val before=saved.toMap();val oldFlash=files.selected(UploadRole.ABL_FLASH,before.getValue(UploadRole.ABL_FLASH))
        install(files,saved,UploadRole.ABL_FLASH,byteArrayOf(44))
        assertFalse(oldFlash.exists())
        for (role in listOf(UploadRole.EFISP_ABL,UploadRole.EFISP_IMAGE)) {
            assertEquals(before.getValue(role),saved.getValue(role));assertTrue(files.selected(role,saved.getValue(role)).isFile)
        }
    }

    @Test fun legacySharedAblIsOnlyAcceptedForFlashingAndNeverAdoptedForEfisp() {
        val folder=temporary.newFolder();val files=UploadedImageFiles(folder)
        val old=File(folder,"abl.bin").apply { writeBytes(byteArrayOf(4,5)) }
        val saved=SavedUpload(old.name,"old-upload",Payloads.sha256(old))
        assertEquals(old,files.selected(UploadRole.ABL_FLASH,saved))
        assertThrows(IllegalStateException::class.java) { files.selected(UploadRole.EFISP_ABL,saved) }
        assertThrows(IllegalStateException::class.java) { files.path(UploadRole.EFISP_IMAGE,saved.fileName) }
    }

    @Test fun aFailedImportOrMetadataCommitRetainsThePreviousPublishedFile() {
        val folder=temporary.newFolder();val files=UploadedImageFiles(folder);val saved=mutableMapOf<UploadRole,SavedUpload>()
        val old=install(files,saved,UploadRole.EFISP_ABL,byteArrayOf(5,6));val previous=files.selected(UploadRole.EFISP_ABL,old)
        for (failure in listOf("empty","oversized","commit","open")) {
            assertTrue(runCatching {
                files.install(UploadRole.EFISP_ABL,"bad",old,maxBytes=2,open={
                    if (failure=="open") throw IllegalArgumentException("read failed")
                    ByteArrayInputStream(if (failure=="empty") byteArrayOf() else if (failure=="oversized") byteArrayOf(1,2,3) else byteArrayOf(1))
                },publish={false})
            }.isFailure)
            assertEquals(old,saved.getValue(UploadRole.EFISP_ABL));assertArrayEquals(byteArrayOf(5,6),previous.readBytes())
            assertEquals(listOf(previous.name),folder.listFiles()!!.map { it.name })
        }
    }

    @Test fun corruptedInputsAndCrossPurposeOrEscapingPathsFailBeforeStaging() {
        val folder=temporary.newFolder();val files=UploadedImageFiles(folder);val saved=mutableMapOf<UploadRole,SavedUpload>()
        val input=install(files,saved,UploadRole.EFISP_ABL,byteArrayOf(7))
        files.path(UploadRole.EFISP_ABL,input.fileName).writeBytes(byteArrayOf(8))
        val dest=File(temporary.newFolder(),"copy.img")
        assertThrows(IllegalStateException::class.java) { files.snapshot(UploadRole.EFISP_ABL,input,dest) }
        assertFalse(dest.exists())
        assertThrows(IllegalStateException::class.java) { files.path(UploadRole.ABL_FLASH,input.fileName) }
        assertThrows(IllegalStateException::class.java) { files.path(UploadRole.ABL_FLASH,"../abl.bin") }
    }

    @Test fun preexistingSnapshotIsNotOverwrittenOrDeleted() {
        val files=UploadedImageFiles(temporary.newFolder());val saved=mutableMapOf<UploadRole,SavedUpload>()
        val input=install(files,saved,UploadRole.EFISP_IMAGE,byteArrayOf(1))
        val destination=temporary.newFile().apply { writeBytes(byteArrayOf(42)) }
        assertThrows(IllegalStateException::class.java) { files.snapshot(UploadRole.EFISP_IMAGE,input,destination) }
        assertArrayEquals(byteArrayOf(42),destination.readBytes())
    }

    @Test fun separateWorkflowFoldersDoNotShareMetadataOrDeleteEachOthersFiles() {
        val custom=UploadedImageFiles(temporary.newFolder());val ota=UploadedImageFiles(temporary.newFolder())
        val c=mutableMapOf<UploadRole,SavedUpload>();val o=mutableMapOf<UploadRole,SavedUpload>()
        val customInput=install(custom,c,UploadRole.EFISP_ABL,byteArrayOf(2));install(ota,o,UploadRole.EFISP_ABL,byteArrayOf(3))
        install(ota,o,UploadRole.EFISP_ABL,byteArrayOf(4))
        assertEquals(customInput,c.getValue(UploadRole.EFISP_ABL));assertArrayEquals(byteArrayOf(2),custom.selected(UploadRole.EFISP_ABL,customInput).readBytes())
    }
}
