#!/system/bin/sh
set -u
D=/data/local/tmp/magic-98260
SOCK1=/data/local/tmp/pc98260-root.sock
SOCK2=/data/local/tmp/pc98260-root2.sock
CURRENT_BOOT=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)

for marker in /data/local/tmp/.magicroot-98260-parked "$D/.98260-parked"; do
    if [ -f "$marker" ] && [ "$(cat "$marker" 2>/dev/null)" = "$CURRENT_BOOT" ]; then
        echo "CLEAN_98260_REFUSED=parked_state_restart_required"
        exit 2
    fi
done

ACTIVE=0
pidof exploit-android-key-ptr >/dev/null 2>&1 && ACTIVE=1
ps -A 2>/dev/null | grep -q '[e]xploit-android' && ACTIVE=1
if [ "$ACTIVE" = "1" ]; then
    ROOT_UID=$(sh "$D/root-command.sh" aWQgLXU= 2>/dev/null | tr -d ' \r\n' || true)
    if [ "$ROOT_UID" != "0" ]; then
        echo "CLEAN_98260_KEPT=rootd_active reason=no_working_root uid=${ROOT_UID:-unknown}"
        exit 3
    fi
    CLEAN_CMD=$(printf '%s' "rm -f $SOCK1 $SOCK2" | base64)
    sh "$D/root-command.sh" "$CLEAN_CMD" >/dev/null 2>&1 || {
        echo "CLEAN_98260_SOCKET_REMOVE_FAILED"
        exit 4
    }
    if [ -S "$SOCK1" ] || [ -S "$SOCK2" ]; then
        echo "CLEAN_98260_SOCKET_KEPT"
        exit 5
    fi
    echo "CLEAN_98260_ROOTD_SOFT_CLEANED=1"
else
    rm -f "$SOCK1" "$SOCK2" 2>/dev/null || true
fi
rm -rf "$D"
sync
echo CLEAN_OK
