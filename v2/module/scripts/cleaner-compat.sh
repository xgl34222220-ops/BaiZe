#!/system/bin/sh
# 兼容清理引擎（v1）。当 v2 原生引擎不可用时由 v2/module/scripts/cleaner.sh 退回到这里，
# 打包为 cleaner-compat.sh。
#
# STATE_DIR 与 MODULE_TAG 通过环境变量注入，默认值即 v2 的取值。
# 此前打包脚本靠构建期 sed 改写这两处，源码与产物行为不一致且难以本地复现。
MODDIR=${0%/*}
# Keep module data at the root; implementations live under scripts/.
case "$MODDIR" in */scripts) MODDIR=${MODDIR%/scripts} ;; esac
SCRIPTDIR="$MODDIR"
[ ! -d "$MODDIR/scripts" ] || SCRIPTDIR="$MODDIR/scripts"
# Legacy direct entry must use the same original per-file review as the module.
# Route before locks, defaults, package discovery or any target traversal.
case "${1:-scan}" in
  deep-scan)
    [ -f "$SCRIPTDIR/deep-scan-manifest.sh" ] || { echo "深度原始清单扫描组件缺失，未执行" >&2; exit 8; }
    exec sh "$SCRIPTDIR/deep-scan-manifest.sh" "$@"
    ;;
  deep-clean)
    [ -f "$SCRIPTDIR/deep-manifest-clean.sh" ] || { echo "深度原始清单消费组件缺失，未执行" >&2; exit 8; }
    exec sh "$SCRIPTDIR/deep-manifest-clean.sh" "$@"
    ;;
  corpse-scan)
    [ -f "$SCRIPTDIR/one-pass-scan.sh" ] || { echo "残留清单扫描组件缺失，未执行" >&2; exit 8; }
    exec sh "$SCRIPTDIR/one-pass-scan.sh" "$@"
    ;;
  corpse-clean)
    [ -f "$SCRIPTDIR/profile-cleaner.sh" ] || { echo "残留原始清单消费组件缺失，未执行" >&2; exit 8; }
    exec sh "$SCRIPTDIR/profile-cleaner.sh" "$@"
    ;;
esac
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
# Capture a task's refresh identity once and keep records outside temporary lanes.
[ -f "$SCRIPTDIR/cleanup-media-queue.sh" ] || { echo "媒体刷新记录组件缺失，未开始删除" >&2; exit 8; }
. "$SCRIPTDIR/cleanup-media-queue.sh"
baize_cleanup_media_init
# 进程匹配用的模块目录名，v1 独立安装时为 safesweep。
MODULE_TAG=${BAIZE_MODULE_TAG:-baize_v2}
CONFIG="$STATE_DIR/config.conf"
WHITELIST="$STATE_DIR/whitelist.conf"
PACKAGE_WHITELIST=${BAIZE_PACKAGE_WHITELIST:-$STATE_DIR/native-cache-packages.conf}
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
APP_DETAILS="$REPORT_DIR/apps-latest.tsv"
APP_ITEMS="$REPORT_DIR/app-items-latest.tsv"
HISTORY_FILE="$STATE_DIR/history.tsv"
APK_SCAN_STATE="$STATE_DIR/apk_scan.env"
APK_SCAN_TARGETS="$STATE_DIR/apk_scan.targets"

REQUEST_MODE=${1:-scan}
DEEP_MODE=0
PROFILE=all
case "$REQUEST_MODE" in
  cache-clean) MODE=clean; PROFILE=cache ;;
  empty-clean) MODE=clean; PROFILE=empty ;;
  rules-clean) MODE=clean; PROFILE=rules ;;
  fragment-scan) MODE=scan; PROFILE=fragment ;;
  fragment-clean) MODE=clean; PROFILE=fragment ;;
  apk-scan) MODE=scan; PROFILE=apk ;;
  apk-clean) MODE=clean; PROFILE=apk ;;
  scan|clean) MODE=$REQUEST_MODE ;;
  *) echo "用法: cleaner.sh scan|clean|cache-clean|empty-clean|rules-clean|fragment-scan|fragment-clean|deep-scan|deep-clean|corpse-scan|corpse-clean|apk-scan|apk-clean [trigger]"; exit 2 ;;
esac
TRIGGER=${2:-manual}

mkdir -p "$LOG_DIR" "$REPORT_DIR"
[ -f "$CONFIG" ] || cp -f "$MODDIR/config/default.conf" "$CONFIG"
if [ "$MODE" = clean ]; then
  [ -f "$WHITELIST" ] && [ -r "$WHITELIST" ] || { echo "白名单缺失或不可读，未开始删除" >&2; exit 7; }
else
  [ -f "$WHITELIST" ] || cp -f "$MODDIR/config/whitelist.conf" "$WHITELIST"
fi
[ -f "$CUSTOM_RULES" ] || cp -f "$MODDIR/config/custom.rules" "$CUSTOM_RULES"

pid_is_safesweep() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in
    *"$MODULE_TAG"*cleaner.sh*|*"$MODULE_TAG"*job-runner.sh*|*"$MODULE_TAG"*webctl.sh*|*apk-scanner.sh*|*apk-scanner.sh*) return 0 ;;
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
PROCESSED_PATHS="$TMP_DIR/processed-paths"
: >"$PROCESSED_PATHS"
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
  progress_current=${2:-0}
  progress_total=${3:-0}
  current_path=${4:-}
  current_path=$(printf '%s' "$current_path" | tr '\r\n' '  ')
  tmp="$RUNNING_FILE.tmp.$$"
  {
    printf 'mode=%s\n' "$REQUEST_MODE"
    printf 'phase=%s\n' "$phase"
    printf 'started=%s\n' "$START_EPOCH"
    printf 'progress_current=%s\n' "$progress_current"
    printf 'progress_total=%s\n' "$progress_total"
    printf 'current_path=%s\n' "$current_path"
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
CHANGED_FILES=0
MISSING_FILES=0
COMPAT_DELETE_SEQ=0
CATEGORY=""
HIDDEN_CONTEXT=0
LIST_SEQ=0
PROTECTED_ITEMS=0
PROTECTED_BYTES=0
RISK_LOW=0
RISK_MEDIUM=0
RISK_HIGH=0
RISK_CRITICAL=0
STOP_REASON=""
DEEP_RULE_SHA=""
DEEP_COVER_TARGET=0
DEEP_COVER_MODE=""
DEEP_SLOW_ITEMS=0
DEEP_MOUNT_ITEMS=0
DEEP_TRUNCATED=0
DEEP_RULE_PARSE_SECONDS=0
DEEP_STAGE_SECONDS=0
DEEP_SLOWEST_SECONDS=0
DEEP_SLOWEST_PATH=""
CACHE_SLOW_DIRS=0
CACHE_TRUNCATED=0
COMMAND_TIMEOUT_MODE=""
WATCHDOG_SEQ=0
DEEP_PROGRESS_CURRENT=0
DEEP_PROGRESS_TOTAL=0
DEEP_CURRENT_PATH=""
REPORT_FILE="$REPORT_DIR/$STAMP-$REQUEST_MODE.tsv"
RULE_SEEN_FILE="$TMP_DIR/rule-targets.seen"
: >"$RULE_SEEN_FILE"
printf 'action\trisk\tcategory\titems\tbytes\tpath\n' >"$REPORT_FILE"
printf 'package\tcategory\tfiles\tbytes\n' >"$APP_DETAILS"
printf 'package\tcategory\tfiles\tbytes\terrors\tsample_path\n' >"$APP_ITEMS"
set_phase "准备扫描"

get_value() {
  config_value=""
  while IFS= read -r config_line || [ -n "$config_line" ]; do
    case "$config_line" in "$1="*) config_value=${config_line#*=} ;; esac
  done <"$CONFIG"
  printf '%s\n' "$config_value"
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

canonical_rule_path() {
  target=$1
  if command -v readlink >/dev/null 2>&1; then
    resolved=$(readlink -f -- "$target" 2>/dev/null) && [ -n "$resolved" ] && { printf '%s\n' "$resolved"; return 0; }
  fi
  if command -v realpath >/dev/null 2>&1; then
    resolved=$(realpath -- "$target" 2>/dev/null) && [ -n "$resolved" ] && { printf '%s\n' "$resolved"; return 0; }
  fi
  return 1
}

resolve_rule_target() {
  base=$1
  target=$2
  [ -e "$target" ] || return 1
  [ -L "$target" ] && return 1
  base_real=$(canonical_rule_path "$base") || return 1
  target_real=$(canonical_rule_path "$target") || return 1
  case "$target_real" in
    "$base_real"/*) printf '%s\n' "$target_real"; return 0 ;;
  esac
  return 1
}

rule_target_once() {
  target=$1
  [ -n "$target" ] || return 1
  grep -Fqx -- "$target" "$RULE_SEEN_FILE" 2>/dev/null && return 1
  printf '%s\n' "$target" >>"$RULE_SEEN_FILE"
  return 0
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

valid_package_name() {
  case "$1" in
    ''|[!A-Za-z0-9]*|*[!A-Za-z0-9._-]*) return 1 ;;
    ?*.*?) return 0 ;;
  esac
  return 1
}

package_from_target() {
  target=${1%/}
  case "$target" in
    /data/user/[0-9]*/*/*)
      rest=${target#/data/user/}; rest=${rest#*/}; printf '%s\n' "${rest%%/*}"; return 0 ;;
    /data/user_de/[0-9]*/*/*)
      rest=${target#/data/user_de/}; rest=${rest#*/}; printf '%s\n' "${rest%%/*}"; return 0 ;;
    /data/media/[0-9]*/Android/data/*/*)
      rest=${target#*/Android/data/}; printf '%s\n' "${rest%%/*}"; return 0 ;;
  esac
  return 1
}

package_for_detail() {
  target=$1
  category=$2
  candidate=""
  case "$category" in
    应用扩展规则:*|外部应用扩展规则:*|WebView缓存:*) candidate=${category##*:} ;;
  esac
  if valid_package_name "$candidate"; then
    printf '%s\n' "$candidate"
    return 0
  fi
  candidate=$(package_from_target "$target" 2>/dev/null)
  valid_package_name "$candidate" || return 1
  printf '%s\n' "$candidate"
}

append_app_detail() {
  package=$1
  category=$2
  files=$3
  bytes=$4
  errors=${5:-0}
  sample_path=${6:-}
  valid_package_name "$package" || return 0
  case "$files" in ''|*[!0-9]*) files=0 ;; esac
  case "$bytes" in ''|*[!0-9]*) bytes=0 ;; esac
  case "$errors" in ''|*[!0-9]*) errors=0 ;; esac
  [ "$files" -gt 0 ] || [ "$bytes" -gt 0 ] || [ "$errors" -gt 0 ] || return 0
  package=$(sanitize_report_field "$package")
  category=$(sanitize_report_field "$category")
  sample_path=$(sanitize_report_field "$sample_path")
  printf '%s\t%s\t%s\t%s\n' "$package" "$category" "$files" "$bytes" >>"$APP_DETAILS"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$package" "$category" "$files" "$bytes" "$errors" "$sample_path" >>"$APP_ITEMS"
}

record_app_detail_for() {
  target=$1
  category=$2
  files=$3
  bytes=$4
  errors=${5:-0}
  sample_path=${6:-$target}
  package=$(package_for_detail "$target" "$category" 2>/dev/null) || return 0
  append_app_detail "$package" "$category" "$files" "$bytes" "$errors" "$sample_path"
}

should_stop() {
  if [ -f "$STATE_DIR/stop" ]; then
    STOP_REASON="已收到停止请求"
    return 0
  fi
  return 1
}

first_nul_path() {
  source_list=$1
  while IFS= read -r -d '' candidate; do
    printf '%s\n' "$candidate"
    return 0
  done <"$source_list"
  return 1
}

