#!/system/bin/sh
MODDIR=${0%/*}
STATE_DIR=/data/adb/safesweep
CONFIG="$STATE_DIR/config.conf"
WHITELIST="$STATE_DIR/whitelist.conf"
CUSTOM_RULES="$STATE_DIR/custom.rules"
APP_RULES="$MODDIR/config/app.rules"
EXTERNAL_RULES="$MODDIR/config/external.rules"
DEEP_RULES="$MODDIR/config/deep.rules"
HIDDEN_RULES="$MODDIR/config/hidden.rules"
LOG_DIR="$STATE_DIR/logs"
LOCK_DIR="$STATE_DIR/run.lock"
RUNNING_FILE="$STATE_DIR/running.env"
TOTALS_FILE="$STATE_DIR/totals.env"
REPORT_DIR="$STATE_DIR/reports"
LATEST_REPORT="$REPORT_DIR/latest.tsv"
HISTORY_FILE="$STATE_DIR/history.tsv"
DEEP_SCAN_STATE="$STATE_DIR/deep_scan.env"
DEEP_SCAN_TARGETS="$STATE_DIR/deep_scan.targets"
CORPSE_SCAN_STATE="$STATE_DIR/corpse_scan.env"
CORPSE_SCAN_TARGETS="$STATE_DIR/corpse_scan.targets"

REQUEST_MODE=${1:-scan}
DEEP_MODE=0
PROFILE=all
case "$REQUEST_MODE" in
  cache-clean) MODE=clean; PROFILE=cache ;;
  empty-clean) MODE=clean; PROFILE=empty ;;
  rules-clean) MODE=clean; PROFILE=rules ;;
  fragment-scan) MODE=scan; PROFILE=fragment ;;
  fragment-clean) MODE=clean; PROFILE=fragment ;;
  deep-scan) MODE=scan; DEEP_MODE=1; PROFILE=deep ;;
  deep-clean) MODE=clean; DEEP_MODE=1; PROFILE=deep ;;
  corpse-scan) MODE=scan; PROFILE=corpse ;;
  corpse-clean) MODE=clean; PROFILE=corpse ;;
  scan|clean) MODE=$REQUEST_MODE ;;
  *) echo "用法: cleaner.sh scan|clean|cache-clean|empty-clean|rules-clean|fragment-scan|fragment-clean|deep-scan|deep-clean|corpse-scan|corpse-clean [trigger]"; exit 2 ;;
esac
TRIGGER=${2:-manual}

mkdir -p "$LOG_DIR" "$REPORT_DIR"
[ -f "$CONFIG" ] || cp -f "$MODDIR/config/default.conf" "$CONFIG"
[ -f "$WHITELIST" ] || cp -f "$MODDIR/config/whitelist.conf" "$WHITELIST"
[ -f "$CUSTOM_RULES" ] || cp -f "$MODDIR/config/custom.rules" "$CUSTOM_RULES"

pid_is_safesweep() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in
    *safesweep*cleaner.sh*|*safesweep*job-runner.sh*|*safesweep*webctl.sh*) return 0 ;;
  esac
  return 1
}

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  old_pid=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
  case "$old_pid" in
    ''|*[!0-9]*) old_pid=0 ;;
  esac
  if [ "$old_pid" -gt 1 ] && kill -0 "$old_pid" 2>/dev/null && pid_is_safesweep "$old_pid"; then
    echo "已有扫描或清理任务正在运行"
    exit 3
  fi
  find "$LOCK_DIR" -type f -exec rm -f {} \; 2>/dev/null
  rmdir "$LOCK_DIR/tmp" "$LOCK_DIR" 2>/dev/null
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    echo "无法恢复任务锁，请重启手机后重试"
    exit 4
  fi
fi
printf '%s\n' "$$" >"$LOCK_DIR/pid"

TMP_DIR="$LOCK_DIR/tmp"
mkdir -p "$TMP_DIR"
cleanup_lock() {
  if [ -d "$RUNNING_FILE" ]; then
    rm -rf -- "$RUNNING_FILE" 2>/dev/null
  else
    rm -f "$RUNNING_FILE"
  fi
  find "$LOCK_DIR" -type f -exec rm -f {} \; 2>/dev/null
  rmdir "$TMP_DIR" "$LOCK_DIR" 2>/dev/null
}
handle_signal() {
  trap - EXIT INT TERM
  cleanup_lock
  exit 9
}
trap cleanup_lock EXIT
trap handle_signal INT TERM

# 仅用于状态展示的固定路径；旧版本或异常中断若留下同名目录，先安全清理。
[ -d "$RUNNING_FILE" ] && rm -rf -- "$RUNNING_FILE" 2>/dev/null

set_phase() {
  phase=$1
  tmp="$RUNNING_FILE.tmp.$$"
  {
    echo "mode=$REQUEST_MODE"
    echo "phase=$phase"
    echo "started=$START_EPOCH"
  } >"$tmp"
  mv -f "$tmp" "$RUNNING_FILE"
}

START_EPOCH=$(date +%s)
STAMP=$(date '+%Y-%m-%d_%H-%M-%S')
LOG_FILE="$LOG_DIR/$STAMP-$MODE.log"
LATEST_LOG="$LOG_DIR/latest.log"
FILES=0
EMPTY_FILES=0
EMPTY_DIRS=0
HIDDEN_ITEMS=0
FRAGMENT_FILES=0
BYTES=0
SKIPPED=0
ERRORS=0
CATEGORY=""
HIDDEN_CONTEXT=0
LIST_SEQ=0
PROTECTED_ITEMS=0
PROTECTED_BYTES=0
RISK_LOW=0
RISK_MEDIUM=0
RISK_HIGH=0
RISK_CRITICAL=0
MAX_RUN_SECONDS=0
STOP_REASON=""
STOP_CHECK_TICKS=0
DEEP_RULE_SHA=""
DEEP_COVER_TARGET=0
DEEP_SCAN_MANIFEST_TMP="$TMP_DIR/deep-scan.targets"
CORPSE_SCAN_MANIFEST_TMP="$TMP_DIR/corpse-scan.targets"
SHARED_MANIFEST_READY=0
REPORT_FILE="$REPORT_DIR/$STAMP-$REQUEST_MODE.tsv"
printf 'action\trisk\tcategory\titems\tbytes\tpath\n' >"$REPORT_FILE"
set_phase "准备扫描"

get_value() {
  sed -n "s/^$1=//p" "$CONFIG" 2>/dev/null | tail -n 1
}

get_bool() {
  value=$(get_value "$1")
  [ "$value" = "1" ] && echo 1 || echo 0
}

get_uint() {
  value=$(get_value "$1")
  fallback=$2
  min=$3
  max=$4
  case "$value" in ''|*[!0-9]*) value=$fallback ;; esac
  [ "$value" -lt "$min" ] && value=$min
  [ "$value" -gt "$max" ] && value=$max
  echo "$value"
}

log_line() {
  printf '%s\n' "$1" >>"$LOG_FILE"
}

sanitize_report_field() {
  printf '%s' "$1" | tr '\t\r\n' '   '
}

report_line() {
  action=$(sanitize_report_field "$1")
  risk=$(sanitize_report_field "$2")
  category=$(sanitize_report_field "$3")
  items=$4
  bytes=$5
  path=$(sanitize_report_field "$6")
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$action" "$risk" "$category" "$items" "$bytes" "$path" >>"$REPORT_FILE"
}

should_stop() {
  if [ -f "$STATE_DIR/stop" ]; then
    STOP_REASON="已收到停止请求"
    return 0
  fi
  if [ "${MAX_RUN_SECONDS:-0}" -gt 0 ]; then
    STOP_CHECK_TICKS=$((STOP_CHECK_TICKS + 1))
    # 避免在数千条规则或文件循环中每次都启动 date 进程；停止标记仍逐项检查。
    [ "$STOP_CHECK_TICKS" -eq 1 ] || [ $((STOP_CHECK_TICKS % 32)) -eq 0 ] || return 1
    now=$(date +%s)
    if [ $((now - START_EPOCH)) -ge "$MAX_RUN_SECONDS" ]; then
      STOP_REASON="已达到单次任务时长上限"
      return 0
    fi
  fi
  return 1
}

existing_files_to_list() {
  source_list=$1
  target_list=$2
  : >"$target_list"
  while IFS= read -r -d '' candidate; do
    [ -f "$candidate" ] && [ ! -L "$candidate" ] && printf '%s\0' "$candidate" >>"$target_list"
  done <"$source_list"
}

existing_paths_to_list() {
  source_list=$1
  target_list=$2
  : >"$target_list"
  while IFS= read -r -d '' candidate; do
    { [ -e "$candidate" ] || [ -L "$candidate" ]; } && printf '%s\0' "$candidate" >>"$target_list"
  done <"$source_list"
}

is_whitelisted() {
  target=$1
  [ "$WHITELIST_ACTIVE" = "1" ] || return 1
  old_ifs=$IFS
  IFS='
'
  for protected in $WHITELIST_PATHS; do
    [ "$protected" = "/" ] && { IFS=$old_ifs; return 0; }
    protected=${protected%/}
    case "$target" in
      "$protected"|"$protected"/*) IFS=$old_ifs; return 0 ;;
    esac
  done
  IFS=$old_ifs
  return 1
}

human_bytes() {
  value=$1
  awk -v b="$value" 'BEGIN {
    if (b >= 1073741824) printf "%.2f GB", b/1073741824;
    else if (b >= 1048576) printf "%.2f MB", b/1048576;
    else if (b >= 1024) printf "%.2f KB", b/1024;
    else printf "%.0f B", b;
  }'
}

update_module_description() {
  [ "$MODE" = "clean" ] || return 0
  prop="$MODDIR/module.prop"
  [ -f "$prop" ] || return 0
  total_space=$(human_bytes "$CUM_BYTES")
  summary="累计清理 $total_space | 文件:$CUM_FILES 空文件:$CUM_EMPTY_FILES 空目录:$CUM_EMPTY_DIRS 碎片:$CUM_FRAGMENTS | $CUM_RUNS 次 累计耗时:${CUM_ELAPSED}秒 | 上次:$CUM_LAST_TIME"
  tmp="$prop.tmp.$$"
  awk -v d="$summary" '
    BEGIN { found=0 }
    /^description=/ { print "description=" d; found=1; next }
    { print }
    END { if (!found) print "description=" d }
  ' "$prop" >"$tmp" 2>/dev/null || { rm -f "$tmp"; return 0; }
  chmod 0644 "$tmp"
  mv -f "$tmp" "$prop" 2>/dev/null || rm -f "$tmp"
}

total_value() {
  key=$1
  value=$(sed -n "s/^$key=//p" "$TOTALS_FILE" 2>/dev/null | tail -n 1)
  case "$value" in ''|*[!0-9]*) value=0 ;; esac
  echo "$value"
}

sum_uint() {
  awk -v a="$1" -v b="$2" 'BEGIN {printf "%.0f", a + b}'
}

load_cumulative_totals() {
  CUM_RUNS=$(total_value runs)
  CUM_FILES=$(total_value regular_files)
  CUM_EMPTY_FILES=$(total_value empty_files)
  CUM_EMPTY_DIRS=$(total_value empty_dirs)
  CUM_HIDDEN=$(total_value hidden_items)
  CUM_FRAGMENTS=$(total_value fragment_files)
  CUM_BYTES=$(total_value bytes)
  CUM_ELAPSED=$(total_value elapsed)
  CUM_LAST_TIME=$(sed -n 's/^last_time=//p' "$TOTALS_FILE" 2>/dev/null | tail -n 1)
  [ -n "$CUM_LAST_TIME" ] || CUM_LAST_TIME="从未清理"
}

update_cumulative_totals() {
  [ "$MODE" = "clean" ] && [ "$STOPPED" = "0" ] && [ "${FATAL_CODE:-0}" -eq 0 ] || { load_cumulative_totals; return 0; }
  CUM_RUNS=$(sum_uint "$(total_value runs)" 1)
  CUM_FILES=$(sum_uint "$(total_value regular_files)" "$FILES")
  CUM_EMPTY_FILES=$(sum_uint "$(total_value empty_files)" "$EMPTY_FILES")
  CUM_EMPTY_DIRS=$(sum_uint "$(total_value empty_dirs)" "$EMPTY_DIRS")
  CUM_HIDDEN=$(sum_uint "$(total_value hidden_items)" "$HIDDEN_ITEMS")
  CUM_FRAGMENTS=$(sum_uint "$(total_value fragment_files)" "$FRAGMENT_FILES")
  CUM_BYTES=$(sum_uint "$(total_value bytes)" "$BYTES")
  CUM_ELAPSED=$(sum_uint "$(total_value elapsed)" "$ELAPSED")
  CUM_LAST_TIME=$(date '+%m-%d %H:%M')
  tmp="$TOTALS_FILE.tmp.$$"
  {
    echo "runs=$CUM_RUNS"
    echo "regular_files=$CUM_FILES"
    echo "empty_files=$CUM_EMPTY_FILES"
    echo "empty_dirs=$CUM_EMPTY_DIRS"
    echo "hidden_items=$CUM_HIDDEN"
    echo "fragment_files=$CUM_FRAGMENTS"
    echo "bytes=$CUM_BYTES"
    echo "elapsed=$CUM_ELAPSED"
    echo "last_time=$CUM_LAST_TIME"
  } >"$tmp"
  chmod 0600 "$tmp"
  mv -f "$tmp" "$TOTALS_FILE"
}

send_completion_notification() {
  [ "$MODE" = "clean" ] || return 0
  [ "$(get_bool notify_on_complete)" = "1" ] || return 0
  if [ "${FATAL_CODE:-0}" -ne 0 ]; then
    title="白泽任务失败（代码 $FATAL_CODE）"
  elif [ "$STOPPED" = "1" ]; then
    if [ "$DEEP_MODE" = "1" ]; then title="白泽深度清理已停止"; else title="白泽清理已停止"; fi
  else
    [ "$BYTES" -gt 0 ] || [ "$(get_bool notify_zero_result)" = "1" ] || return 0
    case "$PROFILE" in
      cache) title="白泽缓存清理完成" ;;
      empty) title="白泽空文件清理完成" ;;
      rules) title="白泽规则清理完成" ;;
      fragment) title="白泽碎片清理完成" ;;
      deep) title="白泽深度清理完成" ;;
      corpse) title="白泽卸载残留清理完成" ;;
      *) title="白泽清理完成" ;;
    esac
  fi
  [ "$ERRORS" -gt 0 ] && title="$title（${ERRORS}项未清理）"
  short="$RESULT"
  total_space=$(human_bytes "$CUM_BYTES")
  body="$RESULT · 文件 $FILES · 碎片 $FRAGMENT_FILES · 空文件 $EMPTY_FILES · 空目录 $EMPTY_DIRS · 受保护 $PROTECTED_ITEMS · 未清理 $ERRORS · 耗时 ${ELAPSED}秒；累计清理 $total_space（$CUM_RUNS 次）"
  notify_result=$(sh "$MODDIR/notify.sh" "$title" "$body" "$short" "baize-$PROFILE" 2>&1)
  case "$notify_result" in
    ok:*) log_line "[通知已发送:${notify_result#ok:}] $title" ;;
    *) log_line "[通知未发送] ${notify_result:-系统通知服务拒绝请求}" ;;
  esac
}

add_bytes() {
  BYTES=$(awk -v a="$BYTES" -v b="$1" 'BEGIN {printf "%.0f", a + b}')
}

handle_file() {
  file=$1
  kind=${2:-regular}
  [ -f "$file" ] || return 0
  [ -L "$file" ] && return 0
  should_stop && return 9

  if is_whitelisted "$file"; then
    SKIPPED=$((SKIPPED + 1))
    log_line "[跳过:白名单][$CATEGORY] $file"
    return 0
  fi

  size=$(stat -c %s "$file" 2>/dev/null)
  case "$size" in ''|*[!0-9]*) size=0 ;; esac
  if [ "$kind" = "empty" ]; then
    name=${file##*/}
    case "$name" in .nomedia|.keep|.gitkeep|.placeholder|*.lock)
      SKIPPED=$((SKIPPED + 1))
      log_line "[跳过:占位文件][$CATEGORY] $file"
      return 0
      ;;
    esac
  fi
  if awk -v s="$size" -v m="$MAX_FILE_BYTES" 'BEGIN {exit !(s > m)}'; then
    SKIPPED=$((SKIPPED + 1))
    log_line "[跳过:大文件][$CATEGORY] $file ($size bytes)"
    return 0
  fi

  if [ "$MODE" = "clean" ]; then
    rm -f -- "$file" 2>/dev/null
    if [ ! -e "$file" ]; then
      if [ "$kind" = "empty" ]; then EMPTY_FILES=$((EMPTY_FILES + 1)); else FILES=$((FILES + 1)); fi
      [ "$HIDDEN_CONTEXT" = "1" ] && HIDDEN_ITEMS=$((HIDDEN_ITEMS + 1))
      add_bytes "$size"
      log_line "[已清理][$CATEGORY] $file ($size bytes)"
      report_line cleaned low "$CATEGORY" 1 "$size" "$file"
    else
      ERRORS=$((ERRORS + 1))
      log_line "[失败][$CATEGORY] $file"
      report_line failed low "$CATEGORY" 1 "$size" "$file"
    fi
  else
    if [ "$kind" = "empty" ]; then EMPTY_FILES=$((EMPTY_FILES + 1)); else FILES=$((FILES + 1)); fi
    [ "$HIDDEN_CONTEXT" = "1" ] && HIDDEN_ITEMS=$((HIDDEN_ITEMS + 1))
    add_bytes "$size"
    log_line "[可清理][$CATEGORY] $file ($size bytes)"
    report_line candidate low "$CATEGORY" 1 "$size" "$file"
  fi
}

