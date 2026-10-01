#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
APP="$ROOT/app/src/main/java/io/github/xgl34222220/baize"
AIDL="$ROOT/app/src/main/aidl/io/github/xgl34222220/baize/root/IProfileRootService.aidl"
SELECTION="$APP/root/CacheSelectionRepository.kt"
WORKBENCH="$APP/ScanWorkbenchActivity.kt"
HOME="$APP/ui/home/HomeRoute.kt"
CLEAN="$APP/ui/clean/CleanRoute.kt"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"

for method in prepareCacheSelection getWhitelistPaths addWhitelistPath; do
  grep -q "String $method" "$AIDL"
done

grep -q 'cache_scan.manifest0' "$SELECTION"
grep -q 'MANIFEST_FIELD_COUNT = 11' "$SELECTION"
grep -q 'verifyHash(manifestFile' "$SELECTION"
grep -q 'nul-v3-sha256' "$SELECTION"
grep -q 'selection_parent_snapshot' "$SELECTION"
grep -q 'path == root || path.startsWith("$root/")' "$SELECTION"

# Cleaning must use the foreground service's immutable snapshot and exact selected paths.
# The module's separate snapshot repository cannot authorize an App foreground scan.
grep -Fq 'val reviewedCacheSnapshot = cacheSnapshotId' "$WORKBENCH"
grep -Fq 'cacheItems.forEach { selection.put(it.path, true) }' "$WORKBENCH"
grep -Fq 'cache.cleanSelected(reviewedCacheSnapshot, selection.toString(), packageWhitelist)' "$WORKBENCH"
grep -q 'profile.cleanProfileSelected' "$WORKBENCH"
CLEAN_SECTION=$(sed -n '/fun cleanSelection()/,/fun quarantineItem/p' "$WORKBENCH")
test -n "$CLEAN_SECTION"
! printf '%s\n' "$CLEAN_SECTION" | grep -q 'prepareCacheSelection'
! printf '%s\n' "$CLEAN_SECTION" | grep -q '__all_safe__'
! printf '%s\n' "$CLEAN_SECTION" | grep -q 'scanCandidates'
! printf '%s\n' "$CLEAN_SECTION" | grep -q 'scanProfile'

# Primary scans go directly to the same workbench; legacy resume remains an explicit recovery tool.
grep -q 'onScan = dashboardActions.scan' "$CLEAN"
grep -q 'CleanerNavigation.scan(this)' "$APP/MiuixDashboardActivity.kt"
grep -q 'ScanWorkbenchActivity::class.java' "$APP/CleanerNavigation.kt"
grep -q 'Intent.FLAG_ACTIVITY_SINGLE_TOP' "$APP/CleanerNavigation.kt"
! grep -q 'onScan = { context.startActivity' "$CLEAN"
grep -q 'android:name=".ScanWorkbenchActivity"' "$MANIFEST"
grep -q '不会直接删除，可单独移入隔离区' "$WORKBENCH"
grep -q '关键风险项目，只展示不自动清理' "$WORKBENCH"
grep -q '加入白名单' "$WORKBENCH"

echo "scan workbench contract regression passed"
