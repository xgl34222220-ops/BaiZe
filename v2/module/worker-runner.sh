#!/system/bin/sh
set -u
MODDIR=${0%/*}
MODE=${1:?mode}
TRIGGER=${2:?trigger}
TASK_ID=${3:?task_id}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
CLEANER="$MODDIR/cleaner.sh"
RESULT_DIR="$STATE_DIR/task-results"
RESULT_FILE="$RESULT_DIR/$TASK_ID.env"
WORKER_FILE="$STATE_DIR/worker.env"
LOG_FILE="$STATE_DIR/logs/worker-$TASK_ID.log"
mkdir -p "$RESULT_DIR" "$STATE_DIR/logs"
started=$(date +%s)
code=127
if [ -x "$CLEANER" ]; then
  "$CLEANER" "$MODE" "$TRIGGER" >>"$LOG_FILE" 2>&1
  code=$?
else
  echo "清理引擎不存在：$CLEANER" >>"$LOG_FILE"
fi
ended=$(date +%s)
tmp="$RESULT_FILE.tmp.$$"
{
  echo "task_id=$TASK_ID"
  echo "mode=$MODE"
  echo "trigger=$TRIGGER"
  echo "started=$started"
  echo "ended=$ended"
  echo "elapsed=$((ended-started))"
  echo "exit_code=$code"
  echo "log=$LOG_FILE"
} >"$tmp" && mv -f "$tmp" "$RESULT_FILE"
chmod 0600 "$RESULT_FILE" 2>/dev/null || true
current_id=$(sed -n 's/^task_id=//p' "$WORKER_FILE" 2>/dev/null | tail -n 1)
[ "$current_id" = "$TASK_ID" ] && rm -f "$WORKER_FILE"
exit "$code"
