#!/system/bin/sh
set -u
MODDIR=${0%/*}
MODE=${1:?mode}
TRIGGER=${2:?trigger}
TASK_ID=${3:?task_id}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
CLEANER="$MODDIR/cleaner.sh"
ORGANIZER="$MODDIR/organizer-worker.sh"
RESULT_DIR="$STATE_DIR/task-results"
RESULT_FILE="$RESULT_DIR/$TASK_ID.env"
WORKER_FILE="$STATE_DIR/worker.env"
LOG_FILE="$STATE_DIR/logs/worker-$TASK_ID.log"
ORGANIZER_RESULT="$STATE_DIR/organizer-result.env"
HISTORY_FILE="$STATE_DIR/history.tsv"
mkdir -p "$RESULT_DIR" "$STATE_DIR/logs"
started=$(date +%s)
code=127
if [ "$MODE" = deep-auto ]; then
  if [ -x "$CLEANER" ]; then
    "$CLEANER" deep-scan "$TRIGGER" >>"$LOG_FILE" 2>&1
    code=$?
    if [ "$code" -eq 0 ]; then
      "$CLEANER" deep-clean "$TRIGGER" >>"$LOG_FILE" 2>&1
      code=$?
    fi
  else
    echo "清理引擎不存在：$CLEANER" >>"$LOG_FILE"
  fi
elif [ "$MODE" = organize ]; then
  if [ -x "$ORGANIZER" ]; then
    "$ORGANIZER" "$MODE" "$TRIGGER" "$TASK_ID" >>"$LOG_FILE" 2>&1
    code=$?
  else
    echo "文件归类引擎不存在：$ORGANIZER" >>"$LOG_FILE"
  fi
elif [ -x "$CLEANER" ]; then
  "$CLEANER" "$MODE" "$TRIGGER" >>"$LOG_FILE" 2>&1
  code=$?
else
  echo "清理引擎不存在：$CLEANER" >>"$LOG_FILE"
fi
ended=$(date +%s)
if [ "$MODE" = organize ] && [ -f "$ORGANIZER_RESULT" ]; then
  moved=$(sed -n 's/^moved=//p' "$ORGANIZER_RESULT" | tail -n 1)
  bytes=$(sed -n 's/^bytes=//p' "$ORGANIZER_RESULT" | tail -n 1)
  failed=$(sed -n 's/^failed=//p' "$ORGANIZER_RESULT" | tail -n 1)
  phase=$(sed -n 's/^phase=//p' "$ORGANIZER_RESULT" | tail -n 1 | tr '\t\r\n' '   ')
  case "$moved" in ''|*[!0-9]*) moved=0 ;; esac
  case "$bytes" in ''|*[!0-9]*) bytes=0 ;; esac
  case "$failed" in ''|*[!0-9]*) failed=0 ;; esac
  timestamp=$(date '+%Y-%m-%d %H:%M:%S')
  printf '%s\torganize\t%s\t%s\t0\t%s\t%s\t%s\t\t\n' \
    "$timestamp" "$bytes" "$moved" "$failed" "${phase:-文件归类完成}" "$TRIGGER" >>"$HISTORY_FILE"
  tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"
fi
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
  if [ "$MODE" = organize ] && [ -f "$ORGANIZER_RESULT" ]; then
    for key in success cancelled requested moved skipped failed renamed deduplicated bytes conflictPolicy undoAvailable undoCount phase; do
      value=$(sed -n "s/^$key=//p" "$ORGANIZER_RESULT" | tail -n 1)
      [ -n "$value" ] && printf '%s=%s\n' "$key" "$value"
    done
  fi
} >"$tmp" && mv -f "$tmp" "$RESULT_FILE"
chmod 0600 "$RESULT_FILE" 2>/dev/null || true
current_id=$(sed -n 's/^task_id=//p' "$WORKER_FILE" 2>/dev/null | tail -n 1)
[ "$current_id" = "$TASK_ID" ] && rm -f "$WORKER_FILE"
exit "$code"
