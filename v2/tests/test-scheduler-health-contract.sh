#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REPOSITORY="$ROOT/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRepository.kt"
OVERLAY="$ROOT/app/src/main/java/io/github/xgl34222220/baize/ui/settings/SchedulerHealthOverlay.kt"
SETTINGS_ROUTE="$ROOT/app/src/main/java/io/github/xgl34222220/baize/ui/settings/SettingsRoute.kt"

for command in scheduler-health scheduler-self-test scheduler-repair scheduler-export-diagnostics; do
  grep -q "\"$command\"" "$REPOSITORY"
done
for code in SERVICE_UNHEALTHY WAIT_SCREEN_OFF WAIT_CHARGING WAIT_BATTERY WAIT_IDLE TASK_CONFLICT RECOVERING RETRY_BACKOFF QUEUED WAIT_NEXT_RUN; do
  grep -q "\"$code\"" "$REPOSITORY"
done
grep -q 'put("blockedGroups", blockedGroups)' "$REPOSITORY"
grep -q '不会修改任何定时周期' "$OVERLAY"
! grep -q 'SchedulerHealthOverlay(' "$SETTINGS_ROUTE"

# User requirement: health diagnostics must not tighten scheduler intervals.
grep -q '"schedule_cache_minutes" to 5..43_200' "$REPOSITORY"
grep -q '"schedule_empty_minutes" to 5..43_200' "$REPOSITORY"
grep -q '"schedule_rules_minutes" to 5..43_200' "$REPOSITORY"
grep -q '"schedule_fragment_minutes" to 5..43_200' "$REPOSITORY"
grep -q '"schedule_deep_minutes" to 5..43_200' "$REPOSITORY"
grep -q '"schedule_organize_minutes" to 15..43_200' "$REPOSITORY"

echo "scheduler health contract regression passed"
