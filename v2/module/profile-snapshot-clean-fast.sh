#!/system/bin/sh

set -u

MODDIR=${0%/*}
MODE=${1:-deep-clean}
TRIGGER=${2:-manual}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
DATA_ROOT=${BAIZE_DATA_ROOT:-/data}
CONFIG="$STATE_DIR/config.conf"
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

mkdir -p "$STATE_DIR" "$REPORT_DIR" "$LOG_DIR"
[ -f "$CONFIG" ] || { [ -f "$MODDIR/config/default.conf" ] && cp -f "$MODDIR/config/default.conf" "$CONFIG" || : >"$CONFIG"; }
[ -f "$WHITELIST" ] || : >"$WHITELIST"

get_config_uint() {
  key=$1 fallback=$2 min=$3 max=$4
  value=$(sed -n "s/^$key=//p" "$CONFIG" 2>/dev/null | tail -n 1)
  case "$value" in ''|*[!0-9]*) value=$fallback ;; esac
  [ "$value" -lt "$min" ] && value=$min
  [ "$value" -gt "$max" ] && value=$max
  echo "$value"
}

TARGET_LIMIT_SECONDS=$(get_config_uint deep_clean_target_timeout_seconds 30 5 300)
STAGE_LIMIT_SECONDS=$(get_config_uint deep_clean_stage_limit_seconds 180 30 900)

pid_is_baize_task() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in
    *baize_v2*cleaner.sh*|*baize-v2*cleaner.sh*|*native-scan.sh*|*profile-snapshot-clean*|*cache-snapshot-clean.sh*|*baize_engine*) return 0 ;;
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
DEADLINE_EPOCH=$((START_EPOCH + STAGE_LIMIT_SECONDS))
STAMP=$(date '+%Y-%m-%d_%H-%M-%S')
REPORT_FILE="$REPORT_DIR/$STAMP-$MODE.tsv"
LOG_FILE="$LOG_DIR/$STAMP-$MODE.log"

state_value() { sed -n "s/^$1=//p" "$STATE_FILE" 2>/dev/null | tail -n 1; }
file_sha() { [ -f "$1" ] && sha256sum "$1" 2>/dev/null | awk 'NR==1{print $1}' || echo missing; }
human_bytes() { awk -v b="$1" 'BEGIN { if (b>=1073741824) printf "%.2f GB",b/1073741824; else if(b>=1048576) printf "%.2f MB",b/1048576; else if(b>=1024) printf "%.2f KB",b/1024; else printf "%.0f B",b }'; }
count_nul() { [ -s "$1" ] && tr -cd '\000' <"$1" | wc -c | tr -d ' ' || echo 0; }
should_stop() { [ -f "$STOP_FILE" ]; }

remaining_seconds() {
  now=$(date +%s)
  remaining=$((DEADLINE_EPOCH - now))
  [ "$remaining" -gt 0 ] || { echo 0; return; }
  [ "$remaining" -lt "$TARGET_LIMIT_SECONDS" ] && echo "$remaining" || echo "$TARGET_LIMIT_SECONDS"
}

set_phase() {
  phase=$1 current=${2:-0} total=${3:-0} path=${4:-}
  tmp="$RUNNING_FILE.tmp.$$"
  {
    echo "mode=$MODE"
    echo "phase=$phase"
    echo "started=$START_EPOCH"
    echo "progress_current=$current"
    echo "progress_total=$total"
    printf 'current_path=%s\n' "$path" | tr '\r\n' '  '
    echo "engine=profile-snapshot-v42.7-budgeted"
  } >"$tmp"
  mv -f "$tmp" "$RUNNING_FILE"
}

wait_with_budget() {
  child=$1 limit=$2
  started=$(date +%s)
  while kill -0 "$child" 2>/dev/null; do
    if should_stop; then
      kill "$child" 2>/dev/null
      wait "$child" 2>/dev/null
      return 9
    fi
    now=$(date +%s)
    if [ "$limit" -le 0 ] || [ $((now - started)) -ge "$limit" ] || [ "$now" -ge "$DEADLINE_EPOCH" ]; then
      kill "$child" 2>/dev/null
      sleep 1
      kill -9 "$child" 2>/dev/null || true
      wait "$child" 2>/dev/null
      return 124
    fi
    sleep 1
  done
  wait "$child"
}

path_relation() {
  parent=${1%/} child=${2%/}
  [ "$parent" = "$child" ] && return 0
  case "$child" in "$parent"/*) return 0 ;; esac
  return 1
}

path_conflicts_whitelist() {
  target=${1%/}
  while IFS= read -r raw || [ -n "$raw" ]; do
    item=$(printf '%s' "$raw" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    case "$item" in ''|'#'*) continue ;; /*) ;; *) continue ;; esac
    item=${item%/}; [ -n "$item" ] || item=/
    path_relation "$item" "$target" && return 0
    path_relation "$target" "$item" && return 0
  done <"$WHITELIST"
  return 1
}

deep_path_allowed() {
  path=${1%/}
  [ -n "$path" ] && [ "$path" != "/" ] || return 1
  case "$path" in
    "$DATA_ROOT/adb"|"$DATA_ROOT/adb"/*|"$DATA_ROOT/app"|"$DATA_ROOT/app"/*|"$DATA_ROOT/system"|"$DATA_ROOT/system"/*|"$DATA_ROOT/misc"|"$DATA_ROOT/misc"/*|"$DATA_ROOT/dalvik-cache"|"$DATA_ROOT/dalvik-cache"/*|/system|/system/*|/vendor|/vendor/*|/product|/product/*|/apex|/apex/*) return 1 ;;
  esac
  case "$path" in
    "$MEDIA_ROOT"/*|"$DATA_ROOT/data"/*|"$DATA_ROOT/user"/*|"$DATA_ROOT/user_de"/*|"$DATA_ROOT/cache"/*|"$DATA_ROOT/media"/*|/data_mirror/data_ce/*) return 0 ;;
  esac
  return 1
}

corpse_target_info() {
  target=${1%/}
  case "$target" in "$MEDIA_ROOT"/*/Android/data/*|"$MEDIA_ROOT"/*/Android/obb/*|"$MEDIA_ROOT"/*/Android/media/*) ;; *) return 1 ;; esac
  rest=${target#"$MEDIA_ROOT"/}; user=${rest%%/*}; rest=${rest#*/Android/}; bucket=${rest%%/*}; package=${rest#*/}
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
    output=$(cmd package list packages --user "$user" "$package" 2>/dev/null); [ $? -eq 0 ] || return 2
    printf '%s\n' "$output" | sed 's/^package://' | grep -Fqx -- "$package"; return $?
  fi
  if command -v pm >/dev/null 2>&1; then
    output=$(pm list packages --user "$user" "$package" 2>/dev/null); [ $? -eq 0 ] || return 2
    printf '%s\n' "$output" | sed 's/^package://' | grep -Fqx -- "$package"; return $?
  fi
  return 2
}

