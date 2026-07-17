#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/dist"
MODULE="$ROOT/module"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
BUNDLED_DIR="$MODULE/app"
BUNDLED_APK="$BUNDLED_DIR/baize.apk"
BUNDLED_HASH="$BUNDLED_DIR/baize.apk.sha256"
OUTPUT="$OUT/BaiZe-v2-Alpha4-Module.zip"

if [ ! -f "$APK" ]; then
  echo "未找到已构建 APK：$APK" >&2
  exit 1
fi

mkdir -p "$OUT" "$BUNDLED_DIR"
cp -f "$APK" "$BUNDLED_APK"
chmod 0644 "$BUNDLED_APK"
sha256sum "$BUNDLED_APK" | awk '{print $1}' > "$BUNDLED_HASH"
chmod 0644 "$BUNDLED_HASH"

rm -f "$OUTPUT"
(
  cd "$MODULE"
  zip -qr "$OUTPUT" .
)

echo "已生成内置 App 的单一模块包：$OUTPUT"
