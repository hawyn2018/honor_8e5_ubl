package net.hawyn.ablefisp.io

data class RootStatus(val available: Boolean, val message: String)

data class AblExtraction(
    val bytes: ByteArray,
    val sourcePath: String,
    val slotSuffix: String?,
    val sha256: String,
    val sourceName: String,
)

/** Read-only extraction. This class contains no partition writes or slot-changing commands. */
class RootAblExtractor internal constructor(private val runner: RootCommandRunner) {
    constructor() : this(SuCommandRunner())

    fun checkRoot(timeoutMillis: Long = 20_000): RootStatus = try {
        val result = runner.run("id -u", 4096, timeoutMillis)
        if (result.exitCode == 0 && result.stdout.toString(Charsets.UTF_8).trim() == "0") {
            RootStatus(true, "已获得 Root 权限")
        } else {
            RootStatus(false, "Root 授权尚未完成，请检查超级用户授权")
        }
    } catch (error: Exception) {
        RootStatus(false, error.message ?: "Root 检查失败")
    }

    fun extractCurrentAbl(timeoutMillis: Long = 60_000): AblExtraction {
        require(timeoutMillis > 0)
        val metadata = runner.run(PROBE_SCRIPT, 64 * 1024, timeoutMillis)
        if (metadata.exitCode != 0) throw AblReadException("ABL 信息读取失败，请确认已授予 Root 权限")
        val selected = AblSourceSelection.parse(metadata.stdout.toString(Charsets.UTF_8)).select()
        val command = readScript(selected)
        val result = runner.run(command, ImageIo.MAX_INPUT_BYTES, timeoutMillis)
        if (result.exitCode != 0) {
            throw AblReadException("ABL 读取未完成（退出码 ${result.exitCode}），源分区未作修改")
        }
        if (result.stdout.size.toLong() != selected.partition.bytes) {
            throw AblReadException("ABL 读取长度与分区大小不一致，请重新提取")
        }
        if (result.stdout.isEmpty()) throw AblReadException("ABL 分区返回空内容")
        return AblExtraction(
            bytes = result.stdout,
            sourcePath = selected.partition.resolvedPath,
            slotSuffix = selected.slotSuffix,
            sha256 = ImageIo.sha256(result.stdout),
            sourceName = selected.partition.name + ".img",
        )
    }

    private fun readScript(source: SelectedAblSource): String {
        val item = source.partition
        return """
            [ "${'$'}(id -u)" = 0 ] || exit 60
            bf_link=${shellQuote(item.linkPath)}
            bf_expected=${shellQuote(item.resolvedPath)}
            bf_target=${'$'}(readlink -f "${'$'}bf_link" 2>/dev/null) || exit 61
            [ "${'$'}bf_target" = "${'$'}bf_expected" ] || exit 62
            [ -b "${'$'}bf_target" ] || exit 63
            bf_size=${'$'}(blockdev --getsize64 "${'$'}bf_target" 2>/dev/null)
            case "${'$'}bf_size" in ''|*[!0-9]*)
                bf_sectors=${'$'}(cat "/sys/class/block/${'$'}{bf_target##*/}/size" 2>/dev/null)
                case "${'$'}bf_sectors" in ''|*[!0-9]*) exit 64;; esac
                bf_size=${'$'}((bf_sectors * 512));;
            esac
            [ "${'$'}bf_size" = "${item.bytes}" ] || exit 65
            exec cat "${'$'}bf_target"
        """.trimIndent()
    }

    companion object {
        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        internal val PROBE_SCRIPT: String = """
            [ "${'$'}(id -u)" = 0 ] || exit 60
            printf 'ROOT=0\n'
            printf 'SLOT_SUFFIX=%s\n' "${'$'}(getprop ro.boot.slot_suffix 2>/dev/null)"
            printf 'SLOT=%s\n' "${'$'}(getprop ro.boot.slot 2>/dev/null)"
            bf_bootctl=${'$'}(bootctl get-current-slot 2>/dev/null)
            case "${'$'}bf_bootctl" in 0|1) ;; *) bf_bootctl=;; esac
            printf 'BOOTCTL=%s\n' "${'$'}bf_bootctl"
            for bf_dir in /dev/block/bootdevice/by-name /dev/block/by-name /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name /dev/block/platform/*/*/*/by-name; do
                [ -d "${'$'}bf_dir" ] || continue
                for bf_name in abl_a abl_b abl; do
                    bf_link="${'$'}bf_dir/${'$'}bf_name"
                    [ -e "${'$'}bf_link" ] || continue
                    bf_target=${'$'}(readlink -f "${'$'}bf_link" 2>/dev/null) || continue
                    case "${'$'}bf_target" in /dev/block/*) ;; *) continue;; esac
                    [ -b "${'$'}bf_target" ] || continue
                    bf_size=${'$'}(blockdev --getsize64 "${'$'}bf_target" 2>/dev/null)
                    case "${'$'}bf_size" in ''|*[!0-9]*)
                        bf_sectors=${'$'}(cat "/sys/class/block/${'$'}{bf_target##*/}/size" 2>/dev/null)
                        case "${'$'}bf_sectors" in ''|*[!0-9]*) continue;; esac
                        bf_size=${'$'}((bf_sectors * 512));;
                    esac
                    printf 'PART|%s|%s|%s|%s\n' "${'$'}bf_name" "${'$'}bf_link" "${'$'}bf_target" "${'$'}bf_size"
                done
            done
        """.trimIndent()
    }
}

