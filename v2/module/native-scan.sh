#!/system/bin/sh

MODDIR=${0%/*}
MODE=${1:-cache-scan}
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
DEEP_RULES=${BAIZE_DEEP_RULES:-$MODDIR/config/deep.rules}
NATIVE_ENGINE=${BAIZE_NATIVE_ENGINE:-$MODDIR/bin/arm64-v8a/baize_engine}

case "$MODE" in cache-scan|deep-scan|corpse-scan) ;; *) echo "不支持的原生扫描模式：$MODE" >&2; exit 2 ;; esac

mkdir -p "$STATE_DIR" "$REPORT_DIR" "$LOG_DIR"
[ -f "$CONFIG" ] || cp -f "$MODDIR/config/default.conf" "$CONFIG"
[ -f "$WHITELIST" ] || cp -f "$MODDIR/config/whitelist.conf" "$WHITELIST"
[ -f "$PACKAGE_WHITELIST" ] || : >"$PACKAGE_WHITELIST"

[ -x "$NATIVE_ENGINE" ] || { echo "C 原生扫描器不可用，请重新刷入完整模块" >&2; exit 8; }
case "$(uname -m 2>/dev/null)" in
  aarch64|arm64) ;;
  x86_64) [ -n "${BAIZE_NATIVE_TEST:-}" ] || { echo "当前架构不支持原生扫描" >&2; exit 8; } ;;
  *) echo "当前架构不支持原生扫描" >&2; exit 8 ;;
esac
native_enabled=$(sed -n 's/^native_scanner_enabled=//p' "$CONFIG" 2>/dev/null | tail -n 1)
[ "$native_enabled" = "0" ] && { echo "原生扫描器已在设置中关闭" >&2; exit 8; }

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
TARGETS_TMP="$TMP_DIR/$MODE.targets"
ITEMS_TMP="$TMP_DIR/cache-scan.items.tsv"
SUMMARY_FILE="$TMP_DIR/native-summary.env"
LOG_FILE="$LOG_DIR/$STAMP-$MODE.log"
INSTALLED_ROOT=${BAIZE_INSTALLED_ROOT:-$TMP_DIR/installed}
mkdir -p "$INSTALLED_ROOT"

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
summary_number() { value=$(summary_value "$1"); case "$value" in ''|*[!0-9]*) value=0 ;; esac; echo "$value"; }
file_sha() { [ -f "$1" ] && sha256sum "$1" 2>/dev/null | awk 'NR==1{print $1}' || echo missing; }
human_bytes() { awk -v b="$1" 'BEGIN { if (b>=1073741824) printf "%.2f GB",b/1073741824; else if(b>=1048576) printf "%.2f MB",b/1048576; else if(b>=1024) printf "%.2f KB",b/1024; else printf "%.0f B",b }'; }

MAX_MB=$(get_config_uint max_file_mb 256 1 16384)
MAX_FILE_BYTES=$((MAX_MB * 1024 * 1024))
code=0
cache_days=0
allow_high=0

case "$MODE" in
  corpse-scan)
    rm -f "$STATE_DIR/corpse_scan.env" "$STATE_DIR/corpse_scan.targets"
    set_phase "正在读取已安装应用" 0 0 ""
    if [ -z "${BAIZE_INSTALLED_ROOT:-}" ]; then
      found_users=0
      for userdir in "$MEDIA_ROOT"/[0-9]*; do
        [ -d "$userdir" ] || continue
        user=${userdir##*/}
        packages="$INSTALLED_ROOT/$user.txt"
        : >"$packages"
        command -v cmd >/dev/null 2>&1 && cmd package list packages --user "$user" 2>/dev/null | sed 's/^package://' | sort -u >"$packages"
        [ -s "$packages" ] || { command -v pm >/dev/null 2>&1 && pm list packages --user "$user" 2>/dev/null | sed 's/^package://' | sort -u >"$packages"; }
        [ -s "$packages" ] || { echo "无法读取用户 $user 的安装包列表" >&2; exit 8; }
        found_users=$((found_users + 1))
      done
      [ "$found_users" -gt 0 ] || { echo "没有找到可扫描的 Android 用户" >&2; exit 8; }
    fi
    set_phase "启动 C 原生卸载残留扫描" 0 0 ""
    "$NATIVE_ENGINE" scan-corpses --media-root "$MEDIA_ROOT" --installed-root "$INSTALLED_ROOT" \
      --whitelist "$WHITELIST" --max-file-bytes "$MAX_FILE_BYTES" --report "$REPORT_FILE" \
      --targets "$TARGETS_TMP" --summary "$SUMMARY_FILE" --progress "$RUNNING_FILE" --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1 || code=$?
    ;;
  deep-scan)
    rm -f "$STATE_DIR/deep_scan.env" "$STATE_DIR/deep_scan.targets"
    [ -f "$DEEP_RULES" ] || { echo "完整深度规则库缺失" >&2; exit 8; }
    allow_high=$(sed -n 's/^deep_high_risk_enabled=//p' "$CONFIG" 2>/dev/null | tail -n 1)
    [ "$allow_high" = "1" ] || allow_high=0
    set_phase "启动 C 原生深度规则扫描" 0 0 ""
    "$NATIVE_ENGINE" scan-deep --rules "$DEEP_RULES" --whitelist "$WHITELIST" \
      --max-file-bytes "$MAX_FILE_BYTES" --allow-high-risk "$allow_high" --report "$REPORT_FILE" \
      --targets "$TARGETS_TMP" --summary "$SUMMARY_FILE" --progress "$RUNNING_FILE" --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1 || code=$?
    ;;
  cache-scan)
    rm -f "$STATE_DIR/cache_scan.env" "$STATE_DIR/cache_scan.targets" "$STATE_DIR/cache_scan.items.tsv"
    cache_days=$(get_config_uint app_cache_days 0 0 365)
    external_days=$(get_config_uint external_cache_days 0 0 365)
    [ "$external_days" -lt "$cache_days" ] && cache_days=$external_days
    set_phase "启动 C 原生应用缓存扫描" 0 0 ""
    "$NATIVE_ENGINE" scan-cache --data-root "$DATA_ROOT" --media-root "$MEDIA_ROOT" \
      --whitelist "$WHITELIST" --package-whitelist "$PACKAGE_WHITELIST" --min-age-days "$cache_days" \
      --max-file-bytes "$MAX_FILE_BYTES" --report "$REPORT_FILE" --targets "$TARGETS_TMP" \
      --items "$ITEMS_TMP" --summary "$SUMMARY_FILE" --progress "$RUNNING_FILE" --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1 || code=$?
    ;;
