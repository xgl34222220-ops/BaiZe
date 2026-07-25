#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SCHEDULER="$ROOT/v2/module/scheduler-v2.5.sh"
HOME_STATUS="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/HomeTaskPresentation.kt"
REPOSITORY="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRepository.kt"
TMP=${TMPDIR:-/tmp}/baize-active-heartbeat-test
MOD="$TMP/module"
STATE="$TMP/state"
rm -rf "$TMP"
mkdir -p "$MOD/config" "$STATE"
cp "$SCHEDULER" "$MOD/scheduler.sh"
cat >"$STATE/config.conf" <<'CONF'
enabled=1
schedule_mode=1
autopilot_enabled=0
screen_off_only=0
charging_only=0
device_idle_only=0
min_battery=0
schedule_cache_enabled=0
schedule_empty_enabled=0
schedule_rules_enabled=0
schedule_fragment_enabled=0
schedule_deep_enabled=0
schedule_organize_enabled=1
schedule_organize_minutes=15
organize_screen_off_only=0
organize_charging_only=0
organize_device_idle_only=0
CONF
cat >"$MOD/task-worker.sh" <<'SH'
#!/system/bin/sh
sleep 3
exit 0
SH
chmod 0755 "$MOD/task-worker.sh" "$MOD/scheduler.sh"

BAIZE_MODULE_DIR="$MOD" \
BAIZE_STATE_DIR="$STATE" \
BAIZE_CONFIG_PATH="$STATE/config.conf" \
BAIZE_SKIP_BOOT_WAIT=1 \
BAIZE_SCHEDULER_ONCE=1 \
BAIZE_ACTIVE_HEARTBEAT_SECONDS=1 \
sh "$MOD/scheduler.sh" &
pid=$!

for _ in $(seq 1 80); do
  [ "$(sed -n 's/^state=//p' "$STATE/scheduler.env" 2>/dev/null | tail -n 1)" = running ] && break
  sleep 0.1
done
first=$(sed -n 's/^heartbeat_epoch=//p' "$STATE/scheduler.env" | tail -n 1)
sleep 1.4
second=$(sed -n 's/^heartbeat_epoch=//p' "$STATE/scheduler.env" | tail -n 1)
case "$first:$second" in *[!0-9:]*|:|*: ) echo "invalid heartbeat values: $first -> $second" >&2; exit 1;; esac
[ "$second" -gt "$first" ] || { echo "active task heartbeat did not advance: $first -> $second" >&2; exit 1; }
wait "$pid"

grep -q 'activeWorkerHealthy' "$REPOSITORY"
grep -q 'schedulerHeartbeatFresh || activeWorkerHealthy' "$REPOSITORY"
python3 - "$HOME_STATUS" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text()
running = text.index('if (scheduler.runtimeState == "running")')
stale = text.index('if (scheduler.runtimeStale && scheduler.nextTask == task.id)')
assert running < stale
assert '"等待当前后台任务完成"' in text
assert '.split(\'+\', \',\')' in text
PY

echo "active task heartbeat and per-task status contract passed"
