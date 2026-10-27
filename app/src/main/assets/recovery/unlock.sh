#!/sbin/sh
# BootForge recovery engine. Invoke with sh unlock.sh --check|--execute.
# No reboot, slot switch, automatic GPT rollback, or formatting.
# Custom ABL writes affect only the explicitly selected current/opposite/both slots.
# --check performs reads only, including e2fsck -fn and resize2fs -P when needed.
# Payloads and their generated payloads.sha256 must be next to this script.
set -u
umask 077
export LC_ALL=C
# The app stages a single-level tool directory with a BusyBox applet link farm.
# Derive this hint without a utility dependency so those applets work immediately.
SCRIPT_DIRECTORY_HINT=${0%/*}
export PATH="$SCRIPT_DIRECTORY_HINT/selected-tools:/sbin:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin:$SCRIPT_DIRECTORY_HINT/tools:${PATH:-}"
[ -r "$SCRIPT_DIRECTORY_HINT/tool-dispatch.sh" ] || { printf '%s\n' 'READY=0' 'BLOCK_REASON=tool dispatch helper is missing'; exit 1; }
. "$SCRIPT_DIRECTORY_HINT/tool-dispatch.sh"

MODE_ARG=${1:---check}
case "$MODE_ARG" in --check|--execute) ;; *) printf '%s\n' 'READY=0' 'BLOCK_REASON=usage: sh unlock.sh --check|--execute [--backup-dir PATH]'; exit 2 ;; esac
shift $(( $# > 0 ? 1 : 0 ))
BACKUP_BASE_OVERRIDE=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --backup-dir) [ "$#" -ge 2 ] || exit 2; BACKUP_BASE_OVERRIDE=$2; shift 2 ;;
        *) printf '%s\n' 'READY=0' 'BLOCK_REASON=unknown argument'; exit 2 ;;
    esac
done

ROOT=0 DEVICE=unknown SLOT=unknown USERDATA=unknown FILESYSTEM=unknown
MANUFACTURER=unknown SOC=unknown
ARITHMETIC64=0
EFISP=absent ABL=unknown PLAN_MODE=unknown BLOCK_REASON=unknown
RAW_FILESYSTEM=unknown DATA_SOURCE=unknown DATA_FS=unknown DATA_CHAIN=unknown
BACKUP_DIR= STAGE=preflight
ABL_MAY_BE_MODIFIED=0 PE_VERIFIED=0
FILESYSTEM_MAY_BE_MODIFIED=0 GPT_MAY_BE_MODIFIED=0 GPT_VERIFIED=0
EFI_BYTES=67108864
PE_HASH= ABL_HASH= PAYLOAD_MANIFEST_HASH=
SOURCE_BINDING_HASH=not-present
ABL_WRITE_TARGET=CURRENT ABL_TARGET_SLOTS=unknown ABL_TARGET_PARTITIONS=unknown ABL_TARGETS_VERIFIED=0
GPT_BEFORE_HASH= GPT_PLANNED_HASH=not-applicable GPTTOOL_HASH= PLAN_ID=unprepared
EFI_PROFILE=efisp-common-v1
EFI_LABEL=efisp
EFI_TYPE=C12A7328-F81F-11D2-BA4B-00A0C93EC93B
# No attribute bit is shared by all three inspected devices. New entries use
# that intersection; a reused entry retains its own GUID and attributes.
EFI_ATTR=0000000000000000
EFI_GUID=unknown EFI_TARGET_ATTR=unknown EFI_METADATA_PROFILE=unplanned
TAIL_FREE_BYTES=0 SKIP_F2FS_SHRINK=0 FS_READ_MODE=not-required

report() {
    printf '%s\n' "ROOT=$ROOT" "DEVICE=$DEVICE" "MANUFACTURER=$MANUFACTURER" "SOC=$SOC" "SLOT=$SLOT" \
        "ARITHMETIC64=$ARITHMETIC64" \
        "USERDATA=$USERDATA" "FILESYSTEM=$FILESYSTEM" "EFISP=$EFISP" \
        "ABL=$ABL" "ABL_WRITE_TARGET=$ABL_WRITE_TARGET" "ABL_TARGET_SLOTS=$ABL_TARGET_SLOTS" "ABL_TARGET_PARTITIONS=$ABL_TARGET_PARTITIONS" "ABL_TARGETS_VERIFIED=$ABL_TARGETS_VERIFIED" "MODE=$PLAN_MODE" "RAW_FILESYSTEM=$RAW_FILESYSTEM" \
        "DATA_SOURCE=$DATA_SOURCE" "DATA_FS=$DATA_FS" "DATA_CHAIN=$DATA_CHAIN" \
        "EFI_PROFILE=$EFI_METADATA_PROFILE" "EFI_LABEL=$EFI_LABEL" "EFI_TYPE=$EFI_TYPE" \
        "EFI_ATTR=$EFI_TARGET_ATTR" "EFI_GUID=$EFI_GUID" \
        "EFI_BYTES=$EFI_BYTES" "TAIL_FREE_BYTES=$TAIL_FREE_BYTES" "SKIP_F2FS_SHRINK=$SKIP_F2FS_SHRINK" "FS_READ_MODE=$FS_READ_MODE" \
        "READY=$1" "BLOCK_REASON=$2"
}
write_failure_state() {
    printf '%s\n' "FILESYSTEM_MAY_BE_MODIFIED=$FILESYSTEM_MAY_BE_MODIFIED" \
        "GPT_MAY_BE_MODIFIED=$GPT_MAY_BE_MODIFIED" "GPT_VERIFIED=$GPT_VERIFIED" \
        "ABL_MAY_BE_MODIFIED=$ABL_MAY_BE_MODIFIED" "PE_VERIFIED=$PE_VERIFIED"
    if [ "$FILESYSTEM_MAY_BE_MODIFIED$GPT_MAY_BE_MODIFIED$ABL_MAY_BE_MODIFIED" != 000 ]; then
        printf '%s\n' 'RESULT=partial'
    else printf '%s\n' "RESULT=$1"; fi
}
die() {
    BLOCK_REASON=$1
    if [ "$MODE_ARG" = --check ]; then report 0 "$1"; else
        printf '%s\n' "ERROR=$1" "STAGE=$STAGE" "BACKUP_DIR=$BACKUP_DIR"
        write_failure_state blocked
    fi
    exit 1
}
log() { printf '%s\n' "EVENT=$*"; }
is_uint() { case "$1" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }
is_hash() { printf '%s\n' "$1" | grep -Eq '^[0-9a-f]{64}$'; }
hash_file() { sha256sum "$1" | awk '{print $1}'; }
is_guid() { printf '%s\n' "$1" | grep -Eq '^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$'; }
kv() { printf '%s\n' "$1" | sed -n "s/^$2=//p"; }

# BEGIN payload source binding
# Parse data only, never shell-source the app-generated provenance file.
# Absence is retained solely for older manual packages and fixture inputs.
load_payload_source() {
    SOURCE_BINDING_FILE=$1 SOURCE_BINDING_HASH=not-present
    ABL_WRITE_TARGET=CURRENT
    SOURCE_REQUEST_MODE= SOURCE_EFI_KIND= SOURCE_PATCH_MODE=
    SOURCE_SLOT= SOURCE_ABL_DEVICE= SOURCE_ABL_MM= SOURCE_ABL_CAPACITY= SOURCE_ABL_SHA256=
    [ -e "$SOURCE_BINDING_FILE" ] || [ -L "$SOURCE_BINDING_FILE" ] || return 0
    [ -f "$SOURCE_BINDING_FILE" ] && [ -r "$SOURCE_BINDING_FILE" ] && [ ! -L "$SOURCE_BINDING_FILE" ] || die 'payload source binding is not a regular readable file'
    awk -F= '
        NF != 2 || $2 == "" {bad=1; next}
        $1 !~ /^(REQUEST_MODE|EFI_SOURCE|PATCH_MODE|ABL_WRITE_TARGET|SOURCE_SLOT|SOURCE_ABL_DEVICE|SOURCE_ABL_MM|SOURCE_ABL_CAPACITY|SOURCE_ABL_SHA256)$/ {bad=1; next}
        ++seen[$1] != 1 {bad=1; next}
        {v[$1]=$2}
        END {
            if(v["REQUEST_MODE"] !~ /^(FAKE_LOCK|DIRECT|CUSTOM)$/ ||
               v["EFI_SOURCE"] !~ /^(UPLOADED_EFISP|UPLOADED_ABL|CURRENT_SLOT_ABL|OTHER_SLOT_ABL|BUILTIN_ABL)$/ ||
               v["PATCH_MODE"] !~ /^(FAKE_LOCK|FREEDOM)$/) bad=1
            if(seen["ABL_WRITE_TARGET"] && v["ABL_WRITE_TARGET"] !~ /^(CURRENT|OTHER|BOTH)$/) bad=1
            if(seen["ABL_WRITE_TARGET"] && v["ABL_WRITE_TARGET"] != "CURRENT" && v["REQUEST_MODE"] != "CUSTOM") bad=1
            if(v["EFI_SOURCE"]=="OTHER_SLOT_ABL" && v["REQUEST_MODE"]!="CUSTOM") bad=1
            if(seen["SOURCE_ABL_SHA256"] && (length(v["SOURCE_ABL_SHA256"])!=64 || v["SOURCE_ABL_SHA256"] ~ /[^0-9a-f]/)) bad=1
            if(v["EFI_SOURCE"]=="CURRENT_SLOT_ABL" || v["EFI_SOURCE"]=="OTHER_SLOT_ABL") {
                if(v["SOURCE_SLOT"] !~ /^[ab]$/ || v["SOURCE_ABL_DEVICE"] !~ /^\/dev\/block\/[A-Za-z0-9_.\/-]+$/ ||
                   v["SOURCE_ABL_DEVICE"] ~ /(^|\/)\.\.?($|\/)/ || v["SOURCE_ABL_MM"] !~ /^[0-9]+:[0-9]+$/ ||
                   v["SOURCE_ABL_CAPACITY"] !~ /^[1-9][0-9]*$/ || !seen["SOURCE_ABL_SHA256"]) bad=1
            }
            exit bad ? 1 : 0
        }
    ' "$SOURCE_BINDING_FILE" || die 'payload source binding has invalid, duplicate, or unknown fields'
    SOURCE_BINDING_HASH=$(hash_file "$SOURCE_BINDING_FILE")
    is_hash "$SOURCE_BINDING_HASH" || die 'payload source binding hash failed'
    SOURCE_REQUEST_MODE=$(sed -n 's/^REQUEST_MODE=//p' "$SOURCE_BINDING_FILE")
    SOURCE_EFI_KIND=$(sed -n 's/^EFI_SOURCE=//p' "$SOURCE_BINDING_FILE")
    SOURCE_PATCH_MODE=$(sed -n 's/^PATCH_MODE=//p' "$SOURCE_BINDING_FILE")
    _source_abl_target=$(sed -n 's/^ABL_WRITE_TARGET=//p' "$SOURCE_BINDING_FILE")
    [ -z "$_source_abl_target" ] || ABL_WRITE_TARGET=$_source_abl_target
    SOURCE_SLOT=$(sed -n 's/^SOURCE_SLOT=//p' "$SOURCE_BINDING_FILE")
    SOURCE_ABL_DEVICE=$(sed -n 's/^SOURCE_ABL_DEVICE=//p' "$SOURCE_BINDING_FILE")
    SOURCE_ABL_MM=$(sed -n 's/^SOURCE_ABL_MM=//p' "$SOURCE_BINDING_FILE")
    SOURCE_ABL_CAPACITY=$(sed -n 's/^SOURCE_ABL_CAPACITY=//p' "$SOURCE_BINDING_FILE")
    SOURCE_ABL_SHA256=$(sed -n 's/^SOURCE_ABL_SHA256=//p' "$SOURCE_BINDING_FILE")
    verify_payload_source_file
}
verify_payload_source_file() {
    if [ "$SOURCE_BINDING_HASH" = not-present ]; then
        [ ! -e "$SOURCE_BINDING_FILE" ] && [ ! -L "$SOURCE_BINDING_FILE" ] || die 'legacy payload source binding appeared after staging'
    else
        [ -f "$SOURCE_BINDING_FILE" ] && [ ! -L "$SOURCE_BINDING_FILE" ] && \
            [ "$(hash_file "$SOURCE_BINDING_FILE")" = "$SOURCE_BINDING_HASH" ] || die 'payload source binding changed after staging'
    fi
}
verify_payload_source_identity() {
    verify_payload_source_file
    [ "$SOURCE_EFI_KIND" = CURRENT_SLOT_ABL ] || return 0
    [ "$SOURCE_SLOT" = "$SLOT" ] && [ "$SOURCE_ABL_DEVICE" = "$ABL" ] && \
        [ "$SOURCE_ABL_MM" = "$ABL_MM" ] && [ "$SOURCE_ABL_CAPACITY" = "$1" ] || die 'generated efisp source does not match the running-slot ABL identity'
}
verify_payload_source_abl() {
    verify_payload_source_file
    [ "$SOURCE_EFI_KIND" != CURRENT_SLOT_ABL ] || [ "$SOURCE_ABL_SHA256" = "$1" ] || die 'current-slot ABL changed since efisp generation'
}
# END payload source binding

# These proofs are mandatory on production block targets. There is no buffered
# fallback; binary reads keep their direct-I/O evidence on a separate log FD.
export_gpt() {
    "$GPTTOOL" export "$1" > "$2" 2> "$3" || return 1
    grep -qx 'DISK_READ_MODE=direct' "$3"
}
inspect_gpt() {
    inspect_text=$("$GPTTOOL" inspect "$1" "$2" 2>&1) || return 1
    [ "$(kv "$inspect_text" GPT_VALID)" = 1 ] && [ "$(kv "$inspect_text" RESULT)" = success ] && \
        [ "$(kv "$inspect_text" DISK_READ_MODE)" = direct ] || return 1
    printf '%s\n' "$inspect_text"
}
gpt_header() {
    printf '%s\n' "$1" | grep -E '^(DISK_GUID|GPT_CAPACITY|GPT_ENTRY_SIZE|GPT_HEADER_SIZE|FIRST_USABLE|LAST_USABLE|PRIMARY_ENTRIES_LBA|BACKUP_ENTRIES_LBA|LOGICAL_SECTOR|DISK_BYTES)='
}
userdata_info() { printf '%s\n' "$1" | grep -E '^UD_'; }
read_direct() {
    "$WORKER" read-range "$1" "$2" "$3" > "$4" 2> "$5" || return 1
    grep -qx 'READ_MODE=direct' "$5" && [ "$(wc -c < "$4" | tr -d ' \r\n')" = "$3" ]
}
write_direct() {
    local write_access
    write_access=$(write_access_path "$1") || return 1
    bf_run_write_range "$WORKER" "$1" "$2" "$3" "$4" "$5" "$write_access"
}

clean_value() { printf '%s' "$1" | tr '\r\n' '  '; }

if command -v id >/dev/null 2>&1 && [ "$(id -u 2>/dev/null)" = 0 ]; then ROOT=1; fi
if command -v getprop >/dev/null 2>&1; then
    DEVICE=$(clean_value "$(getprop ro.product.device 2>/dev/null)")
    [ -n "$DEVICE" ] || DEVICE=unknown
    MANUFACTURER=$(clean_value "$(getprop ro.product.manufacturer 2>/dev/null)")
    SOC=$(clean_value "$(getprop ro.soc.model 2>/dev/null)")
    [ -n "$MANUFACTURER" ] || MANUFACTURER=unknown
    [ -n "$SOC" ] || SOC=unknown
fi
# Gather basic fields before checking recovery-only utility dependencies.
if command -v readlink >/dev/null 2>&1; then
    [ ! -e /dev/block/by-name/userdata ] || USERDATA=$(readlink -f /dev/block/by-name/userdata 2>/dev/null)
    [ ! -e /dev/block/by-name/efisp ] || EFISP=$(readlink -f /dev/block/by-name/efisp 2>/dev/null)
fi
if command -v blkid >/dev/null 2>&1 && [ "$USERDATA" != unknown ]; then
    FILESYSTEM=$(blkid -p -s TYPE -o value "$USERDATA" 2>/dev/null || :)
    if [ -z "$FILESYSTEM" ]; then
        FILESYSTEM=$(blkid "$USERDATA" 2>/dev/null | sed -n 's/.* TYPE="\([^"]*\)".*/\1/p')
    fi
    [ -n "$FILESYSTEM" ] || FILESYSTEM=unknown
fi
RAW_FILESYSTEM=$FILESYSTEM
# The mounted filesystem can be identified even when raw userdata is encrypted.
# It is a reporting field only: shrink eligibility uses RAW_FILESYSTEM below.
if command -v awk >/dev/null 2>&1 && command -v readlink >/dev/null 2>&1; then
    _data_mount=$(awk '$5=="/data" {for(i=6;i<=NF;i++) if($i=="-") {source=$(i+2); fs=$(i+1)}} END {if(fs!="") print source,fs}' /proc/1/mountinfo 2>/dev/null || :)
    if [ -n "$_data_mount" ]; then
        DATA_SOURCE=${_data_mount% *}; DATA_FS=${_data_mount##* }
        [ "$FILESYSTEM" != unknown ] || FILESYSTEM=$DATA_FS
        _data_node=$(readlink -f "$DATA_SOURCE" 2>/dev/null || :)
        _data_name=${_data_node##*/}
        chain_for_block() {
            _chain_name=$1 _chain_depth=$2
            printf '%s' "$_chain_name"
            [ "$_chain_depth" -lt 16 ] || return 0
            # A subshell per child preserves the parent's traversal variables.
            for _slave in /sys/class/block/"$_chain_name"/slaves/*; do
                [ -e "$_slave" ] || continue
                printf '>'
                (chain_for_block "${_slave##*/}" "$((_chain_depth + 1))")
            done
        }
        if [ -r "/sys/class/block/$_data_name/dev" ]; then DATA_CHAIN=$(chain_for_block "$_data_name" 0); fi
    fi
fi

SLOT_CANDIDATE=
accept_slot() {
    _slot=$1
    [ -n "$_slot" ] || return 0
    case "$_slot" in _a) _slot=a ;; _b) _slot=b ;; *) die "invalid current slot suffix from $2" ;; esac
    [ -z "$SLOT_CANDIDATE" ] || [ "$SLOT_CANDIDATE" = "$_slot" ] || die 'current slot sources disagree'
    SLOT_CANDIDATE=$_slot
    SLOT=$_slot
}
if command -v getprop >/dev/null 2>&1; then
    accept_slot "$(getprop ro.boot.slot_suffix 2>/dev/null)" ro.boot.slot_suffix
