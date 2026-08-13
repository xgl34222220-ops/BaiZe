#!/system/bin/sh

MODDIR=${0%/*}
MODE=${1:-deep-clean}
TRIGGER=${2:-manual}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
DATA_ROOT=${BAIZE_DATA_ROOT:-/data}
WHITELIST="$STATE_DIR/whitelist.conf"
DEEP_RULES=${BAIZE_DEEP_RULES:-$MODDIR/config/deep.rules}
REPORT_DIR="$STATE_DIR/reports"
LOG_DIR="$STATE_DIR/logs"
LOCK_DIR="$STATE_DIR/run.lock"
RUNNING_FILE="$STATE_DIR/running.env"
STOP_FILE="$STATE_DIR/stop"
HISTORY_FILE="$STATE_DIR/history.tsv"

case "$MODE" in
  deep-clean)
    STATE_FILE="$STATE_DIR/deep_scan.env"
    TARGETS_FILE="$STATE_DIR/deep_scan.targets"
    TITLE="深度规则"
    CATEGORY="深度安全项"
    ;;
  corpse-clean)
    STATE_FILE="$STATE_DIR/corpse_scan.env"
    TARGETS_FILE="$STATE_DIR/corpse_scan.targets"
    TITLE="卸载残留"
    CATEGORY="确认的卸载残留"
    ;;
  *) echo "不支持的快照清理模式：$MODE" >&2; exit 2 ;;
esac
case "$MODE" in corpse-*) SCOPED_WHITELIST="$STATE_DIR/whitelist.corpses.conf" ;; *) SCOPED_WHITELIST="$STATE_DIR/whitelist.deep.conf" ;; esac
[ -f "$SCOPED_WHITELIST" ] && WHITELIST="$SCOPED_WHITELIST"

mkdir -p "$STATE_DIR" "$REPORT_DIR" "$LOG_DIR"
[ -f "$WHITELIST" ] || : >"$WHITELIST"

pid_is_baize_task() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in
    *baize_v2*cleaner.sh*|*baize-v2*cleaner.sh*|*native-scan.sh*|*profile-snapshot-clean.sh*|*cache-snapshot-clean.sh*|*baize_engine*) return 0 ;;
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
REPORT_FILE="$REPORT_DIR/$STAMP-$MODE.tsv"
LOG_FILE="$LOG_DIR/$STAMP-$MODE.log"

state_value() { sed -n "s/^$1=//p" "$STATE_FILE" 2>/dev/null | tail -n 1; }
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
    echo "mode=$MODE"
    echo "phase=$phase"
    echo "started=$START_EPOCH"
    echo "progress_current=$current"
    echo "progress_total=$total"
    printf 'current_path=%s\n' "$path" | tr '\r\n' '  '
    echo "engine=profile-snapshot-v42.6"
  } >"$tmp"
  mv -f "$tmp" "$RUNNING_FILE"
}

path_relation() {
  parent=${1%/}
  child=${2%/}
  [ "$parent" = "$child" ] && return 0
  case "$child" in "$parent"/*) return 0 ;; esac
  return 1
}

path_conflicts_whitelist() {
  target=${1%/}
  while IFS= read -r raw || [ -n "$raw" ]; do
    item=$(printf '%s' "$raw" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    case "$item" in ''|'#'*) continue ;; /*) ;; *) continue ;; esac
    item=${item%/}
    [ -n "$item" ] || item=/
    path_relation "$item" "$target" && return 0
    path_relation "$target" "$item" && return 0
  done <"$WHITELIST"
  return 1
}

deep_path_allowed() {
  path=${1%/}
  [ -n "$path" ] && [ "$path" != "/" ] || return 1
  case "$path" in
    /data/adb|/data/adb/*|/data/app|/data/app/*|/data/system|/data/system/*|/data/misc|/data/misc/*|/data/dalvik-cache|/data/dalvik-cache/*|/system|/system/*|/vendor|/vendor/*|/product|/product/*|/apex|/apex/*) return 1 ;;
  esac
  case "$path" in
    /data/data/*|/data/user/*|/data/user_de/*|/data/cache/*|/data/media/*|/data_mirror/data_ce/*) return 0 ;;
  esac
  return 1
}

corpse_target_info() {
  target=${1%/}
  case "$target" in
    "$MEDIA_ROOT"/*/Android/data/*|"$MEDIA_ROOT"/*/Android/obb/*|"$MEDIA_ROOT"/*/Android/media/*) ;;
    *) return 1 ;;
  esac
  rest=${target#"$MEDIA_ROOT"/}
  user=${rest%%/*}
  rest=${rest#*/Android/}
  bucket=${rest%%/*}
  package=${rest#*/}
  case "$user" in ''|*[!0-9]*) return 1 ;; esac
  case "$bucket" in data|obb|media) ;; *) return 1 ;; esac
  case "$package" in ''|*/*|*[!A-Za-z0-9_.-]*) return 1 ;; esac
  printf '%s\t%s\t%s\n' "$user" "$bucket" "$package"
}

package_installed() {
  user=$1 package=$2
  if [ -n "${BAIZE_INSTALLED_ROOT:-}" ]; then
    list="$BAIZE_INSTALLED_ROOT/$user.txt"
    [ -f "$list" ] || return 2
    grep -Fqx -- "$package" "$list" 2>/dev/null
    return $?
  fi
  if command -v cmd >/dev/null 2>&1; then
    output=$(cmd package list packages --user "$user" "$package" 2>/dev/null)
    [ $? -eq 0 ] || return 2
    printf '%s\n' "$output" | sed 's/^package://' | grep -Fqx -- "$package"
    return $?
  fi
  if command -v pm >/dev/null 2>&1; then
    output=$(pm list packages --user "$user" "$package" 2>/dev/null)
    [ $? -eq 0 ] || return 2
    printf '%s\n' "$output" | sed 's/^package://' | grep -Fqx -- "$package"
    return $?
  fi
  return 2
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
  target=$1 output=$2 max_bytes=$3
  find "$target" -xdev -mindepth 1 -type f ! -size "+${max_bytes}c" ! -newer "$STATE_FILE" -print0 >"$output" 2>/dev/null &
  child=$!
  wait_with_stop "$child"
}

delete_file_list() {
  input=$1
  xargs -0 -n 200 rm -f -- <"$input" 2>/dev/null &
  child=$!
  wait_with_stop "$child"
}

file_size() {
  value=$(stat -c %s "$1" 2>/dev/null)
  case "$value" in ''|*[!0-9]*) value=$(wc -c <"$1" 2>/dev/null | tr -d ' ') ;; esac
  case "$value" in ''|*[!0-9]*) value=0 ;; esac
  echo "$value"
}

write_latest() {
  files=$1 dirs=$2 bytes=$3 errors=$4 skipped=$5 elapsed=$6 result=$7
  {
    echo "mode=$MODE"
    echo "time=$(date '+%Y-%m-%d %H:%M:%S')"
    echo "files=$files"
    echo "regular_files=$files"
    echo "empty_files=0"
    echo "empty_dirs=$dirs"
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
    echo "engine=profile-snapshot-v42.6"
    echo "result=$result"
  } >"$STATE_DIR/latest.env"
}

[ -s "$STATE_FILE" ] && [ -s "$TARGETS_FILE" ] || { echo "没有可用的${TITLE}扫描快照，请先扫描"; exit 6; }

epoch=$(state_value epoch)
snapshot_id=$(state_value snapshot_id)
expected_targets_sha=$(state_value targets_sha)
expected_whitelist_sha=$(state_value whitelist_sha)
expected_rules_sha=$(state_value rules_sha)
allow_high=$(state_value allow_high_risk)
max_file_bytes=$(state_value max_file_bytes)
authorized_files=$(state_value files)
authorized_bytes=$(state_value bytes)
case "$epoch" in ''|*[!0-9]*) epoch=0 ;; esac
case "$max_file_bytes" in ''|*[!0-9]*) max_file_bytes=$((256 * 1024 * 1024)) ;; esac
case "$authorized_files" in ''|*[!0-9]*) authorized_files=0 ;; esac
case "$authorized_bytes" in ''|*[!0-9]*) authorized_bytes=0 ;; esac
[ "$allow_high" = "1" ] || allow_high=0

now=$(date +%s)
age=$((now - epoch))
if [ "$epoch" -le 0 ] || [ "$age" -lt 0 ] || [ "$age" -gt 1800 ] || [ -z "$snapshot_id" ]; then
  echo "${TITLE}扫描快照已过期，不会自动重新扫描"
  exit 6
fi
[ "$(file_sha "$TARGETS_FILE")" = "$expected_targets_sha" ] || { echo "${TITLE}目标快照校验失败，不会自动重新扫描"; exit 7; }
[ "$(file_sha "$WHITELIST")" = "$expected_whitelist_sha" ] || { echo "白名单已变化，请重新扫描"; exit 7; }
if [ "$MODE" = "deep-clean" ]; then
  [ -f "$DEEP_RULES" ] || { echo "深度规则库缺失"; exit 7; }
  [ "$(file_sha "$DEEP_RULES")" = "$expected_rules_sha" ] || { echo "深度规则库已变化，请重新扫描"; exit 7; }
fi

total=$(wc -l <"$TARGETS_FILE" 2>/dev/null | tr -d ' ')
case "$total" in ''|*[!0-9]*) total=0 ;; esac
[ "$total" -gt 0 ] || { echo "${TITLE}扫描快照为空，请重新扫描"; exit 6; }

printf 'action\trisk\tcategory\titems\tbytes\tpath\n' >"$REPORT_FILE"
set_phase "正在校验${TITLE}扫描快照" 0 "$total" ""
current=0
deleted_files=0
deleted_dirs=0
deleted_bytes=0
cleaned_targets=0
errors=0
skipped=0
code=0
TAB=$(printf '\t')

while IFS= read -r line || [ -n "$line" ]; do
  [ -n "$line" ] || continue
  current=$((current + 1))
  if should_stop; then code=9; break; fi

  risk=high
  target=$line
  if [ "$MODE" = "deep-clean" ]; then
    case "$line" in
      *"$TAB"*) target=${line%%"$TAB"*}; risk=${line#*"$TAB"} ;;
      *) target=$line; risk=high ;;
    esac
    case "$risk" in
      low|medium) ;;
      high) [ "$allow_high" = "1" ] || { skipped=$((skipped + 1)); printf 'protected\thigh\t%s\t1\t0\t%s\n' "$CATEGORY" "$target" >>"$REPORT_FILE"; continue; } ;;
      *) skipped=$((skipped + 1)); printf 'protected\tcritical\t%s\t1\t0\t%s\n' "$CATEGORY" "$target" >>"$REPORT_FILE"; continue ;;
    esac
    deep_path_allowed "$target" || { skipped=$((skipped + 1)); printf 'protected\t%s\t路径保护\t1\t0\t%s\n' "$risk" "$target" >>"$REPORT_FILE"; continue; }
  else
    info=$(corpse_target_info "$target" 2>/dev/null)
    [ -n "$info" ] || { skipped=$((skipped + 1)); printf 'protected\thigh\t路径保护\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"; continue; }
    user=$(printf '%s' "$info" | cut -f1)
    package=$(printf '%s' "$info" | cut -f3)
    package_installed "$user" "$package"
    installed_state=$?
    if [ "$installed_state" -eq 0 ]; then
      skipped=$((skipped + 1))
      printf 'protected\thigh\t应用已重新安装\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"
      continue
    fi
    if [ "$installed_state" -eq 2 ]; then
      skipped=$((skipped + 1)); errors=$((errors + 1))
      printf 'protected\thigh\t无法复核安装状态\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"
      continue
    fi
    risk=high
  fi

  if path_conflicts_whitelist "$target"; then
    skipped=$((skipped + 1))
    printf 'protected\t%s\t白名单保护\t1\t0\t%s\n' "$risk" "$target" >>"$REPORT_FILE"
    continue
  fi
  if [ ! -e "$target" ] || [ -L "$target" ]; then
    skipped=$((skipped + 1))
    printf 'protected\t%s\t目标已变化\t1\t0\t%s\n' "$risk" "$target" >>"$REPORT_FILE"
    continue
  fi

  set_phase "正在清理刚才扫描到的${TITLE}" "$current" "$total" "$target"
  target_files=0
  target_dirs=0
  target_bytes=0

  if [ -f "$target" ]; then
    if [ "$target" -nt "$STATE_FILE" ]; then
      skipped=$((skipped + 1)); printf 'protected\t%s\t扫描后已修改\t1\t0\t%s\n' "$risk" "$target" >>"$REPORT_FILE"; continue
    fi
    size=$(file_size "$target")
    if [ "$size" -gt "$max_file_bytes" ]; then
      skipped=$((skipped + 1)); printf 'protected\t%s\t大文件保护\t1\t%s\t%s\n' "$risk" "$size" "$target" >>"$REPORT_FILE"; continue
    fi
    rm -f -- "$target" 2>/dev/null
    if [ ! -e "$target" ]; then
      target_files=1; target_bytes=$size
    else
      errors=$((errors + 1))
    fi
  elif [ -d "$target" ]; then
    list="$TMP_DIR/$MODE.$current.files0"
    remaining="$TMP_DIR/$MODE.$current.remaining0"
    : >"$list"
    find_snapshot_files "$target" "$list" "$max_file_bytes"
    find_code=$?
    if [ "$find_code" -eq 9 ]; then code=9; break; fi
    count=$(count_nul "$list"); case "$count" in ''|*[!0-9]*) count=0 ;; esac
    if [ "$count" -gt 0 ]; then
      estimated=$(bytes_from_list "$list"); case "$estimated" in ''|*[!0-9]*) estimated=0 ;; esac
      delete_file_list "$list"
      delete_code=$?
      if [ "$delete_code" -eq 9 ]; then code=9; break; fi
      existing_files_to_list "$list" "$remaining"
      remain=$(count_nul "$remaining"); case "$remain" in ''|*[!0-9]*) remain=0 ;; esac
      remaining_bytes=$(bytes_from_list "$remaining"); case "$remaining_bytes" in ''|*[!0-9]*) remaining_bytes=0 ;; esac
      target_files=$((count - remain)); [ "$target_files" -lt 0 ] && target_files=0
      target_bytes=$((estimated - remaining_bytes)); [ "$target_bytes" -lt 0 ] && target_bytes=0
      errors=$((errors + remain))
    fi
    before_dirs=$(find "$target" -xdev -mindepth 1 -type d -empty ! -newer "$STATE_FILE" 2>/dev/null | wc -l | tr -d ' ')
    case "$before_dirs" in ''|*[!0-9]*) before_dirs=0 ;; esac
    find "$target" -xdev -depth -mindepth 1 -type d -empty ! -newer "$STATE_FILE" -delete 2>/dev/null
    after_dirs=$(find "$target" -xdev -mindepth 1 -type d -empty ! -newer "$STATE_FILE" 2>/dev/null | wc -l | tr -d ' ')
    case "$after_dirs" in ''|*[!0-9]*) after_dirs=0 ;; esac
    target_dirs=$((before_dirs - after_dirs)); [ "$target_dirs" -lt 0 ] && target_dirs=0
    if [ -d "$target" ] && [ ! "$target" -nt "$STATE_FILE" ]; then
      rmdir "$target" 2>/dev/null && target_dirs=$((target_dirs + 1))
    fi
    rm -f "$list" "$remaining"
  else
    skipped=$((skipped + 1))
    printf 'protected\t%s\t不支持的文件类型\t1\t0\t%s\n' "$risk" "$target" >>"$REPORT_FILE"
    continue
  fi

  if [ "$target_files" -gt 0 ] || [ "$target_dirs" -gt 0 ]; then
    cleaned_targets=$((cleaned_targets + 1))
    deleted_files=$((deleted_files + target_files))
    deleted_dirs=$((deleted_dirs + target_dirs))
    deleted_bytes=$((deleted_bytes + target_bytes))
    printf 'cleaned\t%s\t%s\t%s\t%s\t%s\n' "$risk" "$CATEGORY" "$target_files" "$target_bytes" "$target" >>"$REPORT_FILE"
  else
    skipped=$((skipped + 1))
    printf 'protected\t%s\t没有仍符合快照的内容\t1\t0\t%s\n' "$risk" "$target" >>"$REPORT_FILE"
  fi
done <"$TARGETS_FILE"

end=$(date +%s)
elapsed=$((end - START_EPOCH))
if [ "$code" -eq 9 ]; then
  result="${TITLE}快照清理已停止，已释放 $(human_bytes "$deleted_bytes")"
else
  result="${TITLE}快照清理完成，已释放 $(human_bytes "$deleted_bytes")"
  rm -f "$STATE_FILE" "$TARGETS_FILE"
fi

write_latest "$deleted_files" "$deleted_dirs" "$deleted_bytes" "$errors" "$skipped" "$elapsed" "$result"
cp -f "$REPORT_FILE" "$REPORT_DIR/latest.tsv"
{
  echo "----------------------------------------"
  echo "$result"
  echo "扫描快照: $snapshot_id"
  echo "授权内容: $authorized_files 项 / $(human_bytes "$authorized_bytes")"
  echo "实际清理: $cleaned_targets 个目标 / $deleted_files 个文件 / $deleted_dirs 个目录 / $(human_bytes "$deleted_bytes")"
  echo "跳过: $skipped | 失败: $errors | 耗时: ${elapsed}s"
} >>"$LOG_FILE"
cp -f "$LOG_FILE" "$LOG_DIR/latest.log"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$(date '+%Y-%m-%d %H:%M:%S')" "$MODE" "$deleted_bytes" "$deleted_files" "$deleted_dirs" "$errors" \
  "$result" "$TRIGGER" "$TITLE|$deleted_bytes|$deleted_files" "$snapshot_id" >>"$HISTORY_FILE"
tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"

echo "$result"
echo "扫描快照: $snapshot_id | 清理目标: $cleaned_targets | 文件: $deleted_files | 目录: $deleted_dirs | 跳过: $skipped | 失败: $errors"
[ "$code" -eq 9 ] && exit 9
exit 0
