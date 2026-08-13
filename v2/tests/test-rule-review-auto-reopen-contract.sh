#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
REVIEW_REPO="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/RuleQualityReviewRepository.kt"
ANALYZER="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/RuleQualityAnalyzer.kt"
AUDIT_REPO="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt"
ACTIVITY="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/RuleQualityActivity.kt"
WORKFLOW="$ROOT/.github/workflows/ci.yml"

for file in "$REVIEW_REPO" "$ANALYZER" "$AUDIT_REPO" "$ACTIVITY" "$WORKFLOW"; do
  test -f "$file" || { echo "missing auto-reopen contract file: $file" >&2; exit 1; }
done

# Reopen only changes Root-owned review metadata, never cleaner rules, files, policy, tasks, or schedules.
grep -Fq 'automaticReviewReopen' "$ANALYZER"
grep -Fq 'reviewMetadataOnly' "$ANALYZER"
grep -Fq '.put("rulesChanged", false)' "$REVIEW_REPO"
grep -Fq '.put("policyUntouched", true)' "$REVIEW_REPO"
grep -Fq '.put("scheduleUntouched", true)' "$REVIEW_REPO"
for file in "$REVIEW_REPO" "$ANALYZER"; do
  ! grep -Fq 'saveSchedulerConfig' "$file"
  ! grep -Fq 'saveConfig(' "$file"
  ! grep -Fq 'deleteRecursively' "$file"
  ! grep -Fq 'Runtime.getRuntime' "$file"
  ! grep -Eq 'disableRule|removeRule|deleteRule|enabled[[:space:]]*=[[:space:]]*false' "$file"
  ! grep -Eq 'schedule_[a-z_]+[[:space:]]*=' "$file"
done

# Every manual review captures a schema-v2 evidence baseline and old records are migrated without reopening.
grep -Fq 'SCHEMA_VERSION = 2' "$REVIEW_REPO"
grep -Fq 'baselineInitialized' "$REVIEW_REPO"
grep -Fq 'withBaseline(current)' "$REVIEW_REPO"
grep -Fq 'subjectKey' "$ANALYZER"
grep -Fq 'newEventsSinceReview' "$ANALYZER"
grep -Fq 'newObservationsSinceReview' "$ANALYZER"

# Reopen requires material new evidence and only applies to kept/ignored states.
grep -Fq 'MIN_NEW_EVENTS = 2' "$REVIEW_REPO"
grep -Fq 'MIN_NEW_OBSERVATIONS = 3' "$REVIEW_REPO"
grep -Fq 'FAILURE_RATE_DELTA = 20' "$REVIEW_REPO"
grep -Fq 'PROTECTION_RATE_DELTA = 25' "$REVIEW_REPO"
grep -Fq 'AUTO_REOPEN_STATES = setOf("kept", "ignored")' "$REVIEW_REPO"
! grep -Fq 'AUTO_REOPEN_STATES = setOf("kept", "observing", "ignored")' "$REVIEW_REPO"
grep -Fq 'review.state !in AUTO_REOPEN_STATES' "$REVIEW_REPO"
grep -Fq 'riskRank(current.risk) > riskRank(review.baselineRisk)' "$REVIEW_REPO"
grep -Fq 'severityRank(current.severity) > severityRank(review.baselineSeverity)' "$REVIEW_REPO"

# Reopen state is persisted once, audited separately, and surfaced in the review UI.
grep -Fq 'ruleQualityReviewRepository.reconcile' "$AUDIT_REPO"
grep -Fq 'rule-review-reopened' "$AUDIT_REPO"
grep -Fq 'operation.contains("rule-review") -> "review"' "$AUDIT_REPO"
for label in '重新打开' '审核已自动重新打开' '只自动重新打开审核状态'; do
  grep -Fq "$label" "$ACTIVITY"
done
grep -Fq 'for test_file in v2/tests/test-*.sh' "$WORKFLOW"

echo "rule review auto-reopen contract ok"
