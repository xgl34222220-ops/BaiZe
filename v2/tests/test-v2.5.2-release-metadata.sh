#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

# v2.5.2 is a frozen historical release. Current main may advance, but its
# release notes and publisher must remain immutable and non-overwriting.
test -s RELEASE_NOTES_v2.5.2.md
grep -q '^# 白泽 v2.5.2$' RELEASE_NOTES_v2.5.2.md

WORKFLOW=.github/workflows/v2.5.2-release.yml
test -s "$WORKFLOW"
grep -q "BAIZE_VERSION: v2.5.2" "$WORKFLOW"
grep -q "BAIZE_VERSION_CODE: '25002'" "$WORKFLOW"
grep -q 'BAIZE_RELEASE_TARGET_SHA: bfd437727d476d3f4a386cf0cea62402abe027ce' "$WORKFLOW"
grep -q 'Reject an existing v2.5.2 Release' "$WORKFLOW"
grep -q 'gh release create v2.5.2' "$WORKFLOW"
grep -q -- '--verify-tag' "$WORKFLOW"
! grep -q 'gh release edit' "$WORKFLOW"
! grep -q -- '--clobber' "$WORKFLOW"

grep -q 'BaiZe v2.5.1 Historical Release Guard' .github/workflows/v2.5.1-release.yml
! grep -q 'branches: \[main\]' .github/workflows/v2.5.1-release.yml

grep -q 'RUNTIME_SCHEMA=deep-manifest-v1' v2/module/service.sh
grep -q 'snapshot_schema=deep-file-manifest-v1' v2/module/deep-scan-manifest.sh
grep -q 'deep_manifest_cursor' v2/module/deep-manifest-clean.sh
grep -q 'bin/arm64-v8a/baize_deep_snapshot' v2/scripts/package-module.sh

echo 'v2.5.2 immutable historical release metadata: ok'
