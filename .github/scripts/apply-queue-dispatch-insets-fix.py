from pathlib import Path

ROOT = Path('.')
BOOTSTRAP_START = '# BEGIN ONE-TIME QUEUE DISPATCH FIX\n'
BOOTSTRAP_END = '# END ONE-TIME QUEUE DISPATCH FIX\n'


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected one match, found {count}')
    file.write_text(text.replace(old, new, 1), encoding='utf-8')


def replace_exact_count(path: str, old: str, new: str, expected: int, label: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding='utf-8')
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f'{label}: expected {expected} matches, found {count}')
    file.write_text(text.replace(old, new), encoding='utf-8')


service = 'service.sh'
replace_once(
    service,
    'CONDITION_RETRY_SECONDS=${BAIZE_CONDITION_RETRY_SECONDS:-5}\n',
    'CONDITION_RETRY_SECONDS=${BAIZE_CONDITION_RETRY_SECONDS:-5}\nQUEUE_RETRY_SECONDS=${BAIZE_QUEUE_RETRY_SECONDS:-1}\n',
    'queue retry constant',
)
replace_once(
    service,
    '''record_group_retry() {
  rr_group=$1; rr_count_file=$(retry_count_file "$rr_group"); rr_until_file=$(retry_until_file "$rr_group")
  rr_count=$(sed -n '1p' "$rr_count_file" 2>/dev/null)
  case "$rr_count" in ''|*[!0-9]*) rr_count=0 ;; esac
  rr_count=$((rr_count + 1)); printf '%s\n' "$rr_count" >"$rr_count_file"
  case "$rr_count" in
    1) rr_delay=300 ;;
    2) rr_delay=900 ;;
    3) rr_delay=1800 ;;
    4) rr_delay=3600 ;;
    *) rr_delay=7200 ;;
  esac
  RETRY_DELAY_SECONDS=$rr_delay
  printf '%s\n' $(( $(date +%s) + rr_delay )) >"$rr_until_file"
}''',
    '''record_group_retry() {
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
}''',
    'automatic recovery delay',
)
replace_once(
    service,
    '''    9)
      [ -n "$hr_request" ] && rm -f "$hr_request"
      write_scheduler_state waiting "$hr_group" "等待自动重试"
      ;;
    *)
      record_group_retry "$hr_group"
      printf '%s\n' "$(date '+%Y-%m-%d %H:%M:%S') task=$hr_group exit=$hr_code retry=${RETRY_DELAY_SECONDS}s" >>"$hr_run_log"
      write_scheduler_state waiting "$hr_group" "等待自动重试"
      ;;''',
    '''    9)
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
      ;;''',
    'fast launch recovery',
)
replace_once(
    service,
    '      rn_reason="${rn_group}:等待自动重试"\n',
    '      rn_reason="${rn_group}:后台正在自动恢复"\n',
    'retry presentation state',
)
replace_once(
    service,
    '  [ "$QUEUE_COUNT" -gt 0 ] && { echo "$CONDITION_RETRY_SECONDS"; return; }\n',
    '  [ "$QUEUE_COUNT" -gt 0 ] && { [ -n "$BLOCKED_GROUPS" ] && echo "$CONDITION_RETRY_SECONDS" || echo "$QUEUE_RETRY_SECONDS"; return; }\n',
    'pending queue wake interval',
)

