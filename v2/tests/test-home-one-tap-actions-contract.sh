#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
HOME="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/HomeRoute.kt"
CLEAN="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanRoute.kt"
DASH="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"

for file in "$HOME" "$CLEAN" "$DASH"; do
  test -f "$file" || exit 1
done

# MIUIX 与 Material 3 现在共用同一套视频风格首页骨架，只通过 VideoSkin 切换组件皮肤。
grep -Fq 'UiStyle.MATERIAL -> VideoSkin.MATERIAL3' "$HOME"
grep -Fq 'UiStyle.MIUIX -> VideoSkin.MIUIX' "$HOME"
grep -Fq 'VideoHomeScreenMiuix(state, scheduler, actions, onOpenClean)' "$HOME"
! grep -Fq 'actions.copy(' "$HOME"
! grep -Fq 'ScanWorkbenchActivity' "$HOME"

# 首页一键清理与一键归类仍直接执行 Root 任务，不能退化成打开扫描工作台。
grep -Fq 'clean = { runSmartClean() }' "$DASH"
grep -Fq 'organize = { runOneTapOrganize() }' "$DASH"
grep -Fq 'service.runModuleTask("clean")' "$DASH"
grep -Fq 'service.runModuleTask("organize")' "$DASH"

# 详细扫描继续只放在清理页专项入口。
grep -Fq 'import io.github.xgl34222220.baize.ScanWorkbenchActivity' "$CLEAN"
grep -Fq 'onScan = { context.startActivity(Intent(context, ScanWorkbenchActivity::class.java)) }' "$CLEAN"

echo "home one-tap actions contract ok"