count_nul() {
  tr -cd '\000' <"$1" | wc -c | tr -d ' '
}

bytes_from_list() {
  [ -s "$1" ] || { echo 0; return; }
  xargs -0 du -k <"$1" 2>/dev/null | awk '{sum += $1} END {printf "%.0f", sum * 1024}'
}

batch_actuals() {
  original=$1
  remaining=$2
  estimated=$3
  original_count=$(count_nul "$original")
  remaining_count=$(count_nul "$remaining")
  case "$original_count" in ''|*[!0-9]*) original_count=0 ;; esac
  case "$remaining_count" in ''|*[!0-9]*) remaining_count=0 ;; esac
  remaining_bytes=$(bytes_from_list "$remaining")
  case "$remaining_bytes" in ''|*[!0-9]*) remaining_bytes=0 ;; esac
  ACTUAL_COUNT=$((original_count - remaining_count))
  [ "$ACTUAL_COUNT" -lt 0 ] && ACTUAL_COUNT=0
  ACTUAL_BYTES=$(awk -v a="$estimated" -v b="$remaining_bytes" 'BEGIN {v=a-b; if (v<0) v=0; printf "%.0f", v}')
  REMAINING_COUNT=$remaining_count
  REMAINING_BYTES=$remaining_bytes
}

filter_whitelist_list() {
  source_list=$1
  [ "$WHITELIST_ACTIVE" = "1" ] || return 0
  filtered="$source_list.filtered"
  : >"$filtered"
  while IFS= read -r -d '' candidate; do
    if is_whitelisted "$candidate" || deep_conflicts_whitelist "$candidate"; then
      SKIPPED=$((SKIPPED + 1))
    else
      printf '%s\0' "$candidate" >>"$filtered"
    fi
  done <"$source_list"
  mv -f "$filtered" "$source_list"
}

# 将全部缓存目录交给同一个 find。旧版会为每个应用单独启动 find，
# 应用较多时会产生数百次进程创建，看起来像卡死。
collect_cache_candidates() {
  dir_list=$1
  days=$2
  target_list=$3
  set --
  while IFS= read -r dir || [ -n "$dir" ]; do
    [ -d "$dir" ] || continue
    [ -L "$dir" ] && continue
    set -- "$@" "$dir"
  done <"$dir_list"
  [ "$#" -gt 0 ] || return 0

  if [ "$days" -eq 0 ]; then
    if [ "$CLEAN_EMPTY_FILES" = "1" ]; then
      find "$@" -mindepth 1 -type f -size "-${MAX_FILE_BYTES}c" \
        ! -name '.nomedia' ! -name '.keep' ! -name '.gitkeep' ! -name '.placeholder' ! -name '*.lock' \
        -print0 2>/dev/null >>"$target_list"
    else
      find "$@" -mindepth 1 -type f -size +0c -size "-${MAX_FILE_BYTES}c" -print0 2>/dev/null >>"$target_list"
    fi
  elif [ "$CLEAN_EMPTY_FILES" = "1" ]; then
    find "$@" -mindepth 1 -type f -size "-${MAX_FILE_BYTES}c" -mtime "+$days" \
      ! -name '.nomedia' ! -name '.keep' ! -name '.gitkeep' ! -name '.placeholder' ! -name '*.lock' \
      -print0 2>/dev/null >>"$target_list"
  else
    find "$@" -mindepth 1 -type f -size +0c -size "-${MAX_FILE_BYTES}c" -mtime "+$days" -print0 2>/dev/null >>"$target_list"
  fi
}

process_cache_candidates() {
  list=$1
  CATEGORY=$2
  filter_whitelist_list "$list"
  count=$(count_nul "$list")
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  [ "$count" -gt 0 ] || { rm -f "$list"; return 0; }

  estimated=$(bytes_from_list "$list")
  case "$estimated" in ''|*[!0-9]*) estimated=0 ;; esac
  if [ "$MODE" = "clean" ]; then
    err_file="$TMP_DIR/rm-cache.err"
    xargs -0 -n 200 rm -f -- <"$list" 2>"$err_file"
    remaining="$list.remaining"
    existing_files_to_list "$list" "$remaining"
    batch_actuals "$list" "$remaining" "$estimated"
    [ "$REMAINING_COUNT" -gt 0 ] && ERRORS=$((ERRORS + REMAINING_COUNT))
    reason=$(tail -n 1 "$err_file" 2>/dev/null)
    [ "$REMAINING_COUNT" -gt 0 ] && log_line "[部分未清理][$CATEGORY] ${reason:-系统拒绝删除部分文件}"
    log_line "[批量清理][$CATEGORY] $ACTUAL_COUNT 个缓存文件，约 $ACTUAL_BYTES bytes，未清理 $REMAINING_COUNT 个"
    report_line cleaned low "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES" "批量缓存文件"
    [ "$REMAINING_COUNT" -gt 0 ] && report_line failed low "$CATEGORY" "$REMAINING_COUNT" "$REMAINING_BYTES" "仍存在的缓存文件"
    FILES=$((FILES + ACTUAL_COUNT))
    add_bytes "$ACTUAL_BYTES"
    rm -f "$remaining" "$err_file"
  else
    log_line "[批量扫描][$CATEGORY] $count 个缓存文件，$estimated bytes"
    report_line candidate low "$CATEGORY" "$count" "$estimated" "批量缓存文件"
    FILES=$((FILES + count))
    add_bytes "$estimated"
  fi
  rm -f "$list"
  return 0
}

