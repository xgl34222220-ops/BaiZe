#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ADVISOR="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/PolicyAdvisor.kt"
AUDIT_REPO="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt"
POLICY_UI="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/CleanupPolicyActivity.kt"
AUDIT_UI="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt"
POLICY="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/CleanupPolicy.kt"

for file in "$ADVISOR" "$AUDIT_REPO" "$POLICY_UI" "$AUDIT_UI" "$POLICY"; do
  test -f "$file" || { echo "missing advisor contract file: $file" >&2; exit 1; }
done

# The advisor is read-only and explicitly reports that it never applies a policy or changes cadence.
grep -Fq '.put("automatic", false)' "$ADVISOR"
grep -Fq '.put("scheduleUntouched", true)' "$ADVISOR"
! grep -Fq 'saveSchedulerConfig' "$ADVISOR"
! grep -Fq 'saveConfig(' "$ADVISOR"
! grep -Eq 'schedule_[a-z_]+[[:space:]]*to' "$ADVISOR"

# Recommendations must use bounded, coarse signals rather than paths or arbitrary user input.
grep -Fq 'storageFreePercent' "$ADVISOR"
grep -Fq 'failureRate' "$ADVISOR"
grep -Fq 'restoreRate' "$ADVISOR"
grep -Fq 'protectionRate' "$ADVISOR"
grep -Fq 'averageScanMs' "$ADVISOR"
grep -Fq 'MAX_INPUT_EVENTS = 120' "$ADVISOR"
! grep -Fq 'pathTail' "$ADVISOR"
! grep -Fq 'originalPath' "$ADVISOR"

# Safety evidence always wins. Upgrades and pressure-relief changes require a real history sample.
grep -Fq 'storage.freePercent in 0..8 && evidenceCount >= MIN_EVIDENCE' "$ADVISOR"
grep -Fq 'storage.freePercent in 9..15 && evidenceCount >= MIN_EVIDENCE' "$ADVISOR"
grep -Fq 'storage.freePercent >= 20 && evidenceCount >= MIN_EVIDENCE' "$ADVISOR"
python3 - "$ADVISOR" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text()
safety = text.index('safetyConcern ->')
critical = text.index('storage.freePercent in 0..8')
insufficient = text.index('evidenceCount < MIN_EVIDENCE')
keep = text.index('recommended = current')
if not (keep < safety < critical < insufficient):
    raise SystemExit('advisor decision order must remain safety-first and keep-current by default')
PY

# Audit response exposes the advisor; both screens consume it, but only the existing explicit apply action writes policy.
grep -Fq 'policyAdvisor.evaluate(combined)' "$AUDIT_REPO"
grep -Fq '.put("advisor", advisor)' "$AUDIT_REPO"
grep -Fq 'root.getAuditTimelinePage(0, 100)' "$POLICY_UI"
grep -Fq 'PolicyAdviceCard' "$POLICY_UI"
grep -Fq '仅建议，不会自动切换' "$POLICY_UI"
grep -Fq 'onApply = { onSelect(advice.recommendedPolicy) }' "$POLICY_UI"
grep -Fq 'AuditPolicyAdviceCard' "$AUDIT_UI"
grep -Fq 'CleanupPolicyActivity::class.java' "$AUDIT_UI"

# Presets themselves still contain no scheduling fields and cannot enable direct high-risk deletion.
! grep -Eq '"schedule_[^"]+"[[:space:]]+to' "$POLICY"
grep -Fq '"deep_high_risk_enabled" to 0' "$POLICY"


echo "adaptive policy advisor contract ok"
