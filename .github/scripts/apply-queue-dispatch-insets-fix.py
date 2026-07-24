from pathlib import Path
import re

R = Path('.')

def get(p): return (R / p).read_text(encoding='utf-8')
def put(p, s): (R / p).write_text(s, encoding='utf-8')
def sub1(pattern, repl, text, label):
    out, n = re.subn(pattern, repl, text, count=1, flags=re.S)
    if n != 1: raise RuntimeError(f'{label}: {n}')
    return out

# Scheduler: runnable queues poll every second and launch failures recover in seconds.
p = 'service.sh'; s = get(p)
if 'QUEUE_RETRY_SECONDS=${BAIZE_QUEUE_RETRY_SECONDS:-1}' not in s:
    s = s.replace('CONDITION_RETRY_SECONDS=${BAIZE_CONDITION_RETRY_SECONDS:-5}\n', 'CONDITION_RETRY_SECONDS=${BAIZE_CONDITION_RETRY_SECONDS:-5}\nQUEUE_RETRY_SECONDS=${BAIZE_QUEUE_RETRY_SECONDS:-1}\n', 1)
if 'rr_base=${2:-15}' not in s:
    s = sub1(r'record_group_retry\(\) \{.*?\n\}', '''record_group_retry() {
  rr_group=$1; rr_base=${2:-15}; rr_count_file=$(retry_count_file "$rr_group"); rr_until_file=$(retry_until_file "$rr_group")
  case "$rr_base" in ''|*[!0-9]*) rr_base=15 ;; esac
  [ "$rr_base" -lt 1 ] && rr_base=1
  rr_count=$(sed -n '1p' "$rr_count_file" 2>/dev/null)
  case "$rr_count" in ''|*[!0-9]*) rr_count=0 ;; esac
  rr_count=$((rr_count + 1)); printf '%s\n' "$rr_count" >"$rr_count_file"
  case "$rr_count" in
    1) rr_delay=$rr_base ;;
    2) rr_delay=$((rr_base * 2)) ;;
    3) rr_delay=$((rr_base * 4)) ;;
    4) rr_delay=$((rr_base * 8)) ;;
    *) rr_delay=$((rr_base * 16)) ;;
  esac
  [ "$rr_delay" -gt 300 ] && rr_delay=300
  RETRY_DELAY_SECONDS=$rr_delay
  printf '%s\n' $(( $(date +%s) + rr_delay )) >"$rr_until_file"
}''', s, 'retry function')
if '后台任务正在重新拉起' not in s:
    s = sub1(r'handle_task_result\(\) \{.*?\n\}', '''handle_task_result() {
  hr_code=$1; hr_group=$2; hr_kind=$3; hr_cycle=$4; hr_request=$5; hr_run_log=$6
  case "$hr_code" in
    0)
      mark_group_completed "$hr_group" "$hr_kind" "$hr_cycle"; clear_group_retry "$hr_group"
      [ -n "$hr_request" ] && rm -f "$hr_request"
      write_scheduler_state completed "$hr_group" "任务已完成，继续检查队列"
      ;;
    3)
      write_scheduler_state waiting "$hr_group" "已有手动或其他任务运行，稍后继续队列"
      ;;
    9)
      [ -n "$hr_request" ] && rm -f "$hr_request"
      write_scheduler_state waiting "$hr_group" "等待自动重试"
      ;;
    4|5|6|7|8|127)
      clear_stale_task_markers
      record_group_retry "$hr_group" 2
      printf '%s\n' "$(date '+%Y-%m-%d %H:%M:%S') task=$hr_group launch_exit=$hr_code recovery=${RETRY_DELAY_SECONDS}s" >>"$hr_run_log"
      write_scheduler_state waiting "$hr_group" "后台任务正在重新拉起"
      ;;
    *)
      record_group_retry "$hr_group" 15
      printf '%s\n' "$(date '+%Y-%m-%d %H:%M:%S') task=$hr_group exit=$hr_code recovery=${RETRY_DELAY_SECONDS}s" >>"$hr_run_log"
      write_scheduler_state waiting "$hr_group" "后台正在自动恢复"
      ;;
  esac
}''', s, 'result function')
s = s.replace('rn_reason="${rn_group}:等待自动重试"', 'rn_reason="${rn_group}:后台正在自动恢复"')
s = s.replace('[ "$QUEUE_COUNT" -gt 0 ] && { echo "$CONDITION_RETRY_SECONDS"; return; }', '[ "$QUEUE_COUNT" -gt 0 ] && { [ -n "$BLOCKED_GROUPS" ] && echo "$CONDITION_RETRY_SECONDS" || echo "$QUEUE_RETRY_SECONDS"; return; }')
put(p, s)

