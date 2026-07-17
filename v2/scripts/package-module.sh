#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/dist"
mkdir -p "$OUT"
rm -f "$OUT/BaiZe-v2-Alpha1-Bridge.zip"
(
  cd "$ROOT/module"
  zip -qr "$OUT/BaiZe-v2-Alpha1-Bridge.zip" .
)
echo "已生成 $OUT/BaiZe-v2-Alpha1-Bridge.zip"
