#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ANALYZER="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/RuleImprovementDraftAnalyzer.kt"
AUDIT_REPO="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt"
ACTIVITY="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/RuleImprovementDraftsActivity.kt"
AUDIT_UI="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt"
MANIFEST="$ROOT/v2/app/src/main/AndroidManifest.xml"

for file in "$ANALYZER" "$AUDIT_REPO" "$ACTIVITY" "$AUDIT_UI" "$MANIFEST"; do
  test -f "$file" || { echo "missing rule improvement drafts contract file: $file" >&2; exit 1; }
done

# Draft generation is conceptual and read-only; no executable patch or rule text is read.
grep -Fq '.put("readOnly", true)' "$ANALYZER"
grep -Fq '.put("manualOnly", true)' "$ANALYZER"
grep -Fq '.put("conceptualPreview", true)' "$ANALYZER"
grep -Fq '.put("exactPatchIncluded", false)' "$ANALYZER"
grep -Fq '.put("ruleTextRead", false)' "$ANALYZER"
grep -Fq '.put("ruleFilesRead", false)' "$ANALYZER"
grep -Fq '.put("automaticActions", false)' "$ANALYZER"
grep -Fq '.put("reviewMetadataChanged", false)' "$ANALYZER"
grep -Fq '.put("rulesChanged", false)' "$ANALYZER"
grep -Fq '.put("policyUntouched", true)' "$ANALYZER"
grep -Fq '.put("tasksUntouched", true)' "$ANALYZER"
grep -Fq '.put("snapshotsUntouched", true)' "$ANALYZER"
grep -Fq '.put("scheduleUntouched", true)' "$ANALYZER"
grep -Fq '.put("pathsIncluded", false)' "$ANALYZER"

# Inputs and outputs remain bounded and path-free.
grep -Fq 'MAX_QUEUE_ITEMS = 40' "$ANALYZER"
grep -Fq 'MAX_TREND_ITEMS = 20' "$ANALYZER"
grep -Fq 'MAX_DRAFTS = 20' "$ANALYZER"
grep -Fq 'MAX_EVIDENCE_ITEMS = 6' "$ANALYZER"
! grep -Fq 'pathTail' "$ANALYZER"
! grep -Fq 'originalPath' "$ANALYZER"
! grep -Fq 'canonicalPath' "$ANALYZER"

# Only human-review draft types are emitted.
for action in consider_disable strengthen_protection narrow_scope observe; do
  grep -Fq "\"$action\"" "$ANALYZER"
done
for label in '考虑停用草案' '增强保护草案' '缩小范围草案' '继续观察草案'; do
  grep -Fq "$label" "$ANALYZER"
done

# Analyzer cannot mutate real rules, files, policies, tasks, snapshots or scheduling.
! grep -Fq 'saveSchedulerConfig' "$ANALYZER"
! grep -Fq 'saveConfig(' "$ANALYZER"
! grep -Fq 'deleteRecursively' "$ANALYZER"
! grep -Fq 'Runtime.getRuntime' "$ANALYZER"
! grep -Eq 'disableRule|removeRule|deleteRule|enabled[[:space:]]*=[[:space:]]*false' "$ANALYZER"
! grep -Eq 'schedule_[a-z_]+[[:space:]]*=' "$ANALYZER"
! grep -Eq 'startScan|startClean|cleanSnapshot|createSnapshot' "$ANALYZER"

# Root response and dedicated UI expose the report without a new mutation API.
grep -Fq 'RuleImprovementDraftAnalyzer()' "$AUDIT_REPO"
grep -Fq 'ruleImprovementDraftAnalyzer.analyze(ruleQuality, ruleReviewTrends)' "$AUDIT_REPO"
grep -Fq '.put("ruleImprovementDrafts", ruleImprovementDrafts)' "$AUDIT_REPO"
grep -Fq 'class RuleImprovementDraftsActivity' "$ACTIVITY"
for label in '规则改进草案' '只读概念草案' '安全差异预览（概念）' '考虑停用' '缩小范围' '增强保护' '继续观察'; do
  grep -Fq "$label" "$ACTIVITY"
done
grep -Fq '不会停用规则、写入规则文件、删除文件、启动清理、修改策略、快照或任何定时周期' "$ACTIVITY"
grep -Fq 'RuleImprovementDraftsActivity::class.java' "$AUDIT_UI"
grep -Fq '规则改进建议草案' "$AUDIT_UI"
grep -Fq 'android:name=".RuleImprovementDraftsActivity"' "$MANIFEST"

# Permanent Root regression executes this contract.

echo "rule improvement drafts contract ok"
