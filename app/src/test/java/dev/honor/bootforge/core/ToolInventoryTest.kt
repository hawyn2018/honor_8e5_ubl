package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test

class ToolInventoryTest {
    private fun report(overrides: Map<String, String> = emptyMap(), completed: Boolean = true): ToolReport {
        val lines = buildList {
            add("TOOLS_PROTOCOL=1")
            ToolInventory.parse("").checks.forEach {
                add("TOOL_${it.id}=${overrides[it.id] ?: "ready"}")
                add("TOOL_${it.id}_SOURCE=bundled")
            }
            if (completed) add("TOOLS_COMPLETE=1")
        }
        return ToolInventory.parse(lines.joinToString("\n"))
    }

    @Test fun missingOptionalToolsLeaveExistingEfispCoreAvailable() {
        val report = report(mapOf("DM" to "missing", "E2FSCK" to "missing", "RESIZE2FS" to "missing"))
        assertTrue(report.complete)
        assertTrue(report.coreReady)
        assertFalse(report.onlineReady)
        assertFalse(report.offlineExt4Ready)
        assertEquals(3, report.warnings.size)
        assertTrue(report.warnings.any { it.contains("仅影响离线 ext4") })
        assertTrue(report.warnings.any { it.contains("仅影响在线 F2FS") })
    }

    @Test fun brokenRequiredAppletStopsCoreReadinessAndNamesTheTool() {
        val report = report(mapOf("APPLET_DD" to "unusable"))
        assertFalse(report.coreReady)
        assertFalse(report.onlineReady)
        assertTrue(report.warnings.single().contains("命令 dd"))
    }

    @Test fun interruptedOrMalformedReportIsNotReady() {
        assertFalse(report(completed = false).coreReady)
        assertFalse(report(mapOf("SGDISK" to "unexpected")).complete)
        assertTrue(ToolInventory.parse("TOOLS_PROTOCOL=1\nTOOLS_COMPLETE=1").warnings.any { it.contains("结果缺失") })
    }

    @Test fun sourceAndLastFailureArePreserved() {
        val first = report()
        val output = buildString {
            append("TOOLS_PROTOCOL=1\nTOOLS_COMPLETE=1\n")
            first.checks.forEach { append("TOOL_${it.id}=ready\nTOOL_${it.id}_SOURCE=system\n") }
            append("TOOL_SGDISK=unusable\nTOOL_SGDISK_DETAIL=version failed=126\nTOOL_SGDISK_SOURCE=none\n")
        }
        val parsed = ToolInventory.parse(output)
        assertFalse(parsed.coreReady)
        assertEquals(ToolSource.SYSTEM, parsed.checks.first { it.id == "GETPROP" }.source)
        assertEquals(ToolSource.NONE, parsed.checks.first { it.id == "SGDISK" }.source)
        assertTrue(parsed.warnings.single().contains("version failed=126"))
    }

    @Test fun probeCommandQuotesPathsAndOnlyInvokesReadOnlyCapabilities() {
        val path = "/data/local/a'\$(touch injected)/__APPLETS__"
        val command = ToolInventory.command(path)
        assertTrue(command.contains("TOOL_DIR=${shellQuote("$path/tools")}"))
        assertTrue(command.contains("--self-test"))
        assertTrue(command.contains("--version"))
        assertTrue(command.indexOf("if system_path sgdisk") < command.indexOf("elif sgdisk_works"))
        listOf("--execute", "/dev/block/", "--resize", "--install", "reboot").forEach {
            assertFalse("unexpected mutation token: $it", command.contains(it))
        }
        assertTrue(ToolInventory.requiredApplets.containsAll(listOf("dd", "blockdev", "tar", "sha256sum")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMultilineToolDirectory() { ToolInventory.command("/tmp/run\nnext-command") }
}