supervisor = '''#!/system/bin/sh
# Persistent watchdog for BaiZe's scheduler. PID identity is protected against reuse.
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
mkdir -p "$STATE_DIR/logs"
rm -f "$STOP"
restart_count=0
backoff=1
child=
RESCUE_AGE=0
INSTANCE_ID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "$(date +%s)-$$")
proc_start_ticks() { [ -r "/proc/$1/stat" ] && awk '{print $22}' "/proc/$1/stat" 2>/dev/null || echo 0; }

signal_child() { [ -n "${child:-}" ] && kill -USR1 "$child" 2>/dev/null || true; }
stop_all() {
  touch "$STOP"
  [ -n "${heartbeat_pid:-}" ] && kill "$heartbeat_pid" 2>/dev/null || true
  [ -n "${child:-}" ] && kill "$child" 2>/dev/null || true
  exit 0
}
trap stop_all INT TERM
trap signal_child USR1 HUP

write_state() {
  status=$1; code=${2:-0}; reason=${3:-}; now=$(date +%s)
  tmp="$STATE.tmp.$$"
  {
    echo "status=$status"
    echo "pid=$$"
    echo "pid_start_ticks=$SUPERVISOR_START_TICKS"
    echo "instance_id=$INSTANCE_ID"
    echo "scheduler_pid=${child:-0}"
    echo "scheduler_start_ticks=$([ -n "${child:-}" ] && proc_start_ticks "$child" || echo 0)"
    echo "restart_count=$restart_count"
    echo "last_exit_code=$code"
    echo "reason=$reason"
    echo "heartbeat_epoch=$now"
    echo "updated=$now"
  } >"$tmp" && mv -f "$tmp" "$STATE"
  chmod 0600 "$STATE" 2>/dev/null || true
}

queue_dispatch_stalled() {
  q_state=$(sed -n 's/^state=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  q_count=$(sed -n 's/^queue_count=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  q_blocked=$(sed -n 's/^blocked_groups=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  q_reason=$(sed -n 's/^reason=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  q_updated=$(sed -n 's/^updated=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  case "$q_count" in ''|*[!0-9]*) q_count=0 ;; esac
  case "$q_updated" in ''|*[!0-9]*) q_updated=0 ;; esac
  [ "$q_count" -gt 0 ] || return 1
  [ "$q_state" != running ] || return 1
  [ -z "$q_blocked" ] || return 1
  case "$q_reason" in
    *息屏*|*充电*|*电量*|*空闲*|*自动重试*|*自动恢复*|*当前任务*|*手动任务*) return 1 ;;
  esac
  q_now=$(date +%s)
  RESCUE_AGE=$((q_now - q_updated))
  [ "$RESCUE_AGE" -lt 0 ] && RESCUE_AGE=0
  [ "$RESCUE_AGE" -ge "$QUEUE_WAKE_AFTER_SECONDS" ]
}

SUPERVISOR_START_TICKS=$(proc_start_ticks $$)
while [ ! -f "$STOP" ]; do
  [ -f "$SCHEDULER" ] || { write_state failed 127 scheduler_missing; sleep 60; continue; }
  write_state starting 0 launching_scheduler
  BAIZE_SUPERVISOR_INSTANCE="$INSTANCE_ID" sh "$SCHEDULER" >>"$STATE_DIR/logs/supervisor-scheduler.log" 2>&1 &
  child=$!
  write_state running 0 scheduler_running

  while kill -0 "$child" 2>/dev/null && [ ! -f "$STOP" ]; do
    sleep "$HEARTBEAT_SECONDS" & heartbeat_pid=$!
    wait "$heartbeat_pid" 2>/dev/null || true
    heartbeat_pid=
    if kill -0 "$child" 2>/dev/null; then
      backoff=1
      if queue_dispatch_stalled; then
        if [ "$RESCUE_AGE" -ge "$QUEUE_RESTART_AFTER_SECONDS" ]; then
          write_state recovering 0 "scheduler_queue_stalled_${RESCUE_AGE}s"
          kill "$child" 2>/dev/null || true
        else
          signal_child
          write_state running 0 "scheduler_queue_wake_${RESCUE_AGE}s"
        fi
      else
        write_state running 0 scheduler_running
      fi
    fi
  done
  wait "$child" 2>/dev/null; code=$?
  child=
  [ -f "$STOP" ] && break
  restart_count=$((restart_count + 1))
  write_state recovering "$code" "scheduler_exited_backoff_${backoff}s"
  sleep "$backoff"
  [ "$backoff" -lt 60 ] && backoff=$((backoff * 3))
  [ "$backoff" -gt 60 ] && backoff=60
done
write_state stopped 0 supervisor_stopped
exit 0
'''
(ROOT / 'v2/module/supervisor.sh').write_text(supervisor, encoding='utf-8')

app = 'v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt'
replace_exact_count(
    app,
    '''modifier = Modifier
                            .fillMaxSize()
                    ) { targetPage ->''',
    '''modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                    ) { targetPage ->''',
    2,
    'system status bar viewport',
)

