#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=${TMPDIR:-/tmp}/baize-v240-scheduler-test
rm -rf "$T"; mkdir -p "$T/module/config" "$T/state/scheduler-requests" "$T/state/scheduler-skips"
cp "$ROOT/service.sh" "$T/module/scheduler.sh"
cat > "$T/module/task-worker.sh" <<'SH2'
#!/bin/sh
printf '%s\t%s\t%s\n' "$1" "$2" "$3" >>"${BAIZE_STATE_DIR}/executed.tsv"
exit 0
SH2
chmod +x "$T/module/task-worker.sh"
cat > "$T/state/config.conf" <<'CONF'
enabled=1
screen_off_only=0
charging_only=0
device_idle_only=0
min_battery=0
max_battery_temp=0
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
schedule_organize_enabled=1
schedule_organize_minutes=30
schedule_organize_hours=1
organize_screen_off_only=0
organize_charging_only=0
organize_device_idle_only=0
CONF
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

# A launch failure is retried quickly and remains an internal recovery state.
cat > "$T/module/task-worker.sh" <<'SH2'
#!/bin/sh
printf '%s\t%s\t%s\n' "$1" "$2" "$3" >>"${BAIZE_STATE_DIR}/executed.tsv"
exit 7
SH2
chmod +x "$T/module/task-worker.sh"
rm -f "$T/state/last_cache_run.epoch"
sed -i 's/^schedule_empty_enabled=.*/schedule_empty_enabled=0/; s/^schedule_organize_enabled=.*/schedule_organize_enabled=0/' "$T/state/config.conf"
run_once
grep -q '^state=waiting$' "$T/state/scheduler.env"
grep -q '^reason=后台任务正在重新拉起$' "$T/state/scheduler.env"
test -s "$T/state/scheduler-retry-cache.until"
retry_until=$(sed -n '1p' "$T/state/scheduler-retry-cache.until")
retry_delay=$((retry_until - $(date +%s)))
[ "$retry_delay" -ge 0 ] && [ "$retry_delay" -le 3 ]
! grep -Eq '连续失败|熔断|暂停|failed|paused' "$T/state/scheduler.env"
grep -q 'QUEUE_RETRY_SECONDS=.*1' "$ROOT/service.sh"
grep -q 'queue_dispatch_stalled' "$ROOT/v2/module/supervisor.sh"
grep -q 'QUEUE_RESTART_AFTER_SECONDS=.*12' "$ROOT/v2/module/supervisor.sh"

# Deep scheduled tasks must request the atomic scan -> clean chain.
cat > "$T/module/task-worker.sh" <<'SH2'
#!/bin/sh
printf '%s\t%s\t%s\n' "$1" "$2" "$3" >>"${BAIZE_STATE_DIR}/executed.tsv"
exit 0
SH2
chmod +x "$T/module/task-worker.sh"
: > "$T/state/executed.tsv"
sed -i 's/^schedule_cache_enabled=.*/schedule_cache_enabled=0/; s/^schedule_deep_enabled=.*/schedule_deep_enabled=1/' "$T/state/config.conf"
printf '%s\n' $((now-7200)) > "$T/state/last_deep_run.epoch"
run_once
[ "$(sed -n '1s/\t.*//p' "$T/state/executed.tsv")" = deep-auto ]

# Use the real worker scripts. A successful scan with zero candidates is a completed no-op, not
# exit 6 followed by an endless scheduler retry. Fast workers must not leave worker.env behind.
W="$T/worker-lifecycle"
rm -rf "$W"; mkdir -p "$W/module" "$W/state/task-results" "$W/state/logs"
cp "$ROOT/v2/module/task-worker.sh" "$W/module/task-worker.sh"
cp "$ROOT/v2/module/worker-runner.sh" "$W/module/worker-runner.sh"
cat > "$W/module/cleaner.sh" <<'SH2'
#!/bin/sh
mode=$1
printf '%s\n' "$mode" >>"$BAIZE_STATE_DIR/deep-chain.log"
case "$mode" in
  deep-scan)
    : >"$BAIZE_STATE_DIR/deep_scan.targets"
    printf 'targets=0\n' >"$BAIZE_STATE_DIR/deep_scan.env"
    exit 0 ;;
  deep-clean)
    : >"$BAIZE_STATE_DIR/unexpected-deep-clean"
    exit 6 ;;
esac
exit 0
SH2
chmod +x "$W/module/"*.sh
BAIZE_STATE_DIR="$W/state" BAIZE_SHELL_BIN=/bin/sh timeout 8 sh "$W/module/task-worker.sh" deep-auto scheduler:test zero wait
[ "$(sed -n '1p' "$W/state/deep-chain.log")" = deep-scan ]
[ "$(wc -l < "$W/state/deep-chain.log")" -eq 1 ]
[ ! -e "$W/state/unexpected-deep-clean" ]
[ ! -e "$W/state/worker.env" ]
[ ! -e "$W/state/running.env" ]
grep -q '^exit_code=0$' "$W/state/task-results/zero.env"
grep -q '^mode=deep-clean$' "$W/state/latest.env"
grep -q '没有可清理项' "$W/state/latest.env"

# Non-empty scans still continue into the actual deep-clean stage and clean up every marker.
rm -rf "$W/state"; mkdir -p "$W/state/task-results" "$W/state/logs"
cat > "$W/module/cleaner.sh" <<'SH2'
#!/bin/sh
mode=$1
printf '%s\n' "$mode" >>"$BAIZE_STATE_DIR/deep-chain.log"
case "$mode" in
  deep-scan)
    printf '/data/user/0/test/cache\tlow\n' >"$BAIZE_STATE_DIR/deep_scan.targets"
    printf 'targets=1\n' >"$BAIZE_STATE_DIR/deep_scan.env"
    exit 0 ;;
  deep-clean)
    rm -f "$BAIZE_STATE_DIR/deep_scan.targets" "$BAIZE_STATE_DIR/deep_scan.env"
    exit 0 ;;
esac
exit 0
SH2
chmod +x "$W/module/cleaner.sh"
BAIZE_STATE_DIR="$W/state" BAIZE_SHELL_BIN=/bin/sh timeout 8 sh "$W/module/task-worker.sh" deep-auto scheduler:test nonempty wait
[ "$(sed -n '1p' "$W/state/deep-chain.log")" = deep-scan ]
[ "$(sed -n '2p' "$W/state/deep-chain.log")" = deep-clean ]
[ ! -e "$W/state/worker.env" ]
[ ! -e "$W/state/running.env" ]
grep -q '^exit_code=0$' "$W/state/task-results/nonempty.env"

grep -q 'deep-pipeline-v1' "$ROOT/v2/module/service.sh"
grep -q 'scheduler-retry-.*until' "$ROOT/v2/module/customize.sh"
echo 'scheduler fairness and deep pipeline: ok'
