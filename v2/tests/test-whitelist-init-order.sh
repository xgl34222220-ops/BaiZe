#!/usr/bin/env bash
# 回归：白名单 loader 必须在 matcher source/fallback 定义完成后调用。
# 这些脚本只有 set -u 没有 set -e；若提前调用不存在的函数，shell 会报错后继续，
# 最危险的后果是 BAIZE_WL_ITEMS 未载入而白名单保护静默失效。
set -uo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
fail=0

check_script() {
  local f=$1
  local load source fallback
  load=$(grep -n '^baize_whitelist_load "\$WHITELIST"$' "$f" | tail -n 1 | cut -d: -f1)
  source=$(grep -n '^  \. "\$MODDIR/whitelist-match.sh"$' "$f" | tail -n 1 | cut -d: -f1)
  fallback=$(grep -n '^  path_conflicts_whitelist() {' "$f" | tail -n 1 | cut -d: -f1)

  case "$load:$source:$fallback" in
    *[!0-9:]*|:*|*::*)
      echo "  [FAIL] ${f#$ROOT/}: matcher/load 结构缺失"
      fail=$((fail + 1))
      return
      ;;
  esac
  if [ "$load" -le "$source" ] || [ "$load" -le "$fallback" ]; then
    echo "  [FAIL] ${f#$ROOT/}: loader 在 matcher 可用前执行（load=$load source=$source fallback=$fallback）"
    fail=$((fail + 1))
  fi
}

check_script "$ROOT/v2/module/apk-snapshot-clean.sh"
check_script "$ROOT/v2/module/apk-snapshot-scan.sh"
check_script "$ROOT/v2/module/profile-snapshot-clean-fast.sh"

# 发布分发器也不能依赖 $0 必须包含斜杠。
for f in \
  "$ROOT/v2/module/cleaner.sh" \
  "$ROOT/v2/module/apk-snapshot-clean.sh" \
  "$ROOT/v2/module/apk-snapshot-scan.sh" \
  "$ROOT/v2/module/profile-snapshot-clean-fast.sh" \
  "$ROOT/v2/module/storage-index.sh"; do
  grep -q 'case "\$0" in \*/\*) MODDIR=' "$f" || {
    echo "  [FAIL] ${f#$ROOT/}: MODDIR 仍依赖 \${0%/*} 的不安全假设"
    fail=$((fail + 1))
  }
done

if [ "$fail" -eq 0 ]; then echo "白名单初始化顺序与关键入口 MODDIR：ok"; else exit 1; fi
