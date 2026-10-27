#!/system/bin/sh
# BootForge online-create backend. No reboot, slot change, live mkfs, or rollback.
# --check is read-only. --execute --prepare creates RAM backups only.
# The caller saves the complete JOB_DIR before --execute --resume JOB_DIR
# --backup-saved MANIFEST_SHA256. This is not proof of an external backup export.
# A failed/interrupted write job is not resumed.
set -u
umask 077
export LC_ALL=C
SCRIPT_DIRECTORY_HINT=${0%/*}
export PATH="$SCRIPT_DIRECTORY_HINT/selected-tools:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin:/sbin:$SCRIPT_DIRECTORY_HINT/tools:${PATH:-}"
[ -r "$SCRIPT_DIRECTORY_HINT/tool-dispatch.sh" ] || { printf '%s\n' 'READY=0' 'BLOCK_REASON=tool dispatch helper is missing'; exit 1; }
. "$SCRIPT_DIRECTORY_HINT/tool-dispatch.sh"

ACTION=${1:---check}
case "$ACTION" in --check|--execute) ;; *) printf '%s\n' 'READY=0' 'BLOCK_REASON=usage online-create.sh --check|--execute --prepare|--execute --resume JOB_DIR --backup-saved SHA256|--execute --cancel-prepared JOB_DIR --backup-saved SHA256'; exit 2 ;; esac
[ "$#" -eq 0 ] || shift
PHASE=prepare JOB_DIR= EXPORTED_HASH= EVIDENCE_HASH=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --prepare) PHASE=prepare; shift ;;
        --resume) [ "$#" -ge 2 ] || exit 2; PHASE=resume; JOB_DIR=$2; shift 2 ;;
        --cancel-prepared) [ "$#" -ge 2 ] || exit 2; PHASE=cancel; JOB_DIR=$2; shift 2 ;;
        --retire-interrupted) [ "$#" -ge 2 ] || exit 2; PHASE=retire; JOB_DIR=$2; shift 2 ;;
        --backup-saved|--backup-exported) [ "$#" -ge 2 ] || exit 2; EXPORTED_HASH=$2; shift 2 ;;
        --evidence-saved) [ "$#" -ge 2 ] || exit 2; EVIDENCE_HASH=$2; shift 2 ;;
        *) printf '%s\n' 'READY=0' 'BLOCK_REASON=unknown argument'; exit 2 ;;
    esac
done
if [ "${BF_RAM_STAGE:-0}" = 1 ] && [ "$PHASE" = prepare ]; then
    # The RAM prepare exec has no public --resume argument. Preserve its job
    # identity in a separate variable; JOB_DIR itself is reset during parsing.
    JOB_DIR=${BF_JOB_DIR:-}
fi
[ "$ACTION" != --check ] || { [ -z "$JOB_DIR$EXPORTED_HASH$EVIDENCE_HASH" ] && [ "$PHASE" = prepare ]; } || exit 2
ROOT=0 DEVICE=unknown MANUFACTURER=unknown SOC=unknown SLOT=unknown
USERDATA=unknown FILESYSTEM=unknown EFISP=absent ABL=unknown STAGE=preflight
DATA_SOURCE=unknown DATA_CHAIN=unknown PLAN_ID=unprepared
RAMCACHE_UNVERIFIED=0
FILESYSTEM_MAY_BE_MODIFIED=0 GPT_MAY_BE_MODIFIED=0 GPT_VERIFIED=0
ABL_MAY_BE_MODIFIED=0 PE_VERIFIED=0
LOCK_DIR=/dev/bootforge-write.lock
DEFAULT_EFI_BYTES=67108864
EFI_BYTES=$DEFAULT_EFI_BYTES
TAIL_FREE_BYTES=0 SKIP_F2FS_SHRINK=0 FS_READ_MODE=unknown PLAN_MODE=create
PE_HASH= ABL_HASH= PAYLOAD_MANIFEST_HASH=
SOURCE_BINDING_HASH=not-present
ABL_WRITE_TARGET=CURRENT ABL_TARGET_SLOTS=unknown ABL_TARGET_PARTITIONS=unknown ABL_TARGETS_VERIFIED=0
EFI_PROFILE=efisp-common-v1
EFI_LABEL=efisp
EFI_TYPE=C12A7328-F81F-11D2-BA4B-00A0C93EC93B
# The bitwise intersection of the three observed GPT attribute masks is zero.
EFI_ATTR=0000000000000000
EFI_ATTR_BACKUP=$EFI_ATTR
EFI_GUID=unprepared

report() {
    printf '%s\n' "ROOT=$ROOT" "DEVICE=$DEVICE" "MANUFACTURER=$MANUFACTURER" "SOC=$SOC" \
        "SLOT=$SLOT" "USERDATA=$USERDATA" "FILESYSTEM=$FILESYSTEM" "EFISP=$EFISP" \
        "ABL=$ABL" "ABL_WRITE_TARGET=$ABL_WRITE_TARGET" "ABL_TARGET_SLOTS=$ABL_TARGET_SLOTS" "ABL_TARGET_PARTITIONS=$ABL_TARGET_PARTITIONS" "ABL_TARGETS_VERIFIED=$ABL_TARGETS_VERIFIED" 'MODE=online-create' "DATA_SOURCE=$DATA_SOURCE" "DATA_CHAIN=$DATA_CHAIN" \
        "EFI_PROFILE=$EFI_PROFILE" "EFI_LABEL=$EFI_LABEL" "EFI_TYPE=$EFI_TYPE" \
        "EFI_ATTR=$EFI_ATTR" "EFI_GUID=$EFI_GUID" \
        "EFI_BYTES=$EFI_BYTES" "TAIL_FREE_BYTES=$TAIL_FREE_BYTES" "SKIP_F2FS_SHRINK=$SKIP_F2FS_SHRINK" "FS_READ_MODE=$FS_READ_MODE" "PLAN_MODE=$PLAN_MODE" \
        "PLAN_ID=$PLAN_ID" "READY=$1" "BLOCK_REASON=$2"
}
cleanup_unpublished_prepare() {
    [ "$ACTION" = --execute ] && [ "$PHASE" = prepare ] && [ -n "$JOB_DIR" ] && \
        [ "$FILESYSTEM_MAY_BE_MODIFIED$GPT_MAY_BE_MODIFIED$ABL_MAY_BE_MODIFIED" = 000 ] || return 0
    # A published prepared job belongs to the caller and must remain cancellable.
    if [ -s "$JOB_DIR/manifest.txt" ] && [ -s "$JOB_DIR/backup-inventory.sha256" ] && \
        grep -qx 'STATE=prepared' "$JOB_DIR/journal.txt" 2>/dev/null; then return 0; fi
    [ -d "$LOCK_DIR" ] && [ "$(cat "$LOCK_DIR/job" 2>/dev/null)" = "$JOB_DIR" ] || return 0
    if rm "$LOCK_DIR/job" && rmdir "$LOCK_DIR"; then
        printf '%s\n' 'PREPARE_LOCK_RELEASED=1' "DIAGNOSTIC_JOB_DIR=$JOB_DIR"
    else printf '%s\n' 'PREPARE_LOCK_RELEASED=0' "DIAGNOSTIC_JOB_DIR=$JOB_DIR"; fi
}
journal() {
    printf '%s\n' "$*"
    if [ "$ACTION" = --execute ] && [ "${BF_RAM_STAGE:-0}" = 1 ] && [ -n "$JOB_DIR" ]; then
        printf '%s\n' "$*" >> "$JOB_DIR/journal.txt" || { cleanup_unpublished_prepare; exit 1; }
    fi
}
die() {
    if [ "$ACTION" = --check ]; then report 0 "$1"; else
        journal "ERROR=$1"
        journal "STAGE=$STAGE"
        journal "FILESYSTEM_MAY_BE_MODIFIED=$FILESYSTEM_MAY_BE_MODIFIED"
        journal "GPT_MAY_BE_MODIFIED=$GPT_MAY_BE_MODIFIED"
        journal "GPT_VERIFIED=$GPT_VERIFIED"
        journal "ABL_MAY_BE_MODIFIED=$ABL_MAY_BE_MODIFIED"
        journal "PE_VERIFIED=$PE_VERIFIED"
        if [ "$FILESYSTEM_MAY_BE_MODIFIED" = 1 ] || [ "$GPT_MAY_BE_MODIFIED" = 1 ] || [ "$ABL_MAY_BE_MODIFIED" = 1 ]; then journal 'RESULT=partial'; else journal 'RESULT=blocked'; fi
        printf '%s\n' "JOB_DIR=$JOB_DIR" 'REQUIRES_INSPECTION=1'
        cleanup_unpublished_prepare
    fi
    exit 1
}
is_uint() { case "$1" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }
is_hash() { printf '%s\n' "$1" | grep -Eq '^[0-9a-f]{64}$'; }
is_guid() { printf '%s\n' "$1" | grep -Eq '^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$'; }
field() { printf '%s\n' "$1" | sed -n "s/^$2: //p"; }
value() { sed -n "s/^$2=//p" "$1"; }
kv() { printf '%s\n' "$1" | sed -n "s/^$2=//p"; }
hash_file() { sha256sum "$1" | awk '{print $1}'; }

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

file_bytes() { wc -c < "$1" | tr -d ' \r\n'; }
safe_path() { case "$1" in /*) ;; *) return 1 ;; esac; case "$1" in *[!\ -~]*|*' '*|*'\'*|*/../*|*/./*) return 1 ;; esac; }
mount_type() {
    awk -v p="$1" '
        ($5=="/" || p==$5 || index(p,$5"/")==1) && length($5)>best {
            for(i=6;i<=NF;i++) if($i=="-") {best=length($5); fs=$(i+1)}
        } END {print fs}' /proc/self/mountinfo
}
assert_ram_directory() {
    safe_path "$JOB_DIR" || die 'invalid RAM job path'
    printf '%s\n' "$JOB_DIR" | grep -Eq '^/dev/bootforge-[A-Za-z0-9._-]+$' || die 'job must be a BootForge RAM directory'
    case "${JOB_DIR#/dev/}" in */*) die 'job path contains nested components' ;; esac
    [ -d "$JOB_DIR" ] && [ "$(readlink -f "$JOB_DIR")" = "$JOB_DIR" ] || die 'RAM job is absent or redirected'
    [ "$(mount_type "$JOB_DIR")" = tmpfs ] || die 'job is not on tmpfs'
}
assert_ram_job() {
    assert_ram_directory
    [ -d "$LOCK_DIR" ] && [ "$(cat "$LOCK_DIR/job" 2>/dev/null)" = "$JOB_DIR" ] || die 'job does not own cross-process write lock'
}
assert_retirement_job() {
    assert_ram_directory
    if [ -d "$JOB_DIR/retired-lock" ]; then
        [ "$(readlink -f "$JOB_DIR/retired-lock")" = "$JOB_DIR/retired-lock" ] && \
            [ "$(cat "$JOB_DIR/retired-lock/job" 2>/dev/null)" = "$JOB_DIR" ] || die 'retired lock evidence is redirected or belongs to another job'
    else
        assert_ram_job
    fi
}
interrupted() {
    journal "ERROR=interrupted at $STAGE; retain RAM backup and inspect before retry"
    journal "FILESYSTEM_MAY_BE_MODIFIED=$FILESYSTEM_MAY_BE_MODIFIED"
    journal "GPT_MAY_BE_MODIFIED=$GPT_MAY_BE_MODIFIED"
    journal "GPT_VERIFIED=$GPT_VERIFIED"
    journal "ABL_MAY_BE_MODIFIED=$ABL_MAY_BE_MODIFIED"
    journal "PE_VERIFIED=$PE_VERIFIED"
    if [ "$FILESYSTEM_MAY_BE_MODIFIED$GPT_MAY_BE_MODIFIED$ABL_MAY_BE_MODIFIED" = 000 ]; then journal 'RESULT=blocked'
    else journal 'RESULT=partial'; fi
    cleanup_unpublished_prepare
    exit 130
}
trap interrupted INT TERM HUP

[ "$(id -u 2>/dev/null)" != 0 ] || ROOT=1
if command -v getprop >/dev/null 2>&1; then
    DEVICE=$(getprop ro.product.device); MANUFACTURER=$(getprop ro.product.manufacturer); SOC=$(getprop ro.soc.model)
fi
[ "$ROOT" = 1 ] || die 'root is required'
[ "$((1875091200 * 512))" = 960046694400 ] && [ 960046694400 -gt 2147483647 ] 2>/dev/null || die '64bit shell arithmetic required'
for tool in id awk sed grep tr readlink cat wc sha256sum mkdir rmdir rm; do
    bf_tool_available "$tool" || die "required utility missing: $tool"