file_size() {
  value=$(stat -c %s "$1" 2>/dev/null)
  case "$value" in ''|*[!0-9]*) value=$(wc -c <"$1" 2>/dev/null | tr -d ' ') ;; esac
  case "$value" in ''|*[!0-9]*) value=0 ;; esac
  echo "$value"
}

find_snapshot_files() {
  target=$1 output=$2 max_bytes=$3 limit=$4
  find "$target" -xdev -mindepth 1 -type f ! -size "+${max_bytes}c" ! -newer "$STATE_FILE" -print0 >"$output" 2>/dev/null &
  wait_with_budget "$!" "$limit"
}

measure_list_bytes() {
  input=$1 output=$2 limit=$3
  : >"$output"
  [ -s "$input" ] || { echo 0 >"$output"; return 0; }
  (xargs -0 du -k <"$input" 2>/dev/null | awk '{sum += $1} END {printf "%.0f\n", sum * 1024}') >"$output" &
  wait_with_budget "$!" "$limit"
}

delete_file_list() {
  input=$1 limit=$2
  [ -s "$input" ] || return 0
  xargs -0 -n 256 rm -f -- <"$input" 2>/dev/null &
  wait_with_budget "$!" "$limit"
}

existing_files_to_list() {
  source_list=$1 target_list=$2 limit=$3
  : >"$target_list"
  (
    while IFS= read -r -d '' candidate; do
      [ -f "$candidate" ] && [ ! -L "$candidate" ] && printf '%s\0' "$candidate" >>"$target_list"
    done <"$source_list"
  ) &
  wait_with_budget "$!" "$limit"
}

snapshot_empty_dirs() {
  target=$1 output=$2 limit=$3
  : >"$output"
  find "$target" -xdev -depth -mindepth 1 -type d -empty ! -newer "$STATE_FILE" -print0 >"$output" 2>/dev/null &
  wait_with_budget "$!" "$limit"
}

