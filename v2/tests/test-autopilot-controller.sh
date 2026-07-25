#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CONTROLLER="$ROOT/module/autopilot-controller.sh"
TMP=${TMPDIR:-/tmp}/baize-autopilot-test-$$
STATE="$TMP/state"
CONFIG="$STATE/config.conf"
HISTORY="$STATE/history.tsv"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$STATE"

cat >"$CONFIG" <<'CONF'
autopilot_enabled=1
autopilot_check_seconds=60
autopilot_require_screen_off=1
autopilot_condition_hold_minutes=5
autopilot_pressure_percent=90
autopilot_low_yield_mb=16
autopilot_high_yield_mb=256
autopilot_low_yield_streak=3
autopilot_zero_yield_streak=5
autopilot_max_interval_factor=8
autopilot_zero_sleep_hours=72
max_battery_temp=42
daily_schedule_enabled=0
schedule_cache_enabled=1
schedule_cache_minutes=60
schedule_empty_enabled=1
schedule_empty_minutes=60
schedule_rules_enabled=0
schedule_fragment_enabled=0
schedule_deep_enabled=0
CONF
: >"$HISTORY"

run_controller() {
  BAIZE_STATE_DIR="$STATE" \
  BAIZE_CONFIG_PATH="$CONFIG" \
  BAIZE_HISTORY_FILE="$HISTORY" \
  BAIZE_FORCE_AUTOPILOT=1 \
  BAIZE_NOW_EPOCH="$1" \
  BAIZE_STORAGE_USED_PERCENT="${2:-50}" \
  BAIZE_SCREEN_INTERACTIVE="${3:-0}" \
  BAIZE_BATTERY_TEMP_TENTHS="${4:-350}" \
  sh "$CONTROLLER"
}
value() { sed -n "s/^$2=//p" "$1" | tail -n 1; }

printf '2026-07-25 00:00:00\tcache-auto\t1048576\t1\t0\t0\tlow\tscheduler\t\t\n' >>"$HISTORY"
run_controller 1000
printf '2026-07-25 01:00:00\tcache-auto\t1048576\t1\t0\t0\tlow\tscheduler\t\t\n' >>"$HISTORY"
run_controller 2000
printf '2026-07-25 02:00:00\tcache-auto\t1048576\t1\t0\t0\tlow\tscheduler\t\t\n' >>"$HISTORY"
run_controller 3000
[ "$(value "$STATE/autopilot-cache.env" factor)" = 2 ]
[ "$(sed -n '1p' "$STATE/last_cache_run.epoch")" = 6600 ]

# Storage pressure cancels the adaptive multiplier and brings the next run forward.
run_controller 4000 95 0 350
[ "$(sed -n '1p' "$STATE/last_cache_run.epoch")" = 3000 ]
[ "$(value "$STATE/autopilot-cache.env" storage_pressure)" = 1 ]

# Active screen defers an otherwise-due scheduled task without affecting manual Root tasks.
printf '500\n' >"$STATE/last_empty_run.epoch"
run_controller 5000 50 1 350
[ "$(sed -n '1p' "$STATE/last_empty_run.epoch")" = 1700 ]
[ "$(value "$STATE/autopilot-empty.env" screen_hold)" = 1 ]

# Battery temperature remains diagnostic telemetry and no longer delays any schedule mode.
run_controller 5000 50 0 430
[ "$(value "$STATE/autopilot-empty.env" temperature_hold)" = 0 ]
[ "$(sed -n '1p' "$STATE/last_empty_run.epoch")" = 500 ]
[ "$(value "$STATE/autopilot.env" reason)" = normal ]

echo "autopilot controller contract ok"
