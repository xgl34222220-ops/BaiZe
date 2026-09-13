#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/v2/scripts" "$TMP/v2/module" "$TMP/v2/app"
cp "$ROOT/v2/scripts/sync-version.sh" "$TMP/v2/scripts/"
printf 'version=v1.0.0\nversionCode=30003\n' > "$TMP/module.prop"
cp "$TMP/module.prop" "$TMP/v2/module/module.prop"
printf 'versionCode = 30003\nversionName = "1.0.0"\n' > "$TMP/v2/app/build.gradle.kts"
printf 'OUTPUT="$OUT/BaiZe-$VERSION-Module.zip"\n' > "$TMP/v2/scripts/package-module.sh"
printf 'ui_print "- 正在安装白泽 v1.0.0"\n' > "$TMP/v2/module/customize.sh"
printf 'detached-root-worker-v1.0.0\n' > "$TMP/v2/module/task-worker.sh"
printf '{"versionCode":30002}\n' > "$TMP/update.json"
code=30003
for version in v1.1.1 v2.0.0 v2.2.2 v3.0.0 v3.3.3 v4.0.0; do
  sh "$TMP/v2/scripts/sync-version.sh" --source-only --next
  code=$((code+1))
  grep -Fxq "version=$version" "$TMP/module.prop"
  grep -Fxq "versionCode=$code" "$TMP/module.prop"
  sh "$TMP/v2/scripts/sync-version.sh" --source-only --check
  grep -Fq '30002' "$TMP/update.json"
done
if sh "$TMP/v2/scripts/sync-version.sh" --source-only --set v4.0.1; then exit 1; fi
sh "$TMP/v2/scripts/sync-version.sh"
sh "$TMP/v2/scripts/sync-version.sh" --check
grep -Fq 'releases/refactor-v4.0.0/' "$TMP/update.json"
echo 'refactor numbering and monotonic upgrades passed'