fi
if command -v awk >/dev/null 2>&1; then
    if [ -r /proc/cmdline ]; then
        for _s in $(awk '{for(i=1;i<=NF;i++) if($i ~ /^androidboot\.slot_suffix=/) {sub(/^androidboot\.slot_suffix=/,"",$i); print $i}}' /proc/cmdline); do
            accept_slot "$_s" cmdline
        done
    fi
    if [ -r /proc/bootconfig ]; then
        for _s in $(awk -F= '$1 ~ /^[ \t]*androidboot\.slot_suffix[ \t]*$/ {gsub(/[ \t";]/,"",$2);print $2}' /proc/bootconfig); do
            accept_slot "$_s" bootconfig
        done
    fi
fi
if command -v bootctl >/dev/null 2>&1; then
    _boot_slot=$(bootctl get-current-slot 2>/dev/null || :)
    case "$_boot_slot" in
        0|1)
            _boot_suffix=$(bootctl get-suffix "$_boot_slot" 2>/dev/null || :)
            [ -n "$_boot_suffix" ] || die 'bootctl current-slot suffix unavailable'
            accept_slot "$_boot_suffix" bootctl
            case "$_boot_slot:$SLOT" in 0:a|1:b) ;; *) die 'bootctl index and suffix disagree' ;; esac ;;
        '') ;; # Recovery may have no boot-control HAL. Kernel sources remain required.
        *) die 'bootctl returned an invalid current slot' ;;
    esac
fi
if [ "$SLOT" != unknown ] && command -v readlink >/dev/null 2>&1; then
    [ ! -e "/dev/block/by-name/abl_$SLOT" ] || ABL=$(readlink -f "/dev/block/by-name/abl_$SLOT" 2>/dev/null)
fi
# Some vendor Android /system/bin/sh builds use 32-bit signed arithmetic even
# on arm64 hardware. Both operands fit 32 bits; compare the product as TEXT.
# No device capacity/LBA calculation, backup, or write precedes this.
# Also verify the shell's integer comparison builtin accepts the same range.
if [ "$((1875091200 * 512))" = 960046694400 ] && \
    [ 960046694400 -gt 2147483647 ] 2>/dev/null; then ARITHMETIC64=1; fi
[ "$ROOT" = 1 ] || die 'root is required'
[ "$ARITHMETIC64" = 1 ] || die '32bit shell arithmetic unsupported, require validated 64bit recovery shell'
[ "$SLOT" != unknown ] || die 'running slot is missing from boot properties and kernel sources'
for _tool in awk sed grep tr readlink cat wc dd od sha256sum head blockdev sgdisk blkid df date mkdir sync chmod cp; do
    bf_tool_available "$_tool" || die "missing required utility: $_tool"
done

