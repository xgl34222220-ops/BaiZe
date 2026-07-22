#!/system/bin/sh

MODDIR=${0%/*}
TRIGGER=${2:-${1:-scheduled:cache}}
SHELL_BIN=${BAIZE_SHELL:-/system/bin/sh}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
LOCK_DIR="$STATE_DIR/run.lock"
RUNNING_FILE="$STATE_DIR/running.env"
STOP_FILE="$STATE_DIR/stop"
HISTORY_FILE="$STATE_DIR/history.tsv"
CACHE_PREFIX=cache_auto

mkdir -p "$STATE_DIR" "$STATE_DIR/logs" "$STATE_DIR/reports"

pid_is_baize_task() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in
    *baize_v2*cleaner.sh*|*baize-v2*cleaner.sh*|*cache-transaction.sh*|*native-scan.sh*|*cache-snapshot-clean.sh*|*baize_engine*) return 0 ;;
  esac
  return 1
}

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  old_pid=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
  case "$old_pid" in ''|*[!0-9]*) old_pid=0 ;; esac
  if [ "$old_pid" -gt 1 ] && kill -0 "$old_pid" 2>/dev/null && pid_is_baize_task "$old_pid"; then
    echo "已有扫描或清理任务正在运行"
    exit 3
  fi
  rm -rf -- "$LOCK_DIR" 2>/dev/null
  rm -f "$RUNNING_FILE" 2>/dev/null
  mkdir "$LOCK_DIR" 2>/dev/null || { echo "无法恢复缓存事务锁，请重试"; exit 4; }
fi
printf '%s\n' "$$" >"$LOCK_DIR/pid"
mkdir -p "$LOCK_DIR/tmp"
rm -f "$STOP_FILE"

CHILD_PID=0
cleanup_transaction() {
  [ "$CHILD_PID" -gt 1 ] 2>/dev/null && kill "$CHILD_PID" 2>/dev/null
  rm -f "$RUNNING_FILE" 2>/dev/null
  rm -rf -- "$LOCK_DIR" 2>/dev/null
}
handle_signal() {
  trap - EXIT INT TERM
  : >"$STOP_FILE" 2>/dev/null
  if [ "$CHILD_PID" -gt 1 ] 2>/dev/null; then
    kill "$CHILD_PID" 2>/dev/null
    wait "$CHILD_PID" 2>/dev/null
  fi
  cleanup_transaction
  exit 9
}
trap cleanup_transaction EXIT
trap handle_signal INT TERM

run_component() {
  component=$1
  shift
  BAIZE_LOCK_HELD=1 \
  BAIZE_CACHE_PREFIX="$CACHE_PREFIX" \
  BAIZE_SUPPRESS_SCAN_HISTORY=1 \
  "$SHELL_BIN" "$component" "$@" &
  CHILD_PID=$!
  wait "$CHILD_PID"
  code=$?
  CHILD_PID=0
  return "$code"
}

run_component "$MODDIR/native-cleaner.sh" cache-scan "$TRIGGER:scan"
scan_code=$?
[ "$scan_code" -eq 0 ] || exit "$scan_code"

state="$STATE_DIR/$CACHE_PREFIX.env"
files=$(sed -n 's/^files=//p' "$state" 2>/dev/null | tail -n 1)
bytes=$(sed -n 's/^bytes=//p' "$state" 2>/dev/null | tail -n 1)
snapshot_id=$(sed -n 's/^snapshot_id=//p' "$state" 2>/dev/null | tail -n 1)
case "$files" in ''|*[!0-9]*) files=0 ;; esac
case "$bytes" in ''|*[!0-9]*) bytes=0 ;; esac

if [ "$files" -eq 0 ]; then
  result="自动缓存扫描完成，没有符合当前安全条件的缓存"
  rm -f "$STATE_DIR/$CACHE_PREFIX.env" "$STATE_DIR/$CACHE_PREFIX.targets" \
        "$STATE_DIR/$CACHE_PREFIX.items.tsv" "$STATE_DIR/$CACHE_PREFIX.manifest0"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$(date '+%Y-%m-%d %H:%M:%S')" "cache-auto" "0" "0" "0" "0" \
    "$result" "$TRIGGER" "应用缓存|0|0" "$snapshot_id" >>"$HISTORY_FILE"
  tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"
  echo "$result"
  exit 0
fi

run_component "$MODDIR/cache-snapshot-clean.sh" cache-clean "$TRIGGER"
exit $?
