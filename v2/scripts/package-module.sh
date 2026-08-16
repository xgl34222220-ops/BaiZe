#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REPO=$(CDPATH= cd -- "$ROOT/.." && pwd)
OUT="$ROOT/dist"
MODULE="$ROOT/module"
STAGE="$ROOT/build/module-stage"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
NATIVE_DIR="$ROOT/build/native"
OUTPUT="$OUT/BaiZe-v2.6.2-Module.zip"

[ -f "$APK" ] || { echo "未找到已构建 APK：$APK" >&2; exit 1; }
# arm64 是必须产物；其余 ABI 有就打进去，没有就跳过。
[ -x "$NATIVE_DIR/arm64-v8a/baize_engine" ] || { echo "未找到 arm64 原生扫描器" >&2; exit 1; }
[ -x "$NATIVE_DIR/arm64-v8a/baize_deep_snapshot" ] || { echo "未找到 arm64 深度不可变快照引擎" >&2; exit 1; }

# 打包前跑全量回归，而不是手工列举五个测试。
bash "$ROOT/tests/run-all.sh"

rm -rf "$STAGE"
mkdir -p "$OUT" "$STAGE/app"
cp -a "$MODULE/." "$STAGE/"
rm -rf "$STAGE/webroot" "$STAGE/webui" "$STAGE/www" "$STAGE/ksu-webui"
cp -a "$REPO/config" "$STAGE/config"

cp -f "$STAGE/cleaner42_6.sh" "$STAGE/cleaner.sh"
cp -f "$STAGE/native-scan.sh" "$STAGE/native-cleaner.sh"
cp -f "$STAGE/profile-snapshot-clean-fast.sh" "$STAGE/profile-cleaner.sh"
cp -f "$STAGE/apk-snapshot-scan.sh" "$STAGE/apk-scanner.sh"
cp -f "$STAGE/apk-snapshot-clean.sh" "$STAGE/apk-cleaner.sh"
cp -f "$STAGE/scheduler-v2.5.sh" "$STAGE/scheduler.sh"
rm -f "$STAGE/cleaner42_6.sh" "$STAGE/native-scan.sh" "$STAGE/profile-snapshot-clean.sh" "$STAGE/profile-snapshot-clean-fast.sh" "$STAGE/apk-snapshot-scan.sh" "$STAGE/apk-snapshot-clean.sh" "$STAGE/cleaner.native.sh" "$STAGE/scheduler-v2.5.sh"

# 兼容清理引擎直接拷贝，不再做构建期 sed 改写。
# STATE_DIR 与 MODULE_TAG 已改为环境变量注入且默认值即 v2 的取值，
# 源码与打包产物行为一致，本地可直接复现。
cp -f "$REPO/cleaner.sh" "$STAGE/cleaner.sh.compat"
cp -f "$REPO/notify.sh" "$STAGE/notify.sh"
grep -q 'STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}' "$STAGE/cleaner.sh.compat" || {
  echo "兼容引擎未使用 v2 状态目录默认值，拒绝打包" >&2
  exit 1
}
grep -q 'MODULE_TAG=${BAIZE_MODULE_TAG:-baize_v2}' "$STAGE/cleaner.sh.compat" || {
  echo "兼容引擎未使用 v2 模块标识默认值，拒绝打包" >&2
  exit 1
}

