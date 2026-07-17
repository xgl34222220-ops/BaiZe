#!/system/bin/sh

MODDIR=${0%/*}
APP_ID="io.github.xgl34222220.baize"
STATE_DIR="/data/adb/baize-v2"
STATE="$STATE_DIR/module.env"
APK="$MODDIR/app/baize.apk"
HASH_FILE="$MODDIR/app/baize.apk.sha256"
INSTALLED_HASH="$STATE_DIR/installed-app.sha256"
CONFIG="$STATE_DIR/config.conf"

mkdir -p "$STATE_DIR" "$STATE_DIR/logs" "$STATE_DIR/reports"
chmod 0700 "$STATE_DIR"
[ -f "$CONFIG" ] || cp -f "$MODDIR/config/default.conf" "$CONFIG" 2>/dev/null
chmod 0600 "$CONFIG" 2>/dev/null

wait_boot() {
  count=0
  while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$count" -lt 180 ]; do
    sleep 1
    count=$((count + 1))
  done
}

INSTALL_MODE="updated"
install_app() {
  [ -f "$APK" ] || return 1
  INSTALL_MODE="updated"
  pm install -r -d --user 0 "$APK" >/dev/null 2>&1 && return 0
  pm install -r -d "$APK" >/dev/null 2>&1 && return 0

  INSTALL_MODE="reinstalled"
  if pm path "$APP_ID" >/dev/null 2>&1; then
    pm uninstall --user 0 "$APP_ID" >/dev/null 2>&1 || pm uninstall "$APP_ID" >/dev/null 2>&1
  fi
  pm install -d --user 0 "$APK" >/dev/null 2>&1 && return 0
  pm install -d "$APK" >/dev/null 2>&1 && return 0
  return 1
}

bundle_hash=""
[ -f "$HASH_FILE" ] && bundle_hash=$(tr -d '\r\n ' < "$HASH_FILE")
installed_hash=""
[ -f "$INSTALLED_HASH" ] && installed_hash=$(tr -d '\r\n ' < "$INSTALLED_HASH")

wait_boot

need_install=0
pm path "$APP_ID" >/dev/null 2>&1 || need_install=1
[ -n "$bundle_hash" ] && [ "$bundle_hash" != "$installed_hash" ] && need_install=1

install_result="unchanged"
if [ "$need_install" = "1" ]; then
  if install_app; then
    install_result="$INSTALL_MODE"
    [ -n "$bundle_hash" ] && printf '%s\n' "$bundle_hash" > "$INSTALLED_HASH"
    chmod 0600 "$INSTALLED_HASH"
  else
    install_result="failed"
  fi
fi

app_installed=0
pm path "$APP_ID" >/dev/null 2>&1 && app_installed=1
app_version=$(dumpsys package "$APP_ID" 2>/dev/null | sed -n 's/.*versionName=//p' | head -n 1)
rules_ready=0
[ -f "$MODDIR/config/deep.rules" ] && rules_ready=1
cleaner_ready=0
[ -x "$MODDIR/cleaner.sh" ] && cleaner_ready=1
scheduler_ready=0
[ -x "$MODDIR/scheduler.sh" ] && scheduler_ready=1

{
  echo "boot_epoch=$(date +%s)"
  echo "app_installed=$app_installed"
  echo "app_install_result=$install_result"
  echo "app_version=$app_version"
  echo "rules_ready=$rules_ready"
  echo "cleaner_ready=$cleaner_ready"
  echo "scheduler_ready=$scheduler_ready"
  echo "module_version=2.0.0-alpha09"
} > "$STATE.tmp"
mv -f "$STATE.tmp" "$STATE"
chmod 0600 "$STATE"

# The module process becomes the real recurring scheduler. It evaluates all saved power, screen,
# charging, idle, battery, temperature, interval and daily-time gates before calling cleaner.sh.
if [ "$scheduler_ready" = "1" ] && [ "$cleaner_ready" = "1" ]; then
  exec sh "$MODDIR/scheduler.sh"
fi

while true; do
  sleep 3600
done
