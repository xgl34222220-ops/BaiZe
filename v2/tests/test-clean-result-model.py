#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/root/CleanPlanResumeRootService.kt").read_text()
ACTIVITY = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ResumableSmartScanActivity.kt").read_text()
SHELL = (ROOT / "v2/module/cache-snapshot-clean.sh").read_text()


def require(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)


# Candidate outcomes are unique final states; engine file/error totals remain separate counters.
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

print("clean result model contract: ok")
