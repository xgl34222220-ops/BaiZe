#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
SCHEDULER=${BAIZE_SCHEDULER_SOURCE:-$ROOT/service.sh}
WORKER_RUNNER=${BAIZE_WORKER_RUNNER_SOURCE:-$ROOT/v2/module/worker-runner.sh}
T=${TMPDIR:-/tmp}/baize-v241-scheduler-test
rm -rf "$T"; mkdir -p "$T/module/config" "$T/state/scheduler-requests" "$T/state/scheduler-skips"
cp "$SCHEDULER" "$T/module/scheduler.sh"
cat > "$T/module/task-worker.sh" <<'SH2'
#!/bin/sh
printf '%s\t%s\t%s\n' "$1" "$2" "$3" >>"${BAIZE_STATE_DIR}/executed.tsv"
if [ -f "${BAIZE_STATE_DIR}/worker-exit-code" ]; then
  code=$(sed -n '1p' "${BAIZE_STATE_DIR}/worker-exit-code")
  case "$code" in ''|*[!0-9]*) code=1 ;; esac
  exit "$code"
fi
exit 0
SH2
chmod +x "$T/module/task-worker.sh"
base_config() {
cat > "$T/state/config.conf" <<'CONF'
enabled=1
screen_off_only=0
charging_only=0
device_idle_only=0
min_battery=0
max_battery_temp=60
daily_schedule_enabled=0
schedule_cache_enabled=1
schedule_cache_minutes=30
schedule_cache_hours=1
schedule_empty_enabled=1
schedule_empty_minutes=30
schedule_empty_hours=1
schedule_rules_enabled=0
schedule_fragment_enabled=0
schedule_deep_enabled=0
schedule_deep_minutes=10080
schedule_deep_hours=168
schedule_organize_enabled=1
schedule_organize_minutes=30
schedule_organize_hours=1
organize_screen_off_only=0
organize_charging_only=0
organize_device_idle_only=0
CONF
}
base_config
now=$(date +%s)
printf '%s\n' $((now-7200)) > "$T/state/last_cache_run.epoch"
printf '%s\n' $((now-3600)) > "$T/state/last_empty_run.epoch"
printf '%s\n' "$now" > "$T/state/last_organize_run.epoch"
cat > "$T/state/scheduler-requests/1-organize.env" <<EOF2
group=organize
created=$((now-100))
request_id=1
reason=test
EOF2
run_once() {
  BAIZE_MODULE_DIR="$T/module" BAIZE_STATE_DIR="$T/state" BAIZE_CONFIG_PATH="$T/state/config.conf" \
  BAIZE_SKIP_BOOT_WAIT=1 BAIZE_SCHEDULER_ONCE=1 sh "$T/module/scheduler.sh"
}
run_once
[ "$(sed -n '1s/\t.*//p' "$T/state/executed.tsv")" = organize ]
run_once
[ "$(sed -n '2s/\t.*//p' "$T/state/executed.tsv")" = cache-auto ]
run_once
[ "$(sed -n '3s/\t.*//p' "$T/state/executed.tsv")" = empty-clean ]

# A blocked organizer must not starve a runnable cache task.
: > "$T/state/executed.tsv"
sed -i 's/^organize_screen_off_only=.*/organize_screen_off_only=1/' "$T/state/config.conf"
printf '%s\n' $((now-7200)) > "$T/state/last_organize_run.epoch"
printf '%s\n' $((now-3600)) > "$T/state/last_cache_run.epoch"
printf '%s\n' "$now" > "$T/state/last_empty_run.epoch"
run_once
[ "$(sed -n '1s/\t.*//p' "$T/state/executed.tsv")" = cache-auto ]
grep -q 'organize:等待息屏' "$T/state/scheduler.env"

# Skip advances only the requested group without disabling it.
: > "$T/state/scheduler-skips/organize.request"
run_once
test -s "$T/state/last_organize_run.epoch"
[ ! -e "$T/state/scheduler-skips/organize.request" ]

