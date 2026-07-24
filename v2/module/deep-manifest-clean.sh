#!/system/bin/sh
set -u

MODDIR=${0%/*}
TRIGGER=${2:-manual}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
CONFIG="$STATE_DIR/config.conf"
WHITELIST="$STATE_DIR/whitelist.conf"
DEEP_RULES=${BAIZE_DEEP_RULES:-$MODDIR/config/deep.rules}
LOCK_DIR="$STATE_DIR/run.lock"
RUNNING_FILE="$STATE_DIR/running.env"
STOP_FILE="$STATE_DIR/stop"
STATE_FILE="$STATE_DIR/deep_scan.env"
TARGETS_FILE="$STATE_DIR/deep_scan.targets"
MANIFEST_FILE="$STATE_DIR/deep_scan.manifest0"
CURSOR_FILE="$STATE_DIR/deep_scan.cursor"
ACCUM_FILE="$STATE_DIR/deep_clean.accum.env"
HISTORY_FILE="$STATE_DIR/history.tsv"
REPORT_DIR="$STATE_DIR/reports"
LOG_DIR="$STATE_DIR/logs"
SNAPSHOT_ENGINE="$MODDIR/bin/arm64-v8a/baize_deep_snapshot"

mkdir -p "$STATE_DIR" "$REPORT_DIR" "$LOG_DIR"
[ -f "$WHITELIST" ] || : >"$WHITELIST"

state_value() { sed -n "s/^$1=//p" "$STATE_FILE" 2>/dev/null | tail -n 1; }
summary_value() { summary_file=$1; summary_key=$2; sed -n "s/^$summary_key=//p" "$summary_file" 2>/dev/null | tail -n 1; }
uint_value() { value=$1; fallback=${2:-0}; case "$value" in ''|*[!0-9]*) value=$fallback ;; esac; echo "$value"; }
file_sha() { [ -f "$1" ] && sha256sum "$1" 2>/dev/null | awk 'NR==1{print $1}' || echo missing; }
human_bytes() { awk -v b="$1" 'BEGIN { if (b>=1073741824) printf "%.2f GB",b/1073741824; else if(b>=1048576) printf "%.2f MB",b/1048576; else if(b>=1024) printf "%.2f KB",b/1024; else printf "%.0f B",b }'; }
proc_start_ticks() { [ -r "/proc/$1/stat" ] && awk '{print $22}' "/proc/$1/stat" 2>/dev/null || echo 0; }
pid_is_task() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in *deep-manifest-clean.sh*|*cleaner.sh*|*task-worker.sh*|*worker-runner.sh*|*baize_deep_snapshot*) return 0 ;; esac
  return 1
}

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  old_pid=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
  case "$old_pid" in ''|*[!0-9]*) old_pid=0 ;; esac
  if [ "$old_pid" -gt 1 ] && kill -0 "$old_pid" 2>/dev/null && pid_is_task "$old_pid"; then
    echo "已有扫描或清理任务正在运行"
    exit 3
  fi
  rm -rf -- "$LOCK_DIR" 2>/dev/null
  rm -f "$RUNNING_FILE" 2>/dev/null
  mkdir "$LOCK_DIR" 2>/dev/null || { echo "无法恢复任务锁，请重试"; exit 4; }
fi
printf '%s\n' "$$" >"$LOCK_DIR/pid"
printf '%s\n' "$(proc_start_ticks $$)" >"$LOCK_DIR/start_ticks"
cleanup_lock() { rm -f "$RUNNING_FILE" 2>/dev/null; rm -rf -- "$LOCK_DIR" 2>/dev/null; }
trap cleanup_lock EXIT
rm -f "$STOP_FILE"

[ -x "$SNAPSHOT_ENGINE" ] || { echo "深度不可变快照引擎缺失，请重新刷入完整模块" >&2; exit 8; }
[ -s "$STATE_FILE" ] && [ -s "$MANIFEST_FILE" ] && [ -f "$CURSOR_FILE" ] || {
  echo "没有可用的深度逐文件快照，请先完成深度扫描"
  exit 6
}

snapshot_id=$(state_value snapshot_id)
epoch=$(uint_value "$(state_value epoch)" 0)
manifest_sha=$(state_value manifest_sha)
expected_targets_sha=$(state_value targets_sha)
expected_whitelist_sha=$(state_value whitelist_sha)
expected_rules_sha=$(state_value rules_sha)
max_file_bytes=$(uint_value "$(state_value max_file_bytes)" 268435456)
now=$(date +%s)
age=$((now - epoch))
if [ "$epoch" -le 0 ] || [ "$age" -lt 0 ] || [ "$age" -gt 1800 ] || [ -z "$snapshot_id" ]; then
  echo "深度扫描快照已过期，请重新扫描"
  exit 6
fi
[ "$(file_sha "$MANIFEST_FILE")" = "$manifest_sha" ] || { echo "深度逐文件快照校验失败"; exit 7; }
[ "$(file_sha "$TARGETS_FILE")" = "$expected_targets_sha" ] || { echo "深度目标快照校验失败"; exit 7; }
[ "$(file_sha "$WHITELIST")" = "$expected_whitelist_sha" ] || { echo "白名单已变化，请重新扫描"; exit 7; }
[ -f "$DEEP_RULES" ] && [ "$(file_sha "$DEEP_RULES")" = "$expected_rules_sha" ] || { echo "深度规则库已变化，请重新扫描"; exit 7; }

if [ -f "$ACCUM_FILE" ] && [ "$(sed -n 's/^snapshot_id=//p' "$ACCUM_FILE" | tail -n 1)" != "$snapshot_id" ]; then
  rm -f "$ACCUM_FILE"
fi
acc_files=$(uint_value "$(sed -n 's/^files=//p' "$ACCUM_FILE" 2>/dev/null | tail -n 1)" 0)
acc_dirs=$(uint_value "$(sed -n 's/^dirs=//p' "$ACCUM_FILE" 2>/dev/null | tail -n 1)" 0)
acc_bytes=$(uint_value "$(sed -n 's/^bytes=//p' "$ACCUM_FILE" 2>/dev/null | tail -n 1)" 0)
acc_skipped=$(uint_value "$(sed -n 's/^skipped=//p' "$ACCUM_FILE" 2>/dev/null | tail -n 1)" 0)
acc_errors=$(uint_value "$(sed -n 's/^errors=//p' "$ACCUM_FILE" 2>/dev/null | tail -n 1)" 0)

STAMP=$(date '+%Y-%m-%d_%H-%M-%S')
REPORT_FILE="$REPORT_DIR/$STAMP-deep-clean.tsv"
SUMMARY_FILE="$LOCK_DIR/deep-clean-summary.env"
LOG_FILE="$LOG_DIR/$STAMP-deep-clean.log"
START_EPOCH=$(date +%s)

"$SNAPSHOT_ENGINE" clean \
  --manifest "$MANIFEST_FILE" \
  --cursor "$CURSOR_FILE" \
  --report "$REPORT_FILE" \
  --summary "$SUMMARY_FILE" \
  --whitelist "$WHITELIST" \
  --progress "$RUNNING_FILE" \
  --stop "$STOP_FILE" \
  --max-file-bytes "$max_file_bytes"
code=$?

run_files=$(uint_value "$(summary_value "$SUMMARY_FILE" files)" 0)
run_dirs=$(uint_value "$(summary_value "$SUMMARY_FILE" dirs)" 0)
run_bytes=$(uint_value "$(summary_value "$SUMMARY_FILE" bytes)" 0)
run_skipped=$(uint_value "$(summary_value "$SUMMARY_FILE" skipped)" 0)
run_errors=$(uint_value "$(summary_value "$SUMMARY_FILE" errors)" 0)
remaining=$(uint_value "$(summary_value "$SUMMARY_FILE" remaining)" 0)
cursor=$(uint_value "$(summary_value "$SUMMARY_FILE" cursor)" 0)
records=$(uint_value "$(summary_value "$SUMMARY_FILE" records)" 0)

total_files=$((acc_files + run_files))
total_dirs=$((acc_dirs + run_dirs))
total_bytes=$((acc_bytes + run_bytes))
total_skipped=$((acc_skipped + run_skipped))
total_errors=$((acc_errors + run_errors))

if [ "$code" -eq 9 ]; then
  stopped=1
  result="深度不可变快照清理已停止，已保存到第 ${cursor}/${records} 条，累计释放 $(human_bytes "$total_bytes")"
  accum_tmp="$ACCUM_FILE.tmp.$$"
  {
    echo "snapshot_id=$snapshot_id"
    echo "files=$total_files"
    echo "dirs=$total_dirs"
    echo "bytes=$total_bytes"
    echo "skipped=$total_skipped"
    echo "errors=$total_errors"
  } >"$accum_tmp" && mv -f "$accum_tmp" "$ACCUM_FILE"
elif [ "$code" -eq 0 ]; then
  stopped=0
  remaining=0
  result="深度不可变快照清理完成，累计释放 $(human_bytes "$total_bytes")"
  rm -f "$STATE_FILE" "$TARGETS_FILE" "$MANIFEST_FILE" "$CURSOR_FILE" "$STATE_DIR/deep_scan.manifest.env" "$ACCUM_FILE"
else
  stopped=0
  result="深度不可变快照清理失败，代码 $code，进度保留在 ${cursor}/${records}"
  accum_tmp="$ACCUM_FILE.tmp.$$"
  {
    echo "snapshot_id=$snapshot_id"
    echo "files=$total_files"
    echo "dirs=$total_dirs"
    echo "bytes=$total_bytes"
    echo "skipped=$total_skipped"
    echo "errors=$total_errors"
  } >"$accum_tmp" && mv -f "$accum_tmp" "$ACCUM_FILE"
fi

END_EPOCH=$(date +%s)
elapsed=$((END_EPOCH - START_EPOCH))
latest_tmp="$STATE_DIR/latest.env.tmp.$$"
{
  echo "mode=deep-clean"
  echo "time=$(date '+%Y-%m-%d %H:%M:%S')"
  echo "schema=clean-result-v2"
  echo "files=$total_files"
  echo "regular_files=$total_files"
  echo "empty_dirs=$total_dirs"
  echo "bytes=$total_bytes"
  echo "skipped=$total_skipped"
  echo "errors=$total_errors"
  echo "protected_items=$total_skipped"
  echo "deep_manifest_records=$records"
  echo "deep_manifest_cursor=$cursor"
  echo "deep_remaining_records=$remaining"
  echo "deep_stopped=$stopped"
  echo "elapsed=$elapsed"
  echo "engine=deep-manifest-v1"
  echo "result=$result"
} >"$latest_tmp" && mv -f "$latest_tmp" "$STATE_DIR/latest.env"
cp -f "$REPORT_FILE" "$REPORT_DIR/latest.tsv" 2>/dev/null || true
{
  echo "----------------------------------------"
  echo "$result"
  echo "扫描快照: $snapshot_id"
  echo "清理进度: $cursor/$records | 剩余: $remaining"
  echo "累计清理: $total_files 个文件 / $total_dirs 个目录 / $(human_bytes "$total_bytes")"
  echo "保护跳过: $total_skipped | 失败: $total_errors | 本次耗时: ${elapsed}s"
} >>"$LOG_FILE"
cp -f "$LOG_FILE" "$LOG_DIR/latest.log"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$(date '+%Y-%m-%d %H:%M:%S')" deep-clean "$run_bytes" "$run_files" "$run_dirs" "$run_errors" \
  "$result" "$TRIGGER" "深度不可变快照|$run_bytes|$run_files" "$snapshot_id" >>"$HISTORY_FILE"
tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"

echo "$result"
echo "进度: $cursor/$records | 剩余: $remaining | 文件: $total_files | 目录: $total_dirs | 跳过: $total_skipped | 失败: $total_errors"
[ "$code" -eq 9 ] && exit 9
exit "$code"
