#!/system/bin/sh
set -u
D=/data/local/tmp/magic-98260
KERNEL=$(uname -r 2>/dev/null || echo unknown)
case "$KERNEL" in
    "6.12.58-android16-6-g3a7ac1674240-abogki541502914-4k") SU=$D/98260/pc98260-su ;;
    "6.12.38-android16-5-gfde7767f6ef6-abogki481467632-4k") SU=$D/98260-6138/pc98260-su ;;
    *) echo "ROOT98260_KERNEL_UNSUPPORTED=$KERNEL" >&2; exit 71 ;;
esac
ENCODED=${1:-}
[ -n "$ENCODED" ] || { echo "ROOT98260_EMPTY_COMMAND" >&2; exit 2; }
[ -x "$SU" ] || { echo "ROOT98260_CLIENT_MISSING=$SU" >&2; exit 125; }

T=$D/io/$$
rm -rf "$T"
mkdir -p "$T"
chmod 777 "$T"
if ! printf '%s' "$ENCODED" | base64 -d > "$T/command.sh"; then
    echo "ROOT98260_BAD_COMMAND" >&2
    exit 2
fi
chmod 644 "$T/command.sh"
ROOT_CMD="umask 022; /system/bin/sh '$T/command.sh' >'$T/stdout' 2>'$T/stderr'; _rc=\$?; printf '%s\\n' \"\$_rc\" > '$T/exit'; chmod 644 '$T/stdout' '$T/stderr' '$T/exit'"
"$SU" -c "$ROOT_CMD" > "$T/transport" 2>&1
CLIENT_RC=$?
if [ ! -f "$T/exit" ]; then
    cat "$T/transport" >&2
    echo "ROOT98260_NO_EXIT client_rc=$CLIENT_RC" >&2
    exit 125
fi
cat "$T/stdout"
cat "$T/stderr" >&2
RC=$(cat "$T/exit")
case "$RC" in
    ''|*[!0-9]*) cat "$T/transport" >&2; echo "ROOT98260_BAD_EXIT=$RC" >&2; exit 125 ;;
esac
rm -rf "$T"
exit "$RC"
