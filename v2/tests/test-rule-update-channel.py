#!/usr/bin/env python3
from pathlib import Path

# Stage eight contract: both the signed index and downloaded pack must cross separate Root trust boundaries.
ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize"
INDEX_SERVICE = (APP / "root/RuleIndexRootService.kt").read_text(encoding="utf-8")
PACK_SERVICE = (APP / "root/RulePackRootService.kt").read_text(encoding="utf-8")
CLIENT = (APP / "RuleUpdateClient.kt").read_text(encoding="utf-8")
WORKER = (APP / "RuleUpdateWorker.kt").read_text(encoding="utf-8")
ACTIVITY = (APP / "RuleUpdateActivity.kt").read_text(encoding="utf-8")
NOTIFIER = (APP / "NativeNotifier.kt").read_text(encoding="utf-8")
APPLICATION = (APP / "BaiZeApplication.kt").read_text(encoding="utf-8")
AIDL = (ROOT / "v2/app/src/main/aidl/io/github/xgl34222220/baize/root/IRuleIndexService.aidl").read_text(encoding="utf-8")
MANIFEST = (ROOT / "v2/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
CENTER = (APP / "CleanCenterActivity.kt").read_text(encoding="utf-8")
PACK_BUILDER = (ROOT / "v2/tools/build-rule-pack.py").read_text(encoding="utf-8")
INDEX_BUILDER = (ROOT / "v2/tools/build-rule-index.py").read_text(encoding="utf-8")
DOC = (ROOT / "v2/docs/rule-update-channel.md").read_text(encoding="utf-8")


def require(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)


for method in ("ping", "verifyIndex", "getCheckpoint"):
    require(method in AIDL, f"signed index binder method missing: {method}")

for marker in (
    "JarFile(file, true)", "entry.certificates", "trustedSignerFingerprints",
    "index_signer_mismatch", "index_unsigned", "index_mixed_signers",
    "enforceCheckpoint", "index_replay", "index_equivocation",
    "generatedAt", "expiresAt", "MAX_INDEX_LIFETIME_MS",
    "validateDownloadUrl", "ALLOWED_DOWNLOAD_HOSTS", "release_hash_invalid",
    "rule-index-checkpoints", "rule-index-imports"
):
    require(marker in INDEX_SERVICE, f"signed index safety primitive missing: {marker}")
require("openConnection(" not in INDEX_SERVICE and "HttpURLConnection" not in INDEX_SERVICE,
        "Root index verifier must never access the network")

for marker in (
    'setRequestProperty("Range"', 'setRequestProperty("If-Range"', 'getHeaderField("ETag")',
    'getHeaderField("Last-Modified")', "HTTP_PARTIAL", "416", "MAX_REDIRECTS",
    "ALLOWED_HOSTS", "expectedSha256", "expectedBytes", "sha256(file)",
    "RuleIndexRootService", "RulePackRootService", "verifyIndex", "previewPackage",
    "BaiZe-Rules-Index-stable.jar", "BaiZe-Rules-Index-beta.jar"
):
    require(marker in CLIENT, f"resumable update client primitive missing: {marker}")

for marker in (
    "NetworkType.UNMETERED", "setRequiresCharging(true)", "setRequiresDeviceIdle(true)",
    "ExistingPeriodicWorkPolicy.UPDATE", 'settings.channel == "stable"',
    'settings.policy == "install"', "Result.retry()"
):
    require(marker in WORKER, f"automatic update policy missing: {marker}")

for marker in (
    "官方规则更新", "签名索引", "防回放", "断点续传", "FilterChip",
    "稳定版", "Beta", "断点下载并双重验证", "安装已验证规则"
):
    require(marker in ACTIVITY, f"online update UI contract missing: {marker}")

require("showRuleUpdate" in NOTIFIER and "RuleUpdateActivity::class.java" in NOTIFIER,
        "rule update notification must open the update page")
require("RuleUpdateWorker.ensureScheduled" in APPLICATION,
        "application must restore the selected update policy")
require('android.permission.INTERNET' in MANIFEST, "online update requires INTERNET permission")
require('android.permission.ACCESS_NETWORK_STATE' in MANIFEST, "update worker requires network state permission")
require('android:name=".RuleUpdateActivity"' in MANIFEST, "rule update activity is not registered")
require('android:name=".root.RuleIndexRootService"' in MANIFEST, "rule index Root service is not registered")
require("RuleUpdateActivity::class.java" in CENTER and "官方规则更新" in CENTER,
        "clean center must expose official rule updates")

require('put("versionCode"' in PACK_SERVICE and 'optLong("versionCode"' in PACK_SERVICE,
        "installed rule metadata must expose a monotonic version code")
require('--version-code' in PACK_BUILDER and '"versionCode"' in PACK_BUILDER,
        "rule pack builder must emit monotonic versionCode")
for marker in ("rule-index.json", "--channel", "generatedAt", "expiresAt", "jarsigner", "ALLOWED_HOSTS"):
    require(marker in INDEX_BUILDER, f"rule index builder contract missing: {marker}")
for marker in ("same certificate", "HTTP Range", "If-Range", "monotonic checkpoint", "Beta channel never"):
    require(marker in DOC, f"rule update documentation missing: {marker}")

print("signed online rule update channel contract: ok")
