#!/usr/bin/env python3
import runpy
from pathlib import Path

# This test intentionally inspects the call path: a clean action may consume snapshots, but it may
# never fall back to either discovery API. Runtime validation belongs inside the snapshot engines.
ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize"
ACTIVITY = (APP / "PersistentSmartScanActivity.kt").read_text(encoding="utf-8")
RESUMABLE = (APP / "ResumableSmartScanActivity.kt").read_text(encoding="utf-8")
CANDIDATE = (APP / "CandidateSmartScanActivity.kt").read_text(encoding="utf-8")
SERVICE = (APP / "root/PersistentCleanPlanRootService.kt").read_text(encoding="utf-8")
MANIFEST = (ROOT / "v2/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
AIDL = (ROOT / "v2/app/src/main/aidl/io/github/xgl34222220/baize/root/IPersistentCleanPlanService.aidl").read_text(encoding="utf-8")
CLEAN_ROUTE = (APP / "ui/clean/CleanRoute.kt").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


clean_start = ACTIVITY.index("private fun cleanSnapshots()")
clean_end = ACTIVITY.index("private fun stopTask()", clean_start)
clean_path = ACTIVITY[clean_start:clean_end]

require("scanCandidates(" not in clean_path, "clean path must not start a cache scan")
require("scanSafe(" not in clean_path, "clean path must not start a safe-profile scan")
require("cleanSelected(" in clean_path, "clean path must consume the cache snapshot")
require("cleanSafe(" in clean_path, "clean path must consume the persisted safe snapshot")
require("cleanPlanCurrent()" in clean_path, "clean path must validate the plan before mutation")

for symbol in (
    "restoreCleanPlan()",
    "validateRestoredPlan()",
    "persistCleanPlan()",
    "smart_clean_plan_v1",
    "CLEAN_PLAN_TTL_MS",
):
    require(symbol in ACTIVITY, f"missing activity clean-plan contract: {symbol}")

for symbol in (
    "persistNativeSnapshot",
    "persistedPage",
    "cleanPersistedSnapshot",
    "profile-snapshots",
    "optionsSha",
    "snapshot_expired",
):
    require(symbol in SERVICE, f"missing root persistence contract: {symbol}")

require("NativeProfileEngine(this, cancelled)" in SERVICE, "native engine must remain the primary scanner")
require('engine.scan("safe"' in SERVICE, "safe discovery must use the existing scanner")
require("engine.scan(" not in SERVICE[SERVICE.index("private fun cleanPersistedSnapshot"):],
        "persisted fallback cleaner must never rediscover candidates")

require('android:name=".PersistentSmartScanActivity"' in MANIFEST, "persistent activity is not registered")
require('android:name=".ResumableSmartScanActivity"' in MANIFEST, "resumable activity is not registered")
require('android:name=".CandidateSmartScanActivity"' in MANIFEST, "candidate activity is not registered")
require('android:name=".SmartScanActivity"' in MANIFEST, "real smart-scan activity is not registered")
require('android:targetActivity=".CandidateSmartScanActivity"' not in MANIFEST,
        "automatic-first build must not redirect SmartScanActivity through the experimental candidate picker")
require("onScan = dashboardActions.clean" in CLEAN_ROUTE,
        "visible cleanup action must use the proven automatic Root cleaner")
require('android:name=".root.PersistentCleanPlanRootService"' in MANIFEST,
        "persistent root service is not registered")
require("PersistentCleanPlanRootService" in RESUMABLE and "IPersistentCleanPlanService" in RESUMABLE,
        "resumable flow must keep using the persisted safe snapshot engine")
require("ResumableSmartScanActivity::class.java" in CANDIDATE,
        "candidate stage must hand finalized plans to the resumable cleaner")

for method in ("scanSafe", "getPage", "cleanSafe", "getTaskState", "cancelCurrentTask"):
    require(method in AIDL, f"binder method missing: {method}")

runpy.run_path(str(ROOT / "v2/tests/test-candidate-selection-plan.py"), run_name="__main__")
runpy.run_path(str(ROOT / "v2/tests/test-clean-result-report.py"), run_name="__main__")
runpy.run_path(str(ROOT / "v2/tests/test-explainable-rules-whitelist.py"), run_name="__main__")
runpy.run_path(str(ROOT / "v2/tests/test-rule-pack-management.py"), run_name="__main__")
runpy.run_path(str(ROOT / "v2/tests/test-rule-update-channel.py"), run_name="__main__")
runpy.run_path(str(ROOT / "v2/tests/test-rule-release-automation.py"), run_name="__main__")
print("clean plan persistence contract: ok")
