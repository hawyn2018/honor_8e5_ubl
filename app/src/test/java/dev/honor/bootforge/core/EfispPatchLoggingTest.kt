package dev.honor.bootforge.core

import net.hawyn.ablefisp.api.AblEfisp
import net.hawyn.ablefisp.io.ImageIo
import net.hawyn.ablefisp.patch.PatchMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class EfispPatchLoggingTest {
    @get:Rule val directory = TemporaryFolder()

    private fun localAbl(): File {
        val workspace = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .take(8).firstOrNull { File(it, "analysis/ablpatcher-fixtures/files/stock-container.fixture.elf").isFile }
        assumeTrue("Optional local firmware integration fixture", workspace != null)
        return File(workspace!!, "analysis/ablpatcher-fixtures/files/stock-container.fixture.elf")
    }

    private fun hex(value: Long) = "0x${value.toString(16).uppercase(Locale.ROOT)}"

    @Test fun logsDescribeEveryAppliedCorePatchWithoutChangingEitherModeOutput() {
        val source = localAbl()
        val original = source.readBytes()
        for (fakeLock in listOf(false, true)) {
            val mode = if (fakeLock) PatchMode.FREEDOM_FAKE_LOCK else PatchMode.FREEDOM
            val core = AblEfisp.patch(original, mode)
            val quiet = File(directory.root, "quiet-$fakeLock.img")
            val logged = File(directory.root, "logged-$fakeLock.img")
            val quietResult = EfispImagePatcher.patch(source, quiet, fakeLock)
            val lines = mutableListOf<String>()
            val loggedResult = EfispImagePatcher.patch(source, logged, fakeLock, onLog = { lines += it })

            assertArrayEquals("Logging must preserve the $mode image", quiet.readBytes(), logged.readBytes())
            assertArrayEquals("The source must remain unchanged", original, source.readBytes())
            assertEquals(quietResult.sha256, loggedResult.sha256)
            assertEquals(ImageIo.sha256(logged.readBytes()), loggedResult.sha256)
            assertEquals(core.patches.size, loggedResult.patchCount)
            val patchHeaders = lines.filter { Regex("补丁 \\d+/\\d+：").containsMatchIn(it) }
            assertEquals("One detail header is required for each $mode patch", core.patches.size, patchHeaders.size)
            core.patches.forEachIndexed { index, patch ->
                val header = patchHeaders[index]
                assertTrue(header, header.contains("补丁 ${index + 1}/${core.patches.size}：${patch.name}"))
                assertTrue(header, header.contains("RVA ${hex(patch.rva)}"))
                assertTrue(header, header.contains("PE 文件偏移 ${hex(patch.fileOffset.toLong())}"))
                assertTrue(header, header.contains(if (patch.alreadyApplied) "原已应用" else "已应用"))
                assertTrue(header, header.contains("文字定制后字节校验通过"))
                val lineIndex = lines.indexOf(header)
                assertTrue(lines[lineIndex + 1], lines[lineIndex + 1].contains(patch.before))
                assertTrue(lines[lineIndex + 2], lines[lineIndex + 2].contains(patch.after))
                val expected = patch.after.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                assertArrayEquals(patch.name, expected,
                    logged.readBytes().copyOfRange(patch.fileOffset, patch.fileOffset + expected.size))
            }
            assertTrue(lines.any { it.contains("源 SHA-256") && it.contains(core.sourceSha256) })
            assertTrue(lines.any { it.contains("核心修补后 SHA-256") && it.contains(core.outputSha256) })
            assertTrue(lines.any { it.contains("文字定制") })
            assertTrue(lines.any { it.contains("输出文件回读校验通过") })
            assertTrue(lines.any { it.contains("最终 efisp SHA-256") && it.contains(loggedResult.sha256) })
            assertTrue(lines.any { it.contains("修补完成") })
            assertFalse(lines.any { it.contains("修补失败") })
        }
    }

    @Test fun invalidSourceLogsFailureWithoutClaimingCompletionOrReplacingOutput() {
        val source = directory.newFile("invalid.img").apply { writeBytes(ByteArray(32)) }
        val output = directory.newFile("efisp.img").apply { writeText("previous verified output") }
        val lines = mutableListOf<String>()

        assertThrows(Exception::class.java) {
            EfispImagePatcher.patch(source, output, false, onLog = { lines += it })
        }

        assertEquals("previous verified output", output.readText())
        assertTrue(lines.any { it.contains("开始") })
        assertTrue(lines.any { it.contains("修补失败") })
        assertFalse(lines.any { it.contains("修补完成") || it.contains("输出文件回读校验通过") })
        assertEquals(2, directory.root.listFiles()!!.size)
    }

    @Test fun loggerFailureDoesNotInterruptOrChangeEitherModeOutput() {
        val source = localAbl()
        for (fakeLock in listOf(false, true)) {
            val quiet = File(directory.root, "quiet-$fakeLock.img")
            val logged = File(directory.root, "throwing-$fakeLock.img")
            EfispImagePatcher.patch(source, quiet, fakeLock)
            var called = 0
            val result = EfispImagePatcher.patch(source, logged, fakeLock, onLog = {
                called++
                throw IllegalStateException("logging sink unavailable")
            })
            assertTrue("The injected sink must actually be exercised", called > 0)
            assertArrayEquals(quiet.readBytes(), logged.readBytes())
            assertEquals(ImageIo.sha256(logged.readBytes()), result.sha256)
        }
    }
}
