#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
# The legacy directory re-walk no longer exists. It must delegate to the same
# bounded original-manifest engine covered by test-deep-manifest.sh and the
# native recovery/performance suites, without authorizing an old directory list.
python3 "$ROOT/tests/test-module-corpse-manifest.py" \
  CorpseManifestContract.test_legacy_deep_entry_delegates_only_to_original_manifest_engine \
  CorpseManifestContract.test_old_directory_plan_cannot_authorize_new_traversal
