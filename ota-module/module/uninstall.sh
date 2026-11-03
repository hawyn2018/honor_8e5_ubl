#!/system/bin/sh
STATE=/data/adb/hnubl
if [ -f "$STATE/service.pid" ]; then
  read -r pid < "$STATE/service.pid"
  case "$pid" in *[!0-9]*|'') ;;
    *) if [ -r "/proc/$pid/cmdline" ] && tr '\000' ' ' < "/proc/$pid/cmdline" | grep -q 'net.hawyn.hnubl.module.ModuleMain'; then kill -TERM "$pid"; fi;;
  esac
fi
# An eternalized agent is scoped to this app process. Restarting this app unloads it.
am force-stop com.hihonor.ouc
