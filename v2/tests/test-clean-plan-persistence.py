#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = (ROOT / "v2/app/src/main/AndroidManifest.xml").read_text()
WORKBENCH = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ScanWorkbenchActivity.kt").read_text()
SOURCE = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize"
PROFILE_SERVICE = (SOURCE / "root/BaiZeProfileRootService.kt").read_text()

for legacy in ("SmartScanActivity.kt", "PersistentSmartScanActivity.kt", "ResumableSmartScanActivity.kt"):
    assert not (SOURCE / legacy).exists(), f"legacy scan generation remains: {legacy}"

assert "ScanWorkbenchActivity" in MANIFEST, "canonical scan workbench is not registered"
assert "snapshot" in WORKBENCH.lower(), "canonical workbench must retain scan snapshots"
assert "recordProfileClean" in PROFILE_SERVICE, "Root transaction boundary must record actual cleanup results"
assert "recordNativeTask" not in WORKBENCH, "workbench must not double-count module and Root events"
print("canonical clean plan persistence contract: ok")
