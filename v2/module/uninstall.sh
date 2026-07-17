#!/system/bin/sh

APP_ID="io.github.xgl34222220.baize"
pm uninstall "$APP_ID" >/dev/null 2>&1 || true
rm -rf /data/adb/baize-v2
