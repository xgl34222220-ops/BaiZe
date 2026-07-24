#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=${TMPDIR:-/tmp}/baize-v240-scheduler-test
rm -rf "$T"; mkdir -p "$T/module/config" "$T/state/scheduler-requests" "$T/state/scheduler-skips"
cp "$ROOT/service.sh" "$T/module/scheduler.sh"
cat > "$T/module/task-worker.sh" <<'SH'
#!/bin/sh
printf '%s\t%s\t%s\n' "$1" "$2" "$3" >>"${BAIZE_STATE_DIR}/executed.tsv"
exit 0
SH
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

# A non-zero result stays in the queue and becomes an internal timed retry, never a public failure state.
cat > "$T/module/task-worker.sh" <<'SH'
#!/bin/sh
printf '%s\t%s\t%s\n' "$1" "$2" "$3" >>"${BAIZE_STATE_DIR}/executed.tsv"
exit 7
SH
chmod +x "$T/module/task-worker.sh"
rm -f "$T/state/last_cache_run.epoch"
sed -i 's/^schedule_empty_enabled=.*/schedule_empty_enabled=0/; s/^schedule_organize_enabled=.*/schedule_organize_enabled=0/' "$T/state/config.conf"
run_once
grep -q '^state=waiting$' "$T/state/scheduler.env"
grep -q '^reason=等待自动重试$' "$T/state/scheduler.env"
test -s "$T/state/scheduler-retry-cache.until"
! grep -Eq '连续失败|熔断|暂停|failed|paused' "$T/state/scheduler.env"
echo 'scheduler fairness: ok'
