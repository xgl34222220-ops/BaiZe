#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SCAN="$ROOT/module/native-scan.sh"
CLEAN="$ROOT/module/deep-manifest-clean.sh"
ENGINE="$ROOT/native/baize_deep_snapshot.c"

grep -q 'case "$TRIGGER" in scheduler:\*) allow_high=0' "$SCAN"
grep -q 'case "$TRIGGER" in scheduler:\*) SAFE_ONLY=1' "$CLEAN"
grep -q -- '--safe-only "$SAFE_ONLY"' "$CLEAN"
grep -q '!g_options.safe_only || safe_risk(risk)' "$ENGINE"
grep -q 'strcmp(risk, "low") == 0 || strcmp(risk, "medium") == 0' "$ENGINE"

echo "scheduled deep safety boundary contract passed"
