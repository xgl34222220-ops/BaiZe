#!/system/bin/sh
# Canonical Magisk module root: /data/adb/modules/baize_v2; MODDIR remains portable for KernelSU and APatch.
MODDIR=${0%/*}
APP_ID=io.github.xgl34222220.baize
STATE_DIR=/data/adb/baize-v2
STATE="$STATE_DIR/module.env"
CONFIG="$STATE_DIR/config.conf"
rm -rf "$MODDIR/webroot" "$MODDIR/webui" "$MODDIR/www" "$MODDIR/ksu-webui" 2>/dev/null || true
mkdir -p "$STATE_DIR" "$STATE_DIR/logs" "$STATE_DIR/reports"
chmod 0700 "$STATE_DIR"
[ -f "$CONFIG" ] || cp -f "$MODDIR/config/default.conf" "$CONFIG" 2>/dev/null
chmod 0600 "$CONFIG" 2>/dev/null || true
count=0; while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$count" -lt 180 ]; do sleep 1; count=$((count+1)); done
install_result=missing
if [ -x "$MODDIR/app-installer.sh" ]; then sh "$MODDIR/app-installer.sh" ensure >/dev/null 2>&1; case $? in 0) install_result=ready;; 11) install_result=signature_mismatch;; *) install_result=failed;; esac; fi
version=$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null | tail -n 1)
version_code=$(sed -n 's/^versionCode=//p' "$MODDIR/module.prop" 2>/dev/null | tail -n 1)
root_framework=Magisk; [ -n "${KSU:-}" ] && root_framework=KernelSU; [ -n "${APATCH:-}" ] && root_framework=APatch
{
 echo "boot_epoch=$(date +%s)"; echo "app_installed=$(pm path "$APP_ID" >/dev/null 2>&1 && echo 1 || echo 0)"; echo "app_install_result=$install_result"; echo "app_version=$(dumpsys package "$APP_ID" 2>/dev/null | sed -n 's/.*versionName=//p' | head -n 1)"; echo "rules_ready=$([ -f "$MODDIR/config/deep.rules" ] && echo 1 || echo 0)"; echo "cleaner_ready=$([ -x "$MODDIR/cleaner.sh" ] && echo 1 || echo 0)"; echo "scheduler_ready=$([ -x "$MODDIR/scheduler.sh" ] && echo 1 || echo 0)"; echo "module_version=$version"; echo "module_version_code=$version_code"; echo "root_framework=$root_framework";
} >"$STATE.tmp" && mv -f "$STATE.tmp" "$STATE"
chmod 0600 "$STATE" 2>/dev/null || true
[ -x "$MODDIR/rules-validator.sh" ] && sh "$MODDIR/rules-validator.sh" >"$STATE_DIR/rules-validation.txt" 2>&1 || true
if [ -x "$MODDIR/supervisor.sh" ] && [ -x "$MODDIR/scheduler.sh" ]; then exec sh "$MODDIR/supervisor.sh"; fi
while true; do sleep 3600; done
