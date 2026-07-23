#!/usr/bin/env python3
from pathlib import Path

# Stage eight trust primitives remain in source for maintainers, but no online updater runs or appears to users.
ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize"
INDEX_SERVICE = (APP / "root/RuleIndexRootService.kt").read_text(encoding="utf-8")
PACK_SERVICE = (APP / "root/RulePackRootService.kt").read_text(encoding="utf-8")
CLIENT = (APP / "RuleUpdateClient.kt").read_text(encoding="utf-8")
WORKER = (APP / "RuleUpdateWorker.kt").read_text(encoding="utf-8")
ACTIVITY = (APP / "RuleUpdateActivity.kt").read_text(encoding="utf-8")
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
    "rule-index-checkpoints", "rule-index-imports",
):
    require(marker in INDEX_SERVICE, f"signed index safety primitive missing: {marker}")
require("openConnection(" not in INDEX_SERVICE and "HttpURLConnection" not in INDEX_SERVICE,
        "Root index verifier must never access the network")

for marker in (
    'setRequestProperty("Range"', 'setRequestProperty("If-Range"', 'getHeaderField("ETag")',
    'getHeaderField("Last-Modified")', "HTTP_PARTIAL", "416", "MAX_REDIRECTS",
    "ALLOWED_HOSTS", "expectedSha256", "expectedBytes", "sha256(file)",
    "RuleIndexRootService", "RulePackRootService", "verifyIndex", "previewPackage",
    "BaiZe-Rules-Index-stable.jar", "BaiZe-Rules-Index-beta.jar",
):
    require(marker in CLIENT, f"archived resumable update primitive missing: {marker}")

for marker in (
    "NetworkType.UNMETERED", "setRequiresCharging(true)", "setRequiresDeviceIdle(true)",
    "ExistingPeriodicWorkPolicy.UPDATE", 'settings.channel == "stable"',
    'settings.policy == "install"', "Result.retry()",
):
    require(marker in WORKER, f"archived automatic update policy missing: {marker}")

for marker in (
    "官方规则更新", "签名索引", "防回放", "断点续传", "FilterChip",
    "稳定版", "Beta", "断点下载并双重验证", "安装已验证规则",
):
    require(marker in ACTIVITY, f"archived online update UI primitive missing: {marker}")

# Product decision: rules ship inside the module. No network permission, component registration, entry, or startup worker.
require("RuleUpdateWorker.ensureScheduled" not in APPLICATION,
        "application must not restore an online update policy")
require('android.permission.INTERNET' not in MANIFEST,
        "built-in-rule product must not request INTERNET")
require('android.permission.ACCESS_NETWORK_STATE' not in MANIFEST,
        "built-in-rule product must not request network state")
require('android:name=".RuleUpdateActivity"' not in MANIFEST,
        "rule update activity must not be registered")
require('android:name=".root.RuleIndexRootService"' not in MANIFEST,
        "rule index Root service must not be registered")
require("RuleUpdateActivity::class.java" not in CENTER and "官方规则更新" not in CENTER,
        "clean center must not expose online rule updates")

require('put("versionCode"' in PACK_SERVICE and 'optLong("versionCode"' in PACK_SERVICE,
        "maintainer rule metadata must expose a monotonic version code")
require('--version-code' in PACK_BUILDER and '"versionCode"' in PACK_BUILDER,
        "rule pack builder must emit monotonic versionCode")
for marker in ("rule-index.json", "--channel", "generatedAt", "expiresAt", "jarsigner", "ALLOWED_HOSTS"):
    require(marker in INDEX_BUILDER, f"rule index builder contract missing: {marker}")
for marker in ("same certificate", "HTTP Range", "If-Range", "monotonic checkpoint", "Beta channel never"):
    require(marker in DOC, f"rule update documentation missing: {marker}")

print("maintainer online rule release primitives contract: ok")