# Discovery and policy stay in this script. The native helper snapshots and
# deletes only selected paths; a missing helper fails closed for clean tasks.
COMPAT_FILTER_ENGINE=""
RULE_SCAN_ENGINE=""
if [ -f "$SCRIPTDIR/abi-resolve.sh" ]; then
  . "$SCRIPTDIR/abi-resolve.sh"
  COMPAT_FILTER_ENGINE=$(baize_resolve_engine "$MODDIR" baize_compat_filter 2>/dev/null) || COMPAT_FILTER_ENGINE=""
  RULE_SCAN_ENGINE=$(baize_resolve_engine "$MODDIR" baize_engine 2>/dev/null) || RULE_SCAN_ENGINE=""
fi


# Kept separately: failure of optional de-duplication must not disable identity
# checking. This boundary is created before any candidate collection starts.
COMPAT_SAFE_ENGINE=$COMPAT_FILTER_ENGINE
COMPAT_BOUNDARY="$TMP_DIR/compat-boundary"
COMPAT_BOUNDARY_READY=0
COMPAT_BOUNDARY_ATTEMPTED=0
compat_begin_collection() {
  [ "$MODE" = "clean" ] || return 0
  [ "$COMPAT_BOUNDARY_ATTEMPTED" = "0" ] || return 0
  COMPAT_BOUNDARY_ATTEMPTED=1
  if [ -n "$COMPAT_SAFE_ENGINE" ]; then
    "$COMPAT_SAFE_ENGINE" --begin "$COMPAT_BOUNDARY" && COMPAT_BOUNDARY_READY=1
  fi
  return 0
}


compat_summary_uint() {
  # A helper stopped by the watchdog may have only committed progress records.
  # Each terminated record holds all counters together, so partial writes cannot
  # mix fields from different operations. Final fields override this fallback.
  awk -F= -v key="$2" '
    $1 == "progress" && $2 ~ /^[0-9]+ [0-9]+ [0-9]+ [0-9]+ [0-9]+ [0-9]+ end$/ {
      split($2, values, " ")
      index_by_key["cleaned"]=1; index_by_key["bytes"]=2; index_by_key["changed"]=3
      index_by_key["missing"]=4; index_by_key["errors"]=5; index_by_key["processed"]=6
      if (key in index_by_key) { value=values[index_by_key[key]]; found=1 }
    }
    $1 == key && $2 ~ /^[0-9]+$/ { value=$2; found=1 }
    END { print found ? value : 0 }
  ' "$1" 2>/dev/null
}

compat_delete_list() {
  compat_list=$1
  compat_kind=${2:-file}
  compat_max_bytes=${3:-$MAX_FILE_BYTES}
  ACTUAL_COUNT=0; ACTUAL_BYTES=0; REMAINING_COUNT=0; REMAINING_BYTES=0
  [ -s "$compat_list" ] || return 0
  should_stop && return 9
  COMPAT_DELETE_SEQ=$((COMPAT_DELETE_SEQ + 1))
  compat_snapshot="$TMP_DIR/compat-delete.$COMPAT_DELETE_SEQ.snapshot"
  compat_summary="$TMP_DIR/compat-delete.$COMPAT_DELETE_SEQ.env"
  # Public integration contract: NUL paths, only successful native unlinkat.
  # Consume before cleanup_lock; never infer success from missing paths.
  COMPAT_DELETED_NUL="$TMP_DIR/compat-deleted.$COMPAT_DELETE_SEQ.nul"
  compat_code=5
  if [ "$COMPAT_BOUNDARY_READY" = "1" ] && [ -n "$COMPAT_SAFE_ENGINE" ]; then
    run_limited_command 30 "$COMPAT_SAFE_ENGINE" --snapshot "$compat_list" "$compat_snapshot" \
      "$COMPAT_BOUNDARY" "$compat_kind" "$STATE_DIR/stop" "$compat_max_bytes"
    compat_code=$?
    if [ "$compat_code" -eq 0 ]; then
      # The next begin publishes the previous batch, after callers consumed its NUL list.
      # Final cleanup publishes the last batch. A live task's .building is never claimed.
      if baize_cleanup_media_begin; then
        COMPAT_DELETED_NUL="$BAIZE_CLEANUP_DELETED_NUL"
      else
        compat_code=5
      fi
    fi
    if [ "$compat_code" -eq 0 ]; then
      run_limited_command 30 "$COMPAT_SAFE_ENGINE" --delete "$compat_snapshot" "$compat_summary" \
        "$COMPAT_DELETED_NUL" "$STATE_DIR/stop"
      compat_code=$?
    fi
  fi
  case "$compat_code" in
    0|8|9) ;;
    *) BAIZE_CLEANUP_MEDIA_UNCONFIRMED=1 ;;
  esac
  if [ -f "$compat_summary" ] && grep -qx 'schema=compat-delete-v1' "$compat_summary"; then
    ACTUAL_COUNT=$(compat_summary_uint "$compat_summary" cleaned)
    ACTUAL_BYTES=$(compat_summary_uint "$compat_summary" bytes)
    REMAINING_COUNT=$(compat_summary_uint "$compat_summary" errors)
    compat_changed=$(compat_summary_uint "$compat_summary" changed)
    compat_missing=$(compat_summary_uint "$compat_summary" missing)
    CHANGED_FILES=$((CHANGED_FILES + compat_changed))
    MISSING_FILES=$((MISSING_FILES + compat_missing))
    PROTECTED_ITEMS=$((PROTECTED_ITEMS + compat_changed))
    SKIPPED=$((SKIPPED + compat_changed + compat_missing))
    [ "$compat_changed" -eq 0 ] || report_line protected changed "$CATEGORY" "$compat_changed" 0 "候选状态已变化"
    [ "$compat_missing" -eq 0 ] || report_line skipped missing "$CATEGORY" "$compat_missing" 0 "候选已不存在"
  elif [ "$compat_code" -eq 0 ]; then
    compat_code=5
  fi
  if [ "$compat_code" -ne 0 ] && [ "$compat_code" -ne 9 ] && [ "$REMAINING_COUNT" -eq 0 ]; then
    REMAINING_COUNT=1
  fi
  ERRORS=$((ERRORS + REMAINING_COUNT))
  if [ "$REMAINING_COUNT" -gt 0 ]; then
    log_line "[清理未完成][$CATEGORY] 身份校验或删除失败（代码 $compat_code），未确认删除的路径已保留"
  fi
  if [ "$compat_code" -eq 9 ]; then
    STOPPED=1
    STOP_REASON="已收到停止请求"
  fi
  return 0
}

compat_collection_failed() {
  ERRORS=$((ERRORS + 1))
  PROTECTED_ITEMS=$((PROTECTED_ITEMS + 1))
  log_line "[扫描未完成][$CATEGORY] 未消费本次部分候选列表"
  report_line protected incomplete "$CATEGORY" 1 0 "$1"
}

filter_processed_list() {
  source_list=$1
  [ -s "$source_list" ] || return 0
  should_stop && return 9
  unique_list="$source_list.unique"
  if [ -n "$COMPAT_FILTER_ENGINE" ]; then
    next_seen="$source_list.seen"
    rm -f "$unique_list" "$next_seen"
    run_limited_command 15 "$COMPAT_FILTER_ENGINE" "$source_list" "$PROCESSED_PATHS" \
      "$unique_list" "$next_seen" "$STATE_DIR/stop"
    filter_code=$?
    if [ "$filter_code" -eq 0 ]; then
      should_stop && { rm -f "$unique_list" "$next_seen"; return 9; }
      mv -f "$next_seen" "$PROCESSED_PATHS" && mv -f "$unique_list" "$source_list" && return 0
      STOP_REASON="无法提交清理候选列表"
      return 9
    fi
    rm -f "$unique_list" "$next_seen"
    should_stop && return 9
    [ "$filter_code" -eq 9 ] && return 9
    COMPAT_FILTER_ENGINE=""
    log_line "[兼容过滤] 原生列表过滤不可用（代码 $filter_code），继续使用兼容过滤"
  fi
  : >"$unique_list"
  while IFS= read -r -d '' candidate; do
    should_stop && { rm -f "$unique_list"; return 9; }
    [ -n "$candidate" ] || continue
    canonical=$(canonical_rule_path "$candidate" 2>/dev/null)
    [ -n "$canonical" ] || canonical=$candidate
    key=$(printf '%s' "$canonical" | tr '\r\n' '  ')
    grep -Fqx -- "$key" "$PROCESSED_PATHS" 2>/dev/null && continue
    printf '%s\n' "$key" >>"$PROCESSED_PATHS"
    printf '%s\0' "$candidate" >>"$unique_list"
  done <"$source_list"
  mv -f "$unique_list" "$source_list"
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
  # App-triggered tasks use the native Android notification channel, which is considerably more
  # reliable on MIUI/HyperOS/ColorOS. Background scheduler jobs still use notify.sh.
  [ "$TRIGGER" = "app" ] && return 0
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
      *) title="白泽清理完成" ;;
    esac
  fi
  [ "$ERRORS" -gt 0 ] && title="$title（${ERRORS}项未清理）"
  short="$RESULT"
  total_space=$(human_bytes "$CUM_BYTES")
  body="$RESULT · 文件 $FILES · 碎片 $FRAGMENT_FILES · 空文件 $EMPTY_FILES · 空目录 $EMPTY_DIRS · 受保护 $PROTECTED_ITEMS · 未清理 $ERRORS · 耗时 ${ELAPSED}秒；累计清理 $total_space（$CUM_RUNS 次）"
  notify_result=$(sh "$SCRIPTDIR/notify.sh" "$title" "$body" "$short" "baize-$PROFILE" 2>&1)
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
  if [ "$MODE" != "clean" ]; then
    [ -f "$file" ] || return 0
    [ -L "$file" ] && return 0
  fi
  should_stop && return 9

  if is_whitelisted "$file"; then
    SKIPPED=$((SKIPPED + 1))
    log_line "[跳过:白名单][$CATEGORY] $file"
    return 0
  fi

  size=$(stat -c %s "$file" 2>/dev/null)
  case "$size" in ''|*[!0-9]*) size=0 ;; esac
  if [ "$kind" = "empty" ]; then
    [ "$size" = "0" ] || { PROTECTED_ITEMS=$((PROTECTED_ITEMS + 1)); return 0; }
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
    LIST_SEQ=$((LIST_SEQ + 1))
    single_list="$TMP_DIR/single.$LIST_SEQ.nul"
    printf '%s\0' "$file" >"$single_list"
    single_kind=file
    [ "$kind" != "empty" ] || single_kind=empty
    compat_delete_list "$single_list" "$single_kind" || return $?
    rm -f "$single_list"
    if [ "$kind" = "empty" ]; then EMPTY_FILES=$((EMPTY_FILES + ACTUAL_COUNT)); else FILES=$((FILES + ACTUAL_COUNT)); fi
    [ "$HIDDEN_CONTEXT" = "1" ] && HIDDEN_ITEMS=$((HIDDEN_ITEMS + ACTUAL_COUNT))
    add_bytes "$ACTUAL_BYTES"
    [ "$ACTUAL_COUNT" -eq 0 ] || report_line cleaned low "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES" "$file"
    [ "$REMAINING_COUNT" -eq 0 ] || report_line failed low "$CATEGORY" "$REMAINING_COUNT" 0 "$file"

  else
    if [ "$kind" = "empty" ]; then EMPTY_FILES=$((EMPTY_FILES + 1)); else FILES=$((FILES + 1)); fi
    [ "$HIDDEN_CONTEXT" = "1" ] && HIDDEN_ITEMS=$((HIDDEN_ITEMS + 1))
    add_bytes "$size"
    log_line "[可清理][$CATEGORY] $file ($size bytes)"
    report_line candidate low "$CATEGORY" 1 "$size" "$file"
  fi
}

