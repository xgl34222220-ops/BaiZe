#!/usr/bin/env bash
# 端到端验证 scan-deep：风险分级、显式标注、用户覆盖、--max-auto-risk 上限。
# 用法：test-deep-risk-engine.sh <engine-binary>
set -euo pipefail

ENGINE=${1:?需要传入 baize_engine 路径}
T=${TMPDIR:-/tmp}/baize-risk-e2e
rm -rf "$T"; mkdir -p "$T"

# 引擎的 deep_allowed() 只放行 /data/data、/data/media 等真实前缀，
# 所以这里用 --media-root 无法绕过，改为在沙箱里构造 /data/media 的同名结构。
# 为了不碰真实系统目录，测试使用 fakeroot 风格的相对判断：
# 直接在 $T 下建 data/media/0/... 然后用 bind 之外的方式不可行，
# 因此改为验证引擎对"给定规则集"的分级与上限决策，用 report 文件断言。
SANDBOX="$T/root"
mkdir -p "$SANDBOX"

# 构造真实前缀下的临时目录（可写则用真实路径，否则跳过端到端）
REAL_BASE="/data/media/0/.baize-selftest-$$"
if ! mkdir -p "$REAL_BASE" 2>/dev/null; then
  echo "  [skip] 当前环境无法写入 /data/media，跳过端到端引擎测试"
  echo "         单元测试 test-deep-risk-classification 已覆盖分级逻辑"
  exit 0
fi
trap 'rm -rf "$REAL_BASE"' EXIT

mkdir -p "$REAL_BASE/appcache/cache" \
         "$REAL_BASE/wallet/nfc/logo" \
         "$REAL_BASE/game/login-identifier" \
         "$REAL_BASE/media/Download"
echo x > "$REAL_BASE/appcache/cache/a.bin"
echo x > "$REAL_BASE/wallet/nfc/logo/icon.png"
echo x > "$REAL_BASE/game/login-identifier/id.txt"
echo x > "$REAL_BASE/media/Download/movie.mp4"

cat > "$T/rules" <<EOF
$REAL_BASE/appcache/cache
$REAL_BASE/wallet/nfc/logo
$REAL_BASE/game/login-identifier
$REAL_BASE/media/Download
EOF
: > "$T/whitelist"

run() {
  local tag=$1; shift
  "$ENGINE" scan-deep --rules "$T/rules" --whitelist "$T/whitelist" \
    --report "$T/report-$tag.tsv" --targets "$T/targets-$tag" \
    --summary "$T/summary-$tag.env" "$@" >/dev/null 2>&1 || true
}

risk_of() { awk -F'\t' -v p="$2" '$6 == p { print $2 }' "$T/report-$1.tsv" | head -n1; }
action_of() { awk -F'\t' -v p="$2" '$6 == p { print $1 }' "$T/report-$1.tsv" | head -n1; }

fail=0
expect() {
  local got=$1 want=$2 why=$3
  if [ "$got" != "$want" ]; then
    printf '  [FAIL] 期望 %-10s 实际 %-10s (%s)\n' "$want" "$got" "$why"
    fail=$((fail + 1))
  fi
}

echo "— 默认上限 medium —"
run default --max-auto-risk medium
expect "$(risk_of default "$REAL_BASE/appcache/cache")" low "cache 判为 low"
expect "$(action_of default "$REAL_BASE/appcache/cache")" candidate "low 可执行"
expect "$(risk_of default "$REAL_BASE/wallet/nfc/logo")" high "logo 不再被当成 log"
expect "$(action_of default "$REAL_BASE/wallet/nfc/logo")" protected "high 仅扫描"
expect "$(risk_of default "$REAL_BASE/game/login-identifier")" high "login-identifier 不是 log"
expect "$(risk_of default "$REAL_BASE/media/Download")" critical "Download 是 critical"
expect "$(action_of default "$REAL_BASE/media/Download")" protected "critical 仅扫描"

echo "— 上限抬到 high 后，high 变为可执行，critical 仍受保护 —"
run high --max-auto-risk high
expect "$(action_of high "$REAL_BASE/wallet/nfc/logo")" candidate "high 在上限内"
expect "$(action_of high "$REAL_BASE/media/Download")" protected "critical 仍被拦"

echo "— 用户覆盖：把 cache 提到 high —"
printf '%s|high\n' "$REAL_BASE/appcache/cache" > "$T/overrides"
run override --max-auto-risk medium --risk-overrides "$T/overrides"
expect "$(risk_of override "$REAL_BASE/appcache/cache")" high "用户覆盖生效"
expect "$(action_of override "$REAL_BASE/appcache/cache")" protected "覆盖后不再自动删"

echo "— 用户覆盖：把 logo 降到 low —"
printf '%s|low\n' "$REAL_BASE/wallet/nfc/logo" > "$T/overrides2"
run override2 --max-auto-risk medium --risk-overrides "$T/overrides2"
expect "$(risk_of override2 "$REAL_BASE/wallet/nfc/logo")" low "用户可以自行下调"
expect "$(action_of override2 "$REAL_BASE/wallet/nfc/logo")" candidate "下调后可执行"

echo "— 规则文件内显式标注 —"
sed "s|^$REAL_BASE/appcache/cache\$|$REAL_BASE/appcache/cache\|critical|" "$T/rules" > "$T/rules2"
cp "$T/rules2" "$T/rules"
run annot --max-auto-risk high
expect "$(risk_of annot "$REAL_BASE/appcache/cache")" critical "规则标注生效"

echo
if [ "$fail" -eq 0 ]; then echo "端到端全部通过"; else echo "$fail 项失败"; exit 1; fi
