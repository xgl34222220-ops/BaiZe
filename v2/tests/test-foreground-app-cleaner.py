#!/usr/bin/env python3
"""Foreground cleaner architecture: App owns cleaning, module owns automation only."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize"
CACHE = (APP / "root/BaiZeRootService.kt").read_text(encoding="utf-8")
CACHE_ENGINE = (APP / "root/ForegroundCacheEngine.kt").read_text(encoding="utf-8")
PROFILE = (APP / "root/BaiZeProfileRootService.kt").read_text(encoding="utf-8")
PROFILE_ENGINE = (APP / "root/NativeProfileEngine.kt").read_text(encoding="utf-8")
DASH = (APP / "MiuixDashboardActivity.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "v2/app/build.gradle.kts").read_text(encoding="utf-8")
ROOT_SUPPORT = (APP / "root/RootSupport.kt").read_text(encoding="utf-8")
STORAGE = (APP / "StorageMediaRepository.kt").read_text(encoding="utf-8")
HOME = (APP / "ui/home/miuix/LuoShuHomeScreen.kt").read_text(encoding="utf-8")

def require(value, message):
    if not value:
        raise AssertionError(message)

scan_start = CACHE.index("override fun scanCandidates")
scan_end = CACHE.index("override fun getResultPage", scan_start)
clean_start = CACHE.index("override fun cleanSelected")
clean_end = CACHE.index("override fun getTaskState", clean_start)
scan_path = CACHE[scan_start:scan_end]
clean_path = CACHE[clean_start:clean_end]

require("runForegroundScan" in scan_path, "foreground cache scan must use App-owned engine")
require("runNativeScan" not in scan_path and "cleaner.sh" not in scan_path,
        "foreground cache scan still delegates to module")
require("runForegroundClean" in clean_path, "foreground cache clean must use App-owned engine")
require("runSnapshotClean" not in clean_path and "cleaner.sh" not in clean_path,
        "foreground cache clean still delegates to module")
require("moduleRequired" in CACHE and "false" in CACHE[CACHE.index("moduleRequired")-80:CACHE.index("moduleRequired")+80],
        "cache service must declare module optional for foreground")
require("AppRuleStore.ensure(context)" in PROFILE_ENGINE,
        "foreground profile engine must load App-owned rules")
require('assets.srcDir("../../config")' in GRADLE, "APK must package foreground rules")
require('modulePurpose", "background-automation"' in PROFILE,
        "profile service must identify module as background automation")
require("openForegroundCleaner()" in DASH, "dashboard must have App foreground cleaner entry")
require('scan = { openForegroundCleaner() }' in DASH, "home scan must open App cleaner")
require('clean = { openForegroundCleaner() }' in DASH, "home clean must open App cleaner")
require('apkScan = { startActivity(Intent(this, ApkScanActivity::class.java)) }' in DASH,
        "APK tool must be App-owned")
require('organize = { startActivity(Intent(this, FileOrganizerActivity::class.java)) }' in DASH,
        "organizer tool must be App-owned")
require("ProcessBuilder" not in CACHE_ENGINE and "cleaner.sh" not in CACHE_ENGINE,
        "App cache engine must not shell out to module")
require("FOREGROUND_STATE_DIR" in ROOT_SUPPORT and "app-foreground" in ROOT_SUPPORT,
        "foreground snapshot state must be isolated from automation module state")
require("StorageMediaRepository" in STORAGE and "contentResolver.query" in STORAGE,
        "storage tools must be App-native")
require('actions.largeFiles' in HOME and 'actions.duplicates' in HOME and 'actions.storageAnalysis' in HOME,
        "home must expose App-native storage tools")
require('自动清理模块' in HOME,
        "home must label automation as an optional module capability")

print("foreground App cleaner architecture contract passed")