ensure_timeout_runtime() {
  [ -n "$COMMAND_TIMEOUT_MODE" ] && return 0
  COMMAND_TIMEOUT_MODE=none
  if command -v timeout >/dev/null 2>&1; then
    COMMAND_TIMEOUT_MODE=timeout
  elif command -v toybox >/dev/null 2>&1 && toybox timeout 1 true >/dev/null 2>&1; then
    COMMAND_TIMEOUT_MODE=toybox
  elif command -v busybox >/dev/null 2>&1 && busybox timeout 1 true >/dev/null 2>&1; then
    COMMAND_TIMEOUT_MODE=busybox
  fi
}

run_with_watchdog() {
  seconds=$1
  shift
  WATCHDOG_SEQ=$((WATCHDOG_SEQ + 1))
  marker="$TMP_DIR/watchdog.$$.${WATCHDOG_SEQ}"
  rm -f "$marker"
  "$@" &
  command_pid=$!
  (
    sleep "$seconds"
    if kill -0 "$command_pid" 2>/dev/null; then
      : >"$marker"
      kill -TERM "$command_pid" 2>/dev/null || true
      sleep 1
      kill -KILL "$command_pid" 2>/dev/null || true
    fi
  ) &
  watchdog_pid=$!
  wait "$command_pid"
  command_code=$?
  kill "$watchdog_pid" 2>/dev/null || true
  wait "$watchdog_pid" 2>/dev/null || true
  if [ -f "$marker" ]; then
    rm -f "$marker"
    return 124
  fi
  rm -f "$marker"
  return "$command_code"
}

run_limited_command() {
  seconds=$1
  shift
  ensure_timeout_runtime
  case "$COMMAND_TIMEOUT_MODE" in
    timeout) timeout "$seconds" "$@" ;;
    toybox) toybox timeout "$seconds" "$@" ;;
    busybox) busybox timeout "$seconds" "$@" ;;
    *) run_with_watchdog "$seconds" "$@" ;;
  esac
}

count_nul() {
  [ -s "$1" ] || { printf '0\n'; return; }
  tr -cd '\000' <"$1" | wc -c | tr -d ' '
}

bytes_from_list() {
  [ -s "$1" ] || { echo 0; return; }
  xargs -0 du -k <"$1" 2>/dev/null | awk '{sum += $1} END {printf "%.0f", sum * 1024}'
}

filter_whitelist_list() {
  source_list=$1
  [ -s "$source_list" ] || return 0
  [ "$WHITELIST_ACTIVE" = "1" ] || return 0
  filtered="$source_list.filtered"
  : >"$filtered"
  while IFS= read -r -d '' candidate; do
    should_stop && { rm -f "$filtered"; return 9; }
    if is_whitelisted "$candidate" || deep_conflicts_whitelist "$candidate"; then
      SKIPPED=$((SKIPPED + 1))
    else
      printf '%s\0' "$candidate" >>"$filtered"
    fi
  done <"$source_list"
  mv -f "$filtered" "$source_list"
}

# 缓存目录按批次交给 find：正常设备只需少量进程，异常大目录则会被单独定位并限时跳过。
run_cache_find() {
  cache_seconds=$1
  cache_days=$2
  cache_output=$3
  shift 3
  if [ "$cache_days" -eq 0 ]; then
    if [ "$CLEAN_EMPTY_FILES" = "1" ]; then
      run_limited_command "$cache_seconds" find "$@" -mindepth 1 -type f -size "-${MAX_FILE_BYTES}c" \
        ! -name '.nomedia' ! -name '.keep' ! -name '.gitkeep' ! -name '.placeholder' ! -name '*.lock' \
        -print0 >"$cache_output" 2>/dev/null
    else
      run_limited_command "$cache_seconds" find "$@" -mindepth 1 -type f -size +0c -size "-${MAX_FILE_BYTES}c" -print0 >"$cache_output" 2>/dev/null
    fi
  elif [ "$CLEAN_EMPTY_FILES" = "1" ]; then
    run_limited_command "$cache_seconds" find "$@" -mindepth 1 -type f -size "-${MAX_FILE_BYTES}c" -mtime "+$cache_days" \
      ! -name '.nomedia' ! -name '.keep' ! -name '.gitkeep' ! -name '.placeholder' ! -name '*.lock' \
      -print0 >"$cache_output" 2>/dev/null
  else
    run_limited_command "$cache_seconds" find "$@" -mindepth 1 -type f -size +0c -size "-${MAX_FILE_BYTES}c" -mtime "+$cache_days" -print0 >"$cache_output" 2>/dev/null
  fi
}

process_cache_chunk() {
  cache_chunk_file=$1
  cache_days=$2
  cache_target_list=$3
  cache_category=$4
  cache_done=$5
  cache_total=$6
  set --
  while IFS= read -r cache_dir || [ -n "$cache_dir" ]; do
    [ -d "$cache_dir" ] && [ ! -L "$cache_dir" ] && set -- "$@" "$cache_dir"
  done <"$cache_chunk_file"
  [ "$#" -gt 0 ] || return 0

  LIST_SEQ=$((LIST_SEQ + 1))
  cache_chunk_out="$TMP_DIR/cache-chunk.$LIST_SEQ.nul"
  run_cache_find 25 "$cache_days" "$cache_chunk_out" "$@"
  cache_code=$?
  if [ "$cache_code" -eq 0 ]; then
    cat "$cache_chunk_out" >>"$cache_target_list"
  else
    case "$cache_code" in
      124|137|143) log_line "[缓存慢批次] $cache_category 第 ${cache_done}/${cache_total} 个目录附近超时，正在逐目录定位" ;;
      *) log_line "[缓存批次异常] $cache_category（代码 $cache_code），正在逐目录重试" ;;
    esac
    cache_fallback_start=$(date +%s)
    while IFS= read -r cache_dir || [ -n "$cache_dir" ]; do
      [ -d "$cache_dir" ] || continue
      cache_fallback_now=$(date +%s)
      if [ $((cache_fallback_now - cache_fallback_start)) -ge 60 ]; then
        CACHE_TRUNCATED=1
        log_line "[缓存批次提前结束] $cache_category 的慢目录定位达到 60 秒上限"
        report_line protected timeout "$cache_category" 1 0 "慢目录定位达到 60 秒上限"
        break
      fi
      LIST_SEQ=$((LIST_SEQ + 1))
      cache_one_out="$TMP_DIR/cache-one.$LIST_SEQ.nul"
      run_cache_find 6 "$cache_days" "$cache_one_out" "$cache_dir"
      cache_one_code=$?
      if [ "$cache_one_code" -eq 0 ]; then
        cat "$cache_one_out" >>"$cache_target_list"
      else
        CACHE_SLOW_DIRS=$((CACHE_SLOW_DIRS + 1))
        PROTECTED_ITEMS=$((PROTECTED_ITEMS + 1))
        log_line "[缓存跳过:目录扫描超时] $cache_dir"
        report_line protected slow "$cache_category" 1 0 "$cache_dir（扫描超时）"
      fi
      rm -f "$cache_one_out"
    done <"$cache_chunk_file"
  fi
  rm -f "$cache_chunk_out"
  set_phase "批量扫描${cache_category}（${cache_done}/${cache_total}个目录）" "$cache_done" "$cache_total" ""
}

collect_cache_candidates() {
  dir_list=$1
  days=$2
  target_list=$3
  cache_category=$4
  cache_total=$(wc -l <"$dir_list" 2>/dev/null | tr -d ' ')
  case "$cache_total" in ''|*[!0-9]*) cache_total=0 ;; esac
  [ "$cache_total" -gt 0 ] || return 0

  cache_chunk="$TMP_DIR/cache-dirs.chunk"
  cache_stage_start=$(date +%s)
  : >"$cache_chunk"
  cache_chunk_count=0
  cache_done=0
  while IFS= read -r cache_dir || [ -n "$cache_dir" ]; do
    [ -d "$cache_dir" ] || continue
    [ -L "$cache_dir" ] && continue
    printf '%s\n' "$cache_dir" >>"$cache_chunk"
    cache_chunk_count=$((cache_chunk_count + 1))
    cache_done=$((cache_done + 1))
    if [ "$cache_chunk_count" -ge 64 ]; then
      process_cache_chunk "$cache_chunk" "$days" "$target_list" "$cache_category" "$cache_done" "$cache_total"
      : >"$cache_chunk"
      cache_chunk_count=0
      should_stop && { rm -f "$cache_chunk"; return 9; }
      cache_stage_now=$(date +%s)
      if [ $((cache_stage_now - cache_stage_start)) -ge 180 ]; then
        CACHE_TRUNCATED=1
        log_line "[缓存扫描提前结束] $cache_category 已达到 180 秒安全时限"
        report_line protected timeout "$cache_category" 1 0 "缓存阶段达到 180 秒上限"
        break
      fi
    fi
  done <"$dir_list"
  if [ "$cache_chunk_count" -gt 0 ]; then
    process_cache_chunk "$cache_chunk" "$days" "$target_list" "$cache_category" "$cache_done" "$cache_total"
  fi
  rm -f "$cache_chunk"
  return 0
}

process_cache_candidates() {
  list=$1
  CATEGORY=$2
  app_package=${3:-}
  app_done=${4:-0}
  app_total=${5:-0}
  filter_whitelist_list "$list" || return $?
  filter_processed_list "$list" || return $?
  count=$(count_nul "$list")
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  [ "$count" -gt 0 ] || { rm -f "$list"; return 0; }
  sample_path=$(first_nul_path "$list" 2>/dev/null)

  if [ -n "$app_package" ]; then
    if [ "$MODE" = "clean" ]; then
      set_phase "正在清理应用缓存" "$app_done" "$app_total" "$app_package"
    else
      set_phase "正在统计应用缓存" "$app_done" "$app_total" "$app_package"
    fi
  fi

  estimated=$(bytes_from_list "$list")
  case "$estimated" in ''|*[!0-9]*) estimated=0 ;; esac
  if [ "$MODE" = "clean" ]; then
    err_file="$TMP_DIR/rm-cache.err.$LIST_SEQ"
    should_stop && return 9
    compat_delete_list "$list" file || return $?
    remaining=""
    reason=$(tail -n 1 "$err_file" 2>/dev/null)
    [ "$REMAINING_COUNT" -gt 0 ] && log_line "[部分未清理][$CATEGORY] ${reason:-系统拒绝删除部分文件}"
    log_line "[应用清理][$app_package][$CATEGORY] $ACTUAL_COUNT 个缓存文件，$ACTUAL_BYTES bytes，未清理 $REMAINING_COUNT 个"
    report_line cleaned low "$CATEGORY:$app_package" "$ACTUAL_COUNT" "$ACTUAL_BYTES" "$app_package"
    [ "$REMAINING_COUNT" -gt 0 ] && report_line failed low "$CATEGORY:$app_package" "$REMAINING_COUNT" "$REMAINING_BYTES" "$app_package"
    append_app_detail "$app_package" "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES" "$REMAINING_COUNT" "$sample_path"
    FILES=$((FILES + ACTUAL_COUNT))
    add_bytes "$ACTUAL_BYTES"
    rm -f "$remaining" "$err_file"
  else
    log_line "[应用扫描][$app_package][$CATEGORY] $count 个缓存文件，$estimated bytes"
    report_line candidate low "$CATEGORY:$app_package" "$count" "$estimated" "$app_package"
    append_app_detail "$app_package" "$CATEGORY" "$count" "$estimated" 0 "$sample_path"
    FILES=$((FILES + count))
    add_bytes "$estimated"
  fi
  rm -f "$list"
  return 0
}