# Supervisor: wake at 2s, restart a non-responsive scheduler at 12s.
put('v2/module/supervisor.sh', '''#!/system/bin/sh
set -u
MODDIR=${BAIZE_MODULE_DIR:-${0%/*}}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
STATE="$STATE_DIR/supervisor.env"
SCHEDULER_STATE="$STATE_DIR/scheduler.env"
SCHEDULER="$MODDIR/scheduler.sh"
STOP="$STATE_DIR/supervisor.stop"
HEARTBEAT_SECONDS=${BAIZE_SUPERVISOR_HEARTBEAT_SECONDS:-5}
QUEUE_WAKE_AFTER_SECONDS=${BAIZE_QUEUE_WAKE_AFTER_SECONDS:-2}
QUEUE_RESTART_AFTER_SECONDS=${BAIZE_QUEUE_RESTART_AFTER_SECONDS:-12}
mkdir -p "$STATE_DIR/logs"; rm -f "$STOP"
restart_count=0; backoff=1; child=; RESCUE_AGE=0
INSTANCE_ID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "$(date +%s)-$$")
proc_start_ticks() { [ -r "/proc/$1/stat" ] && awk '{print $22}' "/proc/$1/stat" 2>/dev/null || echo 0; }
SUPERVISOR_START_TICKS=$(proc_start_ticks $$)
signal_child() { [ -n "${child:-}" ] && kill -USR1 "$child" 2>/dev/null || true; }
stop_all() { touch "$STOP"; [ -n "${heartbeat_pid:-}" ] && kill "$heartbeat_pid" 2>/dev/null || true; [ -n "${child:-}" ] && kill "$child" 2>/dev/null || true; exit 0; }
trap stop_all INT TERM
trap signal_child USR1 HUP
write_state() {
  status=$1; code=${2:-0}; reason=${3:-}; now=$(date +%s); tmp="$STATE.tmp.$$"
  { echo "status=$status"; echo "pid=$$"; echo "pid_start_ticks=$SUPERVISOR_START_TICKS"; echo "instance_id=$INSTANCE_ID"; echo "scheduler_pid=${child:-0}"; echo "scheduler_start_ticks=$([ -n "${child:-}" ] && proc_start_ticks "$child" || echo 0)"; echo "restart_count=$restart_count"; echo "last_exit_code=$code"; echo "reason=$reason"; echo "heartbeat_epoch=$now"; echo "updated=$now"; } >"$tmp" && mv -f "$tmp" "$STATE"
  chmod 0600 "$STATE" 2>/dev/null || true
}
queue_dispatch_stalled() {
  q_state=$(sed -n 's/^state=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  q_count=$(sed -n 's/^queue_count=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  q_blocked=$(sed -n 's/^blocked_groups=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  q_reason=$(sed -n 's/^reason=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  q_updated=$(sed -n 's/^updated=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  case "$q_count" in ''|*[!0-9]*) q_count=0 ;; esac; case "$q_updated" in ''|*[!0-9]*) q_updated=0 ;; esac
  [ "$q_count" -gt 0 ] || return 1; [ "$q_state" != running ] || return 1; [ -z "$q_blocked" ] || return 1
  case "$q_reason" in *息屏*|*充电*|*电量*|*空闲*|*自动重试*|*自动恢复*|*当前任务*|*手动任务*) return 1 ;; esac
  q_now=$(date +%s); RESCUE_AGE=$((q_now - q_updated)); [ "$RESCUE_AGE" -lt 0 ] && RESCUE_AGE=0
  [ "$RESCUE_AGE" -ge "$QUEUE_WAKE_AFTER_SECONDS" ]
}
while [ ! -f "$STOP" ]; do
  [ -f "$SCHEDULER" ] || { write_state failed 127 scheduler_missing; sleep 60; continue; }
  write_state starting 0 launching_scheduler
  BAIZE_SUPERVISOR_INSTANCE="$INSTANCE_ID" sh "$SCHEDULER" >>"$STATE_DIR/logs/supervisor-scheduler.log" 2>&1 & child=$!
  write_state running 0 scheduler_running
  while kill -0 "$child" 2>/dev/null && [ ! -f "$STOP" ]; do
    sleep "$HEARTBEAT_SECONDS" & heartbeat_pid=$!; wait "$heartbeat_pid" 2>/dev/null || true; heartbeat_pid=
    if kill -0 "$child" 2>/dev/null; then
      backoff=1
      if queue_dispatch_stalled; then
        if [ "$RESCUE_AGE" -ge "$QUEUE_RESTART_AFTER_SECONDS" ]; then write_state recovering 0 "scheduler_queue_stalled_${RESCUE_AGE}s"; kill "$child" 2>/dev/null || true
        else signal_child; write_state running 0 "scheduler_queue_wake_${RESCUE_AGE}s"; fi
      else write_state running 0 scheduler_running; fi
    fi
  done
  wait "$child" 2>/dev/null; code=$?; child=; [ -f "$STOP" ] && break
  restart_count=$((restart_count + 1)); write_state recovering "$code" "scheduler_exited_backoff_${backoff}s"; sleep "$backoff"
  [ "$backoff" -lt 60 ] && backoff=$((backoff * 3)); [ "$backoff" -gt 60 ] && backoff=60
done
write_state stopped 0 supervisor_stopped
exit 0
''')