esac

if [ "$code" -ne 0 ] && [ "$code" -ne 9 ]; then
  echo "C 原生扫描器失败，代码 $code" >>"$LOG_FILE"
  rm -f "$REPORT_FILE" "$TARGETS_TMP" "$ITEMS_TMP" "$SUMMARY_FILE"
  exit "$code"
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
TOTAL_ITEMS=$((FILES + EMPTY_DIRS))
END_EPOCH=$(date +%s)
ELAPSED=$((END_EPOCH - START_EPOCH))
SPACE=$(human_bytes "$BYTES")
snapshot_id=""

if [ "$code" -eq 9 ]; then
  RESULT="原生扫描已停止"
  rm -f "$TARGETS_TMP" "$ITEMS_TMP"
else
  scan_epoch=$(date +%s)
  targets_sha=$(file_sha "$TARGETS_TMP")
  snapshot_id="${scan_epoch}-$(printf '%s' "$targets_sha" | cut -c1-16)"
  case "$MODE" in
    corpse-scan)
      RESULT="卸载残留原生扫描完成，可清理 $SPACE"
      mv -f "$TARGETS_TMP" "$STATE_DIR/corpse_scan.targets"
      targets_sha=$(file_sha "$STATE_DIR/corpse_scan.targets")
      {
        echo "epoch=$scan_epoch"
        echo "snapshot_id=$snapshot_id"
        echo "targets_sha=$targets_sha"
        echo "whitelist_sha=$(file_sha "$WHITELIST")"
        echo "max_file_bytes=$MAX_FILE_BYTES"
        echo "bytes=$BYTES"
        echo "files=$TOTAL_ITEMS"
        echo "items=$CANDIDATES"
        echo "targets=$TARGET_COUNT"
        echo "engine=native-c-arm64"
      } >"$STATE_DIR/corpse_scan.env"
      chmod 0600 "$STATE_DIR/corpse_scan.env" "$STATE_DIR/corpse_scan.targets" 2>/dev/null
      ;;
    deep-scan)
      RESULT="深度规则原生扫描完成，可清理 $SPACE"
      mv -f "$TARGETS_TMP" "$STATE_DIR/deep_scan.targets"
      targets_sha=$(file_sha "$STATE_DIR/deep_scan.targets")
      {
        echo "epoch=$scan_epoch"
        echo "snapshot_id=$snapshot_id"
        echo "targets_sha=$targets_sha"
        echo "whitelist_sha=$(file_sha "$WHITELIST")"
        echo "rules_sha=$(file_sha "$DEEP_RULES")"
        echo "allow_high_risk=$allow_high"
        echo "max_file_bytes=$MAX_FILE_BYTES"
        echo "bytes=$BYTES"
        echo "files=$TOTAL_ITEMS"
        echo "items=$CANDIDATES"
        echo "targets=$TARGET_COUNT"
        echo "engine=native-c-arm64"
      } >"$STATE_DIR/deep_scan.env"
      chmod 0600 "$STATE_DIR/deep_scan.env" "$STATE_DIR/deep_scan.targets" 2>/dev/null
      ;;
    cache-scan)
      RESULT="应用缓存原生扫描完成，可清理 $SPACE"
      mv -f "$TARGETS_TMP" "$STATE_DIR/cache_scan.targets"
      mv -f "$ITEMS_TMP" "$STATE_DIR/cache_scan.items.tsv"
      targets_sha=$(file_sha "$STATE_DIR/cache_scan.targets")
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
      } >"$STATE_DIR/cache_scan.env"
      chmod 0600 "$STATE_DIR/cache_scan.env" "$STATE_DIR/cache_scan.targets" "$STATE_DIR/cache_scan.items.tsv" 2>/dev/null
      printf 'package\tcategory\tfiles\tbytes\n' >"$REPORT_DIR/apps-latest.tsv"
      printf 'package\tcategory\tfiles\tbytes\terrors\tsample_path\n' >"$REPORT_DIR/app-items-latest.tsv"
      awk -F '\t' 'NR==1{next}{print $1"\t"$2"\t"$3"\t"$4}' "$STATE_DIR/cache_scan.items.tsv" >>"$REPORT_DIR/apps-latest.tsv"
      awk -F '\t' 'NR==1{next}{print $1"\t"$2"\t"$3"\t"$4"\t0\t"$6}' "$STATE_DIR/cache_scan.items.tsv" >>"$REPORT_DIR/app-items-latest.tsv"
      ;;
  esac
