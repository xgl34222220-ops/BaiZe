#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

grep -q 'versionName = "2.5.3"' "$ROOT/v2/app/build.gradle.kts"
grep -q 'versionCode = 25003' "$ROOT/v2/app/build.gradle.kts"
cmp "$ROOT/module.prop" "$ROOT/v2/module/module.prop"
grep -qx 'version=v2.5.3' "$ROOT/module.prop"
grep -qx 'versionCode=25003' "$ROOT/module.prop"
grep -q 'BaiZe-v2.5.3-Module.zip' "$ROOT/v2/scripts/package-module.sh"
grep -q 'detached-root-worker-v2.5.3' "$ROOT/v2/module/task-worker.sh"
grep -Fqx 'ui_print "- 正在安装白泽 v2.5.3"' "$ROOT/v2/module/customize.sh"
grep -q 'BAIZE_RELEASE_TARGET_SHA: 15c56f69f07c6a3d9b21ca664c24875a3735efa0' "$ROOT/.github/workflows/v2.5.3-release.yml"
grep -q -- '--latest' "$ROOT/.github/workflows/v2.5.3-release.yml"
test -s "$ROOT/RELEASE_NOTES_v2.5.3.md"
python3 - "$ROOT/update.json" <<'PY'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == {
    'version': 'v2.5.3',
    'versionCode': 25003,
    'zipUrl': 'https://github.com/xgl34222220-ops/BaiZe/releases/download/v2.5.3/BaiZe-v2.5.3-Module.zip',
    'changelog': 'https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_v2.5.3.md',
}
PY
echo 'v2.5.3 release metadata contract passed'
