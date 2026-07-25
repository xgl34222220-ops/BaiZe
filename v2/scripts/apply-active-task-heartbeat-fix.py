#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{relative}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1))


replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/HomeTaskPresentation.kt",
    '''    if (task == null || !task.enabled) return "自动任务已关闭"
    if (scheduler.runtimeState == "running" && scheduler.runtimeGroup == task.id) return "正在后台执行"
    if (scheduler.runtimeStale) return "后台调度正在自动恢复"

''',
    '''    if (task == null || !task.enabled) return "自动任务已关闭"
    val runningGroups = scheduler.runtimeGroup
        .split('+', ',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
    if (scheduler.runtimeState == "running") {
        return if (task.id in runningGroups) "正在后台执行" else "等待当前后台任务完成"
    }
    if (scheduler.runtimeStale && scheduler.nextTask == task.id) return "后台调度正在自动恢复"

'''
)

replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRepository.kt",
    '''        val schedulerPid = scheduler.optLong("scheduler_pid", 0L)
        val supervisorPid = supervisor.optLong("pid", 0L)
        val supervisorInstance = supervisor.optString("instance_id")
        val schedulerHeartbeat = scheduler.optLong("heartbeat_epoch", 0L)
        val schedulerHealthy = processMatches(
            schedulerPid,
            scheduler.optLong("scheduler_start_ticks", 0L),
            listOf("scheduler.sh", "service.sh"),
            schedulerHeartbeat,
            expectedInstance = supervisorInstance.takeIf { it.isNotBlank() },
            actualInstance = scheduler.optString("instance_id")
        )
        val supervisorHeartbeat = supervisor.optLong("heartbeat_epoch", supervisor.optLong("updated", 0L))
        val supervisorHealthy = processMatches(
            supervisorPid,
            supervisor.optLong("pid_start_ticks", 0L),
            listOf("supervisor.sh"),
            supervisorHeartbeat
        )
        val now = System.currentTimeMillis() / 1000L
''',
    '''        val schedulerPid = scheduler.optLong("scheduler_pid", 0L)
        val supervisorPid = supervisor.optLong("pid", 0L)
        val supervisorInstance = supervisor.optString("instance_id")
        val schedulerHeartbeat = scheduler.optLong("heartbeat_epoch", 0L)
        val now = System.currentTimeMillis() / 1000L
        val worker = RootFileStore.readEnv(File(stateDir, "worker.env"))
        val activeWorkerHealthy = processMatches(
            worker.optLong("pid", 0L),
            worker.optLong("start_ticks", 0L),
            listOf(
                "task-worker.sh",
                "worker-runner.sh",
                "organizer-worker.sh",
                "cache-lane-worker.sh",
                "cleaner.sh",
                "native-cleaner.sh",
                "profile-cleaner.sh",
                "deep-scan-manifest.sh",
                "deep-manifest-clean.sh",
                "baize_engine",
                "baize_deep_snapshot"
            )
        )
        val schedulerProcessAlive = processMatches(
            schedulerPid,
            scheduler.optLong("scheduler_start_ticks", 0L),
            listOf("scheduler.sh", "service.sh"),
            expectedInstance = supervisorInstance.takeIf { it.isNotBlank() },
            actualInstance = scheduler.optString("instance_id")
        )
        val schedulerHeartbeatFresh = schedulerHeartbeat <= 0L ||
            now - schedulerHeartbeat <= HEARTBEAT_STALE_SECONDS
        val schedulerHealthy = schedulerProcessAlive && (schedulerHeartbeatFresh || activeWorkerHealthy)
        val supervisorHeartbeat = supervisor.optLong("heartbeat_epoch", supervisor.optLong("updated", 0L))
        val supervisorHealthy = processMatches(
            supervisorPid,
            supervisor.optLong("pid_start_ticks", 0L),
            listOf("supervisor.sh"),
            supervisorHeartbeat
        )
'''
)

replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRepository.kt",
    '''            .put("schedulerHealthy", schedulerHealthy)
            .put("supervisorHealthy", supervisorHealthy)
''',
    '''            .put("schedulerHealthy", schedulerHealthy)
            .put("activeWorkerHealthy", activeWorkerHealthy)
            .put("supervisorHealthy", supervisorHealthy)
'''
)

