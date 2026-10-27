# Sourced by BootForge engines. System executables have priority; bundled tools
# handle absent commands and failed read-only/replayable commands. Descriptor 3
# records fallbacks outside captured query stdout/stderr used as GPT evidence.
BF_TOOLS_DIR=${0%/*}/tools
BF_SELECTED_DIR=${0%/*}/selected-tools
exec 3>&2
BF_WRITE_AUTHORIZED=0

bf_system_path() {
    local bf_name=$1 bf_dir
    # Preparation already tried this system command and selected the bundled
    # implementation. Preserve that decision for wrappers as well as PATH calls.
    [ ! -L "$BF_SELECTED_DIR/$bf_name" ] || return 1
    for bf_dir in /sbin /system/bin /system/xbin /vendor/bin /vendor/xbin; do
        if [ -f "$bf_dir/$bf_name" ] && [ -x "$bf_dir/$bf_name" ]; then
            printf '%s\n' "$bf_dir/$bf_name"
            return 0
        fi
    done
    return 1
}
bf_bundled_path() {
    [ -f "$BF_TOOLS_DIR/$1" ] && [ -x "$BF_TOOLS_DIR/$1" ] || return 1
    printf '%s\n' "$BF_TOOLS_DIR/$1"
}
bf_fallback_log() { printf '%s\n' "TOOL_FALLBACK=$1;REASON=$2" >&3; }
bf_system_skip_reason() {
    if [ -L "$BF_SELECTED_DIR/$1" ]; then printf '%s\n' selected-bundled-after-system-check
    else printf '%s\n' system-missing; fi
}
bf_tool_available() {
    bf_system_path "$1" >/dev/null && return 0
    bf_bundled_path "$1" >/dev/null || return 1
    bf_fallback_log "$1" "$(bf_system_skip_reason "$1")"
}
bf_selected_aliases() {
    local bf_selected_dir=$1 bf_alias bf_alias_name bf_alias_target
    if [ ! -d "$bf_selected_dir" ]; then
        [ ! -e "$bf_selected_dir" ] && [ ! -L "$bf_selected_dir" ]
        return "$?"
    fi
    [ ! -L "$bf_selected_dir" ] || return 1
    for bf_alias in "$bf_selected_dir"/*; do
        [ -e "$bf_alias" ] || [ -L "$bf_alias" ] || continue
        bf_alias_name=${bf_alias##*/}
        case "$bf_alias_name" in ''|*[!A-Za-z0-9._-]*) return 1 ;; esac
        [ -L "$bf_alias" ] || return 1
        # Bypass the wrapper so validating a selected readlink alias cannot recurse.
        bf_alias_target=$("$BF_TOOLS_DIR/busybox" readlink "$bf_alias") || return 1
        [ "$bf_alias_target" = "../tools/$bf_alias_name" ] && \
            [ -f "$bf_selected_dir/../tools/$bf_alias_name" ] && \
            [ -x "$bf_selected_dir/../tools/$bf_alias_name" ] || return 1
        printf '%s %s\n' "$bf_alias_name" "$bf_alias_target"
    done
}

# Only commands whose complete output fits a shell value use this interface.
# Suppressing a failed system command's partial output prevents mixing two query
# results. In particular, it is never used for binary streams or stdin pipelines.
bf_query() {
    local bf_name=$1 bf_system bf_bundle bf_output bf_rc
    shift
    bf_system=$(bf_system_path "$bf_name") || bf_system=
    if [ -n "$bf_system" ]; then
        bf_output=$("$bf_system" "$@" 2>&1); bf_rc=$?
        if [ "$bf_rc" = 0 ]; then printf '%s\n' "$bf_output"; return 0; fi
        bf_fallback_log "$bf_name" "system-exit-$bf_rc"
    else bf_fallback_log "$bf_name" "$(bf_system_skip_reason "$bf_name")"; fi
    bf_bundle=$(bf_bundled_path "$bf_name") || return 127
    bf_output=$("$bf_bundle" "$@" 2>&1); bf_rc=$?
    if [ "$bf_rc" = 0 ]; then printf '%s\n' "$bf_output"
    else printf '%s\n' "$bf_output" >&2; fi
    return "$bf_rc"
}

