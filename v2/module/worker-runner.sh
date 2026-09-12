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
RUNNING_FILE="$STATE_DIR/running.env"
LOG_FILE="$STATE_DIR/logs/worker-$TASK_ID.log"
ORGANIZER_RESULT="$STATE_DIR/organizer-result.env"
HISTORY_FILE="$STATE_DIR/history.tsv"
mkdir -p "$RESULT_DIR" "$STATE_DIR/logs"
started=$(date +%s)
code=127
# The launcher owns worker.env; this acknowledgement closes the startup handshake
# without an unconditional sleep and exists before any cleaner writes shared progress.
printf '%s\n' "$$" >"$RESULT_DIR/$TASK_ID.started"
ACTIVE_CHILD=
run_engine() {
  "$@" >>"$LOG_FILE" 2>&1 & ACTIVE_CHILD=$!
  wait "$ACTIVE_CHILD" 2>/dev/null
  engine_code=$?
  if [ -f "$STATE_DIR/stop" ]; then
    # A trapped wait can return before the cleaner has released its destructive lock.
    wait "$ACTIVE_CHILD" 2>/dev/null || true
    engine_code=9
  fi
  ACTIVE_CHILD=
  return "$engine_code"
}
handle_signal() {
  : >"$STATE_DIR/stop"
  [ -n "$ACTIVE_CHILD" ] && kill -TERM "$ACTIVE_CHILD" 2>/dev/null || true
  # The normal result path persists cancellation and clears only our own markers.
  code=9
}
trap handle_signal INT TERM
record_empty_deep_result() {
  now_text=$(date '+%Y-%m-%d %H:%M:%S')
  result="深度清理完成，没有可清理项"
  {
    echo "mode=deep-clean"
    echo "time=$now_text"
    echo "files=0"
    echo "regular_files=0"
    echo "empty_files=0"
    echo "empty_dirs=0"
    echo "hidden_items=0"
    echo "fragment_files=0"
    echo "bytes=0"
    echo "skipped=0"
    echo "errors=0"
    echo "protected_items=0"
    echo "protected_bytes=0"
    echo "elapsed=0"
    echo "result=$result"
  } >"$STATE_DIR/latest.env"
  printf '%s\tdeep-clean\t0\t0\t0\t0\t%s\t%s\t深度规则|0|0\t\n' \
    "$now_text" "$result" "$TRIGGER" >>"$HISTORY_FILE"
  tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"
  rm -f "$STATE_DIR/deep_scan.env" "$STATE_DIR/deep_scan.targets"
  echo "$result" >>"$LOG_FILE"
}
if [ "$MODE" = deep-auto ]; then
  if [ -x "$CLEANER" ]; then
    BAIZE_SUPPRESS_SCAN_HISTORY=1 run_engine "$CLEANER" deep-scan "$TRIGGER"
    code=$?
    if [ "$code" -eq 0 ]; then
      if [ -s "$STATE_DIR/deep_scan.targets" ]; then
        run_engine "$CLEANER" deep-clean "$TRIGGER"
        code=$?
      else
        record_empty_deep_result
        code=0
      fi
    fi
  else
    echo "清理引擎不存在：$CLEANER" >>"$LOG_FILE"
  fi
elif [ "$MODE" = organize ]; then
  rm -f "$ORGANIZER_RESULT"
  if [ -x "$ORGANIZER" ]; then
    run_engine "$ORGANIZER" "$MODE" "$TRIGGER" "$TASK_ID"
    code=$?
  else
    echo "文件归类引擎不存在：$ORGANIZER" >>"$LOG_FILE"
  fi
elif [ -x "$CLEANER" ]; then
  run_engine "$CLEANER" "$MODE" "$TRIGGER"
  code=$?
else
  echo "清理引擎不存在：$CLEANER" >>"$LOG_FILE"
fi
[ ! -f "$STATE_DIR/stop" ] || code=9
ended=$(date +%s)
# Partial deletion also changes the storage tree; never serve the old TTL index.
case "$MODE" in clean|*-clean|*-auto) rm -f "$STATE_DIR/index/meta.env" "${BAIZE_ROOT_STATE_DIR:-$STATE_DIR}/index/meta.env";; esac
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
# A supervisor/scheduler restart must not lose a completed interval task and rerun it.
# Cache-lane work publishes to the shared state after its isolated result is complete.
case "$TRIGGER" in
  scheduler:*)
    case "$MODE" in
      cache-auto|cache-clean) completed_group=cache;; apk-auto) completed_group=apk;;
      empty-clean) completed_group=empty;; rules-clean) completed_group=rules;;
      fragment-clean) completed_group=fragment;; deep-auto|deep-clean) completed_group=deep;;
      organize) completed_group=organize;; *) completed_group=;;
    esac
    if [ -n "$completed_group" ]; then
      completion_root=${BAIZE_ROOT_STATE_DIR:-$STATE_DIR}
      completion_state="$completion_root/scheduler-result-$completed_group.env"
      { echo "task_id=$TASK_ID"; echo "mode=$MODE"; echo "trigger=$TRIGGER"; echo "exit_code=$code"; echo "started=$started"; echo "ended=$ended"; } >"$completion_state.tmp.$$" && mv -f "$completion_state.tmp.$$" "$completion_state"
      if [ "$code" -eq 0 ]; then
        printf '%s\n' "$ended" >"$completion_root/last_${completed_group}_run.epoch.tmp.$$" && mv -f "$completion_root/last_${completed_group}_run.epoch.tmp.$$" "$completion_root/last_${completed_group}_run.epoch"
        rm -f "$completion_root/scheduler-deferred-$completed_group.until" "$completion_root/scheduler-retry-$completed_group.count" "$completion_root/scheduler-retry-$completed_group.until"
        if [ "$TRIGGER" = scheduler:daily ] && [ -n "${BAIZE_SCHEDULE_CYCLE:-}" ]; then
          printf '%s\n' "$BAIZE_SCHEDULE_CYCLE" >"$completion_root/last_${completed_group}_daily.date"
        fi
      fi
    fi
    ;;
esac

current_id=$(sed -n 's/^task_id=//p' "$WORKER_FILE" 2>/dev/null | tail -n 1)
if [ "$current_id" = "$TASK_ID" ]; then
  rm -f "$WORKER_FILE" "$RUNNING_FILE"
fi
exit "$code"