clean_dir() {
  dir=$1
  days=$2
  CATEGORY=$3
  [ -d "$dir" ] || return 0
  [ -L "$dir" ] && return 0
  should_stop && return 9

  LIST_SEQ=$((LIST_SEQ + 1))
  list="$TMP_DIR/files.$LIST_SEQ.nul"
  if [ "$days" -eq 0 ]; then
    find "$dir" -mindepth 1 -type f -size +0c -size "-${MAX_FILE_BYTES}c" -print0 2>/dev/null >"$list"
  else
    find "$dir" -mindepth 1 -type f -size +0c -size "-${MAX_FILE_BYTES}c" -mtime "+$days" -print0 2>/dev/null >"$list"
  fi
  filter_whitelist_list "$list"
  count=$(count_nul "$list")
  case "$count" in ''|*[!0-9]*) count=0 ;; esac

  if [ "$count" -gt 0 ]; then
    estimated=$(bytes_from_list "$list")
    case "$estimated" in ''|*[!0-9]*) estimated=0 ;; esac
    if [ "$MODE" = "clean" ]; then
      err_file="$TMP_DIR/rm-dir.$LIST_SEQ.err"
      xargs -0 -n 200 rm -f -- <"$list" 2>"$err_file"
      remaining="$list.remaining"
      existing_files_to_list "$list" "$remaining"
      batch_actuals "$list" "$remaining" "$estimated"
      [ "$REMAINING_COUNT" -gt 0 ] && ERRORS=$((ERRORS + REMAINING_COUNT))
      reason=$(tail -n 1 "$err_file" 2>/dev/null)
      [ "$REMAINING_COUNT" -gt 0 ] && log_line "[部分未清理][$CATEGORY] ${reason:-系统拒绝删除部分文件}"
      FILES=$((FILES + ACTUAL_COUNT))
      [ "$HIDDEN_CONTEXT" = "1" ] && HIDDEN_ITEMS=$((HIDDEN_ITEMS + ACTUAL_COUNT))
      add_bytes "$ACTUAL_BYTES"
      log_line "[批量清理][$CATEGORY] $dir ($ACTUAL_COUNT 个文件，约 $ACTUAL_BYTES bytes，未清理 $REMAINING_COUNT 个)"
      report_line cleaned low "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES" "$dir"
      [ "$REMAINING_COUNT" -gt 0 ] && report_line failed low "$CATEGORY" "$REMAINING_COUNT" "$REMAINING_BYTES" "$dir"
      rm -f "$remaining" "$err_file"
    else
      FILES=$((FILES + count))
      [ "$HIDDEN_CONTEXT" = "1" ] && HIDDEN_ITEMS=$((HIDDEN_ITEMS + count))
      add_bytes "$estimated"
      log_line "[批量扫描][$CATEGORY] $dir ($count 个文件，$estimated bytes)"
      report_line candidate low "$CATEGORY" "$count" "$estimated" "$dir"
    fi
  fi
  rm -f "$list"

  if [ "$CLEAN_EMPTY_FILES" = "1" ]; then
    LIST_SEQ=$((LIST_SEQ + 1))
    list="$TMP_DIR/empty-files.$LIST_SEQ.nul"
    if [ "$EMPTY_DAYS" -eq 0 ]; then
      find "$dir" -mindepth 1 -type f -size 0c ! -name '.nomedia' ! -name '.keep' ! -name '.gitkeep' ! -name '.placeholder' ! -name '*.lock' -print0 2>/dev/null >"$list"
    else
      find "$dir" -mindepth 1 -type f -size 0c -mtime "+$EMPTY_DAYS" ! -name '.nomedia' ! -name '.keep' ! -name '.gitkeep' ! -name '.placeholder' ! -name '*.lock' -print0 2>/dev/null >"$list"
    fi
    filter_whitelist_list "$list"
    count=$(count_nul "$list")
    case "$count" in ''|*[!0-9]*) count=0 ;; esac
    if [ "$count" -gt 0 ]; then
      if [ "$MODE" = "clean" ]; then
        err_file="$TMP_DIR/rm-empty.$LIST_SEQ.err"
        xargs -0 -n 200 rm -f -- <"$list" 2>"$err_file"
        remaining="$list.remaining"
        existing_files_to_list "$list" "$remaining"
        batch_actuals "$list" "$remaining" 0
        [ "$REMAINING_COUNT" -gt 0 ] && ERRORS=$((ERRORS + REMAINING_COUNT))
        reason=$(tail -n 1 "$err_file" 2>/dev/null)
        [ "$REMAINING_COUNT" -gt 0 ] && log_line "[部分未清理][空文件:$CATEGORY] ${reason:-系统拒绝删除部分文件}"
        EMPTY_FILES=$((EMPTY_FILES + ACTUAL_COUNT))
        [ "$HIDDEN_CONTEXT" = "1" ] && HIDDEN_ITEMS=$((HIDDEN_ITEMS + ACTUAL_COUNT))
        log_line "[批量清理][空文件:$CATEGORY] $dir ($ACTUAL_COUNT 个，未清理 $REMAINING_COUNT 个)"
        report_line cleaned low "空文件:$CATEGORY" "$ACTUAL_COUNT" 0 "$dir"
        [ "$REMAINING_COUNT" -gt 0 ] && report_line failed low "空文件:$CATEGORY" "$REMAINING_COUNT" 0 "$dir"
        rm -f "$remaining" "$err_file"
      else
        EMPTY_FILES=$((EMPTY_FILES + count))
        [ "$HIDDEN_CONTEXT" = "1" ] && HIDDEN_ITEMS=$((HIDDEN_ITEMS + count))
        log_line "[批量扫描][空文件:$CATEGORY] $dir ($count 个)"
        report_line candidate low "空文件:$CATEGORY" "$count" 0 "$dir"
      fi
    fi
    rm -f "$list"
  fi

  if [ "$CLEAN_EMPTY_DIRS" = "1" ]; then
    LIST_SEQ=$((LIST_SEQ + 1))
    list="$TMP_DIR/empty-dirs.$LIST_SEQ.nul"
    find "$dir" -depth -mindepth 1 -type d -empty -print0 2>/dev/null >"$list"
    filter_whitelist_list "$list"
    count=$(count_nul "$list")
    case "$count" in ''|*[!0-9]*) count=0 ;; esac
    if [ "$count" -gt 0 ]; then
      if [ "$MODE" = "clean" ]; then
        xargs -0 -n 100 rmdir <"$list" 2>/dev/null
        remaining="$list.remaining"
        existing_paths_to_list "$list" "$remaining"
        batch_actuals "$list" "$remaining" 0
        [ "$REMAINING_COUNT" -gt 0 ] && ERRORS=$((ERRORS + REMAINING_COUNT))
        EMPTY_DIRS=$((EMPTY_DIRS + ACTUAL_COUNT))
        [ "$HIDDEN_CONTEXT" = "1" ] && HIDDEN_ITEMS=$((HIDDEN_ITEMS + ACTUAL_COUNT))
        log_line "[批量清理][空目录:$CATEGORY] $dir ($ACTUAL_COUNT 个，未清理 $REMAINING_COUNT 个)"
        report_line cleaned low "空目录:$CATEGORY" "$ACTUAL_COUNT" 0 "$dir"
        [ "$REMAINING_COUNT" -gt 0 ] && report_line failed low "空目录:$CATEGORY" "$REMAINING_COUNT" 0 "$dir"
        rm -f "$remaining"
      else
        EMPTY_DIRS=$((EMPTY_DIRS + count))
        [ "$HIDDEN_CONTEXT" = "1" ] && HIDDEN_ITEMS=$((HIDDEN_ITEMS + count))
        log_line "[批量扫描][空目录:$CATEGORY] $dir ($count 个)"
        report_line candidate low "空目录:$CATEGORY" "$count" 0 "$dir"
      fi
    fi
    rm -f "$list"
  fi
  return 0
}

is_allowed_custom_dir() {
  dir=${1%/}
  case "$dir" in
    *'/../'*|*'/..'|*'/./'*|*'/.'|*'//'*) return 1 ;;
    /data/local/tmp|/data/anr|/data/tombstones|/data/vendor/tombstones|/data/system/dropbox) return 0 ;;
  esac

  case "$dir" in
    /data/user/*|/data/user_de/*)
      rest=${dir#/data/user/}
      [ "$rest" = "$dir" ] && rest=${dir#/data/user_de/}
      user=${rest%%/*}; rest=${rest#*/}
      package=${rest%%/*}; leaf=${rest#*/}
      printf '%s' "$user" | grep -Eq '^[0-9]+$' || return 1
      printf '%s' "$package" | grep -Eq '^[A-Za-z0-9._-]+$' || return 1
      case "$leaf" in cache|code_cache) return 0 ;; esac
      ;;
    /data/media/*)
      rest=${dir#/data/media/}
      user=${rest%%/*}; rest=${rest#*/}
      printf '%s' "$user" | grep -Eq '^[0-9]+$' || return 1
      case "$rest" in
        MIUI/debug_log|oplus/log) return 0 ;;
        Android/data/*/cache)
          package=${rest#Android/data/}; package=${package%/cache}
          printf '%s' "$package" | grep -Eq '^[A-Za-z0-9._-]+$' && return 0
          ;;
      esac
      ;;
  esac
  return 1
}

scan_cache_roots() {
  roots=$1
  days=$2
  category=$3
  list="$TMP_DIR/dirs.$category"
  : >"$list"
  for root in $roots; do
    [ -d "$root" ] || continue
    for dir in "$root"/[0-9]*/*/cache "$root"/[0-9]*/*/code_cache; do
      [ -d "$dir" ] && [ ! -L "$dir" ] && printf '%s\n' "$dir" >>"$list"
    done
  done
  dir_count=$(wc -l <"$list" 2>/dev/null | tr -d ' ')
  case "$dir_count" in ''|*[!0-9]*) dir_count=0 ;; esac
  set_phase "批量扫描应用缓存（${dir_count}个目录）"
  candidates="$TMP_DIR/cache-internal.nul"
  : >"$candidates"
  should_stop && return 9
  collect_cache_candidates "$list" "$days" "$candidates"
  set_phase "统计并清理应用缓存"
  process_cache_candidates "$candidates" "$category"
}

scan_external_cache() {
  days=$1
  list="$TMP_DIR/dirs.external"
  : >"$list"
  if [ -d /data/media ]; then
    for dir in /data/media/[0-9]*/Android/data/*/cache; do
      [ -d "$dir" ] && [ ! -L "$dir" ] && printf '%s\n' "$dir" >>"$list"
    done
  fi
  dir_count=$(wc -l <"$list" 2>/dev/null | tr -d ' ')
  case "$dir_count" in ''|*[!0-9]*) dir_count=0 ;; esac
  set_phase "批量扫描外部缓存（${dir_count}个目录）"
  candidates="$TMP_DIR/cache-external.nul"
  : >"$candidates"
  should_stop && return 9
  collect_cache_candidates "$list" "$days" "$candidates"
  set_phase "统计并清理外部缓存"
  process_cache_candidates "$candidates" "外部应用缓存"
}

run_app_rules() {
  [ -f "$APP_RULES" ] || return 0
  while IFS='|' read -r package relative days extra || [ -n "$package$relative$days$extra" ]; do
    case "$package" in ''|'#'*) continue ;; esac
    [ -z "$extra" ] || { log_line "[拒绝:应用规则格式] $package"; continue; }
    case "$package" in *[!A-Za-z0-9._-]*) log_line "[拒绝:包名] $package"; continue ;; esac
    case "$relative" in ''|/*|*'..'*|*'//'*) log_line "[拒绝:相对路径] $package/$relative"; continue ;; esac
    case "$days" in ''|*[!0-9]*) log_line "[拒绝:规则天数] $package/$relative"; continue ;; esac
    for base in /data/user/[0-9]*/"$package" /data/user_de/[0-9]*/"$package"; do
      [ -d "$base" ] || continue
      target="$base/$relative"
      if [ -d "$target" ]; then
        clean_dir "$target" "$days" "应用扩展规则:$package" || return $?
      elif [ -f "$target" ] && { [ "$days" -eq 0 ] || find "$target" -type f -mtime "+$days" -print 2>/dev/null | grep -q .; }; then
        CATEGORY="应用扩展规则:$package"
        size=$(stat -c %s "$target" 2>/dev/null)
        if [ "${size:-0}" = "0" ]; then
          if [ "$CLEAN_EMPTY_FILES" = "1" ]; then handle_file "$target" empty || return $?; fi
        else
          handle_file "$target" regular || return $?
        fi
      fi
    done
  done <"$APP_RULES"
  return 0
}

run_external_rules() {
  [ -f "$EXTERNAL_RULES" ] || return 0
  while IFS='|' read -r package relative days extra || [ -n "$package$relative$days$extra" ]; do
    case "$package" in ''|'#'*) continue ;; esac
    [ -z "$extra" ] || { log_line "[拒绝:外部规则格式] $package"; continue; }
    case "$package" in *[!A-Za-z0-9._-]*) log_line "[拒绝:外部规则包名] $package"; continue ;; esac
    case "$relative" in ''|/*|*'..'*|*'//'*) log_line "[拒绝:外部相对路径] $package/$relative"; continue ;; esac
    case "$days" in ''|*[!0-9]*) log_line "[拒绝:外部规则天数] $package/$relative"; continue ;; esac
    for userdir in /data/media/[0-9]*; do
      [ -d "$userdir" ] || continue
      target="$userdir/Android/data/$package/$relative"
      if [ -d "$target" ]; then
        clean_dir "$target" "$days" "外部应用扩展规则:$package" || return $?
      elif [ -f "$target" ] && { [ "$days" -eq 0 ] || find "$target" -type f -mtime "+$days" -print 2>/dev/null | grep -q .; }; then
        CATEGORY="外部应用扩展规则:$package"
        size=$(stat -c %s "$target" 2>/dev/null)
        if [ "${size:-0}" = "0" ]; then
          if [ "$CLEAN_EMPTY_FILES" = "1" ]; then handle_file "$target" empty || return $?; fi
        else
          handle_file "$target" regular || return $?
        fi
      fi
    done
  done <"$EXTERNAL_RULES"
  return 0
}

# WebView 只清理明确可重新生成的 HTTP、GPU、代码与已完成崩溃缓存。
# 不碰 Cookies、IndexedDB、Local Storage、Web Data 或下载内容。
run_webview_cache_rules() {
  for dir in \
    /data/user/[0-9]*/*/app_webview/Default/Cache \
    /data/user/[0-9]*/*/app_webview/Default/GPUCache \
    /data/user/[0-9]*/*/app_webview/Default/'GPU Cache' \
    /data/user/[0-9]*/*/app_webview/Default/'Code Cache' \
    /data/user/[0-9]*/*/app_webview/Crashpad/completed \
    /data/user/[0-9]*/*/app_hws_webview/Default/Cache \
    /data/user/[0-9]*/*/app_hws_webview/Default/GPUCache \
    /data/user/[0-9]*/*/app_hws_webview/Default/'Code Cache' \
    /data/user_de/[0-9]*/*/app_webview/Default/Cache \
    /data/user_de/[0-9]*/*/app_webview/Default/GPUCache \
    /data/user_de/[0-9]*/*/app_webview/Default/'GPU Cache' \
    /data/user_de/[0-9]*/*/app_webview/Default/'Code Cache' \
    /data/user_de/[0-9]*/*/app_webview/Crashpad/completed; do
    [ -d "$dir" ] || continue
    clean_dir "$dir" 0 "WebView可再生缓存" || return $?
  done
  return 0
}

