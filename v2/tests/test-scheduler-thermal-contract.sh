#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TMP=${TMPDIR:-/tmp}/baize-scheduler-thermal-$$
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/module" "$TMP/state" "$TMP/bin"
cp "$ROOT/v2/module/scheduler-v2.5.sh" "$TMP/module/scheduler.sh"

cat >"$TMP/module/task-worker.sh" <<'EOF_WORKER'
#!/bin/sh
echo "$*" >>"${BAIZE_STATE_DIR}/worker-invocations.log"
exit 0
EOF_WORKER
chmod +x "$TMP/module/task-worker.sh"

cat >"$TMP/bin/dumpsys" <<'EOF_DUMPSYS'
#!/bin/sh
case "${1:-}" in
  power) echo 'mInteractive=false' ;;
  deviceidle) echo 'mState=IDLE' ;;
  battery)
    cat <<EOF_BATTERY
AC powered: false
USB powered: false
Wireless powered: false
status: 3
level: 80
temperature: ${BAIZE_TEST_TEMP:-430}
EOF_BATTERY
    ;;
esac
EOF_DUMPSYS
chmod +x "$TMP/bin/dumpsys"

cat >"$TMP/state/config.conf" <<'EOF_CONFIG'
enabled=1
schedule_mode=1
autopilot_enabled=0
daily_schedule_enabled=0
screen_off_only=0
charging_only=0
device_idle_only=0
min_battery=0
max_battery_temp=42
schedule_cache_enabled=1
schedule_cache_minutes=5
schedule_empty_enabled=0
schedule_rules_enabled=0
schedule_fragment_enabled=0
schedule_deep_enabled=0
schedule_organize_enabled=0
EOF_CONFIG

PATH="$TMP/bin:$PATH" BAIZE_TEST_TEMP=430 BAIZE_SKIP_BOOT_WAIT=1 BAIZE_SCHEDULER_ONCE=1 \
  BAIZE_MODULE_DIR="$TMP/module" BAIZE_STATE_DIR="$TMP/state" BAIZE_CONFIG_PATH="$TMP/state/config.conf" \
  sh "$TMP/module/scheduler.sh"
test ! -s "$TMP/state/worker-invocations.log"
grep -q '温度' "$TMP/state/scheduler.env"

sed -i 's/^max_battery_temp=42$/max_battery_temp=0/' "$TMP/state/config.conf"
PATH="$TMP/bin:$PATH" BAIZE_TEST_TEMP=350 BAIZE_SKIP_BOOT_WAIT=1 BAIZE_SCHEDULER_ONCE=1 \
  BAIZE_MODULE_DIR="$TMP/module" BAIZE_STATE_DIR="$TMP/state" BAIZE_CONFIG_PATH="$TMP/state/config.conf" \
  sh "$TMP/module/scheduler.sh"
grep -q 'cache-auto scheduler:interval' "$TMP/state/worker-invocations.log"

echo 'scheduler thermal contract passed'