done
# A stopped job is archived by the caller, then retired rather than resumed.
# This branch never writes a filesystem, GPT, boot image, or the old evidence.
proc_identity() {
    [ -r "/proc/$1/stat" ] || return 1
    sed 's/^.*) //' "/proc/$1/stat" 2>/dev/null | awk 'NF>=20 {print $2 ":" $20}'
}
assert_no_active_job() {
    ancestors=":$$:"; ancestor=$$
    ancestor_steps=0
    while [ "$ancestor" -gt 1 ] && [ "$ancestor_steps" -lt 64 ]; do
        identity=$(proc_identity "$ancestor") || die 'process ancestry inspection failed'
        ancestor=${identity%%:*}
        is_uint "$ancestor" || die 'invalid process ancestry identity'
        ancestors="$ancestors$ancestor:"; ancestor_steps=$((ancestor_steps + 1))
    done
    for proc_dir in /proc/[0-9]*; do
        proc_pid=${proc_dir##*/}
        case "$ancestors" in *":$proc_pid:"*) continue ;; esac
        [ -r "$proc_dir/cmdline" ] || continue
        proc_before=$(proc_identity "$proc_pid") || continue
        proc_exe=$(readlink -f "$proc_dir/exe" 2>/dev/null || :)
        proc_command=$(tr '\000' ' ' < "$proc_dir/cmdline" 2>/dev/null || :)
        proc_candidate=0
        case "$proc_exe" in
            "$JOB_DIR/"*|*/partition_worker|*/gpttool|*libbootforge_partition_worker.so*|*libbootforge_gpttool.so*) proc_candidate=1 ;;
        esac
        # Also catches orphaned system dd/cp/toybox processes whose executable
        # is outside JOB_DIR but whose arguments still consume an old image.
        case "$proc_command" in *"$JOB_DIR"*) proc_candidate=1 ;; esac
        [ "$proc_candidate" = 1 ] || continue
        proc_after=$(proc_identity "$proc_pid") || continue
        [ -n "$proc_before" ] && [ "$proc_before" = "$proc_after" ] || continue
        die 'an authenticated live executor still owns or may use the interrupted job; lock retained'
    done
}
retire_other_abl_validate() {
    # Account for every selected target before releasing an interrupted job lock.
    retired_target=$(value "$JOB_DIR/manifest.txt" ABL_WRITE_TARGET)
    retired_other_source=
    [ ! -f "$JOB_DIR/payload-source.txt" ] || retired_other_source=$(value "$JOB_DIR/payload-source.txt" EFI_SOURCE)
    case "$retired_target" in
        ''|CURRENT) [ "$retired_other_source" = OTHER_SLOT_ABL ] || return 0 ;;
        OTHER|BOTH) ;;
        *) die 'interrupted ABL target selection is invalid; lock retained' ;;
    esac
    retired_other=$(value "$JOB_DIR/manifest.txt" OTHER_ABL)
    retired_other_parent=$(value "$JOB_DIR/manifest.txt" OTHER_ABL_PARENT)
    retired_other_capacity=$(value "$JOB_DIR/manifest.txt" OTHER_ABL_CAPACITY)
    retired_other_before=$(value "$JOB_DIR/manifest.txt" OTHER_ABL_BEFORE_SHA256)
    retired_other_gpt=$(value "$JOB_DIR/manifest.txt" OTHER_ABL_GPT_SHA256)
    safe_path "$retired_other" && [ -b "$retired_other" ] && [ "$retired_other" != "$retired_abl" ] && \
        is_uint "$retired_other_capacity" && [ "$retired_other_capacity" -gt 0 ] && \
        [ "$retired_other_capacity" = "$(file_bytes "$JOB_DIR/abl-other.full.bin")" ] && \
        is_hash "$retired_other_before" && is_hash "$retired_other_gpt" || die 'interrupted opposite-slot backup identity is invalid; lock retained'
    case "$retired_other_parent" in ''|*[!A-Za-z0-9._-]*) die 'interrupted opposite-slot parent identity is invalid' ;; esac
    retire_verify=$("$SCRIPT_DIRECTORY_HINT/gpttool" verify "/dev/block/$retired_other_parent" 2>&1) || die 'interrupted opposite-slot parent has invalid GPT; lock retained'
    [ "$(kv "$retire_verify" GPT_VALID)" = 1 ] && [ "$(kv "$retire_verify" RESULT)" = success ] && \
        [ "$(kv "$retire_verify" DISK_READ_MODE)" = direct ] || die 'interrupted opposite-slot parent GPT proof is missing'
    export_gpt "/dev/block/$retired_other_parent" "$JOB_DIR/retire-other-gpt.bin" "$JOB_DIR/retire-other-gpt.log" || die 'interrupted opposite-slot GPT readback failed'
    retired_other_gpt_now=$(hash_file "$JOB_DIR/retire-other-gpt.bin")
    if [ "/dev/block/$retired_other_parent" = "$retired_disk" ]; then
        [ "$retired_other_gpt_now" = "$retired_gpt_hash" ] || die 'interrupted shared parent GPT changed during validation'
    else
        [ "$retired_other_gpt_now" = "$retired_other_gpt" ] || die 'interrupted opposite-slot parent GPT changed outside the plan'
    fi
    read_direct "$retired_other" 0 "$retired_other_capacity" "$JOB_DIR/retire-other-abl.bin" "$JOB_DIR/retire-other-abl.log" || die 'interrupted opposite-slot ABL direct readback failed'
    retired_other_now=$(hash_file "$JOB_DIR/retire-other-abl.bin")
    if [ "$retired_other_now" != "$retired_other_before" ]; then
        [ "$retired_target" = OTHER ] || [ "$retired_target" = BOTH ] || die 'opposite-slot source-only ABL changed unexpectedly; lock retained'
        [ "$RETIRED_LAYOUT" = planned ] && [ -s "$JOB_DIR/abl.img" ] || die 'opposite-slot ABL changed outside a complete plan; lock retained'
        retired_image_bytes=$(file_bytes "$JOB_DIR/abl.img")
        [ "$retired_image_bytes" -le "$retired_other_capacity" ] || die 'interrupted ABL image exceeds the opposite-slot backup'
        retired_other_expected=$({ cat "$JOB_DIR/abl.img"; tail -c "+$((retired_image_bytes + 1))" "$JOB_DIR/abl-other.full.bin"; } | sha256sum | awk '{print $1}')
        [ "$retired_other_now" = "$retired_other_expected" ] || die 'opposite-slot ABL is neither complete original nor complete selected image; lock retained'
    fi
}
retire_validate() {
    assert_retirement_job
    [ "$(hash_file "$JOB_DIR/manifest.txt")" = "$EXPORTED_HASH" ] || die 'interrupted manifest differs from archived evidence'
    [ "$(hash_file "$JOB_DIR/backup-inventory.sha256")" = "$(value "$JOB_DIR/manifest.txt" INVENTORY_SHA256)" ] || die 'interrupted inventory manifest changed'
    (cd "$JOB_DIR" && sha256sum -c backup-inventory.sha256) >/dev/null 2>&1 || die 'interrupted backup inventory is incomplete or changed'
    ! grep -qx 'RESULT=success' "$JOB_DIR/journal.txt" || die 'completed job is not an interrupted retirement candidate'
    retire_result=$(awk -F= '$1=="RESULT" {v=$2} END {print v}' "$JOB_DIR/journal.txt")
    case "$retire_result" in partial|blocked|error) ;; *) die 'job has no terminal stopped result; lock retained' ;; esac
    assert_no_active_job
    retired_disk=$(value "$JOB_DIR/manifest.txt" DISK); retired_abl=$(value "$JOB_DIR/manifest.txt" ABL)
    safe_path "$retired_disk" && safe_path "$retired_abl" && [ -b "$retired_disk" ] && [ -b "$retired_abl" ] || die 'interrupted job block identities are invalid'
    retire_verify=$("$SCRIPT_DIRECTORY_HINT/gpttool" verify "$retired_disk" 2>&1) || die 'interrupted disk has no complete valid GPT; lock retained'
    [ "$(kv "$retire_verify" GPT_VALID)" = 1 ] && [ "$(kv "$retire_verify" RESULT)" = success ] && [ "$(kv "$retire_verify" DISK_READ_MODE)" = direct ] || die 'interrupted GPT validation proof is missing'
    export_gpt "$retired_disk" "$JOB_DIR/retire-gpt-readback.bin" "$JOB_DIR/retire-gpt-readback.log" || die 'interrupted GPT direct readback failed'
    retired_gpt_hash=$(hash_file "$JOB_DIR/retire-gpt-readback.bin")
    if [ "$retired_gpt_hash" = "$(value "$JOB_DIR/manifest.txt" GPT_BEFORE_SHA256)" ]; then RETIRED_LAYOUT=before
    elif [ "$retired_gpt_hash" = "$(value "$JOB_DIR/manifest.txt" GPT_PLANNED_SHA256)" ]; then RETIRED_LAYOUT=planned
    else die 'current GPT is neither complete original nor complete planned layout; lock retained'; fi
    retired_abl_capacity=$(file_bytes "$JOB_DIR/abl-current.full.bin")
    read_direct "$retired_abl" 0 "$retired_abl_capacity" "$JOB_DIR/retire-abl-readback.bin" "$JOB_DIR/retire-abl-readback.log" || die 'interrupted ABL direct readback failed'
    retired_abl_hash=$(hash_file "$JOB_DIR/retire-abl-readback.bin")
    if [ "$retired_abl_hash" != "$(value "$JOB_DIR/manifest.txt" ABL_BEFORE_SHA256)" ]; then
        [ "$(value "$JOB_DIR/manifest.txt" ABL_WRITE_TARGET)" != OTHER ] || die 'unselected current-slot ABL changed during opposite-only job; lock retained'
        [ "$RETIRED_LAYOUT" = planned ] && [ -s "$JOB_DIR/abl.img" ] || die 'ABL changed outside a complete planned layout; lock retained'
        retired_image_bytes=$(file_bytes "$JOB_DIR/abl.img")
        [ "$retired_image_bytes" -le "$(file_bytes "$JOB_DIR/abl-current.full.bin")" ] || die 'interrupted ABL image exceeds its original complete backup'
        retired_expected_abl_hash=$({ cat "$JOB_DIR/abl.img"; tail -c "+$((retired_image_bytes + 1))" "$JOB_DIR/abl-current.full.bin"; } | sha256sum | awk '{print $1}')
        [ "$retired_abl_hash" = "$retired_expected_abl_hash" ] || die 'ABL is neither original nor the selected image with its preserved original tail; lock retained'
    fi
    retire_other_abl_validate
    assert_no_active_job
}
if [ "$ACTION" = --execute ] && [ "$PHASE" = retire ]; then
    STAGE=interrupted-job-retirement
    is_hash "$EXPORTED_HASH" && is_hash "$EVIDENCE_HASH" || die 'archived manifest and evidence SHA256 attestations are required'
    GPTTOOL=$SCRIPT_DIRECTORY_HINT/gpttool WORKER=$SCRIPT_DIRECTORY_HINT/partition_worker
    [ -x "$GPTTOOL" ] && [ -x "$WORKER" ] && bf_tool_available tail && bf_tool_available mv || die 'retirement inspection helpers are missing'
    retire_validate
    # A previous retire may have stopped after its claim or RETIRED marker.
    # Validation above authenticates that no other live retire/executor uses
    # this job; repeating only its metadata cleanup is therefore permitted.
    if ! mkdir "$JOB_DIR/retire-claim" 2>/dev/null; then
        [ -d "$JOB_DIR/retire-claim" ] && [ "$(readlink -f "$JOB_DIR/retire-claim")" = "$JOB_DIR/retire-claim" ] || die 'invalid interrupted retirement claim'
    fi
    if [ ! -d "$JOB_DIR/execution-claim" ]; then
        mkdir "$JOB_DIR/execution-claim" 2>/dev/null || die 'an execution claim raced interrupted job retirement'
    fi
    retire_validate
    if ! grep -qx 'RETIRED=1' "$JOB_DIR/journal.txt"; then
        printf '%s\n' 'RETIRED=1' "RETIRED_LAYOUT=$RETIRED_LAYOUT" "EVIDENCE_ARCHIVE_SHA256=$EVIDENCE_HASH" >> "$JOB_DIR/journal.txt" || die 'retirement evidence journal write failed; lock retained'
    fi
    if [ ! -d "$JOB_DIR/retired-lock" ]; then
        [ "$(cat "$LOCK_DIR/job" 2>/dev/null)" = "$JOB_DIR" ] || die 'interrupted lock ownership changed before release'
        # Same-tmpfs rename is atomic. Keep the owner record as evidence rather
        # than deleting it in a second interruptible metadata operation.
        mv "$LOCK_DIR" "$JOB_DIR/retired-lock" || die 'retired lock transfer failed; all evidence retained'
    fi
    printf '%s\n' 'RESULT=retired' "JOB_DIR=$JOB_DIR" "RETIRED_LAYOUT=$RETIRED_LAYOUT" 'BACKUP_RETAINED=1'
    exit 0