deep_conflicts_whitelist() {
  target=${1%/}
  [ "$WHITELIST_ACTIVE" = "1" ] || return 1
  old_ifs=$IFS
  IFS='
'
  for protected in $WHITELIST_PATHS; do
    [ "$protected" = "/" ] && { IFS=$old_ifs; return 0; }
    protected=${protected%/}
    case "$protected" in "$target"|"$target"/*) IFS=$old_ifs; return 0 ;; esac
  done
  IFS=$old_ifs
  return 1
}

is_deep_allowed() {
  target=${1%/}
  case "$target" in
    ''|/|*'/../'*|*'/..'|*'/./'*|*'/.'|*'//'*|"$MODDIR"|"$MODDIR"/*|"$STATE_DIR"|"$STATE_DIR"/*) return 1 ;;
    /data|/data/data|/data/user|/data/user_de|/data/media|/data_mirror|/data_mirror/data_ce) return 1 ;;
    /data/adb|/data/adb/*|/data/app|/data/app/*|/data/system|/data/system/*|/data/misc|/data/misc/*|/data/dalvik-cache|/data/dalvik-cache/*) return 1 ;;
    /system|/system/*|/vendor|/vendor/*|/product|/product/*|/apex|/apex/*) return 1 ;;
  esac
  case "$target" in
    /data/user/*) rest=${target#/data/user/}; [ "$rest" = "${rest%%/*}" ] && return 1 ;;
    /data/user_de/*) rest=${target#/data/user_de/}; [ "$rest" = "${rest%%/*}" ] && return 1 ;;
    /data/media/*) rest=${target#/data/media/}; [ "$rest" = "${rest%%/*}" ] && return 1 ;;
  esac
  case "$target" in
    /data/data/*|/data/user/*|/data/user_de/*|/data/cache/*|/data/media/[0-9]*/*|/data_mirror/data_ce/*) return 0 ;;
  esac
  return 1
}

deep_risk_level() {
  target=${1%/}
  lower=$(printf '%s' "$target" | tr '[:upper:]' '[:lower:]')
  case "$lower" in
    /data/media/[0-9]*/download|/data/media/[0-9]*/download/*|/data/media/[0-9]*/documents|/data/media/[0-9]*/documents/*|/data/media/[0-9]*/dcim|/data/media/[0-9]*/dcim/*|/data/media/[0-9]*/pictures|/data/media/[0-9]*/pictures/*|/data/media/[0-9]*/movies|/data/media/[0-9]*/movies/*|/data/media/[0-9]*/music|/data/media/[0-9]*/music/*|*/android/obb|*/android/obb/*|*/backup|*/backup/*|*/backups|*/backups/*|*/rough_draft|*/rough_draft/*|*/draft|*/draft/*|*/drafts|*/drafts/*|*/database|*/database/*|*/databases|*/databases/*|*/shared_prefs|*/shared_prefs/*) echo critical; return ;;
    */cache|*/cache/*|*/code_cache|*/code_cache/*|*/gpucache|*/gpucache/*|*/code\ cache|*/code\ cache/*|*/crashpad/completed|*/crashpad/completed/*|*/tmp|*/tmp/*|*/temp|*/temp/*|*/logs|*/logs/*|*/log|*/log/*|*/.cache|*/.cache/*|*/.thumbnails|*/.thumbnails/*) echo low; return ;;
    */crash*|*/tombstone*|*/debug*|*/trace*|*/dump*) echo medium; return ;;
    */files|*/files/*|*/app_webview|*/app_webview/*|*/webview|*/webview/*|*/local\ storage|*/local\ storage/*|*/indexeddb|*/indexeddb/*|*/cookies|*/cookies/*) echo high; return ;;
  esac
  echo high
}

count_risk() {
  case "$1" in
    low) RISK_LOW=$((RISK_LOW + 1)) ;;
    medium) RISK_MEDIUM=$((RISK_MEDIUM + 1)) ;;
    high) RISK_HIGH=$((RISK_HIGH + 1)) ;;
    critical) RISK_CRITICAL=$((RISK_CRITICAL + 1)) ;;
  esac
}

deep_rules_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$DEEP_RULES" 2>/dev/null | awk 'NR == 1 {print $1}'
  elif command -v toybox >/dev/null 2>&1; then
    toybox sha256sum "$DEEP_RULES" 2>/dev/null | awk 'NR == 1 {print $1}'
  fi
}

deep_scan_matches_rules() {
  saved=$(sed -n 's/^rules_sha=//p' "$DEEP_SCAN_STATE" 2>/dev/null | tail -n 1)
  [ -n "$saved" ] && [ -n "${DEEP_RULE_SHA:-}" ] && [ "$saved" = "$DEEP_RULE_SHA" ]
}

recent_scan_ok() {
  file=$1
  max_age=${2:-1800}
  epoch=$(sed -n 's/^epoch=//p' "$file" 2>/dev/null | tail -n 1)
  case "$epoch" in ''|*[!0-9]*) return 1 ;; esac
  now=$(date +%s)
  [ $((now - epoch)) -ge 0 ] && [ $((now - epoch)) -le "$max_age" ]
}

deep_high_risk_allowed() {
  [ "$MODE" = "clean" ] || return 1
  case "$TRIGGER" in scheduled:*|daily:*) return 1 ;; esac
  [ "$(get_bool deep_high_risk_enabled)" = "1" ] || return 1
  recent_scan_ok "$DEEP_SCAN_STATE" 1800 || return 1
  deep_scan_matches_rules
}

deep_risk_is_eligible() {
  risk=$1
  case "$risk" in
    low|medium) return 0 ;;
    high|critical)
      if [ "$MODE" = "scan" ]; then
        [ "$(get_bool deep_high_risk_enabled)" = "1" ]
      else
        deep_high_risk_allowed
      fi
      ;;
  esac
  return 1
}

deep_target_stats() {
  target=$1
  if [ -d "$target" ]; then
    kb=$(du -sk "$target" 2>/dev/null | awk 'NR == 1 {print $1}')
    case "$kb" in ''|*[!0-9]*) kb=0 ;; esac
    DEEP_TARGET_SIZE=$(awk -v k="$kb" 'BEGIN {printf "%.0f", k * 1024}')
    DEEP_TARGET_COUNT=$(find "$target" -type f 2>/dev/null | wc -l | tr -d ' ')
    case "$DEEP_TARGET_COUNT" in ''|*[!0-9]*) DEEP_TARGET_COUNT=0 ;; esac
    DEEP_TARGET_EMPTY=0
    [ "$DEEP_TARGET_COUNT" -eq 0 ] && DEEP_TARGET_EMPTY=1
  else
    DEEP_TARGET_SIZE=$(stat -c %s "$target" 2>/dev/null)
    case "$DEEP_TARGET_SIZE" in ''|*[!0-9]*) DEEP_TARGET_SIZE=0 ;; esac
    DEEP_TARGET_COUNT=1
    DEEP_TARGET_EMPTY=0
  fi
  DEEP_REPORT_COUNT=$DEEP_TARGET_COUNT
  [ "$DEEP_REPORT_COUNT" -gt 0 ] || DEEP_REPORT_COUNT=1
}

deep_process_target() {
  target=${1%/}
  DEEP_COVER_TARGET=0
  [ -e "$target" ] || [ -L "$target" ] || return 0
  [ -L "$target" ] && { log_line "[深度跳过:软链接] $target"; SKIPPED=$((SKIPPED + 1)); report_line skipped protected 深度规则 0 0 "$target"; return 0; }
  is_deep_allowed "$target" || { log_line "[深度拒绝:系统边界] $target"; SKIPPED=$((SKIPPED + 1)); report_line rejected protected 深度规则 0 0 "$target"; return 0; }
  is_protected_hidden_path "$target" && { log_line "[深度跳过:隐藏配置] $target"; SKIPPED=$((SKIPPED + 1)); report_line skipped protected 深度规则 0 0 "$target"; return 0; }
  if is_whitelisted "$target" || deep_conflicts_whitelist "$target"; then
    log_line "[深度跳过:白名单] $target"
    SKIPPED=$((SKIPPED + 1))
    report_line skipped protected 深度规则 0 0 "$target"
    return 0
  fi

  risk=$(deep_risk_level "$target")
  count_risk "$risk"
  if ! deep_risk_is_eligible "$risk"; then
    # 定时任务只需要知道路径受保护，不再递归统计高风险大目录，减少无效 I/O。
    if [ "$MODE" = "clean" ]; then
      case "$TRIGGER" in scheduled:*|daily:*)
        size=0; report_count=1
        ;;
        *)
          deep_target_stats "$target"
          size=$DEEP_TARGET_SIZE; report_count=$DEEP_REPORT_COUNT
          ;;
      esac
    else
      deep_target_stats "$target"
      size=$DEEP_TARGET_SIZE; report_count=$DEEP_REPORT_COUNT
    fi
    PROTECTED_ITEMS=$((PROTECTED_ITEMS + report_count))
    PROTECTED_BYTES=$(awk -v a="$PROTECTED_BYTES" -v b="$size" 'BEGIN {printf "%.0f", a+b}')
    log_line "[深度受保护:$risk] $target（${report_count} 项，约 $size bytes）"
    report_line protected "$risk" 深度规则 "$report_count" "$size" "$target"
    return 0
  fi

  deep_target_stats "$target"
  size=$DEEP_TARGET_SIZE
  count=$DEEP_TARGET_COUNT
  was_empty_dir=$DEEP_TARGET_EMPTY
  report_count=$DEEP_REPORT_COUNT

  oversized=0
  if [ -f "$target" ]; then
    [ "$size" -gt "$MAX_FILE_BYTES" ] && oversized=1
  elif [ -d "$target" ] && find "$target" -type f -size "+${MAX_FILE_BYTES}c" -print -quit 2>/dev/null | grep -q .; then
    oversized=1
  fi
  if [ "$oversized" = "1" ]; then
    PROTECTED_ITEMS=$((PROTECTED_ITEMS + report_count))
    PROTECTED_BYTES=$(awk -v a="$PROTECTED_BYTES" -v b="$size" 'BEGIN {printf "%.0f", a+b}')
    log_line "[深度受保护:超过单文件上限] $target（上限 ${MAX_MB} MiB）"
    report_line protected "$risk" 深度规则 "$report_count" "$size" "$target（含超过 ${MAX_MB} MiB 的文件）"
    return 0
  fi

  if [ "$MODE" = "clean" ]; then
    err_file="$TMP_DIR/deep-rm.err"
    if [ -d "$target" ]; then rm -rf -- "$target" 2>"$err_file"; else rm -f -- "$target" 2>"$err_file"; fi
    if [ -e "$target" ] || [ -L "$target" ]; then
      reason=$(tail -n 1 "$err_file" 2>/dev/null)
      if [ -d "$target" ]; then
        remaining_kb=$(du -sk "$target" 2>/dev/null | awk 'NR == 1 {print $1}')
        case "$remaining_kb" in ''|*[!0-9]*) remaining_kb=0 ;; esac
        remaining_size=$(awk -v k="$remaining_kb" 'BEGIN {printf "%.0f", k * 1024}')
        remaining_count=$(find "$target" -type f 2>/dev/null | wc -l | tr -d ' ')
        case "$remaining_count" in ''|*[!0-9]*) remaining_count=0 ;; esac
      else
        remaining_size=$(stat -c %s "$target" 2>/dev/null)
        case "$remaining_size" in ''|*[!0-9]*) remaining_size=0 ;; esac
        remaining_count=1
      fi
      actual_count=$((count - remaining_count)); [ "$actual_count" -lt 0 ] && actual_count=0
      actual_size=$(awk -v a="$size" -v b="$remaining_size" 'BEGIN {v=a-b; if (v<0) v=0; printf "%.0f", v}')
      [ "$actual_count" -gt 0 ] && FILES=$((FILES + actual_count))
      [ "$actual_size" -gt 0 ] && add_bytes "$actual_size"
      [ "$actual_count" -gt 0 ] && report_line cleaned "$risk" 深度规则 "$actual_count" "$actual_size" "$target"
      [ "$remaining_count" -gt 0 ] || remaining_count=1
      ERRORS=$((ERRORS + remaining_count))
      log_line "[深度部分未清理:$risk] $target | 已清理 $actual_count 个，剩余 $remaining_count 个 | ${reason:-目标仍然存在}"
      report_line failed "$risk" 深度规则 "$remaining_count" "$remaining_size" "$target"
      rm -f "$err_file"
      return 0
    fi
    rm -f "$err_file"
    if [ "$was_empty_dir" = "1" ]; then EMPTY_DIRS=$((EMPTY_DIRS + 1)); else FILES=$((FILES + count)); fi
    add_bytes "$size"
    log_line "[深度已清理:$risk] $target ($count 个文件，约 $size bytes)"
    report_line cleaned "$risk" 深度规则 "$report_count" "$size" "$target"
    [ -d "$target" ] || DEEP_COVER_TARGET=1
  else
    if [ "$was_empty_dir" = "1" ]; then EMPTY_DIRS=$((EMPTY_DIRS + 1)); else FILES=$((FILES + count)); fi
    add_bytes "$size"
    log_line "[深度可清理:$risk] $target ($count 个文件，约 $size bytes)"
    report_line candidate "$risk" 深度规则 "$report_count" "$size" "$target"
    printf '%s\t%s\n' "$target" "$risk" >>"$DEEP_SCAN_MANIFEST_TMP"
    [ -d "$target" ] && DEEP_COVER_TARGET=1
  fi
}

run_deep_rules() {
  [ -f "$DEEP_RULES" ] || return 0
  candidates="$TMP_DIR/deep-targets"
  sorted="$TMP_DIR/deep-targets.sorted"
  : >"$candidates"

  case "$REQUEST_MODE:$TRIGGER" in
    deep-clean:scheduled:*|deep-clean:daily:*) use_snapshot=0 ;;
    deep-clean:*)
      if ! recent_scan_ok "$DEEP_SCAN_STATE" 1800 || ! deep_scan_matches_rules || [ ! -f "$DEEP_SCAN_TARGETS" ]; then
        log_line "[深度拒绝] 请先完成深度扫描，并在 30 分钟内按扫描候选清理"
        return 6
      fi
      use_snapshot=1
      cut -f1 "$DEEP_SCAN_TARGETS" 2>/dev/null >"$candidates"
      ;;
    *) use_snapshot=0 ;;
  esac

  if [ "$REQUEST_MODE" = "deep-scan" ]; then : >"$DEEP_SCAN_MANIFEST_TMP"; fi

  if [ "$use_snapshot" = "0" ]; then
    old_ifs=$IFS
    IFS='
'
    while IFS= read -r pattern || [ -n "$pattern" ]; do
      should_stop && { IFS=$old_ifs; return 9; }
      case "$pattern" in /*) ;; *) continue ;; esac
      case "$pattern" in
        /storage/emulated/0*) pattern="/data/media/0${pattern#/storage/emulated/0}" ;;
        /sdcard*) pattern="/data/media/0${pattern#/sdcard}" ;;
      esac
      case "$pattern" in
        *'*'*|*'?'*|*'['*)
          for target in $pattern; do
            [ -e "$target" ] || [ -L "$target" ] || continue
            printf '%s\n' "${target%/}" >>"$candidates"
          done
          ;;
        *)
          [ -e "$pattern" ] || [ -L "$pattern" ] || continue
          printf '%s\n' "${pattern%/}" >>"$candidates"
          ;;
      esac
    done <"$DEEP_RULES"
    IFS=$old_ifs
  fi

  if sort -u "$candidates" >"$sorted" 2>/dev/null; then mv -f "$sorted" "$candidates"; else rm -f "$sorted"; fi
  covered_by=""
  while IFS= read -r target || [ -n "$target" ]; do
    should_stop && return 9
    if [ -n "$covered_by" ]; then
      case "$target" in
        "$covered_by"/*) log_line "[深度去重:已处理父目录覆盖] $target"; continue ;;
        *) covered_by="" ;;
      esac
    fi
    deep_process_target "$target"
    [ "$DEEP_COVER_TARGET" = "1" ] && covered_by=$target
  done <"$candidates"
  return 0
}

package_list_for_user() {
  user=$1
  output=$2
  : >"$output"
  if command -v cmd >/dev/null 2>&1; then
    cmd package list packages --user "$user" 2>/dev/null | sed 's/^package://' | sort -u >"$output"
  fi
  if [ ! -s "$output" ] && command -v pm >/dev/null 2>&1; then
    pm list packages --user "$user" 2>/dev/null | sed 's/^package://' | sort -u >"$output"
  fi
  [ -s "$output" ]
}

# “立即清理/安全扫描”会同时需要空项目、隐藏垃圾和碎片候选。
# 旧版会为这些类别分别遍历共享存储；这里先做一次候选遍历，再按类型分流。
prepare_shared_manifests() {
  [ -d /data/media ] || return 0
  SHARED_EMPTY_FILES_MANIFEST="$TMP_DIR/manifest-empty-files.nul"
  SHARED_EMPTY_DIRS_MANIFEST="$TMP_DIR/manifest-empty-dirs.nul"
  SHARED_HIDDEN_DIRS_MANIFEST="$TMP_DIR/manifest-hidden-dirs"
  SHARED_HIDDEN_FILES_MANIFEST="$TMP_DIR/manifest-hidden-files"
  SHARED_FRAGMENT_MANIFEST="$TMP_DIR/manifest-fragments.nul"
  candidates="$TMP_DIR/manifest-candidates.nul"
  : >"$SHARED_EMPTY_FILES_MANIFEST"
  : >"$SHARED_EMPTY_DIRS_MANIFEST"
  : >"$SHARED_HIDDEN_DIRS_MANIFEST"
  : >"$SHARED_HIDDEN_FILES_MANIFEST"
  : >"$SHARED_FRAGMENT_MANIFEST"

  find /data/media -mindepth 2 -maxdepth 6 \
    \( -path '/data/media/[0-9]*/Android' -o -path '/data/media/[0-9]*/Android/*' \
       -o -path '/data/media/[0-9]*/DCIM' -o -path '/data/media/[0-9]*/Pictures' \
       -o -path '/data/media/[0-9]*/Movies' -o -path '/data/media/[0-9]*/Music' \
       -o -path '/data/media/[0-9]*/Download' -o -path '/data/media/[0-9]*/Documents' \
       -o -path '/data/media/[0-9]*/Podcasts' -o -path '/data/media/[0-9]*/Audiobooks' \
       -o -path '/data/media/[0-9]*/Recordings' -o -path '/data/media/[0-9]*/Fonts' \
       -o -path '/data/media/[0-9]*/Ringtones' -o -path '/data/media/[0-9]*/Alarms' \
       -o -path '/data/media/[0-9]*/Notifications' \) -prune -o \
    \( -type d -name '.*' -print0 \) -prune -o \
    \( -type f -size "-${MAX_FILE_BYTES}c" \
       \( -size 0c -o -name '.DS_Store' -o -name '._*' -o -name 'Thumbs.db' -o -name 'desktop.ini' -o -name '.directory' \
          -o -iname '*.tmp' -o -iname '*.temp' -o -iname '*.tmf' -o -iname '*.log' \
          -o -iname '*.xlog' -o -iname '*.tlog' -o -iname '*.ulog' -o -iname '*.plog' \
          -o -iname '*.hprof' -o -iname '*.dmp' -o -iname '*.dump' -o -iname '*.trace' \
          -o -iname '*.traces' -o -iname '*.stacktrace' -o -iname 'hs_err_pid*.log' \) \
       -o -type d -empty \) -print0 2>/dev/null >"$candidates"

  # 公共媒体树整体受保护，仅把明确可再生的缩略图目录加入隐藏垃圾候选。
  for direct_hidden in /data/media/[0-9]*/DCIM/.thumbnails /data/media/[0-9]*/Pictures/.thumbnails; do
    [ -d "$direct_hidden" ] && printf '%s\n' "$direct_hidden" >>"$SHARED_HIDDEN_DIRS_MANIFEST"
  done
  # Download 只收集中断下载后缀，不把普通临时文档纳入。
  for userdir in /data/media/[0-9]*; do
    [ -d "$userdir/Download" ] || continue
    find "$userdir/Download" -mindepth 1 -maxdepth 4 -type f -size "-${MAX_FILE_BYTES}c" \
      \( -iname '*.part' -o -iname '*.partial' -o -iname '*.crdownload' \
         -o -iname '*.filepart' -o -iname '*.download' -o -iname '*.opdownload' \) \
      -print0 2>/dev/null >>"$SHARED_FRAGMENT_MANIFEST"
  done

  while IFS= read -r -d '' candidate; do
    should_stop && { rm -f "$candidates"; return 9; }
    name=${candidate##*/}
    if [ -d "$candidate" ] && [ ! -L "$candidate" ]; then
      case "$name" in
        .*)
          if hidden_dir_days "$name" >/dev/null 2>&1; then
            printf '%s\n' "$candidate" >>"$SHARED_HIDDEN_DIRS_MANIFEST"
          elif [ -z "$(ls -A "$candidate" 2>/dev/null)" ]; then
            printf '%s\0' "$candidate" >>"$SHARED_EMPTY_DIRS_MANIFEST"
          fi
          ;;
        *) printf '%s\0' "$candidate" >>"$SHARED_EMPTY_DIRS_MANIFEST" ;;
      esac
      continue
    fi
    [ -f "$candidate" ] && [ ! -L "$candidate" ] || continue
    if [ ! -s "$candidate" ]; then
      case "$name" in .nomedia|.keep|.gitkeep|.placeholder|*.lock) ;; *) printf '%s\0' "$candidate" >>"$SHARED_EMPTY_FILES_MANIFEST" ;; esac
    else
      case "$name" in
        .DS_Store|._*|Thumbs.db|desktop.ini|.directory) printf '%s\n' "$candidate" >>"$SHARED_HIDDEN_FILES_MANIFEST" ;;
        *) printf '%s\0' "$candidate" >>"$SHARED_FRAGMENT_MANIFEST" ;;
      esac
    fi
  done <"$candidates"
  rm -f "$candidates"

  # 年龄过滤只作用于小型候选集合，不再重新遍历整个共享存储。
  for age_spec in \
    "$SHARED_EMPTY_FILES_MANIFEST:$EMPTY_DAYS" \
    "$SHARED_FRAGMENT_MANIFEST:$FRAGMENT_DAYS"; do
    age_file=${age_spec%:*}; age_days=${age_spec##*:}
    [ "$age_days" -gt 0 ] && [ -s "$age_file" ] || continue
    aged="$age_file.aged"
    xargs -0 -n 120 sh -c 'days=$1; shift; find "$@" -maxdepth 0 -type f -mtime "+$days" -print0 2>/dev/null' sh "$age_days" <"$age_file" >"$aged"
    mv -f "$aged" "$age_file"
  done
  if [ "$HIDDEN_DAYS" -gt 0 ] && [ -s "$SHARED_HIDDEN_FILES_MANIFEST" ]; then
    hidden_nul="$TMP_DIR/manifest-hidden-files.nul"
    hidden_aged="$TMP_DIR/manifest-hidden-files.aged.nul"
    while IFS= read -r hidden_file || [ -n "$hidden_file" ]; do printf '%s\0' "$hidden_file"; done <"$SHARED_HIDDEN_FILES_MANIFEST" >"$hidden_nul"
    xargs -0 -n 120 sh -c 'days=$1; shift; find "$@" -maxdepth 0 -type f -mtime "+$days" -print 2>/dev/null' sh "$HIDDEN_DAYS" <"$hidden_nul" >"$hidden_aged"
    mv -f "$hidden_aged" "$SHARED_HIDDEN_FILES_MANIFEST"
    rm -f "$hidden_nul"
  fi
  SHARED_MANIFEST_READY=1
  return 0
}

