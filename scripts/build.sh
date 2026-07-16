#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

VERSION=$(sed -n 's/^version=//p' module.prop | head -n1)
[ -n "$VERSION" ] || { echo "module.prop 缺少 version" >&2; exit 1; }

OUT="$ROOT/dist"
STAGE="$ROOT/build/module"
NAME="白泽-${VERSION}.zip"

rm -rf "$ROOT/build"
mkdir -p "$STAGE" "$OUT"

for p in \
  action.sh cleaner.sh customize.sh job-runner.sh notify.sh service.sh status.sh uninstall.sh webctl.sh \
  module.prop skip_mount README.md \
  CHANGELOG-v0.9.7.md CHANGELOG-v0.9.9.md CHANGELOG-v1.0.0.md CHANGELOG-v1.0.1.md CHANGELOG-v1.0.2.md CHANGELOG-v1.1.0-Beta1.md \
  config webroot; do
  cp -a "$p" "$STAGE/"
done

find "$STAGE" -type f -name '*.sh' -exec chmod 0755 {} +

(
  cd "$STAGE"
  zip -qr "$OUT/$NAME" .
)

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$OUT" && sha256sum "$NAME" > "$NAME.sha256.txt")
fi

echo "已生成: $OUT/$NAME"