SCRIPT_PATH=$(readlink -f "$0") || die 'script path resolution failed'
SCRIPT_DIR=${SCRIPT_PATH%/*}
GPTTOOL=$SCRIPT_DIR/gpttool WORKER=$SCRIPT_DIR/partition_worker
[ -x "$WORKER" ] || die 'direct block worker is missing'
[ -f "$GPTTOOL" ] && [ -x "$GPTTOOL" ] || die 'raw GPT helper is missing or not executable'
GPT_SELF_TEST=$("$GPTTOOL" --self-test 2>&1) || die 'raw GPT helper self-test failed'
printf '%s\n' "$GPT_SELF_TEST" | grep -qx 'SELF_TEST_RESULT=PASS' && \
    printf '%s\n' "$GPT_SELF_TEST" | grep -qx 'RESULT=success' || die 'raw GPT helper self-test proof is missing'
bf_selected_aliases "$SCRIPT_DIR/selected-tools" >/dev/null || die 'selected tool aliases differ from the staged bundled tool paths'
PE_IMAGE=$SCRIPT_DIR/efisp.img
ABL_IMAGE=$SCRIPT_DIR/abl.img
PAYLOAD_MANIFEST=$SCRIPT_DIR/payloads.sha256
# Completion reporting only; these helpers never issue reboot or format commands.
completion_requires_recovery() {
    if [ "${SOURCE_PATCH_MODE:-}" = FAKE_LOCK ]; then printf '%s\n' 0; else printf '%s\n' 1; fi
}
completion_reboot_target() {
    if [ "${SOURCE_PATCH_MODE:-}" = FAKE_LOCK ]; then printf '%s\n' bootloader; else printf '%s\n' recovery; fi
}
completion_next_step() {
    if [ "${SOURCE_PATCH_MODE:-}" = FAKE_LOCK ]; then
        printf '%s\n' 'flashing complete; optional reboot bootloader; no reboot has been issued'
    else
        printf '%s\n' 'reboot to Recovery and perform factory reset; no reboot has been issued'
    fi
}
completion_operation() {
    if [ "${SOURCE_PATCH_MODE:-}" = FAKE_LOCK ]; then
        printf '%s\n' 'online F2FS shrink and GPT entry rebuild; optional Fastboot entry after completion'
    else
        printf '%s\n' 'online F2FS shrink and GPT entry rebuild; Recovery factory reset remains pending'
    fi
}

load_payload_source "$SCRIPT_DIR/payload-source.txt"
BACKUP_BASE=${BACKUP_BASE_OVERRIDE:-$SCRIPT_DIR/backups}
case "$SCRIPT_DIR$BACKUP_BASE" in *[!\ -~]*|*' '*|*'\'*) die 'script and backup paths must have plain ASCII without spaces or backslashes' ;; esac
case "$BACKUP_BASE" in /*) ;; *) die 'backup directory must be absolute' ;; esac
case "$BACKUP_BASE/" in */../*|*/./*) die 'backup directory must not contain dot path components' ;; esac

# Identity is always obtained from by-name symlinks and sysfs, never disk-name guesses.
inspect_block() {
    _path=$(readlink -f "$1") || return 1
    [ -b "$_path" ] || return 1
    _name=${_path##*/}
    [ -r "/sys/class/block/$_name/partition" ] || return 1
    _real=$(readlink -f "/sys/class/block/$_name") || return 1
    _parent=${_real%/*}; _parent=${_parent##*/}
    [ -r "/sys/class/block/$_parent/dev" ] || return 1
    case "$_name:$_parent" in dm-*:*|*:dm-*) return 1 ;; esac
    [ ! -d "/sys/class/block/$_name/dm" ] || return 1
    BLOCK_PATH=$_path BLOCK_NAME=$_name BLOCK_PARENT=$_parent
    BLOCK_INDEX=$(cat "/sys/class/block/$_name/partition")
    BLOCK_START512=$(cat "/sys/class/block/$_name/start")
    BLOCK_SIZE512=$(cat "/sys/class/block/$_name/size")
    BLOCK_MM=$(cat "/sys/class/block/$_name/dev")
    is_uint "$BLOCK_INDEX" && is_uint "$BLOCK_START512" && is_uint "$BLOCK_SIZE512"
}
inspect_block "$USERDATA" || die 'userdata is not a raw physical GPT partition'
USERDATA=$BLOCK_PATH UD_NAME=$BLOCK_NAME UD_PARENT=$BLOCK_PARENT UD_INDEX=$BLOCK_INDEX
UD_START512=$BLOCK_START512 UD_SIZE512=$BLOCK_SIZE512 UD_MM=$BLOCK_MM
DISK=/dev/block/$UD_PARENT
[ -b "$DISK" ] || die 'userdata physical parent block node is missing'
inspect_block "$ABL" || die 'current-slot abl is not a raw physical partition'
ABL=$BLOCK_PATH ABL_NAME=$BLOCK_NAME ABL_MM=$BLOCK_MM ABL_PARENT=$BLOCK_PARENT
ABL_INDEX=$BLOCK_INDEX ABL_START512=$BLOCK_START512 ABL_SIZE512=$BLOCK_SIZE512
ABL_PARTITION_BYTES=$((BLOCK_SIZE512 * 512))
verify_payload_source_identity "$ABL_PARTITION_BYTES"
[ "$ABL" != "$USERDATA" ] || die 'abl resolves to userdata'
_other=a; [ "$SLOT" != a ] || _other=b
if [ -e "/dev/block/by-name/abl_$_other" ]; then
    [ "$(readlink -f "/dev/block/by-name/abl_$_other")" != "$ABL" ] || die 'both abl slots resolve to the same block device'
fi
# A stale or ambiguous by-name link must never select an unrelated partition.
unique_partname() {
    _want=$1 _expected=$2 _count=0 _found=
    for _uevent in /sys/class/block/*/uevent; do
        [ -r "$_uevent" ] || continue
        if grep -qx "PARTNAME=$_want" "$_uevent"; then
            _count=$((_count + 1)); _found=${_uevent%/uevent}; _found=${_found##*/}
        fi
    done
    [ "$_count" = 1 ] && [ "$_found" = "$_expected" ]
}
unique_partname userdata "$UD_NAME" || die 'userdata name is missing or duplicated in sysfs'
unique_partname "abl_$SLOT" "$ABL_NAME" || die 'current abl name is missing or duplicated in sysfs'

LOGICAL_SECTOR=$(blockdev --getss "$DISK" 2>/dev/null) || die 'logical sector size query failed'
case "$LOGICAL_SECTOR" in 512|4096) ;; *) die 'unsupported logical sector size' ;; esac
[ $((EFI_BYTES % LOGICAL_SECTOR)) = 0 ] || die '64 MiB is not sector aligned'
EFI_SECTORS=$((EFI_BYTES / LOGICAL_SECTOR))
[ $((UD_START512 * 512 % LOGICAL_SECTOR)) = 0 ] || die 'userdata start disagrees with logical sector size'
[ $((UD_SIZE512 * 512 % LOGICAL_SECTOR)) = 0 ] || die 'userdata size disagrees with logical sector size'
UD_START=$((UD_START512 * 512 / LOGICAL_SECTOR))
UD_SECTORS=$((UD_SIZE512 * 512 / LOGICAL_SECTOR))
UD_END=$((UD_START + UD_SECTORS - 1))
UD_KERNEL_END=$UD_END

verify_gpt() {
    _verify=$("$GPTTOOL" verify "${1:-$DISK}" 2>&1) || return 1
    printf '%s\n' "$_verify" | awk '
        /^GPT_VALID=/ {valid++; if($0!="GPT_VALID=1") bad=1}
        /^RESULT=/ {result++; if($0!="RESULT=success") bad=1}
        /^DISK_READ_MODE=/ {direct++; if($0!="DISK_READ_MODE=direct") bad=1}
        END {exit (bad || valid!=1 || result!=1 || direct!=1)}'
}
# BEGIN selected ABL targets
# ABL/SLOT retain the current-slot source identity. Write destinations are separate.
other_target_selected() { [ "$ABL_WRITE_TARGET" != CURRENT ]; }
other_abl_needed() { other_target_selected || [ "$SOURCE_EFI_KIND" = OTHER_SLOT_ABL ]; }
current_target_selected() { [ "$ABL_WRITE_TARGET" != OTHER ]; }
abl_target_partitions() {
    case "$ABL_WRITE_TARGET" in
        CURRENT) printf '%s\n' "abl_$SLOT" ;;
        OTHER) printf '%s\n' "abl_$ABL_OTHER_SLOT" ;;
        BOTH) printf '%s\n' "abl_$ABL_OTHER_SLOT abl_$SLOT" ;;
        *) die 'unknown selected ABL write target' ;;
    esac
}