corpse_process_target() {
  target=${1%/}
  [ -d "$target" ] || return 0
  [ -L "$target" ] && return 0
  if is_whitelisted "$target" || deep_conflicts_whitelist "$target"; then
    SKIPPED=$((SKIPPED + 1))
    log_line "[残留跳过:白名单] $target"
    report_line skipped protected 卸载残留 0 0 "$target"
    return 0
  fi
  kb=$(du -sk "$target" 2>/dev/null | awk 'NR == 1 {print $1}')
  case "$kb" in ''|*[!0-9]*) kb=0 ;; esac
  size=$(awk -v k="$kb" 'BEGIN {printf "%.0f", k * 1024}')
  count=$(find "$target" -type f 2>/dev/null | wc -l | tr -d ' ')
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  was_empty_dir=0
  [ "$count" -eq 0 ] && was_empty_dir=1
  report_count=$count
  [ "$report_count" -gt 0 ] || report_count=1
  if find "$target" -type f -size "+${MAX_FILE_BYTES}c" -print -quit 2>/dev/null | grep -q .; then
    PROTECTED_ITEMS=$((PROTECTED_ITEMS + report_count))
    PROTECTED_BYTES=$(awk -v a="$PROTECTED_BYTES" -v b="$size" 'BEGIN {printf "%.0f", a+b}')
    log_line "[残留受保护:超过单文件上限] $target（上限 ${MAX_MB} MiB）"
    report_line protected high 卸载残留 "$report_count" "$size" "$target（含超过 ${MAX_MB} MiB 的文件）"
    return 0
  fi
  if [ "$MODE" = "clean" ]; then
    rm -rf -- "$target" 2>/dev/null
    if [ -e "$target" ]; then
      remaining_kb=$(du -sk "$target" 2>/dev/null | awk 'NR == 1 {print $1}')
      case "$remaining_kb" in ''|*[!0-9]*) remaining_kb=0 ;; esac
      remaining_size=$(awk -v k="$remaining_kb" 'BEGIN {printf "%.0f", k * 1024}')
      remaining_count=$(find "$target" -type f 2>/dev/null | wc -l | tr -d ' ')
      case "$remaining_count" in ''|*[!0-9]*) remaining_count=0 ;; esac
      actual_count=$((count - remaining_count)); [ "$actual_count" -lt 0 ] && actual_count=0
      actual_size=$(awk -v a="$size" -v b="$remaining_size" 'BEGIN {v=a-b; if (v<0) v=0; printf "%.0f", v}')
      [ "$actual_count" -gt 0 ] && FILES=$((FILES + actual_count))
      [ "$actual_size" -gt 0 ] && add_bytes "$actual_size"
      [ "$actual_count" -gt 0 ] && report_line cleaned high 卸载残留 "$actual_count" "$actual_size" "$target"
      [ "$remaining_count" -gt 0 ] || remaining_count=1
      ERRORS=$((ERRORS + remaining_count))
      log_line "[残留部分未清理] $target（已清理 $actual_count 个，剩余 $remaining_count 个）"
      report_line failed high 卸载残留 "$remaining_count" "$remaining_size" "$target"
    else
      if [ "$was_empty_dir" = "1" ]; then EMPTY_DIRS=$((EMPTY_DIRS + 1)); else FILES=$((FILES + count)); fi
      add_bytes "$size"
      log_line "[残留已清理] $target ($count 个文件，约 $size bytes)"
      report_line cleaned high 卸载残留 "$report_count" "$size" "$target"
    fi
  else
    if [ "$was_empty_dir" = "1" ]; then EMPTY_DIRS=$((EMPTY_DIRS + 1)); else FILES=$((FILES + count)); fi
    add_bytes "$size"
    log_line "[残留可清理] $target ($count 个文件，约 $size bytes)"
    report_line candidate high 卸载残留 "$report_count" "$size" "$target"
    printf '%s\n' "$target" >>"$CORPSE_SCAN_MANIFEST_TMP"
  fi
}

