#!/system/bin/sh

MODDIR=${0%/*}
COMPAT_ENGINE="$MODDIR/cleaner.sh.compat"
REQUEST_MODE=${1:-scan}
TRIGGER=${2:-manual}
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
CORPSE_SCAN_STATE="$STATE_DIR/corpse_scan.env"
CORPSE_SCAN_TARGETS="$STATE_DIR/corpse_scan.targets"
DEEP_SCAN_STATE="$STATE_DIR/deep_scan.env"
DEEP_SCAN_TARGETS="$STATE_DIR/deep_scan.targets"
CACHE_SCAN_STATE="$STATE_DIR/cache_scan.env"
CACHE_SCAN_ITEMS="$STATE_DIR/cache_scan.items.tsv"
CACHE_SCAN_TARGETS="$STATE_DIR/cache_scan.targets"
DEEP_RULES=${BAIZE_DEEP_RULES:-$MODDIR/config/deep.rules}
NATIVE_ENGINE=${BAIZE_NATIVE_ENGINE:-$MODDIR/bin/arm64-v8a/baize_engine}

fallback() {
  [ -f "$COMPAT_ENGINE" ] || { echo "兼容清理引擎缺失" >&2; exit 5; }
  exec /system/bin/sh "$COMPAT_ENGINE" "$@"
}

case "$REQUEST_MODE" in
  corpse-scan|deep-scan|cache-scan|cache-clean) ;;
  *) fallback "$@" ;;
esac

mkdir -p "$STATE_DIR" "$REPORT_DIR" "$LOG_DIR"
[ -f "$CONFIG" ] || cp -f "$MODDIR/config/default.conf" "$CONFIG"
[ -f "$WHITELIST" ] || cp -f "$MODDIR/config/whitelist.conf" "$WHITELIST"
[ -f "$PACKAGE_WHITELIST" ] || : >"$PACKAGE_WHITELIST"

if [ "$REQUEST_MODE" != "cache-clean" ]; then
  [ -x "$NATIVE_ENGINE" ] || {
    [ "$REQUEST_MODE" = "cache-scan" ] && { echo "原生缓存扫描器不可用" >&2; exit 8; }
    fallback "$@"
  }
  case "$(uname -m 2>/dev/null)" in
    aarch64|arm64) ;;
    x86_64) [ -n "${BAIZE_NATIVE_TEST:-}" ] || {
      [ "$REQUEST_MODE" = "cache-scan" ] && exit 8
      fallback "$@"
    } ;;
    *) [ "$REQUEST_MODE" = "cache-scan" ] && exit 8; fallback "$@" ;;
  esac
  native_enabled=$(sed -n 's/^native_scanner_enabled=//p' "$CONFIG" 2>/dev/null | tail -n 1)
  [ "$native_enabled" = "0" ] && {
    [ "$REQUEST_MODE" = "cache-scan" ] && exit 8
    fallback "$@"
  }
fi

pid_is_baize_task() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in
    *baize_v2*cleaner.sh*|*baize-v2*cleaner.sh*|*baize_engine*) return 0 ;;
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
REPORT_FILE="$REPORT_DIR/$STAMP-$REQUEST_MODE.tsv"
TARGETS_TMP="$TMP_DIR/$REQUEST_MODE.targets"
ITEMS_TMP="$TMP_DIR/cache-scan.items.tsv"
SUMMARY_FILE="$TMP_DIR/native-summary.env"
LOG_FILE="$LOG_DIR/$STAMP-$REQUEST_MODE.log"
INSTALLED_ROOT=${BAIZE_INSTALLED_ROOT:-$TMP_DIR/installed}
mkdir -p "$INSTALLED_ROOT"

set_phase() {
  phase=$1
  current=${2:-0}
  total=${3:-0}
  path=${4:-}
  tmp="$RUNNING_FILE.tmp.$$"
  {
    echo "mode=$REQUEST_MODE"
    echo "phase=$phase"
    echo "started=$START_EPOCH"
    echo "progress_current=$current"
    echo "progress_total=$total"
    printf 'current_path=%s\n' "$path" | tr '\r\n' '  '
    echo "engine=native-c-arm64"
  } >"$tmp"
  mv -f "$tmp" "$RUNNING_FILE"
}

get_config_uint() {
  key=$1 fallback_value=$2 min=$3 max=$4
  value=$(sed -n "s/^$key=//p" "$CONFIG" 2>/dev/null | tail -n 1)
  case "$value" in ''|*[!0-9]*) value=$fallback_value ;; esac
  [ "$value" -lt "$min" ] && value=$min
  [ "$value" -gt "$max" ] && value=$max
  echo "$value"
}
summary_value() { sed -n "s/^$1=//p" "$SUMMARY_FILE" 2>/dev/null | tail -n 1; }
summary_number() { result=$(summary_value "$1"); case "$result" in ''|*[!0-9]*) result=0 ;; esac; echo "$result"; }
state_value() { sed -n "s/^$1=//p" "$CACHE_SCAN_STATE" 2>/dev/null | tail -n 1; }
human_bytes() { awk -v b="$1" 'BEGIN { if (b>=1073741824) printf "%.2f GB",b/1073741824; else if(b>=1048576) printf "%.2f MB",b/1048576; else if(b>=1024) printf "%.2f KB",b/1024; else printf "%.0f B",b }'; }
file_sha() { [ -f "$1" ] && sha256sum "$1" 2>/dev/null | awk 'NR==1{print $1}' || echo missing; }
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

write_latest() {
  mode=$1 files=$2 bytes=$3 errors=$4 skipped=$5 elapsed=$6 result=$7
  {
    echo "mode=$mode"
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
    echo "engine=native-c-arm64"
    echo "result=$result"
  } >"$STATE_DIR/latest.env"
}

run_cache_snapshot_clean() {
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
  elapsed_minutes=$(((age + 59) / 60))
  cutoff_minutes=$((min_age_days * 1440 + elapsed_minutes))
  [ "$cutoff_minutes" -lt 0 ] && cutoff_minutes=0

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
    find "$target" -xdev -mindepth 1 -type f ! -size "+${max_file_bytes}c" -mmin "+$cutoff_minutes" -print0 >"$list" 2>/dev/null
    count=$(count_nul "$list")
    case "$count" in ''|*[!0-9]*) count=0 ;; esac
    if [ "$count" -gt 0 ]; then
      estimated=$(bytes_from_list "$list")
      case "$estimated" in ''|*[!0-9]*) estimated=0 ;; esac
      xargs -0 -n 200 rm -f -- <"$list" 2>/dev/null
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
    find "$target" -xdev -depth -mindepth 1 -type d -empty -delete 2>/dev/null
    rm -f "$list" "$remaining"
  done <"$CACHE_SCAN_TARGETS"

  end=$(date +%s)
  elapsed=$((end - START_EPOCH))
  if [ "$code" -eq 9 ]; then
    result="缓存快照清理已停止，已清理 $(human_bytes "$deleted_bytes")"
  else
    result="缓存快照清理完成，已清理 $(human_bytes "$deleted_bytes")"
    rm -f "$CACHE_SCAN_STATE" "$CACHE_SCAN_TARGETS" "$CACHE_SCAN_ITEMS"
  fi

  write_latest "cache-clean" "$deleted_files" "$deleted_bytes" "$errors" "$skipped" "$elapsed" "$result"
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
  [ "$code" -eq 9 ] && exit 9
  exit 0
}

if [ "$REQUEST_MODE" = "cache-clean" ]; then
  run_cache_snapshot_clean
fi

printf 'package\tcategory\tfiles\tbytes\n' >"$REPORT_DIR/apps-latest.tsv"
printf 'package\tcategory\tfiles\tbytes\terrors\tsample_path\n' >"$REPORT_DIR/app-items-latest.tsv"

max_mb=$(get_config_uint max_file_mb 256 1 16384)
MAX_FILE_BYTES=$((max_mb * 1024 * 1024))
code=0

case "$REQUEST_MODE" in
  corpse-scan)
    set_phase "原生引擎读取已安装应用"
    if [ -z "${BAIZE_INSTALLED_ROOT:-}" ]; then
      found_users=0
      for userdir in "$MEDIA_ROOT"/[0-9]*; do
        [ -d "$userdir" ] || continue
        user=${userdir##*/}
        packages="$INSTALLED_ROOT/$user.txt"
        : >"$packages"
        command -v cmd >/dev/null 2>&1 && cmd package list packages --user "$user" 2>/dev/null | sed 's/^package://' | sort -u >"$packages"
        [ -s "$packages" ] || { command -v pm >/dev/null 2>&1 && pm list packages --user "$user" 2>/dev/null | sed 's/^package://' | sort -u >"$packages"; }
        if [ ! -s "$packages" ]; then
          echo "[原生回退] 无法读取用户 $user 的已安装包列表" >>"$LOG_FILE"
          cleanup_lock; trap - EXIT INT TERM; fallback "$@"
        fi
        found_users=$((found_users + 1))
      done
      if [ "$found_users" -eq 0 ]; then cleanup_lock; trap - EXIT INT TERM; fallback "$@"; fi
    fi
    set_phase "启动 C 原生卸载残留扫描"
    "$NATIVE_ENGINE" scan-corpses --media-root "$MEDIA_ROOT" --installed-root "$INSTALLED_ROOT" \
      --whitelist "$WHITELIST" --max-file-bytes "$MAX_FILE_BYTES" --report "$REPORT_FILE" \
      --targets "$TARGETS_TMP" --summary "$SUMMARY_FILE" --progress "$RUNNING_FILE" --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1 || code=$?
    ;;
  deep-scan)
    [ -f "$DEEP_RULES" ] || { cleanup_lock; trap - EXIT INT TERM; fallback "$@"; }
    high=$(sed -n 's/^deep_high_risk_enabled=//p' "$CONFIG" 2>/dev/null | tail -n 1)
    [ "$high" = "1" ] || high=0
    set_phase "启动 C 原生深度规则扫描"
    "$NATIVE_ENGINE" scan-deep --rules "$DEEP_RULES" --whitelist "$WHITELIST" \
      --max-file-bytes "$MAX_FILE_BYTES" --allow-high-risk "$high" --report "$REPORT_FILE" \
      --targets "$TARGETS_TMP" --summary "$SUMMARY_FILE" --progress "$RUNNING_FILE" --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1 || code=$?
    ;;
  cache-scan)
    rm -f "$CACHE_SCAN_STATE" "$CACHE_SCAN_TARGETS" "$CACHE_SCAN_ITEMS"
    cache_days=$(get_config_uint app_cache_days 0 0 365)
    external_days=$(get_config_uint external_cache_days 0 0 365)
    [ "$external_days" -lt "$cache_days" ] && cache_days=$external_days
    set_phase "启动 C 原生应用缓存扫描"
    "$NATIVE_ENGINE" scan-cache --data-root "$DATA_ROOT" --media-root "$MEDIA_ROOT" \
      --whitelist "$WHITELIST" --package-whitelist "$PACKAGE_WHITELIST" --min-age-days "$cache_days" \
      --max-file-bytes "$MAX_FILE_BYTES" --report "$REPORT_FILE" --targets "$TARGETS_TMP" \
      --items "$ITEMS_TMP" --summary "$SUMMARY_FILE" --progress "$RUNNING_FILE" --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1 || code=$?
    ;;
