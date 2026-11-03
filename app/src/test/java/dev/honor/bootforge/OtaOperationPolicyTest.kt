package dev.honor.bootforge

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class OtaOperationPolicyTest {
    @Test fun missingEfispUsesExactMessageForEveryAction() {
        OtaOperation.entries.forEach {
            val failure = runCatching { OtaOperationPolicy.requireEfisp(false) }.exceptionOrNull()
            assertEquals("设备无efisp分区，请先操作解锁后再试！", failure?.message)
        }
        OtaOperationPolicy.requireEfisp(true)
    }

    @Test fun efispOnlyRejectsAblEvenIfEfispIsAlsoPresent() {
        OtaOperationPolicy.validateWriteTargets(OtaOperation.EFISP_ONLY, listOf("efisp"))
        listOf(emptyList(), listOf("abl_b"), listOf("abl_b", "efisp"), listOf("efisp", "efisp"), listOf("userdata")).forEach {
            assertTrue(it.toString(), runCatching { OtaOperationPolicy.validateWriteTargets(OtaOperation.EFISP_ONLY, it) }.isFailure)
        }
    }

    @Test fun exportRejectsAnyPartitionTarget() {
        OtaOperationPolicy.validateWriteTargets(OtaOperation.EXPORT_EFISP, emptyList())
        listOf("efisp", "abl_a", "userdata", "super").forEach {
            assertTrue(it, runCatching { OtaOperationPolicy.validateWriteTargets(OtaOperation.EXPORT_EFISP, listOf(it)) }.isFailure)
        }
    }

    @Test fun partialActionsRequireAppropriateEfispSource() {
        assertTrue(runCatching { OtaOperationPolicy.validateRequest(OtaOperation.EFISP_ONLY, OtaEfispSource.NONE) }.isFailure)
        assertTrue(runCatching { OtaOperationPolicy.validateRequest(OtaOperation.EXPORT_EFISP, OtaEfispSource.UPLOADED_EFISP) }.isFailure)
        OtaOperationPolicy.validateRequest(OtaOperation.FULL, OtaEfispSource.NONE)
        OtaOperationPolicy.validateRequest(OtaOperation.EFISP_ONLY, OtaEfispSource.UPLOADED_EFISP)
        for (source in OtaEfispSource.entries.filter { it !in listOf(OtaEfispSource.NONE, OtaEfispSource.UPLOADED_EFISP) }) {
            OtaOperationPolicy.validateRequest(OtaOperation.EXPORT_EFISP, source)
        }
    }

    @Test fun exportedDeviceEfispNeverOverwritesOriginalPartitionSnapshot() {
        val session = Files.createTempDirectory("ota-export-test").toFile()
        try {
            val snapshot = File(session, "efisp.img").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val product = File(session, "generated-efisp.img").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
            val exported = OtaExportArtifact.prepare(session, product)
            assertEquals(File(session, "export/efisp.img"), exported)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), snapshot.readBytes())
            assertArrayEquals(product.readBytes(), exported.readBytes())
        } finally { session.deleteRecursively() }
    }
}
