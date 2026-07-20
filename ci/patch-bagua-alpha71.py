from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "work")
installer = root / "module-src/scripts/install-app.sh"
s = installer.read_text()

start = s.index("#!/system/bin/sh")
new_installer = r'''#!/system/bin/sh
MODDIR=${0%/*}/..
MODDIR=$(cd "$MODDIR" 2>/dev/null && pwd)
PERSIST=/data/adb/bagua
APK="$MODDIR/app/bagua.apk"
PKG=io.github.xgl34222220.bagua
ACTIVITY=io.github.xgl34222220.bagua/.MainActivity
EXPECTED_VERSION=0.7.1-alpha.7.1
EXPECTED_CODE=111

mkdir -p "$PERSIST" "$PERSIST/logs"
LOG="$PERSIST/logs/app-installer.log"

log() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG"; }
file_hash() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; return; fi
  for bb in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
    [ -x "$bb" ] || continue
    "$bb" sha256sum "$1" | awk '{print $1}'; return
  done
}
installed_code() {
  dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1
}
installed_name() {
  dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*versionName=\([^[:space:]]*\).*/\1/p' | head -n 1
}
install_once() {
  pm install -r -d -g "$APK" 2>&1
}

[ -s "$APK" ] || { log "APK missing: $APK"; echo 'ERROR=apk_missing'; exit 1; }
NEW_HASH=$(file_hash "$APK")
OLD_HASH=$(cat "$PERSIST/app.sha256" 2>/dev/null)
CURRENT_CODE=$(installed_code)

if [ "$CURRENT_CODE" = "$EXPECTED_CODE" ] && [ -n "$NEW_HASH" ] && [ "$NEW_HASH" = "$OLD_HASH" ]; then
  echo 'RESULT=app_current'
else
  log "Installing bundled app version=$EXPECTED_VERSION code=$EXPECTED_CODE current=${CURRENT_CODE:-none}"
  OUT=$(install_once)
  RC=$?
  printf '%s\n' "$OUT" >> "$LOG"

  if [ "$RC" -ne 0 ] && printf '%s\n' "$OUT" | grep -Eqi 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match|inconsistent certificates'; then
    log "Signature conflict detected; reinstalling only BaGua App"
    pm uninstall "$PKG" >> "$LOG" 2>&1 || true
    OUT=$(pm install -r -d -g "$APK" 2>&1)
    RC=$?
    printf '%s\n' "$OUT" >> "$LOG"
  fi

  [ "$RC" -eq 0 ] || { echo 'ERROR=app_install_failed'; printf '%s\n' "$OUT"; exit "$RC"; }

  VERIFIED_CODE=$(installed_code)
  VERIFIED_NAME=$(installed_name)
  if [ "$VERIFIED_CODE" != "$EXPECTED_CODE" ]; then
    log "Version verification failed expected=$EXPECTED_CODE actual=${VERIFIED_CODE:-missing} name=${VERIFIED_NAME:-missing}"
    echo 'ERROR=app_version_mismatch'
    echo "EXPECTED_CODE=$EXPECTED_CODE"
    echo "INSTALLED_CODE=${VERIFIED_CODE:-missing}"
    echo "INSTALLED_VERSION=${VERIFIED_NAME:-missing}"
    exit 2
  fi

  [ -n "$NEW_HASH" ] && printf '%s\n' "$NEW_HASH" > "$PERSIST/app.sha256"
  printf '%s\n' "$VERIFIED_CODE" > "$PERSIST/app.versionCode"
  printf '%s\n' "$VERIFIED_NAME" > "$PERSIST/app.versionName"
  rm -f "$PERSIST/app-install.pending"
  echo 'RESULT=app_installed'
  echo "APP_VERSION=$VERIFIED_NAME"
  echo "APP_VERSION_CODE=$VERIFIED_CODE"
fi

case "$1" in
  launch)
    am start -n "$ACTIVITY" >/dev/null 2>&1 || monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    ;;
esac
exit 0
'''
installer.write_text(new_installer)

for path in root.rglob("*"):
    if not path.is_file() or path.suffix.lower() in {".apk", ".zip", ".png", ".jpg", ".jpeg", ".webp"}:
        continue
    try:
        text = path.read_text()
    except UnicodeDecodeError:
        continue
    changed = text.replace("0.7.0-alpha.7", "0.7.1-alpha.7.1")
    changed = changed.replace("versionCode = 110", "versionCode = 111")
    changed = changed.replace("versionCode=110", "versionCode=111")
    if changed != text:
        path.write_text(changed)

changelog = root / "module-src/CHANGELOG.md"
changelog.write_text(
    "# 更新日志\n\n"
    "## 0.7.1-alpha.7.1\n\n"
    "- 修复测试构建签名变化时旧版八卦 App 无法被覆盖的问题\n"
    "- 检测到签名冲突后，仅卸载并重装八卦 App，模块规则和配置不受影响\n"
    "- 安装后强制校验实际 versionCode，避免模块已更新但 App 仍是旧版\n"
    "- 保留 Alpha 7 持久 Root 桥接架构\n\n"
    + changelog.read_text()
)
print("patched Alpha 7.1")
