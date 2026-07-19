#!/system/bin/sh

APP_ID="io.github.xgl34222220.baize"
STATE_DIR="/data/adb/baize-v2"

pm uninstall "$APP_ID" >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2' >/dev/null 2>&1 || true
pkill -f '/data/adb/baize-v2' >/dev/null 2>&1 || true
rm -rf "$STATE_DIR"
