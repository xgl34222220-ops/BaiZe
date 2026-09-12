#!/system/bin/sh
set -u

MODDIR=${0%/*}
TRIGGER=${2:-manual}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
LOCK_DIR="$STATE_DIR/run.lock"
RUNNING_FILE="$STATE_DIR/running.env"
STOP_FILE="$STATE_DIR/stop"
STATE_FILE="$STATE_DIR/deep_scan.env"
TARGETS_FILE="$STATE_DIR/deep_scan.targets"
MANIFEST_FILE="$STATE_DIR/deep_scan.manifest0"
CURSOR_FILE="$STATE_DIR/deep_scan.cursor"
BUILD_SUMMARY="$STATE_DIR/deep_scan.manifest.env"
NATIVE_SCANNER="$MODDIR/native-cleaner.sh"
# ABI 解析辅助。测试夹具可能只暂存部分脚本，缺失时退回到内联实现。
if [ -f "$MODDIR/abi-resolve.sh" ]; then
  . "$MODDIR/abi-resolve.sh"
else
  baize_device_abis() { printf 'arm64-v8a\narmeabi-v7a\nx86_64\n'; }
  baize_resolve_engine() {
    for _abi in $(baize_device_abis); do
      [ -x "$1/bin/$_abi/$2" ] && { printf '%s\n' "$1/bin/$_abi/$2"; return 0; }
    done
    return 1
  }
  baize_require_engine() {
    [ -n "${3:-}" ] && [ -x "$3" ] && { printf '%s\n' "$3"; return 0; }
    baize_resolve_engine "$1" "$2" && return 0
    echo "当前架构没有可用的 $2，请重新刷入完整模块" >&2
    return 8
  }
fi
SNAPSHOT_ENGINE=$(baize_require_engine "$MODDIR" baize_deep_snapshot "${BAIZE_DEEP_SNAPSHOT_ENGINE:-}" 2>/dev/null || true)

mkdir -p "$STATE_DIR"

proc_start_ticks() { [ -r "/proc/$1/stat" ] && awk '{print $22}' "/proc/$1/stat" 2>/dev/null || echo 0; }
pid_is_task() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in *deep-scan-manifest.sh*|*native-scan.sh*|*native-cleaner.sh*|*cleaner.sh*|*task-worker.sh*|*worker-runner.sh*|*apk-scanner.sh*|*apk-snapshot-scan.sh*|*apk-snapshot-clean.sh*|*apk-cleaner.sh*|*one-pass-scan.sh*|*cache-snapshot*|*cache-lane-worker.sh*|*deep-scan-manifest.sh*|*deep-manifest-clean.sh*|*profile-cleaner.sh*|*organizer-worker.sh*|*worker-runner.sh*|*task-worker.sh*|*baize_deep_snapshot*) return 0 ;; esac
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
rm -f "$STOP_FILE" "$MANIFEST_FILE" "$CURSOR_FILE" "$BUILD_SUMMARY"

[ -x "$SNAPSHOT_ENGINE" ] || { echo "深度不可变快照引擎缺失，请重新刷入完整模块" >&2; exit 8; }
[ -f "$NATIVE_SCANNER" ] || { echo "原生深度扫描器缺失" >&2; exit 8; }

ROOTS_FILE="$LOCK_DIR/deep_scan.roots0"
BAIZE_LOCK_HELD=1 BAIZE_SUPPRESS_SCAN_HISTORY=1 BAIZE_DEEP_MANIFEST_ROOTS="$ROOTS_FILE" "$NATIVE_SCANNER" deep-scan "$TRIGGER"
scan_code=$?
if [ "$scan_code" -ne 0 ]; then
  rm -f "$MANIFEST_FILE" "$CURSOR_FILE" "$BUILD_SUMMARY"
  exit "$scan_code"
fi

[ -f "$STATE_FILE" ] && [ -f "$TARGETS_FILE" ] || {
  echo "深度扫描未生成有效目标快照" >&2
  rm -f "$STATE_FILE" "$TARGETS_FILE"
  exit 7
}

max_file_bytes=$(sed -n 's/^max_file_bytes=//p' "$STATE_FILE" 2>/dev/null | tail -n 1)
case "$max_file_bytes" in ''|*[!0-9]*) max_file_bytes=268435456 ;; esac
manifest_tmp="$LOCK_DIR/deep_scan.manifest0"
summary_tmp="$LOCK_DIR/deep_scan.manifest.env"
"$SNAPSHOT_ENGINE" build \
  --targets "$TARGETS_FILE" \
  --roots "$ROOTS_FILE" \
  --whitelist "$STATE_DIR/whitelist.conf" \
  --manifest "$manifest_tmp" \
  --summary "$summary_tmp" \
  --report "$LOCK_DIR/deep_scan.report.tsv" \
  --progress "$RUNNING_FILE" \
  --stop "$STOP_FILE" \
  --max-file-bytes "$max_file_bytes"
build_code=$?
if [ "$build_code" -ne 0 ]; then
  rm -f "$STATE_FILE" "$TARGETS_FILE" "$manifest_tmp" "$summary_tmp"
  {
    echo "mode=deep-scan"
    echo "scan_complete=0"
    echo "files=0"
    echo "bytes=0"
    echo "result=深度扫描未完成，未发布快照，代码 $build_code"
  } >"$STATE_DIR/latest.env"
  [ "$build_code" -eq 9 ] && echo "深度扫描已停止，未保留不完整快照"
  exit "$build_code"
fi

mv -f "$manifest_tmp" "$MANIFEST_FILE" && mv -f "$summary_tmp" "$BUILD_SUMMARY" || {
  rm -f "$STATE_FILE" "$TARGETS_FILE" "$MANIFEST_FILE" "$BUILD_SUMMARY"
  exit 71
}
printf '0\n' >"$CURSOR_FILE" || exit 71
rm -f "$STATE_DIR/deep_clean.accum.env"
manifest_sha=$(sha256sum "$MANIFEST_FILE" 2>/dev/null | awk 'NR==1{print $1}')
manifest_records=$(sed -n 's/^records=//p' "$BUILD_SUMMARY" 2>/dev/null | tail -n 1)
case "$manifest_records" in ''|*[!0-9]*) manifest_records=0 ;; esac
snapshot_epoch=$(sed -n 's/^epoch=//p' "$STATE_FILE" 2>/dev/null | tail -n 1)
case "$snapshot_epoch" in ''|*[!0-9]*) snapshot_epoch=$(date +%s) ;; esac
snapshot_id="${snapshot_epoch}-$(printf '%s' "$manifest_sha" | cut -c1-16)"
state_tmp="$STATE_FILE.tmp.$$"
sed '/^snapshot_id=/d;/^manifest_/d;/^cursor_/d;/^snapshot_schema=/d;/^files=/d;/^bytes=/d;/^targets=/d;/^visited_files=/d;/^visited_dirs=/d;/^scan_complete=/d;/^timed_out_dirs=/d;/^truncated=/d;/^protected_targets=/d' "$STATE_FILE" >"$state_tmp"
# Expansion totals are deliberately provisional. Only the file walk supplies scan totals.
grep -E '^(files|bytes|targets|scan_complete|timed_out_dirs|truncated|protected_targets)=' "$BUILD_SUMMARY" >>"$state_tmp"
{
  echo "snapshot_id=$snapshot_id"
  echo "snapshot_schema=deep-file-manifest-v1"
  echo "manifest_sha=$manifest_sha"
  echo "manifest_records=$manifest_records"
  echo "cursor_file=deep_scan.cursor"
  echo "manifest_engine=deep-manifest-v1"
} >>"$state_tmp"
mv -f "$state_tmp" "$STATE_FILE" || exit 71
scan_bytes=$(sed -n 's/^bytes=//p' "$BUILD_SUMMARY")
scan_files=$(sed -n 's/^files=//p' "$BUILD_SUMMARY")
scan_dirs=$(sed -n 's/^dirs=//p' "$BUILD_SUMMARY")
scan_timed_out=$(sed -n 's/^timed_out_dirs=//p' "$BUILD_SUMMARY")
scan_result="深度逐文件快照已固化：$manifest_records 条记录"
[ "$scan_timed_out" -gt 0 ] && scan_result="$scan_result，$scan_timed_out 个目标超时，扫描不完整"
latest_tmp="$STATE_DIR/latest.env.tmp.$$"
sed '/^files=/d;/^regular_files=/d;/^bytes=/d;/^dirs=/d;/^empty_dirs=/d;/^result=/d;/^scan_complete=/d;/^timed_out_dirs=/d;/^truncated=/d;/^protected_targets=/d;/^deep_slow_items=/d;/^visited_files=/d;/^visited_dirs=/d;/^targets=/d' "$STATE_DIR/latest.env" >"$latest_tmp"
{
  echo "files=$scan_files"
  echo "regular_files=$scan_files"
  echo "dirs=$scan_dirs"
  echo "bytes=$scan_bytes"
  echo "deep_slow_items=$scan_timed_out"
  echo "result=$scan_result"
  grep -E '^(scan_complete|timed_out_dirs|truncated|protected_targets|targets)=' "$BUILD_SUMMARY"
} >>"$latest_tmp"
mv -f "$latest_tmp" "$STATE_DIR/latest.env" || exit 71
sed '1d' "$LOCK_DIR/deep_scan.report.tsv" >>"$STATE_DIR/reports/latest.tsv" || exit 71
if [ "${BAIZE_SUPPRESS_SCAN_HISTORY:-0}" != "1" ]; then
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$(date '+%Y-%m-%d %H:%M:%S')" deep-scan "$scan_bytes" "$scan_files" 0 0 \
    "$scan_result" "$TRIGGER" "深度规则|$scan_bytes|$scan_files" "$snapshot_id" >>"$STATE_DIR/history.tsv" || exit 71
fi
chmod 0600 "$STATE_FILE" "$TARGETS_FILE" "$MANIFEST_FILE" "$CURSOR_FILE" "$BUILD_SUMMARY" 2>/dev/null || true

echo "深度逐文件快照已固化：$manifest_records 条记录，可直接清理且不会重新遍历文件"
