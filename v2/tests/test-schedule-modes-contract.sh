#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEFAULTS="$ROOT/config/default.conf"
APP_STATE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
CONTRACT="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanContract.kt"
MATERIAL="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/material/CleanScreenMaterial.kt"
MIUIX="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/miuix/CleanScreenMiuix.kt"
REPOSITORY="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRepository.kt"
AUTOPILOT="$ROOT/v2/module/autopilot-controller.sh"
SCHEDULER="$ROOT/v2/module/scheduler-v2.5.sh"

grep -qx 'schedule_mode=0' "$DEFAULTS"
grep -q 'put("schedule_mode", scheduleMode.coerceIn(0, 2))' "$APP_STATE"
grep -q 'put("autopilot_enabled", if (scheduleMode == 0) 1 else 0)' "$APP_STATE"
grep -q 'put("daily_schedule_enabled", (scheduleMode == 2).flag())' "$APP_STATE"
grep -q '"schedule_mode" to 0..2' "$REPOSITORY"
grep -q '"autopilot_enabled" to 0..1' "$REPOSITORY"
grep -q 'SMART(0, "智能定时"' "$CONTRACT"
grep -q 'STRICT_INTERVAL(1, "严格间隔"' "$CONTRACT"
grep -q 'FIXED_DAILY(2, "每日固定"' "$CONTRACT"
grep -q 'CleanScheduleMode.entries.forEach' "$MATERIAL"
grep -q 'CleanScheduleMode.entries.forEach' "$MIUIX"
grep -q 'daily_mode_enabled' "$SCHEDULER"

TMP=${TMPDIR:-/tmp}/baize-schedule-mode-contract-$$
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/module"
cp "$AUTOPILOT" "$TMP/module/autopilot-controller.sh"

run_mode() {
  mode=$1
  autopilot=$2
  daily=$3
  expected_status=$4
  expected_reason=${5:-}
  state="$TMP/state-$mode"
  mkdir -p "$state"
  cat >"$state/config.conf" <<EOF_CONFIG
enabled=1
schedule_mode=$mode
autopilot_enabled=$autopilot
daily_schedule_enabled=$daily
schedule_cache_enabled=0
schedule_empty_enabled=0
schedule_rules_enabled=0
schedule_fragment_enabled=0
schedule_deep_enabled=0
max_battery_temp=42
EOF_CONFIG
  BAIZE_MODULE_DIR="$TMP/module" \
  BAIZE_STATE_DIR="$state" \
  BAIZE_CONFIG_PATH="$state/config.conf" \
  BAIZE_HISTORY_FILE="$state/history.tsv" \
  BAIZE_FORCE_AUTOPILOT=1 \
  BAIZE_NOW_EPOCH=2000000000 \
  BAIZE_STORAGE_USED_PERCENT=50 \
  BAIZE_SCREEN_INTERACTIVE=0 \
  BAIZE_BATTERY_TEMP_TENTHS=300 \
    sh "$TMP/module/autopilot-controller.sh"
  grep -qx "status=$expected_status" "$state/autopilot.env"
  grep -qx "schedule_mode=$mode" "$state/autopilot.env"
  if [ -n "$expected_reason" ]; then
    grep -qx "reason=$expected_reason" "$state/autopilot.env"
  fi
}

run_mode 0 1 0 idle
run_mode 1 0 0 disabled strict_interval
run_mode 2 0 1 disabled fixed_daily

echo "schedule modes contract passed"
