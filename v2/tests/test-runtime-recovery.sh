#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/module" "$T/state" "$T/bin"
cp "$ROOT/v2/module/scheduler-v2.5.sh" "$T/module/scheduler.sh"
cat >"$T/bin/dumpsys" <<'DUMP'
#!/bin/sh
printf '%s\n' "$1" >>"$BAIZE_STATE_DIR/dumpsys-calls"
case "$1" in power) echo mInteractive=false;; battery) echo 'level: 100';; esac
DUMP
chmod +x "$T/bin/dumpsys"
cat >"$T/module/task-worker.sh" <<'WORKER'
#!/bin/sh
printf '%s\n' "$1" >>"$BAIZE_STATE_DIR/executed"
exit "${BAIZE_TEST_EXIT:-0}"
WORKER
cat >"$T/state/config.conf" <<'CONFIG'
enabled=1
schedule_mode=0
autopilot_enabled=1
screen_off_only=1
charging_only=0
device_idle_only=0
min_battery=0
schedule_cache_enabled=1
schedule_cache_minutes=60
schedule_empty_enabled=0
schedule_rules_enabled=0
schedule_fragment_enabled=0
schedule_deep_enabled=0
schedule_organize_enabled=0
CONFIG
run_once() {
  PATH="$T/bin:$PATH" BAIZE_MODULE_DIR="$T/module" BAIZE_STATE_DIR="$T/state" BAIZE_SKIP_BOOT_WAIT=1 BAIZE_SCHEDULER_ONCE=1 \
    BAIZE_TEST_EXIT="${BAIZE_TEST_EXIT:-0}" sh "$T/module/scheduler.sh"
}
now=$(date +%s)
last=$((now-7200))
printf '%s\n' "$last" >"$T/state/last_cache_run.epoch"
cat >"$T/state/autopilot-cache.env" <<STATE
schema=actual-run-v2
last_actual_epoch=$last
base_interval_seconds=3600
desired_due=$((now+7200))
updated=$now
STATE
run_once
[ ! -e "$T/state/executed" ]
[ "$(cat "$T/state/last_cache_run.epoch")" = "$last" ]
# A strict interval must immediately ignore smart advice, and query each condition once.
sed -i 's/schedule_mode=0/schedule_mode=1/' "$T/state/config.conf"
run_once
[ "$(wc -l <"$T/state/executed")" -eq 1 ]
[ "$(grep -c '^power$' "$T/state/dumpsys-calls")" -eq 1 ]
[ "$(grep -c '^battery$' "$T/state/dumpsys-calls")" -eq 1 ]
# User cancellation defers the next interval without fabricating a successful run.
printf '%s\n' "$last" >"$T/state/last_cache_run.epoch"
BAIZE_TEST_EXIT=9 run_once
[ "$(cat "$T/state/last_cache_run.epoch")" = "$last" ]
[ "$(cat "$T/state/scheduler-deferred-cache.until")" -gt "$now" ]
run_once
[ "$(wc -l <"$T/state/executed")" -eq 2 ]
# A future wall-clock stamp must not prevent cleanup indefinitely.
rm "$T/state/scheduler-deferred-cache.until"
printf '%s\n' $((now+864000)) >"$T/state/last_cache_run.epoch"
run_once
[ "$(wc -l <"$T/state/executed")" -eq 3 ]
# Real runner persists success even with no scheduler process left to consume its result.
cp "$ROOT/v2/module/worker-runner.sh" "$T/module/worker-runner.sh"
cp "$ROOT/v2/module/task-worker.sh" "$T/module/task-worker.sh"
cat >"$T/module/cleaner.sh" <<'CLEANER'
#!/bin/sh
exit 0
CLEANER
chmod +x "$T/module/cleaner.sh"
mkdir -p "$T/state/index"
printf 'cache=stale\n' >"$T/state/index/meta.env"
BAIZE_STATE_DIR="$T/state" BAIZE_SHELL_BIN=/bin/sh sh "$T/module/task-worker.sh" rules-clean scheduler:interval recovered wait
[ -s "$T/state/last_rules_run.epoch" ]
grep -qx 'exit_code=0' "$T/state/scheduler-result-rules.env"
[ ! -e "$T/state/index/meta.env" ]
# A failed organizer cannot republish the previous successful organizer result/history.
printf 'success=true\nmoved=123\nbytes=456\nphase=旧任务成功\n' >"$T/state/organizer-result.env"
BAIZE_STATE_DIR="$T/state" sh "$T/module/worker-runner.sh" organize app missing-organizer && exit 1 || test "$?" -eq 127
[ ! -e "$T/state/organizer-result.env" ]
! grep -q '旧任务成功' "$T/state/task-results/missing-organizer.env"
# Cancellation travels through the runner and is saved as 9, not a stale success.
cat >"$T/module/cleaner.sh" <<'CLEANER'
#!/bin/sh
: >"$BAIZE_STATE_DIR/stop"
exit 0
CLEANER
BAIZE_STATE_DIR="$T/state" sh "$T/module/worker-runner.sh" cache-auto scheduler:interval cancelled && exit 1 || test "$?" -eq 9
grep -qx 'exit_code=9' "$T/state/task-results/cancelled.env"
# Two boot/App recovery requests must converge on one supervisor/scheduler instance.
cp "$ROOT/v2/module/supervisor.sh" "$T/module/supervisor.sh"
cat >"$T/module/scheduler.sh" <<'SCHEDULER'
#!/bin/sh
printf '%s\n' "$$" >>"$BAIZE_STATE_DIR/supervisor-launches"
sleep 20 & sleeper=$!
trap 'kill "$sleeper" 2>/dev/null || true; exit 0' TERM INT
wait "$sleeper"
SCHEDULER
BAIZE_STATE_DIR="$T/state" BAIZE_MODULE_DIR="$T/module" BAIZE_SUPERVISOR_HEARTBEAT_SECONDS=1 sh "$T/module/supervisor.sh" & supervisor_pid=$!
for _ in $(seq 1 100); do [ -s "$T/state/supervisor-launches" ] && break; sleep 0.02; done
BAIZE_STATE_DIR="$T/state" BAIZE_MODULE_DIR="$T/module" timeout 3 sh "$T/module/supervisor.sh"
[ "$(wc -l <"$T/state/supervisor-launches")" -eq 1 ]
kill "$supervisor_pid"
wait "$supervisor_pid"
[ ! -e "$T/state/supervisor.lock" ]
echo 'runtime recovery: scheduling, condition snapshots, cancellation, completion, result isolation: OK'
