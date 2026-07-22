#!/system/bin/sh
set -u
MODDIR=${0%/*}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
STATE="$STATE_DIR/supervisor.env"
SCHEDULER="$MODDIR/scheduler.sh"
STOP="$STATE_DIR/supervisor.stop"
mkdir -p "$STATE_DIR/logs"
rm -f "$STOP"
restart_count=0
backoff=10
trap 'touch "$STOP"; [ -n "${child:-}" ] && kill "$child" 2>/dev/null || true; exit 0' INT TERM
write_state() {
  status=$1 code=${2:-0} reason=${3:-}
  tmp="$STATE.tmp.$$"
  {
    echo "status=$status"
    echo "pid=$$"
    echo "scheduler_pid=${child:-0}"
    echo "restart_count=$restart_count"
    echo "last_exit_code=$code"
    echo "reason=$reason"
    echo "updated=$(date +%s)"
  } >"$tmp" && mv -f "$tmp" "$STATE"
  chmod 0600 "$STATE" 2>/dev/null || true
}
while [ ! -f "$STOP" ]; do
  [ -f "$SCHEDULER" ] || { write_state failed 127 scheduler_missing; sleep 300; continue; }
  write_state starting 0 launching_scheduler
  sh "$SCHEDULER" >>"$STATE_DIR/logs/supervisor-scheduler.log" 2>&1 &
  child=$!
  write_state running 0 scheduler_running
  wait "$child"
  code=$?
  child=""
  [ -f "$STOP" ] && break
  restart_count=$((restart_count + 1))
  write_state recovering "$code" "scheduler_exited_backoff_${backoff}s"
  sleep "$backoff"
  [ "$backoff" -lt 600 ] && backoff=$((backoff * 3))
  [ "$backoff" -gt 600 ] && backoff=600
done
write_state stopped 0 supervisor_stopped
exit 0