clean_dir() {
  compat_begin_collection
  dir=$1
  days=$2
  CATEGORY=$3
  detail_package=$(package_for_detail "$dir" "$CATEGORY" 2>/dev/null)
  [ -n "$detail_package" ] && set_phase "正在处理应用垃圾" 0 0 "$detail_package"
  [ -d "$dir" ] || return 0
  [ -L "$dir" ] && return 0
  should_stop && return 9

  LIST_SEQ=$((LIST_SEQ + 1))
  list="$TMP_DIR/files.$LIST_SEQ.nul"
  rule_empty_list="$TMP_DIR/rule-empty.$LIST_SEQ.nul"
  rule_collected_native=0
  if [ -n "$RULE_SCAN_ENGINE" ]; then
    set -- collect-rule-files --collection-root "$dir" --targets "$list" --whitelist "$WHITELIST" \
      --min-age-days "$days" --empty-age-days "$EMPTY_DAYS" --max-file-bytes "$MAX_FILE_BYTES" \
      --dir-budget-ms 15000 --stop "$STATE_DIR/stop"
    [ "$CLEAN_EMPTY_FILES" = "1" ] && set -- "$@" --empty "$rule_empty_list"
    run_limited_command 18 "$RULE_SCAN_ENGINE" "$@"
    rule_collect_code=$?
    case "$rule_collect_code" in
      0) rule_collected_native=1 ;;
      9) rm -f "$list" "$rule_empty_list"; return 9 ;;
      *)
        # Never consume a partial traversal or silently report it as zero junk.
        ERRORS=$((ERRORS + 1)); PROTECTED_ITEMS=$((PROTECTED_ITEMS + 1))
        log_line "[规则目录未完成] $dir（代码 $rule_collect_code，已保留该目录）"
        report_line protected incomplete "$CATEGORY" 1 0 "$dir"
        rm -f "$list" "$rule_empty_list"
        return 0
        ;;
    esac
  elif [ "$days" -eq 0 ]; then
    run_limited_command 18 find "$dir" -xdev -mindepth 1 -type f -size +0c ! -name '.nomedia' ! -name '.keep' ! -name '.gitkeep' ! -name '.placeholder' ! -name '*.lock' -size "-$((MAX_FILE_BYTES + 1))c" -print0 2>/dev/null >"$list"
  else
    run_limited_command 18 find "$dir" -xdev -mindepth 1 -type f -size +0c ! -name '.nomedia' ! -name '.keep' ! -name '.gitkeep' ! -name '.placeholder' ! -name '*.lock' -size "-$((MAX_FILE_BYTES + 1))c" -mtime "+$((days - 1))" -print0 2>/dev/null >"$list"
  fi
  rule_collect_code=$?
  [ "$rule_collect_code" -ne 9 ] || { rm -f "$list" "$rule_empty_list"; return 9; }
  if [ "$rule_collect_code" -ne 0 ]; then
    ERRORS=$((ERRORS + 1)); PROTECTED_ITEMS=$((PROTECTED_ITEMS + 1))
    report_line protected incomplete "$CATEGORY" 1 0 "$dir"
    rm -f "$list" "$rule_empty_list"
    return 0
  fi
  filter_whitelist_list "$list" || return $?
  filter_processed_list "$list" || return $?
  count=$(count_nul "$list")
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  sample_path=$(first_nul_path "$list" 2>/dev/null)

  if [ "$count" -gt 0 ]; then
    estimated=$(bytes_from_list "$list")
    case "$estimated" in ''|*[!0-9]*) estimated=0 ;; esac
    if [ "$MODE" = "clean" ]; then
      err_file="$TMP_DIR/rm-dir.$LIST_SEQ.err"
      should_stop && return 9
      compat_delete_list "$list" file || return $?
      remaining=""
      reason=$(tail -n 1 "$err_file" 2>/dev/null)
      [ "$REMAINING_COUNT" -gt 0 ] && log_line "[部分未清理][$CATEGORY] ${reason:-系统拒绝删除部分文件}"
      FILES=$((FILES + ACTUAL_COUNT))
      [ "$HIDDEN_CONTEXT" = "1" ] && HIDDEN_ITEMS=$((HIDDEN_ITEMS + ACTUAL_COUNT))
      add_bytes "$ACTUAL_BYTES"
      log_line "[批量清理][$CATEGORY] $dir ($ACTUAL_COUNT 个文件，约 $ACTUAL_BYTES bytes，未清理 $REMAINING_COUNT 个)"
      report_line cleaned low "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES" "$dir"
      record_app_detail_for "$dir" "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES" "$REMAINING_COUNT" "$sample_path"
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
    if [ "$rule_collected_native" = "1" ]; then
      mv -f "$rule_empty_list" "$list"
    elif [ "$EMPTY_DAYS" -eq 0 ]; then
      run_limited_command 18 find "$dir" -xdev -mindepth 1 -type f -size 0c ! -name '.nomedia' ! -name '.keep' ! -name '.gitkeep' ! -name '.placeholder' ! -name '*.lock' -print0 2>/dev/null >"$list"
    else
      run_limited_command 18 find "$dir" -xdev -mindepth 1 -type f -size 0c -mtime "+$((EMPTY_DAYS - 1))" ! -name '.nomedia' ! -name '.keep' ! -name '.gitkeep' ! -name '.placeholder' ! -name '*.lock' -print0 2>/dev/null >"$list"
    fi
    rule_collect_code=$?
    [ "$rule_collect_code" -ne 9 ] || { rm -f "$list" "$rule_empty_list"; return 9; }
    if [ "$rule_collect_code" -ne 0 ]; then
      ERRORS=$((ERRORS + 1)); PROTECTED_ITEMS=$((PROTECTED_ITEMS + 1))
      report_line protected incomplete "空文件:$CATEGORY" 1 0 "$dir"
      rm -f "$list" "$rule_empty_list"
      return 0
    fi
    filter_whitelist_list "$list" || return $?
    filter_processed_list "$list" || return $?
    count=$(count_nul "$list")
    case "$count" in ''|*[!0-9]*) count=0 ;; esac
    sample_path=$(first_nul_path "$list" 2>/dev/null)
    if [ "$count" -gt 0 ]; then
      if [ "$MODE" = "clean" ]; then
        err_file="$TMP_DIR/rm-empty.$LIST_SEQ.err"
        should_stop && return 9
        compat_delete_list "$list" empty || return $?
        remaining=""
        reason=$(tail -n 1 "$err_file" 2>/dev/null)
        [ "$REMAINING_COUNT" -gt 0 ] && log_line "[部分未清理][空文件:$CATEGORY] ${reason:-系统拒绝删除部分文件}"
        EMPTY_FILES=$((EMPTY_FILES + ACTUAL_COUNT))
        [ "$HIDDEN_CONTEXT" = "1" ] && HIDDEN_ITEMS=$((HIDDEN_ITEMS + ACTUAL_COUNT))
        log_line "[批量清理][空文件:$CATEGORY] $dir ($ACTUAL_COUNT 个，未清理 $REMAINING_COUNT 个)"
        report_line cleaned low "空文件:$CATEGORY" "$ACTUAL_COUNT" 0 "$dir"
        record_app_detail_for "$dir" "空文件:$CATEGORY" "$ACTUAL_COUNT" 0 "$REMAINING_COUNT" "$sample_path"
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
    run_limited_command 18 find "$dir" -xdev -depth -mindepth 1 -type d -empty -print0 2>/dev/null >"$list"
    rule_collect_code=$?
    case "$rule_collect_code" in
      0) ;;
      9) rm -f "$list"; return 9 ;;
      *)
        ERRORS=$((ERRORS + 1)); PROTECTED_ITEMS=$((PROTECTED_ITEMS + 1))
        CACHE_TRUNCATED=1
        log_line "[空目录扫描未完成] $dir（代码 $rule_collect_code，部分清单已丢弃）"
        report_line protected incomplete "空目录:$CATEGORY" 1 0 "$dir"
        rm -f "$list"
        return 0
        ;;
    esac
    filter_whitelist_list "$list" || return $?
    filter_processed_list "$list" || return $?
    count=$(count_nul "$list")
    case "$count" in ''|*[!0-9]*) count=0 ;; esac
    sample_path=$(first_nul_path "$list" 2>/dev/null)
    if [ "$count" -gt 0 ]; then
      if [ "$MODE" = "clean" ]; then
        should_stop && return 9
        compat_delete_list "$list" directory || return $?
        remaining=""
        EMPTY_DIRS=$((EMPTY_DIRS + ACTUAL_COUNT))
        [ "$HIDDEN_CONTEXT" = "1" ] && HIDDEN_ITEMS=$((HIDDEN_ITEMS + ACTUAL_COUNT))
        log_line "[批量清理][空目录:$CATEGORY] $dir ($ACTUAL_COUNT 个，未清理 $REMAINING_COUNT 个)"
        report_line cleaned low "空目录:$CATEGORY" "$ACTUAL_COUNT" 0 "$dir"
        record_app_detail_for "$dir" "空目录:$CATEGORY" "$ACTUAL_COUNT" 0 "$REMAINING_COUNT" "$sample_path"
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
  compat_begin_collection
  roots=$1
  days=$2
  category=$3
  packages="$TMP_DIR/cache-packages.internal"
  : >"$packages"

  for root in $roots; do
    [ -d "$root" ] || continue
    for user_root in "$root"/[0-9]*; do
      [ -d "$user_root" ] || continue
      for app_dir in "$user_root"/*; do
        [ -d "$app_dir" ] || continue
        package=${app_dir##*/}
        valid_package_name "$package" || continue
        { [ -d "$app_dir/cache" ] || [ -d "$app_dir/code_cache" ]; } || continue
        grep -Fqx -- "$package" "$packages" 2>/dev/null || printf '%s\n' "$package" >>"$packages"
      done
    done
  done

  total=$(wc -l <"$packages" 2>/dev/null | tr -d ' ')
  case "$total" in ''|*[!0-9]*) total=0 ;; esac
  done_count=0
  stage_started=$(date +%s)
  while IFS= read -r package || [ -n "$package" ]; do
    valid_package_name "$package" || continue
    done_count=$((done_count + 1))
    set --
    for root in $roots; do
      for user_root in "$root"/[0-9]*; do
        [ -d "$user_root/$package/cache" ] && [ ! -L "$user_root/$package/cache" ] && set -- "$@" "$user_root/$package/cache"
        [ -d "$user_root/$package/code_cache" ] && [ ! -L "$user_root/$package/code_cache" ] && set -- "$@" "$user_root/$package/code_cache"
      done
    done
    [ "$#" -gt 0 ] || continue
    should_stop && { rm -f "$packages"; return 9; }
    set_phase "正在扫描应用缓存" "$done_count" "$total" "$package"
    LIST_SEQ=$((LIST_SEQ + 1))
    candidates="$TMP_DIR/cache-internal.$LIST_SEQ.nul"
    run_cache_find 10 "$days" "$candidates" "$@"
    code=$?
    if [ "$code" -eq 0 ]; then
      process_cache_candidates "$candidates" "$category" "$package" "$done_count" "$total" || { rm -f "$packages"; return 9; }
    else
      [ "$code" -ne 9 ] || { rm -f "$packages" "$candidates"; return 9; }
      ERRORS=$((ERRORS + 1))
      CACHE_SLOW_DIRS=$((CACHE_SLOW_DIRS + 1))
      PROTECTED_ITEMS=$((PROTECTED_ITEMS + 1))
      report_line protected timeout "$category:$package" 1 0 "$package"
      rm -f "$candidates"
    fi
    now=$(date +%s)
    if [ $((now - stage_started)) -ge 240 ]; then
      CACHE_TRUNCATED=1
      log_line "[应用缓存提前结束] 已达到 240 秒上限"
      break
    fi
  done <"$packages"
  rm -f "$packages"
  return 0
}

