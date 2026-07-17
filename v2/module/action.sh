#!/system/bin/sh

MODDIR=${0%/*}
APP_ID="io.github.xgl34222220.baize"
APK="$MODDIR/app/baize.apk"

install_app() {
  [ -f "$APK" ] || return 1
  pm install -r -d --user 0 "$APK" >/dev/null 2>&1 && return 0
  pm install -r -d "$APK" >/dev/null 2>&1 && return 0
  return 1
}

if ! pm path "$APP_ID" >/dev/null 2>&1; then
  install_app || {
    echo "白泽 App 未安装，且自动补装失败"
    exit 1
  }
fi

am force-stop "$APP_ID" >/dev/null 2>&1
am start -n "$APP_ID/.MainActivity" >/dev/null 2>&1
