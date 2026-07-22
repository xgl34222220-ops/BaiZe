#!/system/bin/sh
# Persistent watchdog for BaiZe's scheduler. PID identity is protected against reuse.
set -u
MODDIR=${BAIZE_MODULE_DIR:-${0%/*}}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
STATE="$STATE_DIR/supervisor.env"
SCHEDULER="$MODDIR/scheduler.sh"
STOP="$STATE_DIR/supervisor.stop"
HEARTBEAT_SECONDS=${BAIZE_SUPERVISOR_HEARTBEAT_SECONDS:-30}
mkdir -p "$STATE_DIR/logs"
rm -f "$STOP"
restart_count=0
backoff=10
child=
INSTANCE_ID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "$(date +%s)-$$")
proc_start_ticks() { [ -r "/proc/$1/stat" ] && awk '{print $22}' "/proc/$1/stat" 2>/dev/null || echo 0; }
SUPERVISOR_START_TICKS=$(proc_start_ticks $$)

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

while [ ! -f "$STOP" ]; do
  [ -f "$SCHEDULER" ] || { write_state failed 127 scheduler_missing; sleep 300; continue; }
  write_state starting 0 launching_scheduler
  BAIZE_SUPERVISOR_INSTANCE="$INSTANCE_ID" sh "$SCHEDULER" >>"$STATE_DIR/logs/supervisor-scheduler.log" 2>&1 &
  child=$!
  write_state running 0 scheduler_running

  while kill -0 "$child" 2>/dev/null && [ ! -f "$STOP" ]; do
    sleep "$HEARTBEAT_SECONDS" & heartbeat_pid=$!
    wait "$heartbeat_pid" 2>/dev/null || true
    heartbeat_pid=
    kill -0 "$child" 2>/dev/null && write_state running 0 scheduler_running
  done
  wait "$child" 2>/dev/null; code=$?
  child=
  [ -f "$STOP" ] && break
  restart_count=$((restart_count + 1))
  write_state recovering "$code" "scheduler_exited_backoff_${backoff}s"
  sleep "$backoff"
  [ "$backoff" -lt 600 ] && backoff=$((backoff * 3))
  [ "$backoff" -gt 600 ] && backoff=600
done
write_state stopped 0 supervisor_stopped
exit 0
