#!/system/bin/sh

MODDIR=${0%/*}
MODE=${1:-apk-scan}
TRIGGER=${2:-manual}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
CONFIG="$STATE_DIR/config.conf"
WHITELIST="$STATE_DIR/whitelist.conf"
STATE_FILE="$STATE_DIR/apk_scan.env"
TARGETS_FILE="$STATE_DIR/apk_scan.targets"
REPORT_DIR="$STATE_DIR/reports"
LOG_DIR="$STATE_DIR/logs"
LOCK_DIR="$STATE_DIR/run.lock"
RUNNING_FILE="$STATE_DIR/running.env"
STOP_FILE="$STATE_DIR/stop"
HISTORY_FILE="$STATE_DIR/history.tsv"

[ "$MODE" = "apk-scan" ] || { echo "不支持的安装包扫描模式：$MODE" >&2; exit 2; }
mkdir -p "$STATE_DIR" "$REPORT_DIR" "$LOG_DIR"
[ -f "$CONFIG" ] || cp -f "$MODDIR/config/default.conf" "$CONFIG"
[ -f "$WHITELIST" ] || : >"$WHITELIST"

file_sha() {
  file=$1
  [ -f "$file" ] || { echo missing; return; }
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" 2>/dev/null | awk 'NR==1{print $1}'
  else
    toybox sha256sum "$file" 2>/dev/null | awk 'NR==1{print $1}'
  fi
}

get_uint() {
  key=$1 fallback=$2 min=$3 max=$4
  value=$(sed -n "s/^$key=//p" "$CONFIG" 2>/dev/null | tail -n 1)
  case "$value" in ''|*[!0-9]*) value=$fallback ;; esac
  [ "$value" -lt "$min" ] && value=$min
  [ "$value" -gt "$max" ] && value=$max
  echo "$value"
}

pid_is_baize_task() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in
    *baize_v2*cleaner.sh*|*baize-v2*cleaner.sh*|*native-cleaner.sh*|*profile-cleaner.sh*|*cache-snapshot-clean.sh*|*apk-scanner.sh*|*apk-cleaner.sh*|*baize_engine*) return 0 ;;
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
  rm -f "$STATE_FILE" "$TARGETS_FILE"
  cleanup_lock
  exit 9
}
trap cleanup_lock EXIT
trap handle_signal INT TERM
rm -f "$STOP_FILE" "$STATE_FILE" "$TARGETS_FILE"

START_EPOCH=$(date +%s)
STAMP=$(date '+%Y-%m-%d_%H-%M-%S')
REPORT_FILE="$REPORT_DIR/$STAMP-apk-scan.tsv"
LOG_FILE="$LOG_DIR/$STAMP-apk-scan.log"
TARGETS_TMP="$TMP_DIR/apk-scan.targets"
: >"$TARGETS_TMP"

set_phase() {
  phase=$1
  current=${2:-0}
  total=${3:-0}
  path=${4:-}
  tmp="$RUNNING_FILE.tmp.$$"
  {
    echo "mode=apk-scan"
    echo "phase=$phase"
    echo "started=$START_EPOCH"
    echo "progress_current=$current"
    echo "progress_total=$total"
    printf 'current_path=%s\n' "$path" | tr '\r\n' '  '
    echo "engine=apk-snapshot-v2.1.1-generic-roots"
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

file_size() {
  value=$(stat -c %s "$1" 2>/dev/null)
  case "$value" in ''|*[!0-9]*) value=$(wc -c <"$1" 2>/dev/null | tr -d ' ') ;; esac
  case "$value" in ''|*[!0-9]*) value=0 ;; esac
  echo "$value"
}

human_bytes() { awk -v b="$1" 'BEGIN { if (b>=1073741824) printf "%.2f GB",b/1073741824; else if(b>=1048576) printf "%.2f MB",b/1048576; else if(b>=1024) printf "%.2f KB",b/1024; else printf "%.0f B",b }'; }
should_stop() { [ -f "$STOP_FILE" ]; }

CONFIG_DAYS=$(get_uint apk_package_days 30 0 365)
# 手动安装包扫描必须展示当前能找到的全部安装包；保留期只用于自动任务。
if [ "$TRIGGER" = "manual" ]; then DAYS=0; else DAYS=$CONFIG_DAYS; fi
MAX_MB=$(get_uint apk_package_max_mb 4096 16 16384)
MAX_FILE_BYTES=$((MAX_MB * 1024 * 1024))
ROOTS_FILE="$TMP_DIR/apk-roots"
: >"$ROOTS_FILE"

append_root() {
  root=${1%/}
  [ -d "$root" ] || return 0
  grep -Fqx "$root" "$ROOTS_FILE" 2>/dev/null || printf '%s\n' "$root" >>"$ROOTS_FILE"
}

# v2.1.1 不再按 QQ、Telegram、NagramX 等包名逐个维护白名单。
# 公共自建目录、Android/media 全部应用目录，以及 Android/data/<包名> 下常见用户文件根都会纳入。
for userdir in "$MEDIA_ROOT"/[0-9]*; do
  [ -d "$userdir" ] || continue

  # 应用在共享存储根目录创建的自定义下载目录。排除体量巨大的标准媒体目录与白泽目标目录。
  for top in "$userdir"/*; do
    [ -d "$top" ] || continue
    name=${top##*/}
    case "$name" in
      Android|DCIM|Pictures|Movies|Music|Podcasts|Ringtones|Alarms|Notifications|Audiobooks|BaiZe归类|LOST.DIR) continue ;;
    esac
    append_root "$top"
  done

  # Telegram、NagramX 以及其他遵循 Android 标准的应用媒体目录。
  append_root "$userdir/Android/media"

  # 标准应用外部 files 目录和少量仍直接写在包目录下的用户下载子目录。
  for pkgdir in "$userdir"/Android/data/*; do
    [ -d "$pkgdir" ] || continue
    append_root "$pkgdir/files"
    append_root "$pkgdir/Download"
    append_root "$pkgdir/Downloads"
    append_root "$pkgdir/Documents"
    append_root "$pkgdir/Telegram"
    append_root "$pkgdir/Tencent/QQfile_recv"
    append_root "$pkgdir/Tencent/Timfile_recv"
  done

done

root_total=$(wc -l <"$ROOTS_FILE" 2>/dev/null | tr -d ' ')
case "$root_total" in ''|*[!0-9]*) root_total=0 ;; esac
root_current=0
protected=0
errors=0

collect_candidates() {
  root=$1
  output=$2
  depth=${3:-8}
  : >"$output"
  if [ "$DAYS" -eq 0 ]; then
    find "$root" -xdev -mindepth 1 -maxdepth "$depth" \
      \( -type d \( -iname cache -o -iname code_cache -o -iname no_backup -o -iname databases -o -iname shared_prefs -o -iname lib -o -iname tmp -o -iname temp -o -iname thumbnails -o -iname .thumbnails \) -prune \) -o \
      \( -type f ! -size "+${MAX_FILE_BYTES}c" \( -iname '*.apk' -o -iname '*.apks' -o -iname '*.xapk' -o -iname '*.apkm' \) -print0 \) \
      2>/dev/null >"$output"
  else
    find "$root" -xdev -mindepth 1 -maxdepth "$depth" \
      \( -type d \( -iname cache -o -iname code_cache -o -iname no_backup -o -iname databases -o -iname shared_prefs -o -iname lib -o -iname tmp -o -iname temp -o -iname thumbnails -o -iname .thumbnails \) -prune \) -o \
      \( -type f -mtime "+$DAYS" ! -size "+${MAX_FILE_BYTES}c" \( -iname '*.apk' -o -iname '*.apks' -o -iname '*.xapk' -o -iname '*.apkm' \) -print0 \) \
      2>/dev/null >"$output"
  fi
}

# 先处理直接散落在内部存储根目录的安装包，不递归整个共享存储。
for userdir in "$MEDIA_ROOT"/[0-9]*; do
  [ -d "$userdir" ] || continue
  should_stop && handle_signal
  set_phase "正在扫描内部存储根目录" 0 "$root_total" "$userdir"
  LIST="$TMP_DIR/apk-root-files.${userdir##*/}.targets"
  collect_candidates "$userdir" "$LIST" 1
  while IFS= read -r -d '' candidate; do
    should_stop && handle_signal
    if [ -L "$candidate" ] || path_conflicts_whitelist "$candidate"; then
      protected=$((protected + 1))
      continue
    fi
    printf '%s\0' "$candidate" >>"$TARGETS_TMP"
  done <"$LIST"
  rm -f "$LIST"
