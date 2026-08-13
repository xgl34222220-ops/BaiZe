#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKBENCH = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ScanWorkbenchActivity.kt").read_text()
SHELL = (ROOT / "v2/module/cache-snapshot-clean.sh").read_text()
LEDGER = (ROOT / "v2/module/record-clean-event.sh").read_text()
PROFILE_SERVICE = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt").read_text()

for marker in ("cleanedCandidates", "failures"):
    assert marker in WORKBENCH, f"canonical result metric missing: {marker}"
assert "recordProfileClean" in PROFILE_SERVICE and "startCursor" in PROFILE_SERVICE
assert "recordNativeTask" not in WORKBENCH, "UI must not duplicate Root-owned cleanup accounting"

assert "risk_low=$deleted_files" not in SHELL, "legacy file-count-as-risk bug remains"
assert "risk_low=$cleaned_candidates" in SHELL, "risk count must use cleaned candidates"
assert "record-clean-event.sh" in SHELL, "module cleanup bypasses lifetime ledger"
assert "clean-events.tsv" in LEDGER and "EVENT_ID" in LEDGER, "idempotent event ledger missing"
print("clean result model contract: ok")