scan_external_cache() {
  compat_begin_collection
  days=$1
  packages="$TMP_DIR/cache-packages.external"
  : >"$packages"
  for app_dir in /data/media/[0-9]*/Android/data/*; do
    [ -d "$app_dir" ] || continue
    package=${app_dir##*/}
    valid_package_name "$package" || continue
    { [ -d "$app_dir/cache" ] || [ -d "$app_dir/code_cache" ]; } || continue
    grep -Fqx -- "$package" "$packages" 2>/dev/null || printf '%s\n' "$package" >>"$packages"
  done

  total=$(wc -l <"$packages" 2>/dev/null | tr -d ' ')
  case "$total" in ''|*[!0-9]*) total=0 ;; esac
  done_count=0
  stage_started=$(date +%s)
  while IFS= read -r package || [ -n "$package" ]; do
    valid_package_name "$package" || continue
    done_count=$((done_count + 1))
    set --
    for app_dir in /data/media/[0-9]*/Android/data/"$package"; do
      [ -d "$app_dir/cache" ] && [ ! -L "$app_dir/cache" ] && set -- "$@" "$app_dir/cache"
      [ -d "$app_dir/code_cache" ] && [ ! -L "$app_dir/code_cache" ] && set -- "$@" "$app_dir/code_cache"
    done
    [ "$#" -gt 0 ] || continue
    should_stop && { rm -f "$packages"; return 9; }
    set_phase "正在扫描外部应用缓存" "$done_count" "$total" "$package"
    LIST_SEQ=$((LIST_SEQ + 1))
    candidates="$TMP_DIR/cache-external.$LIST_SEQ.nul"
    run_cache_find 10 "$days" "$candidates" "$@"
    code=$?
    if [ "$code" -eq 0 ]; then
      process_cache_candidates "$candidates" "外部应用缓存" "$package" "$done_count" "$total" || { rm -f "$packages"; return 9; }
    else
      [ "$code" -ne 9 ] || { rm -f "$packages" "$candidates"; return 9; }
      ERRORS=$((ERRORS + 1))
      CACHE_SLOW_DIRS=$((CACHE_SLOW_DIRS + 1))
      PROTECTED_ITEMS=$((PROTECTED_ITEMS + 1))
      report_line protected timeout "外部应用缓存:$package" 1 0 "$package"
      rm -f "$candidates"
    fi
    now=$(date +%s)
    if [ $((now - stage_started)) -ge 180 ]; then
      CACHE_TRUNCATED=1
      log_line "[外部缓存提前结束] 已达到 180 秒上限"
      break
    fi
  done <"$packages"
  rm -f "$packages"
  return 0
}

# One native discovery pass groups rules by package and only visits installed
# storage directories. Fallback remains available when the packaged ABI is absent.
run_native_relative_rules() {
  native_rule_source=$1
  native_rule_category=$2
  native_rule_external=${3:-0}
  [ -f "$SCRIPTDIR/abi-resolve.sh" ] || return 1
  [ -n "$RULE_SCAN_ENGINE" ] || return 1
  native_rule_engine=$RULE_SCAN_ENGINE
  native_rule_targets="$TMP_DIR/relative-rule-targets.nul"
  set -- rule-targets --rules "$native_rule_source" --targets "$native_rule_targets" --stop "$STATE_DIR/stop"
  [ "$native_rule_external" = "1" ] && set -- "$@" --rule-external
  run_limited_command 30 "$native_rule_engine" "$@"
  native_rule_code=$?
  case "$native_rule_code" in
    0) ;;
    9) rm -f "$native_rule_targets"; return 9 ;;
    *)
      log_line "[规则发现回退] 原生目录读取未完成（代码 $native_rule_code），使用逐规则发现"
      rm -f "$native_rule_targets"
      return 1
      ;;
  esac
  while IFS= read -r -d '' native_rule_days &&
        IFS= read -r -d '' native_rule_package &&
        IFS= read -r -d '' native_rule_target; do
    should_stop && { rm -f "$native_rule_targets"; return 9; }
    rule_target_once "$native_rule_target" || continue
    if [ -d "$native_rule_target" ]; then
      clean_dir "$native_rule_target" "$native_rule_days" "$native_rule_category:$native_rule_package" || return $?
    elif [ -f "$native_rule_target" ]; then
      rule_file_old_enough "$native_rule_target" "$native_rule_days" || continue
      CATEGORY="$native_rule_category:$native_rule_package"
      native_rule_size=$(stat -c %s "$native_rule_target" 2>/dev/null)
      if [ "${native_rule_size:-0}" = "0" ]; then
        [ "$CLEAN_EMPTY_FILES" != "1" ] || handle_file "$native_rule_target" empty || return $?
      else
        handle_file "$native_rule_target" regular || return $?
      fi
    fi
  done <"$native_rule_targets"
  rm -f "$native_rule_targets"
  return 0
}

rule_file_old_enough() {
  [ "$2" -eq 0 ] && return 0
  rule_file_mtime=$(stat -c %Y "$1" 2>/dev/null) || return 1
  case "$rule_file_mtime" in ''|*[!0-9]*) return 1 ;; esac
  [ "$rule_file_mtime" -le "$((START_EPOCH - $2 * 86400))" ]
}

run_app_rules() {
  compat_begin_collection
  [ -f "$APP_RULES" ] || return 0
  run_native_relative_rules "$APP_RULES" "应用扩展规则" 0
  rule_discovery_code=$?
  case "$rule_discovery_code" in 0) return 0 ;; 9) return 9 ;; esac
  while IFS='|' read -r package relative days extra || [ -n "$package$relative$days$extra" ]; do
    case "$package" in ''|'#'*) continue ;; esac
    [ -z "$extra" ] || { log_line "[拒绝:应用规则格式] $package"; continue; }
    case "$package" in *[!A-Za-z0-9._-]*) log_line "[拒绝:包名] $package"; continue ;; esac
    case "$relative" in ''|/*|*'..'*|*'//'*) log_line "[拒绝:相对路径] $package/$relative"; continue ;; esac
    case "$days" in ''|*[!0-9]*) log_line "[拒绝:规则天数] $package/$relative"; continue ;; esac
    [ "$days" -le 365 ] || { log_line "[拒绝:规则天数超限] $package/$relative"; continue; }
    for base in /data/user/[0-9]*/"$package" /data/user_de/[0-9]*/"$package"; do
      [ -d "$base" ] || continue
      raw_target="$base/$relative"
      target=$(resolve_rule_target "$base" "$raw_target") || {
        { [ -e "$raw_target" ] || [ -L "$raw_target" ]; } && log_line "[拒绝:规则越界或符号链接] $raw_target"
        continue
      }
      rule_target_once "$target" || continue
      if [ -d "$target" ]; then
        clean_dir "$target" "$days" "应用扩展规则:$package" || return $?
      elif [ -f "$target" ] && rule_file_old_enough "$target" "$days"; then
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
  compat_begin_collection
  [ -f "$EXTERNAL_RULES" ] || return 0
  run_native_relative_rules "$EXTERNAL_RULES" "外部应用扩展规则" 1
  rule_discovery_code=$?
  case "$rule_discovery_code" in 0) return 0 ;; 9) return 9 ;; esac
  while IFS='|' read -r package relative days extra || [ -n "$package$relative$days$extra" ]; do
    case "$package" in ''|'#'*) continue ;; esac
    [ -z "$extra" ] || { log_line "[拒绝:外部规则格式] $package"; continue; }
    case "$package" in *[!A-Za-z0-9._-]*) log_line "[拒绝:外部规则包名] $package"; continue ;; esac
    case "$relative" in ''|/*|*'..'*|*'//'*) log_line "[拒绝:外部相对路径] $package/$relative"; continue ;; esac
    case "$days" in ''|*[!0-9]*) log_line "[拒绝:外部规则天数] $package/$relative"; continue ;; esac
    [ "$days" -le 365 ] || { log_line "[拒绝:外部规则天数超限] $package/$relative"; continue; }
    for userdir in /data/media/[0-9]*; do
      [ -d "$userdir" ] || continue
      base="$userdir/Android/data/$package"
      [ -d "$base" ] || continue
      raw_target="$base/$relative"
      target=$(resolve_rule_target "$base" "$raw_target") || {
        { [ -e "$raw_target" ] || [ -L "$raw_target" ]; } && log_line "[拒绝:外部规则越界或符号链接] $raw_target"
        continue
      }
      rule_target_once "$target" || continue
      if [ -d "$target" ]; then
        clean_dir "$target" "$days" "外部应用扩展规则:$package" || return $?
      elif [ -f "$target" ] && rule_file_old_enough "$target" "$days"; then
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
  compat_begin_collection
  for webview_app in /data/user/[0-9]*/* /data/user_de/[0-9]*/*; do
    [ -d "$webview_app" ] && [ ! -L "$webview_app" ] || continue
    webview_package=${webview_app##*/}
    valid_package_name "$webview_package" || continue
    for webview_root in "$webview_app"/app_webview* "$webview_app"/app_hws_webview* "$webview_app"/app_x5webview*; do
      [ -d "$webview_root" ] && [ ! -L "$webview_root" ] || continue
      for webview_leaf in Cache GPUCache 'GPU Cache' 'Code Cache' Default/Cache Default/GPUCache 'Default/GPU Cache' 'Default/Code Cache' Crashpad/completed; do
        webview_target=$(resolve_rule_target "$webview_app" "$webview_root/$webview_leaf") || continue
        [ -d "$webview_target" ] || continue
        rule_target_once "$webview_target" || continue
        clean_dir "$webview_target" 0 "WebView缓存:$webview_package" || return $?
      done
    done
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

scan_shared_empty_files() {
  compat_begin_collection
  [ -d /data/media ] || return 0
  CATEGORY="共享存储空项"
  list="$TMP_DIR/shared-empty-files.nul"
  run_limited_command 18 find /data/media -mindepth 2 -maxdepth 6 \
    \( -path '/data/media/[0-9]*/Android' -o -path '/data/media/[0-9]*/Android/*' \
       -o -path '/data/media/[0-9]*/DCIM' -o -path '/data/media/[0-9]*/Pictures' \
       -o -path '/data/media/[0-9]*/Movies' -o -path '/data/media/[0-9]*/Music' \
       -o -path '/data/media/[0-9]*/Download' -o -path '/data/media/[0-9]*/Documents' \) -prune -o \
    -type f -size 0c \
    ! -name '.nomedia' ! -name '.keep' ! -name '.gitkeep' ! -name '.placeholder' ! -name '*.lock' \
    -print0 2>/dev/null >"$list"
  collect_code=$?
  [ "$collect_code" -ne 9 ] || { rm -f "$list"; return 9; }
  if [ "$collect_code" -ne 0 ]; then
    compat_collection_failed /data/media
    rm -f "$list"
    return 0
  fi
  filter_whitelist_list "$list" || return $?
  count=$(count_nul "$list")
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  if [ "$count" -gt 0 ]; then
    if [ "$MODE" = "clean" ]; then
      should_stop && return 9
      compat_delete_list "$list" empty || return $?
      remaining=""
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
  compat_begin_collection
  [ -d /data/media ] || return 0
  CATEGORY="共享存储空项"
  list="$TMP_DIR/shared-empty-dirs.nul"
  run_limited_command 18 find /data/media -mindepth 2 -maxdepth 6 \
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
  collect_code=$?
  [ "$collect_code" -ne 9 ] || { rm -f "$list"; return 9; }
  if [ "$collect_code" -ne 0 ]; then
    compat_collection_failed /data/media
    rm -f "$list"
    return 0
  fi
  filter_whitelist_list "$list" || return $?
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

  should_stop && return 9
  compat_delete_list "$list" directory || return $?
  EMPTY_DIRS=$((EMPTY_DIRS + ACTUAL_COUNT))
  log_line "[批量清理][共享存储空目录] $ACTUAL_COUNT 个，未清理 $REMAINING_COUNT 个"
  report_line cleaned low 共享存储空目录 "$ACTUAL_COUNT" 0 /data/media
  [ "$REMAINING_COUNT" -gt 0 ] && report_line failed low 共享存储空目录 "$REMAINING_COUNT" 0 /data/media
  rm -f "$list"
}

