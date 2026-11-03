package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

class ImagePatchFailureBoundaryTest {
    @Test fun successfulActionIsReturnedOnceWithoutRetry() {
        var calls = 0
        val value = Any()
        assertSame(value, ImagePatchFailureBoundary.run { calls++; value })
        assertEquals(1, calls)
    }

    @Test fun unsupportedInputAndMissingEngineErrorsAreExplicitFailuresWithOriginalCause() {
        val errors = listOf(AssertionError("unsupported layout assertion"), StackOverflowError("recursive malformed graph"),
            UnsatisfiedLinkError("missing native dependency"), NoClassDefFoundError("engine dependency"), ExceptionInInitializerError("engine init"))
        errors.forEach { expected ->
            var calls = 0
            val actual = assertThrows(ImagePatchExecutionException::class.java) {
                ImagePatchFailureBoundary.run { calls++; throw expected }
            }
            assertEquals(1, calls)
            assertSame(expected, actual.cause)
            assertTrue(actual.message.orEmpty().contains(expected.javaClass.simpleName))
        }
    }

    @Test fun ordinaryIoCancellationAndInterruptionKeepTheirOriginalIdentity() {
        for (expected in listOf(IOException("local disk full"), SecurityException("document permission"),
            CancellationException("cancelled"), InterruptedException("interrupted"))) {
            val actual = assertThrows(expected.javaClass) { ImagePatchFailureBoundary.run { throw expected } }
            assertSame(expected, actual)
        }
    }

    @Test fun unrecoverableVmAndThreadTerminationAreNotConvertedToPatchSuccessOrOrdinaryFailure() {
        for (expected in listOf(OutOfMemoryError("heap"), ThreadDeath(), InternalError("VM failure"))) {
            assertNull(ImagePatchFailureBoundary.reportable(expected))
            val actual = assertThrows(expected.javaClass) { ImagePatchFailureBoundary.run { throw expected } }
            assertSame(expected, actual)
        }
    }

    @Test fun actualAdapterBoundaryReportsErrorEvenIfFailureLoggerAlsoThrows() {
        val folder = Files.createTempDirectory("efisp-error-boundary").toFile()
        try {
            val input = File(folder, "unsupported.img").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val output = File(folder, "previous.img").apply { writeBytes(byteArrayOf(9, 8, 7)) }
            val error = AssertionError("engine diagnostic callback")
            val actual = assertThrows(ImagePatchExecutionException::class.java) {
                EfispImagePatcher.patch(input, output, true) { throw error }
            }
            assertSame(error, actual.cause)
            assertArrayEquals(byteArrayOf(1, 2, 3), input.readBytes())
            assertArrayEquals(byteArrayOf(9, 8, 7), output.readBytes())
            assertEquals(listOf("previous.img", "unsupported.img"), folder.list().orEmpty().sorted())
        } finally { folder.deleteRecursively() }
    }
}
