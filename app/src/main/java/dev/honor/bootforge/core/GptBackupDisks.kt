package dev.honor.bootforge.core

data class GptBackupDisk(
    val node: String,
    val device: String,
    val majorMinor: String,
    val bytes: Long,
    val sectors512: Long,
    val logicalSectorBytes: Long,
    val sources: List<String>,
)

/** Discovers only physical disks reached through the device's named internal partitions. */
object GptBackupDisks {
    private val nodePattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
    private val aliasPattern = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")
    private val numberPattern = Regex("[0-9]+")

    /** Run after SlotPartitionBackup's root/tool prelude. Every operation is a read. */
    val script: String = """
        gpt_emit_disk() {
            local G_ALIAS="${'$'}1" G_NODE="${'$'}2" G_SYS G_DEV G_BYTES G_SIZE G_LOGICAL
            case "${'$'}G_NODE" in ''|*[!A-Za-z0-9_-]*) return 61;; esac
            G_SYS=${'$'}(tool readlink -f "/sys/class/block/${'$'}G_NODE") || return 62
            case "${'$'}G_SYS" in /sys/devices/virtual/*) return 0;; /sys/devices/*) :;; *) return 63;; esac
            [ ! -f "${'$'}G_SYS/partition" ] || return 64
            [ -b "/dev/block/${'$'}G_NODE" ] || return 65
            G_DEV=${'$'}(tool cat "${'$'}G_SYS/dev") || return 66
            G_BYTES=${'$'}(tool blockdev --getsize64 "/dev/block/${'$'}G_NODE") || return 67
            G_SIZE=${'$'}(tool cat "${'$'}G_SYS/size") || return 68
            G_LOGICAL=${'$'}(tool cat "${'$'}G_SYS/queue/logical_block_size") || return 69
            printf 'GPT_DISK|%s|%s|%s|%s|%s|%s\n' "${'$'}G_ALIAS" "${'$'}G_NODE" "${'$'}G_DEV" "${'$'}G_BYTES" "${'$'}G_SIZE" "${'$'}G_LOGICAL"
        }
        gpt_follow_disk() {
            local G_ALIAS="${'$'}1" G_NODE="${'$'}2" G_DEPTH="${'$'}3" G_SYS G_PARENT G_SLAVE G_FOUND=0
            [ "${'$'}G_DEPTH" -le 16 ] || return 70
            case "${'$'}G_NODE" in ''|*[!A-Za-z0-9_-]*) return 71;; esac
            G_SYS=${'$'}(tool readlink -f "/sys/class/block/${'$'}G_NODE") || return 72
            case "${'$'}G_SYS" in /sys/devices/*) :;; *) return 73;; esac
            if [ -f "${'$'}G_SYS/partition" ]; then
                G_PARENT=${'$'}{G_SYS%/*}
                gpt_emit_disk "${'$'}G_ALIAS" "${'$'}{G_PARENT##*/}"
                return "${'$'}?"
            fi
            case "${'$'}G_NODE" in
                dm-*)
                    for G_SLAVE in "${'$'}G_SYS"/slaves/*; do
                        [ -e "${'$'}G_SLAVE" ] || continue
                        G_FOUND=1
                        gpt_follow_disk "${'$'}G_ALIAS" "${'$'}{G_SLAVE##*/}" "${'$'}((G_DEPTH + 1))" || return "${'$'}?"
                    done
                    [ "${'$'}G_FOUND" = 1 ] || return 74
                    ;;
                *) gpt_emit_disk "${'$'}G_ALIAS" "${'$'}G_NODE" || return "${'$'}?";;
            esac
        }
        for G_LINK in /dev/block/by-name/* /dev/block/bootdevice/by-name/*; do
            [ -L "${'$'}G_LINK" ] || continue
            G_SOURCE=${'$'}{G_LINK##*/}
            case "${'$'}G_SOURCE" in ''|*[!A-Za-z0-9_.-]*) continue;; esac
            G_TARGET=${'$'}(tool readlink -f "${'$'}G_LINK") || continue
            [ -b "${'$'}G_TARGET" ] || continue
            case "${'$'}G_TARGET" in /dev/block/*) :;; *) exit 75;; esac
            gpt_follow_disk "${'$'}G_SOURCE" "${'$'}{G_TARGET##*/}" 0 || exit "${'$'}?"
        done
        printf 'GPT_COMPLETE=1\n'
    """.trimIndent()

    fun parse(output: String, requiredSources: Set<String>): List<GptBackupDisk> {
        check(requiredSources.all { aliasPattern.matches(it) && !it.contains("..") }) { "必需分区名称无效" }
        val disks = linkedMapOf<String, GptBackupDisk>()
        val deviceNumbers = mutableMapOf<String, String>()
        var complete = false
        output.lineSequence().forEach { raw ->
            val line = raw.removeSuffix("\r")
            if (line.isEmpty()) return@forEach
            check(!complete) { "分区表磁盘发现结束后仍有输出" }
            if (line == "GPT_COMPLETE=1") {
                complete = true
                return@forEach
            }
            val fields = line.split('|')
            check(fields.size == 7 && fields[0] == "GPT_DISK") { "分区表磁盘发现输出无效" }
            val source = fields[1]
            val node = fields[2]
            check(aliasPattern.matches(source) && !source.contains("..")) { "分区表来源名称无效" }
            check(nodePattern.matches(node)) { "分区表磁盘设备名称无效" }
            val dev = fields[3].split(':')
            check(dev.size == 2) { "分区表磁盘设备号无效" }
            fun unsigned(value: String): Long = value.takeIf { numberPattern.matches(it) }?.toLongOrNull()
                ?: error("分区表磁盘数值无效")
            val majorMinor = "${unsigned(dev[0])}:${unsigned(dev[1])}"
            val previousNumber = deviceNumbers.put(node, majorMinor)
            check(previousNumber == null || previousNumber == majorMinor) { "同一分区表磁盘名称的设备号发生变化" }
            val bytes = unsigned(fields[4])
            val sectors = unsigned(fields[5])
            val logical = unsigned(fields[6])
            check(bytes > 0 && sectors > 0 && sectors <= Long.MAX_VALUE / 512L && sectors * 512L == bytes) {
                "分区表磁盘容量校验失败"
            }
            check(logical in 512L..65536L && logical and (logical - 1L) == 0L && bytes % logical == 0L) {
                "分区表磁盘逻辑扇区无效"
            }
            val disk = GptBackupDisk(node, "/dev/block/$node", majorMinor, bytes, sectors, logical, listOf(source))
            check(disks.values.none { it.node == node && it.majorMinor != majorMinor }) {
                "同一磁盘设备名称对应了不同设备号"
            }
            val previous = disks[majorMinor]
            if (previous == null) {
                disks[majorMinor] = disk
            } else {
                check(previous.copy(sources = emptyList()) == disk.copy(sources = emptyList())) {
                    "同一分区表磁盘的设备名称或几何信息不一致"
                }
                disks[majorMinor] = previous.copy(sources = (previous.sources + source).distinct().sorted())
            }
        }
        check(complete) { "分区表磁盘发现未完整结束" }
        check(disks.isNotEmpty()) { "未发现可备份分区表的物理磁盘" }
        val foundSources = disks.values.flatMap { it.sources }.toSet()
        check(foundSources.containsAll(requiredSources)) { "分区表磁盘发现缺少必需分区来源：${(requiredSources - foundSources).sorted().joinToString()}" }
        return disks.values.sortedBy { it.node }
    }
}
