#!/system/bin/sh
set -u

MODDIR=${0%/*}
MODE=${1:-cache-auto}
TRIGGER=${2:-scheduler:interval}
TASK_ID=${3:-cache-$(date +%s)-$$}
WAIT_MODE=${4:-wait}
ROOT_STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
SHELL_BIN=${BAIZE_SHELL_BIN:-/system/bin/sh}
LANE_ROOT="$ROOT_STATE_DIR/cache-lane"
TASK_STATE="$LANE_ROOT/$TASK_ID"
LANE_LOCK="$ROOT_STATE_DIR/cache-lane.lock"
ROOT_RESULT_DIR="$ROOT_STATE_DIR/task-results"
ROOT_RUNNING="$ROOT_STATE_DIR/running.env"
GLOBAL_LOCK="$ROOT_STATE_DIR/run.lock"
GLOBAL_STOP="$ROOT_STATE_DIR/stop"
CHILD_PID=0

[ "$MODE" = cache-auto ] || { echo "缓存并行通道只接受 cache-auto" >&2; exit 2; }
[ -f "$MODDIR/task-worker.sh" ] || { echo "统一 Root Worker 缺失" >&2; exit 5; }

lane_owner_alive() {
  owner=$(sed -n '1p' "$LANE_LOCK/pid" 2>/dev/null)
  case "$owner" in ''|*[!0-9]*) return 1 ;; esac
  [ "$owner" -gt 1 ] && kill -0 "$owner" 2>/dev/null
}

if ! mkdir "$LANE_LOCK" 2>/dev/null; then
  if lane_owner_alive; then
    echo "应用缓存并行通道已有任务运行" >&2
    exit 3
  fi
  rm -rf -- "$LANE_LOCK" 2>/dev/null || true
  mkdir "$LANE_LOCK" 2>/dev/null || { echo "无法恢复应用缓存并行通道" >&2; exit 4; }
fi
printf '%s\n' "$$" >"$LANE_LOCK/pid"

cleanup_lane() {
  [ "$CHILD_PID" -gt 1 ] 2>/dev/null && kill "$CHILD_PID" 2>/dev/null || true
  current=$(sed -n 's/^task_id=//p' "$ROOT_RUNNING" 2>/dev/null | tail -n 1)
  [ "$current" = "$TASK_ID" ] && rm -f "$ROOT_RUNNING" 2>/dev/null || true
  rm -rf -- "$TASK_STATE" "$LANE_LOCK" 2>/dev/null || true
}
handle_signal() {
  trap - EXIT INT TERM
  : >"$TASK_STATE/stop" 2>/dev/null || true
  cleanup_lane
  exit 9
}
trap cleanup_lane EXIT
trap handle_signal INT TERM

rm -rf -- "$TASK_STATE"
mkdir -p "$TASK_STATE/logs" "$TASK_STATE/reports" "$TASK_STATE/task-results" "$ROOT_RESULT_DIR" "$LANE_ROOT"
chmod 0700 "$TASK_STATE" 2>/dev/null || true
for shared in config.conf whitelist.conf custom.rules native-cache-packages.conf totals.env; do
  [ -f "$ROOT_STATE_DIR/$shared" ] && cp -f "$ROOT_STATE_DIR/$shared" "$TASK_STATE/$shared"
done

mirror_progress() {
  [ -d "$GLOBAL_LOCK" ] && return 0
  [ -f "$TASK_STATE/running.env" ] || return 0
  tmp="$ROOT_RUNNING.tmp.cache.$$"
  {
    cat "$TASK_STATE/running.env"
    echo "task_id=$TASK_ID"
    echo "lane=cache"
    echo "parallel_capable=1"
  } >"$tmp" && mv -f "$tmp" "$ROOT_RUNNING"
  chmod 0600 "$ROOT_RUNNING" 2>/dev/null || true
}

BAIZE_ROOT_STATE_DIR="$ROOT_STATE_DIR" BAIZE_STATE_DIR="$TASK_STATE" BAIZE_SHELL_BIN="$SHELL_BIN" \
  "$SHELL_BIN" "$MODDIR/task-worker.sh" "$MODE" "$TRIGGER" "$TASK_ID" "$WAIT_MODE" &
CHILD_PID=$!

while kill -0 "$CHILD_PID" 2>/dev/null; do
  [ -f "$GLOBAL_STOP" ] && : >"$TASK_STATE/stop" 2>/dev/null || true
  mirror_progress
  sleep 1
done
wait "$CHILD_PID" 2>/dev/null
CODE=$?
CHILD_PID=0

waited=0
while [ -d "$GLOBAL_LOCK" ] && [ "$waited" -lt 900 ]; do
  [ -f "$GLOBAL_STOP" ] && : >"$TASK_STATE/stop" 2>/dev/null || true
  sleep 1
  waited=$((waited + 1))
done

if [ -f "$TASK_STATE/task-results/$TASK_ID.env" ]; then
  cp -f "$TASK_STATE/task-results/$TASK_ID.env" "$ROOT_RESULT_DIR/$TASK_ID.env"
fi
if [ -s "$TASK_STATE/history.tsv" ]; then
  cat "$TASK_STATE/history.tsv" >>"$ROOT_STATE_DIR/history.tsv"
  tail -n 100 "$ROOT_STATE_DIR/history.tsv" >"$ROOT_STATE_DIR/history.tsv.tmp.$$" 2>/dev/null &&
    mv -f "$ROOT_STATE_DIR/history.tsv.tmp.$$" "$ROOT_STATE_DIR/history.tsv"
fi
if [ -f "$TASK_STATE/latest.env" ]; then
  cp -f "$TASK_STATE/latest.env" "$ROOT_STATE_DIR/latest.env.tmp.$$" &&
    mv -f "$ROOT_STATE_DIR/latest.env.tmp.$$" "$ROOT_STATE_DIR/latest.env"
fi
if [ -f "$TASK_STATE/totals.env" ]; then
  cp -f "$TASK_STATE/totals.env" "$ROOT_STATE_DIR/totals.env.tmp.$$" &&
    mv -f "$ROOT_STATE_DIR/totals.env.tmp.$$" "$ROOT_STATE_DIR/totals.env"
fi
for file in "$TASK_STATE"/logs/*; do
  [ -f "$file" ] || continue
  cp -f "$file" "$ROOT_STATE_DIR/logs/cache-lane-$TASK_ID-${file##*/}"
done
for file in "$TASK_STATE"/reports/*; do
  [ -f "$file" ] || continue
  cp -f "$file" "$ROOT_STATE_DIR/reports/cache-lane-$TASK_ID-${file##*/}"
done

current=$(sed -n 's/^task_id=//p' "$ROOT_RUNNING" 2>/dev/null | tail -n 1)
[ "$current" = "$TASK_ID" ] && rm -f "$ROOT_RUNNING" 2>/dev/null || true
rm -rf -- "$TASK_STATE" "$LANE_LOCK" 2>/dev/null || true
trap - EXIT INT TERM
exit "$CODE"