sgdisk() {
    # GPT mutations must use bf_sgdisk_primary and explicit state reconciliation.
    case "${1:-}" in
        --print|--info=*|--verify|--backup=*|--version) bf_query sgdisk "$@" ;;
        *) printf '%s\n' 'ERROR=sgdisk mutation requires explicit state reconciliation' >&2; return 2 ;;
    esac
}
blockdev() {
    case "${1:-}" in
        --getss|--getsize64|--getro) bf_query blockdev "$@" ;;
        --setrw) shift; bf_blockdev_setrw "$@" ;;
        --rereadpt) bf_replayable blockdev "$@" ;;
        *) printf '%s\n' 'ERROR=unsupported blockdev operation' >&2; return 2 ;;
    esac
}

# Engines set this flag only after their complete, frozen backup checks. Merely
# inspecting or preparing a job never changes a block device's access flag.
bf_write_phase_allowed() {
    [ "${BF_WRITE_AUTHORIZED:-0}" = 1 ] || return 1
    case "${ACTION:-}" in
        --execute) [ "${PHASE:-}" = resume ] && [ "${BF_RAM_STAGE:-0}" = 1 ] ;;
        --check) return 1 ;;
        *) [ "${MODE_ARG:-}" = --execute ] ;;
    esac
}
bf_target_identity() {
    local bf_path bf_node bf_mm bf_bytes bf_sys bf_parent bf_index=0 bf_start=0 bf_sector bf_size
    bf_path=$(readlink -f "$1") || return 1
    case "$bf_path" in /dev/block/*) ;; *) return 1 ;; esac
    case "$bf_path" in *'/../'*|*'/./'*|*' '*|*'|'*) return 1 ;; esac
    [ -b "$bf_path" ] || return 1
    bf_node=${bf_path##*/}
    bf_sys=$(readlink -f "/sys/class/block/$bf_node") || return 1
    case "$bf_sys" in /sys/devices/*) ;; *) return 1 ;; esac
    case "$bf_sys" in *'|'*|*' '*) return 1 ;; esac
    bf_mm=$(cat "/sys/class/block/$bf_node/dev") || return 1
    printf '%s\n' "$bf_mm" | grep -Eq '^[0-9]+:[0-9]+$' || return 1
    bf_bytes=$(blockdev --getsize64 "$bf_path") || return 1
    case "$bf_bytes" in ''|*[!0-9]*) return 1 ;; esac
    [ "$bf_bytes" -gt 0 ] || return 1
    bf_parent=$bf_sys
    if [ -r "/sys/class/block/$bf_node/partition" ]; then
        bf_index=$(cat "/sys/class/block/$bf_node/partition") || return 1
        bf_start=$(cat "/sys/class/block/$bf_node/start") || return 1
        bf_parent=${bf_sys%/*}
    fi
    bf_sector=$(cat "$bf_parent/queue/logical_block_size") || return 1
    bf_size=$(cat "/sys/class/block/$bf_node/size") || return 1
    case "$bf_index:$bf_start:$bf_sector:$bf_size" in *[!0-9:]*|:*|*::*) return 1 ;; esac
    [ "$bf_sector" -ge 512 ] && [ "$((bf_size * 512))" = "$bf_bytes" ] || return 1
    printf '%s|%s|%s|%s|%s|%s|%s\n' "$bf_path" "$bf_mm" "$bf_bytes" "$bf_sys" "$bf_index" "$bf_start" "$bf_sector"
}
bf_readonly_state() {
    local bf_state
    bf_state=$(bf_query blockdev --getro "$1") || return 1
    case "$bf_state" in 0|1) printf '%s\n' "$bf_state" ;; *) return 1 ;; esac
}
bf_blockdev_setrw() {
    local bf_target bf_identity bf_system bf_bundle bf_rc bf_ro
    [ "$#" = 1 ] && bf_write_phase_allowed || return 2
    bf_target=$1
    bf_identity=$(bf_target_identity "$bf_target") || return 1
    bf_ro=$(bf_readonly_state "$bf_target") || return 1
    bf_system=$(bf_system_path blockdev) || bf_system=
    if [ -n "$bf_system" ]; then
        printf '%s\n' "BLOCK_SETRW=$bf_target;TOOL=system;BEFORE=$bf_ro" >&3
        "$bf_system" --setrw "$bf_target"; bf_rc=$?
        [ "$(bf_target_identity "$bf_target")" = "$bf_identity" ] || return 1
        bf_ro=$(bf_readonly_state "$bf_target") || return 1
        if [ "$bf_rc" = 0 ] && [ "$bf_ro" = 0 ]; then
            printf '%s\n' "BLOCK_WRITABLE=$bf_target;TOOL=system" >&3
            return 0
        fi
        bf_fallback_log blockdev "system-setrw-exit-$bf_rc-readonly-$bf_ro"
    else bf_fallback_log blockdev "$(bf_system_skip_reason blockdev)"; fi
    bf_bundle=$(bf_bundled_path blockdev) || return 127
    [ "$(bf_target_identity "$bf_target")" = "$bf_identity" ] || return 1
    printf '%s\n' "BLOCK_SETRW=$bf_target;TOOL=bundled;BEFORE=$bf_ro" >&3
    "$bf_bundle" --setrw "$bf_target"; bf_rc=$?
    [ "$(bf_target_identity "$bf_target")" = "$bf_identity" ] || return 1
    bf_ro=$(bf_readonly_state "$bf_target") || return 1
    if [ "$bf_rc" = 0 ] && [ "$bf_ro" = 0 ]; then
        printf '%s\n' "BLOCK_WRITABLE=$bf_target;TOOL=bundled" >&3
        return 0
    fi
    printf '%s\n' "ERROR=blockdev setrw failed;TARGET=$bf_target;EXIT=$bf_rc;READ_ONLY=$bf_ro" >&3
    return 1
}
bf_ensure_writable() {
    local bf_identity bf_ro
    [ "$#" -ge 1 ] && [ "$#" -le 2 ] && bf_write_phase_allowed || return 2
    bf_identity=$(bf_target_identity "$1") || return 1
    bf_ro=$(bf_readonly_state "$1") || return 1
    if [ "$bf_ro" = 1 ] || [ "${2:-}" = force ]; then
        bf_blockdev_setrw "$1" || return 1
    fi
    [ "$(bf_target_identity "$1")" = "$bf_identity" ] && [ "$(bf_readonly_state "$1")" = 0 ]
}
# A convenient by-name access path must still designate the canonical device
# already selected by the engine. Never clear a different alias target's RO flag.
bf_ensure_bound_writable() {
    local bf_bound_target=$1 bf_bound_access=$2 bf_bound_identity
    [ "$#" -ge 2 ] && [ "$#" -le 3 ] && bf_write_phase_allowed || return 2
    bf_bound_identity=$(bf_target_identity "$bf_bound_target") || return 1
    [ "$(bf_target_identity "$bf_bound_access")" = "$bf_bound_identity" ] || return 1
    bf_ensure_writable "$bf_bound_access" "${3:-}" || return 1
    [ "$(bf_target_identity "$bf_bound_target")" = "$bf_bound_identity" ] && \
        [ "$(bf_target_identity "$bf_bound_access")" = "$bf_bound_identity" ]
}
# Unknown BLKROGET errors are not evidence of read-only media. The native helpers
# preserve errno=30 for EROFS; other accepted messages identify an actual RO target.
bf_readonly_failure() {
    local bf_ro
    bf_ro=$(bf_readonly_state "$1") || return 1
    [ "$bf_ro" != 1 ] || return 0
    grep -Eq '(^ERRNO=30$|^ERRNO_TEXT=Read-only file system$|^ERROR=block target is read-only$|^ERROR=block device is read-only$|Read-only file system|read-only file system|EROFS)' "$2"
}
bf_write_range_proof() {
    grep -qx 'READ_MODE=direct' "$1" && grep -qx 'READBACK=match' "$1" && grep -qx 'RESULT=success' "$1"
}
bf_run_write_range() {
    local bf_worker=$1 bf_target=$2 bf_offset=$3 bf_capacity=$4 bf_image=$5 bf_log=$6
    local bf_access=${7:-$2} bf_identity bf_image_hash bf_worker_hash bf_rc
    bf_write_phase_allowed || return 2
    bf_identity=$(bf_target_identity "$bf_target") || return 1
    [ "$(bf_target_identity "$bf_access")" = "$bf_identity" ] || return 1
    bf_image_hash=$(hash_file "$bf_image") || return 1
    bf_worker_hash=$(hash_file "$bf_worker") || return 1
    bf_ensure_writable "$bf_access" || return 1
    [ "$(bf_target_identity "$bf_target")" = "$bf_identity" ] || return 1
    "$bf_worker" write-range "$bf_target" "$bf_offset" "$bf_capacity" "$bf_image" --execute > "$bf_log" 2>&1; bf_rc=$?
    if [ "$bf_rc" = 0 ]; then bf_write_range_proof "$bf_log"; return "$?"; fi
    [ "$(bf_target_identity "$bf_target")" = "$bf_identity" ] && \
        [ "$(bf_target_identity "$bf_access")" = "$bf_identity" ] || return 1
    bf_readonly_failure "$bf_target" "$bf_log" || return "$bf_rc"
    cp "$bf_log" "$bf_log.first-attempt" || return 1
    printf '%s\n' "WRITE_RETRY=read-only;TARGET=$bf_target;ATTEMPT=2" >&3
    bf_ensure_writable "$bf_access" force || return 1
    [ "$(bf_target_identity "$bf_target")" = "$bf_identity" ] && \
        [ "$(bf_target_identity "$bf_access")" = "$bf_identity" ] && \
        [ "$(hash_file "$bf_image")" = "$bf_image_hash" ] && \
        [ "$(hash_file "$bf_worker")" = "$bf_worker_hash" ] || return 1
    "$bf_worker" write-range "$bf_target" "$bf_offset" "$bf_capacity" "$bf_image" --execute > "$bf_log" 2>&1 || return 1
    bf_write_range_proof "$bf_log"
}

# A GPT transaction may be replayed only after direct evidence proves the entire
# original snapshot still exists. A completed planned snapshot is success; mixed
# primary/backup state remains a stopped job with both snapshots retained.
bf_run_gpt_apply() {
    local bf_helper=$1 bf_target=$2 bf_before=$3 bf_planned=$4 bf_log=$5
    local bf_identity bf_before_hash bf_plan_hash bf_helper_hash bf_current_hash bf_rc
    bf_write_phase_allowed || return 2
    bf_identity=$(bf_target_identity "$bf_target") || return 1
    bf_before_hash=$(hash_file "$bf_before") || return 1
    bf_plan_hash=$(hash_file "$bf_planned") || return 1
    bf_helper_hash=$(hash_file "$bf_helper") || return 1
    bf_ensure_writable "$bf_target" || return 1
    [ "$(bf_target_identity "$bf_target")" = "$bf_identity" ] || return 1
    "$bf_helper" apply "$bf_target" "$bf_before" "$bf_planned" --execute > "$bf_log" 2>&1; bf_rc=$?
    [ "$bf_rc" != 0 ] || return 0
    [ "$(bf_target_identity "$bf_target")" = "$bf_identity" ] && \
        [ "$(hash_file "$bf_before")" = "$bf_before_hash" ] && \
        [ "$(hash_file "$bf_planned")" = "$bf_plan_hash" ] && \
        [ "$(hash_file "$bf_helper")" = "$bf_helper_hash" ] || return 1
    bf_readonly_failure "$bf_target" "$bf_log" || return "$bf_rc"
    cp "$bf_log" "$bf_log.first-attempt" || return 1
    "$bf_helper" export "$bf_target" > "$bf_log.retry-state.bin" 2> "$bf_log.retry-state.log" || return 1
    grep -qx 'DISK_READ_MODE=direct' "$bf_log.retry-state.log" || return 1
    [ "$(bf_target_identity "$bf_target")" = "$bf_identity" ] || return 1
    bf_current_hash=$(hash_file "$bf_log.retry-state.bin") || return 1
    if [ "$bf_current_hash" = "$bf_plan_hash" ]; then
        printf '%s\n' 'DISK_READ_MODE=direct' 'READBACK=match' 'RESULT=success' 'RO_RECOVERY=planned-snapshot-already-complete' > "$bf_log"
        return 0
    fi
    [ "$bf_current_hash" = "$bf_before_hash" ] || return 1
    printf '%s\n' "GPT_RETRY=read-only-and-original-snapshot-unchanged;TARGET=$bf_target;ATTEMPT=2" >&3
    bf_ensure_writable "$bf_target" force || return 1
    [ "$(bf_target_identity "$bf_target")" = "$bf_identity" ] && \
        [ "$(hash_file "$bf_before")" = "$bf_before_hash" ] && \
        [ "$(hash_file "$bf_planned")" = "$bf_plan_hash" ] && \
        [ "$(hash_file "$bf_helper")" = "$bf_helper_hash" ] || return 1
    "$bf_helper" apply "$bf_target" "$bf_before" "$bf_planned" --execute > "$bf_log" 2>&1
}

# dd calls in these engines all reopen named input/output files and repeat the
# same finite byte range. A partial first write is safely overwritten by retry.
# Reading a pipeline or changing seek semantics would require a separate plan.
dd() {
    local bf_argument bf_has_input=0 bf_has_output=0
    for bf_argument in "$@"; do
        case "$bf_argument" in if=*) bf_has_input=1 ;; of=*) bf_has_output=1 ;; esac
    done
    [ "$bf_has_input$bf_has_output" = 11 ] || { printf '%s\n' 'ERROR=dd retry requires named input and output' >&2; return 2; }
    bf_replayable dd "$@"
}
bf_replayable() {
    local bf_name=$1 bf_system bf_bundle bf_rc
    shift
    bf_system=$(bf_system_path "$bf_name") || bf_system=
    if [ -n "$bf_system" ]; then
        "$bf_system" "$@"; bf_rc=$?
        [ "$bf_rc" != 0 ] || return 0
        bf_fallback_log "$bf_name" "system-exit-$bf_rc"
    else bf_fallback_log "$bf_name" "$(bf_system_skip_reason "$bf_name")"; fi
    bf_bundle=$(bf_bundled_path "$bf_name") || return 127
    "$bf_bundle" "$@"
}

bf_sgdisk_primary() {
    local bf_system bf_bundle
    bf_system=$(bf_system_path sgdisk) || bf_system=
    if [ -n "$bf_system" ]; then
        BF_SGDISK_PRIMARY_KIND=system
        "$bf_system" "$@"
    else
        BF_SGDISK_PRIMARY_KIND=bundled
        bf_fallback_log sgdisk "$(bf_system_skip_reason sgdisk)"
        bf_bundle=$(bf_bundled_path sgdisk) || return 127
        "$bf_bundle" "$@"
    fi
}
bf_sgdisk_retry_unchanged() {
    local bf_bundle
    [ "${BF_SGDISK_PRIMARY_KIND:-}" = system ] || return 1
    bf_bundle=$(bf_bundled_path sgdisk) || return 127
    bf_fallback_log sgdisk system-nonzero-and-original-gpt-confirmed-unchanged
    "$bf_bundle" "$@"
}

blkid() { bf_query blkid "$@"; }
dumpe2fs() { bf_query dumpe2fs "$@"; }
id() { bf_query id "$@"; }
readlink() { bf_query readlink "$@"; }
od() { bf_query od "$@"; }
df() { bf_query df "$@"; }
date() { bf_query date "$@"; }
sha256sum() {
    # File arguments and -c can be replayed. A stdin hash must remain a streaming
    # command; retrying after consumption would hash a different byte sequence.
    if [ "$#" -gt 0 ]; then bf_query sha256sum "$@"
    else command sha256sum; fi
}
e2fsck() {
    case "${1:-}" in -fn|-nf|-n) bf_query e2fsck "$@" ;; *) bf_filesystem_once e2fsck "$@" ;; esac
}
resize2fs() {
    case "${1:-}" in -P) bf_query resize2fs "$@" ;; *) bf_filesystem_once resize2fs "$@" ;; esac
}
bf_filesystem_once() {
    local bf_name=$1 bf_system bf_bundle bf_rc
    shift
    bf_system=$(bf_system_path "$bf_name") || bf_system=
    if [ -n "$bf_system" ]; then
        "$bf_system" "$@"; bf_rc=$?
        if [ "$bf_rc" != 0 ] && { [ "$bf_name" != e2fsck ] || [ "$bf_rc" != 1 ]; }; then
            printf '%s\n' "TOOL_RETRY_DEFERRED=$bf_name;REASON=filesystem-state-must-be-inspected;SYSTEM_EXIT=$bf_rc" >&3
        fi
        return "$bf_rc"
    fi
    bf_fallback_log "$bf_name" "$(bf_system_skip_reason "$bf_name")"
    bf_bundle=$(bf_bundled_path "$bf_name") || return 127
    "$bf_bundle" "$@"
}