esac

if [ "$code" -ne 0 ] && [ "$code" -ne 9 ]; then
  echo "[原生回退] C 扫描器失败，代码 $code" >>"$LOG_FILE"
  rm -f "$REPORT_FILE" "$TARGETS_TMP" "$ITEMS_TMP" "$SUMMARY_FILE"
  cleanup_lock; trap - EXIT INT TERM
  [ "$REQUEST_MODE" = "cache-scan" ] && exit "$code"
  fallback "$@"
fi

FILES=$(summary_number files)
DIRS=$(summary_number dirs)
EMPTY_DIRS=$(summary_number empty_dirs)
BYTES=$(summary_number bytes)
SKIPPED=$(summary_number skipped)
ERRORS=$(summary_number errors)
PROTECTED_ITEMS=$(summary_number protected_items)
PROTECTED_BYTES=$(summary_number protected_bytes)
CANDIDATES=$(summary_number candidates)
TARGET_COUNT=$(summary_number targets)
RISK_LOW=$(summary_number risk_low)
RISK_MEDIUM=$(summary_number risk_medium)
RISK_HIGH=$(summary_number risk_high)
RISK_CRITICAL=$(summary_number risk_critical)
MOUNT_ITEMS=$(summary_number mount_items)
TRUNCATED=$(summary_number truncated)
WHITELISTED=$(summary_number whitelisted)
TOTAL_FILES=$((FILES + EMPTY_DIRS))
END_EPOCH=$(date +%s)
ELAPSED=$((END_EPOCH - START_EPOCH))
SPACE=$(human_bytes "$BYTES")

