package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test

class ProbeTest {
    @Test fun failureStillRetainsRootSlotAndBlockReason() {
        val probe = Probe.parse("noise\nROOT=1\nSLOT=a\nREADY=0\nBLOCK_REASON=userdata is mapped=dm-23\n")
        assertTrue(probe.root)
        assertFalse(probe.ready)
        assertEquals("a", probe.slot)
        assertEquals("userdata is mapped=dm-23", probe.blockReason)
    }
    @Test fun unknownOrMalformedSlotIsNeverAccepted() {
        listOf("", "_a", "a;dd", "unknown").forEach { value ->
            assertEquals("", Probe.parse("SLOT=$value\nREADY=1").slot)
        }
    }
    @Test fun pathsAreQuotedWithoutShellExpansion() {
        assertEquals("'file with spaces'", shellQuote("file with spaces"))
        assertEquals("'a'\"'\"'b'", shellQuote("a'b"))
        assertEquals("'\$(touch injected)'", shellQuote("\$(touch injected)"))
    }
    @Test fun inspectionLauncherNeverIncludesExecuteMode() {
        val command = scriptCommand("/data/user/0/dev.honor.bootforge/files/run/unlock.sh", "--check")
        assertTrue(command.contains("960046694400"))
        assertTrue(command.contains("--check"))
        assertFalse(command.contains("--execute"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun launcherRejectsInjectedMode() { scriptCommand("/tmp/unlock.sh", "--check; reboot") }
    @Test fun backupHandshakeRetainsHashKeysWithDigits() {
        val hash = "a".repeat(64)
        val probe = Probe.parse("BACKUP_READY=1\nMANIFEST_SHA256=$hash\nJOB_DIR=/dev/bootforge-123-456\nRESULT=backup-ready\n")
        assertEquals(hash, probe.values["MANIFEST_SHA256"])
        assertEquals("/dev/bootforge-123-456", probe.values["JOB_DIR"])
        assertFalse(probe.ready)
    }
    @Test fun laterFailureOverridesEarlierResult() {
        val probe = Probe.parse("RESULT=success\nEVENT=verification stopped\nRESULT=partial\nPE_VERIFIED=0\n")
        assertEquals("partial", probe.values["RESULT"])
        assertEquals("0", probe.values["PE_VERIFIED"])
    }
    @Test fun resumeArgumentsAreQuotedAsIndividualValues() {
        val command = scriptCommand("/tmp/online-create.sh", "--execute", listOf("--resume", "/dev/bootforge-123", "--backup-exported", "abc'\$(echo injected)"))
        assertTrue(command.contains("'--resume' '/dev/bootforge-123' '--backup-exported' 'abc'\"'\"'\$(echo injected)'"))
    }
    @Test fun launcherTriesExistingShellsBeforeBundledFallback() {
        val command = scriptCommand("/data/user/0/dev.honor.bootforge.write/files/run/unlock.sh", "--execute")
        val system = command.indexOf("/data/adb/ksu/bin/busybox")
        val bundled = command.indexOf("/data/user/0/dev.honor.bootforge.write/files/run/tools/busybox")
        assertTrue(system >= 0 && bundled > system)
    }
}
