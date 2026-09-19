#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SCAN="$ROOT/v2/module/scripts/apk-scanner.sh"
CONTRACT="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanContract.kt"
ROUTE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanRoute.kt"
SCREEN="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/miuix/CleanScreenMiuix.kt"

grep -Fq 'CONFIG_DAYS=$(get_uint apk_package_days 30 0 365)' "$SCAN"
grep -Fq 'manual|app|ui)' "$SCAN"
grep -Fq 'INCLUDE_PRIVATE=1' "$SCAN"
grep -Fq '[ "$INCLUDE_PRIVATE" = "1" ] && apk_load_private_roots' "$SCAN"
grep -Fq 'include_private=$INCLUDE_PRIVATE' "$SCAN"
grep -Fq 'apk_collect_candidates "$DIRECT_APK_INDEX"' "$SCAN"
grep -Fq 'SHARED_APK_INDEX="$STATE_DIR/index/apk-files.nul"' "$SCAN"
grep -Fq 'shared_index_code=' "$SCAN"
grep -Fq 'APK_INDEX="$TMP_DIR/apk-files.nul"' "$SCAN"
grep -Fq 'done <"$APK_INDEX"' "$SCAN"
grep -Fq 'apk_scan_candidate_allowed "$candidate"' "$SCAN"
! grep -Fq 'apk_path_allowed "$candidate"' "$SCAN"
grep -Fq 'apk_bruteforce_candidates "$BRUTE_APK_INDEX"' "$SCAN"
grep -Fq 'brute_force_used=' "$SCAN"
grep -Fq 'fun SchedulerUiState.withApkPackageDays(days: Int)' "$CONTRACT"
grep -Fq 'copy(apkPackageDays = days.coerceIn(0, 365))' "$CONTRACT"
grep -Fq 'onApkPackageDaysChanged = { days ->' "$ROUTE"
grep -Fq 'title = "安装包保留时间"' "$SCREEN"
grep -Fq 'range = 0..365' "$SCREEN"
# UI 可重构文案，但必须继续明确：手动安装包扫描不受后台保留天数限制。
grep -Fq '手动扫描始终显示所有安装包' "$SCREEN"
grep -Fq '0 天表示不保留' "$SCREEN"
grep -Fq 'onConfirm = actions.onApkPackageDaysChanged' "$SCREEN"
grep -Fq 'ValueRow("保留时间", "${state.apkPackageDays} 天") { showApkDaysDialog = true }' "$SCREEN"
# Both appearance routes use this same retention editor.
grep -Fq 'CleanScreenMiuix(state, actions, expandedCategory, onExpandedCategoryChanged)' "${SCREEN%/*}/VideoCleanScreenMiuix.kt"

PATHS="$ROOT/v2/module/scripts/apk-paths.sh"
grep -Fq '_apk_base_real=$(readlink -f "$_apk_base"' "$PATHS"
! grep -Fq '[ "$_apk_real" = "$1" ] || return 1' "$PATHS"
grep -Fq 'APK_PRIVATE_BOUNDARIES' "$PATHS"
grep -Fq 'apk_collect_private_candidates' "$PATHS"
grep -Fq 'apk_scan_candidate_allowed()' "$PATHS"
grep -Fq 'apk_bruteforce_candidates()' "$PATHS"
grep -Fq 'find "$_apk_base" -xdev -mindepth 3 -maxdepth 12' "$PATHS"
echo 'apk retention and manual scan contract passed'
