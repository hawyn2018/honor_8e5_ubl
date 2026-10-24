package dev.honor.bootforge.core

/** Preparation probes only packaged executables and tool capabilities; it never opens a device. */
object ToolInventory {
    val requiredApplets: List<String> = listOf(
        "sh", "id", "awk", "sed", "grep", "tr", "readlink", "cat", "wc", "sha256sum",
        "mkdir", "rmdir", "rm", "mv", "dd", "od", "head", "tail", "blockdev", "sort", "cp", "chmod",
        "date", "sync", "df", "blkid", "tar", "sleep",
    )

    private data class Specification(val id: String, val label: String, val group: ToolGroup)
    private val specifications: List<Specification> = listOf(
        Specification("BUSYBOX", "内置 BusyBox（64 位算术）", ToolGroup.CORE),
        Specification("SGDISK", "内置 sgdisk", ToolGroup.CORE),
        Specification("FSPROBE", "内置 fsprobe", ToolGroup.CORE),
        Specification("PARTITION_WORKER", "内置 partition_worker", ToolGroup.CORE),
        Specification("GPTTOOL", "内置 GPT 原始保留工具", ToolGroup.CORE),
        Specification("GETPROP", "系统 getprop", ToolGroup.CORE),
    ) + requiredApplets.map { Specification("APPLET_${it.uppercase()}", "命令 $it", ToolGroup.CORE) } + listOf(
        Specification("DM", "dmsetup / dmctl / 内置 dmprobe", ToolGroup.ONLINE_F2FS),
        Specification("E2FSCK", "e2fsck", ToolGroup.OFFLINE_EXT4),
        Specification("RESIZE2FS", "resize2fs", ToolGroup.OFFLINE_EXT4),
        Specification("DUMPE2FS", "dumpe2fs", ToolGroup.OFFLINE_EXT4),
    )