delete_dir_list() {
  input=$1 output=$2 limit=$3
  : >"$output"
  (
    while IFS= read -r -d '' directory; do
      rmdir -- "$directory" 2>/dev/null && printf '%s\0' "$directory" >>"$output"
    done <"$input"
  ) &
  wait_with_budget "$!" "$limit"
}

write_latest() {
  files=$1 dirs=$2 bytes=$3 errors=$4 skipped=$5 elapsed=$6 result=$7 timed_out=$8 truncated=$9
  tmp="$STATE_DIR/latest.env.tmp.$$"
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
    echo "deep_slow_items=$timed_out"
    echo "deep_mount_items=0"
    echo "deep_truncated=$truncated"
    echo "deep_clean_target_timeout_seconds=$TARGET_LIMIT_SECONDS"
    echo "deep_clean_stage_limit_seconds=$STAGE_LIMIT_SECONDS"
    echo "elapsed=$elapsed"
    echo "engine=profile-snapshot-v42.7-budgeted"
    echo "result=$result"
  } >"$tmp"
  mv -f "$tmp" "$STATE_DIR/latest.env"
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

now=$(date +%s); age=$((now - epoch))
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
current=0 deleted_files=0 deleted_dirs=0 deleted_bytes=0 cleaned_targets=0 errors=0 skipped=0 timed_out_targets=0 stage_truncated=0 code=0
TAB=$(printf '\t')

