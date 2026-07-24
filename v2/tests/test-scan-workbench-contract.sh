#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
APP="$ROOT/app/src/main/java/io/github/xgl34222220/baize"
AIDL="$ROOT/app/src/main/aidl/io/github/xgl34222220/baize/root/IProfileRootService.aidl"
SELECTION="$APP/root/CacheSelectionRepository.kt"
WORKBENCH="$APP/ScanWorkbenchActivity.kt"
HOME="$APP/ui/home/HomeRoute.kt"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"

for method in prepareCacheSelection getWhitelistPaths addWhitelistPath; do
  grep -q "String $method" "$AIDL"
done

grep -q 'cache_scan.manifest0' "$SELECTION"
grep -q 'MANIFEST_FIELD_COUNT = 10' "$SELECTION"
grep -q 'verifyHash(manifestFile' "$SELECTION"
grep -q 'selection_parent_snapshot' "$SELECTION"
grep -q 'path == root || path.startsWith("$root/")' "$SELECTION"

# Cleaning must consume the selected immutable snapshot and never invoke a scan from the clean path.
grep -q 'prepareCacheSelection(cacheSnapshotId' "$WORKBENCH"
grep -q 'cache.cleanSelected' "$WORKBENCH"
grep -q 'profile.cleanProfileSelected' "$WORKBENCH"
CLEAN_SECTION=$(sed -n '/private fun cleanSelection()/,/private fun protectItem/p' "$WORKBENCH")
! printf '%s\n' "$CLEAN_SECTION" | grep -q 'scanCandidates'
! printf '%s\n' "$CLEAN_SECTION" | grep -q 'scanProfile'

grep -q 'ScanWorkbenchActivity::class.java' "$HOME"
grep -q 'android:name=".ScanWorkbenchActivity"' "$MANIFEST"
grep -q '默认只审计，不自动勾选' "$WORKBENCH"
grep -q '加入白名单' "$WORKBENCH"

echo "scan workbench contract regression passed"
