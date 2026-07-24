#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
REVIEW_REPO="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/RuleQualityReviewRepository.kt"
ANALYZER="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/RuleQualityAnalyzer.kt"
AUDIT_REPO="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt"
SERVICE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
AIDL="$ROOT/v2/app/src/main/aidl/io/github/xgl34222220/baize/root/IProfileRootService.aidl"
ACTIVITY="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/RuleQualityActivity.kt"
WORKFLOW="$ROOT/.github/workflows/v2.5-concurrent-scheduler-ci.yml"

for file in "$REVIEW_REPO" "$ANALYZER" "$AUDIT_REPO" "$SERVICE" "$AIDL" "$ACTIVITY" "$WORKFLOW"; do
  test -f "$file" || { echo "missing rule review contract file: $file" >&2; exit 1; }
done

# Root owns bounded, atomic review metadata and accepts only server-generated keys/actions.
grep -Fq 'rule-quality-reviews.json' "$REVIEW_REPO"
grep -Fq 'MAX_RECORDS = 200' "$REVIEW_REPO"
grep -Fq 'MAX_NOTE_LENGTH = 200' "$REVIEW_REPO"
grep -Fq 'Regex("^[0-9a-f]{16}$")' "$REVIEW_REPO"
grep -Fq 'setOf("keep", "observe", "ignore", "reset")' "$REVIEW_REPO"
grep -Fq 'val currentEvidence = evidence[ruleKey]' "$REVIEW_REPO"
grep -Fq 'RootFileStore.writeAtomic(reviewFile' "$REVIEW_REPO"

# Review writes cannot mutate real cleaner rules, files, policies, tasks or scheduling.
for file in "$REVIEW_REPO" "$ANALYZER"; do
  ! grep -Fq 'saveSchedulerConfig' "$file"
  ! grep -Fq 'saveConfig(' "$file"
  ! grep -Fq 'deleteRecursively' "$file"
  ! grep -Fq 'Runtime.getRuntime' "$file"
  ! grep -Eq 'disableRule|removeRule|deleteRule|enabled[[:space:]]*=[[:space:]]*false' "$file"
  ! grep -Eq 'schedule_[a-z_]+[[:space:]]*=' "$file"
done
grep -Fq '.put("rulesChanged", false)' "$REVIEW_REPO"
grep -Fq '.put("scheduleUntouched", true)' "$REVIEW_REPO"

# Analyzer merges review state while keeping task evidence path-free and bounded.
grep -Fq 'reviews: Map<String, RuleQualityReview> = emptyMap()' "$ANALYZER"
grep -Fq 'reviewState' "$ANALYZER"
grep -Fq 'reviewNote' "$ANALYZER"
grep -Fq 'reviewedAt' "$ANALYZER"
grep -Fq 'TASK_KINDS' "$ANALYZER"
! grep -Fq 'pathTail' "$ANALYZER"

# Binder validates current queue membership through AuditRepository and records review actions.
grep -Fq 'String updateRuleQualityReview(String ruleKey, String action, String note);' "$AIDL"
grep -Fq 'override fun updateRuleQualityReview' "$SERVICE"
grep -Fq 'updateRuleQualityReviewJson' "$AUDIT_REPO"
grep -Fq 'ruleQualityReviewRepository.update(ruleKey, action, note, report)' "$AUDIT_REPO"
grep -Fq 'rule-review-' "$AUDIT_REPO"
grep -Fq 'operation.contains("rule-review") -> "review"' "$AUDIT_REPO"

# UI exposes all four explicit actions, optional notes and state filters.
for label in '待审核' '观察中' '已保留' '已忽略' '保留规则' '继续观察' '忽略提醒' '重置审核' '审核备注'; do
  grep -Fq "$label" "$ACTIVITY"
done
grep -Fq 'updateRuleQualityReview(item.key, action, note)' "$ACTIVITY"
grep -Fq '仅保存审核状态和备注' "$ACTIVITY"
grep -Fq '只自动重新打开审核状态' "$ACTIVITY"
grep -Fq '不会停用规则、删除文件、修改清理策略或改变任何定时周期' "$ACTIVITY"

# Permanent Root regression executes this contract.
grep -Fq 'test-rule-review-closed-loop-contract.sh' "$WORKFLOW"

echo "rule review closed-loop contract ok"
