#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize"
ACTIVITY = (APP / "CandidateSmartScanActivity.kt").read_text(encoding="utf-8")
SERVICE = (APP / "root/CandidatePlanRootService.kt").read_text(encoding="utf-8")
AIDL = (ROOT / "v2/app/src/main/aidl/io/github/xgl34222220/baize/root/ICandidatePlanService.aidl").read_text(encoding="utf-8")
MANIFEST = (ROOT / "v2/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


require('android:name=".CandidateSmartScanActivity"' in MANIFEST, "candidate selection activity is not registered")
require('android:targetActivity=".CandidateSmartScanActivity"' in MANIFEST,
        "smart scan entry must route through candidate selection")
require('android:name=".root.CandidatePlanRootService"' in MANIFEST,
        "candidate plan Root service is not registered")
require("finalizePlan" in AIDL, "candidate plan binder method is missing")

for marker in (
    "loadAllPages", "getResultPage", "getPage", "按应用", "按类别",
    "excludedCandidateIds", "__exclude__", "toggleCandidate", "toggleGroup",
    "生成计划并进入清理", "仅锁定最终计划"
):
    require(marker in ACTIVITY, f"candidate selection UI contract is missing: {marker}")

finalize_start = ACTIVITY.index("private fun finalizeSelection")
finalize_end = ACTIVITY.index("private fun openResumableClean", finalize_start)
finalize_path = ACTIVITY[finalize_start:finalize_end]
require("finalizePlan(" in finalize_path, "final selection must be committed by the Root service")
require("scanCandidates(" not in finalize_path and "scanSafe(" not in finalize_path,
        "finalizing a selection must never start discovery")
require("ResumableSmartScanActivity::class.java" in ACTIVITY,
        "final candidate plan must hand off to the verified resumable cleaner")

for marker in (
    "candidate-plan-stage", "prepareCache", "prepareSafe", "commitCache", "rollbackCache",
    "commitSafe", "rollbackSafe", "cache_scan.manifest0", "profile-snapshots",
    "MANIFEST_FIELDS = 10", "underSelectedRoot", "snapshot_id", "manifest_sha"
):
    require(marker in SERVICE, f"Root candidate-plan transaction is missing: {marker}")

require("scanCandidates(" not in SERVICE and "scanSafe(" not in SERVICE and "engine.scan(" not in SERVICE,
        "candidate plan service must only reduce existing snapshots, never discover paths")
require("selectedItems.put(item)" in SERVICE, "safe snapshot must be reduced to explicit candidates")
require("manifestItems != expectedFiles" in SERVICE,
        "cache manifest and selected candidate file counts must be cross-checked")
require("selected <= 0" in SERVICE and "empty_selection" in SERVICE,
        "empty candidate plans must be rejected")

print("candidate selection plan contract: ok")
