#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REPO=$(CDPATH= cd -- "$ROOT/.." && pwd)
VERSION=$(sed -n 's/^version=//p' "$REPO/module.prop" | head -n1)
VERSION_CODE=$(sed -n 's/^versionCode=//p' "$REPO/module.prop" | head -n1)
OUT="$ROOT/dist"
MODULE="$ROOT/module"
STAGE="$ROOT/build/module-stage"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
NATIVE_DIR="$ROOT/build/native"
OUTPUT="$OUT/BaiZe-$VERSION-Module.zip"

[ -f "$APK" ] || { echo "未找到已构建 APK：$APK" >&2; exit 1; }
# arm64 是必须产物；其余 ABI 有就打进去，没有就跳过。
[ -x "$NATIVE_DIR/arm64-v8a/baize_engine" ] || { echo "未找到 arm64 原生扫描器" >&2; exit 1; }
[ -x "$NATIVE_DIR/arm64-v8a/baize_deep_snapshot" ] || { echo "未找到 arm64 深度不可变快照引擎" >&2; exit 1; }
[ -x "$NATIVE_DIR/arm64-v8a/baize_compat_filter" ] || { echo "未找到 arm64 兼容清单过滤器" >&2; exit 1; }

# 打包前跑全量回归，而不是手工列举五个测试。
bash "$ROOT/tests/run-all.sh"

rm -rf "$STAGE"
mkdir -p "$OUT" "$STAGE/app"
cp -a "$MODULE/." "$STAGE/"
rm -rf "$STAGE/webroot" "$STAGE/webui" "$STAGE/www" "$STAGE/ksu-webui"
cp -a "$REPO/config" "$STAGE/config"

