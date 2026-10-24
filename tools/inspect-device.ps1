[CmdletBinding()]
param(
    [string]$Serial,
    [string]$AdbPath = (Join-Path $PSScriptRoot '..\platform-tools\adb.exe'),
    [string]$ReportDirectory = (Join-Path $PSScriptRoot '..\reports')
)

$ErrorActionPreference = 'Stop'
$adbFile = (Resolve-Path -LiteralPath $AdbPath -ErrorAction Stop).Path

function Invoke-AdbCaptured {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$StandardInput = ''
    )

    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adbFile
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $start.StandardErrorEncoding = [System.Text.UTF8Encoding]::new($false)
    $start.StandardInputEncoding = [System.Text.UTF8Encoding]::new($false)
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        [void]$process.Start()
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if ($StandardInput.Length -gt 0) {
            # The shell receives an LF-only script through stdin; no device file is created.
            $process.StandardInput.Write($StandardInput.Replace("`r`n", "`n"))
        }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(60000)) {
            $process.Kill($true)
            $process.WaitForExit()
            throw '只读检查超过 60 秒，已停止本机 adb 子进程。设备未收到任何刷写或调整分区指令。'
        }
        [pscustomobject]@{
            ExitCode = $process.ExitCode
            Stdout = $stdoutTask.GetAwaiter().GetResult()
            Stderr = $stderrTask.GetAwaiter().GetResult()
        }
    }
    finally {
        $process.Dispose()
    }
}

$deviceResult = Invoke-AdbCaptured -Arguments @('devices')
if ($deviceResult.ExitCode -ne 0) { throw "adb devices 失败：$($deviceResult.Stderr.Trim())" }
$devices = @(
    foreach ($line in ($deviceResult.Stdout -split "`r?`n")) {
        if ($line -match '^([^\s]+)\s+(device|offline|unauthorized)\s*$') {
            [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    }
)

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $ready = @($devices | Where-Object State -eq 'device')
    if ($ready.Count -ne 1) {
        throw "已连接可用设备数量为 $($ready.Count)。请连接唯一设备，或通过 -Serial 指定设备。"
    }
    $Serial = $ready[0].Serial
}
else {
    if ($Serial -notmatch '^[A-Za-z0-9._:\-]+$') { throw 'Serial 包含不受支持的字符。' }
    $selected = @($devices | Where-Object { $_.Serial -eq $Serial -and $_.State -eq 'device' })
    if ($selected.Count -ne 1) { throw '指定设备尚未处于 device 状态，请检查 USB 调试授权与连接。' }
}

# Every device command below is an observation. In particular, resize.f2fs only
# receives version/help options and sgdisk only receives print/info/verify options.
$probe = @'
#!/system/bin/sh
export LC_ALL=C

section() { printf '\n========== %s ==========\n' "$1"; }
read_attr() {
    if [ -r "$1" ]; then
        printf '%s=' "$2"
        cat "$1"
    fi
}
resolve_block() {
    local link resolved
    for link in "/dev/block/by-name/$1" "/dev/block/bootdevice/by-name/$1" /dev/block/platform/*/by-name/"$1" /dev/block/platform/*/*/by-name/"$1"; do
        if [ -e "$link" ]; then
            resolved=$(readlink -f "$link" 2>/dev/null)
            if [ -b "$resolved" ]; then
                printf '%s\n' "$resolved"
                return
            fi
        fi
    done
}
partition_disk() {
    local block sys path parent
    block=${1##*/}
    sys="/sys/class/block/$block"
    [ -r "$sys/partition" ] || return
    path=$(readlink -f "$sys" 2>/dev/null)
    parent=${path%/*}
    printf '/dev/block/%s\n' "${parent##*/}"
}

section 'READ-ONLY INSPECTION / 只读检查：无刷写、无分区调整'
printf 'Probe transport: adb shell su -c sh, script over stdin\n'
printf 'No user files, application data, accounts, or device serial are collected.\n'
printf 'This report alone does not establish whether a filesystem supports shrinking.\n'

section 'Device / system'
for prop in ro.product.manufacturer ro.product.model ro.product.device ro.product.board ro.soc.model ro.build.version.release ro.build.version.sdk ro.build.version.security_patch; do
    printf '%s=%s\n' "$prop" "$(getprop "$prop")"
done
printf 'kernel='; uname -r
printf 'architecture='; uname -m
printf 'root='; id
if [ "$(id -u)" != '0' ]; then
    printf 'ERROR: root permission was not granted. The probe stopped before block inspection.\n'
    exit 3
fi

section 'Slot / encryption'
for prop in ro.boot.slot_suffix ro.boot.slot ro.crypto.state ro.crypto.type ro.crypto.volume.filenames_mode ro.crypto.metadata.enabled ro.boot.verifiedbootstate ro.boot.flash.locked; do
    printf '%s=%s\n' "$prop" "$(getprop "$prop")"
done

section 'Init namespace /data mountinfo'
if [ -r /proc/1/mountinfo ]; then
    awk '$5 == "/data" {print}' /proc/1/mountinfo
else
    printf '/proc/1/mountinfo is not readable\n'
fi
section 'Probe namespace /data mountinfo'
awk '$5 == "/data" {print}' /proc/self/mountinfo

USERDATA_DEV=$(resolve_block userdata)
EFISP_DEV=$(resolve_block efisp)
ABL_A_DEV=$(resolve_block abl_a)
ABL_B_DEV=$(resolve_block abl_b)
USERDATA_DISK=''
EFISP_DISK=''
USERDATA_NUMBER=''
EFISP_NUMBER=''
section 'Selected by-name partitions / sysfs partition numbers'
for name in userdata efisp abl_a abl_b; do
    case "$name" in
        userdata) dev=$USERDATA_DEV ;;
        efisp) dev=$EFISP_DEV ;;
        abl_a) dev=$ABL_A_DEV ;;
        abl_b) dev=$ABL_B_DEV ;;
    esac
    printf '%s: %s\n' "$name" "${dev:-not present}"
    [ -n "$dev" ] || continue
    block=${dev##*/}
    read_attr "/sys/class/block/$block/partition" 'partition_number'
    read_attr "/sys/class/block/$block/start" 'start_512byte_sectors'
    read_attr "/sys/class/block/$block/size" 'size_512byte_sectors'
    disk=$(partition_disk "$dev")
    printf 'parent_disk=%s\n' "${disk:-not a plain partition}"
    number=''
    [ ! -r "/sys/class/block/$block/partition" ] || number=$(cat "/sys/class/block/$block/partition")
    case "$name" in
        userdata) USERDATA_DISK=$disk; USERDATA_NUMBER=$number ;;
        efisp) EFISP_DISK=$disk; EFISP_NUMBER=$number ;;
    esac