fi
# Cancellation is a RAM-only transaction. The same per-job atomic claim used by
# resume prevents cancellation from releasing the lock underneath a write worker.
# It intentionally retains the prepared backup directory for inspection/export.
if [ "$ACTION" = --execute ] && [ "$PHASE" = cancel ]; then
    assert_ram_job
    is_hash "$EXPORTED_HASH" && [ "$(hash_file "$JOB_DIR/manifest.txt")" = "$EXPORTED_HASH" ] || die 'prepared manifest hash differs from cancellation request'
    [ "$(hash_file "$JOB_DIR/backup-inventory.sha256")" = "$(value "$JOB_DIR/manifest.txt" INVENTORY_SHA256)" ] || die 'prepared inventory changed before cancellation'
    (cd "$JOB_DIR" && sha256sum -c backup-inventory.sha256) >/dev/null 2>&1 || die 'prepared backup inventory differs before cancellation'
    grep -qx 'STATE=prepared' "$JOB_DIR/journal.txt" && \
        ! grep -Eq '^STATE=(execution-start|cancelled)$' "$JOB_DIR/journal.txt" || die 'only an unexecuted prepared job can be cancelled'
    mkdir "$JOB_DIR/execution-claim" 2>/dev/null || die 'job has already been claimed for execution or cancellation'
    assert_ram_job
    ! grep -qx 'STATE=execution-start' "$JOB_DIR/journal.txt" || die 'job already started before cancellation claim'
    printf '%s\n' 'STATE=cancelled' >> "$JOB_DIR/journal.txt" || die 'cancellation journal write failed; lock retained'
    rm "$LOCK_DIR/job" && rmdir "$LOCK_DIR" || die 'prepared job cancelled but write lock release failed; inspect RAM backup'
    printf '%s\n' 'RESULT=cancelled' "JOB_DIR=$JOB_DIR" 'BACKUP_RETAINED=1'
    exit 0
fi
for tool in getprop dd od head blockdev sgdisk sort cp chmod date sync df; do
    bf_tool_available "$tool" || die "required utility missing: $tool"
done
if ! bf_system_path dmsetup >/dev/null && ! bf_system_path dmctl >/dev/null && \
    [ ! -x "$BF_TOOLS_DIR/dmprobe" ]; then die 'system DM query tools and bundled dmprobe are missing'; fi
# Keep the verified BusyBox interpreter across the RAM handoff. Android mksh
# can have 32-bit arithmetic even on an arm64 device.
CURRENT_SHELL=$(readlink -f "/proc/$$/exe") || die 'current interpreter binary cannot be resolved'
[ -f "$CURRENT_SHELL" ] && [ -r "$CURRENT_SHELL" ] && [ -x "$CURRENT_SHELL" ] || die 'current interpreter binary cannot be copied'
[ "$(od -An -tu1 -j4 -N1 "$CURRENT_SHELL" | tr -d ' \r\n')" = 2 ] && \
    [ "$(od -An -tu2 -j18 -N2 "$CURRENT_SHELL" | tr -d ' \r\n')" = 183 ] || die 'an arm64 BusyBox interpreter is required'
"$CURRENT_SHELL" --list 2>/dev/null | grep -qx sh || die 'current interpreter is not a usable BusyBox sh'
[ "$("$CURRENT_SHELL" sh -c 'printf "%s" "$((1875091200 * 512))"' 2>/dev/null)" = 960046694400 ] || die 'BusyBox sh does not provide validated 64bit arithmetic'

# Copy and run the engine synchronously in tmpfs. A root caller may detach this
# command itself; this script does not claim that its caller has been detached.
SCRIPT_PATH=$(readlink -f "$0") || die 'script path resolution failed'
SCRIPT_DIR=${SCRIPT_PATH%/*}
safe_path "$SCRIPT_PATH" || die 'script path must be absolute plain ASCII without spaces'
bf_selected_aliases "$SCRIPT_DIR/selected-tools" >/dev/null || die 'selected tool aliases differ from the staged bundled tool paths'
if [ "$ACTION" = --execute ] && [ "$PHASE" = resume ] && [ "${BF_RAM_STAGE:-0}" != 1 ]; then
    assert_ram_job
    is_hash "$EXPORTED_HASH" || die 'saved backup manifest SHA256 is required'
    # A prepared RAM job belongs to the release and payload selection that made it.
    for engine_name in online-create.sh tool-dispatch.sh fsprobe partition_worker gpttool payloads.sha256 tools/busybox tools/sgdisk; do
        if [ "$engine_name" = online-create.sh ]; then current_engine=$SCRIPT_PATH
        else current_engine=$SCRIPT_DIR/$engine_name; fi
        [ -f "$current_engine" ] && [ -r "$current_engine" ] && [ -s "$current_engine" ] || die 'current release engine source is missing or incomplete; prepared backups retained'
        [ -f "$JOB_DIR/$engine_name" ] && [ -r "$JOB_DIR/$engine_name" ] && [ -s "$JOB_DIR/$engine_name" ] || die 'prepared RAM engine is missing or incomplete; backups retained'
        [ "$(hash_file "$current_engine")" = "$(hash_file "$JOB_DIR/$engine_name")" ] || die 'prepared job engine differs from the current release; stop and retain backups'
    done
    if [ -e "$SCRIPT_DIR/payload-source.txt" ] || [ -L "$SCRIPT_DIR/payload-source.txt" ] ||
        [ -e "$JOB_DIR/payload-source.txt" ] || [ -L "$JOB_DIR/payload-source.txt" ]; then
        [ -f "$SCRIPT_DIR/payload-source.txt" ] && [ ! -L "$SCRIPT_DIR/payload-source.txt" ] &&
            [ -f "$JOB_DIR/payload-source.txt" ] && [ ! -L "$JOB_DIR/payload-source.txt" ] &&
            [ "$(hash_file "$SCRIPT_DIR/payload-source.txt")" = "$(hash_file "$JOB_DIR/payload-source.txt")" ] || die 'prepared payload source binding differs from the current selection'
    fi
    [ "$(bf_selected_aliases "$SCRIPT_DIR/selected-tools")" = "$(cat "$JOB_DIR/selected-tools.txt")" ] && \
        [ "$(bf_selected_aliases "$JOB_DIR/selected-tools")" = "$(cat "$JOB_DIR/selected-tools.txt")" ] || die 'prepared job tool selection differs from the current release'
    [ "$(hash_file "$JOB_DIR/manifest.txt")" = "$EXPORTED_HASH" ] || die 'manifest changed before RAM resume'
    [ "$(hash_file "$JOB_DIR/backup-inventory.sha256")" = "$(value "$JOB_DIR/manifest.txt" INVENTORY_SHA256)" ] || die 'inventory changed before RAM resume'
    (cd "$JOB_DIR" && sha256sum -c backup-inventory.sha256) >/dev/null 2>&1 || die 'RAM engine inventory changed before exec'
    BF_RAM_STAGE=1; BF_JOB_DIR=$JOB_DIR; export BF_RAM_STAGE BF_JOB_DIR
    [ -x "$JOB_DIR/interpreter/busybox" ] || die 'verified RAM interpreter is absent or not executable'
    exec "$JOB_DIR/interpreter/busybox" sh "$JOB_DIR/online-create.sh" --execute --resume "$JOB_DIR" --backup-saved "$EXPORTED_HASH"
    die 'RAM resume exec failed'
fi
if [ "${BF_RAM_STAGE:-0}" = 1 ]; then
    assert_ram_job
    [ "$SCRIPT_DIR" = "$JOB_DIR" ] || die 'RAM worker script path mismatch'
    [ "$(bf_selected_aliases "$SCRIPT_DIR/selected-tools")" = "$(cat "$JOB_DIR/selected-tools.txt")" ] || die 'RAM tool selection differs from the prepared selection'
    PATH="$JOB_DIR/selected-tools:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin:/sbin:$JOB_DIR/tools:$PATH"; export PATH
fi
WORKER=$SCRIPT_DIR/partition_worker
FSPROBE=$SCRIPT_DIR/fsprobe
GPTTOOL=$SCRIPT_DIR/gpttool
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
for helper in "$WORKER" "$FSPROBE" "$GPTTOOL"; do [ -f "$helper" ] && [ -x "$helper" ] || die 'native helper missing or not executable'; done
GPT_SELF_TEST=$("$GPTTOOL" --self-test 2>&1) || die 'raw GPT helper self-test failed'
printf '%s\n' "$GPT_SELF_TEST" | grep -qx 'SELF_TEST_RESULT=PASS' && \
    printf '%s\n' "$GPT_SELF_TEST" | grep -qx 'RESULT=success' || die 'raw GPT helper self-test proof is missing'
[ -f "$PAYLOAD_MANIFEST" ] && [ -r "$PAYLOAD_MANIFEST" ] || die 'payload manifest is missing or unreadable'
payload_hashes=$(awk '
    NF != 2 || length($1) != 64 || $1 ~ /[^0-9a-f]/ {bad=1; next}
    $2 == "efisp.img" {pe=$1; pc++; next}
    $2 == "abl.img" {abl=$1; ac++; next}
    {bad=1}
    END {if(bad || pc!=1 || ac!=1) exit 1; print pe,abl}
' "$PAYLOAD_MANIFEST") || die 'payload manifest must contain exactly the two staged image hashes'
set -- $payload_hashes
[ "$#" = 2 ] && is_hash "$1" && is_hash "$2" || die 'payload manifest hashes are invalid'
PE_HASH=$1 ABL_HASH=$2 PAYLOAD_MANIFEST_HASH=$(hash_file "$PAYLOAD_MANIFEST")
for image in "$PE_IMAGE" "$ABL_IMAGE"; do [ -f "$image" ] && [ -r "$image" ] || die 'selected image missing'; done
[ "$(hash_file "$PE_IMAGE")" = "$PE_HASH" ] && [ "$(hash_file "$ABL_IMAGE")" = "$ABL_HASH" ] || die 'image differs from staged payload manifest'
PE_BYTES=$(file_bytes "$PE_IMAGE"); ABL_BYTES=$(file_bytes "$ABL_IMAGE")
is_uint "$PE_BYTES" && is_uint "$ABL_BYTES" && [ "$PE_BYTES" -gt 0 ] && [ "$ABL_BYTES" -gt 0 ] || die 'invalid image length'

# Accept an available running-slot source, require all available sources to
# agree, then repeat immediately before ABL. Some systems expose only one.
accept_slot() {
    [ -n "$1" ] || return 0
    case "$1" in _a|a) candidate=a ;; _b|b) candidate=b ;; *) die "invalid slot from $2" ;; esac
    [ "$SLOT" = unknown ] || [ "$SLOT" = "$candidate" ] || die 'running slot sources disagree'
    SLOT=$candidate; SLOT_SOURCES=$((SLOT_SOURCES + 1))
}
check_slot() {
    SLOT_SOURCES=0
    accept_slot "$(getprop ro.boot.slot_suffix)" property
    for candidate_value in $(awk '{for(i=1;i<=NF;i++) if($i ~ /^androidboot\.slot_suffix=/) {sub(/^androidboot\.slot_suffix=/,"",$i); print $i}}' /proc/cmdline 2>/dev/null); do accept_slot "$candidate_value" cmdline; done
    if [ -r /proc/bootconfig ]; then
        for candidate_value in $(awk -F= '$1 ~ /^[ \t]*androidboot\.slot_suffix[ \t]*$/ {gsub(/[ \t";]/,"",$2); print $2}' /proc/bootconfig); do accept_slot "$candidate_value" bootconfig; done
    fi
    if command -v bootctl >/dev/null 2>&1; then
        boot_index=$(bootctl get-current-slot 2>/dev/null || :)
        case "$boot_index" in
            0) accept_slot _a bootctl ;;
            1) accept_slot _b bootctl ;;
            '') ;;
            *) die 'bootctl returned invalid slot index' ;;
        esac
    fi
    [ "$SLOT_SOURCES" -ge 1 ] && [ "$SLOT" != unknown ] || die 'running slot is unavailable'
}
check_slot
USERDATA=$(readlink -f /dev/block/by-name/userdata 2>/dev/null) || die 'userdata link is missing'
ABL=$(readlink -f "/dev/block/by-name/abl_$SLOT" 2>/dev/null) || die 'current-slot ABL link is missing'
inspect_partition() {
    BP=$(readlink -f "$1") || return 1
    [ -b "$BP" ] || return 1
    BN=${BP##*/}
    [ -r "/sys/class/block/$BN/partition" ] && [ ! -d "/sys/class/block/$BN/dm" ] || return 1
    BR=$(readlink -f "/sys/class/block/$BN") || return 1
    PARENT=${BR%/*}; PARENT=${PARENT##*/}
    INDEX=$(cat "/sys/class/block/$BN/partition")
    START512=$(cat "/sys/class/block/$BN/start")
    SIZE512=$(cat "/sys/class/block/$BN/size")
    MM=$(cat "/sys/class/block/$BN/dev")
    is_uint "$INDEX" && is_uint "$START512" && is_uint "$SIZE512" && \
        [ "$INDEX" -gt 0 ] && [ "$START512" -le 18014398509481983 ] && \
        [ "$SIZE512" -gt 0 ] && [ "$SIZE512" -le 18014398509481983 ]
}
unique_name() {
    name_count=0; found_name=
    for uevent in /sys/class/block/*/uevent; do
        [ -r "$uevent" ] || continue
        if grep -qx "PARTNAME=$1" "$uevent"; then name_count=$((name_count + 1)); found_name=${uevent%/uevent}; found_name=${found_name##*/}; fi
    done
    [ "$name_count" = 1 ] && [ "$found_name" = "$2" ]
}
inspect_partition "$USERDATA" || die 'userdata is not a physical partition'
UD_NAME=$BN UD_PARENT=$PARENT UD_INDEX=$INDEX UD_START512=$START512 UD_SIZE512=$SIZE512 UD_MM=$MM
unique_name userdata "$UD_NAME" || die 'userdata name is ambiguous'
UD_BYTES=$((UD_SIZE512 * 512)); DISK=/dev/block/$UD_PARENT
[ -b "$DISK" ] && [ "$(blockdev --getsize64 "$USERDATA")" = "$UD_BYTES" ] || die 'userdata physical geometry disagrees with sysfs'
inspect_partition "$ABL" || die 'current ABL is not a physical partition'
ABL_NAME=$BN ABL_PARENT=$PARENT ABL_INDEX=$INDEX ABL_START512=$START512 ABL_SIZE512=$SIZE512 ABL_MM=$MM
ABL_CAPACITY=$((ABL_SIZE512 * 512))
verify_payload_source_identity "$ABL_CAPACITY"
unique_name "abl_$SLOT" "$ABL_NAME" || die 'current ABL name is ambiguous'
{ [ "$ABL_WRITE_TARGET" = OTHER ] || [ "$ABL_BYTES" -le "$ABL_CAPACITY" ]; } && [ "$ABL" != "$USERDATA" ] || die 'ABL image or target geometry is invalid'
OTHER_SLOT=a; [ "$SLOT" != a ] || OTHER_SLOT=b
[ "$(readlink -f "/dev/block/by-name/abl_$OTHER_SLOT" 2>/dev/null)" != "$ABL" ] || die 'both ABL slots resolve to one device'
LOGICAL_SECTOR=$(blockdev --getss "$DISK") || die 'logical sector query failed'
case "$LOGICAL_SECTOR" in 512|4096) ;; *) die 'unsupported physical disk logical sector size' ;; esac
DISK_BYTES=$(blockdev --getsize64 "$DISK") || die 'disk capacity query failed'
is_uint "$DISK_BYTES" && [ "$DISK_BYTES" -le 9223372036854775807 ] && \
    [ "$DISK_BYTES" -gt "$UD_BYTES" ] && [ $((DISK_BYTES % LOGICAL_SECTOR)) = 0 ] || die 'disk byte size is invalid'
