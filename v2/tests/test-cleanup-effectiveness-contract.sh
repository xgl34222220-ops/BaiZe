#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ANALYZER="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/CleanupEffectivenessAnalyzer.kt"
AUDIT_REPO="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt"
ACTIVITY="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/CleanupEffectivenessActivity.kt"
AUDIT_UI="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt"
MANIFEST="$ROOT/v2/app/src/main/AndroidManifest.xml"
WORKFLOW="$ROOT/.github/workflows/v2.5-concurrent-scheduler-ci.yml"

for file in "$ANALYZER" "$AUDIT_REPO" "$ACTIVITY" "$AUDIT_UI" "$MANIFEST" "$WORKFLOW"; do
  test -f "$file" || { echo "missing effectiveness contract file: $file" >&2; exit 1; }
done

# The scorer is read-only and cannot alter rules, policy, files, tasks or scheduler cadence.
grep -Fq '.put("readOnly", true)' "$ANALYZER"
grep -Fq '.put("automaticActions", false)' "$ANALYZER"
grep -Fq '.put("rulesChanged", false)' "$ANALYZER"
grep -Fq '.put("scheduleUntouched", true)' "$ANALYZER"
! grep -Fq 'saveSchedulerConfig' "$ANALYZER"
! grep -Fq 'saveConfig(' "$ANALYZER"
! grep -Fq 'deleteRecursively' "$ANALYZER"
! grep -Fq 'Runtime.getRuntime' "$ANALYZER"
! grep -Eq 'schedule_[a-z_]+[[:space:]]*=' "$ANALYZER"

# Only bounded, redacted audit summaries may be consumed; complete paths are forbidden.
grep -Fq 'MAX_INPUT_EVENTS = 160' "$ANALYZER"
grep -Fq 'MAX_RECENT_TASKS = 20' "$ANALYZER"
! grep -Fq 'pathTail' "$ANALYZER"
! grep -Fq 'originalPath' "$ANALYZER"
! grep -Fq 'canonicalPath' "$ANALYZER"

# Four dimensions and weighted overall score remain explicit and bounded.
for dimension in safety benefit speed stability; do
  grep -Fq ".put(\"$dimension\"" "$ANALYZER"
done
grep -Fq 'dimensions.safety * 35 + dimensions.benefit * 30 + dimensions.speed * 15 + dimensions.stability * 20' "$ANALYZER"
grep -Fq '.coerceIn(0, 100)' "$ANALYZER"

# Rule observations require repetition and never become automatic rule changes.
grep -Fq 'MIN_RULE_OBSERVATIONS = 3' "$ANALYZER"
grep -Fq 'MIN_PROCESSED_FOR_LOW_VALUE = 2' "$ANALYZER"
grep -Fq 'frequently_protected' "$ANALYZER"
grep -Fq 'low_value' "$ANALYZER"
! grep -Eq 'disableRule\(|removeRule\(|deleteRule\(|setRuleEnabled\(|ruleEnabled[[:space:]]*=' "$ANALYZER"

# Trend requires samples on both sides and cannot guess from a single task.
grep -Fq 'MIN_TREND_SAMPLES = 2' "$ANALYZER"
grep -Fq 'current.size >= MIN_TREND_SAMPLES && previous.size >= MIN_TREND_SAMPLES' "$ANALYZER"

# Audit response exposes a read-only effectiveness object; the UI has a dedicated screen and audit entry.
grep -Fq 'effectivenessAnalyzer.analyze(combined)' "$AUDIT_REPO"
grep -Fq '.put("effectiveness", effectiveness)' "$AUDIT_REPO"
grep -Fq 'class CleanupEffectivenessActivity' "$ACTIVITY"
for label in '安全性' '收益' '耗时' '稳定性' '规则观察' '最近任务' '只读分析'; do
  grep -Fq "$label" "$ACTIVITY"
done
grep -Fq '不会自动关闭规则、删除文件、切换策略或修改定时周期' "$ACTIVITY"
grep -Fq 'CleanupEffectivenessActivity::class.java' "$AUDIT_UI"
grep -Fq 'android:name=".CleanupEffectivenessActivity"' "$MANIFEST"

# Permanent Root regression must execute this contract.
grep -Fq 'test-cleanup-effectiveness-contract.sh' "$WORKFLOW"

echo "cleanup effectiveness scoring contract ok"
