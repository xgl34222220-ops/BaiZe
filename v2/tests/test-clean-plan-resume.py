#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize"
ACTIVITY = (APP / "ResumableSmartScanActivity.kt").read_text(encoding="utf-8")
CANDIDATE = (APP / "CandidateSmartScanActivity.kt").read_text(encoding="utf-8")
SERVICE = (APP / "root/CleanPlanResumeRootService.kt").read_text(encoding="utf-8")
MANIFEST = (ROOT / "v2/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
AIDL = (ROOT / "v2/app/src/main/aidl/io/github/xgl34222220/baize/root/ICleanPlanResumeService.aidl").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


clean_start = ACTIVITY.index("private fun cleanSnapshots()")
clean_end = ACTIVITY.index("private fun stopTask()", clean_start)
clean_path = ACTIVITY[clean_start:clean_end]
require("scanCandidates(" not in clean_path, "resume path must not start cache discovery")
require("scanSafe(" not in clean_path, "resume path must not start safe discovery")
require("transactions.begin(" in clean_path, "cleaning must begin a transaction")
require("checkpointCache" in clean_path, "cache stage must checkpoint")
require("checkpointSafe" in clean_path, "safe stage must checkpoint")
require("transactions.recover(" in clean_path, "failure path must recover transaction state")
require("继续清理" in ACTIVITY, "resume action is not visible to the user")
require("smart_clean_plan_v2" in ACTIVITY, "resume plan schema is missing")
require("LEGACY_PLAN_KEY" in ACTIVITY, "v1 clean plans must migrate")

clear_start = ACTIVITY.index("private fun clearLocalPlan()")
clear_end = ACTIVITY.index("private fun resetPlanFields()", clear_start)
clear_path = ACTIVITY[clear_start:clear_end]
require("resetPlanFields()" not in clear_path, "completed cleanup must preserve final metrics")
require('cacheSummary = "应用缓存剩余 $cacheCount 项"' in ACTIVITY,
        "transaction checkpoints must refresh cache remaining summary")
require('safeSummary = "安全项目剩余 $safeCount 项"' in ACTIVITY,
        "transaction checkpoints must refresh safe remaining summary")

for marker in (
    "clean-plan-transactions",
    "backupCacheSnapshot",
    "restoreCacheIfMissing",
    "filterCacheSnapshot",
    "filterSafeSnapshot",
    "atomicWrite",
    "remainingCandidates",
):
    require(marker in SERVICE, f"missing transaction primitive: {marker}")

require('JSONObject(response(state)).put("recovered", true)' in SERVICE,
        "recover must return the transaction payload with its recovered flag")
require('android:name=".ResumableSmartScanActivity"' in MANIFEST, "resume activity is not registered")
require('android:targetActivity=".CandidateSmartScanActivity"' in MANIFEST,
        "smart scan alias must enter candidate selection before resume")
require("ResumableSmartScanActivity::class.java" in CANDIDATE,
        "candidate selection must hand finalized snapshots to resume flow")
require('android:name=".root.CleanPlanResumeRootService"' in MANIFEST, "resume Root service is not registered")

for method in ("begin", "checkpointCache", "checkpointSafe", "recover", "finish"):
    require(method in AIDL, f"resume binder method missing: {method}")

print("clean plan resume contract: ok")