[ "$UD_START512" -le "$((DISK_BYTES / 512))" ] && \
    [ "$UD_SIZE512" -le "$((DISK_BYTES / 512 - UD_START512))" ] || die 'userdata extends outside its physical parent'
[ "$(cat "/sys/class/block/$UD_PARENT/size")" = "$((DISK_BYTES / 512))" ] || die 'disk sysfs and block geometry disagree'
bf_readonly_state "$DISK" >/dev/null && { [ "$ABL_WRITE_TARGET" = OTHER ] || bf_readonly_state "$ABL" >/dev/null; } || die 'a write target read-only state is unknown'
[ $((UD_START512 * 512 % LOGICAL_SECTOR)) = 0 ] && [ $((UD_BYTES % LOGICAL_SECTOR)) = 0 ] || die 'userdata geometry is not logical-sector aligned'
UD_START=$((UD_START512 * 512 / LOGICAL_SECTOR)); UD_END=$((UD_START + UD_BYTES / LOGICAL_SECTOR - 1))
UD_KERNEL_END=$UD_END

verify_gpt() {
    verify_text=$("$GPTTOOL" verify "$1" 2>&1) || return 1
    printf '%s\n' "$verify_text" | awk '
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

verify_running_slot_for_write() { check_slot; }
resolve_abl_link() { readlink -f "/dev/block/by-name/abl_$1"; }
inspect_abl_target() {
    inspect_partition "$1" || return 1
    TA_PATH=$BP TA_NAME=$BN TA_PARENT=$PARENT TA_INDEX=$INDEX
    TA_START512=$START512 TA_SIZE512=$SIZE512 TA_MM=$MM
}
unique_abl_target() { unique_name "$1" "$2"; }
abl_target_idle() {
    for holder in /sys/class/block/"$1"/holders/*; do [ ! -e "$holder" ] || return 1; done
    ! awk -v mm="$2" '$3==mm {found=1} END {exit !found}' /proc/1/mountinfo /proc/self/mountinfo || return 1
    for namespace in /proc/[0-9]*/mountinfo; do
        [ -r "$namespace" ] || continue
        ! awk -v mm="$2" '$3==mm {found=1} END {exit !found}' "$namespace" 2>/dev/null || return 1
    done
    if [ -r /proc/swaps ]; then
        for swap_path in $(awk 'NR>1 {print $1}' /proc/swaps); do
            [ "$(readlink -f "$swap_path" 2>/dev/null || :)" != "/dev/block/$1" ] || return 1
        done
    fi
}
abl_targets_event() { journal "$1"; }
abl_targets_mark_modified() { ABL_MAY_BE_MODIFIED=1; journal 'ABL_MAY_BE_MODIFIED=1'; }

verify_gpt "$DISK" || die 'userdata disk GPT primary and backup are not clean'
[ "$ABL_PARENT" = "$UD_PARENT" ] || verify_gpt "/dev/block/$ABL_PARENT" || die 'ABL disk GPT is not clean'
initialize_other_abl
GPT_INSPECT=$(inspect_gpt "$DISK" "$UD_INDEX") || die 'direct GPT geometry inspection failed'
[ "$(kv "$GPT_INSPECT" GPT_VALID)" = 1 ] && [ "$(kv "$GPT_INSPECT" RESULT)" = success ] && \
    [ "$(kv "$GPT_INSPECT" LOGICAL_SECTOR)" = "$LOGICAL_SECTOR" ] && \
    [ "$(kv "$GPT_INSPECT" DISK_BYTES)" = "$DISK_BYTES" ] && \
    [ "$(kv "$GPT_INSPECT" UD_START)" = "$UD_START" ] || die 'direct GPT identity differs from userdata physical origin'
GPT_UD_END=$(kv "$GPT_INSPECT" UD_END); UD_GPT_BYTES=$(kv "$GPT_INSPECT" UD_BYTES)
EFI_COUNT=$(kv "$GPT_INSPECT" EFI_COUNT)
is_uint "$GPT_UD_END" && is_uint "$UD_GPT_BYTES" && [ "$GPT_UD_END" -le "$UD_KERNEL_END" ] && \
    [ "$UD_GPT_BYTES" = "$(((GPT_UD_END - UD_START + 1) * LOGICAL_SECTOR))" ] || die 'GPT userdata does not fit its kernel node'
UD_END=$GPT_UD_END
case "$EFI_COUNT" in
    0)
        [ "$UD_END" = "$UD_KERNEL_END" ] || die 'userdata kernel range differs from GPT without an efisp entry'
        [ ! -e /dev/block/by-name/efisp ] || die 'efisp link exists without a direct GPT entry'
        PLAN_MODE=create ;;
    1)
        PLAN_MODE=reuse EFISP=present EFI_PROFILE=existing-preserved
        EFI_INDEX=$(kv "$GPT_INSPECT" EFI_INDEX); EFI_START=$(kv "$GPT_INSPECT" EFI_START)
        EFI_END=$(kv "$GPT_INSPECT" EFI_END); EFI_BYTES=$(kv "$GPT_INSPECT" EFI_BYTES)
        EFI_GUID=$(kv "$GPT_INSPECT" EFI_GUID); EFI_ATTR=$(kv "$GPT_INSPECT" EFI_ATTR)
        EFI_ATTR_BACKUP=$(kv "$GPT_INSPECT" EFI_ATTR_BACKUP)
        [ "$(kv "$GPT_INSPECT" EFI_TYPE)" = "$EFI_TYPE" ] && [ "$(kv "$GPT_INSPECT" EFI_LABEL)" = "$EFI_LABEL" ] || die 'existing efisp has an unexpected GPT type or name'
        is_uint "$EFI_INDEX" && is_uint "$EFI_START" && is_uint "$EFI_END" && is_uint "$EFI_BYTES" && \
            [ "$EFI_BYTES" -gt 0 ] && [ "$EFI_BYTES" = "$(((EFI_END - EFI_START + 1) * LOGICAL_SECTOR))" ] && \
            is_guid "$EFI_GUID" && printf '%s\n' "$EFI_ATTR" | grep -Eq '^[0-9A-Fa-f]{16}$' || die 'existing efisp direct GPT geometry is invalid'
        EFI_OFFSET=$((EFI_START * LOGICAL_SECTOR)) ;;
    *) die 'direct GPT has an ambiguous efisp entry count' ;;
esac
# Strict native verification proves both tables and every entry non-overlapping.
# sgdisk is diagnostic only: its buffered view can lag a direct GPT commit.
GPT_TABLE=$GPT_INSPECT
DISK_GUID=$(kv "$GPT_INSPECT" DISK_GUID); GPT_CAPACITY=$(kv "$GPT_INSPECT" GPT_CAPACITY)
GPT_HEADER_GEOMETRY=$(gpt_header "$GPT_INSPECT")
PARTITIONS=$(kv "$GPT_INSPECT" PARTITION_INDICES | tr ' ' '\n')
UD_INFO=$(userdata_info "$GPT_INSPECT")
UD_TYPE=$(kv "$GPT_INSPECT" UD_TYPE); UD_GUID=$(kv "$GPT_INSPECT" UD_GUID)
UD_ATTR=$(kv "$GPT_INSPECT" UD_ATTR); UD_ATTR_BACKUP=$(kv "$GPT_INSPECT" UD_ATTR_BACKUP)
UD_LABEL=$(kv "$GPT_INSPECT" UD_LABEL)
is_guid "$DISK_GUID" && is_uint "$GPT_CAPACITY" && is_guid "$UD_TYPE" && is_guid "$UD_GUID" && \
    [ "$UD_LABEL" = userdata ] && printf '%s\n' "$UD_ATTR" | grep -Eq '^[0-9A-Fa-f]{16}$' || die 'direct GPT header or userdata identifiers are invalid'
OTHER_ENTRIES='ALL_OTHER_RAW_ENTRIES_BOUND_BY_EXACT_SNAPSHOT'
if [ "$PLAN_MODE" = create ]; then
    EFI_INDEX=1
    while [ "$EFI_INDEX" -le "$GPT_CAPACITY" ]; do
        printf '%s\n' "$PARTITIONS" | grep -qx "$EFI_INDEX" || break
        EFI_INDEX=$((EFI_INDEX + 1))
    done
    [ "$EFI_INDEX" -le "$GPT_CAPACITY" ] || die 'no free GPT entry exists'
fi

