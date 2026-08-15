#!/system/bin/sh
# v1 兼容清理引擎的基础静态检查。
#
# 规则数量与 SHA 不再硬编码——它们以 config/rules.meta.env 为唯一来源，
# 由 v2/scripts/validate-rules.py 生成。此前这两个值同时写死在
# 本文件、README 和 rules.meta.env 里，改一次规则要同步三处。
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

# v1 只保留兼容清理引擎与通知脚本，其余独立模块脚本已随 WebUI 一并移除。
for f in cleaner.sh notify.sh; do
  [ -f "$f" ] || { echo "缺少文件: $f" >&2; exit 1; }
  sh -n "$f"
done

for f in module.prop README.md config/default.conf config/deep.rules config/rules.meta.env; do
  [ -f "$f" ] || { echo "缺少文件: $f" >&2; exit 1; }
done

meta_value() { sed -n "s/^$1=//p" config/rules.meta.env | head -n 1; }

EXPECTED_SHA=$(meta_value rules_sha256)
[ -n "$EXPECTED_SHA" ] || { echo "rules.meta.env 缺少 rules_sha256" >&2; exit 1; }

if command -v sha256sum >/dev/null 2>&1; then
  HASH=$(sha256sum config/deep.rules | awk '{print $1}')
  [ "$HASH" = "$EXPECTED_SHA" ] || {
    echo "深度规则校验失败：期望 $EXPECTED_SHA，实际 $HASH" >&2
    echo "如果规则确实变更了，运行 python3 v2/scripts/validate-rules.py 重新生成元数据" >&2
    exit 1
  }
fi

RULES=$(awk '/^[[:space:]]*\// { n++ } END { print n+0 }' config/deep.rules)
EXPECTED_COUNT=$(meta_value rules_count)
[ "$RULES" = "$EXPECTED_COUNT" ] || {
  echo "深度规则数量与元数据不符：文件 $RULES 条，元数据 $EXPECTED_COUNT 条" >&2
  exit 1
}

# 兼容引擎必须使用注入式状态目录，不再依赖构建期 sed 改写。
grep -q 'STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}' cleaner.sh || {
  echo "兼容引擎未使用注入式状态目录" >&2
  exit 1
}
grep -q 'MODULE_TAG=${BAIZE_MODULE_TAG:-baize_v2}' cleaner.sh || {
  echo "兼容引擎未使用注入式模块标识" >&2
  exit 1
}
grep -q '深度受保护:.*未递归统计' cleaner.sh || { echo "缺少高风险快速保护" >&2; exit 1; }
# 目录时限是可配置项（deep_dir_timeout_seconds，默认 8 秒），
# 旧断言写死 DEEP_DIR_TIMEOUT_SECONDS=12 与实际代码从未一致。
grep -q 'DEEP_DIR_TIMEOUT_SECONDS=$(get_uint deep_dir_timeout_seconds' cleaner.sh || {
  echo "深度目录时限未从配置读取" >&2
  exit 1
}
grep -q 'run_deep_limited "$DEEP_DIR_TIMEOUT_SECONDS"' cleaner.sh || {
  echo "深度目录时限未实际生效" >&2
  exit 1
}
grep -q 'cache_parent_pattern' cleaner.sh || { echo "缺少缓存规则合并" >&2; exit 1; }

echo "基础检查通过，深度规则 $RULES 条，SHA 与元数据一致"
