#!/system/bin/sh
# Fixed WebUI operations. Never source settings or evaluate caller-provided text.
umask 077
MODDIR=${0%/*}
MODDIR=$(cd "$MODDIR" && pwd -P) || { printf '%s\n' '{"ok":false,"error":"Module directory is unavailable"}'; exit 1; }
if [ "$(id -u)" != 0 ]; then
  printf '%s\n' '{"ok":false,"error":"Root access is required"}'
  exit 1
fi
if ! mkdir -p /data/adb/hnubl || ! chmod 700 /data/adb/hnubl; then
  printf '%s\n' '{"ok":false,"error":"Module settings directory is unavailable"}'
  exit 1
fi
cli() {
  CLASSPATH="$MODDIR/broker.jar" /system/bin/app_process /system/bin \
    net.hawyn.hnubl.module.ModuleOptionsMain "$@"
}
case "${1:-}" in
  status)
    [ "$#" = 1 ] || { printf '%s\n' '{"ok":false,"error":"Unexpected arguments"}'; exit 1; }
    cli status
    ;;
  save)
    [ "$#" = 2 ] || { printf '%s\n' '{"ok":false,"error":"Setting must be 0 or 1"}'; exit 1; }
    case "$2" in 0|1) cli save "$2";; *) printf '%s\n' '{"ok":false,"error":"Setting must be 0 or 1"}'; exit 1;; esac
    ;;
  apply)
    [ "$#" = 1 ] || { printf '%s\n' '{"ok":false,"error":"Unexpected arguments"}'; exit 1; }
    # The established activation path replaces only the updater controller and
    # force-stops/reopens com.hihonor.ouc. It never reboots the phone.
    if ! /system/bin/sh "$MODDIR/service.sh" --activate; then
      printf '%s\n' '{"ok":false,"error":"模块重新激活失败，请查看模块状态后重试"}'
      exit 1
    fi
    cli status
    ;;
  *) printf '%s\n' '{"ok":false,"error":"Unknown operation"}'; exit 1;;
esac