abl_target_slots() {
    case "$ABL_WRITE_TARGET" in
        CURRENT) printf '%s\n' "$SLOT" ;;
        OTHER) printf '%s\n' "$ABL_OTHER_SLOT" ;;
        BOTH) printf '%s\n' "$ABL_OTHER_SLOT $SLOT" ;;
        *) die 'unknown selected ABL write target' ;;
    esac
}
initialize_other_abl() {
    OTHER_ABL=none OTHER_ABL_NAME=none OTHER_ABL_PARENT=none OTHER_ABL_INDEX=0
    OTHER_ABL_START512=0 OTHER_ABL_SIZE512=0 OTHER_ABL_MM=none OTHER_ABL_CAPACITY=0
    OTHER_ABL_BEFORE_HASH=not-applicable OTHER_ABL_GPT_BEFORE_HASH=not-applicable
    ABL_OTHER_SLOT=a; [ "$SLOT" != a ] || ABL_OTHER_SLOT=b
    ABL_TARGET_SLOTS=$(abl_target_slots)
    ABL_TARGET_PARTITIONS=$(abl_target_partitions)
    other_abl_needed || return 0
    OTHER_ABL=$(resolve_abl_link "$ABL_OTHER_SLOT") || die 'opposite-slot ABL link is missing'
    inspect_abl_target "$OTHER_ABL" || die 'opposite-slot ABL is not a raw physical partition'
    OTHER_ABL=$TA_PATH OTHER_ABL_NAME=$TA_NAME OTHER_ABL_PARENT=$TA_PARENT OTHER_ABL_INDEX=$TA_INDEX
    OTHER_ABL_START512=$TA_START512 OTHER_ABL_SIZE512=$TA_SIZE512 OTHER_ABL_MM=$TA_MM
    OTHER_ABL_CAPACITY=$((OTHER_ABL_SIZE512 * 512))
    [ "$OTHER_ABL" != "$ABL" ] && [ "$OTHER_ABL_MM" != "$ABL_MM" ] && \
        [ "$OTHER_ABL" != "$USERDATA" ] && [ "$OTHER_ABL_MM" != "$UD_MM" ] || die 'selected ABL slots overlap each other or userdata'
    unique_abl_target "abl_$ABL_OTHER_SLOT" "$OTHER_ABL_NAME" || die 'opposite-slot ABL name is missing or ambiguous'
    is_uint "$OTHER_ABL_CAPACITY" && [ "$OTHER_ABL_CAPACITY" -gt 0 ] || die 'opposite-slot ABL has invalid capacity'
    if other_target_selected; then
        [ "$ABL_BYTES" -le "$OTHER_ABL_CAPACITY" ] || die 'ABL image exceeds the opposite-slot partition capacity'
    fi
    verify_gpt "/dev/block/$OTHER_ABL_PARENT" || die 'opposite-slot ABL parent GPT is not valid'
    validate_other_abl_identity
    verify_other_payload_source
}
verify_other_payload_source() {
    verify_payload_source_file
    [ "$SOURCE_EFI_KIND" = OTHER_SLOT_ABL ] || return 0
    [ "$SOURCE_SLOT" = "$ABL_OTHER_SLOT" ] && [ "$SOURCE_ABL_DEVICE" = "$OTHER_ABL" ] && \
        [ "$SOURCE_ABL_MM" = "$OTHER_ABL_MM" ] && [ "$SOURCE_ABL_CAPACITY" = "$OTHER_ABL_CAPACITY" ] || die 'generated efisp source does not match the opposite-slot ABL identity'
    [ -z "${1:-}" ] || [ "$SOURCE_ABL_SHA256" = "$1" ] || die 'opposite-slot source ABL changed since efisp generation'
}
validate_other_abl_identity() {
    other_abl_needed || return 0
    [ "$(resolve_abl_link "$ABL_OTHER_SLOT")" = "$OTHER_ABL" ] || die 'opposite-slot ABL link changed'
    inspect_abl_target "$OTHER_ABL" || die 'opposite-slot ABL physical identity disappeared'
    [ "$TA_PATH" = "$OTHER_ABL" ] && [ "$TA_NAME" = "$OTHER_ABL_NAME" ] && \
        [ "$TA_PARENT" = "$OTHER_ABL_PARENT" ] && [ "$TA_INDEX" = "$OTHER_ABL_INDEX" ] && \
        [ "$TA_START512" = "$OTHER_ABL_START512" ] && [ "$TA_SIZE512" = "$OTHER_ABL_SIZE512" ] && \
        [ "$TA_MM" = "$OTHER_ABL_MM" ] || die 'opposite-slot ABL geometry changed'
    unique_abl_target "abl_$ABL_OTHER_SLOT" "$OTHER_ABL_NAME" || die 'opposite-slot ABL sysfs name changed'
    [ "$(blockdev --getsize64 "$OTHER_ABL")" = "$OTHER_ABL_CAPACITY" ] || die 'opposite-slot ABL capacity changed'
    if other_target_selected; then
        abl_target_idle "$OTHER_ABL_NAME" "$OTHER_ABL_MM" || die 'opposite-slot ABL is mounted, swapped, or has holders'
        bf_readonly_state "$OTHER_ABL" >/dev/null || die 'opposite-slot ABL read-only state is unknown'
    fi
}
other_abl_gpt_backup() {
    if [ "$OTHER_ABL_PARENT" = "$UD_PARENT" ]; then printf '%s\n' "$1/gpt-before.bin"
    elif [ "$OTHER_ABL_PARENT" = "$ABL_PARENT" ]; then printf '%s\n' "$1/abl-disk-gpt-before.bin"
    else printf '%s\n' "$1/other-abl-disk-gpt-before.bin"; fi
}
backup_other_abl() {
    other_abl_needed || return 0
    validate_other_abl_identity
    if [ "$OTHER_ABL_PARENT" != "$UD_PARENT" ] && [ "$OTHER_ABL_PARENT" != "$ABL_PARENT" ]; then
        export_gpt "/dev/block/$OTHER_ABL_PARENT" "$1/other-abl-disk-gpt-before.bin" "$1/other-abl-gpt-backup.log" || die 'opposite-slot parent GPT backup failed'
    fi
    _other_gpt=$(other_abl_gpt_backup "$1")
    [ -s "$_other_gpt" ] || die 'opposite-slot parent GPT backup is empty'
    OTHER_ABL_GPT_BEFORE_HASH=$(hash_file "$_other_gpt")
    read_direct "$OTHER_ABL" 0 "$OTHER_ABL_CAPACITY" "$1/abl-other.full.bin" "$1/abl-other-backup.log" || die 'complete opposite-slot ABL backup failed'
    OTHER_ABL_BEFORE_HASH=$(hash_file "$1/abl-other.full.bin")
    is_hash "$OTHER_ABL_BEFORE_HASH" && is_hash "$OTHER_ABL_GPT_BEFORE_HASH" || die 'opposite-slot backup hashes are invalid'
    read_direct "$OTHER_ABL" 0 "$OTHER_ABL_CAPACITY" "$1/abl-other-backup-readback.bin" "$1/abl-other-backup-readback.log" && \
        [ "$(hash_file "$1/abl-other-backup-readback.bin")" = "$OTHER_ABL_BEFORE_HASH" ] || die 'opposite-slot ABL backup readback mismatch'
    verify_other_payload_source "$OTHER_ABL_BEFORE_HASH"
}
verify_other_abl_unchanged() {
    other_abl_needed || return 0
    validate_other_abl_identity
    is_hash "$OTHER_ABL_BEFORE_HASH" && is_hash "$OTHER_ABL_GPT_BEFORE_HASH" || die 'opposite-slot backup proof is missing'
    verify_other_payload_source "$OTHER_ABL_BEFORE_HASH"
    [ "$(hash_file "$1/abl-other.full.bin")" = "$OTHER_ABL_BEFORE_HASH" ] || die 'opposite-slot complete backup changed'
    read_direct "$OTHER_ABL" 0 "$OTHER_ABL_CAPACITY" "$1/abl-other-current-check.bin" "$1/abl-other-current-check.log" && \
        [ "$(hash_file "$1/abl-other-current-check.bin")" = "$OTHER_ABL_BEFORE_HASH" ] || die 'opposite-slot ABL changed since backup'
    _other_expected_gpt=$OTHER_ABL_GPT_BEFORE_HASH
    if [ "$OTHER_ABL_PARENT" = "$UD_PARENT" ] && [ "$GPT_VERIFIED" = 1 ]; then
        [ -s "$1/gpt-after.bin" ] && [ -s "$1/gpt-planned.bin" ] && \
            [ "$(hash_file "$1/gpt-after.bin")" = "$(hash_file "$1/gpt-planned.bin")" ] || die 'selected ABL parent GPT does not match the verified partition plan'
        _other_expected_gpt=$(hash_file "$1/gpt-after.bin")
    fi
    export_gpt "/dev/block/$OTHER_ABL_PARENT" "$1/other-abl-gpt-current.bin" "$1/other-abl-gpt-current.log" && \
        [ "$(hash_file "$1/other-abl-gpt-current.bin")" = "$_other_expected_gpt" ] || die 'opposite-slot ABL parent GPT changed outside the verified plan'
}
other_abl_plan_text() {
    printf '%s\n' "ABL_WRITE_TARGET=$ABL_WRITE_TARGET" "ABL_TARGET_SLOTS=$(abl_target_slots)" \
        "OTHER_ABL=$OTHER_ABL" "OTHER_ABL_NAME=$OTHER_ABL_NAME" "OTHER_ABL_PARENT=$OTHER_ABL_PARENT" \
        "OTHER_ABL_INDEX=$OTHER_ABL_INDEX" "OTHER_ABL_START512=$OTHER_ABL_START512" \
        "OTHER_ABL_SIZE512=$OTHER_ABL_SIZE512" "OTHER_ABL_MM=$OTHER_ABL_MM" "OTHER_ABL_CAPACITY=$OTHER_ABL_CAPACITY"
}
other_abl_inventory() {
    other_abl_needed || return 0
    sha256sum abl-other.full.bin || return 1
    if [ "$OTHER_ABL_PARENT" != "$UD_PARENT" ] && [ "$OTHER_ABL_PARENT" != "$ABL_PARENT" ]; then sha256sum other-abl-disk-gpt-before.bin || return 1; fi
}
write_other_abl() {
    other_target_selected || return 0
    verify_payload_source_file
    verify_other_abl_unchanged "$1"
    [ "$(hash_file "$ABL_IMAGE")" = "$ABL_HASH" ] || die 'ABL payload changed before opposite-slot write'
    abl_targets_event "writing selected abl.img to opposite-slot abl_$ABL_OTHER_SLOT: $OTHER_ABL"
    abl_targets_mark_modified
    write_direct "$OTHER_ABL" 0 "$OTHER_ABL_CAPACITY" "$ABL_IMAGE" "$1/abl-other-write.log" || die 'opposite-slot ABL write failed; complete backup retained'
    sync || die 'opposite-slot ABL sync failed'
    read_direct "$OTHER_ABL" 0 "$ABL_BYTES" "$1/abl-other-readback.bin" "$1/abl-other-readback.log" && \
        [ "$(hash_file "$1/abl-other-readback.bin")" = "$ABL_HASH" ] || die 'opposite-slot ABL SHA256 readback mismatch'
    abl_targets_event "EVIDENCE=selected-abl-sha256:$ABL_OTHER_SLOT:$ABL_HASH"
}
write_selected_abl_targets() {
    # Finish the inactive slot first, retaining the running-slot original until then.
    if other_abl_needed && ! other_target_selected; then verify_other_abl_unchanged "$1"; fi
    if other_target_selected; then write_other_abl "$1"; fi
    if current_target_selected; then write_current_abl "$1"; fi
    ABL_TARGETS_VERIFIED=1
    abl_targets_event "EVIDENCE=selected-abl-targets-verified:$ABL_WRITE_TARGET:$(abl_target_partitions)"
    if ! current_target_selected; then abl_targets_event "current-slot abl_$SLOT preserved; it was not selected for writing"; fi
    printf '%s\n' 'ABL_TARGETS_VERIFIED=1' "ABL_WRITE_TARGET=$ABL_WRITE_TARGET" "ABL_TARGET_PARTITIONS=$(abl_target_partitions)"
}
# END selected ABL targets

# BEGIN block write access
write_access_path() {
    if [ "$1" = "$ABL" ] && current_target_selected; then printf '/dev/block/by-name/abl_%s\n' "$SLOT"
    elif other_target_selected && [ "$1" = "$OTHER_ABL" ]; then printf '/dev/block/by-name/abl_%s\n' "$ABL_OTHER_SLOT"
    elif [ "$1" = "$USERDATA" ]; then printf '%s\n' /dev/block/by-name/userdata
    elif [ "$1" = "$EFISP" ] && [ "$EFISP" != absent ] && [ "$EFISP" != present ]; then printf '%s\n' /dev/block/by-name/efisp
    else printf '%s\n' "$1"; fi
}
validate_current_abl_access() {
    current_target_selected || return 0
    [ "$(resolve_abl_link "$SLOT")" = "$ABL" ] || die 'current-slot ABL link changed before writable preparation'
    inspect_abl_target "$ABL" || die 'current-slot ABL identity disappeared before writable preparation'
    [ "$TA_PATH" = "$ABL" ] && [ "$TA_NAME" = "$ABL_NAME" ] && [ "$TA_PARENT" = "$ABL_PARENT" ] && \
        [ "$TA_INDEX" = "$ABL_INDEX" ] && [ "$TA_START512" = "$ABL_START512" ] && \
        [ "$TA_SIZE512" = "$ABL_SIZE512" ] && [ "$TA_MM" = "$ABL_MM" ] || die 'current-slot ABL identity changed before writable preparation'
    abl_target_idle "$ABL_NAME" "$ABL_MM" || die 'current-slot ABL became busy before writable preparation'
}
ensure_selected_abl_writable() {
    bf_write_phase_allowed || die 'write access requested outside an authorized execution phase'
    verify_running_slot_for_write
    verify_payload_source_file
    validate_current_abl_access
    if other_target_selected; then validate_other_abl_identity; fi
    if current_target_selected; then
        bf_ensure_bound_writable "$ABL" "/dev/block/by-name/abl_$SLOT" || die 'current-slot ABL could not be made writable'
    fi
    if other_target_selected; then
        bf_ensure_bound_writable "$OTHER_ABL" "/dev/block/by-name/abl_$ABL_OTHER_SLOT" || die 'opposite-slot ABL could not be made writable'
    fi
    verify_running_slot_for_write
    validate_current_abl_access
    if other_target_selected; then validate_other_abl_identity; fi
}
# END block write access

verify_running_slot_for_write() {
    if command -v getprop >/dev/null 2>&1; then accept_slot "$(getprop ro.boot.slot_suffix 2>/dev/null)" ro.boot.slot_suffix; fi
    if command -v bootctl >/dev/null 2>&1; then
        _write_now=$(bootctl get-current-slot 2>/dev/null || :)
        case "$_write_now" in 0|1) accept_slot "$(bootctl get-suffix "$_write_now" 2>/dev/null || :)" bootctl ;; '') ;; *) die 'current-slot HAL changed before writable preparation' ;; esac
    fi
}
resolve_abl_link() { readlink -f "/dev/block/by-name/abl_$1"; }
inspect_abl_target() {
    inspect_block "$1" || return 1
    TA_PATH=$BLOCK_PATH TA_NAME=$BLOCK_NAME TA_PARENT=$BLOCK_PARENT TA_INDEX=$BLOCK_INDEX
    TA_START512=$BLOCK_START512 TA_SIZE512=$BLOCK_SIZE512 TA_MM=$BLOCK_MM
}
unique_abl_target() { unique_partname "$1" "$2"; }
abl_target_idle() { block_idle "$1"; }
abl_targets_event() { log "$1"; }
abl_targets_mark_modified() { ABL_MAY_BE_MODIFIED=1; }

verify_gpt || die 'GPT verification did not report clean primary and backup tables'
if [ "$ABL_PARENT" != "$UD_PARENT" ]; then
    verify_gpt "/dev/block/$ABL_PARENT" || die 'current-slot abl parent GPT is not clean'
fi
GPT_INSPECT=$(inspect_gpt "$DISK" "$UD_INDEX") || die 'direct GPT inspection failed'
[ "$(kv "$GPT_INSPECT" LOGICAL_SECTOR)" = "$LOGICAL_SECTOR" ] && \
    [ "$(kv "$GPT_INSPECT" UD_START)" = "$UD_START" ] || die 'direct GPT differs from physical userdata origin'
UD_END=$(kv "$GPT_INSPECT" UD_END); UD_GPT_BYTES=$(kv "$GPT_INSPECT" UD_BYTES)
is_uint "$UD_END" && is_uint "$UD_GPT_BYTES" && [ "$UD_END" -le "$UD_KERNEL_END" ] && \
    [ "$UD_GPT_BYTES" = "$(((UD_END - UD_START + 1) * LOGICAL_SECTOR))" ] || die 'direct GPT userdata does not fit its kernel node'
