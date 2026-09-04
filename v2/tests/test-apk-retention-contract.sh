#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SCAN="$ROOT/v2/module/apk-snapshot-scan.sh"
CONTRACT="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanContract.kt"
ROUTE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanRoute.kt"
SCREEN="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/miuix/VideoCleanScreenMiuix.kt"

grep -Fq 'CONFIG_DAYS=$(get_uint apk_package_days 30 0 365)' "$SCAN"
grep -Fq 'manual|app|ui) DAYS=0' "$SCAN"
grep -Fq 'storage-index.sh" ensure "$TRIGGER"' "$SCAN"
grep -Fq 'APK_INDEX="$STATE_DIR/index/apk-files.nul"' "$SCAN"
grep -Fq 'done <"$APK_INDEX"' "$SCAN"
grep -Fq 'fun SchedulerUiState.withApkPackageDays(days: Int)' "$CONTRACT"
grep -Fq 'copy(apkPackageDays = days.coerceIn(0, 365))' "$CONTRACT"
grep -Fq 'onApkPackageDaysChanged = { days ->' "$ROUTE"
grep -Fq 'title = "安装包保留时间"' "$SCREEN"
grep -Fq 'range = 0..365' "$SCREEN"
# UI 可重构文案，但必须继续明确：手动安装包扫描不受后台保留天数限制。
grep -Fq '手动安装包扫描' "$SCREEN"
grep -Fq '0 天表示扫描到后即可进入自动清理范围' "$SCREEN"
echo 'apk retention and manual scan contract passed'
