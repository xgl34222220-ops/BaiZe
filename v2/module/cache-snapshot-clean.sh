#!/system/bin/sh
# set -u：未定义变量视为错误。清理脚本以 root 身份删文件，
# 变量拼写错误静默展开成空串会造成 rm -rf "/foo" 这类事故。
set -u

MODDIR=${0%/*}
TRIGGER=${2:-${1:-manual}}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
DATA_ROOT=${BAIZE_DATA_ROOT:-/data}
CONFIG="$STATE_DIR/config.conf"
WHITELIST="$STATE_DIR/whitelist.conf"
PACKAGE_WHITELIST=${BAIZE_PACKAGE_WHITELIST:-$STATE_DIR/native-cache-packages.conf}
REPORT_DIR="$STATE_DIR/reports"
LOG_DIR="$STATE_DIR/logs"
LOCK_DIR="$STATE_DIR/run.lock"
RUNNING_FILE="$STATE_DIR/running.env"
STOP_FILE="$STATE_DIR/stop"
HISTORY_FILE="$STATE_DIR/history.tsv"
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
NATIVE_ENGINE=$(baize_require_engine "$MODDIR" baize_engine "${BAIZE_NATIVE_ENGINE:-}") || exit 8
CACHE_PREFIX=${BAIZE_CACHE_PREFIX:-cache_scan}
case "$CACHE_PREFIX" in cache_scan|cache_auto) ;; *) echo "无效的缓存快照命名空间" >&2; exit 2 ;; esac
CACHE_SCAN_STATE="$STATE_DIR/$CACHE_PREFIX.env"
CACHE_SCAN_ITEMS="$STATE_DIR/$CACHE_PREFIX.items.tsv"
CACHE_SCAN_TARGETS="$STATE_DIR/$CACHE_PREFIX.targets"
CACHE_SCAN_MANIFEST="$STATE_DIR/$CACHE_PREFIX.manifest0"

mkdir -p "$STATE_DIR" "$REPORT_DIR" "$LOG_DIR"
[ -f "$WHITELIST" ] || : >"$WHITELIST"
[ -f "$PACKAGE_WHITELIST" ] || : >"$PACKAGE_WHITELIST"

# 架构支持由 baize_require_engine 判定，包里有对应 ABI 的引擎即可运行。
[ -x "$NATIVE_ENGINE" ] || { echo "C 原生快照清理器不可用，请重新刷入完整模块" >&2; exit 8; }

pid_is_baize_task() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in
    *baize_v2*cleaner.sh*|*baize-v2*cleaner.sh*|*cache-transaction.sh*|*cache-snapshot-clean.sh*|*baize_engine*) return 0 ;;
  esac
  return 1
}

LOCK_OWNED=0
cleanup_lock() {
  [ "$LOCK_OWNED" = "1" ] || return 0
  rm -f "$RUNNING_FILE" 2>/dev/null
  rm -rf -- "$LOCK_DIR" 2>/dev/null
}
handle_signal() {
  trap - EXIT INT TERM
  : >"$STOP_FILE" 2>/dev/null
  cleanup_lock
  exit 9
}

if [ "${BAIZE_LOCK_HELD:-0}" = "1" ]; then
  [ -d "$LOCK_DIR" ] || { echo "缓存事务锁已丢失" >&2; exit 4; }
else
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    old_pid=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
    case "$old_pid" in ''|*[!0-9]*) old_pid=0 ;; esac
    if [ "$old_pid" -gt 1 ] && kill -0 "$old_pid" 2>/dev/null && pid_is_baize_task "$old_pid"; then
      echo "已有扫描或清理任务正在运行"
      exit 3
    fi
    rm -rf -- "$LOCK_DIR" 2>/dev/null
    mkdir "$LOCK_DIR" 2>/dev/null || { echo "无法恢复任务锁，请重试"; exit 4; }
  fi
  LOCK_OWNED=1
  printf '%s\n' "$$" >"$LOCK_DIR/pid"
  trap cleanup_lock EXIT
  trap handle_signal INT TERM
  rm -f "$STOP_FILE"
fi
TMP_DIR="$LOCK_DIR/tmp"
mkdir -p "$TMP_DIR"

START_EPOCH=$(date +%s)
STAMP=$(date '+%Y-%m-%d_%H-%M-%S')
REPORT_FILE="$REPORT_DIR/$STAMP-cache-clean.tsv"
SUMMARY_FILE="$TMP_DIR/$CACHE_PREFIX-clean-summary.env"
LOG_FILE="$LOG_DIR/$STAMP-cache-clean.log"

state_value() { sed -n "s/^$1=//p" "$CACHE_SCAN_STATE" 2>/dev/null | tail -n 1; }
summary_value() { sed -n "s/^$1=//p" "$SUMMARY_FILE" 2>/dev/null | tail -n 1; }
summary_number() {
  value=$(summary_value "$1")
  case "$value" in ''|*[!0-9]*) value=0 ;; esac
  echo "$value"
}
file_sha() { [ -f "$1" ] && sha256sum "$1" 2>/dev/null | awk 'NR==1{print $1}' || echo missing; }
human_bytes() {
  awk -v b="$1" 'BEGIN {
    if (b>=1073741824) printf "%.2f GB",b/1073741824;
    else if(b>=1048576) printf "%.2f MB",b/1048576;
    else if(b>=1024) printf "%.2f KB",b/1024;
    else printf "%.0f B",b
  }'
}
set_phase() {
  phase=$1
  current=${2:-0}
  total=${3:-0}
  path=${4:-}
  tmp="$RUNNING_FILE.tmp.$$"
  {
    echo "mode=cache-clean"
    echo "phase=$phase"
    echo "started=$START_EPOCH"
    echo "progress_current=$current"
    echo "progress_total=$total"
    printf 'current_path=%s\n' "$path" | tr '\r\n' '  '
    echo "engine=native-c-arm64-immutable-snapshot"
  } >"$tmp"
  mv -f "$tmp" "$RUNNING_FILE"
}

if [ ! -f "$CACHE_SCAN_STATE" ] || [ ! -f "$CACHE_SCAN_TARGETS" ] ||
   [ ! -f "$CACHE_SCAN_ITEMS" ] || [ ! -f "$CACHE_SCAN_MANIFEST" ]; then
  echo "没有可用的缓存扫描快照，请先扫描"
  exit 6
fi

epoch=$(state_value epoch)
snapshot_id=$(state_value snapshot_id)
expected_targets_sha=$(state_value targets_sha)
expected_items_sha=$(state_value items_sha)
expected_manifest_sha=$(state_value manifest_sha)
expected_whitelist_sha=$(state_value whitelist_sha)
expected_package_sha=$(state_value package_whitelist_sha)
manifest_format=$(state_value manifest_format)
manifest_items=$(state_value manifest_items)
max_file_bytes=$(state_value max_file_bytes)
authorized_files=$(state_value files)
authorized_bytes=$(state_value bytes)
case "$epoch" in ''|*[!0-9]*) epoch=0 ;; esac
case "$manifest_items" in ''|*[!0-9]*) manifest_items=0 ;; esac
case "$max_file_bytes" in ''|*[!0-9]*) max_file_bytes=$((256 * 1024 * 1024)) ;; esac
case "$authorized_files" in ''|*[!0-9]*) authorized_files=0 ;; esac
case "$authorized_bytes" in ''|*[!0-9]*) authorized_bytes=0 ;; esac

now=$(date +%s)
age=$((now - epoch))
if [ "$epoch" -le 0 ] || [ "$age" -lt 0 ] || [ "$age" -gt 1800 ] || [ -z "$snapshot_id" ]; then
  echo "缓存扫描快照已过期，请重新扫描"
  exit 6
fi
[ "$manifest_format" = "nul-v2" ] || { echo "缓存快照格式不受支持，请重新扫描"; exit 7; }
[ "$manifest_items" -eq "$authorized_files" ] || { echo "缓存快照项目计数不一致，请重新扫描"; exit 7; }
[ "$(file_sha "$CACHE_SCAN_TARGETS")" = "$expected_targets_sha" ] || { echo "缓存目标快照校验失败，请重新扫描"; exit 7; }
[ "$(file_sha "$CACHE_SCAN_ITEMS")" = "$expected_items_sha" ] || { echo "缓存摘要快照校验失败，请重新扫描"; exit 7; }
[ "$(file_sha "$CACHE_SCAN_MANIFEST")" = "$expected_manifest_sha" ] || { echo "缓存逐文件快照校验失败，请重新扫描"; exit 7; }
[ "$(file_sha "$WHITELIST")" = "$expected_whitelist_sha" ] || { echo "白名单已变化，请重新扫描"; exit 7; }
[ "$(file_sha "$PACKAGE_WHITELIST")" = "$expected_package_sha" ] || { echo "应用白名单已变化，请重新扫描"; exit 7; }

set_phase "正在校验不可变缓存快照" 0 "$manifest_items" ""
code=0
"$NATIVE_ENGINE" clean-cache-snapshot \
  --data-root "$DATA_ROOT" --media-root "$MEDIA_ROOT" \
  --whitelist "$WHITELIST" --package-whitelist "$PACKAGE_WHITELIST" \
  --manifest "$CACHE_SCAN_MANIFEST" --max-file-bytes "$max_file_bytes" \
  --report "$REPORT_FILE" --summary "$SUMMARY_FILE" \
  --progress "$RUNNING_FILE" --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1 || code=$?

deleted_files=$(summary_number files)
deleted_bytes=$(summary_number bytes)
skipped=$(summary_number skipped)
errors=$(summary_number errors)
protected_items=$(summary_number protected_items)
protected_bytes=$(summary_number protected_bytes)
action_count() {
  action=$1
  awk -F '\t' -v action="$action" 'NR>1 && $1==action { count++ } END { print count+0 }' "$REPORT_FILE" 2>/dev/null
}
authorized_candidates=$(awk -F '\t' 'NR>1 && NF>=6 { count++ } END { print count+0 }' "$CACHE_SCAN_ITEMS" 2>/dev/null)
cleaned_candidates=$(action_count cleaned)
changed_candidates=$(action_count skipped)
protected_candidates=$(action_count protected)
partial_candidates=$(action_count partial)
failed_candidates=$(action_count failed)
processed_candidates=$((cleaned_candidates + changed_candidates + protected_candidates + partial_candidates + failed_candidates))
end=$(date +%s)
elapsed=$((end - START_EPOCH))

case "$code" in
  0)
    result="缓存不可变快照清理完成，已清理 $(human_bytes "$deleted_bytes")"
    rm -f "$CACHE_SCAN_STATE" "$CACHE_SCAN_TARGETS" "$CACHE_SCAN_ITEMS" "$CACHE_SCAN_MANIFEST"
    ;;
  9)
    result="缓存不可变快照清理已停止，已清理 $(human_bytes "$deleted_bytes")"
    ;;
  *)
    result="缓存不可变快照清理失败（代码 $code），已清理 $(human_bytes "$deleted_bytes")"
    ;;
esac

latest_tmp="$STATE_DIR/latest.env.tmp.$$"
{
  echo "mode=cache-clean"
  echo "time=$(date '+%Y-%m-%d %H:%M:%S')"
  echo "schema=clean-result-v1"
  echo "scanned_candidates=$authorized_candidates"
  echo "authorized_candidates=$authorized_candidates"
  echo "processed_candidates=$processed_candidates"
  echo "cleaned_candidates=$cleaned_candidates"
  echo "changed_candidates=$changed_candidates"
  echo "protected_candidates=$protected_candidates"
  echo "partial_candidates=$partial_candidates"
  echo "failed_candidates=$failed_candidates"
  echo "skipped_candidates=$((changed_candidates + protected_candidates))"
  echo "files=$deleted_files"
  echo "regular_files=$deleted_files"
  echo "empty_files=0"
  echo "empty_dirs=0"
  echo "hidden_items=0"
  echo "fragment_files=0"
  echo "bytes=$deleted_bytes"
  echo "skipped=$skipped"
  echo "errors=$errors"
  echo "protected_items=$protected_items"
  echo "protected_bytes=$protected_bytes"
  echo "risk_low=$cleaned_candidates"
  echo "risk_low_candidates=$cleaned_candidates"
  echo "risk_medium=0"
  echo "risk_medium_candidates=0"
  echo "risk_high=0"
  echo "risk_high_candidates=0"
  echo "risk_critical=0"
  echo "risk_critical_candidates=0"
  echo "category_cache_candidates=$authorized_candidates"
  echo "category_cache_cleaned=$cleaned_candidates"
  echo "category_cache_changed=$changed_candidates"
  echo "category_cache_protected=$protected_candidates"
  echo "category_cache_partial=$partial_candidates"
  echo "category_cache_failed=$failed_candidates"
  echo "deep_slow_items=0"
  echo "deep_mount_items=0"
  echo "deep_truncated=0"
  echo "cache_slow_dirs=0"
  echo "cache_truncated=0"
  echo "deep_progress_current=$manifest_items"
  echo "deep_progress_total=$manifest_items"
  echo "whitelisted=0"
  echo "elapsed=$elapsed"
  echo "engine=native-c-arm64-immutable-snapshot"
  echo "result=$result"
} >"$latest_tmp"
mv -f "$latest_tmp" "$STATE_DIR/latest.env"

[ -f "$REPORT_FILE" ] && cp -f "$REPORT_FILE" "$REPORT_DIR/latest.tsv"
{
  echo "----------------------------------------"
  echo "$result"
  echo "扫描快照: $snapshot_id"
  echo "授权候选: $authorized_candidates 项 · 文件 $authorized_files 个 / $(human_bytes "$authorized_bytes")"
  echo "实际结果: 清理 $cleaned_candidates 项 · 变化 $changed_candidates · 保护 $protected_candidates · 部分 $partial_candidates · 失败 $failed_candidates"
  echo "实际删除: $deleted_files 个文件 / $(human_bytes "$deleted_bytes") | 引擎跳过: $skipped | 引擎错误: $errors | 耗时: ${elapsed}s"
} >>"$LOG_FILE"
cp -f "$LOG_FILE" "$LOG_DIR/latest.log"

printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$(date '+%Y-%m-%d %H:%M:%S')" "cache-clean" "$deleted_bytes" "$deleted_files" "0" "$errors" \
  "$result" "$TRIGGER" "应用缓存|$deleted_bytes|$deleted_files" "$snapshot_id" >>"$HISTORY_FILE"
tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"

echo "$result"
echo "扫描快照: $snapshot_id | 实际清理: $deleted_files 个 | 变化或跳过: $skipped | 失败: $errors | 耗时: ${elapsed}s"
cleanup_lock
trap - EXIT INT TERM
exit "$code"
