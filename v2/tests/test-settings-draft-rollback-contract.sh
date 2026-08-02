#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ROUTE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/settings/SettingsRoute.kt"
for expected in \
  'var draft by remember { mutableStateOf(scheduler.copy(saving = false)) }' \
  'var dirty by remember { mutableStateOf(false) }' \
  'var saveRequested by remember { mutableStateOf(false) }' \
  'LaunchedEffect(scheduler)' \
  'scheduler.hasSameEditableConfig(draft)' \
  'draft.withRuntimeFrom(scheduler)' \
  'private fun SchedulerUiState.hasSameEditableConfig' \
  'private fun SchedulerUiState.withRuntimeFrom(remote: SchedulerUiState)'; do
  grep -Fq "$expected" "$ROUTE"
done
grep -Fq 'onUpdateScheduler = { updated ->' "$ROUTE"
grep -Fq 'onSaveScheduler = { requested ->' "$ROUTE"
! grep -Fq 'onUpdateScheduler = dashboardActions.updateScheduler' "$ROUTE"
! grep -Fq 'onSaveScheduler = dashboardActions.saveScheduler' "$ROUTE"
echo 'settings draft rollback regression contract passed'