    /** Pass this command to RootShell after staging. All probes are independent of model and images. */
    fun command(runDirectory: String): String {
        require(runDirectory.startsWith("/") && '\u0000' !in runDirectory && '\n' !in runDirectory && '\r' !in runDirectory) {
            "Tool directory must be an absolute single-line path"
        }
        val script = """
            export LC_ALL=C
            TOOL_DIR=__TOOL_DIR__
            BB=__BUSYBOX__
            FSPROBE=__FSPROBE__
            WORKER=__WORKER__
            GPTTOOL=__GPTTOOL__
            SYSTEM_PATH="/sbin:/system/bin:/system/xbin:/vendor/bin:§{PATH:-}"
            export PATH="§SYSTEM_PATH"
            emit() { printf '%s\n' "TOOL_§1=§2" "TOOL_§{1}_DETAIL=§3" "TOOL_§{1}_SOURCE=§4"; }
            printf '%s\n' 'TOOLS_PROTOCOL=1'
            system_path() {
                candidate=§(command -v "§1" 2>/dev/null) || candidate=
                case "§candidate" in "§TOOL_DIR"/*) candidate= ;; esac
                [ -n "§candidate" ] && [ -f "§candidate" ] && [ -x "§candidate" ]
            }
            help_works() {
                help_output=§("§1" --help 2>&1); help_rc=§?
                [ "§help_rc" = 0 ] && return 0
                case "§help_output" in *Usage:*|*usage:*) return 0 ;; *) return 1 ;; esac
            }
            busybox_works() {
                [ -f "§1" ] && [ -x "§1" ] &&
                "§1" sh -c 'test "§((1875091200 * 512))" = 960046694400 && test 960046694400 -gt 2147483647' >/dev/null 2>&1 &&
                "§1" --list >/dev/null 2>&1
            }
            ACTIVE_BB=
            bb_candidate=§(command -v busybox 2>/dev/null) || bb_candidate=
            for candidate in "§bb_candidate" /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /sbin/busybox /system/xbin/busybox; do
                case "§candidate" in ''|"§TOOL_DIR"/*) continue ;; esac
                if busybox_works "§candidate"; then ACTIVE_BB=§candidate; break; fi
            done
            if [ -n "§ACTIVE_BB" ]; then
                emit BUSYBOX ready "§ACTIVE_BB（64位算术就绪）" system
            elif busybox_works "§BB"; then
                ACTIVE_BB=§BB
                emit BUSYBOX ready "§BB（系统不可用，使用内置64位shell）" bundled
            elif [ ! -e "§BB" ]; then
                emit BUSYBOX missing '系统与内置BusyBox均缺少可用64位shell' none
            else
                emit BUSYBOX unusable '系统与内置BusyBox的64位shell检查均失败' none
            fi
            for applet in __APPLETS__; do
                case "§applet" in
                    __APPLET_IDS__
                esac
                if [ "§applet" = sh ] && [ -n "§ACTIVE_BB" ]; then
                    case "§ACTIVE_BB" in "§TOOL_DIR"/*) source=bundled ;; *) source=system ;; esac
                    emit "§applet_id" ready "§ACTIVE_BB sh" "§source"
                elif system_path "§applet" && help_works "§candidate"; then
                    emit "§applet_id" ready "§candidate" system
                elif [ -f "§TOOL_DIR/§applet" ] && [ -x "§TOOL_DIR/§applet" ] && help_works "§TOOL_DIR/§applet"; then
                    emit "§applet_id" ready "§TOOL_DIR/§applet（系统不可用，使用内置）" bundled
                elif [ ! -e "§TOOL_DIR/§applet" ]; then
                    emit "§applet_id" missing "系统与内置均缺少可用§applet" none
                else
                    emit "§applet_id" unusable "系统与内置§applet均未通过运行检查" none
                fi
            done
            sgdisk_works() {
                [ -f "§1" ] && [ -x "§1" ] || return 1
                sgdisk_info=§("§1" --version 2>&1) || return 1
                case "§sgdisk_info" in *sgdisk*|*'GPT fdisk'*) return 0 ;; *) return 1 ;; esac
            }
            if system_path sgdisk && sgdisk_works "§candidate"; then
                emit SGDISK ready "§candidate" system
            elif sgdisk_works "§TOOL_DIR/sgdisk"; then
                emit SGDISK ready "§TOOL_DIR/sgdisk（系统不可用，使用内置）" bundled
            elif [ ! -e "§TOOL_DIR/sgdisk" ]; then
                emit SGDISK missing '系统与内置均缺少可用sgdisk' none
            else
                emit SGDISK unusable '系统与内置sgdisk版本命令均失败' none
            fi
            if [ ! -e "§FSPROBE" ]; then
                emit FSPROBE missing '内置文件缺失' none
            elif [ ! -f "§FSPROBE" ] || [ ! -x "§FSPROBE" ]; then
                emit FSPROBE unusable '文件不可执行' none
            elif probe_info=§("§FSPROBE" --self-test 2>&1); then
                case "§probe_info" in
                    *'SELF_TEST_RESULT=PASS'*) emit FSPROBE ready '内存自检通过' bundled ;;
                    *) emit FSPROBE unusable '自检结果未识别' none ;;
                esac
            else emit FSPROBE unusable '内存自检失败' none; fi
            if [ ! -e "§WORKER" ]; then
                emit PARTITION_WORKER missing '内置文件缺失' none
            elif [ ! -f "§WORKER" ] || [ ! -x "§WORKER" ]; then
                emit PARTITION_WORKER unusable '文件不可执行' none
            elif worker_info=§("§WORKER" --self-test 2>&1); then
                case "§worker_info" in
                    *'RESULT=success'*) emit PARTITION_WORKER ready '算术自检通过' bundled ;;
                    *) emit PARTITION_WORKER unusable '自检结果未识别' none ;;
                esac
            else emit PARTITION_WORKER unusable '算术自检失败' none; fi
            if [ ! -e "§GPTTOOL" ]; then
                emit GPTTOOL missing '内置 GPT 工具文件缺失' none
            elif [ ! -f "§GPTTOOL" ] || [ ! -x "§GPTTOOL" ]; then
                emit GPTTOOL unusable 'GPT 工具不可执行' none
            elif gpt_info=§("§GPTTOOL" --self-test 2>&1); then
                case "§gpt_info" in
                    *'RESULT=success'*) emit GPTTOOL ready 'GPT 内存自检通过' bundled ;;
                    *) emit GPTTOOL unusable 'GPT 自检结果未识别' none ;;
                esac
            else emit GPTTOOL unusable 'GPT 内存自检失败' none; fi
            inspect_path() {
                if system_path "§2" && help_works "§candidate"; then emit "§1" ready "§candidate" system
                elif [ -f "§TOOL_DIR/§2" ] && [ -x "§TOOL_DIR/§2" ] && help_works "§TOOL_DIR/§2"; then emit "§1" ready "§TOOL_DIR/§2（系统不可用，使用内置）" bundled
                elif [ ! -e "§TOOL_DIR/§2" ]; then emit "§1" missing "系统与内置均缺少可用§2" none
                else emit "§1" unusable "系统与内置§2均未通过运行检查" none; fi
            }
            inspect_path GETPROP getprop
            dm_path= dm_source=
            for utility in dmsetup dmctl; do
                if system_path "§utility" && help_works "§candidate"; then dm_path=§candidate; dm_source=system; break; fi
            done
            if [ -z "§dm_path" ]; then
                for utility in dmsetup dmctl; do
                    if [ -f "§TOOL_DIR/§utility" ] && [ -x "§TOOL_DIR/§utility" ] && help_works "§TOOL_DIR/§utility"; then dm_path=§TOOL_DIR/§utility; dm_source=bundled; break; fi
                done
            fi
            if [ -z "§dm_path" ] && [ -f "§TOOL_DIR/dmprobe" ] && [ -x "§TOOL_DIR/dmprobe" ]; then
                if dm_info=§("§TOOL_DIR/dmprobe" --self-test 2>&1); then
                    case "§dm_info" in *'RESULT=success'*) dm_path=§TOOL_DIR/dmprobe; dm_source=bundled ;; esac
                fi
            fi
            if [ -n "§dm_path" ]; then emit DM ready "§dm_path" "§dm_source"
            else emit DM missing '系统dmsetup/dmctl与内置dmprobe均不可用' none; fi
            inspect_path E2FSCK e2fsck
            inspect_path RESIZE2FS resize2fs
            inspect_path DUMPE2FS dumpe2fs
            printf '%s\n' 'TOOLS_COMPLETE=1'
        """.trimIndent().replace('§', '$')
        val replacements = mapOf(
            "__TOOL_DIR__" to shellQuote("$runDirectory/tools"),
            "__BUSYBOX__" to shellQuote("$runDirectory/tools/busybox"),
            "__FSPROBE__" to shellQuote("$runDirectory/fsprobe"),
            "__WORKER__" to shellQuote("$runDirectory/partition_worker"),
            "__GPTTOOL__" to shellQuote("$runDirectory/gpttool"),
            "__APPLETS__" to requiredApplets.joinToString(" ", transform = ::shellQuote),
            "__APPLET_IDS__" to requiredApplets.joinToString("\n") { "${shellQuote(it)}) applet_id=APPLET_${it.uppercase()} ;;" },
        )
        return Regex("__[A-Z_]+__").replace(script) { replacements.getValue(it.value) }
    }