# A scheduled deep task must be a scan-then-clean transaction, never a bare deep-clean.
rm -rf "$T/state"; mkdir -p "$T/state/scheduler-requests" "$T/state/scheduler-skips"
base_config
sed -i 's/^schedule_cache_enabled=.*/schedule_cache_enabled=0/; s/^schedule_empty_enabled=.*/schedule_empty_enabled=0/; s/^schedule_organize_enabled=.*/schedule_organize_enabled=0/; s/^schedule_deep_enabled=.*/schedule_deep_enabled=1/' "$T/state/config.conf"
printf '%s\n' $((now-700000)) > "$T/state/last_deep_run.epoch"
run_once
[ "$(sed -n '1s/\t.*//p' "$T/state/executed.tsv")" = deep-auto ]

# The detached runner must create the immutable snapshot before consuming it.
D="$T/deep-runner"; rm -rf "$D"; mkdir -p "$D/module" "$D/state"
cp "$WORKER_RUNNER" "$D/module/worker-runner.sh"
cat > "$D/module/cleaner.sh" <<'SH2'
#!/bin/sh
printf '%s\n' "$1" >>"${BAIZE_STATE_DIR}/deep-order.txt"
exit 0
SH2
chmod +x "$D/module/cleaner.sh" "$D/module/worker-runner.sh"
BAIZE_STATE_DIR="$D/state" sh "$D/module/worker-runner.sh" deep-auto scheduler:test deep-transaction
[ "$(sed -n '1p' "$D/state/deep-order.txt")" = deep-scan ]
[ "$(sed -n '2p' "$D/state/deep-order.txt")" = deep-clean ]
grep -q '^exit_code=0$' "$D/state/task-results/deep-transaction.env"

# A hard failure receives a five-minute retry delay immediately and cannot storm-retry.
rm -rf "$T/state"; mkdir -p "$T/state/scheduler-requests" "$T/state/scheduler-skips"
base_config
sed -i 's/^schedule_empty_enabled=.*/schedule_empty_enabled=0/; s/^schedule_organize_enabled=.*/schedule_organize_enabled=0/' "$T/state/config.conf"
printf '%s\n' $((now-7200)) > "$T/state/last_cache_run.epoch"
printf '7\n' > "$T/state/worker-exit-code"
: > "$T/state/executed.tsv"
before=$(date +%s)
run_once
[ "$(cat "$T/state/scheduler-fail-cache.count")" = 1 ]
until=$(cat "$T/state/scheduler-pause-cache.until")
[ "$until" -ge $((before+250)) ] && [ "$until" -le $((before+360)) ]
grep -q '^exit_code=7$' "$T/state/scheduler-fail-cache.env"
run_once
[ "$(wc -l < "$T/state/executed.tsv" | tr -d ' ')" = 1 ]
grep -q 'cache:上次代码7' "$T/state/scheduler.env"

# A deliberate manual request clears stale backoff and retries immediately.
rm -f "$T/state/worker-exit-code"
cat > "$T/state/scheduler-requests/manual-cache.env" <<EOF2
group=cache
created=$(date +%s)
request_id=manual-cache
reason=manual
EOF2
run_once
[ "$(wc -l < "$T/state/executed.tsv" | tr -d ' ')" = 2 ]
[ ! -e "$T/state/scheduler-fail-cache.count" ]
[ ! -e "$T/state/scheduler-pause-cache.until" ]

# Exit 10 is success-with-warning: advance the cycle and never increment failure state.
rm -rf "$T/state"; mkdir -p "$T/state/scheduler-requests" "$T/state/scheduler-skips"
base_config
sed -i 's/^schedule_cache_enabled=.*/schedule_cache_enabled=0/; s/^schedule_empty_enabled=.*/schedule_empty_enabled=0/' "$T/state/config.conf"
printf '%s\n' $((now-7200)) > "$T/state/last_organize_run.epoch"
printf '10\n' > "$T/state/worker-exit-code"
run_once
grep -q '^state=completed$' "$T/state/scheduler.env"
test -s "$T/state/last_organize_run.epoch"
[ ! -e "$T/state/scheduler-fail-organize.count" ]

! grep -q 'FAILURE_PAUSE_SECONDS=21600' "$SCHEDULER"
grep -q 'SPEC_MODE=deep-auto' "$SCHEDULER"
grep -q '1) echo 300' "$SCHEDULER"
grep -q '5) echo 7200\|\*) echo "\$FAILURE_MAX_PAUSE_SECONDS"' "$SCHEDULER"
echo 'scheduler fairness and recovery: ok'
