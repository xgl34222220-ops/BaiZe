#!/usr/bin/env bash
# 验证 deep_max_auto_risk 的等级解析与"定时任务永不执行 high/critical"这条硬边界。
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=${TMPDIR:-/tmp}/baize-risk-ceiling
rm -rf "$T"; mkdir -p "$T"
CONFIG="$T/config.conf"

# 从 native-scan.sh 里抽出被测函数，避免复制一份实现造成漂移。
sed -n '/^deep_max_auto_risk() {$/,/^}$/p' "$ROOT/v2/module/native-scan.sh" > "$T/fn.sh"
[ -s "$T/fn.sh" ] || { echo "未能从 native-scan.sh 提取 deep_max_auto_risk"; exit 1; }
# shellcheck disable=SC1090
. "$T/fn.sh"

fail=0
check() {
  local trigger=$1 allow_high=$2 want=$3 why=$4
  local got
  got=$(deep_max_auto_risk "$trigger" "$allow_high")
  if [ "$got" != "$want" ]; then
    printf '  [FAIL] trigger=%-8s allow_high=%s 期望 %-8s 实际 %-8s (%s)\n' \
      "$trigger" "$allow_high" "$want" "$got" "$why"
    fail=$((fail + 1))
  fi
}

write_config() { printf '%s\n' "$@" > "$CONFIG"; }

echo "deep_max_auto_risk 边界测试"
echo

echo "— 默认配置 —"
write_config "enabled=1"
check schedule 0 medium "定时默认 medium"
check manual   0 medium "未开高风险开关，手动也封顶 medium"
check manual   1 high   "开了高风险开关，手动默认 high"

echo "— 用户把定时上限调高，硬边界必须拦下来 —"
write_config "deep_scheduled_max_risk=critical" "deep_manual_max_risk=critical"
check schedule 1 medium "定时任务永不执行 high/critical"
check autopilot 1 medium "自动驾驶同样受限"
check manual   1 critical "手动允许 critical"

echo "— 用户把定时上限调低 —"
write_config "deep_scheduled_max_risk=low"
check schedule 0 low "定时可以更保守"
check schedule 1 low "开关不会反向抬高用户设定"

echo "— 非法值回退到默认 —"
write_config "deep_scheduled_max_risk=banana" "deep_manual_max_risk="
check schedule 0 medium "非法定时值回退 medium"
check manual   1 high   "空手动值回退 high"

echo "— 高风险开关关闭时压制手动上限 —"
write_config "deep_manual_max_risk=critical"
check manual 0 medium "deep_high_risk_enabled=0 时手动不得超过 medium"

echo
if [ "$fail" -eq 0 ]; then
  echo "全部通过"
else
  echo "$fail 项失败"
  exit 1
fi