is_reserved_shared_root() {
  name=${1##*/}
  case "$name" in
    ''|.*|Android|DCIM|Download|Documents|Pictures|Movies|Music|Podcasts|Ringtones|Alarms|Notifications|Audiobooks|Recordings|Fonts|MIUI|ColorOS|HeyTap|oplus|Tencent|WeChat|QQ|backups|Backup|LOST.DIR) return 0 ;;
  esac
  return 1
}

is_mount_target() {
  awk -v target="$1" '$2 == target { found=1; exit } END { exit found ? 0 : 1 }' /proc/mounts 2>/dev/null
}

root_shell_old_enough() {
  [ "$ROOT_SHELL_DAYS" -le 0 ] && return 0
  find "$1" -maxdepth 0 -mtime "+$ROOT_SHELL_DAYS" -print -quit 2>/dev/null | grep -q .
}

root_shell_effectively_empty() {
  dir=$1
  [ -d "$dir" ] || return 1
  [ -L "$dir" ] && return 1
  is_mount_target "$dir" && return 1
  LIST_SEQ=$((LIST_SEQ + 1))
  probe="$TMP_DIR/root-shell-probe.$LIST_SEQ"
  run_limited_command 6 find "$dir" -mindepth 1 \
    ! -type d \
    ! \( -type f -size 0c \( -name '.nomedia' -o -name '.keep' -o -name '.gitkeep' -o -name '.placeholder' \) \) \
    -print -quit >"$probe" 2>/dev/null
  probe_code=$?
  if [ "$probe_code" -ne 0 ]; then
    ERRORS=$((ERRORS + 1))
    PROTECTED_ITEMS=$((PROTECTED_ITEMS + 1))
    log_line "[根目录保护:扫描超时或异常] $dir"
    report_line protected slow 根目录空壳 1 0 "$dir"
    rm -f "$probe"
    return 1
  fi
  if [ -s "$probe" ]; then
    rm -f "$probe"
    return 1
  fi
  rm -f "$probe"
  return 0
}

run_shared_root_shells() {
  compat_begin_collection
  [ -d /data/media ] || return 0
  for userdir in /data/media/[0-9]*; do
    [ -d "$userdir" ] || continue
    for dir in "$userdir"/*; do
      should_stop && return 9
      [ -d "$dir" ] || continue
      [ -L "$dir" ] && continue
      is_reserved_shared_root "$dir" && continue
      root_shell_old_enough "$dir" || continue
      if is_whitelisted "$dir" || deep_conflicts_whitelist "$dir"; then
        SKIPPED=$((SKIPPED + 1))
        log_line "[根目录跳过:白名单] $dir"
        continue
      fi
      root_shell_effectively_empty "$dir" || continue

      if [ "$MODE" = "scan" ]; then
        EMPTY_DIRS=$((EMPTY_DIRS + 1))
        log_line "[根目录空壳候选] $dir（保留 ${ROOT_SHELL_DAYS} 天）"
        report_line candidate medium 根目录空壳 1 0 "$dir"
        continue
      fi

      LIST_SEQ=$((LIST_SEQ + 1))
      shell_list="$TMP_DIR/root-shell.$LIST_SEQ.nul"
      run_limited_command 10 find "$dir" -depth \
        \( -type d -o \( -type f -size 0c \( -name '.nomedia' -o -name '.keep' -o -name '.gitkeep' -o -name '.placeholder' \) \) \) \
        -print0 >"$shell_list" 2>/dev/null
      shell_collect_code=$?
      [ "$shell_collect_code" -ne 9 ] || { rm -f "$shell_list"; return 9; }
      if [ "$shell_collect_code" -ne 0 ]; then
        compat_collection_failed "$dir"
        rm -f "$shell_list"
        continue
      fi
      CATEGORY="根目录空壳"
      compat_delete_list "$shell_list" empty-tree || return $?
      # A shell counts once only when its own rmdir succeeded. This NUL probe
      # reads confirmed operations, never a failed stat or a path difference.
      shell_removed=0
      [ -f "$COMPAT_DELETED_NUL" ] && while IFS= read -r -d '' shell_deleted; do
        [ "$shell_deleted" != "$dir" ] || shell_removed=1
      done <"$COMPAT_DELETED_NUL"
      if [ "$shell_removed" = "1" ]; then
        EMPTY_DIRS=$((EMPTY_DIRS + 1))
        log_line "[根目录空壳已清理] $dir"
        report_line cleaned medium 根目录空壳 1 0 "$dir"
      fi
      rm -f "$shell_list"

    done
  done
  return 0
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
  compat_begin_collection
  CATEGORY="隐藏垃圾"
  [ -d /data/media ] && [ -f "$HIDDEN_RULES" ] || return 0
  HIDDEN_CONTEXT=1
  list="$TMP_DIR/hidden-dirs"
  : >"$list"
  for direct_hidden in /data/media/[0-9]*/DCIM/.thumbnails /data/media/[0-9]*/Pictures/.thumbnails; do
    [ -d "$direct_hidden" ] && printf '%s\0' "$direct_hidden" >>"$list"
  done
  run_limited_command 18 find /data/media -mindepth 2 -maxdepth 6 \
    \( -path '/data/media/[0-9]*/Android' -o -path '/data/media/[0-9]*/Android/*' \
       -o -path '/data/media/[0-9]*/DCIM' -o -path '/data/media/[0-9]*/Pictures' \
       -o -path '/data/media/[0-9]*/Movies' -o -path '/data/media/[0-9]*/Music' \
       -o -path '/data/media/[0-9]*/Download' -o -path '/data/media/[0-9]*/Documents' \) -prune -o \
    -type d -name '.*' -print0 2>/dev/null >>"$list"
  hidden_collect_code=$?
  [ "$hidden_collect_code" -ne 9 ] || { rm -f "$list"; HIDDEN_CONTEXT=0; return 9; }
  if [ "$hidden_collect_code" -ne 0 ]; then
    compat_collection_failed /data/media
    rm -f "$list"
    HIDDEN_CONTEXT=0
    return 0
  fi
  while IFS= read -r -d '' hidden_dir; do
    [ -d "$hidden_dir" ] || continue
    [ -L "$hidden_dir" ] && continue
    is_protected_hidden_path "$hidden_dir" && { log_line "[跳过:隐藏配置] $hidden_dir"; continue; }
    name=${hidden_dir##*/}
    rule_days=$(hidden_dir_days "$name") || continue
    [ "$HIDDEN_DAYS" -gt "$rule_days" ] && rule_days=$HIDDEN_DAYS
    clean_dir "$hidden_dir" "$rule_days" "隐藏垃圾:$name" || { HIDDEN_CONTEXT=0; return 9; }
    if [ -d "$hidden_dir" ] && [ ! -L "$hidden_dir" ] && [ -z "$(ls -A "$hidden_dir" 2>/dev/null)" ]; then
      if is_whitelisted "$hidden_dir"; then
        log_line "[跳过:白名单][隐藏空目录] $hidden_dir"
      elif [ "$MODE" = "clean" ]; then
        LIST_SEQ=$((LIST_SEQ + 1))
        hidden_dir_list="$TMP_DIR/hidden-root.$LIST_SEQ.nul"
        printf '%s\0' "$hidden_dir" >"$hidden_dir_list"
        compat_delete_list "$hidden_dir_list" directory || { HIDDEN_CONTEXT=0; return 9; }
        EMPTY_DIRS=$((EMPTY_DIRS + ACTUAL_COUNT)); HIDDEN_ITEMS=$((HIDDEN_ITEMS + ACTUAL_COUNT))
        [ "$ACTUAL_COUNT" -eq 0 ] || log_line "[已清理][隐藏空目录] $hidden_dir"
        rm -f "$hidden_dir_list"
      else
        EMPTY_DIRS=$((EMPTY_DIRS + 1)); HIDDEN_ITEMS=$((HIDDEN_ITEMS + 1))
        log_line "[可清理][隐藏空目录] $hidden_dir"
      fi
    fi
  done <"$list"

  list="$TMP_DIR/hidden-files"
  if [ "$HIDDEN_DAYS" -eq 0 ]; then
    run_limited_command 18 find /data/media -mindepth 2 -maxdepth 6 \
      \( -path '/data/media/[0-9]*/Android' -o -path '/data/media/[0-9]*/Android/*' \
         -o -path '/data/media/[0-9]*/DCIM' -o -path '/data/media/[0-9]*/Pictures' \
         -o -path '/data/media/[0-9]*/Movies' -o -path '/data/media/[0-9]*/Music' \
         -o -path '/data/media/[0-9]*/Download' -o -path '/data/media/[0-9]*/Documents' \) -prune -o \
      -type f \( -name '.DS_Store' -o -name '._*' -o -name 'Thumbs.db' -o -name 'desktop.ini' -o -name '.directory' \) -print0 2>/dev/null >"$list"
  else
    run_limited_command 18 find /data/media -mindepth 2 -maxdepth 6 \
      \( -path '/data/media/[0-9]*/Android' -o -path '/data/media/[0-9]*/Android/*' \
         -o -path '/data/media/[0-9]*/DCIM' -o -path '/data/media/[0-9]*/Pictures' \
         -o -path '/data/media/[0-9]*/Movies' -o -path '/data/media/[0-9]*/Music' \
         -o -path '/data/media/[0-9]*/Download' -o -path '/data/media/[0-9]*/Documents' \) -prune -o \
      -type f \( -name '.DS_Store' -o -name '._*' -o -name 'Thumbs.db' -o -name 'desktop.ini' -o -name '.directory' \) -mtime "+$HIDDEN_DAYS" -print0 2>/dev/null >"$list"
  fi
  hidden_collect_code=$?
  [ "$hidden_collect_code" -ne 9 ] || { rm -f "$list"; HIDDEN_CONTEXT=0; return 9; }
  if [ "$hidden_collect_code" -ne 0 ]; then
    compat_collection_failed /data/media
    rm -f "$list"
    HIDDEN_CONTEXT=0
    return 0
  fi
  CATEGORY="隐藏垃圾文件"
  while IFS= read -r -d '' hidden_file; do
    is_protected_hidden_path "$hidden_file" && { log_line "[跳过:隐藏配置] $hidden_file"; continue; }
    handle_file "$hidden_file" regular || { HIDDEN_CONTEXT=0; return 9; }
  done <"$list"
  HIDDEN_CONTEXT=0
  return 0
}

# “碎片清理”指可识别的临时残留、诊断转储和中断下载片段，
# 不是对闪存做传统磁盘碎片整理。用户媒体与文档目录不参与通用匹配。
run_fragment_cleanup() {
  compat_begin_collection
  [ -d /data/media ] || return 0
  list="$TMP_DIR/fragments.nul"
  : >"$list"
  CATEGORY="残留碎片"
  fragment_collect_failed=0

  for userdir in /data/media/[0-9]*; do
    [ -d "$userdir" ] || continue

    # 非媒体公共区域：日志、崩溃转储与临时文件，至少保留指定天数。
    run_limited_command 18 find "$userdir" -mindepth 1 -maxdepth 4 \
      \( -path "$userdir/Android" -o -path "$userdir/DCIM" -o -path "$userdir/Pictures" \
         -o -path "$userdir/Movies" -o -path "$userdir/Music" -o -path "$userdir/Documents" \
         -o -path "$userdir/Download" -o -path "$userdir/Podcasts" -o -path "$userdir/Audiobooks" \
         -o -path "$userdir/Recordings" -o -path "$userdir/Fonts" -o -path "$userdir/Ringtones" \
         -o -path "$userdir/Alarms" -o -path "$userdir/Notifications" \) -prune -o \
      -type f -size "-${MAX_FILE_BYTES}c" $FRAGMENT_MTIME_ARGS \
      \( -iname '*.tmp' -o -iname '*.temp' -o -iname '*.tmf' \
         -o -iname '*.log' -o -iname '*.xlog' -o -iname '*.tlog' -o -iname '*.ulog' -o -iname '*.plog' \
         -o -iname '*.hprof' -o -iname '*.dmp' -o -iname '*.dump' -o -iname '*.trace' \
         -o -iname '*.traces' -o -iname '*.stacktrace' -o -iname 'hs_err_pid*.log' \) \
      -print0 2>/dev/null >>"$list"
    fragment_collect_code=$?
    [ "$fragment_collect_code" -ne 9 ] || { rm -f "$list"; return 9; }
    [ "$fragment_collect_code" -eq 0 ] || fragment_collect_failed=1

    # 下载目录只匹配明确的中断下载后缀，避免把普通日志或用户临时文档误删。
    if [ -d "$userdir/Download" ]; then
      run_limited_command 18 find "$userdir/Download" -mindepth 1 -maxdepth 4 -type f \
        -size "-${MAX_FILE_BYTES}c" $FRAGMENT_MTIME_ARGS \
        \( -iname '*.part' -o -iname '*.partial' -o -iname '*.crdownload' \
           -o -iname '*.filepart' -o -iname '*.download' -o -iname '*.opdownload' \) \
        -print0 2>/dev/null >>"$list"
      fragment_collect_code=$?
      [ "$fragment_collect_code" -ne 9 ] || { rm -f "$list"; return 9; }
      [ "$fragment_collect_code" -eq 0 ] || fragment_collect_failed=1
    fi
  done

  if [ "$fragment_collect_failed" -ne 0 ]; then
    compat_collection_failed /data/media
    rm -f "$list"
    return 0
  fi
  filter_whitelist_list "$list" || return $?
  count=$(count_nul "$list")
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  [ "$count" -gt 0 ] || { rm -f "$list"; return 0; }

  estimated=$(bytes_from_list "$list")
  case "$estimated" in ''|*[!0-9]*) estimated=0 ;; esac
  if [ "$MODE" = "clean" ]; then
    err_file="$TMP_DIR/rm-fragments.err"
    should_stop && return 9
    compat_delete_list "$list" file || return $?
    actual_count=$ACTUAL_COUNT
    actual_bytes=$ACTUAL_BYTES
    remaining_count=$REMAINING_COUNT
    remaining_bytes=$REMAINING_BYTES
    remaining=""
    log_line "[批量清理][残留碎片] $actual_count 个文件，约 $actual_bytes bytes，${FRAGMENT_POLICY}，未清理 $remaining_count 个"
    report_line cleaned low 残留碎片 "$actual_count" "$actual_bytes" "${FRAGMENT_POLICY}"
    [ "$remaining_count" -gt 0 ] && report_line failed low 残留碎片 "$remaining_count" "$remaining_bytes" "仍存在的碎片文件"
    rm -f "$remaining"
  else
    actual_count=$count
    actual_bytes=$estimated
    log_line "[批量扫描][残留碎片] $count 个文件，约 $estimated bytes，${FRAGMENT_POLICY}"
    report_line candidate low 残留碎片 "$count" "$estimated" "${FRAGMENT_POLICY}"
  fi
  FILES=$((FILES + actual_count))
  FRAGMENT_FILES=$((FRAGMENT_FILES + actual_count))
  add_bytes "$actual_bytes"
  rm -f "$list"
  return 0
}