if [ "$code" -eq 9 ]; then
  RESULT="原生扫描已停止"
  rm -f "$TARGETS_TMP" "$ITEMS_TMP"
else
  case "$REQUEST_MODE" in
    corpse-scan)
      RESULT="卸载残留原生扫描完成，可清理 $SPACE"
      chmod 0600 "$TARGETS_TMP" 2>/dev/null
      mv -f "$TARGETS_TMP" "$CORPSE_SCAN_TARGETS"
      { echo "epoch=$(date +%s)"; echo "bytes=$BYTES"; echo "items=$TOTAL_FILES"; echo "engine=native-c-arm64"; echo "targets=$TARGET_COUNT"; } >"$CORPSE_SCAN_STATE"
      ;;
    deep-scan)
      RESULT="深度规则原生扫描完成，可清理 $SPACE"
      chmod 0600 "$TARGETS_TMP" 2>/dev/null
      mv -f "$TARGETS_TMP" "$DEEP_SCAN_TARGETS"
      rules_sha=$(file_sha "$DEEP_RULES")
      { echo "epoch=$(date +%s)"; echo "rules_sha=$rules_sha"; echo "bytes=$BYTES"; echo "items=$TOTAL_FILES"; echo "engine=native-c-arm64"; echo "targets=$TARGET_COUNT"; } >"$DEEP_SCAN_STATE"
      ;;
    cache-scan)
      RESULT="应用缓存原生扫描完成，可清理 $SPACE"
      chmod 0600 "$TARGETS_TMP" "$ITEMS_TMP" 2>/dev/null
      mv -f "$TARGETS_TMP" "$CACHE_SCAN_TARGETS"
      mv -f "$ITEMS_TMP" "$CACHE_SCAN_ITEMS"
      scan_epoch=$(date +%s)
      targets_sha=$(file_sha "$CACHE_SCAN_TARGETS")
      snapshot_id="${scan_epoch}-${targets_sha%????????????????????????????????????????????????}"
      {
        echo "epoch=$scan_epoch"
        echo "snapshot_id=$snapshot_id"
        echo "targets_sha=$targets_sha"
        echo "whitelist_sha=$(file_sha "$WHITELIST")"
        echo "package_whitelist_sha=$(file_sha "$PACKAGE_WHITELIST")"
        echo "min_age_days=$cache_days"
        echo "max_file_bytes=$MAX_FILE_BYTES"
        echo "bytes=$BYTES"
        echo "files=$FILES"
        echo "items=$CANDIDATES"
        echo "targets=$TARGET_COUNT"
        echo "engine=native-c-arm64"
      } >"$CACHE_SCAN_STATE"
      chmod 0600 "$CACHE_SCAN_STATE" "$CACHE_SCAN_TARGETS" "$CACHE_SCAN_ITEMS" 2>/dev/null
      awk -F '\t' 'NR==1{next}{print $1"\t"$2"\t"$3"\t"$4}' "$CACHE_SCAN_ITEMS" >>"$REPORT_DIR/apps-latest.tsv"
      awk -F '\t' 'NR==1{next}{print $1"\t"$2"\t"$3"\t"$4"\t0\t"$6}' "$CACHE_SCAN_ITEMS" >>"$REPORT_DIR/app-items-latest.tsv"
      ;;
  esac
