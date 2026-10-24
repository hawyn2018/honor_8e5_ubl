package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RootAccessTest {
    @Test fun `verified su is reused without replaying checks for each task`() {
        val attempts = mutableListOf<String>()
        val access = RootAccess({ listOf("system-su", "ksu-su") })
        val probe: (String, Long) -> ShellResult = { path, _ ->
            attempts += path
            if (path == "system-su") ShellResult(1, "denied") else ShellResult(0, "0\n")
        }
        assertEquals("ksu-su", access.resolve(probe))
        assertEquals("ksu-su", access.resolve(probe))
        assertEquals(listOf("system-su", "ksu-su"), attempts)
    }

    @Test fun `fresh explicit check clears a previously granted but now revoked cache`() {
        val access = RootAccess({ listOf("su") })
        assertEquals("su", access.resolve { _, _ -> ShellResult(0, "0") })
        val failed = access.check(20) { _, _ -> ShellResult(1, "denied") }
        assertNotEquals(0, failed.exitCode)
        assertFalse(failed.output.contains("ROOT_UID=0"))
        assertTrue(runCatching { access.resolve { _, _ -> ShellResult(1, "denied") } }.isFailure)
    }

    @Test fun `invalidate forces a new uid check`() {
        val access = RootAccess({ listOf("su") })
        var checks = 0
        val probe: (String, Long) -> ShellResult = { _, _ -> checks++; ShellResult(0, "0") }
        access.resolve(probe)
        access.invalidate()
        access.resolve(probe)
        assertEquals(2, checks)
    }

    @Test fun `zero exit status alone and uid-like substrings do not grant root`() {
        listOf("2000", "10", "uid=0", "ROOT_UID=0", "banner\n0", "").forEach { output ->
            val access = RootAccess({ listOf("su") })
            assertNotEquals(0, access.check(20) { _, _ -> ShellResult(0, output) }.exitCode)
        }
        val access = RootAccess({ listOf("su") })
        assertNotEquals(0, access.check(20) { _, _ -> ShellResult(1, "0") }.exitCode)
    }

    @Test fun `successful check reports a machine readable uid and selected path`() {
        val access = RootAccess({ listOf("/data/adb/ksu/bin/su") })
        assertEquals(ShellResult(0, "ROOT_UID=0\nSU_PATH=/data/adb/ksu/bin/su\n"),
            access.check(20) { _, _ -> ShellResult(0, "0\n") })
    }

    @Test fun `all candidates share the same timeout budget`() {
        var now = 0L
        val access = RootAccess({ listOf("first", "second") }, { now })
        val budgets = mutableListOf<Long>()
        access.check(20) { path, timeoutMillis ->
            budgets += timeoutMillis
            now += TimeUnit.SECONDS.toNanos(7)
            if (path == "first") ShellResult(1, "denied") else ShellResult(0, "0")
        }
        assertEquals(listOf(20_000L, 13_000L), budgets)
    }

    @Test fun `timeout stops candidate selection rather than leaving extra prompts`() {
        val attempts = mutableListOf<String>()
        val access = RootAccess({ listOf("first", "second") })
        val result = access.check(20) { path, _ -> attempts += path; ShellResult(124, "timeout") }
        assertEquals(124, result.exitCode)
        assertEquals(listOf("first"), attempts)
    }

    @Test fun `exhausted authorization budget never starts another candidate`() {
        var now = 0L
        val attempts = mutableListOf<String>()
        val access = RootAccess({ listOf("first", "second") }, { now })
        val result = access.check(1) { path, _ ->
            attempts += path
            now = TimeUnit.SECONDS.toNanos(1)
            ShellResult(1, "denied")
        }
        assertEquals(124, result.exitCode)
        assertEquals(listOf("first"), attempts)
    }

    @Test fun `a fractional millisecond of remaining authorization time is rounded up`() {
        var now = 0L
        val budgets = mutableListOf<Long>()
        val access = RootAccess({ listOf("first", "second") }, { now })
        access.check(1) { path, timeoutMillis ->
            budgets += timeoutMillis
            now += 500_001
            if (path == "first") ShellResult(1, "denied") else ShellResult(0, "0")
        }
        assertEquals(listOf(1_000L, 1_000L), budgets)
    }

    @Test fun `missing candidate can fall back and duplicate paths are probed once`() {
        val attempts = mutableListOf<String>()
        val access = RootAccess({ listOf("missing", "missing", "ksu") })
        assertEquals("ksu", access.resolve { path, _ ->
            attempts += path
            if (path == "missing") throw java.io.IOException("missing") else ShellResult(0, "0")
        })
        assertEquals(listOf("missing", "ksu"), attempts)
    }

    @Test fun `PATH su precedes explicit KSU and common fallback locations`() {
        val path = listOf("system-tools", "other-tools").joinToString(File.pathSeparator)
        val found = File("system-tools", "su").absolutePath
        val candidates = RootAccess.candidatePaths(path) { it == found }
        assertEquals(found, candidates.first())
        assertEquals("/data/adb/ksu/bin/su", candidates[1])
        assertTrue(candidates.indexOf("/system/bin/su") > candidates.indexOf("/data/adb/ksu/bin/su"))
        assertEquals("su", RootAccess.candidatePaths(null) { false }.first())
    }
}
