#!/usr/bin/env bash
# 运行全部 shell / python / 原生测试，作为 CI 与提交前的统一入口。
# 逐个执行并汇总失败项，不在第一个失败处停下，方便一次看全。
set -uo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
TESTS="$ROOT/v2/tests"

pass=0
fail=0
failed_names=()

run_one() {
  local name=$1; shift
  local out
  if out=$("$@" 2>&1); then
    pass=$((pass + 1))
    printf '  ok    %s\n' "$name"
  else
    fail=$((fail + 1))
    failed_names+=("$name")
    printf '  FAIL  %s\n' "$name"
    printf '%s\n' "$out" | sed 's/^/          /' | tail -n 25
  fi
}

echo "==> 原生引擎测试"
if command -v cc >/dev/null 2>&1 || command -v gcc >/dev/null 2>&1; then
  run_one "native (风险分级 / 引擎端到端 / 上限边界)" bash "$TESTS/run-native-tests.sh"
else
  echo "  skip  未找到 C 编译器"
fi

echo
echo "==> Shell 测试"
have_busybox=1
command -v busybox >/dev/null 2>&1 || have_busybox=0
for t in "$TESTS"/test-*.sh; do
  name=$(basename "$t")
  # run-native-tests.sh 已单独跑过它们
  case "$name" in
    test-deep-risk-engine.sh|test-deep-risk-ceiling.sh) continue ;;
  esac
  # 这几个测试用 busybox ash 验证脚本在 Android 上的行为，
  # 本机没有 busybox 时跳过（CI 会安装 busybox-static）。
  if [ "$have_busybox" = "0" ] && grep -q 'busybox' "$t"; then
    printf '  skip  %s（需要 busybox）\n' "$name"
    continue
  fi
  run_one "$name" bash "$t"
done

echo
echo "==> Python 测试"
for t in "$TESTS"/test-*.py; do
  [ -e "$t" ] || continue
  run_one "$(basename "$t")" python3 "$t"
done

echo
echo "======================================"
printf '通过 %d 项，失败 %d 项\n' "$pass" "$fail"
if [ "$fail" -gt 0 ]; then
  echo
  echo "失败项："
  for n in "${failed_names[@]}"; do echo "  - $n"; done
  exit 1
fi
echo "全部通过"
