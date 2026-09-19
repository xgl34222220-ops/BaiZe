#!/usr/bin/env python3
"""Unified smart scan must include MediaStore APK discovery and cleanup."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize"
SMART = (APP / "ResumableSmartScanActivity.kt").read_text(encoding="utf-8")
APK = (APP / "ApkScanActivity.kt").read_text(encoding="utf-8")
INDEX = (APP / "ApkMediaStoreIndex.kt").read_text(encoding="utf-8")

def require(condition, message):
    if not condition:
        raise AssertionError(message)

require("val apkJob = async { scanApkForSmartClean() }" in SMART,
        "smart scan must discover APKs in parallel")
require("val total = cacheCount + safeCount + apkCount" in SMART,
        "smart scan total must include APK candidates")
require('ResumeSummaryRow("安装包", state.apkSummary)' in SMART,
        "smart scan UI must show APK summary")
require("async(Dispatchers.IO) { cleanApkForSmartClean() }" in SMART,
        "smart cleanup must delete APKs in parallel")
require("ApkMediaStoreIndex.deleteIfUnchanged" in SMART,
        "smart cleanup must delete APKs through MediaStore record validation")
require("cleanReady = snapshots.isNotEmpty()" in APK,
        "standalone APK results must expose cleanup")
require("ApkMediaStoreIndex.deleteIfUnchanged" in APK,
        "standalone APK cleanup must use MediaStore deletion")
require("ContentUris.withAppendedId" in INDEX and "contentResolver.delete" in INDEX,
        "MediaStore index must retain record URI and delete through ContentResolver")
require("Os.lstat(candidate.path)" not in APK,
        "standalone scan must not hide MediaStore hits behind path lstat")

print("unified MediaStore APK smart-clean contract passed")