# The ioctl must act on the same mounted F2FS seen by init. Inspect DM ancestry
# using sysfs; table output is reduced immediately to geometry/target, not keys.
data_mount() {
    awk '$5=="/data" {for(i=6;i<=NF;i++) if($i=="-") {n++; mm=$3; root=$4; opts=$6; fs=$(i+1); source=$(i+2)}} END {if(n==1) print mm,root,opts,fs,source}' "$1"
}
INIT_DATA=$(data_mount /proc/1/mountinfo); SELF_DATA=$(data_mount /proc/self/mountinfo)
[ -n "$INIT_DATA" ] && [ "$INIT_DATA" = "$SELF_DATA" ] || die 'init and worker /data mount namespaces differ'
set -- $INIT_DATA
[ "$#" = 5 ] && [ "$2" = / ] && [ "$4" = f2fs ] || die 'init /data must be a whole mounted F2FS'
case ",$3," in *,rw,*) ;; *) die '/data is not read-write' ;; esac
DATA_MM=$1; DATA_SOURCE=$(readlink -f "$5") || die 'mounted data device cannot be resolved'
DATA_NAME=${DATA_SOURCE##*/}; FILESYSTEM=f2fs
[ -b "$DATA_SOURCE" ] && [ "$(cat "/sys/class/block/$DATA_NAME/dev")" = "$DATA_MM" ] || die 'mount source and sysfs identity differ'
reduce_dm_table() {
    local dm_node=$1 dm_name dm_command reduced
    dm_name=$(cat "/sys/class/block/$dm_node/dm/name") || return 1
    case "$dm_name" in ''|*[!A-Za-z0-9._-]*) return 1 ;; esac
    dm_command=$(bf_system_path dmsetup) || dm_command=
    if [ -n "$dm_command" ]; then
        # Reduce immediately inside awk; raw table/key fields are never logged.
        reduced=$({ "$dm_command" table "/dev/block/$dm_node" 2>/dev/null; table_rc=$?; printf "\nBF_COMMAND_RC=%s\n" "$table_rc"; } | awk '
            /^BF_COMMAND_RC=/ {split($0,r,"="); command_rc=r[2]; rc_seen++; next}
            NF>=3 && $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ {
                t=$3; offset="na";
                if(t=="linear") offset=$5;
                if(t=="crypt") offset=$8;
                if(t=="default-key") {
                    if($6 ~ /^\/dev\// || $6 ~ /^[0-9]+:[0-9]+$/) offset=$7;
                    else offset=$8;
                }
                if(t!="ramcache" && offset !~ /^[0-9]+$/) invalid=1;
                result=$1" "$2" "t" "offset; n++
            } END {if(n!=1 || rc_seen!=1 || command_rc!="0" || invalid) exit 1; print result}')
        if [ "$?" = 0 ]; then printf '%s\n' "$reduced"; return 0; fi
        bf_fallback_log dmsetup system-query-or-format-failed
    fi
    dm_command=$(bf_system_path dmctl) || dm_command=
    if [ -n "$dm_command" ]; then
        reduced=$({ "$dm_command" table "$dm_name" 2>/dev/null; table_rc=$?; printf "\nBF_COMMAND_RC=%s\n" "$table_rc"; } | awk '
            /^BF_COMMAND_RC=/ {split($0,r,"="); command_rc=r[2]; rc_seen++; next}
            $1 ~ /^[0-9]+-[0-9]+:$/ {
                split($1,g,/[-:]/); t=$2; sub(/,$/,"",t); offset="na";
                if(t=="linear") offset=$4;
                if(t=="crypt") offset=$7;
                if(t=="default-key") {
                    if($5 ~ /^\/dev\// || $5 ~ /^[0-9]+:[0-9]+$/) offset=$6;
                    else offset=$7;
                }
                if(t!="ramcache" && offset !~ /^[0-9]+$/) invalid=1;
                result=g[1]" "g[2]" "t" "offset; n++
            } END {if(n!=1 || rc_seen!=1 || command_rc!="0" || invalid) exit 1; print result}')
        if [ "$?" = 0 ]; then printf '%s\n' "$reduced"; return 0; fi
        bf_fallback_log dmctl system-query-or-format-failed
    fi
    [ -x "$BF_TOOLS_DIR/dmprobe" ] || return 1
    bf_fallback_log dm-table system-commands-unavailable-or-failed
    "$BF_TOOLS_DIR/dmprobe" table "$dm_name"
}
walk_chain() (
    node=$1; depth=$2; previous=$3
    [ "$depth" -lt 16 ] || exit 1
    case ":$previous:" in *":$node:"*) exit 1 ;; esac
    [ -r "/sys/class/block/$node/size" ] || exit 1
    if [ "$node" = "$UD_NAME" ]; then printf '%s\n' "ORIGIN=$node"; exit 0; fi
    case "$node" in
        ramcache|ram[0-9]*|zram[0-9]*)
            virtual_path=$(readlink -f "/sys/class/block/$node") || exit 1
            [ "$virtual_path" = "/sys/devices/virtual/block/$node" ] && \
                [ ! -r "/sys/class/block/$node/partition" ] || exit 1
            printf '%s\n' "AUX=$node"; exit 0 ;;
    esac
    [ -d "/sys/class/block/$node/dm" ] || exit 1
    [ "$(cat "/sys/class/block/$node/size")" = "$UD_SIZE512" ] && \
        [ "$(blockdev --getsize64 "/dev/block/$node")" = "$UD_BYTES" ] || exit 1
    reduced=$(reduce_dm_table "$node") || exit 1
    set -- $reduced
    [ "$#" = 4 ] && [ "$1" = 0 ] && [ "$2" = "$UD_SIZE512" ] || exit 1
    case "$3" in default-key|crypt|linear|ramcache) target=$3 ;; *) exit 1 ;; esac
    [ "$target" = ramcache ] || [ "$4" = 0 ] || exit 1
    printf '%s\n' "MAP=$node:$target"
    count=0
    for slave in /sys/class/block/"$node"/slaves/*; do
        [ -e "$slave" ] || continue
        count=$((count + 1))
        walk_chain "${slave##*/}" "$((depth + 1))" "$previous:$node" || exit 1
    done
    [ "$count" -gt 0 ] || exit 1
)
DATA_CHAIN=$(walk_chain "$DATA_NAME" 0 '') || die 'data DM chain contains an unknown target, changed size, cycle, or other physical origin'
[ "$(printf '%s\n' "$DATA_CHAIN" | grep -c "^ORIGIN=$UD_NAME$")" = 1 ] || die 'data chain must reach userdata exactly once'
if printf '%s\n' "$DATA_CHAIN" | grep -Eq '^MAP=[^:]+:ramcache$'; then
    RAMCACHE_UNVERIFIED=1
fi
# Identifying this mapping does not prove cache/discard coherence after shrink.
# Version 1.2 records the limitation rather than applying a device-profile gate.
printf '%s\n' "CACHE_COHERENCE_UNVERIFIED=$RAMCACHE_UNVERIFIED"
[ "$(blockdev --getsize64 "$DATA_SOURCE")" = "$UD_BYTES" ] || die 'mapped and raw userdata byte lengths differ'
FS_PROOF=$("$WORKER" inspect-f2fs /data "$DATA_SOURCE" 2>&1) || die 'direct F2FS and checkpoint inspection failed'
[ "$(printf '%s\n' "$FS_PROOF" | sed -n 's/^FS_TYPE=//p')" = f2fs ] && \
    [ "$(printf '%s\n' "$FS_PROOF" | sed -n 's/^FS_MATCH=//p')" = 1 ] && \
    [ "$(printf '%s\n' "$FS_PROOF" | sed -n 's/^DEVICE_BYTES=//p')" = "$UD_BYTES" ] && \
    [ "$(kv "$FS_PROOF" RESULT)" = success ] || die 'F2FS geometry proof differs from mapped userdata'
FS_BYTES=$(kv "$FS_PROOF" FS_BYTES); FS_READ_MODE=$(kv "$FS_PROOF" FS_READ_MODE)
is_uint "$FS_BYTES" && [ "$FS_BYTES" -gt 0 ] && [ "$FS_BYTES" -le "$UD_GPT_BYTES" ] && \
    [ $((FS_BYTES % LOGICAL_SECTOR)) = 0 ] && [ "$FS_READ_MODE" = direct ] || die 'direct filesystem size does not fit GPT userdata'
TAIL_FREE_BYTES=$((UD_GPT_BYTES - FS_BYTES))
if [ "$PLAN_MODE" = reuse ]; then
    SKIP_F2FS_SHRINK=1 NEW_UD_END=$UD_END NEW_UD_BYTES=$UD_GPT_BYTES MAX_FS_BYTES=$UD_GPT_BYTES
elif [ "$TAIL_FREE_BYTES" -gt 62914560 ]; then
    SKIP_F2FS_SHRINK=1 NEW_UD_BYTES=$FS_BYTES MAX_FS_BYTES=$FS_BYTES
else
    [ "$UD_GPT_BYTES" -gt "$((DEFAULT_EFI_BYTES + 1048576))" ] || die 'userdata is too small for the default filesystem shrink'
    MAX_FS_BYTES=$((UD_GPT_BYTES - DEFAULT_EFI_BYTES - 1048576))
    FS_PLAN=$("$WORKER" plan-f2fs /data "$DATA_SOURCE" "$MAX_FS_BYTES" 2>&1) || die 'read-only F2FS shrink planning failed'
    [ "$(kv "$FS_PLAN" RESULT)" = planned ] && [ "$(kv "$FS_PLAN" FS_READ_MODE)" = direct ] || die 'F2FS shrink plan proof is missing'
    NEW_UD_BYTES=$(kv "$FS_PLAN" FS_EXPECTED_BYTES)
    is_uint "$NEW_UD_BYTES" && [ "$NEW_UD_BYTES" -gt 0 ] && [ "$NEW_UD_BYTES" -lt "$FS_BYTES" ] && \
        [ $((NEW_UD_BYTES % LOGICAL_SECTOR)) = 0 ] || die 'planned filesystem byte size is invalid'
fi
if [ "$PLAN_MODE" = create ]; then
    NEW_UD_END=$((UD_START + NEW_UD_BYTES / LOGICAL_SECTOR - 1))
    EFI_START=$((NEW_UD_END + 1)); EFI_END=$UD_END
    EFI_BYTES=$((UD_GPT_BYTES - NEW_UD_BYTES)); EFI_OFFSET=$((EFI_START * LOGICAL_SECTOR))
    [ "$EFI_BYTES" -gt 0 ] && [ $(((EFI_END - EFI_START + 1) * LOGICAL_SECTOR)) = "$EFI_BYTES" ] || die 'dynamic efisp tail geometry is invalid'
fi
[ "$PE_BYTES" -le "$EFI_BYTES" ] || die 'selected efisp image exceeds actual available capacity; filesystem is not shrunk again'
printf '%s\n' "TAIL_FREE_BYTES=$TAIL_FREE_BYTES" "SKIP_F2FS_SHRINK=$SKIP_F2FS_SHRINK" "FS_READ_MODE=$FS_READ_MODE" "EFI_BYTES=$EFI_BYTES" "PLAN_MODE=$PLAN_MODE"

if [ "$ACTION" = --execute ] && [ "$PHASE" = resume ]; then
    assert_ram_job
    is_hash "$EXPORTED_HASH" && [ "$(hash_file "$JOB_DIR/manifest.txt")" = "$EXPORTED_HASH" ] || die 'saved backup manifest hash differs from prepared backup'
    EFI_GUID=$(value "$JOB_DIR/plan.txt" EFI_GUID)
elif [ "$PLAN_MODE" = create ]; then
    EFI_GUID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null | tr 'a-f' 'A-F')
fi
is_guid "$EFI_GUID" || die 'new efisp GUID could not be precomputed'
plan_text() {
    printf '%s\n' "DISK=$DISK" "DISK_GUID=$DISK_GUID" "LOGICAL_SECTOR=$LOGICAL_SECTOR" \
        "DISK_BYTES=$DISK_BYTES" "UD_INDEX=$UD_INDEX" "UD_START=$UD_START" "UD_END=$UD_END" \
        "UD_GUID=$UD_GUID" "UD_TYPE=$UD_TYPE" "UD_ATTR=$UD_ATTR" "UD_ATTR_BACKUP=$UD_ATTR_BACKUP" "UD_LABEL=$UD_LABEL" \
        "NEW_UD_END=$NEW_UD_END" "NEW_UD_BYTES=$NEW_UD_BYTES" "EFI_INDEX=$EFI_INDEX" \
        "EFI_START=$EFI_START" "EFI_END=$EFI_END" "EFI_PROFILE=$EFI_PROFILE" "EFI_LABEL=$EFI_LABEL" \
        "EFI_TYPE=$EFI_TYPE" "EFI_ATTR=$EFI_ATTR" "EFI_ATTR_BACKUP=$EFI_ATTR_BACKUP" "EFI_GUID=$EFI_GUID" "MAX_FS_BYTES=$MAX_FS_BYTES" \
        "EFI_BYTES=$EFI_BYTES" "PLAN_MODE=$PLAN_MODE" "TAIL_FREE_BYTES=$TAIL_FREE_BYTES" "SKIP_F2FS_SHRINK=$SKIP_F2FS_SHRINK" "FS_READ_MODE=$FS_READ_MODE" "FS_BYTES=$FS_BYTES" \
        "SLOT=$SLOT" "ABL=$ABL" "ABL_MM=$ABL_MM" "ABL_CAPACITY=$ABL_CAPACITY" \
        "PE_SHA256=$PE_HASH" "ABL_SHA256=$ABL_HASH" "PAYLOAD_MANIFEST_SHA256=$PAYLOAD_MANIFEST_HASH" \
        "PAYLOAD_SOURCE_SHA256=$SOURCE_BINDING_HASH" \
        "DATA_SOURCE=$DATA_SOURCE" "DATA_MM=$DATA_MM" "CACHE_COHERENCE_UNVERIFIED=$RAMCACHE_UNVERIFIED"
    other_abl_plan_text
}
if [ "$ACTION" = --check ]; then
    report 1 ''
    plan_text
    printf '%s\n' 'BACKUP_READY=0' 'NEXT_STEP=prepare RAM backup and save before execution' \
        "REQUIRES_RECOVERY_FORMAT=$(completion_requires_recovery)" "OPERATION=$(completion_operation)"
    exit 0