replace_once(
    "v2/module/scheduler-v2.5.sh",
    '''QUEUE_RETRY_SECONDS=${BAIZE_QUEUE_RETRY_SECONDS:-1}
EMPTY_FIELD=-
NEXT_CHECK_EPOCH=0
SLEEP_PID=
''',
    '''QUEUE_RETRY_SECONDS=${BAIZE_QUEUE_RETRY_SECONDS:-1}
ACTIVE_HEARTBEAT_SECONDS=${BAIZE_ACTIVE_HEARTBEAT_SECONDS:-5}
EMPTY_FIELD=-
NEXT_CHECK_EPOCH=0
SLEEP_PID=
ACTIVE_HEARTBEAT_PID=
'''
)

replace_once(
    "v2/module/scheduler-v2.5.sh",
    '''  chmod 0600 "$SCHEDULER_STATE" 2>/dev/null || true
}
refresh_next_check() {
''',
    '''  chmod 0600 "$SCHEDULER_STATE" 2>/dev/null || true
}
start_active_heartbeat() {
  ah_group=$1; ah_reason=$2; shift 2
  (
    while true; do
      alive=0
      for ah_pid in "$@"; do kill -0 "$ah_pid" 2>/dev/null && alive=1; done
      [ "$alive" -eq 1 ] || exit 0
      sleep "$ACTIVE_HEARTBEAT_SECONDS"
      alive=0
      for ah_pid in "$@"; do kill -0 "$ah_pid" 2>/dev/null && alive=1; done
      [ "$alive" -eq 1 ] || exit 0
      write_scheduler_state running "$ah_group" "$ah_reason"
    done
  ) &
  ACTIVE_HEARTBEAT_PID=$!
}
stop_active_heartbeat() {
  [ -n "${ACTIVE_HEARTBEAT_PID:-}" ] || return 0
  kill "$ACTIVE_HEARTBEAT_PID" 2>/dev/null || true
  wait "$ACTIVE_HEARTBEAT_PID" 2>/dev/null || true
  ACTIVE_HEARTBEAT_PID=
}
refresh_next_check() {
'''
)

replace_once(
    "v2/module/scheduler-v2.5.sh",
    '''  BAIZE_STATE_DIR="$STATE_DIR" sh "$CACHE_LANE_WORKER" "$pc_mode" "scheduler:$pc_kind" "$cache_id" wait >>"$cache_log" 2>&1 & cache_pid=$!
  sh "$MODDIR/task-worker.sh" "$po_mode" "scheduler:$po_kind" "$organize_id" wait >>"$organize_log" 2>&1 & organize_pid=$!
  wait "$cache_pid" 2>/dev/null; cache_code=$?
  wait "$organize_pid" 2>/dev/null; organize_code=$?
''',
    '''  BAIZE_STATE_DIR="$STATE_DIR" sh "$CACHE_LANE_WORKER" "$pc_mode" "scheduler:$pc_kind" "$cache_id" wait >>"$cache_log" 2>&1 & cache_pid=$!
  sh "$MODDIR/task-worker.sh" "$po_mode" "scheduler:$po_kind" "$organize_id" wait >>"$organize_log" 2>&1 & organize_pid=$!
  start_active_heartbeat "cache+organize" "正在并行执行应用缓存与文件归类" "$cache_pid" "$organize_pid"
  wait "$cache_pid" 2>/dev/null; cache_code=$?
  wait "$organize_pid" 2>/dev/null; organize_code=$?
  stop_active_heartbeat
'''
)

replace_once(
    "v2/module/scheduler-v2.5.sh",
    '''    sh "$MODDIR/task-worker.sh" "$mode" "scheduler:$kind" "$task_id" wait >>"$log" 2>&1
    code=$?; TASK_EXECUTED=1; handle_task_result "$code" "$group" "$kind" "$cycle" "$request" "$log"; return 0
''',
    '''    sh "$MODDIR/task-worker.sh" "$mode" "scheduler:$kind" "$task_id" wait >>"$log" 2>&1 & task_pid=$!
    start_active_heartbeat "$group" "按超期时间与请求顺序执行" "$task_pid"
    wait "$task_pid" 2>/dev/null; code=$?
    stop_active_heartbeat
    TASK_EXECUTED=1; handle_task_result "$code" "$group" "$kind" "$cycle" "$request" "$log"; return 0
'''
)

test_path = ROOT / "v2/tests/test-active-task-heartbeat-contract.sh"
test_path.write_text(r'''#!/usr/bin/env bash
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
''')

health = ROOT / "v2/tests/test-scheduler-health-contract.sh"
health_text = health.read_text()
needle = 'echo "scheduler health contract regression passed"\n'
if health_text.count(needle) != 1:
    raise SystemExit("scheduler health contract footer mismatch")
health.write_text(health_text.replace(needle, 'bash "$ROOT/tests/test-active-task-heartbeat-contract.sh"\n\n' + needle, 1))

print("active task heartbeat fix applied")