GPT_TABLE=$GPT_INSPECT
DISK_GUID=$(kv "$GPT_INSPECT" DISK_GUID); GPT_CAPACITY=$(kv "$GPT_INSPECT" GPT_CAPACITY)
GPT_HEADER_GEOMETRY=$(gpt_header "$GPT_INSPECT")
PARTITION_NUMBERS=$(kv "$GPT_INSPECT" PARTITION_INDICES | tr ' ' '\n')
UD_INFO=$(userdata_info "$GPT_INSPECT")
info_field() { printf '%s\n' "$1" | sed -n "s/^$2: //p"; }
UD_TYPE=$(kv "$GPT_INSPECT" UD_TYPE); UD_GUID=$(kv "$GPT_INSPECT" UD_GUID)
UD_ATTR=$(kv "$GPT_INSPECT" UD_ATTR); UD_ATTR_BACKUP=$(kv "$GPT_INSPECT" UD_ATTR_BACKUP)
UD_LABEL=$(kv "$GPT_INSPECT" UD_LABEL)
is_guid "$DISK_GUID" && is_uint "$GPT_CAPACITY" && is_guid "$UD_TYPE" && is_guid "$UD_GUID" && \
    [ "$UD_LABEL" = userdata ] || die 'direct GPT metadata is malformed'
OTHER_ENTRIES='ALL_OTHER_RAW_ENTRIES_BOUND_BY_EXACT_SNAPSHOT'
case "$(kv "$GPT_INSPECT" EFI_COUNT)" in
    0) EFI_GPT_INDEX= EFI_GPT_INFO= ;;
    1) EFI_GPT_INDEX=$(kv "$GPT_INSPECT" EFI_INDEX); EFI_GPT_INFO=$GPT_INSPECT ;;
    *) die 'multiple efisp GPT entries exist' ;;
esac

if [ -n "$EFI_GPT_INDEX" ]; then
    PLAN_MODE=reuse
    [ "$EFISP" != absent ] || die 'efisp GPT entry exists but block link is missing; reboot recovery'
    inspect_block "$EFISP" || die 'efisp is not a raw GPT partition'
    EFISP=$BLOCK_PATH EFI_NAME=$BLOCK_NAME EFI_MM=$BLOCK_MM
    [ "$BLOCK_PARENT" = "$UD_PARENT" ] && [ "$BLOCK_INDEX" = "$EFI_GPT_INDEX" ] || die 'efisp belongs to the wrong disk or entry'
    unique_partname efisp "$EFI_NAME" || die 'efisp name is missing or duplicated in sysfs'
    EFI_BYTES=$((BLOCK_SIZE512 * 512))
    [ "$EFI_BYTES" -gt 0 ] && [ $((EFI_BYTES % LOGICAL_SECTOR)) = 0 ] || die 'existing efisp capacity is invalid'
    EFI_SECTORS=$((EFI_BYTES / LOGICAL_SECTOR))
    SKIP_F2FS_SHRINK=1
    EFI_START=$(kv "$EFI_GPT_INFO" EFI_START)
    EFI_END=$(kv "$EFI_GPT_INFO" EFI_END)
    EFI_EXISTING_TYPE=$(kv "$EFI_GPT_INFO" EFI_TYPE)
    [ "$EFI_EXISTING_TYPE" = "$EFI_TYPE" ] || die 'existing efisp GPT type is not EFI System'
    EFI_GUID=$(kv "$EFI_GPT_INFO" EFI_GUID)
    EFI_TARGET_ATTR=$(kv "$EFI_GPT_INFO" EFI_ATTR)
    EFI_TARGET_ATTR_BACKUP=$(kv "$EFI_GPT_INFO" EFI_ATTR_BACKUP)
    EFI_METADATA_PROFILE=existing-preserved
    is_uint "$EFI_START" && is_uint "$EFI_END" || die 'existing efisp GPT geometry is invalid'
    [ "$EFI_END" = "$((EFI_START + EFI_SECTORS - 1))" ] || die 'existing efisp GPT capacity differs from its physical node'
    # A pre-existing efisp may occupy an earlier free extent on this disk.
    # Clean GPT verification above establishes that it overlaps no partition.
    [ $((BLOCK_START512 * 512)) = "$((EFI_START * LOGICAL_SECTOR))" ] || die 'efisp kernel geometry is stale; reboot recovery'
    NEW_UD_END=$UD_END NEW_UD_BYTES=$UD_GPT_BYTES
else
    PLAN_MODE=create
    [ "$UD_END" = "$UD_KERNEL_END" ] || die 'userdata kernel range differs from GPT without efisp'
    [ "$EFISP" = absent ] || die 'efisp block link exists without matching GPT entry'
    for _uevent in /sys/class/block/*/uevent; do
        [ ! -r "$_uevent" ] || ! grep -qx PARTNAME=efisp "$_uevent" || die 'an efisp partition already exists on another disk'
    done
    [ "$UD_SECTORS" -gt "$EFI_SECTORS" ] || die 'userdata is smaller than the requested shrink'
    NEW_UD_END=$((UD_END - EFI_SECTORS))
    NEW_UD_BYTES=$(((NEW_UD_END - UD_START + 1) * LOGICAL_SECTOR))
    EFI_START=$((NEW_UD_END + 1)) EFI_END=$UD_END
    EFI_GPT_INDEX=1
    while [ "$EFI_GPT_INDEX" -le "$GPT_CAPACITY" ]; do
        printf '%s\n' "$PARTITION_NUMBERS" | grep -qx "$EFI_GPT_INDEX" || break
        EFI_GPT_INDEX=$((EFI_GPT_INDEX + 1))
    done
    [ "$EFI_GPT_INDEX" -le "$GPT_CAPACITY" ] || die 'GPT has no unused partition entry'
    EFI_GUID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null | tr 'a-f' 'A-F')
    is_guid "$EFI_GUID" || die 'new efisp GUID could not be precomputed'
    EFI_TARGET_ATTR=$EFI_ATTR EFI_TARGET_ATTR_BACKUP=$EFI_ATTR EFI_METADATA_PROFILE=$EFI_PROFILE
fi
[ "$ABL" != "$EFISP" ] || die 'abl and efisp resolve to the same block device'

# Check mounts in init, this process, and every visible process namespace.
# Holders catches dm-default-key, dm-crypt, and other stacked block users.
[ -r /proc/1/mountinfo ] && [ -r /proc/self/mountinfo ] || die 'mount namespace inspection is unavailable'
block_idle() {
    _idle_name=$1
    _idle_mm=$(cat "/sys/class/block/$_idle_name/dev") || return 1
    for _holder in /sys/class/block/"$_idle_name"/holders/*; do [ ! -e "$_holder" ] || return 1; done
    if awk -v mm="$_idle_mm" '$3==mm {found=1} END {exit !found}' /proc/1/mountinfo /proc/self/mountinfo; then return 1; fi
    for _mi in /proc/[0-9]*/mountinfo; do
        [ -r "$_mi" ] || continue
        if awk -v mm="$_idle_mm" '$3==mm {found=1} END {exit !found}' "$_mi" 2>/dev/null; then return 1; fi
    done
    if [ -r /proc/swaps ]; then
        for _swap in $(awk 'NR>1 {print $1}' /proc/swaps); do
            [ "$(readlink -f "$_swap" 2>/dev/null || :)" != "/dev/block/$_idle_name" ] || return 1
        done
    fi
    return 0
}
disk_idle() {
    block_idle "$UD_PARENT" || return 1
    for _child in /sys/class/block/"$UD_PARENT"/*; do
        [ ! -r "$_child/partition" ] || block_idle "${_child##*/}" || return 1
    done
}
block_idle "$ABL_NAME" || die 'current-slot abl is mounted, swapped, or has holders'
[ "$ABL_WRITE_TARGET" = OTHER ] || bf_readonly_state "$ABL" >/dev/null || die 'current-slot ABL read-only state is unknown'
if [ "$PLAN_MODE" = create ]; then
    for _tool in e2fsck resize2fs dumpe2fs; do bf_tool_available "$_tool" || die "missing ext4 utility: $_tool"; done
    disk_idle || die 'userdata disk has mounted partitions, swap, or dm holders; use offline recovery'
    bf_readonly_state "$DISK" >/dev/null || die 'userdata disk read-only state is unknown'
    bf_readonly_state "$USERDATA" >/dev/null || die 'userdata read-only state is unknown'
    [ "$RAW_FILESYSTEM" = ext4 ] || die 'userdata shrink requires verified raw ext4; f2fs and encrypted mappings are unsupported'
    _crypto=$(getprop ro.crypto.state 2>/dev/null || :)
    case "$_crypto" in encrypted) die 'userdata reports encryption; no raw shrink backend is selected' ;; esac
    # An encryption footer occupies the tail even if a raw ext4 signature is visible.
    for _fstab in /fstab.* /etc/recovery.fstab /vendor/etc/fstab* /odm/etc/fstab* /system/etc/fstab*; do
        [ -r "$_fstab" ] || continue
        if awk '!/^[ \t]*#/ && /userdata|[ \t]\/data[ \t]/ && /encryptable=footer|forceencrypt=footer|forcefdeorfbe=footer|metadata_encryption/ {found=1} END {exit !found}' "$_fstab"; then
            die 'userdata fstab requests footer or metadata encryption'
        fi
    done
    EXT4_HEADER=$(dumpe2fs -h "$USERDATA" 2>/dev/null) || die 'ext4 superblock inspection failed'
    EXT4_BLOCK_SIZE=$(info_field "$EXT4_HEADER" 'Block size' | awk '{print $1}')
    EXT4_BLOCK_COUNT=$(info_field "$EXT4_HEADER" 'Block count' | awk '{print $1}')
    is_uint "$EXT4_BLOCK_SIZE" && is_uint "$EXT4_BLOCK_COUNT" || die 'ext4 block geometry is unknown'
    [ "$EXT4_BLOCK_SIZE" -gt 0 ] || die 'ext4 block size is invalid'
    [ $((EXT4_BLOCK_SIZE * EXT4_BLOCK_COUNT)) -le $((UD_SIZE512 * 512)) ] || die 'ext4 filesystem exceeds userdata partition'
    # Keep 1 MiB spare inside userdata instead of relying on rounded units.
    TARGET_FS_BLOCKS=$(((NEW_UD_BYTES - 1048576) / EXT4_BLOCK_SIZE))
    [ "$TARGET_FS_BLOCKS" -gt 0 ] || die 'target filesystem is too small'
    _fsck=$(e2fsck -fn "$USERDATA" 2>&1); _fsck_rc=$?
    [ "$_fsck_rc" = 0 ] || die 'read-only e2fsck found issues; repair and recheck separately before shrinking'
    _minimum=$(resize2fs -P "$USERDATA" 2>&1) || die 'ext4 minimum size query failed'
    MIN_FS_BLOCKS=$(printf '%s\n' "$_minimum" | awk '/^Estimated minimum size of the filesystem:/ {print $NF}')
    is_uint "$MIN_FS_BLOCKS" || die 'ext4 minimum size is unknown'
    [ "$MIN_FS_BLOCKS" -le "$TARGET_FS_BLOCKS" ] || die 'ext4 does not have enough movable free space for the shrink'