fi

if [ "$PHASE" = prepare ] && [ "${BF_RAM_STAGE:-0}" != 1 ]; then
    [ "$(mount_type /dev)" = tmpfs ] || die '/dev must be tmpfs for independent execution and backups'
    FREE_KB=$(df -Pk /dev | awk 'NR>1 && $4 ~ /^[0-9]+$/ {v=$4} END {print v}')
    EFI_BACKUP_BYTES=0; [ "$PLAN_MODE" != reuse ] || EFI_BACKUP_BYTES=$EFI_BYTES
    is_uint "$FREE_KB" && [ $((FREE_KB * 1024)) -gt "$((ABL_CAPACITY * 2 + OTHER_ABL_CAPACITY * 2 + EFI_BACKUP_BYTES * 2 + PE_BYTES * 3 + ABL_BYTES * 2 + 33554432))" ] || die 'RAM filesystem lacks space for complete backups, payloads, and proofs'
    JOB_DIR=/dev/bootforge-$(date +%s)-$$
    mkdir "$LOCK_DIR" 2>/dev/null || die 'another prepared or running write job owns the lock'
    printf '%s\n' "$JOB_DIR" > "$LOCK_DIR/job" || die 'write lock ownership record failed'
    mkdir "$JOB_DIR" && chmod 700 "$JOB_DIR" || die 'RAM job directory creation failed'
    for name in online-create.sh tool-dispatch.sh partition_worker fsprobe gpttool payloads.sha256 efisp.img abl.img; do
        cp "$SCRIPT_DIR/$name" "$JOB_DIR/$name" || die 'RAM engine copy failed'
        [ "$(hash_file "$SCRIPT_DIR/$name")" = "$(hash_file "$JOB_DIR/$name")" ] || die 'RAM engine copy hash mismatch'
    done
    verify_payload_source_file
    if [ "$SOURCE_BINDING_HASH" != not-present ]; then
        cp "$SOURCE_BINDING_FILE" "$JOB_DIR/payload-source.txt" || die 'RAM payload source binding copy failed'
        [ "$(hash_file "$JOB_DIR/payload-source.txt")" = "$SOURCE_BINDING_HASH" ] || die 'RAM payload source binding copy hash mismatch'
    fi
    [ -d "$SCRIPT_DIR/tools" ] && [ -x "$SCRIPT_DIR/tools/busybox" ] && \
        [ -x "$SCRIPT_DIR/tools/sgdisk" ] || die 'staged BusyBox and partition tools are missing'
    for tool_source in "$SCRIPT_DIR"/tools/*; do
        [ -f "$tool_source" ] && [ -r "$tool_source" ] || die 'staged tools must be readable files in one directory'
        if [ -L "$tool_source" ]; then
            [ "$(readlink "$tool_source")" = busybox ] || die 'staged applet links must point to the local BusyBox'
        fi
    done
    cp -R "$SCRIPT_DIR/tools" "$JOB_DIR/tools" || die 'tool directory copy to RAM failed'
    for tool_source in "$SCRIPT_DIR"/tools/*; do
        tool_name=${tool_source##*/}
        [ "$(hash_file "$tool_source")" = "$(hash_file "$JOB_DIR/tools/$tool_name")" ] || die 'RAM tool copy hash mismatch'
    done
    bf_selected_aliases "$SCRIPT_DIR/selected-tools" > "$JOB_DIR/selected-tools.txt" || die 'selected tool snapshot failed'
    if [ -d "$SCRIPT_DIR/selected-tools" ]; then
        cp -R "$SCRIPT_DIR/selected-tools" "$JOB_DIR/selected-tools" || die 'selected tool directory copy to RAM failed'
    else mkdir "$JOB_DIR/selected-tools" || die 'empty selected tool directory creation failed'; fi
    [ "$(bf_selected_aliases "$JOB_DIR/selected-tools")" = "$(cat "$JOB_DIR/selected-tools.txt")" ] || die 'RAM selected tool aliases differ from the staged selection'
    chmod 700 "$JOB_DIR/tools/busybox" "$JOB_DIR/tools/sgdisk" "$JOB_DIR/partition_worker" "$JOB_DIR/fsprobe" "$JOB_DIR/gpttool" || die 'RAM helper permissions failed'
    # The validated system/root BusyBox can differ from the bundled fallback.
    # Retain a separate executable with the exact multi-call basename busybox.
    mkdir "$JOB_DIR/interpreter" && cp "$CURRENT_SHELL" "$JOB_DIR/interpreter/busybox" && \
        chmod 700 "$JOB_DIR/interpreter/busybox" || die 'validated interpreter copy to RAM failed'
    [ "$(hash_file "$CURRENT_SHELL")" = "$(hash_file "$JOB_DIR/interpreter/busybox")" ] || die 'RAM interpreter copy hash mismatch'
    BF_RAM_STAGE=1; BF_JOB_DIR=$JOB_DIR; export BF_RAM_STAGE BF_JOB_DIR
    exec "$JOB_DIR/interpreter/busybox" sh "$JOB_DIR/online-create.sh" --execute --prepare
    die 'RAM prepare exec failed'
