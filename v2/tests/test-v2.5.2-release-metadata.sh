#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

cmp module.prop v2/module/module.prop
grep -qx 'version=v2.5.2' module.prop
grep -qx 'versionCode=25002' module.prop
grep -q 'versionName = "2.5.2"' v2/app/build.gradle.kts
grep -q 'versionCode = 25002' v2/app/build.gradle.kts
grep -q 'BaiZe-v2.5.2-Module.zip' v2/scripts/package-module.sh
grep -q 'detached-root-worker-v2.5.2' v2/module/task-worker.sh
grep -Fqx 'ui_print "- 正在安装白泽 v2.5.2"' v2/module/customize.sh
test -s RELEASE_NOTES_v2.5.2.md

python3 - <<'PY'
import json
from pathlib import Path
assert json.loads(Path('update.json').read_text()) == {
    'version': 'v2.5.2',
    'versionCode': 25002,
    'zipUrl': 'https://github.com/xgl34222220-ops/BaiZe/releases/download/v2.5.2/BaiZe-v2.5.2-Module.zip',
    'changelog': 'https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_v2.5.2.md',
}
PY

WORKFLOW=.github/workflows/v2.5.2-release.yml
test -s "$WORKFLOW"
grep -q 'tags:' "$WORKFLOW"
grep -q -- "- 'v2.5.2'" "$WORKFLOW"
! grep -q 'workflow_dispatch' "$WORKFLOW"
grep -q 'Reject an existing v2.5.2 Release' "$WORKFLOW"
grep -q 'gh release create v2.5.2' "$WORKFLOW"
grep -q -- '--verify-tag' "$WORKFLOW"
! grep -q 'gh release edit' "$WORKFLOW"
! grep -q -- '--clobber' "$WORKFLOW"
! grep -q 'git/refs/tags/v2.5.2' "$WORKFLOW"

grep -q 'BaiZe v2.5.1 Historical Release Guard' .github/workflows/v2.5.1-release.yml
! grep -q 'branches: \[main\]' .github/workflows/v2.5.1-release.yml

grep -q 'RUNTIME_SCHEMA=deep-manifest-v1' v2/module/service.sh
grep -q 'snapshot_schema=deep-file-manifest-v1' v2/module/deep-scan-manifest.sh
grep -q 'deep_manifest_cursor' v2/module/deep-manifest-clean.sh
grep -q 'bin/arm64-v8a/baize_deep_snapshot' v2/scripts/package-module.sh

echo 'v2.5.2 immutable release metadata: ok'
