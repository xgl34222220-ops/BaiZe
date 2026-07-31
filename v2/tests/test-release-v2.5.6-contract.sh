#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

grep -q 'versionName = "2.5.6"' "$ROOT/v2/app/build.gradle.kts"
grep -q 'versionCode = 25006' "$ROOT/v2/app/build.gradle.kts"
cmp "$ROOT/module.prop" "$ROOT/v2/module/module.prop"
grep -qx 'version=v2.5.6' "$ROOT/module.prop"
grep -qx 'versionCode=25006' "$ROOT/module.prop"
grep -q 'BaiZe-v2.5.6-Module.zip' "$ROOT/v2/scripts/package-module.sh"
grep -q "apk-scanner.sh | grep -q 'apk-files.nul'" "$ROOT/v2/scripts/package-module.sh"
grep -q 'detached-root-worker-v2.5.6' "$ROOT/v2/module/task-worker.sh"
grep -Fqx 'ui_print "- 正在安装白泽 v2.5.6"' "$ROOT/v2/module/customize.sh"
grep -q 'BAIZE_VERSION: v2.5.6' "$ROOT/.github/workflows/v2.5.6-release.yml"
grep -q "BAIZE_VERSION_CODE: '25006'" "$ROOT/.github/workflows/v2.5.6-release.yml"
grep -q 'test-apk-retention-contract.sh' "$ROOT/.github/workflows/v2.5.6-release.yml"
grep -q -- '--latest' "$ROOT/.github/workflows/v2.5.6-release.yml"
test -s "$ROOT/RELEASE_NOTES_v2.5.6.md"
python3 - "$ROOT/update.json" <<'PY'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == {
    'version': 'v2.5.6',
    'versionCode': 25006,
    'zipUrl': 'https://github.com/xgl34222220-ops/BaiZe/releases/download/v2.5.6/BaiZe-v2.5.6-Module.zip',
    'changelog': 'https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_v2.5.6.md',
}
PY
echo 'v2.5.6 release metadata contract passed'