# Source names and package names now match; scripts are copied without rewriting.
grep -q 'STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}' "$STAGE/scripts/cleaner-compat.sh" || {
  echo "兼容引擎未使用 v2 状态目录默认值，拒绝打包" >&2
  exit 1
}
grep -q 'MODULE_TAG=${BAIZE_MODULE_TAG:-baize_v2}' "$STAGE/scripts/cleaner-compat.sh" || {
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
  [ -x "$abidir/baize_compat_filter" ] || { echo "$abi 缺少兼容清单过滤器，拒绝打包" >&2; exit 1; }
  mkdir -p "$STAGE/bin/$abi"
  cp -f "$abidir/baize_engine" "$STAGE/bin/$abi/baize_engine"
  cp -f "$abidir/baize_deep_snapshot" "$STAGE/bin/$abi/baize_deep_snapshot"
  cp -f "$abidir/baize_compat_filter" "$STAGE/bin/$abi/baize_compat_filter"
  chmod 0755 "$STAGE/bin/$abi/baize_engine" "$STAGE/bin/$abi/baize_deep_snapshot" "$STAGE/bin/$abi/baize_compat_filter"
  packed_abis="$packed_abis $abi"
done
[ -n "$packed_abis" ] || { echo "没有可打包的原生引擎" >&2; exit 1; }
echo "已打包 ABI：$packed_abis"

# One permissions policy replaces repeated per-script chmod lists.
chmod 0755 "$STAGE"/*.sh "$STAGE/scripts"/*.sh
chmod 0644 "$STAGE/scripts/abi-resolve.sh" "$STAGE/module.prop" "$STAGE/skip_mount"

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
unzip -l "$OUTPUT" | grep -q 'apk-paths.sh'
unzip -l "$OUTPUT" | grep -q 'task-worker.sh'
unzip -l "$OUTPUT" | grep -q 'cache-lane-worker.sh'
unzip -p "$OUTPUT" scripts/cache-lane-worker.sh | grep -q 'BAIZE_ROOT_STATE_DIR'
unzip -l "$OUTPUT" | grep -q 'organizer-worker.sh'
unzip -l "$OUTPUT" | grep -q 'autopilot-controller.sh'
unzip -p "$OUTPUT" scripts/autopilot-controller.sh | grep -q 'autopilot_zero_yield_streak'
unzip -p "$OUTPUT" scripts/supervisor.sh | grep -q 'run_autopilot'
unzip -p "$OUTPUT" scripts/scheduler.sh | grep -q 'resource-lane scheduler'
unzip -p "$OUTPUT" scripts/scheduler.sh | grep -q 'run_parallel_pair'
unzip -p "$OUTPUT" scripts/scheduler.sh | grep -q 'fixed-seven-fields-v1'
unzip -p "$OUTPUT" scripts/task-worker.sh | grep -q "detached-root-worker-$(sed -n 's/^version=//p' "$REPO/module.prop" | head -n1)"
unzip -p "$OUTPUT" scripts/task-worker.sh | grep -q 'organize'
unzip -p "$OUTPUT" scripts/organizer-worker.sh | grep -q 'organizer-result.env'
unzip -p "$OUTPUT" scripts/organizer-worker.sh | grep -q 'operation=module-organize'
unzip -p "$OUTPUT" scripts/organizer-worker.sh | grep -q 'build_fallback_index'
unzip -l "$OUTPUT" | grep -q 'profile-cleaner.sh'
unzip -l "$OUTPUT" | grep -q 'deep-scan-manifest.sh'
unzip -l "$OUTPUT" | grep -q 'deep-manifest-clean.sh'
unzip -l "$OUTPUT" | grep -q 'bin/arm64-v8a/baize_deep_snapshot'
unzip -l "$OUTPUT" | grep -q 'bin/arm64-v8a/baize_compat_filter'
unzip -p "$OUTPUT" scripts/cleaner-compat.sh | grep -q 'baize_compat_filter'
unzip -l "$OUTPUT" | grep -q 'abi-resolve.sh'
unzip -l "$OUTPUT" | grep -q 'config/risk-overrides.conf'
unzip -p "$OUTPUT" scripts/cleaner.sh | grep -q 'deep-scan-manifest.sh'
unzip -p "$OUTPUT" scripts/cleaner.sh | grep -q 'deep-manifest-clean.sh'
unzip -p "$OUTPUT" scripts/deep-scan-manifest.sh | grep -q 'snapshot_schema=deep-file-manifest-v1'
unzip -p "$OUTPUT" scripts/deep-scan-manifest.sh | grep -q 'manifest_sha='
unzip -p "$OUTPUT" scripts/deep-manifest-clean.sh | grep -q 'deep_manifest_cursor'
unzip -p "$OUTPUT" scripts/deep-manifest-clean.sh | grep -q 'deep_remaining_records'
unzip -p "$OUTPUT" service.sh | grep -q 'RUNTIME_SCHEMA=deep-manifest-v1'
if unzip -p "$OUTPUT" scripts/deep-manifest-clean.sh | grep -Eq '(^|[[:space:]])find[[:space:]]|xargs[[:space:]]'; then
  echo "深度不可变快照清理器不得重新枚举目录" >&2
  exit 1
fi
unzip -l "$OUTPUT" | grep -q 'apk-scanner.sh'
unzip -l "$OUTPUT" | grep -q 'apk-cleaner.sh'
unzip -l "$OUTPUT" | grep -q 'cleaner-compat.sh'
unzip -l "$OUTPUT" | grep -q 'bin/arm64-v8a/baize_engine'
unzip -l "$OUTPUT" | grep -q 'scheduler.sh'
unzip -l "$OUTPUT" | grep -q 'config/deep.rules'
unzip -l "$OUTPUT" | grep -q 'config/organizer-categories.conf'
# 归类分类表必须与索引侧同源，缺了会导致大量文件永远归类不到
unzip -p "$OUTPUT" config/organizer-categories.conf | grep -q '^音频=' 
unzip -p "$OUTPUT" scripts/cleaner.sh | grep -q 'profile-cleaner.sh'
unzip -p "$OUTPUT" scripts/cleaner.sh | grep -q 'cache-snapshot-clean.sh'
unzip -p "$OUTPUT" scripts/cleaner.sh | grep -q 'cache-transaction.sh'
unzip -p "$OUTPUT" scripts/one-pass-scan.sh | grep -q 'scan-external-one-pass'
unzip -p "$OUTPUT" scripts/storage-index.sh | grep -q 'Android/media'
unzip -p "$OUTPUT" scripts/storage-index.sh | grep -q 'Android/data'
unzip -p "$OUTPUT" scripts/storage-index.sh | grep -q 'QQfile_recv'
unzip -p "$OUTPUT" scripts/storage-index.sh | grep -q 'nu.gpu.nagramx\|Android/data'
unzip -p "$OUTPUT" scripts/one-pass-scan.sh | grep -q 'one_pass_app_dirs'
unzip -p "$OUTPUT" scripts/one-pass-scan.sh | grep -q 'whitelist_index_queries'
unzip -p "$OUTPUT" scripts/one-pass-scan.sh | grep -q 'pruned_subtrees'
unzip -p "$OUTPUT" scripts/one-pass-scan.sh | grep -q 'BAIZE_ROOT_WORKERS'
unzip -p "$OUTPUT" scripts/one-pass-scan.sh | grep -q 'parallel_overlap_milli'
unzip -p "$OUTPUT" config/default.conf | grep -q '^scan_root_workers=0$'
unzip -p "$OUTPUT" config/default.conf | grep -q '^autopilot_enabled=1$'
unzip -p "$OUTPUT" config/default.conf | grep -q '^schedule_cache_minutes=1440$'
unzip -p "$OUTPUT" config/default.conf | grep -q '^app_cache_days=2$'
unzip -p "$OUTPUT" scripts/cleaner.sh | grep -q 'apk-scanner.sh'
unzip -p "$OUTPUT" scripts/cleaner.sh | grep -q 'apk-cleaner.sh'
unzip -p "$OUTPUT" scripts/cleaner.sh | grep -q 'native-cleaner.sh'
unzip -p "$OUTPUT" scripts/apk-scanner.sh | grep -q 'apk-snapshot-v2.3-global-index'
unzip -p "$OUTPUT" scripts/apk-scanner.sh | grep -q 'apk-files.nul'
unzip -p "$OUTPUT" module.prop | grep -Fqx "version=$VERSION"
unzip -p "$OUTPUT" module.prop | grep -Fqx "versionCode=$VERSION_CODE"
EXPECTED_INSTALL_LINE=$(printf 'ui_print "- 正在安装白泽 %s"' "$VERSION")
unzip -p "$OUTPUT" customize.sh | grep -Fqx "$EXPECTED_INSTALL_LINE"
if unzip -p "$OUTPUT" customize.sh | grep -Eq 'v2\.5\.6|versionCode=25006|v2\.5\.5|versionCode=25005|v2\.5\.2|versionCode=25002|v2\.5\.1|versionCode=25001|v2\.5\.0|versionCode=25000|v2\.4\.0|versionCode=24000'; then
  echo "安装脚本仍包含旧版发布标识，禁止发布" >&2
  exit 1
fi
if unzip -Z1 "$OUTPUT" | grep -Eq '^(webroot|webui|www|ksu-webui)/'; then
  echo "模块包中不允许包含 WebUI 资源" >&2
  exit 1
fi
EXPECTED_DEEP_SHA=$(sha256sum "$REPO/config/deep.rules" | awk '{print $1}')
unzip -p "$OUTPUT" config/deep.rules | sha256sum | grep -q "^$EXPECTED_DEEP_SHA"
unzip -p "$OUTPUT" scripts/cache-snapshot-clean.sh | grep -q 'clean-cache-snapshot'
if unzip -p "$OUTPUT" scripts/cache-snapshot-clean.sh | grep -Eq 'find[[:space:]].*cache|xargs[[:space:]].*rm'; then
  echo "缓存快照清理器不得重新枚举目录生成删除名单" >&2
  exit 1
fi
python3 "$ROOT/scripts/verify-module-layout.py" "$OUTPUT" "$REPO/design/app-icons/official-icon.webp"
echo "已生成白泽 $VERSION 深度不可变快照模块：$OUTPUT"
