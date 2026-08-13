#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/release.yml"
VALIDATOR="$ROOT/v2/scripts/validate-release-evidence.py"
test -f "$WORKFLOW" && test -f "$VALIDATOR"

grep -Fq 'workflow_dispatch:' "$WORKFLOW"
! grep -Eq '^[[:space:]]+(push|pull_request|schedule):' "$WORKFLOW"
grep -Fq "inputs.confirm == 'RELEASE'" "$WORKFLOW"
grep -Fq 'test -z "$(git ls-remote --tags origin' "$WORKFLOW"
grep -Fq 'v2/release-evidence/$RELEASE_TAG.json' "$WORKFLOW"
grep -Fq 'environment: formal-release' "$WORKFLOW"
grep -Fq 'Require successful unified CI for this commit' "$WORKFLOW"
grep -Fq 'test "$GITHUB_REF_NAME" = "$DEFAULT_BRANCH"' "$WORKFLOW"
grep -Fq "aarch64-linux-android26-clang" "$WORKFLOW"
grep -Fq 'BAIZE_KEYSTORE_BASE64' "$WORKFLOW"
grep -Fq 'sha256sum ./* > SHA256SUMS' "$WORKFLOW"

grep -Fq 'len(devices) < 2' "$VALIDATOR"
grep -Fq 'device.get("kind") != "physical"' "$VALIDATOR"
grep -Fq 'device.get("abi") != "arm64-v8a"' "$VALIDATOR"
grep -Fq 'if 26 not in apis' "$VALIDATOR"
grep -Fq 'multi_user_guard' "$VALIDATOR"
echo 'manual immutable formal release gate: ok'
