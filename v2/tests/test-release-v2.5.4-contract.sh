#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/v2.5.4-release.yml"

grep -q 'BAIZE_VERSION: v2.5.4' "$WORKFLOW"
grep -q "BAIZE_VERSION_CODE: '25004'" "$WORKFLOW"
grep -q 'BAIZE_RELEASE_TARGET_SHA: 9cf1056055a3f2fb1b74b566ec41cd268f5e853b' "$WORKFLOW"
grep -q 'BaiZe-v2.5.4-Module.zip' "$WORKFLOW"
grep -q -- '--latest' "$WORKFLOW"
test -s "$ROOT/RELEASE_NOTES_v2.5.4.md"
echo 'frozen v2.5.4 release metadata contract passed'