while IFS= read -r line || [ -n "$line" ]; do
  [ -n "$line" ] || continue
  current=$((current + 1))
  should_stop && { code=9; break; }
  budget=$(remaining_seconds)
  if [ "$budget" -le 0 ]; then stage_truncated=1; break; fi

  risk=high target=$line
  if [ "$MODE" = "deep-clean" ]; then
    case "$line" in *"$TAB"*) target=${line%%"$TAB"*}; risk=${line#*"$TAB"} ;; esac
    case "$risk" in
      low|medium) ;;
      high) [ "$allow_high" = "1" ] || { skipped=$((skipped + 1)); printf 'protected\thigh\t%s\t1\t0\t%s\n' "$CATEGORY" "$target" >>"$REPORT_FILE"; continue; } ;;
      *) skipped=$((skipped + 1)); printf 'protected\tcritical\t%s\t1\t0\t%s\n' "$CATEGORY" "$target" >>"$REPORT_FILE"; continue ;;
    esac
    deep_path_allowed "$target" || { skipped=$((skipped + 1)); printf 'protected\t%s\t路径保护\t1\t0\t%s\n' "$risk" "$target" >>"$REPORT_FILE"; continue; }
  else
    info=$(corpse_target_info "$target" 2>/dev/null)
    [ -n "$info" ] || { skipped=$((skipped + 1)); printf 'protected\thigh\t路径保护\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"; continue; }
    user=$(printf '%s' "$info" | cut -f1); package=$(printf '%s' "$info" | cut -f3)
    package_installed "$user" "$package"; installed_state=$?
    [ "$installed_state" -eq 0 ] && { skipped=$((skipped + 1)); printf 'protected\thigh\t应用已重新安装\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"; continue; }
    [ "$installed_state" -eq 2 ] && { skipped=$((skipped + 1)); errors=$((errors + 1)); printf 'protected\thigh\t无法复核安装状态\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"; continue; }
  fi

  path_conflicts_whitelist "$target" && { skipped=$((skipped + 1)); printf 'protected\t%s\t白名单保护\t1\t0\t%s\n' "$risk" "$target" >>"$REPORT_FILE"; continue; }
  if [ ! -e "$target" ] || [ -L "$target" ]; then skipped=$((skipped + 1)); printf 'protected\t%s\t目标已变化\t1\t0\t%s\n' "$risk" "$target" >>"$REPORT_FILE"; continue; fi

  target_files=0 target_dirs=0 target_bytes=0 target_partial=0
  if [ -f "$target" ]; then
    if [ "$target" -nt "$STATE_FILE" ]; then skipped=$((skipped + 1)); printf 'protected\t%s\t扫描后已修改\t1\t0\t%s\n' "$risk" "$target" >>"$REPORT_FILE"; continue; fi
    size=$(file_size "$target")
    if [ "$size" -gt "$max_file_bytes" ]; then skipped=$((skipped + 1)); printf 'protected\t%s\t大文件保护\t1\t%s\t%s\n' "$risk" "$size" "$target" >>"$REPORT_FILE"; continue; fi
    set_phase "正在删除扫描快照文件" "$current" "$total" "$target"
    rm -f -- "$target" 2>/dev/null
    if [ ! -e "$target" ]; then target_files=1; target_bytes=$size; else errors=$((errors + 1)); fi
  elif [ -d "$target" ]; then
    list="$TMP_DIR/$MODE.$current.files0"; remaining="$TMP_DIR/$MODE.$current.remaining0"; size_file="$TMP_DIR/$MODE.$current.bytes"; remaining_size_file="$TMP_DIR/$MODE.$current.remaining.bytes"
    dirs_list="$TMP_DIR/$MODE.$current.dirs0"; deleted_dirs_list="$TMP_DIR/$MODE.$current.deleted-dirs0"
    : >"$list"
    set_phase "正在一次枚举${TITLE}目标" "$current" "$total" "$target"
    budget=$(remaining_seconds); [ "$budget" -gt 0 ] || { stage_truncated=1; break; }
    find_snapshot_files "$target" "$list" "$max_file_bytes" "$budget"; find_code=$?
    if [ "$find_code" -eq 9 ]; then code=9; break; fi
    if [ "$find_code" -eq 124 ]; then
      timed_out_targets=$((timed_out_targets + 1)); skipped=$((skipped + 1)); rm -f "$list"
      printf 'protected\tslow\t目录超时保护\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"
      continue
    fi

    count=$(count_nul "$list"); case "$count" in ''|*[!0-9]*) count=0 ;; esac
    if [ "$count" -gt 0 ]; then
      budget=$(remaining_seconds); [ "$budget" -gt 0 ] || { stage_truncated=1; break; }
      measure_list_bytes "$list" "$size_file" "$budget"; measure_code=$?
      if [ "$measure_code" -eq 9 ]; then code=9; break; fi
      if [ "$measure_code" -eq 124 ]; then timed_out_targets=$((timed_out_targets + 1)); skipped=$((skipped + 1)); printf 'protected\tslow\t容量统计超时\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"; continue; fi
      estimated=$(sed -n '1p' "$size_file" 2>/dev/null); case "$estimated" in ''|*[!0-9]*) estimated=0 ;; esac
      set_phase "正在批量删除扫描快照文件" "$current" "$total" "$target"
      budget=$(remaining_seconds); [ "$budget" -gt 0 ] || { stage_truncated=1; break; }
      delete_file_list "$list" "$budget"; delete_code=$?
      [ "$delete_code" -eq 9 ] && { code=9; break; }
      [ "$delete_code" -eq 124 ] && { target_partial=1; timed_out_targets=$((timed_out_targets + 1)); }

      budget=$(remaining_seconds)
      if [ "$budget" -gt 0 ]; then
        existing_files_to_list "$list" "$remaining" "$budget"; existing_code=$?
        [ "$existing_code" -eq 9 ] && { code=9; break; }
        if [ "$existing_code" -eq 124 ]; then
          target_partial=1; remain=$count; remaining_bytes=$estimated
        else
          remain=$(count_nul "$remaining"); case "$remain" in ''|*[!0-9]*) remain=0 ;; esac
          budget=$(remaining_seconds)
          if [ "$remain" -gt 0 ] && [ "$budget" -gt 0 ]; then
            measure_list_bytes "$remaining" "$remaining_size_file" "$budget" || true
            remaining_bytes=$(sed -n '1p' "$remaining_size_file" 2>/dev/null)
          else
            remaining_bytes=0
          fi
          case "$remaining_bytes" in ''|*[!0-9]*) remaining_bytes=0 ;; esac
        fi
      else
        stage_truncated=1; target_partial=1; remain=$count; remaining_bytes=$estimated
      fi
      target_files=$((count - remain)); [ "$target_files" -lt 0 ] && target_files=0
      target_bytes=$((estimated - remaining_bytes)); [ "$target_bytes" -lt 0 ] && target_bytes=0
      errors=$((errors + remain))
    fi

    if [ "$stage_truncated" -eq 0 ]; then
      set_phase "正在一次收尾空目录" "$current" "$total" "$target"
      budget=$(remaining_seconds)
      if [ "$budget" -gt 0 ]; then
        snapshot_empty_dirs "$target" "$dirs_list" "$budget"; dirs_code=$?
        [ "$dirs_code" -eq 9 ] && { code=9; break; }
        if [ "$dirs_code" -eq 124 ]; then
          target_partial=1; timed_out_targets=$((timed_out_targets + 1))
        else
          budget=$(remaining_seconds)
          if [ "$budget" -gt 0 ]; then
            delete_dir_list "$dirs_list" "$deleted_dirs_list" "$budget"; prune_code=$?
            [ "$prune_code" -eq 9 ] && { code=9; break; }
            [ "$prune_code" -eq 124 ] && { target_partial=1; timed_out_targets=$((timed_out_targets + 1)); }
            target_dirs=$(count_nul "$deleted_dirs_list"); case "$target_dirs" in ''|*[!0-9]*) target_dirs=0 ;; esac
          else
            stage_truncated=1; target_partial=1
          fi
        fi
      else
        stage_truncated=1; target_partial=1
      fi
    fi
    if [ -d "$target" ] && [ ! "$target" -nt "$STATE_FILE" ]; then rmdir -- "$target" 2>/dev/null && target_dirs=$((target_dirs + 1)); fi
    rm -f "$list" "$remaining" "$size_file" "$remaining_size_file" "$dirs_list" "$deleted_dirs_list"
  else
    skipped=$((skipped + 1)); printf 'protected\t%s\t不支持的文件类型\t1\t0\t%s\n' "$risk" "$target" >>"$REPORT_FILE"; continue
  fi

  if [ "$target_files" -gt 0 ] || [ "$target_dirs" -gt 0 ]; then
    cleaned_targets=$((cleaned_targets + 1)); deleted_files=$((deleted_files + target_files)); deleted_dirs=$((deleted_dirs + target_dirs)); deleted_bytes=$((deleted_bytes + target_bytes))
    action=cleaned; [ "$target_partial" -eq 1 ] && action=partial
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$action" "$risk" "$CATEGORY" "$target_files" "$target_bytes" "$target" >>"$REPORT_FILE"
  else
    skipped=$((skipped + 1)); printf 'protected\t%s\t没有仍符合快照的内容\t1\t0\t%s\n' "$risk" "$target" >>"$REPORT_FILE"
  fi
  [ "$stage_truncated" -eq 1 ] && break
