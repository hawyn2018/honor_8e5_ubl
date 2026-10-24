package dev.honor.bootforge.core

data class Probe(val values: Map<String, String>) {
    val root: Boolean get() = values["ROOT"] == "1"
    val ready: Boolean get() = values["READY"] == "1"
    val slot: String get() = values["SLOT"].orEmpty().takeIf { it == "a" || it == "b" }.orEmpty()
    val mode: String get() = values["MODE"].orEmpty()
    val blockReason: String get() = values["BLOCK_REASON"].orEmpty()
    companion object {
        fun parse(text: String) = Probe(text.lineSequence().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split > 0 && line[0] in 'A'..'Z' && line.substring(0, split).all { it in 'A'..'Z' || it in '0'..'9' || it == '_' })
                line.substring(0, split) to line.substring(split + 1).trim()
            else null
        }.toMap())
    }
}

fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

fun scriptCommand(path: String, mode: String, extraArguments: List<String> = emptyList()): String {
    require(mode in listOf("--check", "--execute"))
    val capability = "test \"\$((1875091200 * 512))\" = 960046694400 && test 960046694400 -gt 2147483647"
    val arguments = (listOf(path, mode) + extraArguments).joinToString(" ", transform = ::shellQuote)
    val bundled = path.substringBeforeLast('/') + "/tools/busybox"
    return "for bb in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /sbin/busybox /system/xbin/busybox ${shellQuote(bundled)}; do " +
        "if [ -x \"\$bb\" ] && \"\$bb\" sh -c ${shellQuote(capability)} 2>/dev/null; then " +
        "exec \"\$bb\" sh $arguments; fi; done; exec sh $arguments"
}