activity = 'v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt'
replace_once(
    activity,
    '''    private var schedulerMonitorJob: Job? = null
    private var taskCallbackRegistered = false''',
    '''    private var schedulerMonitorJob: Job? = null
    private var queueStallStartedRealtime = 0L
    private var lastQueueWakeRealtime = 0L
    private var taskCallbackRegistered = false''',
    'queue rescue fields',
)
replace_once(
    activity,
    '''                snapshots.first?.let { schedulerState.value = SchedulerUiState.fromJson(it) }
                val task = snapshots.second''',
    '''                snapshots.first?.let { schedulerJson ->
                    val schedulerSnapshot = SchedulerUiState.fromJson(schedulerJson)
                    schedulerState.value = schedulerSnapshot
                    val reason = schedulerSnapshot.runtimeReason
                    val explicitlyBlocked = reason.contains("息屏") || reason.contains("充电") ||
                        reason.contains("电量") || reason.contains("空闲") ||
                        reason.contains("当前任务") || reason.contains("自动重试") ||
                        reason.contains("自动恢复")
                    val queueNeedsWake = schedulerSnapshot.queueCount > 0 &&
                        schedulerSnapshot.runtimeState != "running" && !explicitlyBlocked
                    val nowRealtime = SystemClock.elapsedRealtime()
                    if (queueNeedsWake) {
                        if (queueStallStartedRealtime == 0L) queueStallStartedRealtime = nowRealtime
                        if (nowRealtime - queueStallStartedRealtime >= 2_000L &&
                            nowRealtime - lastQueueWakeRealtime >= 2_500L
                        ) {
                            lastQueueWakeRealtime = nowRealtime
                            withContext(Dispatchers.IO) {
                                runCatching { service.runModuleTask("scheduler-wake") }
                            }
                        }
                    } else {
                        queueStallStartedRealtime = 0L
                    }
                }
                val task = snapshots.second''',
    'foreground queue rescue',
)

presentation = 'v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/HomeTaskPresentation.kt'
replace_once(
    presentation,
    '''                reason.contains("空闲") || reason.contains("当前任务") || reason.contains("手动任务") ||
                reason.contains("队列将在完成") || reason.contains("重试") -> reason
            scheduler.queueCount > 0 && scheduler.nextTask == task.id -> "已进入 Root 队列"''',
    '''                reason.contains("空闲") || reason.contains("当前任务") || reason.contains("手动任务") ||
                reason.contains("队列将在完成") || reason.contains("重试") || reason.contains("恢复") ||
                reason.contains("重新拉起") -> reason
            scheduler.queueCount > 0 && scheduler.nextTask == task.id -> "正在唤醒 Root Worker"''',
    'truthful root wake label',
)

scheduler_repo = 'v2/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRepository.kt'
replace_once(
    scheduler_repo,
    '''        raw.contains("重试") -> "等待自动重试"
        raw.contains("没有到期") || raw.contains("下次") -> "等待下次执行"''',
    '''        raw.contains("恢复") || raw.contains("重新拉起") -> "后台正在自动恢复"
        raw.contains("重试") -> "等待自动重试"
        raw.contains("没有到期") || raw.contains("下次") -> "等待下次执行"''',
    'public recovery state',
)

# Turn the existing failure case into a bounded automatic-recovery contract and retain permanent checks.
test_file = ROOT / 'v2/tests/test-scheduler-fairness.sh'
test = test_file.read_text(encoding='utf-8')
if BOOTSTRAP_START in test:
    before, remainder = test.split(BOOTSTRAP_START, 1)
    _, after = remainder.split(BOOTSTRAP_END, 1)
    test = before + after
old_test = '''run_once
grep -q '^state=waiting$' "$T/state/scheduler.env"
grep -q '^reason=等待自动重试$' "$T/state/scheduler.env"
test -s "$T/state/scheduler-retry-cache.until"
! grep -Eq '连续失败|熔断|暂停|failed|paused' "$T/state/scheduler.env"
echo 'scheduler fairness: ok'\n'''
new_test = '''run_once
grep -q '^state=waiting$' "$T/state/scheduler.env"
grep -q '^reason=后台任务正在重新拉起$' "$T/state/scheduler.env"
test -s "$T/state/scheduler-retry-cache.until"
retry_until=$(sed -n '1p' "$T/state/scheduler-retry-cache.until")
retry_delay=$((retry_until - $(date +%s)))
[ "$retry_delay" -ge 0 ] && [ "$retry_delay" -le 3 ]
! grep -Eq '连续失败|熔断|暂停|failed|paused' "$T/state/scheduler.env"
grep -q 'QUEUE_RETRY_SECONDS=.*1' "$ROOT/service.sh"
grep -q 'queue_dispatch_stalled' "$ROOT/v2/module/supervisor.sh"
grep -q 'QUEUE_RESTART_AFTER_SECONDS=.*12' "$ROOT/v2/module/supervisor.sh"
echo 'scheduler fairness: ok'\n'''
if old_test not in test:
    raise RuntimeError('scheduler recovery test anchor not found')
test_file.write_text(test.replace(old_test, new_test, 1), encoding='utf-8')

script = ROOT / '.github/scripts/apply-queue-dispatch-insets-fix.py'
script.unlink()
print('queue dispatch, recovery and system bar fix applied')