done

section 'GPT read-only print / info / verify'
SGDISK=$(command -v sgdisk 2>/dev/null)
if [ -z "$SGDISK" ]; then
    printf 'sgdisk is not available in the current root PATH; nothing was installed.\n'
else
    GPT_SEEN=' '
    for disk in "$USERDATA_DISK" "$EFISP_DISK"; do
        [ -n "$disk" ] && [ -b "$disk" ] || continue
        case "$GPT_SEEN" in *" $disk "*) continue ;; esac
        GPT_SEEN="$GPT_SEEN$disk "
        printf '\n$ sgdisk --print %s\n' "$disk"
        "$SGDISK" --print "$disk" 2>&1
        if [ "$USERDATA_DISK" = "$disk" ] && [ -n "$USERDATA_NUMBER" ]; then
            printf '\n$ sgdisk --info=%s %s [userdata]\n' "$USERDATA_NUMBER" "$disk"
            "$SGDISK" "--info=$USERDATA_NUMBER" "$disk" 2>&1
        fi
        if [ "$EFISP_DISK" = "$disk" ] && [ -n "$EFISP_NUMBER" ]; then
            printf '\n$ sgdisk --info=%s %s [efisp]\n' "$EFISP_NUMBER" "$disk"
            "$SGDISK" "--info=$EFISP_NUMBER" "$disk" 2>&1
        fi
        printf '\n$ sgdisk --verify %s\n' "$disk"
        "$SGDISK" --verify "$disk" 2>&1
    done
fi

