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

# 与打包时写入的期望校验值比对。HASH_FILE 此前只声明未使用，
# 下面的 saved_hash 比较是"和上次安装的是否一致"，不是完整性校验。
if [ -f "$HASH_FILE" ]; then
  expected_hash=$(tr -d ' \t\r\n' <"$HASH_FILE")
  if [ -n "$expected_hash" ] && [ "$expected_hash" != "$bundle_hash" ]; then
    write_result failed apk_integrity_mismatch
    echo "内置 App 校验失败，模块包可能已损坏或被篡改" >&2
    exit 13
  fi
else
  write_result failed apk_hash_file_missing
  echo "缺少内置 App 校验文件，拒绝安装" >&2
  exit 13
fi

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
# Keep PackageManager's downgrade and signature checks; preserve its actual failure reason.
install_output=$(pm install -r --user 0 "$APK" 2>&1)
install_code=$?
case "$install_output" in
  *"Unknown option"*|*"unknown option"*)
    install_output=$(pm install -r "$APK" 2>&1)
    install_code=$?
    ;;
esac
if [ "$install_code" = 0 ]; then
  printf '%s\n' "$bundle_hash" >"$INSTALLED_HASH"
  chmod 0600 "$INSTALLED_HASH" 2>/dev/null || true
  write_result updated installed_or_updated
  exit 0
fi
# Never uninstall automatically. A full disk or downgrade is not a signature mismatch.
case "$install_output" in
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES*)
    write_result signature_mismatch preserved_existing_app
    echo "App 签名不兼容，已保留现有 App；请手动确认后处理" >&2
    exit 11
    ;;
  *INSTALL_FAILED_VERSION_DOWNGRADE*) reason=version_downgrade_blocked ;;
  *INSTALL_FAILED_INSUFFICIENT_STORAGE*) reason=insufficient_storage ;;
  *) reason=install_failed ;;
esac
write_result failed "$reason"
printf 'App 安装失败（%s），现有 App 保持不变\n' "$reason" >&2
exit 12
