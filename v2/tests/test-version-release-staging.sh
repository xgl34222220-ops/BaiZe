#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/v2/scripts" "$T/v2/module" "$T/v2/app"
cp "$ROOT/v2/scripts/sync-version.sh" "$ROOT/v2/scripts/package-module.sh" "$T/v2/scripts/"
cp "$ROOT/module.prop" "$ROOT/update.json" "$T/"
cp "$ROOT/v2/module/module.prop" "$ROOT/v2/module/customize.sh" "$ROOT/v2/module/task-worker.sh" "$T/v2/module/"
cp "$ROOT/v2/app/build.gradle.kts" "$T/v2/app/"
cp "$T/update.json" "$T/previous-update.json"

sh "$T/v2/scripts/sync-version.sh" --source-only --set v2.9.42
cmp "$T/update.json" "$T/previous-update.json"
sh "$T/v2/scripts/sync-version.sh" --source-only --check
if sh "$T/v2/scripts/sync-version.sh" --check >/dev/null 2>&1; then
  printf 'Full check must reject a deferred OTA version\n' >&2
  exit 1
fi
sh "$T/v2/scripts/sync-version.sh"
sh "$T/v2/scripts/sync-version.sh" --check
python3 - "$T" <<'PY'
import json
import pathlib
import sys
root = pathlib.Path(sys.argv[1])
update = json.loads((root / 'update.json').read_text())
assert update['version'] == 'v2.9.42'
assert update['versionCode'] == 29042
assert '/v2.9.42/BaiZe-v2.9.42-Module.zip' in update['zipUrl']
assert update['changelog'].endswith('/RELEASE_NOTES_v2.9.42.md')
assert 'versionName = "2.9.42"' in (root / 'v2/app/build.gradle.kts').read_text()
assert (root / 'module.prop').read_bytes() == (root / 'v2/module/module.prop').read_bytes()
PY
printf 'source version and post-verification OTA sync: ok\n'
