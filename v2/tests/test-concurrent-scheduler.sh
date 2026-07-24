#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
T=${TMPDIR:-/tmp}/baize-v250-concurrent-scheduler-test
rm -rf "$T"
mkdir -p "$T/module/config" "$T/state/scheduler-requests" "$T/state/scheduler-skips"
cp "$ROOT/module/scheduler-v2.5.sh" "$T/module/scheduler.sh"
cp "$ROOT/module/cache-lane-worker.sh" "$T/module/cache-lane-worker.sh"

cat >"$T/module/task-worker.sh" <<'SH'
#!/bin/sh
root=${BAIZE_ROOT_STATE_DIR:-$BAIZE_STATE_DIR}
mkdir -p "$BAIZE_STATE_DIR/task-results"
started=$(date +%s)
printf 'start\t%s\t%s\t%s\n' "$1" "$started" "$3" >>"$root/trace.tsv"
sleep "${BAIZE_TEST_TASK_SECONDS:-2}"
ended=$(date +%s)
printf 'end\t%s\t%s\t%s\n' "$1" "$ended" "$3" >>"$root/trace.tsv"
printf 'exit_code=0\n' >"$BAIZE_STATE_DIR/task-results/$3.env"
exit 0
SH
chmod +x "$T/module/"*.sh

write_parallel_config() {
  cat >"$T/state/config.conf" <<'CONF'
enabled=1
screen_off_only=0
charging_only=0
device_idle_only=0
min_battery=0
daily_schedule_enabled=0
schedule_cache_enabled=1
schedule_cache_minutes=30
schedule_cache_hours=1
schedule_empty_enabled=0
schedule_rules_enabled=0
schedule_fragment_enabled=0
schedule_deep_enabled=0
schedule_organize_enabled=1
schedule_organize_minutes=30
schedule_organize_hours=1
organize_screen_off_only=0
organize_charging_only=0
organize_device_idle_only=0
CONF
}
run_once() {
  BAIZE_MODULE_DIR="$T/module" BAIZE_STATE_DIR="$T/state" BAIZE_CONFIG_PATH="$T/state/config.conf" \
    BAIZE_SKIP_BOOT_WAIT=1 BAIZE_SCHEDULER_ONCE=1 BAIZE_TEST_TASK_SECONDS=${BAIZE_TEST_TASK_SECONDS:-2} \
    sh "$T/module/scheduler.sh"
}

write_parallel_config
now=$(date +%s)
printf '%s\n' $((now-7200)) >"$T/state/last_cache_run.epoch"
printf '%s\n' $((now-7200)) >"$T/state/last_organize_run.epoch"
run_once
cache_start=$(awk -F '\t' '$1=="start" && $2=="cache-auto" {print $3; exit}' "$T/state/trace.tsv")
organize_start=$(awk -F '\t' '$1=="start" && $2=="organize" {print $3; exit}' "$T/state/trace.tsv")
cache_end=$(awk -F '\t' '$1=="end" && $2=="cache-auto" {print $3; exit}' "$T/state/trace.tsv")
organize_end=$(awk -F '\t' '$1=="end" && $2=="organize" {print $3; exit}' "$T/state/trace.tsv")
[ -n "$cache_start" ] && [ -n "$organize_start" ] && [ -n "$cache_end" ] && [ -n "$organize_end" ]
gap=$((cache_start-organize_start)); [ "$gap" -lt 0 ] && gap=$((-gap))
[ "$gap" -le 1 ]
grep -q '^group=cache+organize$' "$T/state/scheduler.env"
grep -q '^reason=2 项兼容任务已并行完成$' "$T/state/scheduler.env"

# A setting edit made while the pair is running must be preserved for the next scheduler pass.
rm -f "$T/state/trace.tsv"
printf '%s\n' $((now-7200)) >"$T/state/last_cache_run.epoch"
printf '%s\n' $((now-7200)) >"$T/state/last_organize_run.epoch"
BAIZE_TEST_TASK_SECONDS=3 run_once & scheduler_pid=$!
sleep 1
sed -i 's/^schedule_organize_enabled=.*/schedule_organize_enabled=0/' "$T/state/config.conf"
wait "$scheduler_pid"
grep -q '^schedule_organize_enabled=0$' "$T/state/config.conf"
: >"$T/state/trace.tsv"
printf '%s\n' $((now-7200)) >"$T/state/last_cache_run.epoch"
run_once
[ "$(awk -F '\t' '$1=="start" {count++} END {print count+0}' "$T/state/trace.tsv")" -eq 1 ]
grep -q $'^start\tcache-auto\t' "$T/state/trace.tsv"

# Deep and cache deletion pipelines overlap in possible paths, so they must not be launched together.
rm -rf "$T/state"
mkdir -p "$T/state/scheduler-requests" "$T/state/scheduler-skips"
cat >"$T/state/config.conf" <<'CONF'
enabled=1
screen_off_only=0
charging_only=0
device_idle_only=0
min_battery=0
daily_schedule_enabled=0
schedule_cache_enabled=1
schedule_cache_minutes=30
schedule_cache_hours=1
schedule_empty_enabled=0
schedule_rules_enabled=0
schedule_fragment_enabled=0
schedule_deep_enabled=1
schedule_deep_minutes=30
schedule_deep_hours=1
schedule_organize_enabled=0
CONF
now=$(date +%s)
printf '%s\n' $((now-7200)) >"$T/state/last_cache_run.epoch"
printf '%s\n' $((now-7200)) >"$T/state/last_deep_run.epoch"
BAIZE_TEST_TASK_SECONDS=1 run_once
[ "$(awk -F '\t' '$1=="start" {count++} END {print count+0}' "$T/state/trace.tsv")" -eq 1 ]

echo 'compatible scheduler lanes: ok'
