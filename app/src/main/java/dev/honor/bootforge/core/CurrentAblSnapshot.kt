package dev.honor.bootforge.core

import android.content.Context
import java.io.File

/** Read-only snapshot through the application's verified Root command provider. */
object CurrentAblSnapshot {
    data class Identity(val slot: String, val device: String, val majorMinor: String, val bytes: Long)
    data class Snapshot(val identity: Identity, val file: File, val sha256: String)

    @JvmOverloads
    fun extract(context: Context, runtime: File, destination: File, useOtherSlot: Boolean = false): Snapshot {
        val before = identity(context, runtime, useOtherSlot)
        check(before.bytes in 1..(64L * 1024 * 1024)) { "所选 ABL 超过修补引擎支持的 64 MiB 输入范围" }
        val source = shellQuote(before.device)
        val error = File(context.filesDir, "abl-extract-error.log")
        val command = "[ -b $source ] || exit 1; exec dd if=$source bs=4096 iflag=direct"
        var result = RootShell.capture(command, destination, error, 120)
        if (result.exitCode != 0 || destination.length() != before.bytes) {
            result = RootShell.capture("[ -b $source ] || exit 1; exec ${shellQuote(File(runtime, "tools/busybox").absolutePath)} dd if=$source bs=4096 iflag=direct",
                destination, error, 120)
        }
        check(result.exitCode == 0 && destination.length() == before.bytes) { "所选槽位 ABL 直接提取失败：${result.output}" }
        check(identity(context, runtime, useOtherSlot) == before) { "提取期间运行槽位或来源 ABL 分区身份发生变化" }
        return Snapshot(before, destination, Payloads.sha256(destination))
    }

    private fun identity(context: Context, runtime: File, useOtherSlot: Boolean): Identity {
        val busybox = shellQuote(File(runtime, "tools/busybox").absolutePath)
        val script = """
            tool() { "${'$'}@" 2>/dev/null || $busybox "${'$'}@"; }
            [ "${'$'}(id -u)" = 0 ] || exit 1
            printf 'SLOT_SUFFIX='; getprop ro.boot.slot_suffix
            printf 'SLOT='; getprop ro.boot.slot
            printf 'CMDLINE_SLOT_SUFFIX='
            for ARG in ${'$'}(tool cat /proc/cmdline); do
                case "${'$'}ARG" in androidboot.slot_suffix=*) printf '%s' "${'$'}{ARG#*=}";; esac
            done
            printf '\nBOOTCONFIG_SLOT_SUFFIX='
            if [ -r /proc/bootconfig ]; then
                tool awk '${'$'}1 == "androidboot.slot_suffix" {gsub(/\"/, "", ${'$'}3); printf "%s", ${'$'}3}' /proc/bootconfig
            fi
            printf '\n'
        """.trimIndent()
        val slotResult = RootShell.run(script, File(context.filesDir, "abl-extract-slot.log"), 30)
        check(slotResult.exitCode == 0) { slotResult.output }
        val runningSlot = CurrentBackupSlot.select(Probe.parse(slotResult.output).values)
        val slot = ablSnapshotSourceSlot(runningSlot, useOtherSlot)
        val currentSlot = ablSnapshotSourceSlot(runningSlot, false)
        val geometry = """
            tool() { "${'$'}@" 2>/dev/null || $busybox "${'$'}@"; }
            resolve_abl() {
                NAME=${'$'}1 FOUND=
                for BASE in /dev/block/by-name /dev/block/bootdevice/by-name; do
                    CANDIDATE=${'$'}(tool readlink -f "${'$'}BASE/${'$'}NAME") || continue
                    [ -b "${'$'}CANDIDATE" ] || continue
                    case "${'$'}CANDIDATE" in /dev/block/*) :;; *) return 1;; esac
                    [ -z "${'$'}FOUND" ] || [ "${'$'}FOUND" = "${'$'}CANDIDATE" ] || return 1
                    FOUND=${'$'}CANDIDATE
                done
                [ -n "${'$'}FOUND" ] || return 1
                printf '%s\n' "${'$'}FOUND"
            }
            SRC=${'$'}(resolve_abl abl_$slot) || exit 1
            NODE=${'$'}{SRC##*/}
            [ -f "/sys/class/block/${'$'}NODE/partition" ] || exit 1
            if [ ${if (useOtherSlot) 1 else 0} = 1 ]; then
                CURRENT=${'$'}(resolve_abl abl_$currentSlot) || exit 1
                [ "${'$'}SRC" != "${'$'}CURRENT" ] || exit 1
                PARTNAME=${'$'}(tool awk -F= '${'$'}1 == "PARTNAME" {print ${'$'}2}' "/sys/class/block/${'$'}NODE/uevent") || exit 1
                [ "${'$'}PARTNAME" = abl_$slot ] || exit 1
                SOURCE_MM=${'$'}(tool cat "/sys/class/block/${'$'}NODE/dev") || exit 1
                CURRENT_NODE=${'$'}{CURRENT##*/}
                CURRENT_MM=${'$'}(tool cat "/sys/class/block/${'$'}CURRENT_NODE/dev") || exit 1
                [ "${'$'}SOURCE_MM" != "${'$'}CURRENT_MM" ] || exit 1
            fi
            printf 'RUNNING_SLOT=%s\nSOURCE_SLOT=%s\n' $currentSlot $slot
            printf 'DEVICE=%s\n' "${'$'}SRC"
            printf 'MAJOR_MINOR='; tool cat "/sys/class/block/${'$'}NODE/dev" || exit 1
            printf 'BYTES='; tool blockdev --getsize64 "${'$'}SRC" || exit 1
        """.trimIndent()
        val result = RootShell.run(geometry, File(context.filesDir, "abl-extract-identity.log"), 30)
        check(result.exitCode == 0) { result.output }
        val values = Probe.parse(result.output).values
        val device = values["DEVICE"].orEmpty()
        val mm = values["MAJOR_MINOR"].orEmpty()
        val bytes = values["BYTES"]?.toLongOrNull() ?: error("ABL 容量读取失败")
        check(Regex("/dev/block/[A-Za-z0-9_./-]+").matches(device) && !device.contains("..")) { "ABL 设备路径无效" }
        check(Regex("[0-9]+:[0-9]+").matches(mm) && bytes > 0) { "ABL 分区身份无效" }
        return Identity(slot, device, mm, bytes)
    }
}
