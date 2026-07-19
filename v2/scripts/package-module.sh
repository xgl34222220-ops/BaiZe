#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REPO=$(CDPATH= cd -- "$ROOT/.." && pwd)
OUT="$ROOT/dist"
MODULE="$ROOT/module"
STAGE="$ROOT/build/module-stage"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
NATIVE="$ROOT/build/native/arm64-v8a/baize_engine"
OUTPUT="$OUT/BaiZe-v2.0.4-Module.zip"

[ -f "$APK" ] || { echo "未找到已构建 APK：$APK" >&2; exit 1; }
[ -x "$NATIVE" ] || { echo "未找到 arm64 原生扫描器：$NATIVE" >&2; exit 1; }

rm -rf "$STAGE"
mkdir -p "$OUT" "$STAGE/app" "$STAGE/bin/arm64-v8a"
cp -a "$MODULE/." "$STAGE/"
# App-only package: never ship WebUI assets, including stale source directories.
rm -rf "$STAGE/webroot" "$STAGE/webui" "$STAGE/www" "$STAGE/ksu-webui"
cp -a "$REPO/config" "$STAGE/config"

# Ship dedicated cache, APK, deep/corpse snapshot executors behind one stable task router.
cp -f "$STAGE/cleaner42_6.sh" "$STAGE/cleaner.sh"
cp -f "$STAGE/native-scan.sh" "$STAGE/native-cleaner.sh"
cp -f "$STAGE/profile-snapshot-clean.sh" "$STAGE/profile-cleaner.sh"
cp -f "$STAGE/apk-snapshot-scan.sh" "$STAGE/apk-scanner.sh"
cp -f "$STAGE/apk-snapshot-clean.sh" "$STAGE/apk-cleaner.sh"
rm -f "$STAGE/cleaner42_6.sh" "$STAGE/native-scan.sh" "$STAGE/profile-snapshot-clean.sh" "$STAGE/apk-snapshot-scan.sh" "$STAGE/apk-snapshot-clean.sh" "$STAGE/cleaner.native.sh"

cp -f "$REPO/cleaner.sh" "$STAGE/cleaner.sh.compat"
cp -f "$REPO/notify.sh" "$STAGE/notify.sh"
cp -f "$REPO/service.sh" "$STAGE/scheduler.sh"
sed -i 's|STATE_DIR=/data/adb/safesweep|STATE_DIR=/data/adb/baize-v2|g' "$STAGE/cleaner.sh.compat" "$STAGE/scheduler.sh"
sed -i 's|\*safesweep\*cleaner.sh\*|*baize_v2*cleaner.sh*|g; s|\*safesweep\*job-runner.sh\*|*baize_v2*job-runner.sh*|g; s|\*safesweep\*webctl.sh\*|*baize_v2*webctl.sh*|g' "$STAGE/cleaner.sh.compat"

cp -f "$NATIVE" "$STAGE/bin/arm64-v8a/baize_engine"
chmod 0755 "$STAGE/cleaner.sh" "$STAGE/native-cleaner.sh" "$STAGE/cache-snapshot-clean.sh" "$STAGE/profile-cleaner.sh" "$STAGE/apk-scanner.sh" "$STAGE/apk-cleaner.sh"
chmod 0755 "$STAGE/cleaner.sh.compat" "$STAGE/bin/arm64-v8a/baize_engine"
chmod 0755 "$STAGE/notify.sh" "$STAGE/scheduler.sh" "$STAGE/service.sh" "$STAGE/action.sh"

cp -f "$APK" "$STAGE/app/baize.apk"
chmod 0644 "$STAGE/app/baize.apk"
sha256sum "$STAGE/app/baize.apk" | awk '{print $1}' > "$STAGE/app/baize.apk.sha256"
chmod 0644 "$STAGE/app/baize.apk.sha256"

rm -f "$OUTPUT"
(
  cd "$STAGE"
  zip -qr "$OUTPUT" .
)

unzip -tq "$OUTPUT" >/dev/null
unzip -l "$OUTPUT" | grep -q 'app/baize.apk'
unzip -l "$OUTPUT" | grep -q 'cleaner.sh'
unzip -l "$OUTPUT" | grep -q 'native-cleaner.sh'
unzip -l "$OUTPUT" | grep -q 'cache-snapshot-clean.sh'
unzip -l "$OUTPUT" | grep -q 'profile-cleaner.sh'
unzip -l "$OUTPUT" | grep -q 'apk-scanner.sh'
unzip -l "$OUTPUT" | grep -q 'apk-cleaner.sh'
unzip -l "$OUTPUT" | grep -q 'cleaner.sh.compat'
unzip -l "$OUTPUT" | grep -q 'bin/arm64-v8a/baize_engine'
unzip -l "$OUTPUT" | grep -q 'scheduler.sh'
unzip -l "$OUTPUT" | grep -q 'config/deep.rules'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'profile-cleaner.sh'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'cache-snapshot-clean.sh'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'apk-scanner.sh'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'apk-cleaner.sh'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'native-cleaner.sh'
unzip -p "$OUTPUT" module.prop | grep -q '^version=v2.0.4$'
unzip -p "$OUTPUT" module.prop | grep -q '^versionCode=22340$'
if unzip -Z1 "$OUTPUT" | grep -Eq '^(webroot|webui|www|ksu-webui)/'; then
  echo "模块包中不允许包含 WebUI 资源" >&2
  exit 1
fi
unzip -p "$OUTPUT" config/deep.rules | sha256sum | grep -q '^73d4c898630a292753adca33298c8aabbf6146debf414b2cabbe6b87d1d5c31c'

echo "已生成白泽 v2.0.4 状态栏安全区修正版模块：$OUTPUT"
