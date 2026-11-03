package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomSuSettingsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `first install keeps custom selection disabled`() {
        assertEquals(CustomSuSettings(), store().read())
        assertFalse(store().read().enabled)
    }

    @Test fun `arbitrary absolute executable is preserved without probing the filesystem`() {
        val settings = CustomSuSettings(true, "/bin/renamed su; literal ' $ (tool)")
        assertNull(settings.validationError())
        store().save(settings)
        assertEquals(settings, store().read())
        assertEquals(listOf(settings.path), RootAccess.candidatePaths(null, settings))
    }

    @Test fun `enabled path must be absolute and contain no nul`() {
        listOf("", "  ", "666", "bin/666", "/bin/\u0000su").forEach { path ->
            assertNotNull(path, CustomSuSettings(true, path).validationError())
            assertTrue(runCatching { store().save(CustomSuSettings(true, path)) }.isFailure)
        }
        assertNull(CustomSuSettings(true, "/bin/666").validationError())
    }

    @Test fun `disabling retains path and new store instances see each complete saved setting`() {
        val first = store()
        val second = store()
        val enabled = CustomSuSettings(true, "/bin/666")
        first.save(enabled)
        assertEquals(enabled, second.read())
        val disabled = enabled.copy(enabled = false)
        second.save(disabled)
        assertEquals(disabled, first.read())
        assertEquals(listOf("custom-su.properties"), temporary.root.list()!!.toList())
    }

    @Test fun `an incomplete or malformed record is reported instead of silently selecting another provider`() {
        val file = File(temporary.root, "custom-su.properties")
        listOf("", "format=1\n", "format=2\nenabled=true\npath=/bin/666\n",
            "format=1\nenabled=perhaps\n").forEach { text ->
            file.writeText(text)
            assertTrue(runCatching { store().read() }.isFailure)
        }
    }

    @Test fun `invalid save leaves previous usable configuration intact`() {
        val settings = CustomSuSettings(true, "/bin/666")
        store().save(settings)
        val before = File(temporary.root, "custom-su.properties").readBytes()
        assertTrue(runCatching { store().save(CustomSuSettings(true, "relative-su")) }.isFailure)
        assertArrayEquals(before, File(temporary.root, "custom-su.properties").readBytes())
        assertEquals(settings, store().read())
    }

    @Test fun `disabled draft can be retained and nested app private directory is created`() {
        val nested = CustomSuSettingsStore.inDirectory(File(temporary.root, "private/settings"))
        val settings = CustomSuSettings(false, "draft path")
        nested.save(settings)
        assertEquals(settings, nested.read())
    }

    private fun store() = CustomSuSettingsStore.inDirectory(temporary.root)
}
