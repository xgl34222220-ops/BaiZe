#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/module/config" "$T/state" "$T/bin"
cp "$ROOT/v2/module/scripts/scheduler.sh" "$T/module/scheduler.sh"
cat > "$T/module/task-worker.sh" <<'SH'
#!/bin/sh
exit "${TEST_EXIT:-0}"
SH
cat > "$T/bin/dumpsys" <<'SH'
#!/bin/sh
case "$1" in power) echo "mInteractive=${TEST_INTERACTIVE:-true}";; battery) [ "${TEST_BATTERY:-known}" = unknown ] || echo 'level: 90';; esac
SH
chmod +x "$T/module/task-worker.sh" "$T/bin/dumpsys"
cat > "$T/state/config.conf" <<'CONF'
enabled=1
screen_off_only=1
charging_only=0
device_idle_only=0
min_battery=25
schedule_mode=1
clean_apk_packages=1
schedule_apk_minutes=5
schedule_cache_enabled=0
schedule_empty_enabled=0
schedule_rules_enabled=0
schedule_fragment_enabled=0
schedule_deep_enabled=0
schedule_organize_enabled=0
CONF
run() { PATH="$T/bin:$PATH" BAIZE_MODULE_DIR="$T/module" BAIZE_STATE_DIR="$T/state" BAIZE_CONFIG_PATH="$T/state/config.conf" BAIZE_SKIP_BOOT_WAIT=1 BAIZE_SCHEDULER_ONCE=1 sh "$T/module/scheduler.sh"; }
run
grep -q '等待息屏' "$T/state/auto-run-ledger/"*.env
first=$(find "$T/state/auto-run-ledger" -name '*.env' | wc -l)
run
[ "$(find "$T/state/auto-run-ledger" -name '*.env' | wc -l)" = "$first" ]
export TEST_INTERACTIVE=false TEST_BATTERY=unknown
run
grep -q '无法读取电量' "$T/state/auto-run-ledger/"*.env
export TEST_BATTERY=known TEST_EXIT=7
run
grep -q '后台任务正在重新拉起' "$T/state/auto-run-ledger/"*.env
rm -f "$T/state/scheduler-retry-apk.until"
export TEST_EXIT=3
run
grep -q '已有其他任务运行' "$T/state/auto-run-ledger/"*.env
export TEST_EXIT=0
run
grep -q '任务已完成' "$T/state/auto-run-ledger/"*.env
printf 'auto-clean transition ledger: PASS (locked screen, unavailable telemetry, service failure, busy lock, completion, deduplication)\n'