fi
assert_ram_job
if [ "$PHASE" = prepare ]; then
    [ ! -e "$JOB_DIR/manifest.txt" ] || die 'RAM job was already prepared'
    STAGE=backup
    journal 'STATE=preparing-backup'
    if [ "$PLAN_MODE" = create ]; then
        "$GPTTOOL" plan "$DISK" "$UD_INDEX" "$NEW_UD_END" "$EFI_INDEX" "$EFI_GUID" \
            "$JOB_DIR/gpt-before.bin" "$JOB_DIR/gpt-planned.bin" > "$JOB_DIR/gpt-plan.log" 2>&1 || die 'raw GPT snapshot and plan creation failed'
    else
        export_gpt "$DISK" "$JOB_DIR/gpt-before.bin" "$JOB_DIR/gpt-plan.log" || die 'existing-layout raw GPT backup failed'
        cp "$JOB_DIR/gpt-before.bin" "$JOB_DIR/gpt-planned.bin" || die 'existing-layout unchanged GPT plan copy failed'
        printf '%s\n' 'PLAN_MODE=reuse' 'RESULT=success' >> "$JOB_DIR/gpt-plan.log" || die 'existing-layout plan proof write failed'
    fi
    grep -qx 'DISK_READ_MODE=direct' "$JOB_DIR/gpt-plan.log" && grep -qx 'RESULT=success' "$JOB_DIR/gpt-plan.log" && \
        [ -s "$JOB_DIR/gpt-before.bin" ] && [ -s "$JOB_DIR/gpt-planned.bin" ] || die 'raw GPT snapshot or planned proof is missing'
    if [ "$ABL_PARENT" != "$UD_PARENT" ]; then
        export_gpt "/dev/block/$ABL_PARENT" "$JOB_DIR/abl-disk-gpt-before.bin" "$JOB_DIR/abl-gpt-backup.log" || die 'ABL disk raw GPT backup failed'
        [ -s "$JOB_DIR/abl-disk-gpt-before.bin" ] || die 'ABL disk raw GPT backup is empty'
    fi
    read_direct "$ABL" 0 "$ABL_CAPACITY" "$JOB_DIR/abl-current.full.bin" "$JOB_DIR/abl-backup.log" || die 'complete current-slot ABL backup failed'
    [ "$(file_bytes "$JOB_DIR/abl-current.full.bin")" = "$ABL_CAPACITY" ] || die 'ABL backup length mismatch'
    ABL_BEFORE_HASH=$(hash_file "$JOB_DIR/abl-current.full.bin")
    verify_payload_source_abl "$ABL_BEFORE_HASH"
    read_direct "$ABL" 0 "$ABL_CAPACITY" "$JOB_DIR/abl-backup-readback.bin" "$JOB_DIR/abl-backup-readback.log" && [ "$ABL_BEFORE_HASH" = "$(hash_file "$JOB_DIR/abl-backup-readback.bin")" ] || die 'ABL backup readback hash mismatch'
    backup_other_abl "$JOB_DIR"
    if [ "$PLAN_MODE" = reuse ]; then
        read_direct "$DISK" "$EFI_OFFSET" "$EFI_BYTES" "$JOB_DIR/efisp-before.full.bin" "$JOB_DIR/efisp-backup.log" || die 'existing efisp complete physical range backup failed'
        [ "$(file_bytes "$JOB_DIR/efisp-before.full.bin")" = "$EFI_BYTES" ] || die 'existing efisp backup length mismatch'
        EFI_BEFORE_HASH=$(hash_file "$JOB_DIR/efisp-before.full.bin")
    else EFI_BEFORE_HASH=not-applicable; fi
    printf '%s\n' "$GPT_TABLE" > "$JOB_DIR/table-before.txt" || die 'GPT table snapshot failed'
    printf '%s\n' "$OTHER_ENTRIES" > "$JOB_DIR/other-entries-before.txt" || die 'other entry snapshot failed'
    printf '%s\n' "$UD_INFO" > "$JOB_DIR/userdata-entry-before.txt" || die 'userdata entry snapshot failed'
    printf '%s\n' "$DATA_CHAIN" > "$JOB_DIR/data-chain-before.txt" || die 'data chain snapshot failed'
    printf '%s\n' "$FS_PROOF" > "$JOB_DIR/filesystem-before.txt" || die 'filesystem proof snapshot failed'
    plan_text > "$JOB_DIR/plan.txt" || die 'plan snapshot failed'
    GPT_BEFORE_HASH=$(hash_file "$JOB_DIR/gpt-before.bin")
    GPT_PLANNED_HASH=$(hash_file "$JOB_DIR/gpt-planned.bin")
    PLAN_ID=$({ cat "$JOB_DIR/plan.txt"; printf '%s\n' "GPT_BEFORE_SHA256=$GPT_BEFORE_HASH" "GPT_PLANNED_SHA256=$GPT_PLANNED_HASH"; } | sha256sum | awk '{print $1}')
    (
        cd "$JOB_DIR" || exit 1
        sha256sum online-create.sh tool-dispatch.sh partition_worker fsprobe gpttool payloads.sha256 efisp.img abl.img tools/* \
            interpreter/busybox selected-tools.txt \
            gpt-before.bin gpt-planned.bin abl-current.full.bin table-before.txt other-entries-before.txt \
            userdata-entry-before.txt data-chain-before.txt filesystem-before.txt plan.txt || exit 1
        for selected_alias in selected-tools/*; do
            [ -L "$selected_alias" ] || continue
            sha256sum "$selected_alias" || exit 1
        done
        if [ "$SOURCE_BINDING_HASH" != not-present ]; then sha256sum payload-source.txt || exit 1; fi
        other_abl_inventory || exit 1
        [ "$ABL_PARENT" = "$UD_PARENT" ] || sha256sum abl-disk-gpt-before.bin || exit 1
        [ "$PLAN_MODE" != reuse ] || sha256sum efisp-before.full.bin
    ) > "$JOB_DIR/backup-inventory.sha256" || die 'backup inventory creation failed'
    {
        printf '%s\n' 'FORMAT=BootForge-online-create-v3' "PLAN_ID=$PLAN_ID" "GPT_BEFORE_SHA256=$GPT_BEFORE_HASH" \
            "GPT_PLANNED_SHA256=$GPT_PLANNED_HASH" "GPTTOOL_SHA256=$(hash_file "$GPTTOOL")" \
            "EFI_BEFORE_SHA256=$EFI_BEFORE_HASH" \
            "ABL_BEFORE_SHA256=$ABL_BEFORE_HASH" "INVENTORY_SHA256=$(hash_file "$JOB_DIR/backup-inventory.sha256")" \
            "OTHER_ABL_BEFORE_SHA256=$OTHER_ABL_BEFORE_HASH" "OTHER_ABL_GPT_SHA256=$OTHER_ABL_GPT_BEFORE_HASH" \
            'DATA_FORMATTED=0' "REQUIRES_RECOVERY_FORMAT=$(completion_requires_recovery)" \
            'NOTE=F2FS online shrink precedes GPT rebuild; completion entry follows the selected mode' \
            'NOTE=GPT and ABL backup do not restore filesystem data or undo an F2FS shrink'
        cat "$JOB_DIR/plan.txt"
    } > "$JOB_DIR/manifest.txt" || die 'manifest creation failed'
    sync || die 'RAM backup sync failed'
    journal "EVIDENCE=backup-complete:$PLAN_ID"
    journal 'PROOF=BackupVerified'
    journal 'STATE=prepared'
    printf '%s\n' 'BACKUP_READY=1' "JOB_DIR=$JOB_DIR" "MANIFEST_SHA256=$(hash_file "$JOB_DIR/manifest.txt")" "SLOT=$SLOT" 'MODE=online-create' \
        "PLAN_ID=$PLAN_ID" 'RESULT=backup-ready' 'DATA_FORMATTED=0' "REQUIRES_RECOVERY_FORMAT=$(completion_requires_recovery)"
    exit 0
fi

# The mounted F2FS ioctl can write through every verified mapping and auxiliary
# backend. Freeze all identities together; read-only inspection never calls this.
DATA_WRITE_IDENTITIES=
validate_data_write_chain() {
    [ "$(walk_chain "$DATA_NAME" 0 '')" = "$DATA_CHAIN" ] && \
        [ "$(data_mount /proc/1/mountinfo)" = "$INIT_DATA" ] && \
        [ "$(data_mount /proc/self/mountinfo)" = "$SELF_DATA" ] || return 1
}
ensure_data_chain_writable() {
    local data_targets data_ids= data_path data_id data_access
    bf_write_phase_allowed && validate_data_write_chain || return 1
    data_targets=$(printf '%s\n' "$DATA_CHAIN" | awk -F'[=:]' '/^(MAP|ORIGIN|AUX)=/ {print "/dev/block/"$2}' | sort -u)
    [ -n "$data_targets" ] || return 1
    for data_path in $data_targets; do
        data_id=$(bf_target_identity "$data_path") || return 1
        data_ids="$data_ids
$data_path=$data_id"
    done
    if [ -n "$DATA_WRITE_IDENTITIES" ]; then [ "$DATA_WRITE_IDENTITIES" = "$data_ids" ] || return 1
    else DATA_WRITE_IDENTITIES=$data_ids; fi
    for data_path in $data_targets; do
        data_id=$(printf '%s\n' "$data_ids" | awk -v p="$data_path=" 'index($0,p)==1 {print substr($0,length(p)+1)}')
        [ "$(bf_target_identity "$data_path")" = "$data_id" ] || return 1
        data_access=$(write_access_path "$data_path") || return 1
        [ "$(bf_target_identity "$data_access")" = "$data_id" ] || return 1
        bf_ensure_writable "$data_access" "${1:-}" || return 1
        [ "$(bf_target_identity "$data_path")" = "$data_id" ] || return 1
    done
    validate_data_write_chain
}
run_f2fs_shrink() {
    local fs_log=$1 fs_identity fs_retry fs_retry_bytes fs_worker_hash fs_rc
    fs_identity=$(bf_target_identity "$DATA_SOURCE") || return 1
    fs_worker_hash=$(hash_file "$WORKER") || return 1
    ensure_data_chain_writable || return 1
    [ "$(bf_target_identity "$DATA_SOURCE")" = "$fs_identity" ] && validate_data_write_chain || return 1
    "$WORKER" shrink-f2fs /data "$DATA_SOURCE" "$MAX_FS_BYTES" --execute > "$fs_log" 2>&1; fs_rc=$?
    [ "$fs_rc" != 0 ] || return 0
    [ "$(bf_target_identity "$DATA_SOURCE")" = "$fs_identity" ] && validate_data_write_chain || return 1
    bf_readonly_failure "$DATA_SOURCE" "$fs_log" || return "$fs_rc"
    cp "$fs_log" "$fs_log.first-attempt" || return 1
    fs_retry=$("$WORKER" inspect-f2fs /data "$DATA_SOURCE" 2> "$fs_log.retry-inspect.log") || return 1
    printf '%s\n' "$fs_retry" > "$fs_log.retry-inspect.txt" || return 1
    [ "$(kv "$fs_retry" RESULT)" = success ] && [ "$(kv "$fs_retry" FS_MATCH)" = 1 ] && \
        [ "$(kv "$fs_retry" FS_TYPE)" = f2fs ] && [ "$(kv "$fs_retry" FS_READ_MODE)" = direct ] && \
        [ "$(kv "$fs_retry" DEVICE_BYTES)" = "$UD_BYTES" ] || return 1
    fs_retry_bytes=$(kv "$fs_retry" FS_BYTES)
    [ "$fs_retry_bytes" = "$FS_BYTES" ] || [ "$fs_retry_bytes" = "$NEW_UD_BYTES" ] || return 1
    ensure_data_chain_writable force || return 1
    [ "$(bf_target_identity "$DATA_SOURCE")" = "$fs_identity" ] && \
        [ "$(hash_file "$WORKER")" = "$fs_worker_hash" ] || return 1
    if [ "$fs_retry_bytes" = "$NEW_UD_BYTES" ]; then
        journal 'F2FS_RO_RECOVERY=planned-filesystem-already-complete'
        return 0
    fi
    journal 'F2FS_RETRY=read-only-and-original-size-verified;ATTEMPT=2'
    "$WORKER" shrink-f2fs /data "$DATA_SOURCE" "$MAX_FS_BYTES" --execute > "$fs_log" 2>&1
}

# A resume is a single execution attempt after caller backup-save attestation.
STAGE=resume-validation
verify_payload_source_file
grep -qx 'STATE=prepared' "$JOB_DIR/journal.txt" || die 'prepared journal proof is absent'
! grep -qx 'STATE=execution-start' "$JOB_DIR/journal.txt" || die 'job already started; inspect state instead of blindly rerunning'
mkdir "$JOB_DIR/execution-claim" 2>/dev/null || die 'job has already been claimed for execution or cancellation'
# A claimed failed attempt must be retired through the stopped-job evidence path.
journal 'STATE=execution-start'
[ "$(hash_file "$JOB_DIR/backup-inventory.sha256")" = "$(value "$JOB_DIR/manifest.txt" INVENTORY_SHA256)" ] || die 'inventory differs from saved backup manifest'
(cd "$JOB_DIR" && sha256sum -c backup-inventory.sha256) > "$JOB_DIR/inventory-readback.log" 2>&1 || die 'RAM backup or engine inventory mismatch'
PLAN_ID=$(value "$JOB_DIR/manifest.txt" PLAN_ID)
is_hash "$PLAN_ID" || die 'prepared PLAN_ID is invalid'
[ "$(plan_text)" = "$(cat "$JOB_DIR/plan.txt")" ] || die 'device geometry, slot, image, or plan changed since backup'
[ "$GPT_TABLE" = "$(cat "$JOB_DIR/table-before.txt")" ] && \
    [ "$UD_INFO" = "$(cat "$JOB_DIR/userdata-entry-before.txt")" ] && \
    [ "$OTHER_ENTRIES" = "$(cat "$JOB_DIR/other-entries-before.txt")" ] && \
    [ "$DATA_CHAIN" = "$(cat "$JOB_DIR/data-chain-before.txt")" ] || die 'GPT or mapping chain changed since backup'
export_gpt "$DISK" "$JOB_DIR/gpt-resume-readback.bin" "$JOB_DIR/gpt-resume-readback.log" || die 'resume raw GPT readback failed'
[ "$(hash_file "$JOB_DIR/gpt-resume-readback.bin")" = "$(value "$JOB_DIR/manifest.txt" GPT_BEFORE_SHA256)" ] || die 'raw GPT backup hash changed since preparation'
[ "$(hash_file "$JOB_DIR/gpt-before.bin")" = "$(value "$JOB_DIR/manifest.txt" GPT_BEFORE_SHA256)" ] && \
    [ "$(hash_file "$JOB_DIR/gpt-planned.bin")" = "$(value "$JOB_DIR/manifest.txt" GPT_PLANNED_SHA256)" ] && \
    [ "$(hash_file "$GPTTOOL")" = "$(value "$JOB_DIR/manifest.txt" GPTTOOL_SHA256)" ] || die 'raw GPT snapshots or helper changed since preparation'
read_direct "$ABL" 0 "$ABL_CAPACITY" "$JOB_DIR/abl-resume-readback.bin" "$JOB_DIR/abl-resume-readback.log" && [ "$(hash_file "$JOB_DIR/abl-resume-readback.bin")" = "$(value "$JOB_DIR/manifest.txt" ABL_BEFORE_SHA256)" ] || die 'current-slot ABL changed since backup'
if other_abl_needed; then
    OTHER_ABL_BEFORE_HASH=$(value "$JOB_DIR/manifest.txt" OTHER_ABL_BEFORE_SHA256)
    OTHER_ABL_GPT_BEFORE_HASH=$(value "$JOB_DIR/manifest.txt" OTHER_ABL_GPT_SHA256)
    verify_other_abl_unchanged "$JOB_DIR"
fi
expected_plan=$({ cat "$JOB_DIR/plan.txt"; printf '%s\n' "GPT_BEFORE_SHA256=$(value "$JOB_DIR/manifest.txt" GPT_BEFORE_SHA256)" "GPT_PLANNED_SHA256=$(value "$JOB_DIR/manifest.txt" GPT_PLANNED_SHA256)"; } | sha256sum | awk '{print $1}')
[ "$PLAN_ID" = "$expected_plan" ] || die 'PLAN_ID binding mismatch'
journal "EVIDENCE=caller-backup-saved-attested:$EXPORTED_HASH"
journal "EVIDENCE=original-gpt-and-slot-unchanged:$PLAN_ID"
journal 'PROOF=BackupVerified'
BF_WRITE_AUTHORIZED=1
ensure_selected_abl_writable
bf_ensure_writable "$DISK" || die 'physical efisp/GPT disk could not be made writable'
if [ "$SKIP_F2FS_SHRINK" = 0 ]; then ensure_data_chain_writable || die 'userdata write chain could not be made writable'; fi

if [ "$SKIP_F2FS_SHRINK" = 0 ]; then
    STAGE=f2fs-online-shrink
    journal "STATE=$STAGE"
    FILESYSTEM_MAY_BE_MODIFIED=1
    journal 'FILESYSTEM_MAY_BE_MODIFIED=1'
    run_f2fs_shrink "$JOB_DIR/f2fs-shrink.log" || die 'native F2FS shrink failed or read-only state could not be reconciled; GPT remains original and must be inspected'
    sync || die 'F2FS sync failed before GPT write'
else
    STAGE=filesystem-already-fits
    journal "STATE=$STAGE"
    journal "EVIDENCE=skip-f2fs-shrink:$PLAN_MODE:$TAIL_FREE_BYTES"
fi
FS_AFTER=$("$WORKER" inspect-f2fs /data "$DATA_SOURCE" 2>&1) || die 'direct F2FS and checkpoint readback failed before GPT operation'
FS_AFTER_BYTES=$(printf '%s\n' "$FS_AFTER" | sed -n 's/^FS_BYTES=//p')
is_uint "$FS_AFTER_BYTES" && [ "$FS_AFTER_BYTES" -le "$NEW_UD_BYTES" ] && \
    [ "$(printf '%s\n' "$FS_AFTER" | sed -n 's/^FS_MATCH=//p')" = 1 ] && \
    [ "$(printf '%s\n' "$FS_AFTER" | sed -n 's/^FS_TYPE=//p')" = f2fs ] && \
    [ "$(kv "$FS_AFTER" RESULT)" = success ] && [ "$(kv "$FS_AFTER" FS_READ_MODE)" = direct ] && \
    [ "$(printf '%s\n' "$FS_AFTER" | sed -n 's/^DEVICE_BYTES=//p')" = "$UD_BYTES" ] || die 'direct filesystem proof does not fit planned userdata'
[ "$PLAN_MODE" = reuse ] || [ "$FS_AFTER_BYTES" = "$NEW_UD_BYTES" ] || die 'actual filesystem size differs from precomputed GPT plan; stop without GPT write'
CURRENT_DATA_CHAIN=$(walk_chain "$DATA_NAME" 0 '') || die 'DM chain changed during F2FS shrink'
[ "$CURRENT_DATA_CHAIN" = "$DATA_CHAIN" ] && [ "$(data_mount /proc/1/mountinfo)" = "$INIT_DATA" ] && \
    [ "$(data_mount /proc/self/mountinfo)" = "$SELF_DATA" ] || die 'mounted filesystem or origin changed during F2FS shrink'
printf '%s\n' "$FS_AFTER" > "$JOB_DIR/filesystem-after-shrink.txt" || die 'post-shrink proof persistence failed'
journal "EVIDENCE=f2fs-main-backup-fit:$FS_AFTER_BYTES:$MAX_FS_BYTES"
journal 'PROOF=FilesystemAdjusted'
verify_gpt "$DISK" || die 'GPT changed during F2FS shrink'
export_gpt "$DISK" "$JOB_DIR/gpt-write-source.bin" "$JOB_DIR/gpt-write-source.log" || die 'raw GPT source readback failed after F2FS shrink'
[ "$(hash_file "$JOB_DIR/gpt-write-source.bin")" = "$(value "$JOB_DIR/manifest.txt" GPT_BEFORE_SHA256)" ] && \
    [ "$(hash_file "$JOB_DIR/gpt-planned.bin")" = "$(value "$JOB_DIR/manifest.txt" GPT_PLANNED_SHA256)" ] && \
    [ "$(hash_file "$GPTTOOL")" = "$(value "$JOB_DIR/manifest.txt" GPTTOOL_SHA256)" ] || die 'raw GPT source, plan, or helper changed before the sole apply'

GPT_WRITE_RC=0
if [ "$PLAN_MODE" = create ]; then
    STAGE=gpt-entry-rebuild
    journal "STATE=$STAGE"
    GPT_MAY_BE_MODIFIED=1
    journal 'GPT_MAY_BE_MODIFIED=1'
    bf_run_gpt_apply "$GPTTOOL" "$DISK" "$JOB_DIR/gpt-before.bin" "$JOB_DIR/gpt-planned.bin" "$JOB_DIR/gpt-write.log"
    GPT_WRITE_RC=$?
    [ "$GPT_WRITE_RC" = 0 ] || die 'raw GPT apply failed or changed state prevents read-only retry; retain both snapshots and inspect'
    grep -qx 'DISK_READ_MODE=direct' "$JOB_DIR/gpt-write.log" && grep -qx 'RESULT=success' "$JOB_DIR/gpt-write.log" && \
        grep -qx 'READBACK=match' "$JOB_DIR/gpt-write.log" || die 'raw GPT apply proof is missing; no transaction replay'
    sync || die 'GPT write sync failed'
else
    STAGE=existing-efisp-reuse
    journal "STATE=$STAGE"
    journal 'EVIDENCE=existing-efisp-reused:no-filesystem-or-gpt-write'
fi
export_gpt "$DISK" "$JOB_DIR/gpt-after.bin" "$JOB_DIR/gpt-after.log" || die 'raw GPT post-apply export failed'
[ "$(hash_file "$JOB_DIR/gpt-after.bin")" = "$(value "$JOB_DIR/manifest.txt" GPT_PLANNED_SHA256)" ] || die 'raw GPT after apply differs from the exact planned snapshot'
# The helper writes backup then primary without a kernel partition reread.
# No node reread, DM reload, or by-name efisp discovery is used for the PE write.
verify_gpt "$DISK" || die 'on-disk primary/backup GPT is not clean after transaction'
GPT_AFTER_INSPECT=$(inspect_gpt "$DISK" "$UD_INDEX") || die 'direct post-transaction GPT inspection failed'
[ "$(kv "$GPT_AFTER_INSPECT" UD_START)" = "$UD_START" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" UD_END)" = "$NEW_UD_END" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" UD_TYPE)" = "$UD_TYPE" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" UD_GUID)" = "$UD_GUID" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" UD_ATTR)" = "$UD_ATTR" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" UD_ATTR_BACKUP)" = "$UD_ATTR_BACKUP" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" UD_LABEL)" = userdata ] || die 'direct userdata geometry or preserved metadata failed readback'
[ "$(kv "$GPT_AFTER_INSPECT" EFI_COUNT)" = 1 ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" EFI_INDEX)" = "$EFI_INDEX" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" EFI_START)" = "$EFI_START" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" EFI_END)" = "$EFI_END" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" EFI_BYTES)" = "$EFI_BYTES" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" EFI_TYPE)" = "$EFI_TYPE" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" EFI_GUID)" = "$EFI_GUID" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" EFI_ATTR)" = "$EFI_ATTR" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" EFI_ATTR_BACKUP)" = "$EFI_ATTR_BACKUP" ] && \
    [ "$(kv "$GPT_AFTER_INSPECT" EFI_LABEL)" = "$EFI_LABEL" ] || die 'direct efisp exact range or common profile failed readback'
[ "$(gpt_header "$GPT_AFTER_INSPECT")" = "$GPT_HEADER_GEOMETRY" ] || die 'direct disk GUID or GPT header changed'
PARTITIONS_AFTER=$(kv "$GPT_AFTER_INSPECT" PARTITION_INDICES | tr ' ' '\n')
if [ "$PLAN_MODE" = create ]; then EXPECTED_PARTITIONS=$(printf '%s\n%s\n' "$PARTITIONS" "$EFI_INDEX" | sort -n)
else EXPECTED_PARTITIONS=$PARTITIONS; fi
[ "$PARTITIONS_AFTER" = "$EXPECTED_PARTITIONS" ] || die 'unexpected GPT entries appeared or disappeared'
TABLE_AFTER=$GPT_AFTER_INSPECT UD_AFTER=$(userdata_info "$GPT_AFTER_INSPECT") EFI_AFTER=$GPT_AFTER_INSPECT OTHER_AFTER=$OTHER_ENTRIES
GPT_VERIFIED=1
journal 'GPT_VERIFIED=1'
printf '%s\n' "$TABLE_AFTER" "$UD_AFTER" "$EFI_AFTER" "$OTHER_AFTER" > "$JOB_DIR/gpt-after-proof.txt" || die 'GPT readback proof persistence failed'
journal "EVIDENCE=gpt-entry-transaction-readback:$GPT_WRITE_RC:$EFI_START:$EFI_END:$EFI_GUID:$EFI_PROFILE:$EFI_TYPE:$EFI_ATTR"
journal 'PROOF=GptAndEfispVerified'
if [ "$PLAN_MODE" = reuse ]; then
    read_direct "$DISK" "$EFI_OFFSET" "$EFI_BYTES" "$JOB_DIR/efisp-reuse-readback.bin" "$JOB_DIR/efisp-reuse-readback.log" || die 'existing efisp changed-state readback failed'
    [ "$(hash_file "$JOB_DIR/efisp-reuse-readback.bin")" = "$(value "$JOB_DIR/manifest.txt" EFI_BEFORE_SHA256)" ] || die 'existing efisp content changed since backup'
fi

STAGE=current-slot-abl-write
journal "STATE=$STAGE"
check_slot
write_current_abl() {
[ "$(readlink -f "/dev/block/by-name/abl_$SLOT")" = "$ABL" ] || die 'current-slot ABL link changed'
inspect_partition "$ABL" || die 'ABL physical partition disappeared'
[ "$BN" = "$ABL_NAME" ] && [ "$PARENT" = "$ABL_PARENT" ] && [ "$INDEX" = "$ABL_INDEX" ] && \
    [ "$START512" = "$ABL_START512" ] && [ "$SIZE512" = "$ABL_SIZE512" ] && [ "$MM" = "$ABL_MM" ] || die 'ABL physical geometry changed'
for holder in /sys/class/block/"$ABL_NAME"/holders/*; do [ ! -e "$holder" ] || die 'ABL acquired an active holder'; done
! awk -v mm="$ABL_MM" '$3==mm {found=1} END {exit !found}' /proc/1/mountinfo /proc/self/mountinfo || die 'ABL became mounted'
for namespace in /proc/[0-9]*/mountinfo; do
    [ -r "$namespace" ] || continue
    ! awk -v mm="$ABL_MM" '$3==mm {found=1} END {exit !found}' "$namespace" 2>/dev/null || die 'ABL is mounted in another process namespace'
done
[ "$(hash_file "$ABL_IMAGE")" = "$ABL_HASH" ] || die 'ABL image changed before write'
ABL_MAY_BE_MODIFIED=1
journal 'ABL_MAY_BE_MODIFIED=1'
write_direct "$ABL" 0 "$ABL_CAPACITY" "$ABL_IMAGE" "$JOB_DIR/abl-write.log" || die 'current-slot ABL write failed; retain complete original backup'
sync || die 'ABL sync failed'
read_direct "$ABL" 0 "$ABL_BYTES" "$JOB_DIR/abl-readback.bin" "$JOB_DIR/abl-readback.log" || die 'ABL readback failed'
[ "$(file_bytes "$JOB_DIR/abl-readback.bin")" = "$ABL_BYTES" ] && [ "$(hash_file "$JOB_DIR/abl-readback.bin")" = "$ABL_HASH" ] || die 'current-slot ABL SHA256 mismatch'
journal "EVIDENCE=current-slot-abl-sha256:$SLOT:$ABL_HASH"
}
write_selected_abl_targets "$JOB_DIR"
journal 'PROOF=CurrentAblVerified'

