package dev.honor.bootforge.wireless

import dev.honor.bootforge.core.RootShell
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Execute the actual guard in a POSIX shell, using a fixture identity rather than host UID. */
class WirelessRootProviderTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun clearProvider() { RootShell.invalidate() }

    private fun provider(identity: String, started: MutableList<String> = mutableListOf()) =
        WirelessRootProvider { command ->
            started += command
            val gitShell = File("C:/Program Files/Git/bin/bash.exe")
            val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
            assumeTrue("POSIX shell required for executable guard fixtures", !windows || gitShell.isFile)
            val shell = if (windows) gitShell.absolutePath else "/bin/sh"
            // A script file avoids Windows/MSYS argv quoting changing double quotes.
            val script = temporary.newFile().apply { writeText("id() { $identity; }\n$command\n") }
            ProcessBuilder(shell, script.absolutePath.replace('\\', '/')).start()
        }

    @Test fun `a downgraded uid skips the payload in that same remote shell`() {
        val started = mutableListOf<String>()
        val process = provider("printf '2000\\n'", started).start("printf PAYLOAD_MUST_NOT_RUN")
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(126, process.exitValue())
        assertEquals("", process.inputStream.bufferedReader().readText())
        assertTrue(process.errorStream.bufferedReader().readText().contains("HNUBL_WIRELESS_ROOT_REQUIRED"))
        assertEquals(1, started.size)
    }

    @Test fun `a failed uid query with root looking stdout still skips the payload`() {
        val process = provider("printf '0\\n'; return 1").start("printf PAYLOAD_MUST_NOT_RUN")
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(126, process.exitValue())
        assertEquals("", process.inputStream.bufferedReader().readText())
    }

    @Test fun `root payload stdout stderr and exit status are preserved without replay`() {
        val started = mutableListOf<String>()
        val process = provider("printf '0\\n'", started).start("printf payload; printf diagnostic >&2; exit 9")
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(9, process.exitValue())
        assertEquals("payload", process.inputStream.bufferedReader().readText())
        assertEquals("diagnostic", process.errorStream.bufferedReader().readText())
        assertEquals(1, started.size)
    }

    @Test fun `binary backup and benign identity stderr survive the full root capture path`() {
        val provider = provider("printf '0\\n'; printf 'identity notice\\n' >&2")
        val registered = RootShell.setWirelessProvider(provider, File(temporary.root, "proof.log"))
        assertEquals(0, registered.exitCode)
        val bytes = ByteArray(256) { it.toByte() }
        val octal = bytes.joinToString("") { "\\%03o".format(it.toInt() and 255) }
        val image = File(temporary.root, "partition.img")
        val result = RootShell.capture("printf '$octal'; printf 'dd diagnostic\\n' >&2", image,
            File(temporary.root, "capture.log"), 5)
        assertEquals(0, result.exitCode)
        assertArrayEquals(bytes, image.readBytes())
        assertTrue(result.output.contains("dd diagnostic"))
        assertFalse(String(image.readBytes(), Charsets.ISO_8859_1).contains("identity notice"))
    }

    @Test fun `quotes and multiline shell input keep their original meaning`() {
        val command = "cat <<'HNUBL_EOF'\nquotes ' \" dollar ${'$'}HOME\nHNUBL_EOF"
        val process = provider("printf '0\\n'").start(command)
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertEquals("quotes ' \" dollar ${'$'}HOME\n", process.inputStream.bufferedReader().readText())
    }
}