    fun parse(output: String): ToolReport {
        val values = output.lineSequence().mapNotNull { raw ->
            val line = raw.trimEnd('\r')
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
        }.toMap()
        val complete = values["TOOLS_PROTOCOL"] == "1" && values["TOOLS_COMPLETE"] == "1" &&
            specifications.all {
                val status = values["TOOL_${it.id}"]
                status in setOf("ready", "missing", "unusable") &&
                    (status != "ready" || values["TOOL_${it.id}_SOURCE"] in setOf("system", "bundled"))
            }
        val checks = specifications.map { specification ->
            val status = when (values["TOOL_${specification.id}"]) {
                "ready" -> ToolStatus.READY
                "missing" -> ToolStatus.MISSING
                else -> ToolStatus.UNUSABLE
            }
            ToolCheck(
                specification.id, specification.label, specification.group, status,
                values["TOOL_${specification.id}_DETAIL"].orEmpty().ifBlank {
                    if (values["TOOL_${specification.id}"] == null) "检查结果缺失" else status.description
                },
                when (values["TOOL_${specification.id}_SOURCE"]) {
                    "system" -> ToolSource.SYSTEM
                    "bundled" -> ToolSource.BUNDLED
                    else -> ToolSource.NONE
                },
            )
        }
        return ToolReport(complete, checks)
    }
}

enum class ToolGroup { CORE, ONLINE_F2FS, OFFLINE_EXT4 }
enum class ToolSource { SYSTEM, BUNDLED, NONE }
enum class ToolStatus(val description: String) { READY("已就绪"), MISSING("缺失"), UNUSABLE("不可用") }
data class ToolCheck(val id: String, val label: String, val group: ToolGroup, val status: ToolStatus, val detail: String, val source: ToolSource = ToolSource.NONE)
data class ToolReport(val complete: Boolean, val checks: List<ToolCheck>) {
    val coreReady: Boolean get() = complete && ready(ToolGroup.CORE)
    val onlineReady: Boolean get() = coreReady && ready(ToolGroup.ONLINE_F2FS)
    val offlineExt4Ready: Boolean get() = coreReady && ready(ToolGroup.OFFLINE_EXT4)
    val warnings: List<String> get() = buildList {
        if (!complete) add("工具检查报告不完整，请重新准备工具")
        checks.filter { it.status != ToolStatus.READY }.forEach { tool ->
            val scope = when (tool.group) {
                ToolGroup.CORE -> ""
                ToolGroup.ONLINE_F2FS -> "（仅影响在线 F2FS 创建 efisp）"
                ToolGroup.OFFLINE_EXT4 -> "（仅影响离线 ext4 缩容）"
            }
            add("${tool.label}：${tool.detail}$scope")
        }
    }
    private fun ready(group: ToolGroup): Boolean = checks.filter { it.group == group }.all { it.status == ToolStatus.READY }
}
