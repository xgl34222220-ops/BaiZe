#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize"

assert_contains() {
  local file="$1"
  local pattern="$2"
  grep -Fq "$pattern" "$file" || {
    echo "missing '$pattern' in $file" >&2
    exit 1
  }
}

assert_absent_tree() {
  local pattern="$1"
  if grep -R -F -n --include='*.kt' "$pattern" "$APP"; then
    echo "forbidden shared-skin symbol remains: $pattern" >&2
    exit 1
  fi
}

assert_contains "$APP/ui/home/HomeRoute.kt" "UiStyle.MATERIAL -> HomeScreenMaterial"
assert_contains "$APP/ui/home/HomeRoute.kt" "UiStyle.MIUIX -> HomeScreenMiuix"
assert_contains "$APP/ui/clean/CleanRoute.kt" "UiStyle.MATERIAL -> CleanScreenMaterial"
assert_contains "$APP/ui/clean/CleanRoute.kt" "UiStyle.MIUIX -> CleanScreenMiuix"
assert_contains "$APP/ui/history/HistoryRoute.kt" "UiStyle.MATERIAL -> HistoryScreenMaterial"
assert_contains "$APP/ui/history/HistoryRoute.kt" "UiStyle.MIUIX -> HistoryScreenMiuix"
assert_contains "$APP/ui/settings/SettingsRoute.kt" "UiStyle.MATERIAL -> SettingsScreenMaterial"
assert_contains "$APP/ui/settings/SettingsRoute.kt" "UiStyle.MIUIX -> SettingsScreenMiuix"

assert_contains "$APP/ui/theme/BaiZeTheme.kt" "MaterialBaiZeCorners"
assert_contains "$APP/ui/theme/BaiZeTheme.kt" "MiuixBaiZeCorners"
assert_contains "$APP/ui/theme/BaiZeTheme.kt" "MaterialBaiZeTypeScale"
assert_contains "$APP/ui/theme/BaiZeTheme.kt" "MiuixBaiZeTypeScale"

assert_absent_tree "ProvideVideoSkin"
assert_absent_tree "VideoSkin"
assert_absent_tree "LocalVideoSkin"

for feature in onScan onApkScan onInstantCache onFileOrganizer onDeepClean onCorpses onAudit onApkPackageDaysChanged; do
  assert_contains "$APP/ui/clean/material/CleanScreenMaterial.kt" "actions.$feature"
  assert_contains "$APP/ui/clean/miuix/CleanScreenMiuix.kt" "actions.$feature"
done

assert_contains "$APP/ui/home/material/HomeScreenMaterial.kt" "actions.scan"
assert_contains "$APP/ui/home/miuix/HomeScreenMiuix.kt" "actions.scan"

echo "dual-style UI contract passed"