snapshot_sha256() {
  file=$1
  [ -f "$file" ] || { echo missing; return; }
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" 2>/dev/null | awk 'NR==1{print $1}'
  else
    toybox sha256sum "$file" 2>/dev/null | awk 'NR==1{print $1}'
  fi
}

run_apk_packages() {
  compat_begin_collection
  MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
  apk_helper=${BAIZE_APK_PATHS:-$SCRIPTDIR/apk-paths.sh}
  [ -f "$apk_helper" ] || apk_helper="$MODDIR/v2/module/scripts/apk-paths.sh"
  [ -f "$apk_helper" ] || { echo "安装包组件缺失" >&2; return 5; }
  . "$apk_helper"
  apk_load_roots
  list="$TMP_DIR/apk-packages.nul"
  raw="$TMP_DIR/apk-discovered.nul"
  CATEGORY="APK安装包"
  apk_collect_candidates "$raw"
  apk_collect_code=$?
  [ "$apk_collect_code" -ne 9 ] || return 9
  if [ "$apk_collect_code" -ne 0 ] || [ "${APK_SCAN_ROOT_ERRORS:-0}" -gt 0 ]; then
    compat_collection_failed "安装包存储范围"
    rm -f "$raw"
    return 0
  fi
  : >"$list"
  APK_RETAINED=0
  apk_selection_failed=0
  cutoff=$(( $(date +%s) - APK_PACKAGE_DAYS * 86400 ))
  while IFS= read -r -d '' package; do
    should_stop && return 9
    apk_path_allowed "$package" || continue
    metadata=$(stat -c '%s %Y' "$package" 2>/dev/null) || { apk_selection_failed=1; continue; }
    size=${metadata%% *}; modified=${metadata##* }
    [ "$size" -le "$APK_PACKAGE_MAX_BYTES" ] || continue
    if [ "$APK_PACKAGE_DAYS" -gt 0 ] && [ "$modified" -ge "$cutoff" ]; then
      APK_RETAINED=$((APK_RETAINED + 1))
      continue
    fi
    printf '%s\0' "$package" >>"$list"
  done <"$raw"
  if [ "$apk_selection_failed" -ne 0 ]; then
    compat_collection_failed "安装包候选属性"
    rm -f "$list"
    return 0
  fi

  filter_whitelist_list "$list" || return $?
  filter_processed_list "$list" || return $?
  count=$(count_nul "$list")
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  [ "$count" -gt 0 ] || { rm -f "$list"; return 0; }
  estimated=$(bytes_from_list "$list")
  case "$estimated" in ''|*[!0-9]*) estimated=0 ;; esac
  sample_path=$(first_nul_path "$list" 2>/dev/null)

  if [ "$REQUEST_MODE" = "apk-scan" ] && [ "$MODE" = "scan" ]; then
    cp -f "$list" "$APK_SCAN_TARGETS"
    targets_sha=$(snapshot_sha256 "$APK_SCAN_TARGETS")
    whitelist_sha=$(snapshot_sha256 "$WHITELIST")
    scan_epoch=$(date +%s)
    snapshot_id="${scan_epoch}-$(printf '%s' "$targets_sha" | cut -c1-16)"
    {
      echo "epoch=$scan_epoch"
      echo "snapshot_id=$snapshot_id"
      echo "targets_sha=$targets_sha"
      echo "whitelist_sha=$whitelist_sha"
      echo "max_file_bytes=$APK_PACKAGE_MAX_BYTES"
      echo "package_days=$APK_PACKAGE_DAYS"
      echo "bytes=$estimated"
      echo "files=$count"
      echo "engine=compat-apk-scan-v42.8"
    } >"$APK_SCAN_STATE"
    chmod 0600 "$APK_SCAN_STATE" "$APK_SCAN_TARGETS" 2>/dev/null
  fi

  if [ "$MODE" = "clean" ]; then
    err_file="$TMP_DIR/rm-apk-packages.err"
    should_stop && return 9
    compat_delete_list "$list" file "$APK_PACKAGE_MAX_BYTES" || return $?
    remaining=""
    FILES=$((FILES + ACTUAL_COUNT))
    add_bytes "$ACTUAL_BYTES"
    log_line "[安装包清理] 清理 $ACTUAL_COUNT 个，释放 $ACTUAL_BYTES bytes，未清理 $REMAINING_COUNT 个"
    [ "$ACTUAL_COUNT" -gt 0 ] && report_line cleaned low APK安装包 "$ACTUAL_COUNT" "$ACTUAL_BYTES" "${sample_path:-共享存储安装包}"
    [ "$REMAINING_COUNT" -gt 0 ] && report_line failed low APK安装包 "$REMAINING_COUNT" "$REMAINING_BYTES" "仍存在的安装包"
    rm -f "$remaining" "$err_file"
  else
    FILES=$((FILES + count))
    add_bytes "$estimated"
    log_line "[安装包扫描] 发现 $count 个过期安装包，约 $estimated bytes"
    report_line candidate low APK安装包 "$count" "$estimated" "${sample_path:-共享存储安装包}"
  fi
  rm -f "$list"
  return 0
}

run_installer_temp() {
  compat_begin_collection
  [ -d /data/local/tmp ] || return 0
  list="$TMP_DIR/installer-temp.nul"
  run_limited_command 18 find /data/local/tmp -mindepth 1 -maxdepth 2 -type f -mtime "+$INSTALLER_TEMP_DAYS" \
    \( -name '*.apk.tmp' -o -name '*.apks.tmp' -o -name '*.xapk.tmp' -o -name '*.zip.tmp' \
       -o -name '*.part' -o -name '*.download' -o -name '*.crdownload' \) \
    -size "-${MAX_FILE_BYTES}c" -print0 2>/dev/null >"$list"
  installer_collect_code=$?
  [ "$installer_collect_code" -ne 9 ] || { rm -f "$list"; return 9; }
  if [ "$installer_collect_code" -ne 0 ]; then
    CATEGORY="过期安装临时文件"
    compat_collection_failed /data/local/tmp
    rm -f "$list"
    return 0
  fi
  filter_whitelist_list "$list" || return $?
  while IFS= read -r -d '' file; do
    CATEGORY="过期安装临时文件"
    handle_file "$file" regular || { rm -f "$list"; return 9; }
  done <"$list"
  rm -f "$list"
  return 0
}

run_custom_rules() {
  compat_begin_collection
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
FRAGMENT_DAYS=$(get_uint fragment_days 7 0 365)
INSTALLER_TEMP_DAYS=$(get_uint installer_temp_days 7 1 30)
APK_PACKAGE_DAYS=$(get_uint apk_package_days 30 0 365)
APK_PACKAGE_MAX_MB=$(get_uint apk_package_max_mb 4096 16 16384)
APK_PACKAGE_MAX_BYTES=$(awk -v m="$APK_PACKAGE_MAX_MB" 'BEGIN {printf "%.0f", m * 1048576}')
ROOT_SHELL_DAYS=$(get_uint root_shell_days 14 1 90)
if [ "$FRAGMENT_DAYS" -eq 0 ]; then
  FRAGMENT_POLICY="立即清理"
  FRAGMENT_MTIME_ARGS=""
else
  FRAGMENT_POLICY="保留 ${FRAGMENT_DAYS} 天"
  FRAGMENT_MTIME_ARGS="-mtime +$FRAGMENT_DAYS"
fi
CLEAN_EMPTY_FILES=$(get_bool clean_empty_files)
CLEAN_EMPTY_DIRS=$(get_bool clean_empty_dirs)
CLEAN_ROOT_SHELLS=$(get_bool clean_root_shells)
RUN_EMPTY=0
RUN_CACHE=0
RUN_RULES=0
RUN_FRAGMENT=0
RUN_APK=0
case "$PROFILE" in
  all) RUN_EMPTY=1; RUN_CACHE=1; RUN_RULES=1; RUN_FRAGMENT=1; RUN_APK=1 ;;
  empty) RUN_EMPTY=1 ;;
  cache) RUN_CACHE=1 ;;
  rules) RUN_RULES=1 ;;
  fragment) RUN_FRAGMENT=1 ;;
  apk) RUN_APK=1 ;;
