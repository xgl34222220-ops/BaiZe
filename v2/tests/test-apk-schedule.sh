#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/module" "$T/state" "$T/bin"
cp "$ROOT/v2/module/scheduler-v2.5.sh" "$T/module/scheduler.sh"
cat >"$T/state/config.conf" <<'CONF'
enabled=1
clean_apk_packages=1
schedule_apk_minutes=60
schedule_mode=1
screen_off_only=0
charging_only=0
device_idle_only=0
min_battery=0
schedule_cache_enabled=0
schedule_rules_enabled=0
CONF
cat >"$T/module/task-worker.sh" <<'WORKER'
#!/bin/sh
printf '%s\n' "$1 $2" >>"$BAIZE_STATE_DIR/executed"
exit 0
WORKER
cat >"$T/bin/dumpsys" <<'DUMP'
#!/bin/sh
echo 'level: 100'
DUMP
chmod +x "$T/bin/dumpsys"
run() {
 env PATH="$T/bin:$PATH" BAIZE_STATE_DIR="$T/state" BAIZE_MODULE_DIR="$T/module" BAIZE_SKIP_BOOT_WAIT=1 BAIZE_SCHEDULER_ONCE=1 \
   bash "$T/module/scheduler.sh"
}
run
grep -Fxq 'apk-auto scheduler:interval' "$T/state/executed"
test -s "$T/state/last_apk_run.epoch"
test ! -e "$T/state/last_rules_run.epoch"
run
test "$(wc -l <"$T/state/executed")" -eq 1
printf '%s\n' "$(( $(date +%s) - 3601 ))" >"$T/state/last_apk_run.epoch"
run
test "$(wc -l <"$T/state/executed")" -eq 2
sed -i 's/clean_apk_packages=1/clean_apk_packages=0/' "$T/state/config.conf"
printf '0\n' >"$T/state/last_apk_run.epoch"
run
test "$(wc -l <"$T/state/executed")" -eq 2
echo 'APK schedule: independent hourly execution, not early, obeys switch: OK'