run_corpse_cleanup() {
  if [ "$MODE" = "clean" ]; then
    if ! recent_scan_ok "$CORPSE_SCAN_STATE" 1800 || [ ! -f "$CORPSE_SCAN_TARGETS" ]; then
      log_line "[残留拒绝] 请先执行卸载残留扫描，并在 30 分钟内按扫描候选清理"
      return 6
    fi
    current_user=""
    packages=""
    while IFS= read -r target || [ -n "$target" ]; do
      should_stop && return 9
      case "$target" in
        /data/media/[0-9]*/Android/data/*|/data/media/[0-9]*/Android/obb/*) ;;
        *) log_line "[残留跳过:快照路径异常] $target"; continue ;;
      esac
      rest=${target#/data/media/}
      user=${rest%%/*}
      package=${target##*/}
      case "$user" in ''|*[!0-9]*) continue ;; esac
      case "$package" in ''|*[!A-Za-z0-9._-]*) continue ;; esac
      if [ "$user" != "$current_user" ]; then
        packages="$TMP_DIR/installed-$user.txt"
        if ! package_list_for_user "$user" "$packages"; then
          log_line "[残留跳过] 无法读取用户 $user 的已安装包列表"
          current_user=""
          continue
        fi
        current_user=$user
      fi
      grep -Fxq "$package" "$packages" 2>/dev/null && { log_line "[残留跳过:应用已安装] $target"; continue; }
      corpse_process_target "$target"
    done <"$CORPSE_SCAN_TARGETS"
    return 0
  fi

  : >"$CORPSE_SCAN_MANIFEST_TMP"
  found_users=0
  for userdir in /data/media/[0-9]*; do
    [ -d "$userdir" ] || continue
    user=${userdir##*/}
    packages="$TMP_DIR/installed-$user.txt"
    if ! package_list_for_user "$user" "$packages"; then
      log_line "[残留跳过] 无法读取用户 $user 的已安装包列表"
      continue
    fi
    found_users=$((found_users + 1))
    for root in "$userdir/Android/data" "$userdir/Android/obb"; do
      [ -d "$root" ] || continue
      for target in "$root"/*; do
        should_stop && return 9
        [ -d "$target" ] || continue
        package=${target##*/}
        case "$package" in ''|*[!A-Za-z0-9._-]*) continue ;; esac
        grep -Fxq "$package" "$packages" 2>/dev/null && continue
        corpse_process_target "$target"
      done
    done
  done
  [ "$found_users" -gt 0 ] || { log_line "[残留失败] 未能读取任何 Android 用户的包列表"; return 7; }
}

scan_shared_empty_files() {
  [ -d /data/media ] || return 0
  list="$TMP_DIR/shared-empty-files.nul"
  if [ "$SHARED_MANIFEST_READY" = "1" ]; then
    cp -f "$SHARED_EMPTY_FILES_MANIFEST" "$list"
  else
    find /data/media -mindepth 2 -maxdepth 6 \
      \( -path '/data/media/[0-9]*/Android' -o -path '/data/media/[0-9]*/Android/*' \
         -o -path '/data/media/[0-9]*/DCIM' -o -path '/data/media/[0-9]*/Pictures' \
         -o -path '/data/media/[0-9]*/Movies' -o -path '/data/media/[0-9]*/Music' \
         -o -path '/data/media/[0-9]*/Download' -o -path '/data/media/[0-9]*/Documents' \) -prune -o \
      -type f -size 0c \
      ! -name '.nomedia' ! -name '.keep' ! -name '.gitkeep' ! -name '.placeholder' ! -name '*.lock' \
      -print0 2>/dev/null >"$list"
  fi
  filter_whitelist_list "$list"
  count=$(count_nul "$list")
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  if [ "$count" -gt 0 ]; then
    if [ "$MODE" = "clean" ]; then
      xargs -0 -n 200 rm -f -- <"$list" 2>/dev/null
      remaining="$list.remaining"
      existing_files_to_list "$list" "$remaining"
      batch_actuals "$list" "$remaining" 0
      [ "$REMAINING_COUNT" -gt 0 ] && ERRORS=$((ERRORS + REMAINING_COUNT))
      EMPTY_FILES=$((EMPTY_FILES + ACTUAL_COUNT))
      log_line "[批量清理][共享存储空文件] $ACTUAL_COUNT 个，未清理 $REMAINING_COUNT 个"
      report_line cleaned low 共享存储空文件 "$ACTUAL_COUNT" 0 /data/media
      [ "$REMAINING_COUNT" -gt 0 ] && report_line failed low 共享存储空文件 "$REMAINING_COUNT" 0 /data/media
      rm -f "$remaining"
    else
      EMPTY_FILES=$((EMPTY_FILES + count))
      log_line "[批量扫描][共享存储空文件] $count 个"
      report_line candidate low 共享存储空文件 "$count" 0 /data/media
    fi
  fi
  rm -f "$list"
}

scan_shared_empty_dirs() {
  [ -d /data/media ] || return 0
  list="$TMP_DIR/shared-empty-dirs.nul"
  if [ "$SHARED_MANIFEST_READY" = "1" ]; then
    cp -f "$SHARED_EMPTY_DIRS_MANIFEST" "$list"
  else
    find /data/media -mindepth 2 -maxdepth 6 \
      \( -path '/data/media/[0-9]*/Android' -o -path '/data/media/[0-9]*/Android/*' \
         -o -path '/data/media/[0-9]*/DCIM' -o -path '/data/media/[0-9]*/Pictures' \
         -o -path '/data/media/[0-9]*/Movies' -o -path '/data/media/[0-9]*/Music' \
         -o -path '/data/media/[0-9]*/Download' -o -path '/data/media/[0-9]*/Documents' \) -prune -o \
      -type d -empty \
      ! -path '/data/media/[0-9]*/DCIM' ! -path '/data/media/[0-9]*/Pictures' \
      ! -path '/data/media/[0-9]*/Movies' ! -path '/data/media/[0-9]*/Music' \
      ! -path '/data/media/[0-9]*/Download' ! -path '/data/media/[0-9]*/Documents' \
      ! -path '/data/media/[0-9]*/Podcasts' ! -path '/data/media/[0-9]*/Ringtones' \
      ! -path '/data/media/[0-9]*/Alarms' ! -path '/data/media/[0-9]*/Notifications' \
      ! -path '/data/media/[0-9]*/Audiobooks' ! -path '/data/media/[0-9]*/Recordings' \
      ! -path '/data/media/[0-9]*/MIUI' ! -path '/data/media/[0-9]*/ColorOS' \
      ! -path '/data/media/[0-9]*/HeyTap' ! -path '/data/media/[0-9]*/oplus' \
      -print0 2>/dev/null >"$list"
  fi
  filter_whitelist_list "$list"
  count=$(count_nul "$list")
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  [ "$count" -gt 0 ] || { rm -f "$list"; return 0; }
  if [ "$MODE" = "scan" ]; then
    EMPTY_DIRS=$((EMPTY_DIRS + count))
    log_line "[批量扫描][共享存储空目录] $count 个"
    report_line candidate low 共享存储空目录 "$count" 0 /data/media
    rm -f "$list"
    return 0
  fi

  parents="$TMP_DIR/shared-empty-parents"
  : >"$parents"
  while IFS= read -r -d '' dir; do
    parent=${dir%/*}
    level=1
    while [ "$level" -le 4 ]; do
      case "$parent" in /data/media|/data/media/[0-9]*) break ;; esac
      case "$parent" in
        /data/media/[0-9]*/DCIM|/data/media/[0-9]*/Pictures|/data/media/[0-9]*/Movies|/data/media/[0-9]*/Music|\
        /data/media/[0-9]*/Download|/data/media/[0-9]*/Documents|/data/media/[0-9]*/Podcasts|/data/media/[0-9]*/Ringtones|\
        /data/media/[0-9]*/Alarms|/data/media/[0-9]*/Notifications|/data/media/[0-9]*/Audiobooks|/data/media/[0-9]*/Recordings|\
        /data/media/[0-9]*/MIUI|/data/media/[0-9]*/ColorOS|/data/media/[0-9]*/HeyTap|/data/media/[0-9]*/oplus) ;;
        *) printf '%s\n' "$parent" >>"$parents" ;;
      esac
      parent=${parent%/*}
      level=$((level + 1))
    done
  done <"$list"
  xargs -0 -n 200 rmdir <"$list" 2>/dev/null
  remaining="$list.remaining"
  existing_paths_to_list "$list" "$remaining"
  batch_actuals "$list" "$remaining" 0
  [ "$REMAINING_COUNT" -gt 0 ] && ERRORS=$((ERRORS + REMAINING_COUNT))
  initial_removed=$ACTUAL_COUNT
  parent_count=0
  if [ -s "$parents" ]; then
    removed="$TMP_DIR/shared-empty-parents.removed"
    : >"$removed"
    awk '{p=$0; n=gsub("/", "/", p); print n "|" $0}' "$parents" | sort -t'|' -k1,1nr -k2,2r | cut -d'|' -f2- | \
      while IFS= read -r parent; do
        if rmdir "$parent" 2>/dev/null; then printf '%s\n' "$parent" >>"$removed"; fi
      done
    parent_count=$(wc -l <"$removed" 2>/dev/null | tr -d ' ')
    case "$parent_count" in ''|*[!0-9]*) parent_count=0 ;; esac
  fi
  actual_total=$((initial_removed + parent_count))
  EMPTY_DIRS=$((EMPTY_DIRS + actual_total))
  log_line "[批量清理][共享存储空目录] $initial_removed 个，连带空父目录 $parent_count 个，未清理 $REMAINING_COUNT 个"
  report_line cleaned low 共享存储空目录 "$actual_total" 0 /data/media
  [ "$REMAINING_COUNT" -gt 0 ] && report_line failed low 共享存储空目录 "$REMAINING_COUNT" 0 /data/media
  rm -f "$list" "$remaining" "$parents" "$removed"
}

is_protected_hidden_path() {
  case "$1" in
    */.git|*/.git/*|*/.ssh|*/.ssh/*|*/.termux|*/.termux/*|*/.config|*/.config/*|*/.local|*/.local/*|*/.obsidian|*/.obsidian/*|*/.android|*/.android/*|*/.vscode|*/.vscode/*|*/.gnupg|*/.gnupg/*) return 0 ;;
  esac
  return 1
}

hidden_dir_days() {
  value=$(awk -F'|' -v name="$1" '$1 == "dir" && $2 == name { print $3; exit }' "$HIDDEN_RULES" 2>/dev/null)
  case "$value" in ''|*[!0-9]*) return 1 ;; esac
  echo "$value"
}

run_hidden_junk() {
  [ -d /data/media ] && [ -f "$HIDDEN_RULES" ] || return 0
  HIDDEN_CONTEXT=1
  list="$TMP_DIR/hidden-dirs"
  if [ "$SHARED_MANIFEST_READY" = "1" ]; then
    cp -f "$SHARED_HIDDEN_DIRS_MANIFEST" "$list"
  else
    : >"$list"
    for direct_hidden in /data/media/[0-9]*/DCIM/.thumbnails /data/media/[0-9]*/Pictures/.thumbnails; do
      [ -d "$direct_hidden" ] && printf '%s\n' "$direct_hidden" >>"$list"
    done
    find /data/media -mindepth 2 -maxdepth 6 \
      \( -path '/data/media/[0-9]*/Android' -o -path '/data/media/[0-9]*/Android/*' \
         -o -path '/data/media/[0-9]*/DCIM' -o -path '/data/media/[0-9]*/Pictures' \
         -o -path '/data/media/[0-9]*/Movies' -o -path '/data/media/[0-9]*/Music' \
         -o -path '/data/media/[0-9]*/Download' -o -path '/data/media/[0-9]*/Documents' \) -prune -o \
      -type d -name '.*' -print 2>/dev/null >>"$list"
  fi
  while IFS= read -r hidden_dir || [ -n "$hidden_dir" ]; do
    [ -d "$hidden_dir" ] || continue
    [ -L "$hidden_dir" ] && continue
    is_protected_hidden_path "$hidden_dir" && { log_line "[跳过:隐藏配置] $hidden_dir"; continue; }
    name=${hidden_dir##*/}
    rule_days=$(hidden_dir_days "$name") || continue
    [ "$HIDDEN_DAYS" -gt "$rule_days" ] && rule_days=$HIDDEN_DAYS
    clean_dir "$hidden_dir" "$rule_days" "隐藏垃圾:$name" || { HIDDEN_CONTEXT=0; return $?; }
    if [ "$MODE" = "clean" ]; then
      case "$name" in
        .cache|.thumbnails|.thumbnail|.thumb|.tmp|.temp|.xlDownload)
          rm -f "$hidden_dir/.nomedia" 2>/dev/null
          ;;
      esac
    fi
    if [ -d "$hidden_dir" ] && [ ! -L "$hidden_dir" ] && [ -z "$(ls -A "$hidden_dir" 2>/dev/null)" ]; then
      if is_whitelisted "$hidden_dir"; then
        log_line "[跳过:白名单][隐藏空目录] $hidden_dir"
      elif [ "$MODE" = "clean" ]; then
        if rmdir "$hidden_dir" 2>/dev/null; then
          EMPTY_DIRS=$((EMPTY_DIRS + 1)); HIDDEN_ITEMS=$((HIDDEN_ITEMS + 1))
          log_line "[已清理][隐藏空目录] $hidden_dir"
        fi
      else
        EMPTY_DIRS=$((EMPTY_DIRS + 1)); HIDDEN_ITEMS=$((HIDDEN_ITEMS + 1))
        log_line "[可清理][隐藏空目录] $hidden_dir"
      fi
    fi
  done <"$list"

  list="$TMP_DIR/hidden-files"
  if [ "$SHARED_MANIFEST_READY" = "1" ]; then
    cp -f "$SHARED_HIDDEN_FILES_MANIFEST" "$list"
  elif [ "$HIDDEN_DAYS" -eq 0 ]; then
    find /data/media -mindepth 2 -maxdepth 6 \
      \( -path '/data/media/[0-9]*/Android' -o -path '/data/media/[0-9]*/Android/*' \
         -o -path '/data/media/[0-9]*/DCIM' -o -path '/data/media/[0-9]*/Pictures' \
         -o -path '/data/media/[0-9]*/Movies' -o -path '/data/media/[0-9]*/Music' \
         -o -path '/data/media/[0-9]*/Download' -o -path '/data/media/[0-9]*/Documents' \) -prune -o \
      -type f \( -name '.DS_Store' -o -name '._*' -o -name 'Thumbs.db' -o -name 'desktop.ini' -o -name '.directory' \) -print 2>/dev/null >"$list"
  else
    find /data/media -mindepth 2 -maxdepth 6 \
      \( -path '/data/media/[0-9]*/Android' -o -path '/data/media/[0-9]*/Android/*' \
         -o -path '/data/media/[0-9]*/DCIM' -o -path '/data/media/[0-9]*/Pictures' \
         -o -path '/data/media/[0-9]*/Movies' -o -path '/data/media/[0-9]*/Music' \
         -o -path '/data/media/[0-9]*/Download' -o -path '/data/media/[0-9]*/Documents' \) -prune -o \
      -type f \( -name '.DS_Store' -o -name '._*' -o -name 'Thumbs.db' -o -name 'desktop.ini' -o -name '.directory' \) -mtime "+$HIDDEN_DAYS" -print 2>/dev/null >"$list"
  fi
  CATEGORY="隐藏垃圾文件"
  while IFS= read -r hidden_file || [ -n "$hidden_file" ]; do
    is_protected_hidden_path "$hidden_file" && { log_line "[跳过:隐藏配置] $hidden_file"; continue; }
    handle_file "$hidden_file" regular || { HIDDEN_CONTEXT=0; return $?; }
  done <"$list"
  HIDDEN_CONTEXT=0
  return 0
}