STAGE=pe-physical-range-write
journal "STATE=$STAGE"
[ "$(hash_file "$PE_IMAGE")" = "$PE_HASH" ] || die 'PE image changed before write'
write_direct "$DISK" "$EFI_OFFSET" "$EFI_BYTES" "$PE_IMAGE" "$JOB_DIR/pe-write.log" || die 'PE physical range write failed after ABL update'
sync || die 'PE sync failed after ABL update'
read_direct "$DISK" "$EFI_OFFSET" "$PE_BYTES" "$JOB_DIR/pe-readback.bin" "$JOB_DIR/pe-readback.log" || die 'PE physical readback failed after ABL update'
[ "$(file_bytes "$JOB_DIR/pe-readback.bin")" = "$PE_BYTES" ] && [ "$(hash_file "$JOB_DIR/pe-readback.bin")" = "$PE_HASH" ] || die 'PE physical range SHA256 mismatch after ABL update'
PE_VERIFIED=1
journal "EVIDENCE=pe-physical-sha256:$PE_HASH"
journal 'PE_VERIFIED=1'
journal 'PROOF=PeVerified'
STAGE=complete
journal 'STATE=complete'
if [ -d "$LOCK_DIR" ] && [ "$(cat "$LOCK_DIR/job" 2>/dev/null)" = "$JOB_DIR" ] && \
    rm "$LOCK_DIR/job" && rmdir "$LOCK_DIR"; then
    journal 'LOCK_RELEASED=1'
else
    journal 'LOCK_RELEASED=0'
    journal 'WARNING=all image writes verified; write lock cleanup failed; retain RAM backup and inspect lock before another job'
fi
journal 'RESULT=success'
printf '%s\n' "SLOT=$SLOT" "ABL=$ABL" "ABL_WRITE_TARGET=$ABL_WRITE_TARGET" "ABL_TARGET_SLOTS=$(abl_target_slots)" "ABL_TARGET_PARTITIONS=$(abl_target_partitions)" "ABL_TARGETS_VERIFIED=$ABL_TARGETS_VERIFIED" "EFISP=GPT:$DISK:$EFI_INDEX:$EFI_START:$EFI_END" \
    "EFI_PROFILE=$EFI_PROFILE" "EFI_LABEL=$EFI_LABEL" "EFI_TYPE=$EFI_TYPE" "EFI_ATTR=$EFI_ATTR" "EFI_GUID=$EFI_GUID" \
    "JOB_DIR=$JOB_DIR" "PLAN_ID=$PLAN_ID" 'DATA_FORMATTED=0' "REQUIRES_RECOVERY_FORMAT=$(completion_requires_recovery)" \
    "EFI_BYTES=$EFI_BYTES" "TAIL_FREE_BYTES=$TAIL_FREE_BYTES" "SKIP_F2FS_SHRINK=$SKIP_F2FS_SHRINK" "FS_READ_MODE=$FS_READ_MODE" "PLAN_MODE=$PLAN_MODE" \
    "REBOOT_TARGET=$(completion_reboot_target)" "NEXT_STEP=$(completion_next_step)"
exit 0
