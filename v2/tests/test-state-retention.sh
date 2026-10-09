#!/usr/bin/env bash
# Ledger bounds for /data/adb/baize-v2 (boot restorecon cost scales with file count).
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
SCRIPTS="$ROOT/v2/module/scripts"
SH=sh; command -v busybox >/dev/null 2>&1 && SH="busybox ash"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/task-results" "$T/logs"
i=0
while [ "$i" -lt 260 ]; do
  : >"$T/task-results/task $i.env"; touch -t "$(printf '202601011%03d.00' $((i % 60)))" "$T/task-results/task $i.env" 2>/dev/null || true
  i=$((i + 1))
done
# Deterministic ordering: newest are 250..259.
for i in 250 251 252 253 254 255 256 257 258 259; do touch "$T/task-results/task $i.env"; done
: >"$T/task-results/stale.started"; touch -d '2 hours ago' "$T/task-results/stale.started"
: >"$T/task-results/live.started"
: >"$T/task-results/notes.txt"
j=0; while [ "$j" -lt 230 ]; do : >"$T/logs/worker-$j.log"; j=$((j + 1)); done
: >"$T/logs/supervisor-scheduler.log"
$SH -c ". '$SCRIPTS/state-retention.sh'; BAIZE_TASK_RESULTS_KEEP=200; baize_prune_task_results '$T/task-results'; baize_prune_task_logs '$T/logs'"
envs=$(find "$T/task-results" -name '*.env' | wc -l)
[ "$envs" -eq 200 ] || { echo "expected 200 results, got $envs"; exit 1; }
[ -f "$T/task-results/task 259.env" ] || { echo "newest result pruned"; exit 1; }
[ ! -e "$T/task-results/stale.started" ] || { echo "stale startup ack kept"; exit 1; }
[ -f "$T/task-results/live.started" ] || { echo "live startup ack removed"; exit 1; }
[ -f "$T/task-results/notes.txt" ] || { echo "unrelated file removed"; exit 1; }
logs=$(find "$T/logs" -name 'worker-*.log' | wc -l)
[ "$logs" -eq 200 ] || { echo "expected 200 worker logs, got $logs"; exit 1; }
[ -f "$T/logs/supervisor-scheduler.log" ] || { echo "service log removed"; exit 1; }
grep -q 'state-migrate.sh' "$ROOT/v2/module/service.sh"
# Migration must be launched in the background from service.sh (late_start), never post-fs-data.
grep -q 'state-migrate.log" 2>&1 &' "$ROOT/v2/module/service.sh"
! grep -rq 'state-migrate' "$ROOT/v2/module/post-fs-data.sh" 2>/dev/null
# Without an App runtime the migration is a no-op that keeps every receipt.
mkdir -p "$T/cleanup-media/completed/done-x"; : >"$T/cleanup-media/completed/done-x/ack-0"
BAIZE_MODULE_DIR="$T/nomodule" BAIZE_ROOT_STATE_DIR="$T" BAIZE_APP_PROCESS=/nonexistent $SH "$SCRIPTS/state-migrate.sh" >/dev/null
[ -f "$T/cleanup-media/completed/done-x/ack-0" ] || { echo "receipt removed without verified archive"; exit 1; }
[ ! -e "$T/state-migrate.lock" ] || { echo "migration lock leaked"; exit 1; }
echo "state retention ok"
