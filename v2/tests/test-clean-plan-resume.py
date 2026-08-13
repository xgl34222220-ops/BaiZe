#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = (ROOT / "v2/app/src/main/AndroidManifest.xml").read_text()
MODULE = ROOT / "v2/module"

assert "PersistentCleanPlanRootService" not in MANIFEST
assert "CleanPlanResumeRootService" not in MANIFEST
for script in ("deep-manifest-clean.sh", "profile-snapshot-clean-fast.sh"):
    text = (MODULE / script).read_text()
    assert "cursor" in text.lower() or "remaining" in text.lower(), f"resume state missing from {script}"
    assert "snapshot_id" in text, f"snapshot identity missing from {script}"
print("root snapshot resume contract: ok")
