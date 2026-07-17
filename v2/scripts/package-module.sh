#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REPO=$(CDPATH= cd -- "$ROOT/.." && pwd)
OUT="$ROOT/dist"
MODULE="$ROOT/module"
STAGE="$ROOT/build/module-stage"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
OUTPUT="$OUT/BaiZe-v2-Alpha5-Module.zip"

if [ ! -f "$APK" ]; then
  echo "未找到已构建 APK：$APK" >&2
  exit 1
fi

rm -rf "$STAGE"
mkdir -p "$OUT" "$STAGE/app"
cp -a "$MODULE/." "$STAGE/"
cp -a "$REPO/config" "$STAGE/config"
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
unzip -l "$OUTPUT" | grep -q 'config/deep.rules'
unzip -p "$OUTPUT" config/deep.rules | sha256sum | grep -q '^73d4c898630a292753adca33298c8aabbf6146debf414b2cabbe6b87d1d5c31c'

echo "已生成内置 App 与完整规则库的一体化模块：$OUTPUT"
