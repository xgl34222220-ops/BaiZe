#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/v2.5.3-release.yml"

grep -q 'BAIZE_VERSION: v2.5.3' "$WORKFLOW"
grep -q "BAIZE_VERSION_CODE: '25003'" "$WORKFLOW"
grep -q 'BAIZE_RELEASE_TARGET_SHA: 15c56f69f07c6a3d9b21ca664c24875a3735efa0' "$WORKFLOW"
grep -q 'BaiZe-v2.5.3-Module.zip' "$WORKFLOW"
grep -q -- '--latest' "$WORKFLOW"
test -s "$ROOT/RELEASE_NOTES_v2.5.3.md"
echo 'frozen v2.5.3 release metadata contract passed'
