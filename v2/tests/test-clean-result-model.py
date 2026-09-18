#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/root/CleanPlanResumeRootService.kt").read_text()
ACTIVITY = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ResumableSmartScanActivity.kt").read_text()
SHELL = (ROOT / "v2/module/cache-snapshot-clean.sh").read_text()
CACHE_SERVICE = (APP / "root/BaiZeRootService.kt").read_text()
WORKBENCH = (APP / "ScanWorkbenchActivity.kt").read_text()
CACHE_ACTIVITY = (APP / "CacheActivity.kt").read_text()


def require(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)


# Candidate outcomes are unique final states; engine file/error totals remain separate counters.
# Keep this contract active after rebasing the result model onto the release baseline.
for marker in (
    "itemStates", "processedCandidates", "changedCandidates", "protectedCandidates",
    "partialCandidates", "failedCandidates", "categoryStats", "riskStats",
    "classifiedDeletedBytes", "unattributedDeletedBytes", "captureCacheOutcomes",
    "captureSafeOutcomes", "rebuildResultMetrics"
):
    require(marker in SERVICE, f"missing result model primitive: {marker}")

require('private const val TRANSACTION_VERSION = 2' in SERVICE, "transaction schema was not upgraded")
require('risk = "low"' in SERVICE, "cache candidate risk must be explicit")
require('normalizeCategory' in SERVICE and 'normalizeRisk' in SERVICE, "classification normalization missing")

for marker in (
    "processedCandidates", "changedCandidates", "protectedCandidates", "partialCandidates",
    "failedCandidates", "categoryStats", "riskStats", "formatMetricBuckets"
):
    require(marker in ACTIVITY, f"result metric is not persisted/rendered: {marker}")

require('risk_low=$deleted_files' not in SHELL, "legacy file-count-as-risk bug remains")
require('risk_low=$cleaned_candidates' in SHELL, "risk count must use cleaned candidates")
require('authorized_candidates=' in SHELL and 'processed_candidates=' in SHELL, "module result schema missing")
require('category_cache_cleaned=' in SHELL, "module category summary missing")

# A successful shell exit is not proof that anything was actually deleted.
require('else if (code == 0) beforeCount' not in CACHE_SERVICE,
        "cache service still converts zero mutations into fake cleaned candidates")
for marker in (
    'cleaned_candidates', 'changed_candidates', 'protected_candidates',
    'partial_candidates', 'failed_candidates', 'skipped_candidates',
    '.put("mutated", mutated)'
):
    require(marker in CACHE_SERVICE, f"cache service does not expose real outcome field: {marker}")
require('cacheMutated' in WORKBENCH and '实际删除 $cacheDeletedFiles 个文件' in WORKBENCH,
        "workbench still does not gate success on real cache mutations")
require('profile.getModuleState()).optJSONArray("appDetails")' not in WORKBENCH,
        "workbench still reuses stale scan app details as cleanup results")
require('val mutated = deletedFiles > 0L || cleanedCandidates > 0' in CACHE_ACTIVITY,
        "cache detail page still treats exit code as cleanup success")
require('result.optBoolean("success") && mutated' in SERVICE,
        "resume transaction can still complete a cache plan without a real mutation")

print("clean result model contract: ok")