# “碎片清理”指可识别的临时残留、诊断转储和中断下载片段，
# 不是对闪存做传统磁盘碎片整理。用户媒体与文档目录不参与通用匹配。
run_fragment_cleanup() {
  [ -d /data/media ] || return 0
  list="$TMP_DIR/fragments.nul"
  if [ "$SHARED_MANIFEST_READY" = "1" ]; then
    cp -f "$SHARED_FRAGMENT_MANIFEST" "$list"
  else
    : >"$list"

    for userdir in /data/media/[0-9]*; do
      [ -d "$userdir" ] || continue

    # 非媒体公共区域：日志、崩溃转储与临时文件，至少保留指定天数。
    find "$userdir" -mindepth 1 -maxdepth 4 \
      \( -path "$userdir/Android" -o -path "$userdir/DCIM" -o -path "$userdir/Pictures" \
         -o -path "$userdir/Movies" -o -path "$userdir/Music" -o -path "$userdir/Documents" \
         -o -path "$userdir/Download" -o -path "$userdir/Podcasts" -o -path "$userdir/Audiobooks" \
         -o -path "$userdir/Recordings" -o -path "$userdir/Fonts" -o -path "$userdir/Ringtones" \
         -o -path "$userdir/Alarms" -o -path "$userdir/Notifications" \) -prune -o \
      -type f -size "-${MAX_FILE_BYTES}c" -mtime "+$FRAGMENT_DAYS" \
      \( -iname '*.tmp' -o -iname '*.temp' -o -iname '*.tmf' \
         -o -iname '*.log' -o -iname '*.xlog' -o -iname '*.tlog' -o -iname '*.ulog' -o -iname '*.plog' \
         -o -iname '*.hprof' -o -iname '*.dmp' -o -iname '*.dump' -o -iname '*.trace' \
         -o -iname '*.traces' -o -iname '*.stacktrace' -o -iname 'hs_err_pid*.log' \) \
      -print0 2>/dev/null >>"$list"

    # 下载目录只匹配明确的中断下载后缀，避免把普通日志或用户临时文档误删。
      if [ -d "$userdir/Download" ]; then
        find "$userdir/Download" -mindepth 1 -maxdepth 4 -type f \
          -size "-${MAX_FILE_BYTES}c" -mtime "+$FRAGMENT_DAYS" \
          \( -iname '*.part' -o -iname '*.partial' -o -iname '*.crdownload' \
             -o -iname '*.filepart' -o -iname '*.download' -o -iname '*.opdownload' \) \
          -print0 2>/dev/null >>"$list"
      fi
    done
  fi

  filter_whitelist_list "$list"
  count=$(count_nul "$list")
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  [ "$count" -gt 0 ] || { rm -f "$list"; return 0; }

  estimated=$(bytes_from_list "$list")
  case "$estimated" in ''|*[!0-9]*) estimated=0 ;; esac
  if [ "$MODE" = "clean" ]; then
    err_file="$TMP_DIR/rm-fragments.err"
    if ! xargs -0 -n 200 rm -f -- <"$list" 2>"$err_file"; then
      reason=$(tail -n 1 "$err_file" 2>/dev/null)
      log_line "[部分未清理][残留碎片] ${reason:-系统拒绝删除部分文件}"
    fi
    rm -f "$err_file"
    remaining="$TMP_DIR/fragments.remaining.nul"
    : >"$remaining"
    while IFS= read -r -d '' fragment; do
      [ -f "$fragment" ] && printf '%s\0' "$fragment" >>"$remaining"
    done <"$list"
    remaining_count=$(count_nul "$remaining")
    case "$remaining_count" in ''|*[!0-9]*) remaining_count=0 ;; esac
    remaining_bytes=$(bytes_from_list "$remaining")
    case "$remaining_bytes" in ''|*[!0-9]*) remaining_bytes=0 ;; esac
    actual_count=$((count - remaining_count))
    actual_bytes=$(awk -v a="$estimated" -v b="$remaining_bytes" 'BEGIN {v=a-b; if (v < 0) v=0; printf "%.0f", v}')
    [ "$remaining_count" -gt 0 ] && ERRORS=$((ERRORS + remaining_count))
    log_line "[批量清理][残留碎片] $actual_count 个文件，约 $actual_bytes bytes，保留 ${FRAGMENT_DAYS} 天，未清理 $remaining_count 个"
    report_line cleaned low 残留碎片 "$actual_count" "$actual_bytes" "保留 ${FRAGMENT_DAYS} 天"
    [ "$remaining_count" -gt 0 ] && report_line failed low 残留碎片 "$remaining_count" "$remaining_bytes" "仍存在的碎片文件"
    rm -f "$remaining"
  else
    actual_count=$count
    actual_bytes=$estimated
    log_line "[批量扫描][残留碎片] $count 个文件，约 $estimated bytes，保留 ${FRAGMENT_DAYS} 天"
    report_line candidate low 残留碎片 "$count" "$estimated" "保留 ${FRAGMENT_DAYS} 天"
  fi
  FILES=$((FILES + actual_count))
  FRAGMENT_FILES=$((FRAGMENT_FILES + actual_count))
  add_bytes "$actual_bytes"
  rm -f "$list"
  return 0
}

run_custom_rules() {
  while IFS='|' read -r dir days extra || [ -n "$dir$days$extra" ]; do
    dir=$(printf '%s' "$dir" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    days=$(printf '%s' "$days" | sed 's/[[:space:]]//g')
    case "$dir" in ''|'#'*) continue ;; esac
    [ -n "$extra" ] && { log_line "[拒绝:格式错误] $dir"; continue; }
    case "$days" in ''|*[!0-9]*) log_line "[拒绝:天数错误] $dir"; continue ;; esac
    if is_allowed_custom_dir "$dir"; then
      clean_dir "${dir%/}" "$days" "自定义规则" || return $?
    else
      log_line "[拒绝:不安全路径] $dir"
    fi
  done <"$CUSTOM_RULES"
  return 0
}

if [ "$(get_bool enabled)" != "1" ]; then
  echo "模块功能已在配置中关闭"
  cleanup_lock
  trap - EXIT INT TERM
  exit 0
fi

MAX_MB=$(get_uint max_file_mb 256 1 4096)
MAX_FILE_BYTES=$(awk -v m="$MAX_MB" 'BEGIN {printf "%.0f", m * 1048576}')
APP_DAYS=$(get_uint app_cache_days 0 0 365)
EXT_DAYS=$(get_uint external_cache_days 0 0 365)
SYS_DAYS=$(get_uint system_logs_days 7 0 365)
OEM_DAYS=$(get_uint oem_logs_days 7 0 365)
EMPTY_DAYS=$(get_uint empty_file_days 0 0 365)
HIDDEN_DAYS=$(get_uint hidden_junk_days 0 0 365)
FRAGMENT_DAYS=$(get_uint fragment_days 7 1 365)
MAX_RUN_MINUTES=$(get_uint max_run_minutes 45 5 180)
MAX_RUN_SECONDS=$((MAX_RUN_MINUTES * 60))
CLEAN_EMPTY_FILES=$(get_bool clean_empty_files)
CLEAN_EMPTY_DIRS=$(get_bool clean_empty_dirs)
CLEAN_HIDDEN_JUNK=$(get_bool clean_hidden_junk)
CLEAN_FRAGMENTS=$(get_bool clean_fragments)
RUN_EMPTY=0
RUN_CACHE=0
RUN_RULES=0
RUN_FRAGMENT=0
case "$PROFILE" in
  all) RUN_EMPTY=1; RUN_CACHE=1; RUN_RULES=1; RUN_FRAGMENT=1 ;;
  empty) RUN_EMPTY=1 ;;
  cache) RUN_CACHE=1 ;;
  rules) RUN_RULES=1 ;;
  fragment) RUN_FRAGMENT=1 ;;
  corpse) ;;
