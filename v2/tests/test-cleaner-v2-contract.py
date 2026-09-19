#!/usr/bin/env python3
"""Cleaner v2 UX/performance contracts."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize"
SMART = (APP / "ResumableSmartScanActivity.kt").read_text(encoding="utf-8")
HISTORY = (APP / "AppTaskHistoryStore.kt").read_text(encoding="utf-8")
DASH = (APP / "MiuixDashboardActivity.kt").read_text(encoding="utf-8")
CACHE = (APP / "root/ForegroundCacheEngine.kt").read_text(encoding="utf-8")

def require(value, message):
    if not value:
        raise AssertionError(message)

require("SmartCleanCategory" in SMART, "one-tap cleaner must expose category selection")
require("ResumeSelectableRow" in SMART and "Checkbox(" in SMART,
        "scan result must provide category checkboxes")
require("screenState.cacheSelected" in SMART and "screenState.apkSelected" in SMART and "screenState.safeSelected" in SMART,
        "clean path must honor category selections")
require('DetailPageHeader("一键清理"' in SMART, "primary scan page must present itself as one-tap cleaner")
require("AppTaskHistoryStore.append" in SMART, "manual one-tap clean must write persistent history")
require("LastCleanupStore.save" in SMART, "manual one-tap clean must persist the latest result summary")
require("AppTaskHistoryStore.read" in DASH and "appHistory.entries + moduleEntries" in DASH,
        "history page must merge App and automation records")
require("AppTaskHistoryStore.clearRecent" in DASH, "clear-history must clear App manual history")
require("MAX_ENTRIES = 100" in HISTORY, "manual history must remain bounded")
require("Executors.newFixedThreadPool" in CACHE and "coerceIn(2, 4)" in CACHE,
        "foreground cache scan must use bounded parallel workers")
require("find " not in CACHE and "ProcessBuilder" not in CACHE,
        "foreground cache engine must not shell out or run full-storage find")

print("cleaner v2 UX/performance contract passed")

