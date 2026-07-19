#!/system/bin/sh

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
CACHE_SCAN_STATE="$STATE_DIR/cache_scan.env"
CACHE_SCAN_ITEMS="$STATE_DIR/cache_scan.items.tsv"
CACHE_SCAN_TARGETS="$STATE_DIR/cache_scan.targets"

mkdir -p "$STATE_DIR" "$REPORT_DIR" "$LOG_DIR"
[ -f "$WHITELIST" ] || : >"$WHITELIST"
[ -f "$PACKAGE_WHITELIST" ] || : >"$PACKAGE_WHITELIST"

pid_is_baize_task() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in
    *baize_v2*cleaner.sh*|*baize-v2*cleaner.sh*|*cache-snapshot-clean.sh*|*baize_engine*) return 0 ;;
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
  mkdir "$LOCK_DIR" 2>/dev/null || { echo "无法恢复任务锁，请重试"; exit 4; }
fi
printf '%s\n' "$$" >"$LOCK_DIR/pid"
TMP_DIR="$LOCK_DIR/tmp"
mkdir -p "$TMP_DIR"

cleanup_lock() {
  rm -f "$RUNNING_FILE" 2>/dev/null
  rm -rf -- "$LOCK_DIR" 2>/dev/null
}
handle_signal() {
  trap - EXIT INT TERM
  : >"$STOP_FILE" 2>/dev/null
  cleanup_lock
  exit 9
}
trap cleanup_lock EXIT
trap handle_signal INT TERM
rm -f "$STOP_FILE"

START_EPOCH=$(date +%s)
STAMP=$(date '+%Y-%m-%d_%H-%M-%S')
REPORT_FILE="$REPORT_DIR/$STAMP-cache-clean.tsv"
LOG_FILE="$LOG_DIR/$STAMP-cache-clean.log"

state_value() { sed -n "s/^$1=//p" "$CACHE_SCAN_STATE" 2>/dev/null | tail -n 1; }
file_sha() { [ -f "$1" ] && sha256sum "$1" 2>/dev/null | awk 'NR==1{print $1}' || echo missing; }
human_bytes() { awk -v b="$1" 'BEGIN { if (b>=1073741824) printf "%.2f GB",b/1073741824; else if(b>=1048576) printf "%.2f MB",b/1048576; else if(b>=1024) printf "%.2f KB",b/1024; else printf "%.0f B",b }'; }
count_nul() { tr -cd '\000' <"$1" | wc -c | tr -d ' '; }
bytes_from_list() {
  [ -s "$1" ] || { echo 0; return; }
  xargs -0 du -k <"$1" 2>/dev/null | awk '{sum += $1} END {printf "%.0f", sum * 1024}'
}
existing_files_to_list() {
  source_list=$1 target_list=$2
  : >"$target_list"
  while IFS= read -r -d '' candidate; do
    [ -f "$candidate" ] && [ ! -L "$candidate" ] && printf '%s\0' "$candidate" >>"$target_list"
  done <"$source_list"
}
should_stop() { [ -f "$STOP_FILE" ]; }

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
    echo "engine=snapshot-shell-v42.5"
  } >"$tmp"
  mv -f "$tmp" "$RUNNING_FILE"
}

cache_target_package() {
  target=${1%/}
  user="" package="" leaf=""
  case "$target" in
    "$DATA_ROOT"/user/*/*/cache|"$DATA_ROOT"/user/*/*/code_cache)
      rest=${target#"$DATA_ROOT"/user/}; user=${rest%%/*}; rest=${rest#*/}; package=${rest%%/*}; leaf=${rest#*/}
      ;;
    "$DATA_ROOT"/user_de/*/*/cache|"$DATA_ROOT"/user_de/*/*/code_cache)
      rest=${target#"$DATA_ROOT"/user_de/}; user=${rest%%/*}; rest=${rest#*/}; package=${rest%%/*}; leaf=${rest#*/}
      ;;
    "$MEDIA_ROOT"/*/Android/data/*/cache|"$MEDIA_ROOT"/*/Android/data/*/code_cache)
      rest=${target#"$MEDIA_ROOT"/}; user=${rest%%/*}; rest=${rest#*/Android/data/}; package=${rest%%/*}; leaf=${rest#*/}
      ;;
    *) return 1 ;;
  esac
  case "$user" in ''|*[!0-9]*) return 1 ;; esac
  case "$leaf" in cache|code_cache) ;; *) return 1 ;; esac
  printf '%s' "$package" | grep -Eq '^[A-Za-z0-9_]+([.][A-Za-z0-9_-]+)+$' || return 1
  printf '%s\n' "$package"
}

