#!/system/bin/sh
MODDIR=${0%/*}
APP_ID=io.github.xgl34222220.baize
rm -rf "$MODDIR/webroot" "$MODDIR/webui" "$MODDIR/www" "$MODDIR/ksu-webui" 2>/dev/null || true
if [ -x "$MODDIR/app-installer.sh" ]; then sh "$MODDIR/app-installer.sh" ensure >/dev/null 2>&1 || true; fi
pm path "$APP_ID" >/dev/null 2>&1 || { echo "白泽 App 未安装，请查看 /data/adb/baize-v2/app-install.env"; exit 1; }
am start -n "$APP_ID/.MiuixDashboardActivity" >/dev/null 2>&1 || monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
