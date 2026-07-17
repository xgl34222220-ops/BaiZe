#!/system/bin/sh
# 在 Android/BusyBox 或常规 Linux shell 中执行基础静态检查。
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

for f in action.sh cleaner.sh customize.sh job-runner.sh notify.sh service.sh status.sh uninstall.sh webctl.sh; do
  [ -f "$f" ] || { echo "缺少文件: $f" >&2; exit 1; }
  sh -n "$f"
done

for f in module.prop README.md CHANGELOG-v1.0.3.md config/default.conf config/deep.rules webroot/index.html webroot/app.js webroot/style.css; do
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

if command -v node >/dev/null 2>&1; then
  node --check webroot/app.js
fi

grep -q '深度受保护:.*未递归统计' cleaner.sh || { echo "缺少高风险快速保护" >&2; exit 1; }
grep -q 'DEEP_DIR_TIMEOUT_SECONDS=12' cleaner.sh || { echo "缺少深度目录时限" >&2; exit 1; }
grep -q 'run_progress_current' status.sh || { echo "缺少实时进度状态" >&2; exit 1; }
grep -q 'cache_parent_pattern' cleaner.sh || { echo "缺少缓存规则合并" >&2; exit 1; }

echo "基础检查通过，深度规则: $RULES 条，WebUI 与性能保护正常"