internal data class AblPartition(val name: String, val linkPath: String, val resolvedPath: String, val bytes: Long)
internal data class SelectedAblSource(val slotSuffix: String?, val partition: AblPartition)

/** Selection is separate from shell execution so conflicting slot evidence is testable. */
internal data class AblSourceSelection(
    val suffixProperty: String,
    val slotProperty: String,
    val bootctl: String,
    val partitions: List<AblPartition>,
) {
    fun select(): SelectedAblSource {
        val evidence = listOfNotNull(
            normalizeSlot(suffixProperty, "slot_suffix"),
            normalizeSlot(slotProperty, "slot"),
            when (bootctl) { "" -> null; "0" -> "_a"; "1" -> "_b"; else -> throw AblReadException("bootctl 槽位结果异常") },
        ).distinct()
        if (evidence.size > 1) throw AblReadException("当前槽位信息相互冲突，提取已停止")
        val slot = evidence.singleOrNull()
        if (slot == null && partitions.any { it.name == "abl_a" || it.name == "abl_b" }) {
            throw AblReadException("检测到双槽位 ABL，但当前槽位尚未确定")
        }
        // Some A/B Android devices use one shared, unsuffixed ABL. Accept it only
        // when no suffixed ABL was reported, rather than selecting the other slot.
        val hasSlottedAbl = partitions.any { it.name == "abl_a" || it.name == "abl_b" }
        val sharedAbl = !hasSlottedAbl && partitions.any { it.name == "abl" }
        val sourceSlot = if (sharedAbl) null else slot
        val name = if (sourceSlot == null) "abl" else "abl$sourceSlot"
        val candidates = partitions.filter { it.name == name }
        if (candidates.isEmpty()) throw AblReadException("未找到${if (slot == null) "无槽位" else "当前槽位 $slot 的"} ABL 分区")
        val unique = candidates.distinctBy { it.resolvedPath to it.bytes }
        if (unique.size != 1) throw AblReadException("当前槽位对应多个不同 ABL 分区，提取已停止")
        val selected = unique.single()
        if (selected.bytes <= 0 || selected.bytes > ImageIo.MAX_INPUT_BYTES) {
            throw AblReadException("ABL 分区大小超出支持范围（1 字节至 64 MiB）")
        }
        return SelectedAblSource(sourceSlot, selected)
    }

    companion object {
        private val safePath = Regex("^/dev/block/[A-Za-z0-9_./:-]+$")

        fun parse(text: String): AblSourceSelection {
            val values = linkedMapOf<String, String>()
            val partitions = mutableListOf<AblPartition>()
            for (line in text.lineSequence().filter(String::isNotBlank)) {
                if (line.startsWith("PART|")) {
                    val fields = line.split('|')
                    if (fields.size != 5) throw AblReadException("ABL 分区信息格式异常")
                    val name = fields[1]
                    if (name !in setOf("abl", "abl_a", "abl_b")) throw AblReadException("ABL 分区名称异常")
                    val link = fields[2]
                    val resolved = fields[3]
                    if (!validPath(link) || !validPath(resolved) || !link.contains("/by-name/") || !link.endsWith("/$name")) {
                        throw AblReadException("ABL 分区路径验证失败")
                    }
                    val size = fields[4].toLongOrNull() ?: throw AblReadException("ABL 分区大小格式异常")
                    partitions += AblPartition(name, link, resolved, size)
                } else {
                    val split = line.indexOf('=')
                    if (split <= 0) throw AblReadException("Root 返回信息格式异常")
                    val key = line.substring(0, split)
                    if (key !in setOf("ROOT", "SLOT_SUFFIX", "SLOT", "BOOTCTL") || values.put(key, line.substring(split + 1)) != null) {
                        throw AblReadException("Root 返回信息包含重复或未知字段")
                    }
                }
            }
            if (values["ROOT"] != "0") throw AblReadException("本次读取未取得 UID 0")
            return AblSourceSelection(values["SLOT_SUFFIX"].orEmpty(), values["SLOT"].orEmpty(), values["BOOTCTL"].orEmpty(), partitions)
        }

        private fun validPath(path: String): Boolean =
            path.length <= 512 && safePath.matches(path) && path.split('/').none { it == "." || it == ".." }

        private fun normalizeSlot(raw: String, label: String): String? = when (raw) {
            "" -> null
            "a", "_a" -> "_a"
            "b", "_b" -> "_b"
            else -> throw AblReadException("$label 返回了未识别的槽位")
        }
    }
}
