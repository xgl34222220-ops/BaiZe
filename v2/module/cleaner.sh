#!/system/bin/sh

MODDIR=${0%/*}
COMPAT_ENGINE="$MODDIR/cleaner.sh.compat"
REQUEST_MODE=${1:-scan}
TRIGGER=${2:-manual}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
CONFIG="$STATE_DIR/config.conf"
WHITELIST="$STATE_DIR/whitelist.conf"
REPORT_DIR="$STATE_DIR/reports"
LOG_DIR="$STATE_DIR/logs"
LOCK_DIR="$STATE_DIR/run.lock"
RUNNING_FILE="$STATE_DIR/running.env"
STOP_FILE="$STATE_DIR/stop"
CORPSE_SCAN_STATE="$STATE_DIR/corpse_scan.env"
CORPSE_SCAN_TARGETS="$STATE_DIR/corpse_scan.targets"
HISTORY_FILE="$STATE_DIR/history.tsv"
NATIVE_ENGINE=${BAIZE_NATIVE_ENGINE:-$MODDIR/bin/arm64-v8a/baize_engine}

fallback() {
  [ -f "$COMPAT_ENGINE" ] || { echo "兼容清理引擎缺失" >&2; exit 5; }
  exec /system/bin/sh "$COMPAT_ENGINE" "$@"
}

[ "$REQUEST_MODE" = "corpse-scan" ] || fallback "$@"
[ -x "$NATIVE_ENGINE" ] || fallback "$@"
case "$(uname -m 2>/dev/null)" in
  aarch64|arm64) ;;
  x86_64) [ -n "${BAIZE_NATIVE_TEST:-}" ] || fallback "$@" ;;
  *) fallback "$@" ;;
esac

mkdir -p "$STATE_DIR" "$REPORT_DIR" "$LOG_DIR"
[ -f "$CONFIG" ] || cp -f "$MODDIR/config/default.conf" "$CONFIG"
[ -f "$WHITELIST" ] || cp -f "$MODDIR/config/whitelist.conf" "$WHITELIST"

native_enabled=$(sed -n 's/^native_scanner_enabled=//p' "$CONFIG" 2>/dev/null | tail -n 1)
[ "$native_enabled" = "0" ] && fallback "$@"

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  old_pid=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
  case "$old_pid" in ''|*[!0-9]*) old_pid=0 ;; esac
  if [ "$old_pid" -gt 1 ] && kill -0 "$old_pid" 2>/dev/null; then
    echo "已有扫描或清理任务正在运行"
    exit 3
  fi
  rm -rf -- "$LOCK_DIR" 2>/dev/null
  mkdir "$LOCK_DIR" 2>/dev/null || { echo "无法恢复任务锁"; exit 4; }
fi
printf '%s\n' "$$" >"$LOCK_DIR/pid"
TMP_DIR="$LOCK_DIR/tmp"
mkdir -p "$TMP_DIR"

cleanup_lock() {
  rm -f "$RUNNING_FILE" 2>/dev/null
  rm -rf -- "$LOCK_DIR" 2>/dev/null
}
trap cleanup_lock EXIT INT TERM
rm -f "$STOP_FILE"

START_EPOCH=$(date +%s)
STAMP=$(date '+%Y-%m-%d_%H-%M-%S')
REPORT_FILE="$REPORT_DIR/$STAMP-corpse-scan.tsv"
TARGETS_TMP="$TMP_DIR/corpse-scan.targets"
SUMMARY_FILE="$TMP_DIR/native-summary.env"
LOG_FILE="$LOG_DIR/$STAMP-corpse-scan.log"
INSTALLED_ROOT=${BAIZE_INSTALLED_ROOT:-$TMP_DIR/installed}
mkdir -p "$INSTALLED_ROOT"
printf 'package\tcategory\tfiles\tbytes\n' >"$REPORT_DIR/apps-latest.tsv"
printf 'package\tcategory\tfiles\tbytes\terrors\tsample_path\n' >"$REPORT_DIR/app-items-latest.tsv"

set_phase() {
  phase=$1
  current=${2:-0}
  total=${3:-0}
  path=${4:-}
  tmp="$RUNNING_FILE.tmp.$$"
  {
    echo "mode=corpse-scan"
    echo "phase=$phase"
    echo "started=$START_EPOCH"
    echo "progress_current=$current"
    echo "progress_total=$total"
    printf 'current_path=%s\n' "$path" | tr '\r\n' '  '
    echo "engine=native-c-arm64"
  } >"$tmp"
  mv -f "$tmp" "$RUNNING_FILE"
}

set_phase "原生引擎读取已安装应用"

if [ -z "${BAIZE_INSTALLED_ROOT:-}" ]; then
  found_users=0
  for userdir in "$MEDIA_ROOT"/[0-9]*; do
    [ -d "$userdir" ] || continue
    user=${userdir##*/}
    case "$user" in ''|*[!0-9]*) continue ;; esac
    packages="$INSTALLED_ROOT/$user.txt"
    : >"$packages"
    if command -v cmd >/dev/null 2>&1; then
      cmd package list packages --user "$user" 2>/dev/null | sed 's/^package://' | sort -u >"$packages"
    fi
    if [ ! -s "$packages" ] && command -v pm >/dev/null 2>&1; then
      pm list packages --user "$user" 2>/dev/null | sed 's/^package://' | sort -u >"$packages"
    fi
    if [ ! -s "$packages" ]; then
      echo "[原生回退] 无法读取用户 $user 的已安装包列表" >>"$LOG_FILE"
      cleanup_lock
      trap - EXIT INT TERM
      fallback "$@"
    fi
    found_users=$((found_users + 1))
  done
  if [ "$found_users" -eq 0 ]; then
    echo "[原生回退] 未发现可扫描的 Android 用户" >>"$LOG_FILE"
    cleanup_lock
    trap - EXIT INT TERM
    fallback "$@"
  fi
