#!/system/bin/sh

MODDIR=${0%/*}
APP_ID="io.github.xgl34222220.baize"
APK="$MODDIR/app/baize.apk"

# The module action always enters the native App; legacy WebUI assets are unsupported.
rm -rf "$MODDIR/webroot" "$MODDIR/webui" "$MODDIR/www" "$MODDIR/ksu-webui" 2>/dev/null || true

install_app() {
  [ -f "$APK" ] || return 1
  pm install -r -d --user 0 "$APK" >/dev/null 2>&1 && return 0
  pm install -r -d "$APK" >/dev/null 2>&1 && return 0

  if pm path "$APP_ID" >/dev/null 2>&1; then
    pm uninstall --user 0 "$APP_ID" >/dev/null 2>&1 || pm uninstall "$APP_ID" >/dev/null 2>&1
  fi
  pm install -d --user 0 "$APK" >/dev/null 2>&1 && return 0
  pm install -d "$APK" >/dev/null 2>&1 && return 0
  return 1
}

if ! pm path "$APP_ID" >/dev/null 2>&1; then
  install_app || {
    echo "白泽 App 未安装，且自动补装失败"
    exit 1
  }
fi

am force-stop "$APP_ID" >/dev/null 2>&1
am start -n "$APP_ID/.MiuixDashboardActivity" >/dev/null 2>&1 || monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
