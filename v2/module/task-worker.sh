#!/system/bin/sh
set -u
MODDIR=${0%/*}
MODE=${1:-clean}
TRIGGER=${2:-app}
TASK_ID=${3:-$(date +%s)-$$}
WAIT_MODE=${4:-detach}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
SHELL_BIN=${BAIZE_SHELL_BIN:-/system/bin/sh}
RUNNING_FILE="$STATE_DIR/running.env"
WORKER_FILE="$STATE_DIR/worker.env"
LOCK_DIR="$STATE_DIR/run.lock"
RESULT_FILE="$STATE_DIR/task-results/$TASK_ID.env"
RUNNER="$MODDIR/worker-runner.sh"
LAUNCH_LOCK="$STATE_DIR/task-launch.lock"
LAUNCH_OWNED=0
case "$TASK_ID" in ''|*[!a-zA-Z0-9_.-]*|.*) echo "任务编号无效" >&2; exit 2;; esac
case "$MODE" in clean|scan|apk-auto|cache-auto|cache-clean|empty-clean|rules-clean|fragment-scan|fragment-clean|deep-scan|deep-clean|deep-auto|corpse-scan|corpse-clean|apk-scan|apk-clean|organize) ;; *) echo "不支持的任务模式：$MODE" >&2; exit 2 ;; esac
[ -x "$SHELL_BIN" ] || { echo "Shell 不可用：$SHELL_BIN" >&2; exit 4; }
[ -f "$RUNNER" ] || { echo "Root Worker Runner 缺失" >&2; exit 5; }
proc_start_ticks() { [ -r "/proc/$1/stat" ] && awk '{print $22}' "/proc/$1/stat" 2>/dev/null || echo 0; }
lock_owner_alive() {
  old_pid=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
  old_ticks=$(sed -n '1p' "$LOCK_DIR/start_ticks" 2>/dev/null)
  case "$old_pid" in ''|*[!0-9]*) return 1 ;; esac
  [ "$old_pid" -gt 1 ] && kill -0 "$old_pid" 2>/dev/null || return 1
  current_ticks=$(proc_start_ticks "$old_pid")
  case "$old_ticks" in ''|*[!0-9]*) old_ticks=0 ;; esac
  [ "$old_ticks" -eq 0 ] || [ "$current_ticks" = "$old_ticks" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$old_pid/cmdline" 2>/dev/null)
  case "$cmdline" in *worker-runner.sh*|*organizer-worker.sh*|*cleaner.sh*|*native-cleaner.sh*|*profile-cleaner.sh*|*deep-scan-manifest.sh*|*deep-manifest-clean.sh*|*baize_engine*|*apk-scanner.sh*|*apk-snapshot-scan.sh*|*apk-snapshot-clean.sh*|*baize_deep_snapshot*) return 0 ;; esac
  return 1
}
worker_owner_alive() {
  worker_pid=$(sed -n 's/^pid=//p' "$WORKER_FILE" 2>/dev/null | tail -n 1)
  worker_ticks=$(sed -n 's/^start_ticks=//p' "$WORKER_FILE" 2>/dev/null | tail -n 1)
  case "$worker_pid" in ''|*[!0-9]*) return 1;; esac
  [ "$worker_pid" -gt 1 ] && kill -0 "$worker_pid" 2>/dev/null || return 1
  case "$worker_ticks" in ''|0|*[!0-9]*) return 1;; esac
  [ "$(proc_start_ticks "$worker_pid")" = "$worker_ticks" ] || return 1
  worker_cmd=$(tr '\000' ' ' <"/proc/$worker_pid/cmdline" 2>/dev/null)
  case "$worker_cmd" in *worker-runner.sh*|*task-worker.sh*) return 0;; esac
  return 1
}
release_launch_lock() {
  [ "$LAUNCH_OWNED" = 1 ] || return 0
  [ "$(sed -n '1p' "$LAUNCH_LOCK" 2>/dev/null)" = "$$" ] && rm -f "$LAUNCH_LOCK"
  rm -f "$LAUNCH_LOCK.$$"
  LAUNCH_OWNED=0
}
acquire_launch_lock() {
  mkdir -p "$STATE_DIR"
  # link publishes both PID and identity at once: another launcher never sees an empty lock.
  printf '%s\n%s\n' "$$" "$(proc_start_ticks $$)" >"$LAUNCH_LOCK.$$"
  if ! ln "$LAUNCH_LOCK.$$" "$LAUNCH_LOCK" 2>/dev/null; then
    launch_pid=$(sed -n '1p' "$LAUNCH_LOCK" 2>/dev/null)
    launch_ticks=$(sed -n '2p' "$LAUNCH_LOCK" 2>/dev/null)
    case "$launch_pid" in ''|*[!0-9]*) launch_pid=0;; esac
    if [ "$launch_pid" -gt 1 ] && kill -0 "$launch_pid" 2>/dev/null &&
       [ "$(proc_start_ticks "$launch_pid")" = "$launch_ticks" ]; then
      rm -f "$LAUNCH_LOCK.$$"; return 1
    fi
    rm -f "$LAUNCH_LOCK"
    ln "$LAUNCH_LOCK.$$" "$LAUNCH_LOCK" 2>/dev/null || { rm -f "$LAUNCH_LOCK.$$"; return 1; }
  fi
  LAUNCH_OWNED=1
  return 0
}
trap release_launch_lock EXIT
trap 'release_launch_lock; exit 9' INT TERM
acquire_launch_lock || { echo "已有任务正在启动" >&2; exit 3; }
worker_owner_alive && { echo "已有扫描、清理或归类任务正在运行" >&2; exit 3; }

worker_marker_id() { sed -n 's/^task_id=//p' "$WORKER_FILE" 2>/dev/null | tail -n 1; }
write_worker_marker() {
  marker_pid=$1
  tmp="$WORKER_FILE.tmp.$$"
  {
    echo "task_id=$TASK_ID"
    echo "pid=$marker_pid"
    echo "mode=$MODE"
    echo "trigger=$TRIGGER"
    echo "started=$started"
    echo "result=$RESULT_FILE"
    echo "start_ticks=$([ "$marker_pid" -gt 1 ] 2>/dev/null && proc_start_ticks "$marker_pid" || echo 0)"
  } >"$tmp" && mv -f "$tmp" "$WORKER_FILE"
  chmod 0600 "$WORKER_FILE" 2>/dev/null || true
}
cleanup_worker_marker() {
  [ "$(worker_marker_id)" = "$TASK_ID" ] && rm -f "$WORKER_FILE" || true
}
if [ -d "$LOCK_DIR" ]; then
  if lock_owner_alive; then
    echo "已有扫描、清理或归类任务正在运行" >&2
    exit 3
  fi
  rm -rf -- "$LOCK_DIR" 2>/dev/null || true
fi
mkdir -p "$STATE_DIR/logs" "$STATE_DIR/task-results"
rm -f "$STATE_DIR/stop" "$RESULT_FILE"
started=$(date +%s)
tmp="$RUNNING_FILE.tmp.$$"
{
  echo "mode=$MODE"
  echo "phase=正在启动统一 Root Worker"
  echo "started=$started"
  echo "progress_current=0"
  echo "progress_total=0"
  echo "current_path="
  echo "task_id=$TASK_ID"
  echo "worker=detached-root-worker-v2.9.1"
} >"$tmp" && mv -f "$tmp" "$RUNNING_FILE"
write_worker_marker 0
if [ "$WAIT_MODE" = wait ]; then
  "$SHELL_BIN" "$RUNNER" "$MODE" "$TRIGGER" "$TASK_ID" </dev/null >/dev/null 2>&1 &
elif command -v setsid >/dev/null 2>&1; then
  setsid "$SHELL_BIN" "$RUNNER" "$MODE" "$TRIGGER" "$TASK_ID" </dev/null >/dev/null 2>&1 &
elif command -v nohup >/dev/null 2>&1; then
  nohup "$SHELL_BIN" "$RUNNER" "$MODE" "$TRIGGER" "$TASK_ID" </dev/null >/dev/null 2>&1 &
else
  "$SHELL_BIN" "$RUNNER" "$MODE" "$TRIGGER" "$TASK_ID" </dev/null >/dev/null 2>&1 &
fi
pid=$!
case "$pid" in ''|*[!0-9]*) cleanup_worker_marker; rm -f "$RUNNING_FILE"; echo "无法启动 Root Worker" >&2; exit 6 ;; esac
[ "$(worker_marker_id)" = "$TASK_ID" ] && write_worker_marker "$pid"
# Wait only for startup acknowledgement/result, never impose a fixed one-second
# delay on every small scheduled task. The runner acknowledges before invoking a cleaner.
launch_checks=0
while [ ! -f "$RESULT_FILE" ] && [ ! -f "$STATE_DIR/task-results/$TASK_ID.started" ] && kill -0 "$pid" 2>/dev/null && [ "$launch_checks" -lt 20 ]; do
  sleep 0.05
  launch_checks=$((launch_checks+1))
done
rm -f "$STATE_DIR/task-results/$TASK_ID.started"
if ! kill -0 "$pid" 2>/dev/null && [ ! -f "$RESULT_FILE" ]; then
  cleanup_worker_marker
  rm -f "$RUNNING_FILE"
  echo "Root Worker 启动后立即退出" >&2
  exit 7
fi
release_launch_lock
echo "统一 Root Worker 已启动：$pid"
[ "$WAIT_MODE" = wait ] || exit 0
wait "$pid" 2>/dev/null
runner_code=$?
cleanup_worker_marker
code=$(sed -n 's/^exit_code=//p' "$RESULT_FILE" 2>/dev/null | tail -n 1)
case "$code" in ''|*[!0-9]*) code=$runner_code ;; esac
case "$code" in ''|*[!0-9]*) code=8 ;; esac
exit "$code"
