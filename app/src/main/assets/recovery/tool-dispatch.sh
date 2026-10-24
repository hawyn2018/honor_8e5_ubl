# Sourced by BootForge engines. System executables have priority; bundled tools
# handle absent commands and failed read-only/replayable commands. Descriptor 3
# records fallbacks outside captured query stdout/stderr used as GPT evidence.
BF_TOOLS_DIR=${0%/*}/tools
BF_SELECTED_DIR=${0%/*}/selected-tools
exec 3>&2

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
        --rereadpt) bf_replayable blockdev "$@" ;;
        *) printf '%s\n' 'ERROR=unsupported blockdev operation' >&2; return 2 ;;
    esac
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
