#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
HOME="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/HomeRoute.kt"
CLEAN="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanRoute.kt"
DASH="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
CI="$ROOT/.github/workflows/v2.5-concurrent-scheduler-ci.yml"

for file in "$HOME" "$CLEAN" "$DASH" "$CI"; do
  test -f "$file" || exit 1
done

grep -Fq 'HomeScreenMaterial(state, scheduler, actions, onOpenClean)' "$HOME"
grep -Fq 'HomeScreenMiuix(state, scheduler, actions, onOpenClean)' "$HOME"
! grep -Fq 'actions.copy(' "$HOME"
! grep -Fq 'ScanWorkbenchActivity' "$HOME"

grep -Fq 'clean = { runSmartClean() }' "$DASH"
grep -Fq 'organize = { runOneTapOrganize() }' "$DASH"
grep -Fq 'service.runModuleTask("clean")' "$DASH"
grep -Fq 'service.runModuleTask("organize")' "$DASH"

grep -Fq 'import io.github.xgl34222220.baize.ScanWorkbenchActivity' "$CLEAN"
grep -Fq 'onScan = { context.startActivity(Intent(context, ScanWorkbenchActivity::class.java)) }' "$CLEAN"
grep -Fq 'test-home-one-tap-actions-contract.sh' "$CI"

echo "home one-tap actions contract ok"