esac
WHITELIST_PATHS=$(sed -n 's/[[:space:]]*$//; /^[[:space:]]*\($\|#\)/d; p' "$WHITELIST" 2>/dev/null) || { echo "白名单读取失败，未开始删除" >&2; exit 7; }
# Expand package protection once, not once per candidate. Reuse the existing
# ancestor/descendant checks for every category, including compatibility rules.
if [ -f "$PACKAGE_WHITELIST" ]; then
  while IFS= read -r protected_package || [ -n "$protected_package" ]; do
    protected_package=${protected_package#"${protected_package%%[![:space:]]*}"}
    protected_package=${protected_package%"${protected_package##*[![:space:]]}"}
    valid_package_name "$protected_package" || continue
    for protected_root in /data/user/[0-9]*/"$protected_package" /data/user_de/[0-9]*/"$protected_package" \
      /data/media/[0-9]*/Android/data/"$protected_package" /data/media/[0-9]*/Android/obb/"$protected_package"; do
      [ -d "$protected_root" ] || continue
      WHITELIST_PATHS="$WHITELIST_PATHS
$protected_root"
    done
  done <"$PACKAGE_WHITELIST"
fi
if [ -n "$WHITELIST_PATHS" ]; then
  WHITELIST_ACTIVE=1
else
  WHITELIST_ACTIVE=0
fi

log_line "白泽 $REQUEST_MODE"
log_line "时间: $(date '+%Y-%m-%d %H:%M:%S')"
log_line "触发: $TRIGGER"
log_line "单文件上限: $MAX_MB MiB"
log_line "----------------------------------------"

STOPPED=0
if [ "$RUN_EMPTY" = "1" ] && [ "$CLEAN_EMPTY_FILES" = "1" ]; then
  set_phase "清理共享存储空文件"
  scan_shared_empty_files || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_EMPTY" = "1" ] && [ "$CLEAN_EMPTY_DIRS" = "1" ]; then
  set_phase "清理共享存储空目录"
  scan_shared_empty_dirs || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_EMPTY" = "1" ] && [ "$CLEAN_ROOT_SHELLS" = "1" ]; then
  set_phase "识别共享存储根目录空壳"
  run_shared_root_shells || STOPPED=1
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


if [ "$STOPPED" = "0" ] && [ "$RUN_RULES" = "1" ] && [ "$(get_bool clean_hidden_junk)" = "1" ]; then
  set_phase "扫描隐藏垃圾"
  run_hidden_junk || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_FRAGMENT" = "1" ] && [ "$(get_bool clean_fragments)" = "1" ]; then
  set_phase "扫描残留碎片（保留 ${FRAGMENT_DAYS} 天）"
  run_fragment_cleanup || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_APK" = "1" ] && [ "$(get_bool clean_apk_packages)" = "1" ]; then
  set_phase "扫描过期 APK 安装包（保留 ${APK_PACKAGE_DAYS} 天）"
  run_apk_packages || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_RULES" = "1" ] && [ "$(get_bool clean_installer_temp)" = "1" ]; then
  set_phase "扫描过期安装临时文件"
  run_installer_temp || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_RULES" = "1" ] && [ "$(get_bool clean_custom_rules)" = "1" ]; then
  set_phase "执行自定义规则"
  run_custom_rules || STOPPED=1
fi


# Direct CLI runs must invalidate the shared index after any deletion attempt,
# including partial failures; otherwise its TTL can resurrect removed entries.
[ "$MODE" != "clean" ] || rm -f "$STATE_DIR/index/meta.env"
set_phase "整理结果"

END_EPOCH=$(date +%s)
ELAPSED=$((END_EPOCH - START_EPOCH))
SPACE=$(human_bytes "$BYTES")

if [ "${FATAL_CODE:-0}" -ne 0 ]; then
  RESULT="任务失败（代码 $FATAL_CODE）"
elif [ "$STOPPED" = "1" ]; then
  RESULT="${STOP_REASON:-任务已中断}"
elif [ "$ERRORS" -gt 0 ]; then
  RESULT="任务未完成，已处理 $((FILES + EMPTY_FILES)) 项，失败 $ERRORS 项，释放 $SPACE"
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
    [ "$DEEP_SLOW_ITEMS" -gt 0 ] && RESULT="$RESULT，慢目录跳过 ${DEEP_SLOW_ITEMS} 项"
    [ "$DEEP_MOUNT_ITEMS" -gt 0 ] && RESULT="$RESULT，挂载保护 ${DEEP_MOUNT_ITEMS} 项"
    [ "$DEEP_TRUNCATED" = "1" ] && RESULT="$RESULT，已达到深度阶段时限"
  elif [ "$PROFILE" = "fragment" ]; then
    RESULT="碎片扫描完成，可清理 $SPACE"
  elif [ "$PROFILE" = "apk" ]; then
    RESULT="安装包扫描完成，可清理 $SPACE"
  else
    RESULT="扫描完成，可清理 $SPACE"
    [ "$CACHE_SLOW_DIRS" -gt 0 ] && RESULT="$RESULT，慢缓存目录跳过 ${CACHE_SLOW_DIRS} 项"
    [ "$CACHE_TRUNCATED" = "1" ] && RESULT="$RESULT，缓存阶段已到时限"
  fi
else
  case "$PROFILE" in
    cache)
      RESULT="缓存清理完成，释放 $SPACE"
      [ "$CACHE_SLOW_DIRS" -gt 0 ] && RESULT="$RESULT，慢目录跳过 ${CACHE_SLOW_DIRS} 项"
      [ "$CACHE_TRUNCATED" = "1" ] && RESULT="$RESULT，已到安全时限"
      ;;
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
      [ "$DEEP_SLOW_ITEMS" -gt 0 ] && RESULT="$RESULT，慢目录跳过 ${DEEP_SLOW_ITEMS} 项"
      [ "$DEEP_MOUNT_ITEMS" -gt 0 ] && RESULT="$RESULT，挂载保护 ${DEEP_MOUNT_ITEMS} 项"
      [ "$DEEP_TRUNCATED" = "1" ] && RESULT="$RESULT，已达到深度阶段时限"
      ;;
    apk) RESULT="安装包清理完成，删除 $FILES 个，期限内保留 ${APK_RETAINED:-0} 个，释放 $SPACE" ;;
    *) RESULT="清理完成，释放 $SPACE" ;;
  esac
  [ "${FATAL_CODE:-0}" -eq 0 ] && date +%s >"$STATE_DIR/last_run.epoch"
fi

# Publish before composing the final result so a publication failure is visible.
# The caller has finished reading the last batch's original NUL list here.
if [ "$MODE" = clean ] && [ "$COMPAT_DELETE_SEQ" -gt 0 ]; then
  baize_cleanup_media_publish || BAIZE_CLEANUP_MEDIA_UNCONFIRMED=1
  baize_cleanup_media_kick
  if [ "$BAIZE_CLEANUP_MEDIA_UNCONFIRMED" = 1 ]; then
    RESULT="$RESULT；媒体索引刷新未确认"
  else
    RESULT="$RESULT；媒体索引已排队核对"
  fi
fi
log_line "----------------------------------------"
log_line "$RESULT"
TOTAL_FILES=$((FILES + EMPTY_FILES))
log_line "文件总计: $FILES，其中碎片: $FRAGMENT_FILES，空文件: $EMPTY_FILES，空目录: $EMPTY_DIRS，隐藏垃圾: $HIDDEN_ITEMS，受保护: $PROTECTED_ITEMS，深度慢目录: $DEEP_SLOW_ITEMS，缓存慢目录: $CACHE_SLOW_DIRS，缓存截断: $CACHE_TRUNCATED，挂载保护: $DEEP_MOUNT_ITEMS，跳过: $SKIPPED，未清理: $ERRORS，耗时: ${ELAPSED}s"
[ "$DEEP_RULE_PARSE_SECONDS" -gt 0 ] && log_line "深度规则解析: ${DEEP_RULE_PARSE_SECONDS}s"
[ "$DEEP_STAGE_SECONDS" -gt 0 ] && log_line "深度目录处理: ${DEEP_STAGE_SECONDS}s"
[ "$DEEP_SLOWEST_SECONDS" -gt 0 ] && log_line "最慢目录: ${DEEP_SLOWEST_SECONDS}s · $DEEP_SLOWEST_PATH"

{
  echo "mode=$REQUEST_MODE"
  echo "media_refresh_unconfirmed=$BAIZE_CLEANUP_MEDIA_UNCONFIRMED"
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
  echo "changed_files=$CHANGED_FILES"
  echo "missing_files=$MISSING_FILES"
  echo "protected_items=$PROTECTED_ITEMS"
  echo "protected_bytes=$PROTECTED_BYTES"
  echo "risk_low=$RISK_LOW"
  echo "risk_medium=$RISK_MEDIUM"
  echo "risk_high=$RISK_HIGH"
  echo "risk_critical=$RISK_CRITICAL"
  echo "deep_slow_items=$DEEP_SLOW_ITEMS"
  echo "deep_mount_items=$DEEP_MOUNT_ITEMS"
  echo "deep_truncated=$DEEP_TRUNCATED"
  echo "deep_rule_parse_seconds=$DEEP_RULE_PARSE_SECONDS"
  echo "deep_stage_seconds=$DEEP_STAGE_SECONDS"
  echo "deep_slowest_seconds=$DEEP_SLOWEST_SECONDS"
  printf 'deep_slowest_path=%s\n' "$DEEP_SLOWEST_PATH" | tr '
' '  '
  echo "cache_slow_dirs=$CACHE_SLOW_DIRS"
  echo "cache_truncated=$CACHE_TRUNCATED"
  echo "deep_progress_current=$DEEP_PROGRESS_CURRENT"
  echo "deep_progress_total=$DEEP_PROGRESS_TOTAL"
  echo "elapsed=$ELAPSED"
  echo "result=$RESULT"
} >"$STATE_DIR/latest.env"

cp -f "$REPORT_FILE" "$LATEST_REPORT"

# Persist compact category/application details with each history row. Old eight-column rows remain compatible.
HISTORY_ACTION=candidate
[ "$MODE" = "clean" ] && HISTORY_ACTION=cleaned
HISTORY_CATEGORIES=$(awk -F '\t' -v action="$HISTORY_ACTION" '
  NR > 1 && $1 == action {
    name=$3; gsub(/[|;\t\r\n]/, " ", name)
    if (name == "") next
    files[name]+=$4+0; bytes[name]+=$5+0
  }
  END { for (name in bytes) printf "%d\t%d\t%s\n", bytes[name], files[name], name }
' "$REPORT_FILE" 2>/dev/null | sort -t "$(printf '\t')" -k1,1nr | head -n 8 | awk -F '\t' '
  BEGIN { first=1 }
  { if (!first) printf ";"; printf "%s|%s|%s", $3, $1, $2; first=0 }
')
HISTORY_APPS=$(awk -F '\t' '
  NR > 1 {
    package=$1; category=$2
    gsub(/[|;\t\r\n]/, " ", package); gsub(/[|;\t\r\n]/, " ", category)
    if (package == "") next
    files[package]+=$3+0; bytes[package]+=$4+0
    if (topcat[package] == "" || ($4+0) > topbytes[package]) { topcat[package]=category; topbytes[package]=$4+0 }
  }
  END { for (package in bytes) printf "%d\t%d\t%s\t%s\n", bytes[package], files[package], package, topcat[package] }
' "$APP_ITEMS" 2>/dev/null | sort -t "$(printf '\t')" -k1,1nr | head -n 8 | awk -F '\t' '
  BEGIN { first=1 }
  { if (!first) printf ";"; printf "%s|%s|%s|%s", $3, $1, $2, $4; first=0 }
')
HISTORY_CATEGORIES=$(sanitize_report_field "$HISTORY_CATEGORIES")
HISTORY_APPS=$(sanitize_report_field "$HISTORY_APPS")
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$REQUEST_MODE" "$BYTES" "$TOTAL_FILES" "$EMPTY_DIRS" "$ERRORS" "$RESULT" "$TRIGGER" "$HISTORY_CATEGORIES" "$HISTORY_APPS" >>"$HISTORY_FILE"
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
[ "$ERRORS" -eq 0 ] || exit 8
exit 0
