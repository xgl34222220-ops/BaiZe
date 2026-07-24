#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
POLICY="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/CleanupPolicy.kt"
ACTIVITY="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/CleanupPolicyActivity.kt"
SCHEDULER="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRepository.kt"
ENGINE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/NativeProfileEngine.kt"
WORKBENCH="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ScanWorkbenchActivity.kt"
QUARANTINE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/QuarantineRepository.kt"
CENTER="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/CleanCenterActivity.kt"
MANIFEST="$ROOT/v2/app/src/main/AndroidManifest.xml"
DEFAULTS="$ROOT/config/default.conf"

for file in "$POLICY" "$ACTIVITY" "$SCHEDULER" "$ENGINE" "$WORKBENCH" "$QUARANTINE" "$CENTER" "$MANIFEST" "$DEFAULTS"; do
  test -f "$file" || { echo "missing cleanup policy contract file: $file" >&2; exit 1; }
done

# Exactly three guarded presets exist and balanced remains the upgrade-safe default.
grep -Fq 'CONSERVATIVE(' "$POLICY"
grep -Fq 'BALANCED(' "$POLICY"
grep -Fq 'AGGRESSIVE(' "$POLICY"
grep -Fq 'cleanup_policy=1' "$DEFAULTS"
grep -Fq 'fun fromId(id: Int): CleanupPolicy' "$POLICY"

# A policy may not carry or write scheduler cadence fields.
! grep -Eq '"schedule_[^"]+"[[:space:]]+to' "$POLICY"
grep -Fq '!key.startsWith("schedule_")' "$SCHEDULER"
grep -Fq '"cleanup_policy" to 0..2' "$SCHEDULER"

# No preset can enable direct high-risk or custom-rule deletion.
count_high=$(grep -Fc '"deep_high_risk_enabled" to 0' "$POLICY")
count_custom=$(grep -Fc '"clean_custom_rules" to 0' "$POLICY")
test "$count_high" -eq 3
test "$count_custom" -eq 3
! grep -Fq '"deep_high_risk_enabled" to 1' "$POLICY"
! grep -Fq '"clean_custom_rules" to 1' "$POLICY"

# __all_safe__ is bounded by the policy stored in the immutable server snapshot.
grep -Fq 'snapshot.options.maxAutoRisk == "medium"' "$ENGINE"
grep -Fq 'snapshot.options.highRiskMode == "audit"' "$ENGINE"
grep -Fq 'policy_audit_only' "$ENGINE"
grep -Fq '.put("maxAutoRisk", options.maxAutoRisk)' "$ENGINE"
grep -Fq '.put("highRiskMode", options.highRiskMode)' "$ENGINE"

# The workbench reads policy before scanning and only uses it for defaults and quarantine UI.
grep -Fq 'cleanupPolicy = CleanupPolicy.fromId' "$WORKBENCH"
grep -Fq 'cleanupPolicy.defaultSelected(it.risk)' "$WORKBENCH"
grep -Fq '.put("maxAutoRisk", policy.autoRisk)' "$WORKBENCH"
grep -Fq '.put("highRiskMode", policy.highRiskMode)' "$WORKBENCH"
grep -Fq '!cleanupPolicy.canQuarantineHighRisk' "$WORKBENCH"
grep -Fq 'highRiskMode != "audit"' "$WORKBENCH"

# Applying a preset sends only its id; Root expands cleanup fields transactionally.
grep -Fq 'JSONObject().put("cleanup_policy", policy.id)' "$ACTIVITY"
! grep -Fq 'put("schedule_' "$ACTIVITY"
grep -Fq 'policy.values.forEach' "$SCHEDULER"
grep -Fq 'cleanup_policy_customized' "$SCHEDULER"

# Newly quarantined items receive the selected retention, while stored expiry timestamps remain authoritative.
grep -Fq 'quarantine_retention_days' "$QUARANTINE"
grep -Fq 'expiresAt = now + retentionDays * DAY_MS' "$QUARANTINE"
grep -Fq '.put("retentionDays", retentionDays())' "$QUARANTINE"

# UI entry and manifest registration stay connected.
grep -Fq 'CleanupPolicyActivity::class.java' "$CENTER"
grep -Fq 'CleanCenterItem(Icons.Rounded.Tune, "清理策略"' "$CENTER"
grep -Fq '<activity android:name=".CleanupPolicyActivity"' "$MANIFEST"

# User requirement: strategy work must never tighten scheduler ranges.
grep -Fq '"schedule_cache_minutes" to 5..43_200' "$SCHEDULER"
grep -Fq '"schedule_empty_minutes" to 5..43_200' "$SCHEDULER"
grep -Fq '"schedule_rules_minutes" to 5..43_200' "$SCHEDULER"
grep -Fq '"schedule_fragment_minutes" to 5..43_200' "$SCHEDULER"
grep -Fq '"schedule_deep_minutes" to 5..43_200' "$SCHEDULER"
grep -Fq '"schedule_organize_minutes" to 15..43_200' "$SCHEDULER"

echo "cleanup policy contract ok"