esac
WHITELIST_PATHS=$(sed -n 's/[[:space:]]*$//; /^[[:space:]]*\($\|#\)/d; p' "$WHITELIST" 2>/dev/null)
if [ -n "$WHITELIST_PATHS" ]; then
  WHITELIST_ACTIVE=1
else
  WHITELIST_ACTIVE=0
fi

log_line "白泽 $REQUEST_MODE"
log_line "时间: $(date '+%Y-%m-%d %H:%M:%S')"
log_line "触发: $TRIGGER"
log_line "单文件上限: $MAX_MB MiB"
log_line "单次任务上限: $MAX_RUN_MINUTES 分钟"
log_line "----------------------------------------"

STOPPED=0
if [ "$PROFILE" = "all" ] && \
   { { [ "$RUN_EMPTY" = "1" ] && { [ "$CLEAN_EMPTY_FILES" = "1" ] || [ "$CLEAN_EMPTY_DIRS" = "1" ]; }; } || \
     { [ "$RUN_RULES" = "1" ] && [ "$CLEAN_HIDDEN_JUNK" = "1" ]; } || \
     { [ "$RUN_FRAGMENT" = "1" ] && [ "$CLEAN_FRAGMENTS" = "1" ]; }; }; then
  set_phase "一次扫描共享存储并分类"
  prepare_shared_manifests || STOPPED=1
fi
if [ "$STOPPED" = "0" ] && [ "$RUN_EMPTY" = "1" ] && [ "$CLEAN_EMPTY_FILES" = "1" ]; then
  set_phase "清理共享存储空文件"
  scan_shared_empty_files || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_EMPTY" = "1" ] && [ "$CLEAN_EMPTY_DIRS" = "1" ]; then
  set_phase "清理共享存储空目录"
  scan_shared_empty_dirs || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_CACHE" = "1" ] && [ "$(get_bool clean_app_cache)" = "1" ]; then
  set_phase "扫描应用内部缓存"
  scan_cache_roots "/data/user /data/user_de" "$APP_DAYS" "应用内部缓存" || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_CACHE" = "1" ] && [ "$(get_bool clean_external_cache)" = "1" ]; then
  set_phase "扫描外部应用缓存"
  scan_external_cache "$EXT_DAYS" || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_RULES" = "1" ] && [ "$(get_bool clean_app_rules)" = "1" ]; then
  set_phase "执行扩展应用规则"
  run_app_rules || STOPPED=1
  [ "$STOPPED" = "0" ] && run_external_rules || STOPPED=1
  [ "$STOPPED" = "0" ] && run_webview_cache_rules || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_RULES" = "1" ] && [ "$(get_bool clean_system_logs)" = "1" ]; then
  set_phase "扫描系统诊断日志"
  clean_dir /data/anr "$SYS_DAYS" "ANR日志" || STOPPED=1
  clean_dir /data/tombstones "$SYS_DAYS" "崩溃日志" || STOPPED=1
  clean_dir /data/vendor/tombstones "$SYS_DAYS" "厂商崩溃日志" || STOPPED=1
  clean_dir /data/system/dropbox "$SYS_DAYS" "系统DropBox日志" || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_RULES" = "1" ] && [ "$(get_bool clean_oem_logs)" = "1" ]; then
  set_phase "扫描厂商调试日志"
  for userdir in /data/media/[0-9]*; do
    [ -d "$userdir" ] || continue
    clean_dir "$userdir/MIUI/debug_log" "$OEM_DAYS" "HyperOS调试日志" || STOPPED=1
    clean_dir "$userdir/oplus/log" "$OEM_DAYS" "ColorOS调试日志" || STOPPED=1
  done
  clean_dir /data/oplus/log "$OEM_DAYS" "ColorOS系统日志" || STOPPED=1
  clean_dir /data/oppo/log "$OEM_DAYS" "ColorOS系统日志" || STOPPED=1
  clean_dir /data/vendor/oplus/log "$OEM_DAYS" "ColorOS厂商日志" || STOPPED=1
fi


if [ "$STOPPED" = "0" ] && [ "$RUN_RULES" = "1" ] && [ "$CLEAN_HIDDEN_JUNK" = "1" ]; then
  set_phase "扫描隐藏垃圾"
  run_hidden_junk || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_FRAGMENT" = "1" ] && [ "$CLEAN_FRAGMENTS" = "1" ]; then
  set_phase "扫描残留碎片（保留 ${FRAGMENT_DAYS} 天）"
  run_fragment_cleanup || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_RULES" = "1" ] && [ "$(get_bool clean_custom_rules)" = "1" ]; then
  set_phase "执行自定义规则"
  run_custom_rules || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$PROFILE" = "corpse" ]; then
  set_phase "扫描卸载应用残留"
  run_corpse_cleanup
  corpse_code=$?
  if [ "$corpse_code" -ne 0 ]; then
    if [ "$corpse_code" -eq 9 ]; then STOPPED=1; else FATAL_CODE=$corpse_code; fi
  fi
fi

if [ "$STOPPED" = "0" ] && [ "${FATAL_CODE:-0}" -eq 0 ] && [ "$DEEP_MODE" = "1" ]; then
  DEEP_RULE_SHA=$(deep_rules_sha256)
  DEEP_RULE_COUNT=$(awk '/^[[:space:]]*\//{n++} END{print n+0}' "$DEEP_RULES" 2>/dev/null)
  set_phase "执行深度规则（${DEEP_RULE_COUNT:-0} 条）"
  run_deep_rules
  deep_code=$?
  if [ "$deep_code" -ne 0 ]; then
    if [ "$deep_code" -eq 9 ]; then STOPPED=1; else FATAL_CODE=$deep_code; fi
  fi
fi

set_phase "整理结果"

END_EPOCH=$(date +%s)
ELAPSED=$((END_EPOCH - START_EPOCH))
SPACE=$(human_bytes "$BYTES")

if [ "${FATAL_CODE:-0}" -ne 0 ]; then
  RESULT="任务失败（代码 $FATAL_CODE）"
elif [ "$STOPPED" = "1" ]; then
  RESULT="${STOP_REASON:-任务已中断}"
elif [ "$MODE" = "scan" ]; then
  if [ "$DEEP_MODE" = "1" ]; then
    if [ "$PROTECTED_BYTES" -gt 0 ]; then
      deep_protected_summary="$(human_bytes "$PROTECTED_BYTES")"
    elif [ "$PROTECTED_ITEMS" -gt 0 ]; then
      deep_protected_summary="${PROTECTED_ITEMS} 项"
    else
      deep_protected_summary="0 项"
    fi
    RESULT="深度扫描完成，可清理 $SPACE，受保护 $deep_protected_summary"
  elif [ "$PROFILE" = "fragment" ]; then
    RESULT="碎片扫描完成，可清理 $SPACE"
  else
    if [ "$PROFILE" = "corpse" ]; then RESULT="卸载残留扫描完成，可清理 $SPACE"; else RESULT="扫描完成，可清理 $SPACE"; fi
  fi
else
  case "$PROFILE" in
    cache) RESULT="缓存清理完成，释放 $SPACE" ;;
    empty) RESULT="空文件清理完成，释放 $SPACE" ;;
    rules) RESULT="规则清理完成，释放 $SPACE" ;;
    fragment) RESULT="碎片清理完成，释放 $SPACE" ;;
    deep)
      if [ "$PROTECTED_BYTES" -gt 0 ]; then
        deep_protected_summary="$(human_bytes "$PROTECTED_BYTES")"
      elif [ "$PROTECTED_ITEMS" -gt 0 ]; then
        deep_protected_summary="${PROTECTED_ITEMS} 项"
      else
        deep_protected_summary="0 项"
      fi
      RESULT="深度清理完成，释放 $SPACE，受保护 $deep_protected_summary"
      ;;
    corpse) RESULT="卸载残留清理完成，释放 $SPACE" ;;
    *) RESULT="清理完成，释放 $SPACE" ;;
  esac
  [ "${FATAL_CODE:-0}" -eq 0 ] && date +%s >"$STATE_DIR/last_run.epoch"
fi

log_line "----------------------------------------"
log_line "$RESULT"
TOTAL_FILES=$((FILES + EMPTY_FILES))
log_line "文件总计: $FILES，其中碎片: $FRAGMENT_FILES，空文件: $EMPTY_FILES，空目录: $EMPTY_DIRS，隐藏垃圾: $HIDDEN_ITEMS，受保护: $PROTECTED_ITEMS，跳过: $SKIPPED，未清理: $ERRORS，耗时: ${ELAPSED}s"

{
  echo "mode=$REQUEST_MODE"
  echo "time=$(date '+%Y-%m-%d %H:%M:%S')"
  echo "files=$TOTAL_FILES"
  echo "regular_files=$FILES"
  echo "empty_files=$EMPTY_FILES"
  echo "empty_dirs=$EMPTY_DIRS"
  echo "hidden_items=$HIDDEN_ITEMS"
  echo "fragment_files=$FRAGMENT_FILES"
  echo "bytes=$BYTES"
  echo "skipped=$SKIPPED"
  echo "errors=$ERRORS"
  echo "protected_items=$PROTECTED_ITEMS"
  echo "protected_bytes=$PROTECTED_BYTES"
  echo "risk_low=$RISK_LOW"
  echo "risk_medium=$RISK_MEDIUM"
  echo "risk_high=$RISK_HIGH"
  echo "risk_critical=$RISK_CRITICAL"
  echo "elapsed=$ELAPSED"
  echo "result=$RESULT"
} >"$STATE_DIR/latest.env"

if [ "$REQUEST_MODE" = "deep-scan" ] && [ "$STOPPED" = "0" ] && [ "${FATAL_CODE:-0}" -eq 0 ]; then
  chmod 0600 "$DEEP_SCAN_MANIFEST_TMP" 2>/dev/null
  mv -f "$DEEP_SCAN_MANIFEST_TMP" "$DEEP_SCAN_TARGETS"
  { echo "epoch=$(date +%s)"; echo "bytes=$BYTES"; echo "items=$((FILES + EMPTY_DIRS))"; echo "rules_sha=$DEEP_RULE_SHA"; } >"$DEEP_SCAN_STATE"
fi
if [ "$REQUEST_MODE" = "corpse-scan" ] && [ "$STOPPED" = "0" ] && [ "${FATAL_CODE:-0}" -eq 0 ]; then
  chmod 0600 "$CORPSE_SCAN_MANIFEST_TMP" 2>/dev/null
  mv -f "$CORPSE_SCAN_MANIFEST_TMP" "$CORPSE_SCAN_TARGETS"
  { echo "epoch=$(date +%s)"; echo "bytes=$BYTES"; echo "items=$((FILES + EMPTY_DIRS))"; } >"$CORPSE_SCAN_STATE"
fi
if [ "$REQUEST_MODE" = "deep-clean" ] && [ "$STOPPED" = "0" ] && [ "${FATAL_CODE:-0}" -eq 0 ]; then
  case "$TRIGGER" in scheduled:*|daily:*) ;; *) rm -f "$DEEP_SCAN_STATE" "$DEEP_SCAN_TARGETS" ;; esac
fi
if [ "$REQUEST_MODE" = "corpse-clean" ] && [ "$STOPPED" = "0" ] && [ "${FATAL_CODE:-0}" -eq 0 ]; then
  rm -f "$CORPSE_SCAN_STATE" "$CORPSE_SCAN_TARGETS"
fi
cp -f "$REPORT_FILE" "$LATEST_REPORT"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$REQUEST_MODE" "$BYTES" "$TOTAL_FILES" "$EMPTY_DIRS" "$ERRORS" "$RESULT" >>"$HISTORY_FILE"
tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"
update_cumulative_totals
update_module_description
send_completion_notification
cp -f "$LOG_FILE" "$LATEST_LOG"

# 保留 latest 加最近 10 份历史日志、最近 20 份历史审计报告，避免模块自身制造垃圾。
ls -1t "$LOG_DIR"/*.log 2>/dev/null | awk 'NR>11' | while IFS= read -r old; do rm -f "$old"; done
ls -1t "$REPORT_DIR"/*.tsv 2>/dev/null | grep -v '/latest.tsv$' | awk 'NR>20' | while IFS= read -r old; do rm -f "$old"; done

echo "$RESULT"
echo "文件: $TOTAL_FILES（碎片 $FRAGMENT_FILES，空文件 $EMPTY_FILES）| 空目录: $EMPTY_DIRS | 隐藏垃圾: $HIDDEN_ITEMS | 受保护: $PROTECTED_ITEMS | 跳过: $SKIPPED | 未清理: $ERRORS | 耗时: ${ELAPSED}s"

cleanup_lock
trap - EXIT INT TERM
if [ "${FATAL_CODE:-0}" -ne 0 ]; then exit "$FATAL_CODE"; fi
[ "$STOPPED" = "1" ] && exit 9
exit 0
