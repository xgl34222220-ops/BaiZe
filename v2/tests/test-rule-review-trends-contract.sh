#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ANALYZER="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/RuleReviewTrendAnalyzer.kt"
AUDIT_REPO="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt"
ACTIVITY="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/RuleReviewTrendsActivity.kt"
AUDIT_UI="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt"
MANIFEST="$ROOT/v2/app/src/main/AndroidManifest.xml"

for file in "$ANALYZER" "$AUDIT_REPO" "$ACTIVITY" "$AUDIT_UI" "$MANIFEST"; do
  test -f "$file" || { echo "missing rule review trends contract file: $file" >&2; exit 1; }
done

# Trend analysis is bounded, read-only and path-free.
grep -Fq 'LOOKBACK_DAYS = 90' "$ANALYZER"
grep -Fq 'MAX_INPUT_EVENTS = 240' "$ANALYZER"
grep -Fq 'MAX_ITEMS = 20' "$ANALYZER"
grep -Fq '.put("readOnly", true)' "$ANALYZER"
grep -Fq '.put("automaticActions", false)' "$ANALYZER"
grep -Fq '.put("reviewMetadataChanged", false)' "$ANALYZER"
grep -Fq '.put("rulesChanged", false)' "$ANALYZER"
grep -Fq '.put("policyUntouched", true)' "$ANALYZER"
grep -Fq '.put("scheduleUntouched", true)' "$ANALYZER"
grep -Fq '.put("pathsIncluded", false)' "$ANALYZER"
! grep -Fq 'pathTail' "$ANALYZER"
! grep -Fq 'originalPath' "$ANALYZER"
! grep -Fq 'canonicalPath' "$ANALYZER"

# Analyzer cannot mutate cleaner rules, files, policy, tasks, snapshots or scheduling.
! grep -Fq 'saveSchedulerConfig' "$ANALYZER"
! grep -Fq 'saveConfig(' "$ANALYZER"
! grep -Fq 'deleteRecursively' "$ANALYZER"
! grep -Fq 'Runtime.getRuntime' "$ANALYZER"
! grep -Eq 'disableRule|removeRule|deleteRule|enabled[[:space:]]*=[[:space:]]*false' "$ANALYZER"
! grep -Eq 'schedule_[a-z_]+[[:space:]]*=' "$ANALYZER"

# Report includes history, repeated reopen, reason and handling-cycle metrics.
for marker in 'reopenCount' 'repeatedlyReopenedCount' 'activeReopenCount' 'resolutionRate' 'averageResolutionMs' 'medianResolutionMs' 'reasonBreakdown' 'weekly'; do
  grep -Fq "$marker" "$ANALYZER"
done
grep -Fq 'REPEATED_REOPEN_THRESHOLD = 2' "$ANALYZER"
grep -Fq 'TREND_WINDOW_DAYS = 14' "$ANALYZER"
for reason in failure_rate protection_rate risk_raise severity_raise type_escalation; do
  grep -Fq "\"$reason\"" "$ANALYZER"
done

# Root exposes the report and UI has a dedicated MIUIx-compatible entry.
grep -Fq 'RuleReviewTrendAnalyzer()' "$AUDIT_REPO"
grep -Fq '.put("ruleReviewTrends", ruleReviewTrends)' "$AUDIT_REPO"
grep -Fq 'class RuleReviewTrendsActivity' "$ACTIVITY"
for label in '审核历史与趋势' '反复重开' '主要恶化原因' '近八周审核趋势' '只读趋势分析'; do
  grep -Fq "$label" "$ACTIVITY"
done
grep -Fq '不会自动处理审核、停用规则、删除文件、切换策略或改变任何定时周期' "$ACTIVITY"
grep -Fq 'RuleReviewTrendsActivity::class.java' "$AUDIT_UI"
grep -Fq 'android:name=".RuleReviewTrendsActivity"' "$MANIFEST"

# Permanent Root regression executes this contract.

echo "rule review trends contract ok"
