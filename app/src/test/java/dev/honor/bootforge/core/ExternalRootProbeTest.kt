package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ExternalRootProbeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun grantedExternalSuIsVerifiedByExactUidInStdout() {
        val result = ExternalRootProbe.select(listOf("su"), 3_000, { 0L }) { _, _ ->
            ExternalRootProbe.Attempt(0, "0\n")
        }
        assertEquals(ExternalRootProbe.Result(true, "su"), result)
    }

    @Test fun shellUidOrBannersNeverCountAsRoot() {
        for (uid in listOf("2000", "uid=0", "ROOT_UID=0", "0\nnotice", "", "10")) {
            val result = ExternalRootProbe.select(listOf("su"), 3_000, { 0L }) { _, _ ->
                ExternalRootProbe.Attempt(0, uid)
            }
            assertFalse(uid, result.granted)
            assertNull(result.suPath)
        }
    }

    @Test fun deniedAndRevokedAuthorizationNeverReuseEarlierSuccess() {
        assertTrue(ExternalRootProbe.select(listOf("su"), 3_000, { 0L }) { _, _ ->
            ExternalRootProbe.Attempt(0, "0")
        }.granted)
        assertFalse(ExternalRootProbe.select(listOf("su"), 3_000, { 0L }) { _, _ ->
            ExternalRootProbe.Attempt(1, "0")
        }.granted)
    }

    @Test fun missingCandidateFallsBackAndDuplicatesDoNotRepeatAuthorization() {
        val attempts = mutableListOf<String>()
        val result = ExternalRootProbe.select(listOf("missing", "missing", "ksu"), 3_000, { 0L }) { path, _ ->
            attempts += path
            if (path == "missing") throw IOException("no su")
            ExternalRootProbe.Attempt(0, "0")
        }
        assertEquals(listOf("missing", "ksu"), attempts)
        assertEquals("ksu", result.suPath)
    }

    @Test fun allCandidatesShareOneMonotonicDeadline() {
        var now = 0L
        val limits = mutableListOf<Long>()
        val result = ExternalRootProbe.select(listOf("first", "second", "third"), 3_000, { now }) { path, remaining ->
            limits += remaining
            now += TimeUnit.MILLISECONDS.toNanos(1_000)
            ExternalRootProbe.Attempt(if (path == "second") 0 else 1, if (path == "second") "0" else "")
        }
        assertTrue(result.granted)
        assertEquals(listOf(3_000L, 2_000L), limits)
    }

    @Test fun authorizationTimeoutStopsWithoutTryingAnotherRootManager() {
        val attempts = mutableListOf<String>()
        val result = ExternalRootProbe.select(listOf("first", "second"), 3_000, { 0L }) { path, _ ->
            attempts += path
            ExternalRootProbe.Attempt(124, "0", timedOut = true)
        }
        assertEquals(listOf("first"), attempts)
        assertFalse(result.granted)
        assertTrue(result.timedOut)
    }

    @Test fun lateSuccessDoesNotRouteToOtaAfterTheDeadline() {
        var now = 0L
        val result = ExternalRootProbe.select(listOf("su"), 3_000, { now }) { _, _ ->
            now = TimeUnit.MILLISECONDS.toNanos(3_000)
            ExternalRootProbe.Attempt(0, "0")
        }
        assertFalse(result.granted)
        assertTrue(result.timedOut)
    }

    @Test fun interruptedProbeStopsWithoutLaunchingFurtherCandidates() {
        val attempts = mutableListOf<String>()
        try {
            val result = ExternalRootProbe.select(listOf("first", "second"), 3_000, { 0L }) { path, _ ->
                attempts += path
                throw InterruptedException()
            }
            assertFalse(result.granted)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(listOf("first"), attempts)
        } finally { Thread.interrupted() }
    }

    @Test fun productionCommandOnlyRunsExternalSuAndIgnoresStderrNotices() {
        val commands = mutableListOf<List<String>>()
        val log = temporary.newFile("external.log")
        val result = ExternalRootProbe.check(log, 3_000, listOf("/ksu/su"), System::nanoTime) { command ->
            commands += command
            FixtureProcess("0\n", "root manager notice\n")
        }
        assertTrue(result.granted)
        assertEquals(listOf(listOf("/ksu/su", "-c", "id -u")), commands)
        assertTrue(log.readText().contains("ROOT_PROVIDER=external-su"))
        assertTrue(log.readText().contains("root manager notice"))
        assertEquals(listOf("external.log"), temporary.root.listFiles().orEmpty().map { it.name })
    }

    @Test fun stderrUidZeroWithoutStdoutProofDoesNotGrantRoot() {
        val result = ExternalRootProbe.check(temporary.newFile("external.log"), 3_000, listOf("su"), System::nanoTime) {
            FixtureProcess("2000\n", "0\n")
        }
        assertFalse(result.granted)
    }

    @Test fun aHangingExternalSuIsDestroyedAtTimeout() {
        val process = FixtureProcess("", "", timesOut = true)
        val result = ExternalRootProbe.check(temporary.newFile("timeout.log"), 20, listOf("su", "second"), System::nanoTime) {
            process
        }
        assertFalse(result.granted)
        assertTrue(result.timedOut)
        assertTrue(process.destroyed)
    }

    private class FixtureProcess(stdout: String, stderr: String, private val timesOut: Boolean = false) : Process() {
        private val input = ByteArrayInputStream(stdout.toByteArray())
        private val error = ByteArrayInputStream(stderr.toByteArray())
        private val output = ByteArrayOutputStream()
        var destroyed = false
        override fun getOutputStream() = output
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !timesOut || destroyed
        override fun exitValue(): Int = 0
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
        override fun isAlive(): Boolean = timesOut && !destroyed
    }
}