done

while IFS= read -r root || [ -n "$root" ]; do
  [ -d "$root" ] || continue
  root_current=$((root_current + 1))
  should_stop && handle_signal
  set_phase "正在扫描全应用下载目录" "$root_current" "$root_total" "$root"
  LIST="$TMP_DIR/apk-root.$root_current.targets"
  collect_candidates "$root" "$LIST" 8

  while IFS= read -r -d '' candidate; do
    should_stop && handle_signal
    if [ -L "$candidate" ] || path_conflicts_whitelist "$candidate"; then
      protected=$((protected + 1))
      continue
    fi
    printf '%s\0' "$candidate" >>"$TARGETS_TMP"
  done <"$LIST"
  rm -f "$LIST"
done <"$ROOTS_FILE"

files=0
bytes=0
sample_path=""
while IFS= read -r -d '' candidate; do
  [ -f "$candidate" ] || continue
  files=$((files + 1))
  size=$(file_size "$candidate")
  bytes=$((bytes + size))
  [ -n "$sample_path" ] || sample_path=$candidate
done <"$TARGETS_TMP"

scan_epoch=$(date +%s)
targets_sha=$(file_sha "$TARGETS_TMP")
snapshot_id="${scan_epoch}-$(printf '%s' "$targets_sha" | cut -c1-16)"
mv -f "$TARGETS_TMP" "$TARGETS_FILE"
targets_sha=$(file_sha "$TARGETS_FILE")
{
  echo "epoch=$scan_epoch"
  echo "snapshot_id=$snapshot_id"
  echo "targets_sha=$targets_sha"
  echo "whitelist_sha=$(file_sha "$WHITELIST")"
  echo "max_file_bytes=$MAX_FILE_BYTES"
  echo "package_days=$DAYS"
  echo "configured_package_days=$CONFIG_DAYS"
  echo "bytes=$bytes"
  echo "files=$files"
  echo "engine=apk-snapshot-v2.1.1-generic-roots"
} >"$STATE_FILE"
chmod 0600 "$STATE_FILE" "$TARGETS_FILE" 2>/dev/null

