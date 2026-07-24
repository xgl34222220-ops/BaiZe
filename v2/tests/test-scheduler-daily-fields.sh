#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=${TMPDIR:-/tmp}/baize-scheduler-daily-fields-test
rm -rf "$T"
mkdir -p "$T/module" "$T/state/scheduler-requests" "$T/state/scheduler-skips" "$T/state/logs"
cp "$ROOT/v2/module/scheduler-v2.5.sh" "$T/module/scheduler.sh"
chmod +x "$T/module/scheduler.sh"

cat >"$T/module/task-worker.sh" <<'SH'
#!/bin/sh
printf '%s\t%s\t%s\n' "$1" "$2" "$3" >>"$BAIZE_STATE_DIR/executed.tsv"
exit 0
SH
chmod +x "$T/module/task-worker.sh"

hour=$(date +%H | sed 's/^0//'); [ -n "$hour" ] || hour=0
minute=$(date +%M | sed 's/^0//'); [ -n "$minute" ] || minute=0
today=$(date +%F)
cat >"$T/state/config.conf" <<CONF
enabled=1
screen_off_only=0
charging_only=0
device_idle_only=0
min_battery=0
daily_schedule_enabled=1
daily_schedule_hour=$hour
daily_schedule_minute=$minute
daily_grace_minutes=30
schedule_cache_enabled=1
schedule_empty_enabled=0
schedule_rules_enabled=0
schedule_fragment_enabled=0
schedule_deep_enabled=0
schedule_organize_enabled=0
CONF
printf '%s\n' 2000-01-01 >"$T/state/last_cache_daily.date"

run_once() {
  BAIZE_MODULE_DIR="$T/module" BAIZE_STATE_DIR="$T/state" BAIZE_CONFIG_PATH="$T/state/config.conf" \
    BAIZE_SKIP_BOOT_WAIT=1 BAIZE_SCHEDULER_ONCE=1 sh "$T/module/scheduler.sh"
}

run_once
[ "$(wc -l <"$T/state/executed.tsv" | tr -d ' ')" -eq 1 ]
[ "$(cut -f1 "$T/state/executed.tsv")" = cache-auto ]
[ "$(cut -f2 "$T/state/executed.tsv")" = scheduler:daily ]
[ "$(cat "$T/state/last_cache_daily.date")" = "$today" ]
awk -F '\t' -v today="$today" 'NF == 7 && $3 == "cache" && $5 == "daily" && $6 == "-" && $7 == today {ok=1} END {exit !ok}' "$T/state/scheduler-queue.tsv"
grep -q '^queue_schema=fixed-seven-fields-v1$' "$T/state/scheduler.env"

# The same daily cycle must not execute twice.
run_once
[ "$(wc -l <"$T/state/executed.tsv" | tr -d ' ')" -eq 1 ]
[ ! -s "$T/state/scheduler-queue.tsv" ]

# Manual requests also keep an explicit empty cycle field and are removed only after success.
sed -i 's/^daily_schedule_enabled=.*/daily_schedule_enabled=0/; s/^schedule_cache_enabled=.*/schedule_cache_enabled=0/' "$T/state/config.conf"
request="$T/state/scheduler-requests/100-cache.env"
cat >"$request" <<EOF
group=cache
created=$(date +%s)
request_id=100
reason=test
EOF
run_once
[ "$(wc -l <"$T/state/executed.tsv" | tr -d ' ')" -eq 2 ]
[ "$(tail -n 1 "$T/state/executed.tsv" | cut -f2)" = scheduler:manual ]
[ ! -e "$request" ]
awk -F '\t' 'NF == 7 && $3 == "cache" && $5 == "manual" && $6 != "-" && $7 == "-" {ok=1} END {exit !ok}' "$T/state/scheduler-queue.tsv"

echo 'scheduler fixed seven-field queue: ok'
