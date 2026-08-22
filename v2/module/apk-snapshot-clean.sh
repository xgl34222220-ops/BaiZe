#!/system/bin/sh
# set -u：未定义变量视为错误。清理脚本以 root 身份删文件，
# 变量拼写错误静默展开成空串会造成 rm -rf "/foo" 这类事故。
set -u

MODDIR=${0%/*}
MODE=${1:-apk-clean}
TRIGGER=${2:-manual}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
WHITELIST="$STATE_DIR/whitelist.conf"
STATE_FILE="$STATE_DIR/apk_scan.env"
TARGETS_FILE="$STATE_DIR/apk_scan.targets"
REPORT_DIR="$STATE_DIR/reports"
LOG_DIR="$STATE_DIR/logs"
LOCK_DIR="$STATE_DIR/run.lock"
RUNNING_FILE="$STATE_DIR/running.env"
STOP_FILE="$STATE_DIR/stop"
HISTORY_FILE="$STATE_DIR/history.tsv"

[ "$MODE" = "apk-clean" ] || { echo "不支持的安装包快照模式：$MODE" >&2; exit 2; }
mkdir -p "$STATE_DIR" "$REPORT_DIR" "$LOG_DIR"
[ -f "$WHITELIST" ] || : >"$WHITELIST"
# 白名单只在启动时载入一次；匹配时零子进程。
baize_whitelist_load "$WHITELIST"


file_sha() {
  file=$1
  [ -f "$file" ] || { echo missing; return; }
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" 2>/dev/null | awk 'NR==1{print $1}'
  else
    toybox sha256sum "$file" 2>/dev/null | awk 'NR==1{print $1}'
  fi
}

pid_is_baize_task() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in
    *baize_v2*cleaner.sh*|*baize-v2*cleaner.sh*|*native-cleaner.sh*|*profile-cleaner.sh*|*cache-snapshot-clean.sh*|*apk-cleaner.sh*|*baize_engine*) return 0 ;;
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
REPORT_FILE="$REPORT_DIR/$STAMP-apk-clean.tsv"
LOG_FILE="$LOG_DIR/$STAMP-apk-clean.log"

state_value() { sed -n "s/^$1=//p" "$STATE_FILE" 2>/dev/null | tail -n 1; }
human_bytes() { awk -v b="$1" 'BEGIN { if (b>=1073741824) printf "%.2f GB",b/1073741824; else if(b>=1048576) printf "%.2f MB",b/1048576; else if(b>=1024) printf "%.2f KB",b/1024; else printf "%.0f B",b }'; }
file_size() {
  value=$(stat -c %s "$1" 2>/dev/null)
  case "$value" in ''|*[!0-9]*) value=$(wc -c <"$1" 2>/dev/null | tr -d ' ') ;; esac
  case "$value" in ''|*[!0-9]*) value=0 ;; esac
  echo "$value"
}
count_nul() { tr -cd '\000' <"$1" | wc -c | tr -d ' '; }
should_stop() { [ -f "$STOP_FILE" ]; }

set_phase() {
  phase=$1
  current=${2:-0}
  total=${3:-0}
  path=${4:-}
  tmp="$RUNNING_FILE.tmp.$$"
  {
    echo "mode=apk-clean"
    echo "phase=$phase"
    echo "started=$START_EPOCH"
    echo "progress_current=$current"
    echo "progress_total=$total"
    printf 'current_path=%s\n' "$path" | tr '\r\n' '  '
    echo "engine=apk-snapshot-v42.8"
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

# 白名单匹配。测试夹具可能只暂存部分脚本，缺失时退回内联实现。
if [ -f "$MODDIR/whitelist-match.sh" ]; then
  . "$MODDIR/whitelist-match.sh"
else
  baize_whitelist_load() {
    _wl_file=${1:-${WHITELIST:-}}
    BAIZE_WL_ITEMS=""
    [ -n "$_wl_file" ] && [ -f "$_wl_file" ] || return 0
    while IFS= read -r _wl_raw || [ -n "$_wl_raw" ]; do
      _wl_item=${_wl_raw#"${_wl_raw%%[![:space:]]*}"}
      _wl_item=${_wl_item%"${_wl_item##*[![:space:]]}"}
      case "$_wl_item" in ''|'#'*) continue ;; esac
      case "$_wl_item" in /*) ;; *) continue ;; esac
      _wl_item=${_wl_item%/}
      [ -n "$_wl_item" ] || _wl_item=/
      BAIZE_WL_ITEMS="$BAIZE_WL_ITEMS$_wl_item
"
    done <"$_wl_file"
    return 0
  }
  path_conflicts_whitelist() {
    _wl_target=${1%/}
    [ -n "${BAIZE_WL_ITEMS:-}" ] || return 1
    _wl_old_ifs=$IFS
    case "$-" in *f*) _wl_had_f=1 ;; *) _wl_had_f=0 ;; esac
    IFS='
'
    set -f
    for _wl_item in $BAIZE_WL_ITEMS; do
      if [ "$_wl_item" = "/" ]; then
        IFS=$_wl_old_ifs; [ "$_wl_had_f" = 1 ] || set +f; return 0
      fi
      case "$_wl_target" in
        "$_wl_item"|"$_wl_item"/*) IFS=$_wl_old_ifs; [ "$_wl_had_f" = 1 ] || set +f; return 0 ;;
      esac
      case "$_wl_item" in
        "$_wl_target"|"$_wl_target"/*) IFS=$_wl_old_ifs; [ "$_wl_had_f" = 1 ] || set +f; return 0 ;;
      esac
    done
    IFS=$_wl_old_ifs
    [ "$_wl_had_f" = 1 ] || set +f
    return 1
  }
fi

apk_path_allowed() {
  path=$1
  case "$path" in
    "$MEDIA_ROOT"/[0-9]*/Download/*|\
    "$MEDIA_ROOT"/[0-9]*/Documents/*|\
    "$MEDIA_ROOT"/[0-9]*/Tencent/QQfile_recv/*|\
    "$MEDIA_ROOT"/[0-9]*/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/*|\
    "$MEDIA_ROOT"/[0-9]*/Android/data/com.tencent.mm/MicroMsg/Download/*|\
    "$MEDIA_ROOT"/[0-9]*/UCDownloads/*|\
    "$MEDIA_ROOT"/[0-9]*/Quark/Download/*|\
    "$MEDIA_ROOT"/[0-9]*/BaiduNetdisk/*) ;;
    *) return 1 ;;
  esac
  lower=$(printf '%s' "$path" | tr '[:upper:]' '[:lower:]')
  case "$lower" in *.apk|*.apks|*.xapk|*.apkm) return 0 ;; esac
  return 1
}

write_latest() {
  files=$1 bytes=$2 errors=$3 skipped=$4 elapsed=$5 result=$6
  {
    echo "mode=apk-clean"
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
    echo "elapsed=$elapsed"
    echo "engine=apk-snapshot-v42.8"
    echo "result=$result"
  } >"$STATE_DIR/latest.env"
}

[ -s "$STATE_FILE" ] && [ -s "$TARGETS_FILE" ] || { echo "没有可用的安装包扫描快照，请先扫描"; exit 6; }
epoch=$(state_value epoch)
snapshot_id=$(state_value snapshot_id)
expected_targets_sha=$(state_value targets_sha)
expected_whitelist_sha=$(state_value whitelist_sha)
max_file_bytes=$(state_value max_file_bytes)
authorized_files=$(state_value files)
authorized_bytes=$(state_value bytes)
case "$epoch" in ''|*[!0-9]*) epoch=0 ;; esac
case "$max_file_bytes" in ''|*[!0-9]*) max_file_bytes=$((4096 * 1024 * 1024)) ;; esac
case "$authorized_files" in ''|*[!0-9]*) authorized_files=0 ;; esac
case "$authorized_bytes" in ''|*[!0-9]*) authorized_bytes=0 ;; esac

now=$(date +%s)
age=$((now - epoch))
if [ "$epoch" -le 0 ] || [ "$age" -lt 0 ] || [ "$age" -gt 1800 ] || [ -z "$snapshot_id" ]; then
  echo "安装包扫描快照已过期，不会自动重新扫描"
  exit 6
fi
[ "$(file_sha "$TARGETS_FILE")" = "$expected_targets_sha" ] || { echo "安装包目标快照校验失败，不会自动重新扫描"; exit 7; }
[ "$(file_sha "$WHITELIST")" = "$expected_whitelist_sha" ] || { echo "白名单已变化，请重新扫描"; exit 7; }

total=$(count_nul "$TARGETS_FILE")
case "$total" in ''|*[!0-9]*) total=0 ;; esac
[ "$total" -gt 0 ] || { echo "安装包扫描快照为空，请重新扫描"; exit 6; }

printf 'action\trisk\tcategory\titems\tbytes\tpath\n' >"$REPORT_FILE"
set_phase "正在校验安装包扫描快照" 0 "$total" ""
current=0
deleted_files=0
deleted_bytes=0
errors=0
skipped=0
code=0

while IFS= read -r -d '' target; do
  current=$((current + 1))
  if should_stop; then code=9; break; fi
  set_phase "正在清理刚才扫描到的安装包" "$current" "$total" "$target"

  if ! apk_path_allowed "$target"; then
    skipped=$((skipped + 1))
    printf 'protected\thigh\t路径保护\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"
    continue
  fi
  if path_conflicts_whitelist "$target"; then
    skipped=$((skipped + 1))
    printf 'protected\tlow\t白名单保护\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"
    continue
  fi
  if [ ! -f "$target" ] || [ -L "$target" ]; then
    skipped=$((skipped + 1))
    printf 'protected\tlow\t目标已变化\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"
    continue
  fi
  if [ "$target" -nt "$STATE_FILE" ]; then
    skipped=$((skipped + 1))
    printf 'protected\tlow\t扫描后已修改\t1\t0\t%s\n' "$target" >>"$REPORT_FILE"
    continue
  fi
  size=$(file_size "$target")
  if [ "$size" -gt "$max_file_bytes" ]; then
    skipped=$((skipped + 1))
    printf 'protected\tlow\t大文件保护\t1\t%s\t%s\n' "$size" "$target" >>"$REPORT_FILE"
    continue
  fi

  rm -f -- "$target" 2>/dev/null
  if [ ! -e "$target" ]; then
    deleted_files=$((deleted_files + 1))
    deleted_bytes=$((deleted_bytes + size))
    printf 'cleaned\tlow\tAPK安装包\t1\t%s\t%s\n' "$size" "$target" >>"$REPORT_FILE"
  else
    errors=$((errors + 1))
    printf 'failed\tlow\tAPK安装包\t1\t%s\t%s\n' "$size" "$target" >>"$REPORT_FILE"
  fi
done <"$TARGETS_FILE"

end=$(date +%s)
elapsed=$((end - START_EPOCH))
if [ "$code" -eq 9 ]; then
  result="安装包快照清理已停止，已释放 $(human_bytes "$deleted_bytes")"
else
  result="安装包快照清理完成，已释放 $(human_bytes "$deleted_bytes")"
  rm -f "$STATE_FILE" "$TARGETS_FILE"
fi

write_latest "$deleted_files" "$deleted_bytes" "$errors" "$skipped" "$elapsed" "$result"
cp -f "$REPORT_FILE" "$REPORT_DIR/latest.tsv"
{
  echo "----------------------------------------"
  echo "$result"
  echo "扫描快照: $snapshot_id"
  echo "授权内容: $authorized_files 个 / $(human_bytes "$authorized_bytes")"
  echo "实际清理: $deleted_files 个 / $(human_bytes "$deleted_bytes")"
  echo "跳过: $skipped | 失败: $errors | 耗时: ${elapsed}s"
} >>"$LOG_FILE"
cp -f "$LOG_FILE" "$LOG_DIR/latest.log"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$(date '+%Y-%m-%d %H:%M:%S')" "apk-clean" "$deleted_bytes" "$deleted_files" 0 "$errors" \
  "$result" "$TRIGGER" "APK安装包|$deleted_bytes|$deleted_files" "$snapshot_id" >>"$HISTORY_FILE"
tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"

echo "$result"
echo "扫描快照: $snapshot_id | 清理: $deleted_files 个 | 跳过: $skipped | 失败: $errors"
cleanup_lock
trap - EXIT INT TERM
[ "$code" -eq 9 ] && exit 9
exit 0