result="安装包扫描完成，发现 $files 个 / $(human_bytes "$bytes")"
end=$(date +%s)
elapsed=$((end - START_EPOCH))
printf 'action\trisk\tcategory\titems\tbytes\tpath\n' >"$REPORT_FILE"
if [ "$files" -gt 0 ]; then
  printf 'candidate\tlow\tAPK安装包\t%s\t%s\t%s\n' "$files" "$bytes" "${sample_path:-共享存储安装包}" >>"$REPORT_FILE"
fi
cp -f "$REPORT_FILE" "$REPORT_DIR/latest.tsv"
{
  echo "mode=apk-scan"
  echo "time=$(date '+%Y-%m-%d %H:%M:%S')"
  echo "files=$files"
  echo "regular_files=$files"
  echo "empty_files=0"
  echo "empty_dirs=0"
  echo "hidden_items=0"
  echo "fragment_files=0"
  echo "bytes=$bytes"
  echo "skipped=0"
  echo "errors=$errors"
  echo "protected_items=$protected"
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
  echo "deep_progress_current=$root_current"
  echo "deep_progress_total=$root_total"
  echo "elapsed=$elapsed"
  echo "engine=apk-snapshot-v2.1.1-generic-roots"
  echo "result=$result"
} >"$STATE_DIR/latest.env"
{
  echo "----------------------------------------"
  echo "$result"
  echo "扫描快照: $snapshot_id"
  echo "扫描根目录: $root_total | 手动扫描全部年龄: $([ "$DAYS" -eq 0 ] && echo 是 || echo 否)"
  echo "白名单或异常保护: $protected | 失败: $errors | 耗时: ${elapsed}s"
} >>"$LOG_FILE"
cp -f "$LOG_FILE" "$LOG_DIR/latest.log"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$(date '+%Y-%m-%d %H:%M:%S')" "apk-scan" "$bytes" "$files" 0 "$errors" \
  "$result" "$TRIGGER" "APK安装包|$bytes|$files" "$snapshot_id" >>"$HISTORY_FILE"
tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"

echo "$result"
echo "扫描快照: $snapshot_id | 安装包: $files 个 | 扫描根: $root_total | 受保护: $protected | 耗时: ${elapsed}s"
cleanup_lock
trap - EXIT INT TERM
exit 0
