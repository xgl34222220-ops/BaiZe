#!/usr/bin/env python3
from pathlib import Path

# Fifth-stage contract: reporting is read-only, paginated, and archived before transaction removal.
ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize"
ACTIVITY = (APP / "CleanResultActivity.kt").read_text(encoding="utf-8")
RESUMABLE = (APP / "ResumableSmartScanActivity.kt").read_text(encoding="utf-8")
SERVICE = (APP / "root/CleanResultRootService.kt").read_text(encoding="utf-8")
AIDL = (ROOT / "v2/app/src/main/aidl/io/github/xgl34222220/baize/root/ICleanResultService.aidl").read_text(encoding="utf-8")
MANIFEST = (ROOT / "v2/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")


def require(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)


for method in ("registerPlan", "archive", "getSummary", "getPage"):
    require(method in AIDL, f"clean result binder method missing: {method}")

for marker in (
    "clean-result-reports", "summary.json", "items.ndjson", "latest.txt",
    "compactSummary", "resultItems", "MAX_PAGE_SIZE", "REPORT_TTL_MS",
    "estimatedBytes", "actualDeletedBytes", "completionPercent", "spaceRecoveryPercent",
    "defaultReason", "actionPriority", "remove(\"itemStates\")"
):
    require(marker in SERVICE, f"result archive primitive missing: {marker}")

require("scanCandidates(" not in SERVICE and "scanSafe(" not in SERVICE and "engine.scan(" not in SERVICE,
        "result service must never discover candidates")
require("MAX_PAGE_SIZE = 100" in SERVICE, "result pages must stay Binder-safe")
require("items.ndjson" in SERVICE and "MAX_RESULT_ITEMS" in SERVICE,
        "item outcomes must be stored outside the compact summary")

for marker in (
    "BEFORE / AFTER", "清理前后对比", "逐项结果", "受保护", "已变化",
    "getSummary", "getPage", "FilterChip", "加载更多"
):
    require(marker in ACTIVITY, f"result UI contract missing: {marker}")

require("results.registerPlan" in RESUMABLE, "clean flow must register before-clean totals")
require("results.archive" in RESUMABLE, "completed transactions must be archived before finish")
clean_start = RESUMABLE.index("private fun cleanSnapshots()")
clean_end = RESUMABLE.index("private fun openResultReport()", clean_start)
clean_path = RESUMABLE[clean_start:clean_end]
require("scanCandidates(" not in clean_path and "scanSafe(" not in clean_path,
        "report integration must not reintroduce discovery into clean")
archive_pos = clean_path.index("results.archive")
finish_pos = clean_path.index("transactions.finish", archive_pos)
require(archive_pos < finish_pos, "result archive must happen before transaction deletion")
require("CleanResultActivity::class.java" in RESUMABLE, "resumable page must open the item report")
require("PREF_LAST_RESULT_ID" in RESUMABLE, "latest archived report must survive process death")

require('android:name=".CleanResultActivity"' in MANIFEST, "result activity is not registered")
require('android:name=".root.CleanResultRootService"' in MANIFEST, "result Root service is not registered")

print("clean result report contract: ok")