section 'userdata-linked holders / slaves tree and block sizes'
SEEN_BLOCKS=' '
BLOCK_COUNT=0
walk_userdata_chain() {
    local block indent sys sectors relation edge next parent_path parent queue
    block=$1
    indent=$2
    case "$SEEN_BLOCKS" in
        *" $block "*) printf '%s%s [already shown]\n' "$indent" "$block"; return ;;
    esac
    if [ "$BLOCK_COUNT" -ge 24 ]; then
        printf '%s[linked graph limit: 24 block devices]\n' "$indent"
        return
    fi
    sys="/sys/class/block/$block"
    [ -d "$sys" ] || return
    SEEN_BLOCKS="$SEEN_BLOCKS$block "
    BLOCK_COUNT=$((BLOCK_COUNT + 1))
    printf '%s%s\n' "$indent" "$block"
    if [ -r "$sys/dm/name" ]; then
        dm_name=$(cat "$sys/dm/name")
        printf '%s  dm_name=%s\n' "$indent" "$dm_name"
        # status queries target state without querying encryption key material.
        if command -v dmctl >/dev/null 2>&1; then dmctl status "$dm_name" 2>/dev/null; fi
    fi
    if [ -r "$sys/size" ]; then
        sectors=$(cat "$sys/size")
        # Android mksh arithmetic may be 32-bit even on aarch64.
        # awk doubles represent these device sizes exactly (less than 2^53).
        bytes=$(awk -v n="$sectors" 'BEGIN {if (n ~ /^[0-9]+$/ && n <= 17592186044416) printf "%.0f", n*512; else printf "unknown"}')
        printf '%s  size_512byte_sectors=%s bytes=%s\n' "$indent" "$sectors" "$bytes"
    fi
    queue="$sys/queue"
    if [ ! -d "$queue" ] && [ -r "$sys/partition" ]; then
        parent_path=$(readlink -f "$sys" 2>/dev/null)
        parent=${parent_path%/*}
        queue="$parent/queue"
    fi
    for field in logical_block_size physical_block_size minimum_io_size optimal_io_size; do
        if [ -r "$queue/$field" ]; then
            printf '%s  %s=%s\n' "$indent" "$field" "$(cat "$queue/$field")"
        fi
    done
    for relation in holders slaves; do
        for edge in "$sys/$relation"/*; do
            [ -e "$edge" ] || continue
            next=${edge##*/}
            printf '%s  %s ->\n' "$indent" "$relation"
            walk_userdata_chain "$next" "$indent    "
        done
    done
}
if [ -n "$USERDATA_DEV" ]; then
    walk_userdata_chain "${USERDATA_DEV##*/}" ''
else
    printf 'userdata was not resolved.\n'
fi

section 'Decrypted /data source: F2FS superblock bytes 0..127 at device offset 1024'
DATA_SOURCE=$(awk '$5 == "/data" {for (i=1;i<=NF;i++) if ($i == "-") {print $(i+2); exit}}' /proc/1/mountinfo 2>/dev/null)
if [ -z "$DATA_SOURCE" ]; then
    DATA_SOURCE=$(awk '$5 == "/data" {for (i=1;i<=NF;i++) if ($i == "-") {print $(i+2); exit}}' /proc/self/mountinfo)
fi
case "$DATA_SOURCE" in
    /dev/block/*)
        DATA_SOURCE=$(readlink -f "$DATA_SOURCE" 2>/dev/null)
        if [ -b "$DATA_SOURCE" ]; then
            printf 'decrypted_data_source=%s\n' "$DATA_SOURCE"
            printf '$ od -Ax -tx1 -N128 -j1024 [decrypted_data_source]\n'
            od -Ax -tx1 -N128 -j1024 "$DATA_SOURCE" 2>&1
        else
            printf 'The mount source did not resolve to a block device.\n'
        fi
        ;;
    *) printf 'No /dev/block source found for /data; no raw bytes were read.\n' ;;
esac

section 'Kernel F2FS resize symbols: names only'
if [ -r /proc/kallsyms ]; then
    awk '$3 ~ /f2fs.*resize|resize.*f2fs/ {print $3; n++; if (n >= 60) exit} END {if (n == 0) print "No matching symbol names were exposed."}' /proc/kallsyms
else
    printf '/proc/kallsyms is not readable.\n'
fi

section 'resize.f2fs version / help only (no filesystem argument)'
RESIZE_TOOL=$(command -v resize.f2fs 2>/dev/null)
if [ -n "$RESIZE_TOOL" ]; then
    printf '$ resize.f2fs -V\n'
    "$RESIZE_TOOL" -V 2>&1
    printf '\n$ resize.f2fs -h\n'
    "$RESIZE_TOOL" -h 2>&1
else
    printf 'resize.f2fs is not available in the current root PATH.\n'
fi

section 'END / 只读检查完成'
printf 'No partition table, filesystem, bootloader, or image was modified.\n'
exit 0
'@

$inspection = Invoke-AdbCaptured -Arguments @('-s', $Serial, 'shell', 'su', '-c', 'sh') -StandardInput ($probe + "`n")
[void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetFullPath($ReportDirectory))
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$reportPath = Join-Path ([System.IO.Path]::GetFullPath($ReportDirectory)) "device-readonly-$timestamp.txt"
$reportHeader = @"
honor_ubl 设备只读检查报告
只读，无刷写，无分区调整；未 push / install / reboot / sync。
生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
设备序列号：已省略
adb shell exit code：$($inspection.ExitCode)
说明：只读取设备与分区元数据，不读取用户文件、账号或应用数据。

"@
$report = $reportHeader + $inspection.Stdout
if (-not [string]::IsNullOrWhiteSpace($inspection.Stderr)) {
    $report += "`n========== adb / su stderr ==========`n" + $inspection.Stderr
}
[System.IO.File]::WriteAllText($reportPath, $report, [System.Text.UTF8Encoding]::new($false))
Write-Output "报告已保存：$reportPath"
if ($inspection.ExitCode -ne 0) { throw "只读检查未完整结束（退出码 $($inspection.ExitCode)），请查看报告。" }
