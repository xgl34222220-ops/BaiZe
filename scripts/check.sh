#!/system/bin/sh
# 在 Android/BusyBox 或常规 Linux shell 中执行基础静态检查。
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

for f in action.sh cleaner.sh customize.sh job-runner.sh notify.sh service.sh status.sh uninstall.sh webctl.sh; do
  [ -f "$f" ] || { echo "缺少文件: $f" >&2; exit 1; }
  sh -n "$f"
done

for f in module.prop README.md config/default.conf config/deep.rules webroot/index.html webroot/app.js webroot/style.css; do
  [ -f "$f" ] || { echo "缺少文件: $f" >&2; exit 1; }
done

RULES=$(awk '/^[[:space:]]*\// { n++ } END { print n+0 }' config/deep.rules)
[ "$RULES" = "4746" ] || { echo "深度规则数量异常: $RULES" >&2; exit 1; }

if command -v sha256sum >/dev/null 2>&1; then
  HASH=$(sha256sum config/deep.rules | awk '{print $1}')
  [ "$HASH" = "73d4c898630a292753adca33298c8aabbf6146debf414b2cabbe6b87d1d5c31c" ] || {
    echo "深度规则校验失败: $HASH" >&2
    exit 1
  }
fi

echo "基础检查通过，深度规则: $RULES 条"