else
    block_idle "$EFI_NAME" || die 'efisp is mounted, swapped, or has holders'
    bf_readonly_state "$EFISP" >/dev/null || die 'efisp read-only state is unknown'
    # Reuse never resizes userdata or changes GPT, so it does not require the
    # shrink worker's checkpoint/feature eligibility. Still verify the actual
    # filesystem boundary to reject an incomplete previous GPT-only resize.
    # Read the plaintext filesystem through its mounted mapping when present.
    [ -x "$SCRIPT_DIR/fsprobe" ] || die 'filesystem geometry helper is missing'
    if [ "$DATA_SOURCE" != unknown ]; then
        _reuse_source=$(readlink -f "$DATA_SOURCE") || die 'mounted userdata source is unresolved'
        case ">$DATA_CHAIN>" in *">$UD_NAME>"*) ;; *) die 'mounted data chain does not contain userdata' ;; esac
    else
        _reuse_source=$USERDATA
        block_idle "$UD_NAME" || die 'userdata has active mappings without a verifiable data mount'
    fi
    _reuse_proof=$("$SCRIPT_DIR/fsprobe" "$_reuse_source" 2>&1) || die 'userdata filesystem geometry verification failed'
    FS_READ_MODE=$(printf '%s\n' "$_reuse_proof" | sed -n 's/^FS_READ_MODE=//p')
    [ "$FS_READ_MODE" = direct ] && [ "$(printf '%s\n' "$_reuse_proof" | sed -n 's/^RESULT=//p')" = success ] || die 'direct filesystem proof is missing'
    _reuse_type=$(printf '%s\n' "$_reuse_proof" | sed -n 's/^FS_TYPE=//p')
    case "$DATA_FS" in
        f2fs|ext4) [ "$_reuse_type" = "$DATA_FS" ] || die 'mounted userdata filesystem type does not match direct geometry' ;;
    esac
    _reuse_match=$(printf '%s\n' "$_reuse_proof" | sed -n 's/^FS_MATCH=//p')
    _reuse_device=$(printf '%s\n' "$_reuse_proof" | sed -n 's/^DEVICE_BYTES=//p')
    _reuse_bytes=$(printf '%s\n' "$_reuse_proof" | sed -n 's/^FS_BYTES=//p')
    is_uint "$_reuse_bytes" && [ "$_reuse_match" = 1 ] && \
        [ "$_reuse_device" = "$((UD_SIZE512 * 512))" ] && [ "$_reuse_bytes" -gt 0 ] && \
        [ "$_reuse_bytes" -le "$NEW_UD_BYTES" ] || die 'filesystem or mapping still exceeds userdata GPT boundary'
    TAIL_FREE_BYTES=$((NEW_UD_BYTES - _reuse_bytes))
fi