fi

{
  echo "mode=$REQUEST_MODE"
  echo "time=$(date '+%Y-%m-%d %H:%M:%S')"
  echo "files=$TOTAL_FILES"
  echo "regular_files=$FILES"
  echo "empty_files=0"
  echo "empty_dirs=$EMPTY_DIRS"
  echo "hidden_items=0"
  echo "fragment_files=0"
  echo "bytes=$BYTES"
  echo "skipped=$SKIPPED"
  echo "errors=$ERRORS"
  echo "protected_items=$PROTECTED_ITEMS"
  echo "protected_bytes=$PROTECTED_BYTES"
  echo "risk_low=$RISK_LOW"
  echo "risk_medium=$RISK_MEDIUM"
  echo "risk_high=$RISK_HIGH"
  echo "risk_critical=$RISK_CRITICAL"
  echo "deep_slow_items=0"
  echo "deep_mount_items=$MOUNT_ITEMS"
  echo "deep_truncated=$TRUNCATED"
  echo "cache_slow_dirs=0"
  echo "cache_truncated=0"
  echo "deep_progress_current=$TARGET_COUNT"
  echo "deep_progress_total=$TARGET_COUNT"
  echo "whitelisted=$WHITELISTED"
  echo "elapsed=$ELAPSED"
  echo "engine=native-c-arm64"
  echo "result=$RESULT"
} >"$STATE_DIR/latest.env"

[ -f "$REPORT_FILE" ] && cp -f "$REPORT_FILE" "$REPORT_DIR/latest.tsv"
{
  echo "----------------------------------------"
  echo "$RESULT"
  echo "原生引擎: C arm64 42.5"
  echo "候选: $CANDIDATES | 文件: $FILES | 目录: $DIRS | 受保护: $PROTECTED_ITEMS | 跳过: $SKIPPED | 错误: $ERRORS | 耗时: ${ELAPSED}s"
} >>"$LOG_FILE"
cp -f "$LOG_FILE" "$LOG_DIR/latest.log"

case "$REQUEST_MODE" in
  corpse-scan) history_name="卸载残留" ;;
  deep-scan) history_name="深度规则" ;;
  cache-scan) history_name="应用缓存" ;;
esac
history_category="$history_name|$BYTES|$TOTAL_FILES"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$(date '+%Y-%m-%d %H:%M:%S')" "$REQUEST_MODE" "$BYTES" "$TOTAL_FILES" "$EMPTY_DIRS" "$ERRORS" \
  "$RESULT" "$TRIGGER" "$history_category" "" >>"$HISTORY_FILE"
tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"

echo "$RESULT"
echo "原生引擎: C arm64 | 候选: $CANDIDATES | 文件: $FILES | 受保护: $PROTECTED_ITEMS | 耗时: ${ELAPSED}s"
cleanup_lock
trap - EXIT INT TERM
[ "$code" -eq 9 ] && exit 9
exit 0
