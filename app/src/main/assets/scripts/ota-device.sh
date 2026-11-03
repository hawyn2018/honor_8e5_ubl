#!/system/bin/sh
# No GPT, slot-selection, userdata or reboot operation belongs to this engine.
set -eu
RUN=$1; ACTION=$2; shift 2
BB="$RUN/tools/busybox"
WORKER="$RUN/partition_worker"
export LC_ALL=C
tool() { "$@" 2>/dev/null || "$BB" "$@"; }
die() { printf 'ERROR=%s\n' "$*" >&2; exit 1; }
hash() { tool sha256sum "$1" | "$BB" awk '{print $1}'; }
size() { "$BB" stat -c %s "$1"; }
safe_name() { case "$1" in ''|*[!A-Za-z0-9_.-]*) return 1;; esac; }
slot() {
    candidates="$(getprop ro.boot.slot_suffix) $(getprop ro.boot.slot)"
    for word in $(tool cat /proc/cmdline); do case "$word" in androidboot.slot_suffix=*) candidates="$candidates ${word#*=}";; esac; done
    if [ -r /proc/bootconfig ]; then candidates="$candidates $(tool awk '$1 == "androidboot.slot_suffix" {gsub(/\"/, "", $3); print $3}' /proc/bootconfig)"; fi
    answer=
    for value in $candidates; do
        case "$value" in a|_a) value=a;; b|_b) value=b;; *) die 'running slot invalid';; esac
        [ -z "$answer" ] || [ "$value" = "$answer" ] || die 'running slot evidence conflicts'
        answer=$value
    done
    [ -n "$answer" ] || die 'running slot absent'
    printf '%s\n' "$answer"
}
identity() {
    name=$1; safe_name "$name" || return 1
    found=; link=
    for base in /dev/block/by-name /dev/block/bootdevice/by-name; do
        candidate=$(tool readlink -f "$base/$name") || continue
        [ -b "$candidate" ] || continue
        case "$candidate" in /dev/block/*) :;; *) return 1;; esac
        [ -z "$found" ] || [ "$found" = "$candidate" ] || return 1
        found=$candidate; [ -n "$link" ] || link=$base/$name
    done
    [ -n "$found" ] || return 1
    node=${found##*/}; sys=$(tool readlink -f "/sys/class/block/$node") || return 1
    [ -f "$sys/partition" ] || return 1
    partname=$(tool awk -F= '$1 == "PARTNAME" {print $2}' "$sys/uevent") || return 1
    [ "$partname" = "$name" ] || return 1
    mm=$(tool cat "$sys/dev") || return 1
    bytes=$(tool blockdev --getsize64 "$link") || return 1
    start=$(tool cat "$sys/start") || return 1
    sector=$(tool cat "${sys%/*}/queue/logical_block_size") || return 1
    case "$mm:$bytes:$start:$sector" in *[!0-9:]*|*::*|:*) return 1;; esac
    [ "$bytes" -gt 0 ] && [ "$sector" -ge 512 ] || return 1
    printf '%s|%s|%s|%s|%s|%s|%s\n' "$name" "$link" "$found" "$mm" "$bytes" "$start" "$sector"
}
assert_directory() {
    [ -d "$1" ] && [ ! -L "$1" ] && [ "$("$BB" stat -c '%d:%i' "$1")" = "$2" ] || die 'backup directory identity changed'
}
assert_ram() {
    case "$1" in /dev/hnubl-ota-[0-9a-f-]*) :;; *) die 'RAM directory invalid';; esac
    [ -d "$1" ] && [ ! -L "$1" ] || die 'RAM directory missing'
    [ "$("$BB" stat -f -c %T "$1")" = tmpfs ] || die 'RAM workspace is not tmpfs'
}
[ "$(id -u)" = 0 ] || die 'root uid not verified'
case "$ACTION" in
    mkdir-backup)
        directory=$1
        case "$directory" in /storage/emulated/[0-9]*/Download/hnubl/hnubl_ota_backup_[0-9]*_[0-9]*) :;; *) die 'backup path invalid';; esac
        [ ! -e "$directory" ] && [ ! -L "$directory" ] || die 'backup directory already exists'
        parent=${directory%/*}; tool mkdir -p "$parent" || die 'backup parent creation failed'
        tool mkdir "$directory" || die 'backup directory creation failed'
        "$BB" stat -c '%d:%i' "$directory"
        ;;
    efisp-presence)
        # Presence is the partition name in ls output, independent of block-device numbering.
        entries=$(tool ls /dev/block/by-name) || die '读取 /dev/block/by-name 分区列表失败'
        present=0
        for entry in $entries; do
            if [ "$entry" = efisp ]; then present=1; break; fi
        done
        if [ "$present" = 1 ]; then printf 'EFISP=present\n'; else printf 'EFISP=absent\n'; fi
        ;;
    inventory)
        printf 'SLOT=%s\n' "$(slot)"
        for base in /dev/block/by-name /dev/block/bootdevice/by-name; do
            [ -d "$base" ] || continue
            for link in "$base"/*; do
                name=${link##*/}; safe_name "$name" || continue
                case "$name" in *_a|*_b|efisp) identity "$name" || die "partition identity failed: $name";; esac
            done
        done
        ;;
    read)
        expected=$1; name=${expected%%|*}
        [ "$(identity "$name")" = "$expected" ] || die 'source identity changed before direct read'
        oldifs=$IFS; IFS='|'; set -- $expected; IFS=$oldifs
        "$WORKER" read-range "$2" 0 "$5" || die 'partition direct read failed'
        [ "$(identity "$name")" = "$expected" ] || die 'source identity changed during direct read'
        ;;
    export)
        directory=$1; dirid=$2; source=$3; name=$4; expected=$5
        safe_name "$name" || die 'export filename invalid'
        assert_directory "$directory" "$dirid"
        [ "$(hash "$source")" = "$expected" ] || die 'export source hash changed'
        [ ! -e "$directory/$name" ] && [ ! -L "$directory/$name" ] || die 'export destination exists'
        tool cp "$source" "$directory/$name.partial" || die 'backup copy failed'
        tool sync || die 'backup sync failed'
        [ "$(hash "$directory/$name.partial")" = "$expected" ] || die 'backup hash mismatch'
        assert_directory "$directory" "$dirid"
        tool mv "$directory/$name.partial" "$directory/$name" || die 'backup publish failed'
        [ "$(hash "$directory/$name")" = "$expected" ] || die 'published backup hash mismatch'
        ;;
    ram-create)
        ram=$1
        case "$ram" in /dev/hnubl-ota-[0-9a-f-]*) :;; *) die 'RAM directory invalid';; esac
        [ ! -e "$ram" ] && [ ! -L "$ram" ] || die 'RAM path already exists'
        tool mkdir "$ram" || die 'RAM directory creation failed'
        assert_ram "$ram"
        tool chmod 700 "$ram"
        ;;
    pin)
        ram=$1; source=$2; name=$3; expected=$4
        assert_ram "$ram"; safe_name "$name" || die 'RAM filename invalid'
        [ ! -e "$ram/$name" ] || die 'RAM source already exists'
        [ "$(hash "$source")" = "$expected" ] || die 'source hash changed before RAM freeze'
        tool cp "$source" "$ram/$name" || die 'RAM source copy failed'
        [ "$(hash "$ram/$name")" = "$expected" ] && [ "$(size "$ram/$name")" = "$(size "$source")" ] || die 'RAM source mismatch'
        tool chmod 400 "$ram/$name"
        ;;
    ram-cleanup)
        ram=$1; assert_ram "$ram"
        for file in "$ram"/*; do
            [ -f "$file" ] && [ ! -L "$file" ] || die 'unexpected RAM entry'
            tool rm "$file" || die 'RAM file cleanup failed'
        done
        tool rmdir "$ram" || die 'RAM directory cleanup failed'
        ;;
    commit)
        ram=$1; planhash=$2; directory=$3; dirid=$4; running=$5; scope=${6:-full}
        case "$scope" in full|efisp-only) :;; *) die 'invalid commit scope';; esac
        writecount=0; efispbackup=0
        assert_ram "$ram"; assert_directory "$directory" "$dirid"
        [ "$(slot)" = "$running" ] || die 'running slot changed before commit'
        [ "$(hash "$ram/commit.plan")" = "$planhash" ] || die 'commit plan hash changed'
        # Preflight the whole transaction, including every frozen source, BEFORE setrw.
        while IFS='|' read -r kind name bytes expected sourcebytes beforehash targetidentity; do
            case "$kind" in
                BACKUP)
                    safe_name "$name" || die 'backup record name invalid'
                    [ "$name" != efisp.img ] || efispbackup=1
                    [ -f "$directory/$name" ] && [ ! -L "$directory/$name" ] || die 'required backup missing'
                    [ "$(size "$directory/$name")" = "$bytes" ] && [ "$(hash "$directory/$name")" = "$expected" ] || die 'required backup verification failed'
                    ;;
                SOURCE)
                    safe_name "$name" || die 'source record name invalid'
                    [ -f "$ram/$name" ] && [ ! -L "$ram/$name" ] || die 'frozen source absent'
                    [ "$(size "$ram/$name")" = "$bytes" ] && [ "$(hash "$ram/$name")" = "$expected" ] || die 'frozen source verification failed'
                    ;;
                WRITE)
                    writecount=$((writecount + 1))
                    [ "$scope" != efisp-only ] || [ "$name" = efisp ] || die 'efisp-only target rejected'
                    case "$name" in efisp) :;; *_a) [ "$running" = b ] || die 'active A write rejected';; *_b) [ "$running" = a ] || die 'active B write rejected';; *) die 'non AB write rejected';; esac
                    safe_name "$bytes" || die 'write source filename invalid'
                    [ "$(identity "$name")" = "$targetidentity" ] || die 'preflight target identity changed'
                    [ "$(hash "$ram/$bytes")" = "$expected" ] && [ "$(size "$ram/$bytes")" = "$sourcebytes" ] || die 'preflight write source mismatch'
                    oldifs=$IFS; IFS='|'; set -- $targetidentity; IFS=$oldifs
                    [ "$sourcebytes" -gt 0 ] && [ "$sourcebytes" -le "$5" ] || die 'preflight capacity exceeded'
                    ;;
                *) die 'commit plan record invalid';;
            esac
        done < "$ram/commit.plan"
        [ "$writecount" -gt 0 ] || die 'empty commit rejected'
        if [ "$scope" = efisp-only ]; then
            [ "$writecount" = 1 ] && [ "$efispbackup" = 1 ] || die 'efisp-only backup or write count invalid'
        fi
        while IFS='|' read -r kind name source sourcehash sourcebytes beforehash expected; do
            [ "$kind" = WRITE ] || continue
            case "$name" in efisp) :;; *_a) [ "$running" = b ] || die 'active A write rejected';; *_b) [ "$running" = a ] || die 'active B write rejected';; *) die 'non AB write rejected';; esac
            safe_name "$source" || die 'write source filename invalid'
            [ "$(slot)" = "$running" ] || die 'running slot changed'
            assert_directory "$directory" "$dirid"
            [ "$(identity "$name")" = "$expected" ] || die 'write target identity changed'
            [ "$(hash "$ram/$source")" = "$sourcehash" ] && [ "$(size "$ram/$source")" = "$sourcebytes" ] || die 'write source changed'
            oldifs=$IFS; IFS='|'; set -- $expected; IFS=$oldifs
            byname=$2; capacity=$5
            [ "$sourcebytes" -gt 0 ] && [ "$sourcebytes" -le "$capacity" ] || die 'source exceeds target capacity'
            "$WORKER" read-range "$byname" 0 "$capacity" > "$ram/prewrite.raw" || die 'prewrite direct read failed'
            [ "$(hash "$ram/prewrite.raw")" = "$beforehash" ] || die 'target content changed since backup'
            [ "$(identity "$name")" = "$expected" ] || die 'write target identity changed after preflight'
            printf 'WRITE_STARTED=%s\n' "$name" >> "$directory/ota-write-journal.log"
            # Access flag is changed only here, immediately before this exact write.
            tool blockdev --setrw "$byname" || die 'blockdev setrw failed'
            [ "$(identity "$name")" = "$expected" ] || die 'write target changed after setrw'
            if command -v dd >/dev/null 2>&1; then dd if="$ram/$source" of="$byname" bs=4096 conv=fsync
            else "$BB" dd if="$ram/$source" of="$byname" bs=4096 conv=fsync; fi
            "$WORKER" read-range "$byname" 0 "$sourcebytes" > "$ram/readback.raw" || die 'writeback O_DIRECT read failed'
            [ "$(hash "$ram/readback.raw")" = "$sourcehash" ] || die 'writeback SHA256 mismatch'
            [ "$(identity "$name")" = "$expected" ] || die 'writeback target identity changed'
            printf 'WRITE_VERIFIED=%s\n' "$name" | "$BB" tee -a "$directory/ota-write-journal.log"
            tool sync || die 'write journal sync failed'
        done < "$ram/commit.plan"
        printf 'RESULT=success\n'
        ;;
    *) die 'unknown OTA action';;
esac
