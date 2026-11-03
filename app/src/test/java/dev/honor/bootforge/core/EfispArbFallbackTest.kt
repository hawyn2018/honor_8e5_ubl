package dev.honor.bootforge.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class EfispArbFallbackTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun counter(value: Long) = AblRollbackInspector.Result.Parsed(AblRollbackInspector.Format.SIGNED_ELF,
        listOf(AblRollbackInspector.Counter(AblRollbackInspector.Authority.OEM, value, "fixture", 0)), emptyList())
    private val noCounter = AblRollbackInspector.Result.NoMetadata(AblRollbackInspector.Format.EFI_PE, emptyList())
    private fun patch(source: File, destination: File, fakeLock: Boolean, log: (String) -> Unit): EfispImagePatcher.Result {
        destination.writeText("${source.readText()}:$fakeLock")
        return EfispImagePatcher.Result(destination.length(), "a".repeat(64), source.name.padEnd(64, '0').take(64),
            "fixture", fakeLock, 1, 2, emptyList())
    }

    @Test fun zeroAbsentAndUnknownProductsRetainOriginalFileAndNeverReadFallbackSources() {
        val initial = temporary.newFile("selected.img").apply { writeText("selected") }
        val results = listOf(counter(0), noCounter, AblRollbackInspector.Result.Invalid("unknown metadata"),
            AblRollbackInspector.Result.Parsed(AblRollbackInspector.Format.SIGNED_MBN, emptyList(), emptyList()))
        results.forEach { result ->
            val prepared = EfispArbFallback.prepareWith(initial, { error("current source must stay unused") },
                { error("builtin source must stay unused") }, temporary.root, true, {}, { result },
                { _, _, _, _ -> error("no patch should run") })
            assertEquals(initial, prepared.file)
            assertEquals(EfispArbFallback.Source.SELECTED, prepared.source)
            assertEquals(if (result == results.first()) "0" else "absent", prepared.arbBinding)
        }
    }

    @Test fun positiveSelectedProductUsesFrozenCurrentSourceAndPreservesPatchMode() {
        listOf(false, true).forEach { fakeLock ->
            val initial = File(temporary.root, "selected-$fakeLock.img").apply { writeText("selected") }
            val current = File(temporary.root, "current-$fakeLock.img").apply { writeText("frozen-current") }
            val inspections = mutableListOf<File>()
            val patches = mutableListOf<File>()
            val logs = mutableListOf<String>()
            val prepared = EfispArbFallback.prepareWith(initial, { current }, { error("builtin must stay unused") },
                temporary.root, fakeLock, logs::add, { file ->
                    inspections += file
                    if (file == initial) counter(1) else noCounter
                }, { source, destination, mode, log ->
                    patches += source
                    assertEquals(fakeLock, mode)
                    patch(source, destination, mode, log)
                })
            assertEquals(listOf(current), patches)
            assertEquals(listOf(initial, prepared.file), inspections)
            assertEquals("frozen-current:$fakeLock", prepared.file.readText())
            assertEquals(EfispArbFallback.Source.CURRENT_SLOT_ABL, prepared.source)
            assertEquals("absent", prepared.arbBinding)
            assertTrue(logs.any { it.contains("已固定的当前槽位") })
        }
    }

    @Test fun secondPositiveProductUsesBuiltinAndNeverInspectsThatLastProduct() {
        val initial = temporary.newFile("selected.img").apply { writeText("selected") }
        val current = temporary.newFile("current.img").apply { writeText("frozen-current") }
        val builtin = temporary.newFile("builtin.img").apply { writeText("builtin") }
        val inspections = mutableListOf<File>()
        val sources = mutableListOf<File>()
        val prepared = EfispArbFallback.prepareWith(initial, { current }, { builtin }, temporary.root, true, {},
            { file -> inspections += file; check(!file.name.contains("builtin")); counter(7) },
            { source, destination, mode, log -> sources += source; patch(source, destination, mode, log) })
        assertEquals(listOf(current, builtin), sources)
        assertEquals(2, inspections.size)
        assertEquals("builtin:true", prepared.file.readText())
        assertEquals(EfispArbFallback.Source.BUILTIN_ABL, prepared.source)
        assertEquals("builtin-unchecked", prepared.arbBinding)
        assertNull(prepared.rollback)
        assertEquals(prepared.patchResult!!.sourceSha256, prepared.sourceSha256)
    }

    @Test fun actualReadFailureStopsAndDoesNotBecomeAnArbFallbackTrigger() {
        val initial = temporary.newFile("selected.img")
        assertThrows(IOException::class.java) {
            EfispArbFallback.prepareWith(initial, { error("current must stay unused") },
                { error("builtin must stay unused") }, temporary.root, false, {},
                { throw IOException("read failed") }, ::patch)
        }
        val current = temporary.newFile("current.img")
        assertThrows(IOException::class.java) {
            EfispArbFallback.prepareWith(initial, { current }, { error("builtin must stay unused") },
                temporary.root, false, {}, { file -> if (file == initial) counter(1) else throw IOException("current read failed") },
                { source, destination, mode, log -> source.writeText("current"); patch(source, destination, mode, log) })
        }
    }

    @Test fun readableUnknownBytesAreNoCounterWhileMissingEmptyAndDirectoryInputsStop() {
        val unknown = temporary.newFile("unknown.img").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val inspected = AblRollbackGuardPolicy.inspectReadable(unknown)
        assertTrue(inspected is AblRollbackInspector.Result.Invalid)
        assertEquals(AblRollbackGuardPolicy.Decision.Allowed, AblRollbackGuardPolicy.finalEfisp(inspected))
        assertEquals("absent", AblRollbackGuardPolicy.bindingValue(inspected))
        listOf(File(temporary.root, "missing.img"), temporary.newFile("empty.img"), temporary.newFolder("directory"))
            .forEach { assertThrows(IllegalStateException::class.java) { AblRollbackGuardPolicy.inspectReadable(it) } }
    }
}