# The app generates this manifest from the selected files, or each default image
# independently. No extension or executable-format assumption selects the input.
[ -f "$PAYLOAD_MANIFEST" ] && [ -r "$PAYLOAD_MANIFEST" ] || die 'payload manifest is missing or unreadable'
_payload_hashes=$(awk '
    NF != 2 || length($1) != 64 || $1 ~ /[^0-9a-f]/ {bad=1; next}
    $2 == "efisp.img" {pe=$1; pc++; next}
    $2 == "abl.img" {abl=$1; ac++; next}
    {bad=1}
    END {if(bad || pc!=1 || ac!=1) exit 1; print pe,abl}
' "$PAYLOAD_MANIFEST") || die 'payload manifest must contain exactly the two staged image hashes'
set -- $_payload_hashes
[ "$#" = 2 ] && is_hash "$1" && is_hash "$2" || die 'payload manifest hashes are invalid'
PE_HASH=$1 ABL_HASH=$2
PAYLOAD_MANIFEST_HASH=$(sha256sum "$PAYLOAD_MANIFEST" | awk '{print $1}')
for _image in "$PE_IMAGE" "$ABL_IMAGE"; do [ -f "$_image" ] && [ -r "$_image" ] || die 'selected image is missing or unreadable'; done
PE_BYTES=$(wc -c < "$PE_IMAGE" | tr -d ' ')
ABL_BYTES=$(wc -c < "$ABL_IMAGE" | tr -d ' ')
is_uint "$PE_BYTES" && is_uint "$ABL_BYTES" || die 'image sizes are invalid'
[ "$PE_BYTES" -gt 0 ] && [ "$PE_BYTES" -le "$EFI_BYTES" ] || die 'PE image exceeds efisp capacity'
[ "$ABL_BYTES" -gt 0 ] && { [ "$ABL_WRITE_TARGET" = OTHER ] || [ "$ABL_BYTES" -le "$ABL_PARTITION_BYTES" ]; } || die 'ABL image exceeds the selected current-slot abl partition'
[ "$(sha256sum "$PE_IMAGE" | awk '{print $1}')" = "$PE_HASH" ] || die 'efisp image differs from the staged payload manifest'
[ "$(sha256sum "$ABL_IMAGE" | awk '{print $1}')" = "$ABL_HASH" ] || die 'ABL image differs from the staged payload manifest'
initialize_other_abl

# Inspect the existing ancestor without creating a directory in --check.
BACKUP_ANCESTOR=$BACKUP_BASE
while [ ! -e "$BACKUP_ANCESTOR" ]; do
    _above=${BACKUP_ANCESTOR%/*}; [ -n "$_above" ] || _above=/
    [ "$_above" != "$BACKUP_ANCESTOR" ] || die 'backup ancestor resolution failed'
    BACKUP_ANCESTOR=$_above
done
BACKUP_ANCESTOR=$(readlink -f "$BACKUP_ANCESTOR") || die 'backup ancestor is unresolved'
[ -d "$BACKUP_ANCESTOR" ] && [ -w "$BACKUP_ANCESTOR" ] || die 'backup ancestor is not a writable directory'
BACKUP_MOUNT=$(awk -v p="$BACKUP_ANCESTOR" '
    {m=$5; if(m=="/" || p==m || index(p,m"/")==1) {if(length(m)>best) {best=length(m); mm=$3; for(i=6;i<=NF;i++) if($i=="-") fs=$(i+1)}}}
    END {if(best) print mm,fs}' /proc/self/mountinfo)
set -- $BACKUP_MOUNT
[ "$#" = 2 ] || die 'backup backing mount could not be identified'
BACKUP_MM=$1 BACKUP_FS=$2
if [ "$PLAN_MODE" = create ]; then
    case "$BACKUP_FS" in tmpfs|ramfs) ;; *)
        _backup_block=
        for _sys in /sys/class/block/*/dev; do
            [ ! -r "$_sys" ] || [ "$(cat "$_sys")" != "$BACKUP_MM" ] || _backup_block=${_sys%/dev}
        done
        [ -n "$_backup_block" ] || die 'backup backing device is unknown; use recovery /tmp'
        _backup_name=${_backup_block##*/}
        [ "$_backup_name" != "$UD_PARENT" ] || die 'backup is on the userdata disk; use recovery /tmp or another disk'
        if [ -r "$_backup_block/partition" ]; then
            _backup_real=$(readlink -f "$_backup_block"); _backup_parent=${_backup_real%/*}; _backup_parent=${_backup_parent##*/}
            [ "$_backup_parent" != "$UD_PARENT" ] || die 'backup is on the userdata disk; use recovery /tmp or another disk'
        fi
        [ ! -d "$_backup_block/dm" ] || die 'backup backing device is mapped; use recovery /tmp'
        ;;
    esac
fi
[ "$BACKUP_MM" != "$ABL_MM" ] || die 'backup filesystem resolves to the abl target'
if other_target_selected; then [ "$BACKUP_MM" != "$OTHER_ABL_MM" ] || die 'backup filesystem resolves to the opposite-slot abl target'; fi
[ "$PLAN_MODE" != reuse ] || [ "$BACKUP_MM" != "$EFI_MM" ] || die 'backup filesystem resolves to efisp'
FREE_KB=$(df -Pk "$BACKUP_ANCESTOR" 2>/dev/null | awk 'END {print $4}')
is_uint "$FREE_KB" || die 'backup free space could not be queried'
REQUIRED_BACKUP=$((ABL_PARTITION_BYTES + OTHER_ABL_CAPACITY * 2 + 16777216))
[ "$PLAN_MODE" != reuse ] || REQUIRED_BACKUP=$((REQUIRED_BACKUP + EFI_BYTES))
[ $((FREE_KB * 1024)) -ge "$REQUIRED_BACKUP" ] || die 'backup destination does not have enough free space'

if [ "$MODE_ARG" = --check ]; then report 1 ''; exit 0; fi

trap 'printf "%s\n" "ERROR=operation interrupted; inspect backup and on-disk state before retry" "STAGE=$STAGE" "BACKUP_DIR=$BACKUP_DIR"; write_failure_state interrupted; exit 130' INT TERM HUP
BACKUP_DIR=$BACKUP_BASE/$(date -u +%Y%m%dT%H%M%SZ)-$$
mkdir -p "$BACKUP_DIR" || die 'backup directory creation failed'
chmod 700 "$BACKUP_DIR" || die 'backup directory permissions failed'
log "preflight passed; mode=$PLAN_MODE; running_slot=$SLOT; abl=$ABL"
log "backup_directory=$BACKUP_DIR"
STAGE=backup
if [ "$PLAN_MODE" = create ]; then
    "$GPTTOOL" plan "$DISK" "$UD_INDEX" "$NEW_UD_END" "$EFI_GPT_INDEX" "$EFI_GUID" \
        "$BACKUP_DIR/gpt-before.bin" "$BACKUP_DIR/gpt-planned.bin" > "$BACKUP_DIR/gpt-plan.log" 2>&1 || die 'raw GPT snapshot and plan creation failed before disk writes'
    grep -qx 'DISK_READ_MODE=direct' "$BACKUP_DIR/gpt-plan.log" && grep -qx 'RESULT=success' "$BACKUP_DIR/gpt-plan.log" && [ -s "$BACKUP_DIR/gpt-planned.bin" ] || die 'raw GPT planned snapshot or proof is missing'
    GPT_PLANNED_HASH=$(hash_file "$BACKUP_DIR/gpt-planned.bin")
else
    export_gpt "$DISK" "$BACKUP_DIR/gpt-before.bin" "$BACKUP_DIR/gpt-backup.log" || die 'raw GPT backup failed before disk writes'
fi
[ -s "$BACKUP_DIR/gpt-before.bin" ] || die 'raw GPT backup file is empty'
GPT_BEFORE_HASH=$(hash_file "$BACKUP_DIR/gpt-before.bin")
GPTTOOL_HASH=$(hash_file "$GPTTOOL")
cp "$GPTTOOL" "$BACKUP_DIR/gpttool.bin" || die 'GPT helper backup copy failed'
[ "$(hash_file "$BACKUP_DIR/gpttool.bin")" = "$GPTTOOL_HASH" ] || die 'GPT helper backup copy hash mismatch'
if [ "$ABL_PARENT" != "$UD_PARENT" ]; then
    export_gpt "/dev/block/$ABL_PARENT" "$BACKUP_DIR/abl-disk-gpt-before.bin" "$BACKUP_DIR/abl-gpt-backup.log" || die 'ABL parent raw GPT backup failed'
    [ -s "$BACKUP_DIR/abl-disk-gpt-before.bin" ] || die 'ABL parent raw GPT backup is empty'
fi
read_direct "$ABL" 0 "$ABL_PARTITION_BYTES" "$BACKUP_DIR/abl_$SLOT.full.bin" "$BACKUP_DIR/abl-backup.log" || die 'full original abl backup failed'
[ "$(wc -c < "$BACKUP_DIR/abl_$SLOT.full.bin" | tr -d ' ')" = "$ABL_PARTITION_BYTES" ] || die 'original abl backup length is wrong'
_abl_backup_hash=$(sha256sum "$BACKUP_DIR/abl_$SLOT.full.bin" | awk '{print $1}')
verify_payload_source_abl "$_abl_backup_hash"
read_direct "$ABL" 0 "$ABL_PARTITION_BYTES" "$BACKUP_DIR/abl-backup-readback.bin" "$BACKUP_DIR/abl-backup-readback.log" && [ "$_abl_backup_hash" = "$(hash_file "$BACKUP_DIR/abl-backup-readback.bin")" ] || die 'original abl backup verification failed'
backup_other_abl "$BACKUP_DIR"
if [ "$PLAN_MODE" = reuse ]; then
    read_direct "$EFISP" 0 "$EFI_BYTES" "$BACKUP_DIR/efisp.full.bin" "$BACKUP_DIR/efisp-backup.log" || die 'original efisp backup failed'
    [ "$(wc -c < "$BACKUP_DIR/efisp.full.bin" | tr -d ' ')" = "$EFI_BYTES" ] || die 'original efisp backup length is wrong'
    read_direct "$EFISP" 0 "$EFI_BYTES" "$BACKUP_DIR/efisp-backup-readback.bin" "$BACKUP_DIR/efisp-backup-readback.log" && [ "$(hash_file "$BACKUP_DIR/efisp.full.bin")" = "$(hash_file "$BACKUP_DIR/efisp-backup-readback.bin")" ] || die 'original efisp backup verification failed'
fi
verify_payload_source_file
if [ "$SOURCE_BINDING_HASH" != not-present ]; then
    cp "$SOURCE_BINDING_FILE" "$BACKUP_DIR/payload-source.txt" || die 'payload source binding backup copy failed'
    [ "$(hash_file "$BACKUP_DIR/payload-source.txt")" = "$SOURCE_BINDING_HASH" ] || die 'payload source binding backup hash mismatch'
fi
PLAN_ID=$(printf '%s\n' "DISK=$DISK" "DISK_GUID=$DISK_GUID" "SLOT=$SLOT" \
    "UD_INDEX=$UD_INDEX" "UD_START=$UD_START" "UD_END=$UD_END" "NEW_UD_END=$NEW_UD_END" \
    "EFI_INDEX=$EFI_GPT_INDEX" "EFI_START=$EFI_START" "EFI_END=$EFI_END" "EFI_GUID=$EFI_GUID" \
    "GPT_BEFORE_SHA256=$GPT_BEFORE_HASH" "GPT_PLANNED_SHA256=$GPT_PLANNED_HASH" "GPTTOOL_SHA256=$GPTTOOL_HASH" \
    "ABL_BEFORE_SHA256=$_abl_backup_hash" "PAYLOAD_MANIFEST_SHA256=$PAYLOAD_MANIFEST_HASH" \
    "PAYLOAD_SOURCE_SHA256=$SOURCE_BINDING_HASH" "ABL_WRITE_TARGET=$ABL_WRITE_TARGET" \
    "OTHER_ABL_BEFORE_SHA256=$OTHER_ABL_BEFORE_HASH" "OTHER_ABL_GPT_SHA256=$OTHER_ABL_GPT_BEFORE_HASH" | sha256sum | awk '{print $1}')
(
    cd "$BACKUP_DIR" || exit 1
    sha256sum gpt-before.bin gpttool.bin "abl_$SLOT.full.bin" || exit 1
    other_abl_inventory || exit 1
    if [ "$SOURCE_BINDING_HASH" != not-present ]; then sha256sum payload-source.txt || exit 1; fi
    [ "$PLAN_MODE" != create ] || sha256sum gpt-planned.bin || exit 1
    [ "$PLAN_MODE" != reuse ] || sha256sum efisp.full.bin || exit 1
    [ "$ABL_PARENT" = "$UD_PARENT" ] || sha256sum abl-disk-gpt-before.bin
) > "$BACKUP_DIR/backup-inventory.sha256" || die 'backup inventory creation failed'
{
    printf '%s\n' "device=$DEVICE" "slot=$SLOT" "disk=$DISK" "disk_guid=$DISK_GUID" \
        "manufacturer=$MANUFACTURER" "soc=$SOC" \
        "logical_sector=$LOGICAL_SECTOR" "mode=$PLAN_MODE" "userdata=$USERDATA" \
        "userdata_index=$UD_INDEX" "userdata_start=$UD_START" "userdata_old_end=$UD_END" \
        "userdata_new_end=$NEW_UD_END" "userdata_guid=$UD_GUID" "userdata_type=$UD_TYPE" \
        "userdata_attributes=$UD_ATTR" "efisp_index=$EFI_GPT_INDEX" "efisp_start=$EFI_START" \
        "efisp_end=$EFI_END" "efisp_profile=$EFI_METADATA_PROFILE" "efisp_label=$EFI_LABEL" \
        "efisp_type=$EFI_TYPE" "efisp_attributes=$EFI_TARGET_ATTR" "efisp_guid=$EFI_GUID" \
        "abl=$ABL" "abl_backup_sha256=$_abl_backup_hash" \
        "OTHER_ABL_BEFORE_SHA256=$OTHER_ABL_BEFORE_HASH" "OTHER_ABL_GPT_SHA256=$OTHER_ABL_GPT_BEFORE_HASH" \
        "PLAN_ID=$PLAN_ID" "GPT_BEFORE_SHA256=$GPT_BEFORE_HASH" "GPT_PLANNED_SHA256=$GPT_PLANNED_HASH" \
        "GPTTOOL_SHA256=$GPTTOOL_HASH" "INVENTORY_SHA256=$(hash_file "$BACKUP_DIR/backup-inventory.sha256")" \
        "pe_sha256=$PE_HASH" "abl_image_sha256=$ABL_HASH" "payload_manifest_sha256=$PAYLOAD_MANIFEST_HASH" \
        "PAYLOAD_SOURCE_SHA256=$SOURCE_BINDING_HASH" \
        'note=GPT and abl backups do not restore userdata filesystem contents; no automatic rollback'
    other_abl_plan_text
    printf '%s\n' "$GPT_TABLE" "$UD_INFO"
} > "$BACKUP_DIR/manifest.txt" || die 'backup manifest write failed'
sync || die 'backup sync failed'

# Revalidate the facts that could change while backups were being read.
[ "$(sha256sum "$PE_IMAGE" | awk '{print $1}')" = "$PE_HASH" ] && [ "$(sha256sum "$ABL_IMAGE" | awk '{print $1}')" = "$ABL_HASH" ] || die 'images changed after preflight'
[ "$(sha256sum "$PAYLOAD_MANIFEST" | awk '{print $1}')" = "$PAYLOAD_MANIFEST_HASH" ] || die 'payload manifest changed after staging'
verify_payload_source_file
[ "$(readlink -f "/dev/block/by-name/abl_$SLOT")" = "$ABL" ] || die 'abl target changed after preflight'
[ "$(hash_file "$GPTTOOL")" = "$GPTTOOL_HASH" ] || die 'raw GPT helper changed since backup'
(cd "$BACKUP_DIR" && sha256sum -c backup-inventory.sha256) > "$BACKUP_DIR/inventory-readback.log" 2>&1 || die 'backup inventory changed before writes'
export_gpt "$DISK" "$BACKUP_DIR/gpt-source-readback.bin" "$BACKUP_DIR/gpt-source-readback.log" || die 'raw GPT source verification failed before writes'
[ "$(hash_file "$BACKUP_DIR/gpt-source-readback.bin")" = "$GPT_BEFORE_HASH" ] || die 'raw GPT changed since backup; filesystem and GPT writes are blocked'
verify_gpt || die 'raw GPT validation failed after backup'
GPT_SOURCE_INSPECT=$(inspect_gpt "$DISK" "$UD_INDEX") || die 'direct GPT source inspection failed'
[ "$GPT_SOURCE_INSPECT" = "$GPT_INSPECT" ] || die 'direct GPT changed after preflight'
block_idle "$ABL_NAME" || die 'abl became busy after preflight'
verify_other_abl_unchanged "$BACKUP_DIR"
# A late EROFS from an offline filesystem command is retried only after direct
# geometry inspection and a clean read-only fsck; a completed resize is not repeated.
run_ext4_write() {
    local ext_command=$1 ext_log=$2 ext_identity ext_rc ext_header ext_bs ext_bc
    shift 2
    # Internal verification failures must not alias e2fsck's successful rc=1.
    ext_identity=$(bf_target_identity "$USERDATA") || return 125
    bf_ensure_bound_writable "$USERDATA" /dev/block/by-name/userdata || return 125
    [ "$(bf_target_identity "$USERDATA")" = "$ext_identity" ] || return 125
    "$ext_command" "$@" > "$ext_log" 2>&1; ext_rc=$?
    if [ "$ext_rc" = 0 ] || { [ "$ext_command" = e2fsck ] && [ "$ext_rc" = 1 ]; }; then return "$ext_rc"; fi
    [ "$(bf_target_identity "$USERDATA")" = "$ext_identity" ] || return 125
    bf_readonly_failure "$USERDATA" "$ext_log" || return "$ext_rc"
    cp "$ext_log" "$ext_log.first-attempt" || return 125
    ext_header=$(dumpe2fs -h "$USERDATA" 2> "$ext_log.retry-geometry.log") || return 125
    ext_bs=$(info_field "$ext_header" 'Block size' | awk '{print $1}')
    ext_bc=$(info_field "$ext_header" 'Block count' | awk '{print $1}')
    [ "$ext_bs" = "$EXT4_BLOCK_SIZE" ] || return 125
    [ "$ext_bc" = "$EXT4_BLOCK_COUNT" ] || \
        { [ "$ext_command" = resize2fs ] && [ "$ext_bc" = "$TARGET_FS_BLOCKS" ]; } || return 125
    e2fsck -fn "$USERDATA" > "$ext_log.retry-fsck.log" 2>&1 || return 125
    bf_ensure_bound_writable "$USERDATA" /dev/block/by-name/userdata force || return 125
    [ "$(bf_target_identity "$USERDATA")" = "$ext_identity" ] || return 125
    if [ "$ext_command" = resize2fs ] && [ "$ext_bc" = "$TARGET_FS_BLOCKS" ]; then
        log 'EXT4_RO_RECOVERY=planned-filesystem-already-complete'
        return 0
    fi
    log "EXT4_RETRY=read-only-and-unchanged-clean-geometry;COMMAND=$ext_command;ATTEMPT=2"
    "$ext_command" "$@" > "$ext_log" 2>&1
}

BF_WRITE_AUTHORIZED=1
ensure_selected_abl_writable
if [ "$PLAN_MODE" = create ]; then
    bf_ensure_writable "$DISK" || die 'userdata GPT disk could not be made writable'
    bf_ensure_bound_writable "$USERDATA" /dev/block/by-name/userdata || die 'userdata could not be made writable'
else
    bf_ensure_bound_writable "$EFISP" /dev/block/by-name/efisp || die 'efisp could not be made writable'
fi

if [ "$PLAN_MODE" = create ]; then
    disk_idle || die 'userdata disk became busy after preflight'
    STAGE=filesystem-shrink
    log 'checking offline ext4 before resize'
    FILESYSTEM_MAY_BE_MODIFIED=1
    run_ext4_write e2fsck "$BACKUP_DIR/ext4-fsck-write.log" -fp "$USERDATA"; _rc=$?
    case "$_rc" in 0|1) ;; *) die 'ext4 check requested additional repair or reboot; GPT was not changed' ;; esac
    log "resizing userdata ext4 to $TARGET_FS_BLOCKS filesystem blocks"
    run_ext4_write resize2fs "$BACKUP_DIR/ext4-resize-write.log" "$USERDATA" "$TARGET_FS_BLOCKS" || die 'ext4 resize failed or read-only state could not be reconciled; GPT remains original; inspect filesystem'
    e2fsck -fn "$USERDATA" || die 'resized ext4 verification failed; GPT remains original'
    _after_header=$(dumpe2fs -h "$USERDATA" 2>/dev/null) || die 'resized ext4 superblock query failed'
    _after_bs=$(info_field "$_after_header" 'Block size' | awk '{print $1}')
    _after_bc=$(info_field "$_after_header" 'Block count' | awk '{print $1}')
    is_uint "$_after_bs" && is_uint "$_after_bc" || die 'resized filesystem geometry is invalid'
    [ "$_after_bs" = "$EXT4_BLOCK_SIZE" ] && [ "$_after_bc" = "$TARGET_FS_BLOCKS" ] || die 'resized filesystem geometry differs from requested size'
    [ $((_after_bs * _after_bc)) -le "$NEW_UD_BYTES" ] || die 'resized filesystem still exceeds new userdata boundary'
    sync || die 'filesystem sync failed before GPT update'
    disk_idle || die 'userdata disk became busy before GPT update; resized filesystem still fits original GPT'
    STAGE=gpt-write
    log 'committing userdata end change and efisp entry in one GPT transaction'
    export_gpt "$DISK" "$BACKUP_DIR/gpt-write-source.bin" "$BACKUP_DIR/gpt-write-source.log" || die 'raw GPT source readback failed after ext4 shrink'
    [ "$(hash_file "$BACKUP_DIR/gpt-write-source.bin")" = "$GPT_BEFORE_HASH" ] && \
        [ "$(hash_file "$BACKUP_DIR/gpt-planned.bin")" = "$GPT_PLANNED_HASH" ] && \
        [ "$(hash_file "$GPTTOOL")" = "$GPTTOOL_HASH" ] || die 'raw GPT source, plan, or helper changed before the sole apply'
    GPT_MAY_BE_MODIFIED=1
    bf_run_gpt_apply "$GPTTOOL" "$DISK" "$BACKUP_DIR/gpt-before.bin" "$BACKUP_DIR/gpt-planned.bin" "$BACKUP_DIR/gpt-write.log"
    GPT_WRITE_RC=$?
    [ "$GPT_WRITE_RC" = 0 ] || die 'raw GPT apply failed or changed state prevents read-only retry; retain snapshots and inspect'
    grep -qx 'DISK_READ_MODE=direct' "$BACKUP_DIR/gpt-write.log" && grep -qx 'RESULT=success' "$BACKUP_DIR/gpt-write.log" && \
        grep -qx 'READBACK=match' "$BACKUP_DIR/gpt-write.log" || die 'raw GPT apply proof is missing; no transaction replay'
    sync || die 'GPT sync failed; inspect on-disk and kernel geometry before retry'
    export_gpt "$DISK" "$BACKUP_DIR/gpt-after.bin" "$BACKUP_DIR/gpt-after.log" || die 'raw GPT post-apply export failed'
    [ "$(hash_file "$BACKUP_DIR/gpt-after.bin")" = "$GPT_PLANNED_HASH" ] || die 'raw GPT after apply differs from exact planned snapshot'
    verify_gpt || die 'GPT post-write verification failed; restore only after reviewing backups'
    GPT_AFTER_INSPECT=$(inspect_gpt "$DISK" "$UD_INDEX") || die 'direct GPT post-write inspection failed'
    [ "$(kv "$GPT_AFTER_INSPECT" UD_START)" = "$UD_START" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" UD_END)" = "$NEW_UD_END" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" UD_TYPE)" = "$UD_TYPE" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" UD_GUID)" = "$UD_GUID" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" UD_ATTR)" = "$UD_ATTR" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" UD_ATTR_BACKUP)" = "$UD_ATTR_BACKUP" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" UD_LABEL)" = userdata ] || die 'direct userdata metadata was not preserved'
    [ "$(gpt_header "$GPT_AFTER_INSPECT")" = "$GPT_HEADER_GEOMETRY" ] || die 'direct GPT header changed'
    [ "$(kv "$GPT_AFTER_INSPECT" EFI_COUNT)" = 1 ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" EFI_INDEX)" = "$EFI_GPT_INDEX" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" EFI_START)" = "$EFI_START" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" EFI_END)" = "$EFI_END" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" EFI_BYTES)" = "$EFI_BYTES" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" EFI_TYPE)" = "$EFI_TYPE" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" EFI_GUID)" = "$EFI_GUID" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" EFI_ATTR)" = "$EFI_TARGET_ATTR" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" EFI_ATTR_BACKUP)" = "$EFI_TARGET_ATTR_BACKUP" ] && \
        [ "$(kv "$GPT_AFTER_INSPECT" EFI_LABEL)" = "$EFI_LABEL" ] || die 'direct efisp common profile readback failed'
    printf '%s\n' "$GPT_AFTER_INSPECT" > "$BACKUP_DIR/gpt-after-proof.txt" || die 'direct GPT proof persistence failed'
    GPT_VERIFIED=1
    STAGE=kernel-partition-reread
    blockdev --rereadpt "$DISK" || die 'GPT committed but kernel partition reread failed; reboot recovery and run check again'
    # Recovery ueventd may need a moment to publish the new by-name symlink.
    _attempt=0
    while [ ! -b /dev/block/by-name/efisp ] && [ "$_attempt" -lt 5 ]; do
        command -v sleep >/dev/null 2>&1 || break
        sleep 1; _attempt=$((_attempt + 1))
    done
    inspect_block /dev/block/by-name/userdata || die 'GPT committed; userdata kernel node missing; reboot recovery'
    [ "$BLOCK_PARENT" = "$UD_PARENT" ] && [ "$BLOCK_INDEX" = "$UD_INDEX" ] && \
        [ "$BLOCK_START512" = "$UD_START512" ] && [ $((BLOCK_SIZE512 * 512)) = "$NEW_UD_BYTES" ] || die 'GPT committed; userdata kernel geometry stale; reboot recovery'
    inspect_block /dev/block/by-name/efisp || die 'GPT committed; efisp kernel node missing; reboot recovery'
    EFISP=$BLOCK_PATH EFI_NAME=$BLOCK_NAME EFI_MM=$BLOCK_MM
    [ "$BLOCK_PARENT" = "$UD_PARENT" ] && [ "$BLOCK_INDEX" = "$EFI_GPT_INDEX" ] && \
        [ $((BLOCK_START512 * 512)) = "$((EFI_START * LOGICAL_SECTOR))" ] && \
        [ $((BLOCK_SIZE512 * 512)) = "$EFI_BYTES" ] || die 'GPT committed; efisp kernel geometry stale; reboot recovery'
    unique_partname efisp "$EFI_NAME" || die 'new efisp sysfs name is ambiguous'
fi

# Bind the final write to the same GPT entry, physical disk, and kernel geometry.
validate_efisp_target() {
[ "$(readlink -f /dev/block/by-name/efisp 2>/dev/null)" = "$EFISP" ] || die 'efisp target link changed before write'
inspect_block "$EFISP" || die 'efisp block identity disappeared before write'
[ "$BLOCK_PARENT" = "$UD_PARENT" ] && [ "$BLOCK_INDEX" = "$EFI_GPT_INDEX" ] && \
    [ $((BLOCK_START512 * 512)) = "$((EFI_START * LOGICAL_SECTOR))" ] && \
    [ $((BLOCK_SIZE512 * 512)) = "$EFI_BYTES" ] || die 'efisp block identity changed before write'
_final_efi=$(inspect_gpt "$DISK" "$UD_INDEX") || die 'direct efisp GPT inspection failed before write'
if [ "$PLAN_MODE" = reuse ]; then
    [ "$_final_efi" = "$EFI_GPT_INFO" ] || die 'existing direct efisp GPT metadata changed before write'
else
    [ "$_final_efi" = "$GPT_AFTER_INSPECT" ] || die 'new direct efisp GPT metadata changed before write'
fi
[ "$(sha256sum "$PE_IMAGE" | awk '{print $1}')" = "$PE_HASH" ] || die 'PE image changed before write'
block_idle "$EFI_NAME" || die 'efisp became busy before write'
bf_readonly_state "$EFISP" >/dev/null || die 'efisp read-only state became unknown'
}
STAGE=targets-final-validation
validate_efisp_target
STAGE=abl-write
# Re-read running-slot information immediately before the sole abl write.
if command -v getprop >/dev/null 2>&1; then accept_slot "$(getprop ro.boot.slot_suffix 2>/dev/null)" ro.boot.slot_suffix; fi
if command -v bootctl >/dev/null 2>&1; then
    _now=$(bootctl get-current-slot 2>/dev/null || :)
    case "$_now" in 0|1) accept_slot "$(bootctl get-suffix "$_now" 2>/dev/null || :)" bootctl ;; '') ;; *) die 'current-slot HAL changed before abl write' ;; esac
