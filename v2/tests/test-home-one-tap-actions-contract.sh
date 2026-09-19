#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
HOME_ROUTE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/HomeRoute.kt"
CLEAN="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanRoute.kt"
DASH="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"

for file in "$HOME_ROUTE" "$CLEAN" "$DASH"; do
  test -f "$file" || exit 1
done

grep -Fq 'UiStyle.MATERIAL -> VideoSkin.MATERIAL3' "$HOME_ROUTE"
grep -Fq 'UiStyle.MIUIX -> VideoSkin.MIUIX' "$HOME_ROUTE"
grep -Eq 'VideoHomeScreenMiuix\(state, scheduler, actions, onOpenClean([,)])' "$HOME_ROUTE"
! grep -Fq 'actions.copy(' "$HOME_ROUTE"

# Rebuilt architecture: foreground actions belong to the App, never to module shell tasks.
grep -Fq 'clean = { openForegroundCleaner() }' "$DASH"
grep -Fq 'scan = { openForegroundCleaner() }' "$DASH"
grep -Fq 'organize = { startActivity(Intent(this, FileOrganizerActivity::class.java)) }' "$DASH"
grep -Fq 'apkScan = { startActivity(Intent(this, ApkScanActivity::class.java)) }' "$DASH"
grep -Fq 'deep = { openProfile("deep") }' "$DASH"

# The clean page must open the App-owned resumable cleaner.
grep -Fq 'import io.github.xgl34222220.baize.ResumableSmartScanActivity' "$CLEAN"
grep -Fq 'onScan = { context.startActivity(Intent(context, ResumableSmartScanActivity::class.java)) }' "$CLEAN"

# Legacy module runners may remain for automatic/background compatibility, but none may be wired
# directly to the main foreground action map.
ACTIONS=$(sed -n '/DashboardActions(/,/)/p' "$DASH")
! printf '%s\n' "$ACTIONS" | grep -q 'runModuleTask'
! printf '%s\n' "$ACTIONS" | grep -q 'runModuleClean'

echo "home App-owned foreground actions contract ok"
