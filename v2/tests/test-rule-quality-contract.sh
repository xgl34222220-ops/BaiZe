#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ANALYZER="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/RuleQualityAnalyzer.kt"
AUDIT_REPO="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt"
ACTIVITY="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/RuleQualityActivity.kt"
AUDIT_UI="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt"
MANIFEST="$ROOT/v2/app/src/main/AndroidManifest.xml"
WORKFLOW="$ROOT/.github/workflows/v2.5-concurrent-scheduler-ci.yml"

for file in "$ANALYZER" "$AUDIT_REPO" "$ACTIVITY" "$AUDIT_UI" "$MANIFEST" "$WORKFLOW"; do
  test -f "$file" || { echo "missing rule quality contract file: $file" >&2; exit 1; }
done

# Analysis is review-only and cannot mutate rules, policy, tasks, files or scheduler cadence.
grep -Fq '.put("readOnly", true)' "$ANALYZER"
grep -Fq '.put("reviewOnly", true)' "$ANALYZER"
grep -Fq '.put("automaticActions", false)' "$ANALYZER"
grep -Fq '.put("rulesChanged", false)' "$ANALYZER"
grep -Fq '.put("policyUntouched", true)' "$ANALYZER"
grep -Fq '.put("scheduleUntouched", true)' "$ANALYZER"
grep -Fq '.put("pathsIncluded", false)' "$ANALYZER"
! grep -Fq 'saveSchedulerConfig' "$ANALYZER"
! grep -Fq 'saveConfig(' "$ANALYZER"
! grep -Fq 'deleteRecursively' "$ANALYZER"
! grep -Fq 'Runtime.getRuntime' "$ANALYZER"
! grep -Eq 'disableRule|removeRule|deleteRule|enabled[[:space:]]*=[[:space:]]*false' "$ANALYZER"
! grep -Eq 'schedule_[a-z_]+[[:space:]]*=' "$ANALYZER"

# Only bounded, already-redacted audit fields are accepted; full paths are forbidden.
grep -Fq 'MAX_INPUT_EVENTS = 180' "$ANALYZER"
grep -Fq 'MAX_DETAILS_PER_EVENT = 120' "$ANALYZER"
! grep -Fq 'pathTail' "$ANALYZER"
! grep -Fq 'originalPath' "$ANALYZER"
! grep -Fq 'canonicalPath' "$ANALYZER"

# Review classifications require repeated evidence and use explicit thresholds.
grep -Fq 'MIN_RULE_EVENTS = 3' "$ANALYZER"
grep -Fq 'MIN_RULE_OBSERVATIONS = 3' "$ANALYZER"
grep -Fq 'MIN_ZERO_HIT_EVENTS = 4' "$ANALYZER"
grep -Fq 'HIGH_FAILURE_RATE = 40' "$ANALYZER"
grep -Fq 'FREQUENT_PROTECTION_RATE = 50' "$ANALYZER"
for type in high_failure frequently_protected zero_hit low_value; do
  grep -Fq "\"$type\"" "$ANALYZER"
done
for recommendation in consider_disable narrow_scope observe keep; do
  grep -Fq "\"$recommendation\"" "$ANALYZER"
done

# Root exposes the report and UI provides a dedicated, non-actionable review screen.
grep -Fq 'ruleQualityAnalyzer.analyze(combined)' "$AUDIT_REPO"
grep -Fq '.put("ruleQuality", ruleQuality)' "$AUDIT_REPO"
grep -Fq 'class RuleQualityActivity' "$ACTIVITY"
for label in '规则质量中心' '高失败' '频繁保护' '零命中' '低收益' '只读人工审核'; do
  grep -Fq "$label" "$ACTIVITY"
done
grep -Fq '不会自动停用规则、删除文件、修改清理策略或改变任何定时周期' "$ACTIVITY"
grep -Fq 'RuleQualityActivity::class.java' "$AUDIT_UI"
grep -Fq 'android:name=".RuleQualityActivity"' "$MANIFEST"

# Permanent Root regression executes this contract.
grep -Fq 'test-rule-quality-contract.sh' "$WORKFLOW"

echo "rule quality center contract ok"
