#!/system/bin/sh
set -eu

MODDIR=${0%/*}
MODE=${1:-clean}
TRIGGER=${2:-app}
TASK_ID=${3:-$(date +%s)-$$}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
SHELL_BIN=${BAIZE_SHELL_BIN:-/system/bin/sh}
RUNNING_FILE="$STATE_DIR/running.env"
WORKER_FILE="$STATE_DIR/worker.env"
LAUNCH_LOG="$STATE_DIR/logs/worker-$TASK_ID.log"
CLEANER="$MODDIR/cleaner.sh"
LOCK_DIR="$STATE_DIR/run.lock"

case "$MODE" in
  clean|scan|apk-scan|apk-clean|deep-scan|deep-clean|corpse-scan|corpse-clean|cache-scan|cache-clean) ;;
  *) echo "不支持的独立任务模式：$MODE" >&2; exit 2 ;;
esac

[ -x "$SHELL_BIN" ] || { echo "Shell 不可用：$SHELL_BIN" >&2; exit 4; }
[ -f "$CLEANER" ] || { echo "清理引擎缺失：$CLEANER" >&2; exit 5; }
if [ -d "$LOCK_DIR" ]; then
  OLD_PID=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
  case "$OLD_PID" in ''|*[!0-9]*) OLD_PID=0 ;; esac
  if [ "$OLD_PID" -gt 1 ] && kill -0 "$OLD_PID" 2>/dev/null; then
    echo "已有扫描或清理任务正在运行" >&2
    exit 3
  fi
fi

mkdir -p "$STATE_DIR/logs"
rm -f "$STATE_DIR/stop"
STARTED=$(date +%s)
TMP="$RUNNING_FILE.tmp.$$"
{
  echo "mode=$MODE"
  echo "phase=正在启动独立 Root 清理任务"
  echo "started=$STARTED"
  echo "progress_current=0"
  echo "progress_total=0"
  echo "current_path="
  echo "task_id=$TASK_ID"
  echo "worker=detached-root-shell"
} >"$TMP"
mv -f "$TMP" "$RUNNING_FILE"

if command -v setsid >/dev/null 2>&1; then
  setsid "$SHELL_BIN" "$CLEANER" "$MODE" "$TRIGGER" </dev/null >>"$LAUNCH_LOG" 2>&1 &
elif command -v nohup >/dev/null 2>&1; then
  nohup "$SHELL_BIN" "$CLEANER" "$MODE" "$TRIGGER" </dev/null >>"$LAUNCH_LOG" 2>&1 &
else
  "$SHELL_BIN" "$CLEANER" "$MODE" "$TRIGGER" </dev/null >>"$LAUNCH_LOG" 2>&1 &
fi
PID=$!
case "$PID" in ''|*[!0-9]*) rm -f "$RUNNING_FILE"; echo "无法启动独立 Root Worker" >&2; exit 6 ;; esac
sleep 0.2
if ! kill -0 "$PID" 2>/dev/null; then
  rm -f "$RUNNING_FILE"
  echo "独立 Root Worker 启动后立即退出" >&2
  exit 7
fi

TMP_WORKER="$WORKER_FILE.tmp.$$"
{
  echo "task_id=$TASK_ID"
  echo "pid=$PID"
  echo "mode=$MODE"
  echo "trigger=$TRIGGER"
  echo "started=$STARTED"
  echo "log=$LAUNCH_LOG"
} >"$TMP_WORKER"
mv -f "$TMP_WORKER" "$WORKER_FILE"
chmod 0600 "$WORKER_FILE" "$RUNNING_FILE" 2>/dev/null || true

echo "独立 Root Worker 已启动：$PID"
exit 0