done <"$TARGETS_FILE"

end=$(date +%s); elapsed=$((end - START_EPOCH))
if [ "$code" -eq 9 ]; then
  result="${TITLE}快照清理已停止，已释放 $(human_bytes "$deleted_bytes")"
elif [ "$stage_truncated" -eq 1 ]; then
  result="${TITLE}清理达到 ${STAGE_LIMIT_SECONDS} 秒上限，已安全结束并释放 $(human_bytes "$deleted_bytes")"
elif [ "$timed_out_targets" -gt 0 ]; then
  result="${TITLE}清理完成，跳过 $timed_out_targets 个慢目标，已释放 $(human_bytes "$deleted_bytes")"
else
  result="${TITLE}快照清理完成，已释放 $(human_bytes "$deleted_bytes")"
fi

[ "$code" -eq 9 ] || rm -f "$STATE_FILE" "$TARGETS_FILE"
write_latest "$deleted_files" "$deleted_dirs" "$deleted_bytes" "$errors" "$skipped" "$elapsed" "$result" "$timed_out_targets" "$stage_truncated"
cp -f "$REPORT_FILE" "$REPORT_DIR/latest.tsv"
{
  echo "----------------------------------------"
  echo "$result"
  echo "扫描快照: $snapshot_id"
  echo "授权内容: $authorized_files 项 / $(human_bytes "$authorized_bytes")"
  echo "实际清理: $cleaned_targets 个目标 / $deleted_files 个文件 / $deleted_dirs 个目录 / $(human_bytes "$deleted_bytes")"
  echo "慢目标: $timed_out_targets | 阶段截断: $stage_truncated | 跳过: $skipped | 失败: $errors | 耗时: ${elapsed}s"
} >>"$LOG_FILE"
cp -f "$LOG_FILE" "$LOG_DIR/latest.log"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$(date '+%Y-%m-%d %H:%M:%S')" "$MODE" "$deleted_bytes" "$deleted_files" "$deleted_dirs" "$errors" \
  "$result" "$TRIGGER" "$TITLE|$deleted_bytes|$deleted_files" "$snapshot_id" >>"$HISTORY_FILE"
tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"

echo "$result"
echo "扫描快照: $snapshot_id | 清理目标: $cleaned_targets | 文件: $deleted_files | 目录: $deleted_dirs | 慢目标: $timed_out_targets | 跳过: $skipped | 失败: $errors"
[ "$code" -eq 9 ] && exit 9
exit 0