path_conflicts_whitelist() {
  target=${1%/}
  [ -f "$WHITELIST" ] || return 1
  while IFS= read -r raw || [ -n "$raw" ]; do
    item=$(printf '%s' "$raw" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    case "$item" in ''|'#'*) continue ;; esac
    case "$item" in /*) ;; *) continue ;; esac
    item=${item%/}
    [ -n "$item" ] || item=/
    case "$target" in "$item"|"$item"/*) return 0 ;; esac
    case "$item" in "$target"|"$target"/*) return 0 ;; esac
  done <"$WHITELIST"
  return 1
}

wait_with_stop() {
  child=$1
  while kill -0 "$child" 2>/dev/null; do
    if should_stop; then
      kill "$child" 2>/dev/null
      wait "$child" 2>/dev/null
      return 9
    fi
    sleep 1
  done
  wait "$child"
}

find_snapshot_files() {
  target=$1 output=$2 max_bytes=$3 min_days=$4
  if [ "$min_days" -gt 0 ]; then
    # The state file is written only after scanning completes. -newer therefore protects every file
    # created or modified after that scan, while -mtime keeps the configured retention period.
    find "$target" -xdev -mindepth 1 -type f ! -size "+${max_bytes}c" ! -newer "$CACHE_SCAN_STATE" -mtime "+$((min_days - 1))" -print0 >"$output" 2>/dev/null &
  else
    find "$target" -xdev -mindepth 1 -type f ! -size "+${max_bytes}c" ! -newer "$CACHE_SCAN_STATE" -print0 >"$output" 2>/dev/null &
  fi
  find_pid=$!
  wait_with_stop "$find_pid"
}

delete_file_list() {
  input=$1
  xargs -0 -n 200 rm -f -- <"$input" 2>/dev/null &
  delete_pid=$!
  wait_with_stop "$delete_pid"
}

write_latest() {
  files=$1 bytes=$2 errors=$3 skipped=$4 elapsed=$5 result=$6
  {
    echo "mode=cache-clean"
    echo "time=$(date '+%Y-%m-%d %H:%M:%S')"
    echo "files=$files"
    echo "regular_files=$files"
    echo "empty_files=0"
    echo "empty_dirs=0"
    echo "hidden_items=0"
    echo "fragment_files=0"
    echo "bytes=$bytes"
    echo "skipped=$skipped"
    echo "errors=$errors"
    echo "protected_items=$skipped"
    echo "protected_bytes=0"
    echo "risk_low=$files"
    echo "risk_medium=0"
    echo "risk_high=0"
    echo "risk_critical=0"
    echo "deep_slow_items=0"
    echo "deep_mount_items=0"
    echo "deep_truncated=0"
    echo "cache_slow_dirs=0"
    echo "cache_truncated=0"
    echo "deep_progress_current=0"
    echo "deep_progress_total=0"
    echo "whitelisted=0"
    echo "elapsed=$elapsed"
    echo "engine=snapshot-shell-v42.5"
    echo "result=$result"
  } >"$STATE_DIR/latest.env"
}

if [ ! -s "$CACHE_SCAN_STATE" ] || [ ! -s "$CACHE_SCAN_TARGETS" ] || [ ! -s "$CACHE_SCAN_ITEMS" ]; then
  echo "没有可用的缓存扫描快照，请先扫描"
  exit 6
fi

epoch=$(state_value epoch)
snapshot_id=$(state_value snapshot_id)
expected_targets_sha=$(state_value targets_sha)
expected_whitelist_sha=$(state_value whitelist_sha)
expected_package_sha=$(state_value package_whitelist_sha)
min_age_days=$(state_value min_age_days)
max_file_bytes=$(state_value max_file_bytes)
authorized_files=$(state_value files)
authorized_bytes=$(state_value bytes)
case "$epoch" in ''|*[!0-9]*) epoch=0 ;; esac
case "$min_age_days" in ''|*[!0-9]*) min_age_days=0 ;; esac
case "$max_file_bytes" in ''|*[!0-9]*) max_file_bytes=$((256 * 1024 * 1024)) ;; esac
case "$authorized_files" in ''|*[!0-9]*) authorized_files=0 ;; esac
case "$authorized_bytes" in ''|*[!0-9]*) authorized_bytes=0 ;; esac

now=$(date +%s)
age=$((now - epoch))
if [ "$epoch" -le 0 ] || [ "$age" -lt 0 ] || [ "$age" -gt 1800 ] || [ -z "$snapshot_id" ]; then
  echo "缓存扫描快照已过期，请重新扫描"
  exit 6
fi
[ "$(file_sha "$CACHE_SCAN_TARGETS")" = "$expected_targets_sha" ] || { echo "缓存扫描快照校验失败，请重新扫描"; exit 7; }
[ "$(file_sha "$WHITELIST")" = "$expected_whitelist_sha" ] || { echo "白名单已变化，请重新扫描"; exit 7; }
[ "$(file_sha "$PACKAGE_WHITELIST")" = "$expected_package_sha" ] || { echo "应用白名单已变化，请重新扫描"; exit 7; }

total=$(wc -l <"$CACHE_SCAN_TARGETS" 2>/dev/null | tr -d ' ')
case "$total" in ''|*[!0-9]*) total=0 ;; esac
[ "$total" -gt 0 ] || { echo "缓存扫描快照为空，请重新扫描"; exit 6; }

printf 'action\trisk\tcategory\titems\tbytes\tpath\n' >"$REPORT_FILE"
set_phase "正在校验缓存扫描快照" 0 "$total" ""
current=0
deleted_files=0
deleted_bytes=0
errors=0
skipped=0
code=0

while IFS= read -r target || [ -n "$target" ]; do
  [ -n "$target" ] || continue
  current=$((current + 1))
  if should_stop; then code=9; break; fi

  package=$(cache_target_package "$target" 2>/dev/null)
  if [ -z "$package" ] || [ ! -d "$target" ] || [ -L "$target" ]; then
    skipped=$((skipped + 1))
    printf 'protected\tlow\t缓存快照\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"
    continue
  fi
  if grep -Fqx -- "$package" "$PACKAGE_WHITELIST" 2>/dev/null || path_conflicts_whitelist "$target"; then
    skipped=$((skipped + 1))
    printf 'protected\tlow\t缓存白名单\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"
    continue
  fi

  set_phase "正在清理刚才扫描到的缓存" "$current" "$total" "$target"
  list="$TMP_DIR/cache-clean.$current.files0"
  remaining="$TMP_DIR/cache-clean.$current.remaining0"
  : >"$list"
  if ! find_snapshot_files "$target" "$list" "$max_file_bytes" "$min_age_days"; then
    find_code=$?
    if [ "$find_code" -eq 9 ] || should_stop; then code=9; break; fi
    errors=$((errors + 1))
    continue
  fi

  count=$(count_nul "$list")
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  if [ "$count" -gt 0 ]; then
    estimated=$(bytes_from_list "$list")
    case "$estimated" in ''|*[!0-9]*) estimated=0 ;; esac
    if ! delete_file_list "$list"; then
      delete_code=$?
      [ "$delete_code" -eq 9 ] || should_stop && code=9
    fi
    existing_files_to_list "$list" "$remaining"
    remain=$(count_nul "$remaining")
    case "$remain" in ''|*[!0-9]*) remain=0 ;; esac
    remaining_bytes=$(bytes_from_list "$remaining")
    case "$remaining_bytes" in ''|*[!0-9]*) remaining_bytes=0 ;; esac
    actual=$((count - remain))
    [ "$actual" -lt 0 ] && actual=0
    actual_bytes=$((estimated - remaining_bytes))
    [ "$actual_bytes" -lt 0 ] && actual_bytes=0
    deleted_files=$((deleted_files + actual))
    deleted_bytes=$((deleted_bytes + actual_bytes))
    errors=$((errors + remain))
    printf 'cleaned\tlow\t缓存快照:%s\t%s\t%s\t%s\n' "$package" "$actual" "$actual_bytes" "$target" >>"$REPORT_FILE"
    [ "$remain" -gt 0 ] && printf 'failed\tlow\t缓存快照:%s\t%s\t%s\t%s\n' "$package" "$remain" "$remaining_bytes" "$target" >>"$REPORT_FILE"
  fi
  [ "$code" -eq 9 ] && break
  find "$target" -xdev -depth -mindepth 1 -type d -empty -delete 2>/dev/null
  rm -f "$list" "$remaining"
  if should_stop; then code=9; break; fi
done <"$CACHE_SCAN_TARGETS"

end=$(date +%s)
elapsed=$((end - START_EPOCH))
if [ "$code" -eq 9 ]; then
  result="缓存快照清理已停止，已清理 $(human_bytes "$deleted_bytes")"
else
  result="缓存快照清理完成，已清理 $(human_bytes "$deleted_bytes")"
  rm -f "$CACHE_SCAN_STATE" "$CACHE_SCAN_TARGETS" "$CACHE_SCAN_ITEMS"
fi

write_latest "$deleted_files" "$deleted_bytes" "$errors" "$skipped" "$elapsed" "$result"
cp -f "$REPORT_FILE" "$REPORT_DIR/latest.tsv"
{
  echo "----------------------------------------"
  echo "$result"
  echo "扫描快照: $snapshot_id"
  echo "授权项目: $authorized_files 个 / $(human_bytes "$authorized_bytes")"
  echo "实际清理: $deleted_files 个 / $(human_bytes "$deleted_bytes") | 跳过: $skipped | 失败: $errors | 耗时: ${elapsed}s"
} >>"$LOG_FILE"
cp -f "$LOG_FILE" "$LOG_DIR/latest.log"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$(date '+%Y-%m-%d %H:%M:%S')" "cache-clean" "$deleted_bytes" "$deleted_files" "0" "$errors" \
  "$result" "$TRIGGER" "应用缓存|$deleted_bytes|$deleted_files" "$snapshot_id" >>"$HISTORY_FILE"
tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"

echo "$result"
echo "扫描快照: $snapshot_id | 实际清理: $deleted_files 个 | 跳过: $skipped | 失败: $errors | 耗时: ${elapsed}s"
cleanup_lock
trap - EXIT INT TERM
[ "$code" -eq 9 ] && exit 9
exit 0