# Compose viewport: never let scrolling content draw beneath the status bar.
p = 'v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt'; s = get(p)
if s.count('.statusBarsPadding()') < 2:
    pattern = re.compile(r'(modifier = Modifier\n(?P<i>\s+)\.fillMaxSize\(\))(\n(?P<c>\s+)\) \{ targetPage ->)')
    s, n = pattern.subn(lambda m: m.group(1) + '\n' + m.group('i') + '.statusBarsPadding()' + m.group(3), s)
    if n != 2: raise RuntimeError(f'status hosts: {n}')
put(p, s)

# Foreground app adds a second wake path, without owning task lifetime.
p = 'v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt'; s = get(p)
if 'queueStallStartedRealtime' not in s:
    s = s.replace('    private var schedulerMonitorJob: Job? = null\n    private var taskCallbackRegistered = false', '    private var schedulerMonitorJob: Job? = null\n    private var queueStallStartedRealtime = 0L\n    private var lastQueueWakeRealtime = 0L\n    private var taskCallbackRegistered = false', 1)
old = '                snapshots.first?.let { schedulerState.value = SchedulerUiState.fromJson(it) }\n                val task = snapshots.second'
if old in s:
    s = s.replace(old, '''                snapshots.first?.let { schedulerJson ->
                    val schedulerSnapshot = SchedulerUiState.fromJson(schedulerJson)
                    schedulerState.value = schedulerSnapshot
                    val reason = schedulerSnapshot.runtimeReason
                    val blocked = reason.contains("息屏") || reason.contains("充电") || reason.contains("电量") ||
                        reason.contains("空闲") || reason.contains("当前任务") || reason.contains("自动重试") || reason.contains("自动恢复")
                    val pending = schedulerSnapshot.queueCount > 0 && schedulerSnapshot.runtimeState != "running" && !blocked
                    val nowRealtime = SystemClock.elapsedRealtime()
                    if (pending) {
                        if (queueStallStartedRealtime == 0L) queueStallStartedRealtime = nowRealtime
                        if (nowRealtime - queueStallStartedRealtime >= 2_000L && nowRealtime - lastQueueWakeRealtime >= 2_500L) {
                            lastQueueWakeRealtime = nowRealtime
                            withContext(Dispatchers.IO) { runCatching { service.runModuleTask("scheduler-wake") } }
                        }
                    } else queueStallStartedRealtime = 0L
                }
                val task = snapshots.second''', 1)
put(p, s)

p = 'v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/HomeTaskPresentation.kt'; s = get(p)
s = s.replace('reason.contains("队列将在完成") || reason.contains("重试") -> reason', 'reason.contains("队列将在完成") || reason.contains("重试") || reason.contains("恢复") ||\n                reason.contains("重新拉起") -> reason')
s = s.replace('"已进入 Root 队列"', '"正在唤醒 Root Worker"'); put(p, s)

p = 'v2/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRepository.kt'; s = get(p)
if 'raw.contains("恢复") || raw.contains("重新拉起")' not in s:
    s = s.replace('        raw.contains("重试") -> "等待自动重试"', '        raw.contains("恢复") || raw.contains("重新拉起") -> "后台正在自动恢复"\n        raw.contains("重试") -> "等待自动重试"', 1)
put(p, s)

# Permanent regression contract and cleanup of temporary transport files.
p = 'v2/tests/test-scheduler-fairness.sh'; s = get(p)
s = re.sub(r'# BEGIN ONE-TIME QUEUE DISPATCH FIX\n.*?# END ONE-TIME QUEUE DISPATCH FIX\n\n?', '', s, count=1, flags=re.S)
s = s.replace("grep -q '^reason=等待自动重试$' \"$T/state/scheduler.env\"", "grep -q '^reason=后台任务正在重新拉起$' \"$T/state/scheduler.env\"")
if 'retry_delay=$((retry_until - $(date +%s)))' not in s:
    s = s.replace('test -s "$T/state/scheduler-retry-cache.until"\n! grep -Eq', 'test -s "$T/state/scheduler-retry-cache.until"\nretry_until=$(sed -n \'1p\' "$T/state/scheduler-retry-cache.until")\nretry_delay=$((retry_until - $(date +%s)))\n[ "$retry_delay" -ge 0 ] && [ "$retry_delay" -le 3 ]\n! grep -Eq', 1)
if "grep -q 'queue_dispatch_stalled'" not in s:
    s = s.replace("echo 'scheduler fairness: ok'", "grep -q 'QUEUE_RETRY_SECONDS=.*1' \"$ROOT/service.sh\"\ngrep -q 'queue_dispatch_stalled' \"$ROOT/v2/module/supervisor.sh\"\ngrep -q 'QUEUE_RESTART_AFTER_SECONDS=.*12' \"$ROOT/v2/module/supervisor.sh\"\necho 'scheduler fairness: ok'", 1)
put(p, s)
(R / '.queue-dispatch-fix-error.log').unlink(missing_ok=True)
(R / '.github/scripts/apply-queue-dispatch-insets-fix.py').unlink()
print('queue dispatch and system bar repair applied')
