package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecoveryScriptFormatTest {
    @Test fun unixScriptKeepsItsExactContents() {
        val script = "#!/system/bin/sh\nset -u\nprintf '%s\\n' 'ROOT=1'\n"
        assertEquals(script, normalizeShellScript(script))
    }

    @Test fun windowsBomAndMixedLineEndingsBecomeExecutableUnixText() {
        val script = "\uFEFF#!/system/bin/sh\r\nset -u\rprintf '%s\\n' 'ROOT=1'\n"
        val expected = "#!/system/bin/sh\nset -u\nprintf '%s\\n' 'ROOT=1'\n"
        assertEquals(expected, normalizeShellScript(script))
        assertEquals(expected, normalizeShellScript(normalizeShellScript(script)))
    }

    @Test fun packagedRecoveryEnginesHaveNoBomOrCarriageReturns() {
        val assets = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/assets/recovery") }
            .firstOrNull { it.isDirectory }
            ?: error("Recovery script source directory was not found")
        listOf("unlock.sh", "online-create.sh", "tool-dispatch.sh").forEach { name ->
            val script = File(assets, name).readText(Charsets.UTF_8)
            assertFalse("$name contains a UTF-8 BOM", script.startsWith("\uFEFF"))
            assertFalse("$name contains Windows or bare CR line endings", script.contains('\r'))
            assertTrue("$name must end with LF", script.endsWith('\n'))
            assertEquals("$name should already be normalized", script, normalizeShellScript(script))
        }
    }
}
