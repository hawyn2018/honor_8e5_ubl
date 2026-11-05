#!/system/bin/sh
case "$ARCH" in
  arm64) ;;
  *) abort "This payload needs the Android arm64 ABI." ;;
esac
ui_print "- hnubl OTA companion 3.0.6"
ui_print "- Runtime is bundled; no Zygisk/LSPosed dependency"
ui_print "- Activation restarts the updater only if it is already running"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/frida-inject" 0 0 0700
set_perm "$MODPATH/service.sh" 0 0 0700
set_perm "$MODPATH/uninstall.sh" 0 0 0700
set_perm "$MODPATH/webui-control.sh" 0 0 0700
