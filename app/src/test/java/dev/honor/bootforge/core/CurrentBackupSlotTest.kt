package dev.honor.bootforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentBackupSlotTest {
    @Test fun `selects currently running a from agreeing properties`() {
        assertEquals("_a", CurrentBackupSlot.select(mapOf("SLOT_SUFFIX" to "_a", "SLOT" to "a", "CMDLINE_SLOT_SUFFIX" to "_a")))
    }
    @Test fun `supports b and bootconfig fallback`() {
        assertEquals("_b", CurrentBackupSlot.select(mapOf("SLOT_SUFFIX" to "", "SLOT" to "", "BOOTCONFIG_SLOT_SUFFIX" to "_b")))
    }
    @Test fun `rejects conflicting running slot sources`() {
        assertTrue(runCatching { CurrentBackupSlot.select(mapOf("SLOT_SUFFIX" to "_a", "SLOT" to "b")) }.isFailure)
    }
    @Test fun `rejects absent or unexpected slot values`() {
        listOf(emptyMap(), mapOf("SLOT_SUFFIX" to ""), mapOf("SLOT_SUFFIX" to "_c"), mapOf("SLOT" to "../../userdata"))
            .forEach { assertTrue(runCatching { CurrentBackupSlot.select(it) }.isFailure) }
    }
    @Test fun `next active slot is never selected`() {
        assertEquals("_a", CurrentBackupSlot.select(mapOf("SLOT_SUFFIX" to "_a", "NEXT_ACTIVE_SLOT" to "_b")))
    }
}
