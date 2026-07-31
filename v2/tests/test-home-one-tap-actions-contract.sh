#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
HOME_ROUTE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/HomeRoute.kt"
HOME_MATERIAL="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/material/HomeScreenMaterial.kt"
HOME_MIUIX="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/miuix/HomeScreenMiuix.kt"
CLEAN="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanRoute.kt"
DASH="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
CI="$ROOT/.github/workflows/v2.5-concurrent-scheduler-ci.yml"

for file in "$HOME_ROUTE" "$HOME_MATERIAL" "$HOME_MIUIX" "$CLEAN" "$DASH" "$CI"; do
  test -f "$file" || exit 1
done

# Material 3 与 MIUIX 使用两套独立首页实现，只共享 DashboardUiState 与 DashboardActions。
grep -Fq 'UiStyle.MATERIAL -> HomeScreenMaterial' "$HOME_ROUTE"
grep -Fq 'UiStyle.MIUIX -> HomeScreenMiuix' "$HOME_ROUTE"
! grep -Fq 'VideoSkin' "$HOME_ROUTE"
! grep -Fq 'ProvideVideoSkin' "$HOME_ROUTE"
! grep -Fq 'actions.copy(' "$HOME_ROUTE"
! grep -Fq 'ScanWorkbenchActivity' "$HOME_ROUTE"

# 两套首页都必须保留直接的一键清理、扫描、归类与停止入口。
for home in "$HOME_MATERIAL" "$HOME_MIUIX"; do
  grep -Fq 'actions.clean' "$home"
  grep -Fq 'actions.cleanScan' "$home"
  grep -Fq 'actions.scan' "$home"
  grep -Fq 'actions.organize' "$home"
  grep -Fq 'actions.stop' "$home"
done

# 首页一键清理与一键归类仍直接执行 Root 任务，不能退化成打开扫描工作台。
grep -Fq 'clean = { runSmartClean() }' "$DASH"
grep -Fq 'organize = { runOneTapOrganize() }' "$DASH"
grep -Fq 'service.runModuleTask("clean")' "$DASH"
grep -Fq 'service.runModuleTask("organize")' "$DASH"

# 完整扫描工作台继续由清理页专项工具打开。
grep -Fq 'import io.github.xgl34222220.baize.ScanWorkbenchActivity' "$CLEAN"
grep -Fq 'onScan = { context.startActivity(Intent(context, ScanWorkbenchActivity::class.java)) }' "$CLEAN"
grep -Fq 'test-home-one-tap-actions-contract.sh' "$CI"

echo "home one-tap actions contract ok"