# 把构建出来的每个 ABI 都打进包里，安装时由 abi-resolve.sh 选取。
packed_abis=""
for abidir in "$NATIVE_DIR"/*/; do
  [ -d "$abidir" ] || continue
  abi=$(basename "$abidir")
  [ -x "$abidir/baize_engine" ] || continue
  [ -x "$abidir/baize_deep_snapshot" ] || continue
  mkdir -p "$STAGE/bin/$abi"
  cp -f "$abidir/baize_engine" "$STAGE/bin/$abi/baize_engine"
  cp -f "$abidir/baize_deep_snapshot" "$STAGE/bin/$abi/baize_deep_snapshot"
  chmod 0755 "$STAGE/bin/$abi/baize_engine" "$STAGE/bin/$abi/baize_deep_snapshot"
  packed_abis="$packed_abis $abi"
done
[ -n "$packed_abis" ] || { echo "没有可打包的原生引擎" >&2; exit 1; }
echo "已打包 ABI：$packed_abis"

chmod 0755 "$STAGE/storage-index.sh" "$STAGE/task-worker.sh" "$STAGE/cache-lane-worker.sh" "$STAGE/organizer-worker.sh"
chmod 0755 "$STAGE/cleaner.sh" "$STAGE/native-cleaner.sh" "$STAGE/cache-snapshot-clean.sh" "$STAGE/cache-transaction.sh" "$STAGE/one-pass-scan.sh" "$STAGE/profile-cleaner.sh" "$STAGE/deep-scan-manifest.sh" "$STAGE/deep-manifest-clean.sh" "$STAGE/apk-scanner.sh" "$STAGE/apk-cleaner.sh"
chmod 0644 "$STAGE/abi-resolve.sh"
chmod 0755 "$STAGE/cleaner.sh.compat"
chmod 0755 "$STAGE/notify.sh" "$STAGE/scheduler.sh" "$STAGE/service.sh" "$STAGE/action.sh" "$STAGE/uninstall.sh"
chmod 0755 "$STAGE/quarantine-manager.sh" "$STAGE/large-file-scanner.sh" "$STAGE/duplicate-scanner.sh" "$STAGE/storage-analyzer.sh" "$STAGE/diagnostics-export.sh" "$STAGE/app-installer.sh" "$STAGE/supervisor.sh" "$STAGE/autopilot-controller.sh" "$STAGE/worker-runner.sh" "$STAGE/task-worker.sh" "$STAGE/rules-validator.sh" "$STAGE/organizer-worker.sh" "$STAGE/cache-lane-worker.sh"

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
unzip -l "$OUTPUT" | grep -q 'uninstall.sh'
unzip -p "$OUTPUT" uninstall.sh | grep -q 'signal_owned_processes'
unzip -p "$OUTPUT" uninstall.sh | grep -q 'baize-v2-quarantine-recovery'
zipinfo -l "$OUTPUT" | grep -Eq '^-rwxr-xr-x.*uninstall\.sh$'
unzip -l "$OUTPUT" | grep -q 'cleaner.sh'
unzip -l "$OUTPUT" | grep -q 'native-cleaner.sh'
unzip -l "$OUTPUT" | grep -q 'cache-snapshot-clean.sh'
unzip -l "$OUTPUT" | grep -q 'cache-transaction.sh'
unzip -l "$OUTPUT" | grep -q 'one-pass-scan.sh'
unzip -l "$OUTPUT" | grep -q 'storage-index.sh'
unzip -l "$OUTPUT" | grep -q 'task-worker.sh'
unzip -l "$OUTPUT" | grep -q 'cache-lane-worker.sh'
unzip -p "$OUTPUT" cache-lane-worker.sh | grep -q 'BAIZE_ROOT_STATE_DIR'
unzip -l "$OUTPUT" | grep -q 'organizer-worker.sh'
unzip -l "$OUTPUT" | grep -q 'autopilot-controller.sh'
unzip -p "$OUTPUT" autopilot-controller.sh | grep -q 'autopilot_zero_yield_streak'
unzip -p "$OUTPUT" supervisor.sh | grep -q 'run_autopilot'
unzip -p "$OUTPUT" scheduler.sh | grep -q 'resource-lane scheduler'
unzip -p "$OUTPUT" scheduler.sh | grep -q 'run_parallel_pair'
unzip -p "$OUTPUT" scheduler.sh | grep -q 'fixed-seven-fields-v1'
unzip -p "$OUTPUT" task-worker.sh | grep -q "detached-root-worker-$(sed -n 's/^version=//p' "$REPO/module.prop" | head -n1)"
unzip -p "$OUTPUT" task-worker.sh | grep -q 'organize'
unzip -p "$OUTPUT" organizer-worker.sh | grep -q 'organizer-result.env'
unzip -p "$OUTPUT" organizer-worker.sh | grep -q 'operation=module-organize'
unzip -p "$OUTPUT" organizer-worker.sh | grep -q 'build_fallback_index'
unzip -l "$OUTPUT" | grep -q 'profile-cleaner.sh'
unzip -l "$OUTPUT" | grep -q 'deep-scan-manifest.sh'
unzip -l "$OUTPUT" | grep -q 'deep-manifest-clean.sh'
unzip -l "$OUTPUT" | grep -q 'bin/arm64-v8a/baize_deep_snapshot'
unzip -l "$OUTPUT" | grep -q 'abi-resolve.sh'
unzip -l "$OUTPUT" | grep -q 'config/risk-overrides.conf'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'deep-scan-manifest.sh'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'deep-manifest-clean.sh'
unzip -p "$OUTPUT" deep-scan-manifest.sh | grep -q 'snapshot_schema=deep-file-manifest-v1'
unzip -p "$OUTPUT" deep-scan-manifest.sh | grep -q 'manifest_sha='
unzip -p "$OUTPUT" deep-manifest-clean.sh | grep -q 'deep_manifest_cursor'
unzip -p "$OUTPUT" deep-manifest-clean.sh | grep -q 'deep_remaining_records'
unzip -p "$OUTPUT" service.sh | grep -q 'RUNTIME_SCHEMA=deep-manifest-v1'
if unzip -p "$OUTPUT" deep-manifest-clean.sh | grep -Eq '(^|[[:space:]])find[[:space:]]|xargs[[:space:]]'; then
  echo "深度不可变快照清理器不得重新枚举目录" >&2
  exit 1
fi
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
unzip -p "$OUTPUT" config/default.conf | grep -q '^autopilot_enabled=1$'
unzip -p "$OUTPUT" config/default.conf | grep -q '^schedule_cache_minutes=1440$'
unzip -p "$OUTPUT" config/default.conf | grep -q '^app_cache_days=2$'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'apk-scanner.sh'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'apk-cleaner.sh'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'native-cleaner.sh'
unzip -p "$OUTPUT" apk-scanner.sh | grep -q 'apk-snapshot-v2.2-shared-index'
unzip -p "$OUTPUT" apk-scanner.sh | grep -q 'apk-files.nul'
unzip -p "$OUTPUT" module.prop | grep -q '^version=v2.6.0$'
unzip -p "$OUTPUT" module.prop | grep -q '^versionCode=26000$'
unzip -p "$OUTPUT" customize.sh | grep -Fqx 'ui_print "- 正在安装白泽 v2.6.0"'
if unzip -p "$OUTPUT" customize.sh | grep -Eq 'v2\.5\.6|versionCode=25006|v2\.5\.5|versionCode=25005|v2\.5\.2|versionCode=25002|v2\.5\.1|versionCode=25001|v2\.5\.0|versionCode=25000|v2\.4\.0|versionCode=24000'; then
  echo "安装脚本仍包含旧版发布标识，禁止发布" >&2
  exit 1
fi
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
echo "已生成白泽 v2.6.0 深度不可变快照模块：$OUTPUT"
