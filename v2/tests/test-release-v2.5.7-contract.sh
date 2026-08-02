#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/v2.5.7-release.yml"
cmp "$ROOT/module.prop" "$ROOT/v2/module/module.prop"
grep -q 'versionName = "2.5.7"' "$ROOT/v2/app/build.gradle.kts"
grep -q 'versionCode = 25007' "$ROOT/v2/app/build.gradle.kts"
grep -qx 'version=v2.5.7' "$ROOT/module.prop"
grep -qx 'versionCode=25007' "$ROOT/module.prop"
grep -q 'BaiZe-v2.5.7-Module.zip' "$ROOT/v2/scripts/package-module.sh"
grep -q 'detached-root-worker-v2.5.7' "$ROOT/v2/module/task-worker.sh"
grep -Fqx 'ui_print "- 正在安装白泽 v2.5.7"' "$ROOT/v2/module/customize.sh"
grep -q 'BAIZE_VERSION: v2.5.7' "$WORKFLOW"
grep -q "BAIZE_VERSION_CODE: '25007'" "$WORKFLOW"
grep -q '.github/release-v2.5.7.publish' "$WORKFLOW"
grep -q 'test-settings-draft-rollback-contract.sh' "$WORKFLOW"
grep -q -- "--title '白泽 v2.5.7'" "$WORKFLOW"
grep -q -- '--latest' "$WORKFLOW"
test -s "$ROOT/RELEASE_NOTES_v2.5.7.md"
python3 - "$ROOT/update.json" <<'PY'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == {
    'version': 'v2.5.7',
    'versionCode': 25007,
    'zipUrl': 'https://github.com/xgl34222220-ops/BaiZe/releases/download/v2.5.7/BaiZe-v2.5.7-Module.zip',
    'changelog': 'https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_v2.5.7.md',
}
PY
echo 'v2.5.7 release metadata contract passed'
