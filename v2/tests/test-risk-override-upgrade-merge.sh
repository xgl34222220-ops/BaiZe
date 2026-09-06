#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=$(mktemp -d /tmp/baize-risk-upgrade.XXXXXX)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/tmp"
TMP_DIR="$T/tmp"

# 只抽取纯合并函数，避免 source native-scan.sh 时启动真实扫描流程。
sed -n '/^build_effective_risk_overrides()/,/^}/p' \
  "$ROOT/v2/module/native-scan.sh" >"$T/merge-fn.sh"
# shellcheck disable=SC1090
. "$T/merge-fn.sh"

cat >"$T/builtin.conf" <<'EOF'
/storage/emulated/0/Documents|critical
/storage/emulated/0/CacheDir|high
/storage/emulated/0/NewProtectedAfterUpgrade|critical
EOF

# 模拟旧版已经存在的持久文件：包含用户调整、旧条目，以及同一路径重复修改。
cat >"$T/user.conf" <<'EOF'
# existing user state from an older BaiZe install
/storage/emulated/0/Documents|low
/storage/emulated/0/LegacyOnly|high
/storage/emulated/0/Documents|medium
EOF

build_effective_risk_overrides "$T/builtin.conf" "$T/user.conf" "$T/effective.conf"

expect_line() {
  local expected=$1
  grep -Fqx "$expected" "$T/effective.conf" || {
    echo "missing effective rule: $expected" >&2
    cat "$T/effective.conf" >&2
    exit 1
  }
}

expect_line '/storage/emulated/0/Documents|medium'
expect_line '/storage/emulated/0/CacheDir|high'
expect_line '/storage/emulated/0/NewProtectedAfterUpgrade|critical'
expect_line '/storage/emulated/0/LegacyOnly|high'

# 精确同路径只能保留用户最后一次设置，不能再被内置 critical 顶回去。
[ "$(grep -Fc '/storage/emulated/0/Documents|' "$T/effective.conf")" -eq 1 ]
! grep -Fqx '/storage/emulated/0/Documents|critical' "$T/effective.conf"

# 关键升级合同：运行时同时读取模块内置表和持久用户表，不再只在首次安装复制一次。
grep -Fq 'BUILTIN_RISK_OVERRIDES=' "$ROOT/v2/module/native-scan.sh"
grep -Fq 'USER_RISK_OVERRIDES=' "$ROOT/v2/module/native-scan.sh"
grep -Fq 'build_effective_risk_overrides "$BUILTIN_RISK_OVERRIDES" "$USER_RISK_OVERRIDES"' "$ROOT/v2/module/native-scan.sh"
! grep -Fq 'cp -f "$MODDIR/config/risk-overrides.conf" "$RISK_OVERRIDES"' "$ROOT/v2/module/native-scan.sh"

echo "risk override upgrade merge: ok"
