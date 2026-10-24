package dev.honor.bootforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GptBackupDisksTest {
    private val required = setOf("init_boot_a", "vbmeta_a", "abl_a", "userdata")

    @Test fun `deduplicates multiple LUNs and sorts source aliases`() {
        val disks = GptBackupDisks.parse(protocol(
            record("userdata", "sda", "8:0"),
            record("vbmeta_a", "sdb", "8:16"),
            record("init_boot_a", "sdb", "8:16"),
            record("abl_a", "sdb", "8:16"),
            record("abl_a", "sdb", "8:16"),
        ), required)
        assertEquals(listOf("sda", "sdb"), disks.map { it.node })
        assertEquals("/dev/block/sda", disks[0].device)
        assertEquals(listOf("abl_a", "init_boot_a", "vbmeta_a"), disks[1].sources)
    }

    @Test fun `allows a device mapper source to reach multiple physical disks`() {
        val disks = GptBackupDisks.parse(protocol(
            record("userdata", "sda", "8:0"),
            record("userdata", "sdb", "8:16"),
            record("super", "sdb", "8:16"),
        ), setOf("userdata"))
        assertEquals(2, disks.size)
        assertEquals(listOf("super", "userdata"), disks[1].sources)
    }

    @Test fun `requires all expected sources and a nonempty disk set`() {
        reject(protocol(record("userdata")), required)
        reject("GPT_COMPLETE=1\n", emptySet())
    }

    @Test fun `requires exactly one final completion marker and rejects unknown output`() {
        reject(record("userdata"))
        reject(protocol(record("userdata")) + "GPT_COMPLETE=1\n")
        reject(protocol(record("userdata")) + record("userdata"))
        reject("unrelated output\n" + protocol(record("userdata")))
        reject("GPT_COMPLETE=0\n" + protocol(record("userdata")))
    }

    @Test fun `rejects conflicting geometry or node names for the same device number`() {
        reject(protocol(record("userdata"), record("super", bytes = 2097152, sectors = 4096)))
        reject(protocol(record("userdata"), record("super", logical = 4096)))
        reject(protocol(record("userdata"), record("super", node = "sdb")))
        reject(protocol(record("userdata"), record("super", majorMinor = "8:16")))
    }

    @Test fun `rejects device paths and alias traversal`() {
        listOf("../sda", "sda/../../x", "/dev/block/sda", "sda;id", "-sda", "sda.1").forEach { node ->
            reject(protocol(record("userdata", node = node)))
        }
        listOf("../userdata", "user..data", "user/data", "userdata|extra").forEach { alias ->
            reject(protocol(record(alias)), emptySet())
        }
        reject(protocol(record("userdata")), setOf("../userdata"))
    }

    @Test fun `rejects numeric overflow and inconsistent sector capacity`() {
        reject(protocol(record("userdata", bytes = Long.MAX_VALUE, sectors = Long.MAX_VALUE)))
        reject(protocol(record("userdata").replace("1048576", "9223372036854775808")))
        reject(protocol(record("userdata", sectors = 2049)))
        reject(protocol(record("userdata", bytes = 0, sectors = 0)))
        reject(protocol(record("userdata", majorMinor = "8:-1")))
        reject(protocol(record("userdata", majorMinor = "8:9223372036854775808")))
    }

    @Test fun `requires aligned power of two logical sectors within the supported range`() {
        listOf(0L, 256L, 768L, 131072L).forEach { logical -> reject(protocol(record("userdata", logical = logical))) }
        reject(protocol(record("userdata", bytes = 1049088, sectors = 2049, logical = 4096)))
        assertEquals(4096L, GptBackupDisks.parse(protocol(record("userdata", logical = 4096)), setOf("userdata")).single().logicalSectorBytes)
    }

    private fun record(source: String, node: String = "sda", majorMinor: String = "8:0", bytes: Long = 1048576,
        sectors: Long = 2048, logical: Long = 512): String =
        "GPT_DISK|$source|$node|$majorMinor|$bytes|$sectors|$logical"

    private fun protocol(vararg records: String): String = records.joinToString("\n", postfix = "\nGPT_COMPLETE=1\n")

    private fun reject(output: String, sources: Set<String> = setOf("userdata")) {
        assertTrue("Malformed disk discovery was accepted", runCatching { GptBackupDisks.parse(output, sources) }.isFailure)
    }
}