fi
write_current_abl() {
[ "$(readlink -f "/dev/block/by-name/abl_$SLOT")" = "$ABL" ] || die 'abl target changed before write'
inspect_block "$ABL" || die 'current-slot abl block identity disappeared before write'
[ "$BLOCK_NAME" = "$ABL_NAME" ] && [ "$BLOCK_PARENT" = "$ABL_PARENT" ] && \
    [ "$BLOCK_INDEX" = "$ABL_INDEX" ] && [ "$BLOCK_START512" = "$ABL_START512" ] && \
    [ "$BLOCK_MM" = "$ABL_MM" ] && [ $((BLOCK_SIZE512 * 512)) = "$ABL_PARTITION_BYTES" ] || die 'current-slot abl block identity changed before write'
block_idle "$ABL_NAME" || die 'current-slot abl became busy before write'
bf_readonly_state "$ABL" >/dev/null || die 'current-slot ABL read-only state became unknown'
[ "$(sha256sum "$ABL_IMAGE" | awk '{print $1}')" = "$ABL_HASH" ] || die 'abl image changed before write'
log "writing abl.img to selected current-slot abl_$SLOT: $ABL"
ABL_MAY_BE_MODIFIED=1
write_direct "$ABL" 0 "$ABL_PARTITION_BYTES" "$ABL_IMAGE" "$BACKUP_DIR/abl-write.log" || die 'current-slot abl write failed; original full partition backup is retained'
sync || die 'abl sync failed'
read_direct "$ABL" 0 "$ABL_BYTES" "$BACKUP_DIR/abl-readback.bin" "$BACKUP_DIR/abl-readback.log" && [ "$(hash_file "$BACKUP_DIR/abl-readback.bin")" = "$ABL_HASH" ] || die 'current-slot abl image-length readback hash mismatch'
log 'current-slot abl image-length readback SHA-256 verified'
}
write_selected_abl_targets "$BACKUP_DIR"
STAGE=efisp-write
validate_efisp_target
log "writing selected efisp image to $EFISP"
write_direct "$EFISP" 0 "$EFI_BYTES" "$PE_IMAGE" "$BACKUP_DIR/pe-write.log" || die 'efisp write failed after ABL update; keep backups and inspect target'
sync || die 'efisp sync failed after ABL update'
read_direct "$EFISP" 0 "$PE_BYTES" "$BACKUP_DIR/pe-readback.bin" "$BACKUP_DIR/pe-readback.log" && [ "$(hash_file "$BACKUP_DIR/pe-readback.bin")" = "$PE_HASH" ] || die 'efisp image-length readback hash mismatch after ABL update'
PE_VERIFIED=1
log 'efisp image-length readback SHA-256 verified'
STAGE=complete
printf '%s\n' "SLOT=$SLOT" "ABL=$ABL" "ABL_WRITE_TARGET=$ABL_WRITE_TARGET" "ABL_TARGET_SLOTS=$(abl_target_slots)" "ABL_TARGET_PARTITIONS=$(abl_target_partitions)" "ABL_TARGETS_VERIFIED=$ABL_TARGETS_VERIFIED" "EFISP=$EFISP" "BACKUP_DIR=$BACKUP_DIR" \
    "EFI_PROFILE=$EFI_METADATA_PROFILE" "EFI_LABEL=$EFI_LABEL" "EFI_TYPE=$EFI_TYPE" \
    "EFI_BYTES=$EFI_BYTES" "TAIL_FREE_BYTES=$TAIL_FREE_BYTES" "SKIP_F2FS_SHRINK=$SKIP_F2FS_SHRINK" "FS_READ_MODE=$FS_READ_MODE" \
    "EFI_ATTR=$EFI_TARGET_ATTR" "EFI_GUID=$EFI_GUID" 'PE_VERIFIED=1' 'DATA_FORMATTED=0' \
    "REQUIRES_RECOVERY_FORMAT=$(completion_requires_recovery)" "REBOOT_TARGET=$(completion_reboot_target)" \
    "NEXT_STEP=$(completion_next_step)" 'RESULT=success'
exit 0