fi

max_mb=$(sed -n 's/^max_file_mb=//p' "$CONFIG" 2>/dev/null | tail -n 1)
case "$max_mb" in ''|*[!0-9]*) max_mb=256 ;; esac
[ "$max_mb" -lt 1 ] && max_mb=1
[ "$max_mb" -gt 4096 ] && max_mb=4096
MAX_FILE_BYTES=$((max_mb * 1024 * 1024))

set_phase "启动 C 原生卸载残留扫描"
"$NATIVE_ENGINE" scan-corpses \
  --media-root "$MEDIA_ROOT" \
  --installed-root "$INSTALLED_ROOT" \
  --whitelist "$WHITELIST" \
  --max-file-bytes "$MAX_FILE_BYTES" \
  --report "$REPORT_FILE" \
  --targets "$TARGETS_TMP" \
  --summary "$SUMMARY_FILE" \
  --progress "$RUNNING_FILE" \
  --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1
code=$?

value() {
  sed -n "s/^$1=//p" "$SUMMARY_FILE" 2>/dev/null | tail -n 1
}
number() {
  result=$(value "$1")
  case "$result" in ''|*[!0-9]*) result=0 ;; esac
  echo "$result"
}
human_bytes() {
  awk -v b="$1" 'BEGIN {
    if (b >= 1073741824) printf "%.2f GB", b/1073741824;
    else if (b >= 1048576) printf "%.2f MB", b/1048576;
    else if (b >= 1024) printf "%.2f KB", b/1024;
    else printf "%.0f B", b;
  }'
}

if [ "$code" -ne 0 ] && [ "$code" -ne 9 ]; then
  echo "[原生回退] C 扫描器失败，代码 $code，切换兼容引擎" >>"$LOG_FILE"
  rm -f "$REPORT_FILE" "$TARGETS_TMP" "$SUMMARY_FILE"
  cleanup_lock
  trap - EXIT INT TERM
  fallback "$@"
fi

FILES=$(number files)
EMPTY_DIRS=$(number empty_dirs)
BYTES=$(number bytes)
SKIPPED=$(number skipped)
ERRORS=$(number errors)
PROTECTED_ITEMS=$(number protected_items)
PROTECTED_BYTES=$(number protected_bytes)
TARGETS=$(number targets)
CANDIDATES=$(number candidates)
TOTAL_FILES=$((FILES + EMPTY_DIRS))
END_EPOCH=$(date +%s)
ELAPSED=$((END_EPOCH - START_EPOCH))
SPACE=$(human_bytes "$BYTES")

if [ "$code" -eq 9 ]; then
  RESULT="卸载残留扫描已停止"
  rm -f "$TARGETS_TMP"
else
  RESULT="卸载残留原生扫描完成，可清理 $SPACE"
  chmod 0600 "$TARGETS_TMP" 2>/dev/null
  mv -f "$TARGETS_TMP" "$CORPSE_SCAN_TARGETS"
  {
    echo "epoch=$(date +%s)"
    echo "bytes=$BYTES"
    echo "items=$TOTAL_FILES"
    echo "engine=native-c-arm64"
    echo "targets=$CANDIDATES"
  } >"$CORPSE_SCAN_STATE"
fi

{
  echo "mode=corpse-scan"
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
  echo "risk_low=0"
  echo "risk_medium=0"
  echo "risk_high=$TARGETS"
  echo "risk_critical=0"
  echo "deep_slow_items=0"
  echo "deep_mount_items=0"
  echo "deep_truncated=0"
  echo "cache_slow_dirs=0"
  echo "cache_truncated=0"
  echo "deep_progress_current=$TARGETS"
  echo "deep_progress_total=$TARGETS"
  echo "elapsed=$ELAPSED"
  echo "engine=native-c-arm64"
  echo "result=$RESULT"
} >"$STATE_DIR/latest.env"

[ -f "$REPORT_FILE" ] && cp -f "$REPORT_FILE" "$REPORT_DIR/latest.tsv"
{
  echo "----------------------------------------"
  echo "$RESULT"
  echo "原生引擎: C arm64 预览版"
  echo "候选目录: $CANDIDATES | 文件: $FILES | 空目录: $EMPTY_DIRS | 受保护: $PROTECTED_ITEMS | 跳过: $SKIPPED | 错误: $ERRORS | 耗时: ${ELAPSED}s"
} >>"$LOG_FILE"
cp -f "$LOG_FILE" "$LOG_DIR/latest.log"

history_category="卸载残留|$BYTES|$TOTAL_FILES"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$(date '+%Y-%m-%d %H:%M:%S')" "corpse-scan" "$BYTES" "$TOTAL_FILES" "$EMPTY_DIRS" "$ERRORS" \
  "$RESULT" "$TRIGGER" "$history_category" "" >>"$HISTORY_FILE"
tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"

ls -1t "$LOG_DIR"/*.log 2>/dev/null | awk 'NR>11' | while IFS= read -r old; do rm -f "$old"; done
ls -1t "$REPORT_DIR"/*.tsv 2>/dev/null | grep -v '/latest.tsv$' | awk 'NR>20' | while IFS= read -r old; do rm -f "$old"; done

echo "$RESULT"
echo "原生引擎: C arm64 | 候选目录: $CANDIDATES | 文件: $FILES | 空目录: $EMPTY_DIRS | 受保护: $PROTECTED_ITEMS | 耗时: ${ELAPSED}s"
cleanup_lock
trap - EXIT INT TERM
[ "$code" -eq 9 ] && exit 9
exit 0
