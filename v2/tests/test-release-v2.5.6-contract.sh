#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/v2.5.6-release.yml"
NOTES="$ROOT/RELEASE_NOTES_v2.5.6.md"

# v2.5.6 is an immutable historical release. New versions update the current
# runtime metadata, while the original signed publisher and release notes stay
# frozen and continue to address the v2.5.6 assets.
test -s "$WORKFLOW"
test -s "$NOTES"
grep -q 'name: BaiZe v2.5.6 Immutable Release' "$WORKFLOW"
grep -q 'BAIZE_VERSION: v2.5.6' "$WORKFLOW"
grep -q 'BAIZE_VERSION_NAME: 2.5.6' "$WORKFLOW"
grep -q "BAIZE_VERSION_CODE: '25006'" "$WORKFLOW"
grep -q '.github/release-v2.5.6.publish' "$WORKFLOW"
grep -q 'BaiZe-v2.5.6-Module.zip' "$WORKFLOW"
grep -q 'BaiZe-v2.5.6.apk' "$WORKFLOW"
grep -q 'detached-root-worker-v2.5.6' "$WORKFLOW"
grep -q 'test-apk-retention-contract.sh' "$WORKFLOW"
grep -q -- "--title '白泽 v2.5.6'" "$WORKFLOW"
grep -q -- '--latest' "$WORKFLOW"
echo 'frozen v2.5.6 release metadata contract passed'
