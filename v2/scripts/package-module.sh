#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REPO=$(CDPATH= cd -- "$ROOT/.." && pwd)
OUT="$ROOT/dist"
MODULE="$ROOT/module"
STAGE="$ROOT/build/module-stage"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
NATIVE="$ROOT/build/native/arm64-v8a/baize_engine"
OUTPUT="$OUT/BaiZe-v2.3.0-Module.zip"

[ -f "$APK" ] || { echo "未找到已构建 APK：$APK" >&2; exit 1; }
[ -x "$NATIVE" ] || { echo "未找到 arm64 原生扫描器：$NATIVE" >&2; exit 1; }

rm -rf "$STAGE"
mkdir -p "$OUT" "$STAGE/app" "$STAGE/bin/arm64-v8a"
cp -a "$MODULE/." "$STAGE/"
rm -rf "$STAGE/webroot" "$STAGE/webui" "$STAGE/www" "$STAGE/ksu-webui"
cp -a "$REPO/config" "$STAGE/config"

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
chmod 0755 "$STAGE/storage-index.sh" "$STAGE/task-worker.sh" "$STAGE/organizer-worker.sh"
chmod 0755 "$STAGE/cleaner.sh" "$STAGE/native-cleaner.sh" "$STAGE/cache-snapshot-clean.sh" "$STAGE/cache-transaction.sh" "$STAGE/one-pass-scan.sh" "$STAGE/profile-cleaner.sh" "$STAGE/apk-scanner.sh" "$STAGE/apk-cleaner.sh"
chmod 0755 "$STAGE/cleaner.sh.compat" "$STAGE/bin/arm64-v8a/baize_engine"
chmod 0755 "$STAGE/notify.sh" "$STAGE/scheduler.sh" "$STAGE/service.sh" "$STAGE/action.sh"
chmod 0755 "$STAGE/rules-validator.sh" "$STAGE/quarantine-manager.sh" "$STAGE/large-file-scanner.sh" "$STAGE/duplicate-scanner.sh" "$STAGE/storage-analyzer.sh" "$STAGE/diagnostics-export.sh" "$STAGE/app-installer.sh" "$STAGE/supervisor.sh" "$STAGE/worker-runner.sh" "$STAGE/task-worker.sh" "$STAGE/organizer-worker.sh"

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
unzip -l "$OUTPUT" | grep -q 'cache-transaction.sh'
unzip -l "$OUTPUT" | grep -q 'one-pass-scan.sh'
unzip -l "$OUTPUT" | grep -q 'storage-index.sh'
unzip -l "$OUTPUT" | grep -q 'task-worker.sh'
unzip -l "$OUTPUT" | grep -q 'organizer-worker.sh'
unzip -p "$OUTPUT" task-worker.sh | grep -q 'detached-root-shell'
unzip -p "$OUTPUT" task-worker.sh | grep -q 'organize'
unzip -p "$OUTPUT" organizer-worker.sh | grep -q 'organizer-result.env'
unzip -p "$OUTPUT" organizer-worker.sh | grep -q 'operation=module-organize'
unzip -p "$OUTPUT" organizer-worker.sh | grep -q 'build_fallback_index'
unzip -l "$OUTPUT" | grep -q 'profile-cleaner.sh'
unzip -l "$OUTPUT" | grep -q 'apk-scanner.sh'
unzip -l "$OUTPUT" | grep -q 'apk-cleaner.sh'
unzip -l "$OUTPUT" | grep -q 'cleaner.sh.compat'
unzip -l "$OUTPUT" | grep -q 'bin/arm64-v8a/baize_engine'
unzip -l "$OUTPUT" | grep -q 'scheduler.sh'
unzip -l "$OUTPUT" | grep -q 'config/deep.rules'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'profile-cleaner.sh'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'cache-snapshot-clean.sh'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'cache-transaction.sh'
unzip -p "$OUTPUT" one-pass-scan.sh | grep -q 'scan-external-one-pass'
unzip -p "$OUTPUT" storage-index.sh | grep -q 'Android/media'
unzip -p "$OUTPUT" storage-index.sh | grep -q 'Android/data'
unzip -p "$OUTPUT" storage-index.sh | grep -q 'QQfile_recv'
unzip -p "$OUTPUT" storage-index.sh | grep -q 'nu.gpu.nagramx\|Android/data'
unzip -p "$OUTPUT" one-pass-scan.sh | grep -q 'one_pass_app_dirs'
unzip -p "$OUTPUT" one-pass-scan.sh | grep -q 'whitelist_index_queries'
unzip -p "$OUTPUT" one-pass-scan.sh | grep -q 'pruned_subtrees'
unzip -p "$OUTPUT" one-pass-scan.sh | grep -q 'BAIZE_ROOT_WORKERS'
unzip -p "$OUTPUT" one-pass-scan.sh | grep -q 'parallel_overlap_milli'
unzip -p "$OUTPUT" config/default.conf | grep -q '^scan_root_workers=0$'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'apk-scanner.sh'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'apk-cleaner.sh'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'native-cleaner.sh'
unzip -p "$OUTPUT" apk-scanner.sh | grep -q 'apk-snapshot-v2.2-shared-index'
unzip -p "$OUTPUT" apk-scanner.sh | grep -q 'storage-files.nul'
unzip -p "$OUTPUT" module.prop | grep -q '^version=v2.3.0$'
unzip -p "$OUTPUT" module.prop | grep -q '^versionCode=23000$'
if unzip -Z1 "$OUTPUT" | grep -Eq '^(webroot|webui|www|ksu-webui)/'; then
  echo "模块包中不允许包含 WebUI 资源" >&2
  exit 1
fi
unzip -p "$OUTPUT" config/deep.rules | sha256sum | grep -q '^73d4c898630a292753adca33298c8aabbf6146debf414b2cabbe6b87d1d5c31c'
unzip -p "$OUTPUT" cache-snapshot-clean.sh | grep -q 'clean-cache-snapshot'
if unzip -p "$OUTPUT" cache-snapshot-clean.sh | grep -Eq 'find[[:space:]].*cache|xargs[[:space:]].*rm'; then
  echo "缓存快照清理器不得重新枚举目录生成删除名单" >&2
  exit 1
fi
echo "已生成白泽 v2.3.0 稳定化模块：$OUTPUT"
