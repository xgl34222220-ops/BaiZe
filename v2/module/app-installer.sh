#!/system/bin/sh
set -u
MODDIR=${0%/*}
APP_ID=${BAIZE_APP_ID:-io.github.xgl34222220.baize}
APK=${BAIZE_APK:-$MODDIR/app/baize.apk}
HASH_FILE=${BAIZE_HASH_FILE:-$MODDIR/app/baize.apk.sha256}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
INSTALLED_HASH="$STATE_DIR/installed-app.sha256"
RESULT_FILE="$STATE_DIR/app-install.env"
MODE=${1:-ensure}
mkdir -p "$STATE_DIR"

apk_hash() { [ -f "$1" ] && sha256sum "$1" 2>/dev/null | awk 'NR==1{print $1}'; }
installed_version() { dumpsys package "$APP_ID" 2>/dev/null | sed -n 's/.*versionName=//p' | head -n 1; }
write_result() {
  status=$1 reason=$2
  tmp="$RESULT_FILE.tmp.$$"
  {
    echo "status=$status"
    echo "reason=$reason"
    echo "time=$(date '+%Y-%m-%d %H:%M:%S')"
    echo "installed=$(pm path "$APP_ID" >/dev/null 2>&1 && echo 1 || echo 0)"
    echo "installed_version=$(installed_version)"
    echo "bundle_hash=$(apk_hash "$APK")"
  } >"$tmp" && mv -f "$tmp" "$RESULT_FILE"
  chmod 0600 "$RESULT_FILE" 2>/dev/null || true
}

[ -f "$APK" ] || { write_result failed apk_missing; echo "内置 App 缺失" >&2; exit 5; }
bundle_hash=$(apk_hash "$APK")
[ -n "$bundle_hash" ] || { write_result failed hash_failed; exit 5; }
installed=0
pm path "$APP_ID" >/dev/null 2>&1 && installed=1
saved_hash=$(sed -n '1p' "$INSTALLED_HASH" 2>/dev/null | tr -d '\r\n ')
if [ "$MODE" = check ]; then
  [ "$installed" = 1 ] && [ "$saved_hash" = "$bundle_hash" ] && { write_result current current; exit 0; }
  write_result outdated version_or_hash_mismatch
  exit 10
fi
if [ "$installed" = 1 ] && [ "$saved_hash" = "$bundle_hash" ]; then
  write_result current current
  exit 0
fi
if pm install -r -d --user 0 "$APK" >/dev/null 2>&1 || pm install -r -d "$APK" >/dev/null 2>&1; then
  printf '%s\n' "$bundle_hash" >"$INSTALLED_HASH"
  chmod 0600 "$INSTALLED_HASH" 2>/dev/null || true
  write_result updated installed_or_updated
  exit 0
fi
# Never uninstall automatically: a signature mismatch must stay visible and recoverable.
if [ "$installed" = 1 ]; then
  write_result signature_mismatch preserved_existing_app
  echo "App 签名不兼容，已保留现有 App；请手动确认后处理" >&2
  exit 11
fi
write_result failed install_failed
echo "App 安装失败" >&2
exit 12
