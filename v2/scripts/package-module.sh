#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REPO=$(CDPATH= cd -- "$ROOT/.." && pwd)
OUT="$ROOT/dist"
MODULE="$ROOT/module"
STAGE="$ROOT/build/module-stage"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
NATIVE="$ROOT/build/native/arm64-v8a/baize_engine"
OUTPUT="$OUT/BaiZe-v2-Alpha42.5-Cache-Snapshot-Recovery-Module.zip"

[ -f "$APK" ] || { echo "未找到已构建 APK：$APK" >&2; exit 1; }
[ -x "$NATIVE" ] || { echo "未找到 arm64 原生扫描器：$NATIVE" >&2; exit 1; }

rm -rf "$STAGE"
mkdir -p "$OUT" "$STAGE/app" "$STAGE/bin/arm64-v8a"
cp -a "$MODULE/." "$STAGE/"
cp -a "$REPO/config" "$STAGE/config"

# 保留 C 扫描入口，但由一个极小分流器把 cache-clean 交给独立快照执行器。
# 这样缓存删除不会再落回旧兼容引擎，也不会触发第二次全机扫描。
mv "$STAGE/cleaner.sh" "$STAGE/cleaner.native.sh"
cat >"$STAGE/cleaner.sh" <<'WRAPPER'
#!/system/bin/sh
MODDIR=${0%/*}
case "${1:-scan}" in
  cache-clean) exec /system/bin/sh "$MODDIR/cache-snapshot-clean.sh" "$@" ;;
  *) exec /system/bin/sh "$MODDIR/cleaner.native.sh" "$@" ;;
esac
WRAPPER

cp -f "$REPO/cleaner.sh" "$STAGE/cleaner.sh.compat"
cp -f "$REPO/notify.sh" "$STAGE/notify.sh"
cp -f "$REPO/service.sh" "$STAGE/scheduler.sh"
sed -i 's|STATE_DIR=/data/adb/safesweep|STATE_DIR=/data/adb/baize-v2|g' "$STAGE/cleaner.sh.compat" "$STAGE/scheduler.sh"
sed -i 's|\*safesweep\*cleaner.sh\*|*baize_v2*cleaner.sh*|g; s|\*safesweep\*job-runner.sh\*|*baize_v2*job-runner.sh*|g; s|\*safesweep\*webctl.sh\*|*baize_v2*webctl.sh*|g' "$STAGE/cleaner.sh.compat"

cp -f "$NATIVE" "$STAGE/bin/arm64-v8a/baize_engine"
chmod 0755 "$STAGE/cleaner.sh" "$STAGE/cleaner.native.sh" "$STAGE/cache-snapshot-clean.sh"
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
unzip -l "$OUTPUT" | grep -q 'cleaner.native.sh'
unzip -l "$OUTPUT" | grep -q 'cache-snapshot-clean.sh'
unzip -l "$OUTPUT" | grep -q 'cleaner.sh.compat'
unzip -l "$OUTPUT" | grep -q 'bin/arm64-v8a/baize_engine'
unzip -l "$OUTPUT" | grep -q 'scheduler.sh'
unzip -l "$OUTPUT" | grep -q 'config/deep.rules'
unzip -p "$OUTPUT" cleaner.sh | grep -q 'cache-snapshot-clean.sh'
unzip -p "$OUTPUT" module.prop | grep -q 'version=v2.0.0-alpha42.5'
unzip -p "$OUTPUT" config/deep.rules | sha256sum | grep -q '^73d4c898630a292753adca33298c8aabbf6146debf414b2cabbe6b87d1d5c31c'

echo "已生成 Alpha 42.5 缓存快照恢复与停止修复模块：$OUTPUT"
