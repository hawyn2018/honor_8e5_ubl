package dev.honor.bootforge

import android.content.Context
import dev.honor.bootforge.core.Probe
import dev.honor.bootforge.core.RootShell
import dev.honor.bootforge.core.ToolInventory
import dev.honor.bootforge.core.ToolReport
import dev.honor.bootforge.core.ToolSource
import dev.honor.bootforge.core.ToolStatus
import dev.honor.bootforge.core.shellQuote
import java.io.File
import java.nio.file.Files

internal object RuntimeTools {
    fun inspect(context: Context, directory: File): ToolReport {
        val result = RootShell.run(ToolInventory.command(directory.absolutePath), File(context.filesDir, "tools.log"), 60)
        val report = ToolInventory.parse(result.output)
        val selected = File(directory, "selected-tools").apply { mkdirs() }
        val names = ToolInventory.requiredApplets + listOf("sgdisk", "e2fsck", "resize2fs", "dumpe2fs")
        for (name in names) {
            val id = if (name in ToolInventory.requiredApplets) "APPLET_${name.uppercase()}" else name.uppercase()
            val check = report.checks.singleOrNull { it.id == id }
            val link = File(selected, name).toPath()
            if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.delete(link)
            if (check?.source == ToolSource.BUNDLED && check.status == ToolStatus.READY) {
                Files.createSymbolicLink(link, File("../tools/$name").toPath())
            }
        }
        return report
    }

    fun mode(context: Context): String {
        val fallbackPath = "/system/bin:/system/xbin:/vendor/bin:/sbin:${File(context.filesDir, "run/tools").absolutePath}"
        val result = RootShell.run("PATH=${shellQuote(fallbackPath)}:\"\$PATH\"; export PATH; test \"\$(id -u)\" = 0 || exit 1; " +
            "if [ -b /dev/block/by-name/efisp ]; then printf 'MODE=reuse\\n'; else printf 'MODE=online-create\\n'; fi",
            File(context.filesDir, "resolve-target.log"), 30)
        val mode = Probe.parse(result.output).mode
        check(result.exitCode == 0 && mode in listOf("reuse", "online-create")) { "读取分区入口失败，请检查 Root 授权\n${result.output}" }
        return mode
    }
}
