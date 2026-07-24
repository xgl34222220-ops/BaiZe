#!/system/bin/sh
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
