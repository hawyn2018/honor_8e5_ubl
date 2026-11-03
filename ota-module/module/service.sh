#!/system/bin/sh
umask 077
MODDIR=${0%/*}
MODDIR=$(cd "$MODDIR" && pwd -P) || exit 1
STATE=/data/adb/hnubl
mkdir -p "$STATE" || exit 1
chmod 700 "$STATE" || exit 1
if [ ! -x /data/adb/ksud ]; then
  echo 'KernelSU policy loader is unavailable' >&2; exit 1
fi
/data/adb/ksud sepolicy apply "$MODDIR/sepolicy.rule" >&2 || exit 1
action=boot
[ "${1:-}" = --activate ] && action=activate
if [ "$action" = boot ]; then
  # PackageManager and application data must be available for UID/signature binding.
  while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done
fi
# Serialize activation requests, never kill the broker that may be carrying one.
if ! mkdir "$STATE/activation.lock" 2>/dev/null; then
  lockpid=
  [ -f "$STATE/activation.lock/pid" ] && read -r lockpid < "$STATE/activation.lock/pid"
  case "$lockpid" in *[!0-9]*|'') ;;
    *) if [ -r "/proc/$lockpid/cmdline" ] && tr '\000' ' ' < "/proc/$lockpid/cmdline" | grep -q "$MODDIR/service.sh"; then
         echo 'Another module activation is in progress' >&2; exit 1
       fi;;
  esac
  rm -f "$STATE/activation.lock/pid"
  rmdir "$STATE/activation.lock" || exit 1
  mkdir "$STATE/activation.lock" || exit 1
fi
printf '%s' "$$" > "$STATE/activation.lock/pid"
trap 'rm -f "$STATE/activation.lock/pid"; rmdir "$STATE/activation.lock" 2>/dev/null' EXIT
id=$(cat /proc/sys/kernel/random/uuid) || exit 1
case "$id" in *[!A-Za-z0-9-]*|'') exit 1;; esac
# Capture before publishing the new request: disposing the old controller can
# force-stop its previously attached OUC process. Do not decide from that later
# empty process list, or a running updater would never be reopened after Apply.
active=false
if [ "$action" = activate ]; then
  active=$(CLASSPATH="$MODDIR/broker.jar" /system/bin/app_process /system/bin \
    net.hawyn.hnubl.module.ModuleOptionsMain updater-running) || exit 1
  case "$active" in true|false) ;; *) echo 'Updater process snapshot failed' >&2; exit 1;; esac
fi
# Take an activation-bound settings snapshot before asking the resident VM to
# replace its controller. A valid saved choice survives upgrades; old or invalid
# settings start with force-current-version disabled.
CLASSPATH="$MODDIR/broker.jar" /system/bin/app_process /system/bin \
  net.hawyn.hnubl.module.ModuleOptionsMain prepare "$id" >/dev/null || exit 1
printf '%s' "$id" > "$STATE/activation.id.tmp" || exit 1
mv -f "$STATE/activation.id.tmp" "$STATE/activation.id" || exit 1
printf '{"hook_ready":false,"critical_ready":false,"broker_ready":false,"controller_ready":false,"updater_running":false,"waiting_for_updater":false,"activation_id":"%s","error":"","phase":"Activation pending","warnings":[]}' "$id" > "$STATE/status.json.tmp" || exit 1
mv -f "$STATE/status.json.tmp" "$STATE/status.json" || exit 1
printf '{"activation_id":"%s","module_dir":"%s","activate":%s}' "$id" "$MODDIR" "$active" > "$STATE/activation.request.tmp" || exit 1
mv -f "$STATE/activation.request.tmp" "$STATE/activation.request" || exit 1
alive=false
if [ -f "$STATE/service.pid" ]; then
  read -r pid < "$STATE/service.pid"
  case "$pid" in *[!0-9]*|'') ;;
    *) if [ -r "/proc/$pid/cmdline" ] && tr '\000' ' ' < "/proc/$pid/cmdline" | grep -q 'net.hawyn.hnubl.module.ModuleMain'; then alive=true; fi;;
  esac
fi
if [ "$alive" != true ]; then
  mkdir -p "$STATE/runtime" || exit 1
  chmod 700 "$STATE/runtime" || exit 1
  # Never truncate the mapped base DEX when reinstalling a live module.
  cp "$MODDIR/broker.jar" "$STATE/runtime/.root-api-v1.jar.new" || exit 1
  chmod 600 "$STATE/runtime/.root-api-v1.jar.new" || exit 1
  mv -f "$STATE/runtime/.root-api-v1.jar.new" "$STATE/runtime/root-api-v1.jar" || exit 1
  CLASSPATH="$STATE/runtime/root-api-v1.jar" /system/bin/app_process /system/bin net.hawyn.hnubl.module.ModuleMain >> "$STATE/service.log" 2>&1 &
fi
# An existing VM intentionally keeps its ROOT listener and accepted commands.
# The freshly snapshotted controller borrows that VM's one marker transaction
# object and upgrades only the OTA endpoint, including its mount-namespace view.
echo "hnubl OTA activation requested: $id" >&2
