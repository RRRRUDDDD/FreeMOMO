SKIPUNZIP=0

ui_print "- FreeMOMO Zygisk Companion"
ui_print "- Target: signature-validated Momo builds (arm64, API 28+)"

case "$ARCH" in
  arm64|arm64-v8a) ;;
  *) abort "! Unsupported architecture: ${ARCH:-unknown}; arm64 is required" ;;
esac

case "$API" in
  ''|*[!0-9]*) abort "! Unable to determine Android API level" ;;
esac

[ "$API" -ge 28 ] || abort "! Android API 28 or newer is required (found $API)"

LIBRARY="$MODPATH/zygisk/arm64-v8a.so"
[ -f "$LIBRARY" ] || abort "! Missing Zygisk arm64 library"

touch "$MODPATH/skip_mount" || abort "! Failed to create skip_mount"

set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
set_perm "$MODPATH/skip_mount" 0 0 0644

ui_print "- Installed module files; reboot is required to activate"
