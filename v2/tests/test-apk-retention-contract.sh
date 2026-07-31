#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SCAN="$ROOT/v2/module/apk-snapshot-scan.sh"
CONTRACT="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanContract.kt"
ROUTE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanRoute.kt"
MATERIAL_SCREEN="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/material/CleanScreenMaterial.kt"
MIUIX_SCREEN="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/miuix/CleanScreenMiuix.kt"

grep -Fq 'CONFIG_DAYS=$(get_uint apk_package_days 30 0 365)' "$SCAN"
grep -Fq 'manual|app|ui) DAYS=0' "$SCAN"
grep -Fq 'storage-index.sh" ensure "$TRIGGER"' "$SCAN"
grep -Fq 'APK_INDEX="$STATE_DIR/index/apk-files.nul"' "$SCAN"
grep -Fq 'done <"$APK_INDEX"' "$SCAN"
grep -Fq 'fun SchedulerUiState.withApkPackageDays(days: Int)' "$CONTRACT"
grep -Fq 'copy(apkPackageDays = days.coerceIn(0, 365))' "$CONTRACT"
grep -Fq 'onApkPackageDaysChanged = { days ->' "$ROUTE"

for screen in "$MATERIAL_SCREEN" "$MIUIX_SCREEN"; do
  grep -Fq 'title = "安装包保留时间"' "$screen"
  grep -Fq 'range = 0..365' "$screen"
  grep -Fq '手动安装包扫描始终显示全部安装包' "$screen"
  grep -Fq 'actions.onApkPackageDaysChanged' "$screen"
done

echo 'apk retention and manual scan contract passed'