fi

{
  echo "mode=$MODE"
  echo "time=$(date '+%Y-%m-%d %H:%M:%S')"
  echo "files=$TOTAL_ITEMS"
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
  echo "原生引擎: C arm64 42.6"
  echo "候选: $CANDIDATES | 文件: $FILES | 目录: $DIRS | 受保护: $PROTECTED_ITEMS | 跳过: $SKIPPED | 错误: $ERRORS | 耗时: ${ELAPSED}s"
} >>"$LOG_FILE"
cp -f "$LOG_FILE" "$LOG_DIR/latest.log"

case "$MODE" in corpse-scan) history_name="卸载残留" ;; deep-scan) history_name="深度规则" ;; cache-scan) history_name="应用缓存" ;; esac
history_category="$history_name|$BYTES|$TOTAL_ITEMS"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$(date '+%Y-%m-%d %H:%M:%S')" "$MODE" "$BYTES" "$TOTAL_ITEMS" "$EMPTY_DIRS" "$ERRORS" \
  "$RESULT" "$TRIGGER" "$history_category" "$snapshot_id" >>"$HISTORY_FILE"
tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"

echo "$RESULT"
echo "原生引擎: C arm64 | 候选: $CANDIDATES | 文件: $FILES | 受保护: $PROTECTED_ITEMS | 耗时: ${ELAPSED}s"
cleanup_lock
trap - EXIT INT TERM
[ "$code" -eq 9 ] && exit 9
exit 0
