#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/v2.5.5-release.yml"
NOTES="$ROOT/RELEASE_NOTES_v2.5.5.md"

# v2.5.5 is an immutable historical release. New versions must not require the
# current source tree to keep v2.5.5 runtime metadata; only its frozen publisher
# and release notes must remain unchanged and address the original assets.
test -s "$WORKFLOW"
test -s "$NOTES"
grep -q 'name: BaiZe v2.5.5 Immutable Release' "$WORKFLOW"
grep -q 'BAIZE_VERSION: v2.5.5' "$WORKFLOW"
grep -q 'BAIZE_VERSION_NAME: 2.5.5' "$WORKFLOW"
grep -q "BAIZE_VERSION_CODE: '25005'" "$WORKFLOW"
grep -q 'BaiZe-v2.5.5-Module.zip' "$WORKFLOW"
grep -q 'BaiZe-v2.5.5.apk' "$WORKFLOW"
grep -q 'detached-root-worker-v2.5.5' "$WORKFLOW"
grep -q 'test-uninstall-cleanup.sh' "$WORKFLOW"
grep -q -- '--title '\''白泽 v2.5.5'\''' "$WORKFLOW"
grep -q -- '--latest' "$WORKFLOW"
echo 'frozen v2.5.5 release metadata contract passed'
