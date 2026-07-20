#!/system/bin/sh

MODDIR=${0%/*}
MODE=${1:-cache-scan}
TRIGGER=${2:-manual}
case "$MODE" in cache-scan|corpse-scan) ;; *) echo "不支持的联合扫描模式：$MODE" >&2; exit 2 ;; esac

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
NATIVE_ENGINE=${BAIZE_NATIVE_ENGINE:-$MODDIR/bin/arm64-v8a/baize_engine}
CACHE_PREFIX=${BAIZE_CACHE_PREFIX:-cache_scan}
case "$CACHE_PREFIX" in cache_scan|cache_auto) ;; *) echo "无效的缓存快照命名空间" >&2; exit 2 ;; esac

CACHE_STATE="$STATE_DIR/$CACHE_PREFIX.env"
CACHE_TARGETS="$STATE_DIR/$CACHE_PREFIX.targets"
CACHE_ITEMS="$STATE_DIR/$CACHE_PREFIX.items.tsv"
CACHE_MANIFEST="$STATE_DIR/$CACHE_PREFIX.manifest0"
CORPSE_STATE="$STATE_DIR/corpse_scan.env"
CORPSE_TARGETS="$STATE_DIR/corpse_scan.targets"

mkdir -p "$STATE_DIR" "$REPORT_DIR" "$LOG_DIR"
[ -f "$CONFIG" ] || cp -f "$MODDIR/config/default.conf" "$CONFIG"
[ -f "$WHITELIST" ] || cp -f "$MODDIR/config/whitelist.conf" "$WHITELIST"
[ -f "$PACKAGE_WHITELIST" ] || : >"$PACKAGE_WHITELIST"
[ -x "$NATIVE_ENGINE" ] || { echo "C 原生 One-pass 扫描器不可用，请重新刷入完整模块" >&2; exit 8; }

pid_is_baize_task() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in
    *baize_v2*cleaner.sh*|*baize-v2*cleaner.sh*|*native-cleaner.sh*|*one-pass-scan.sh*|*cache-transaction.sh*|*cache-snapshot-clean.sh*|*baize_engine*) return 0 ;;
  esac
  return 1
}

LOCK_OWNED=0
cleanup_lock() {
  [ "$LOCK_OWNED" = "1" ] || return 0
  rm -f "$RUNNING_FILE" 2>/dev/null
  rm -rf -- "$LOCK_DIR" 2>/dev/null
}
handle_signal() {
  trap - EXIT INT TERM
  : >"$STOP_FILE" 2>/dev/null
  cleanup_lock
  exit 9
}

if [ "${BAIZE_LOCK_HELD:-0}" = "1" ]; then
  [ -d "$LOCK_DIR" ] || { echo "联合扫描事务锁已丢失" >&2; exit 4; }
else
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    old_pid=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
    case "$old_pid" in ''|*[!0-9]*) old_pid=0 ;; esac
    if [ "$old_pid" -gt 1 ] && kill -0 "$old_pid" 2>/dev/null && pid_is_baize_task "$old_pid"; then
      echo "已有扫描或清理任务正在运行"
      exit 3
    fi
    rm -rf -- "$LOCK_DIR" 2>/dev/null
    rm -f "$RUNNING_FILE" 2>/dev/null
    mkdir "$LOCK_DIR" 2>/dev/null || { echo "无法恢复联合扫描锁，请重试"; exit 4; }
  fi
  LOCK_OWNED=1
  printf '%s\n' "$$" >"$LOCK_DIR/pid"
  trap cleanup_lock EXIT
  trap handle_signal INT TERM
  rm -f "$STOP_FILE"
fi

TMP_DIR="$LOCK_DIR/tmp"
mkdir -p "$TMP_DIR"
START_EPOCH=$(date +%s)
STAMP=$(date '+%Y-%m-%d_%H-%M-%S')
CACHE_REPORT="$REPORT_DIR/$STAMP-cache-one-pass.tsv"
CORPSE_REPORT="$REPORT_DIR/$STAMP-corpse-one-pass.tsv"
CACHE_TARGETS_TMP="$TMP_DIR/$CACHE_PREFIX.targets"
CACHE_ITEMS_TMP="$TMP_DIR/$CACHE_PREFIX.items.tsv"
CACHE_MANIFEST_TMP="$TMP_DIR/$CACHE_PREFIX.manifest0"
CACHE_SUMMARY="$TMP_DIR/cache-one-pass.env"
CORPSE_TARGETS_TMP="$TMP_DIR/corpse-one-pass.targets"
CORPSE_SUMMARY="$TMP_DIR/corpse-one-pass.env"
LOG_FILE="$LOG_DIR/$STAMP-$MODE-one-pass.log"
INSTALLED_ROOT=${BAIZE_INSTALLED_ROOT:-$TMP_DIR/installed}
mkdir -p "$INSTALLED_ROOT"

set_phase() {
  phase=$1
  tmp="$RUNNING_FILE.tmp.$$"
  {
    echo "mode=$MODE"
    echo "phase=$phase"
    echo "started=$START_EPOCH"
    echo "progress_current=0"
    echo "progress_total=0"
    echo "current_path="
    echo "engine=native-c-arm64-one-pass-path-index"
  } >"$tmp"
  mv -f "$tmp" "$RUNNING_FILE"
}
get_config_uint() {
  key=$1 fallback=$2 min=$3 max=$4
  value=$(sed -n "s/^$key=//p" "$CONFIG" 2>/dev/null | tail -n 1)
  case "$value" in ''|*[!0-9]*) value=$fallback ;; esac
  [ "$value" -lt "$min" ] && value=$min
  [ "$value" -gt "$max" ] && value=$max
  echo "$value"
}
summary_value() { file=$1 key=$2; sed -n "s/^$key=//p" "$file" 2>/dev/null | tail -n 1; }
summary_number() { value=$(summary_value "$1" "$2"); case "$value" in ''|*[!0-9]*) value=0 ;; esac; echo "$value"; }
file_sha() { [ -f "$1" ] && sha256sum "$1" 2>/dev/null | awk 'NR==1{print $1}' || echo missing; }
human_bytes() { awk -v b="$1" 'BEGIN { if (b>=1073741824) printf "%.2f GB",b/1073741824; else if(b>=1048576) printf "%.2f MB",b/1048576; else if(b>=1024) printf "%.2f KB",b/1024; else printf "%.0f B",b }'; }

rm -f "$CACHE_STATE" "$CACHE_TARGETS" "$CACHE_ITEMS" "$CACHE_MANIFEST"
rm -f "$CORPSE_STATE" "$CORPSE_TARGETS"
set_phase "正在建立所有 Android 用户的安装包共享索引"
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

MAX_MB=$(get_config_uint max_file_mb 256 1 16384)
MAX_FILE_BYTES=$((MAX_MB * 1024 * 1024))
cache_days=$(get_config_uint app_cache_days 0 0 365)
external_days=$(get_config_uint external_cache_days 0 0 365)
[ "$external_days" -lt "$cache_days" ] && cache_days=$external_days

set_phase "正在单次枚举 Android/data 并生成双快照"
code=0
"$NATIVE_ENGINE" scan-external-one-pass \
  --data-root "$DATA_ROOT" --media-root "$MEDIA_ROOT" --installed-root "$INSTALLED_ROOT" \
  --whitelist "$WHITELIST" --package-whitelist "$PACKAGE_WHITELIST" \
  --min-age-days "$cache_days" --max-file-bytes "$MAX_FILE_BYTES" \
  --report "$CACHE_REPORT" --targets "$CACHE_TARGETS_TMP" --items "$CACHE_ITEMS_TMP" \
  --manifest "$CACHE_MANIFEST_TMP" --summary "$CACHE_SUMMARY" \
  --corpse-report "$CORPSE_REPORT" --corpse-targets "$CORPSE_TARGETS_TMP" \
  --corpse-summary "$CORPSE_SUMMARY" --progress "$RUNNING_FILE" --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1 || code=$?

if [ "$code" -ne 0 ] && [ "$code" -ne 9 ]; then
  echo "C 原生 One-pass 扫描器失败，代码 $code" >>"$LOG_FILE"
  rm -f "$CACHE_REPORT" "$CORPSE_REPORT" "$CACHE_TARGETS_TMP" "$CACHE_ITEMS_TMP" \
        "$CACHE_MANIFEST_TMP" "$CACHE_SUMMARY" "$CORPSE_TARGETS_TMP" "$CORPSE_SUMMARY"
  exit "$code"
fi
if [ "$code" -eq 9 ]; then
  echo "联合扫描已停止"
  rm -f "$CACHE_TARGETS_TMP" "$CACHE_ITEMS_TMP" "$CACHE_MANIFEST_TMP" "$CORPSE_TARGETS_TMP"
  exit 9
fi

C_FILES=$(summary_number "$CACHE_SUMMARY" files)
C_BYTES=$(summary_number "$CACHE_SUMMARY" bytes)
C_DIRS=$(summary_number "$CACHE_SUMMARY" dirs)
C_CANDIDATES=$(summary_number "$CACHE_SUMMARY" candidates)
C_TARGETS=$(summary_number "$CACHE_SUMMARY" targets)
C_ERRORS=$(summary_number "$CACHE_SUMMARY" errors)
C_SKIPPED=$(summary_number "$CACHE_SUMMARY" skipped)
C_PROTECTED=$(summary_number "$CACHE_SUMMARY" protected_items)
C_PROTECTED_BYTES=$(summary_number "$CACHE_SUMMARY" protected_bytes)
C_VISITED_FILES=$(summary_number "$CACHE_SUMMARY" visited_files)
C_VISITED_DIRS=$(summary_number "$CACHE_SUMMARY" visited_dirs)
C_FIRST=$(summary_number "$CACHE_SUMMARY" first_result_ms)
C_ELAPSED=$(summary_number "$CACHE_SUMMARY" elapsed_ms)
C_RATE=$(summary_number "$CACHE_SUMMARY" items_per_second)
INDEX_ENTRIES=$(summary_number "$CACHE_SUMMARY" package_index_entries)
INDEX_FILES=$(summary_number "$CACHE_SUMMARY" package_index_files)
INDEX_LOOKUPS=$(summary_number "$CACHE_SUMMARY" package_lookups)
ONE_APP_DIRS=$(summary_number "$CACHE_SUMMARY" one_pass_app_dirs)
ONE_INSTALLED=$(summary_number "$CACHE_SUMMARY" one_pass_installed_dirs)
ONE_ORPHAN=$(summary_number "$CACHE_SUMMARY" one_pass_orphan_dirs)
WL_INDEX_ENTRIES=$(summary_number "$CACHE_SUMMARY" whitelist_index_entries)
WL_INDEX_QUERIES=$(summary_number "$CACHE_SUMMARY" whitelist_index_queries)
WL_ANCESTOR_HITS=$(summary_number "$CACHE_SUMMARY" whitelist_ancestor_hits)
WL_DESCENDANT_HITS=$(summary_number "$CACHE_SUMMARY" whitelist_descendant_hits)
WL_PRUNED_SUBTREES=$(summary_number "$CACHE_SUMMARY" pruned_subtrees)

R_FILES=$(summary_number "$CORPSE_SUMMARY" files)
R_BYTES=$(summary_number "$CORPSE_SUMMARY" bytes)
R_DIRS=$(summary_number "$CORPSE_SUMMARY" dirs)
R_EMPTY=$(summary_number "$CORPSE_SUMMARY" empty_dirs)
R_CANDIDATES=$(summary_number "$CORPSE_SUMMARY" candidates)
R_TARGETS=$(summary_number "$CORPSE_SUMMARY" targets)
R_ERRORS=$(summary_number "$CORPSE_SUMMARY" errors)
R_SKIPPED=$(summary_number "$CORPSE_SUMMARY" skipped)
R_PROTECTED=$(summary_number "$CORPSE_SUMMARY" protected_items)
R_PROTECTED_BYTES=$(summary_number "$CORPSE_SUMMARY" protected_bytes)
R_VISITED_FILES=$(summary_number "$CORPSE_SUMMARY" visited_files)
R_VISITED_DIRS=$(summary_number "$CORPSE_SUMMARY" visited_dirs)
R_FIRST=$(summary_number "$CORPSE_SUMMARY" first_result_ms)
R_ELAPSED=$(summary_number "$CORPSE_SUMMARY" elapsed_ms)
R_RATE=$(summary_number "$CORPSE_SUMMARY" items_per_second)

scan_epoch=$(date +%s)
mv -f "$CACHE_TARGETS_TMP" "$CACHE_TARGETS"
mv -f "$CACHE_ITEMS_TMP" "$CACHE_ITEMS"
mv -f "$CACHE_MANIFEST_TMP" "$CACHE_MANIFEST"
cache_targets_sha=$(file_sha "$CACHE_TARGETS")
cache_items_sha=$(file_sha "$CACHE_ITEMS")
cache_manifest_sha=$(file_sha "$CACHE_MANIFEST")
cache_snapshot_id="${scan_epoch}-$(printf '%s' "$cache_manifest_sha" | cut -c1-16)"
{
  echo "epoch=$scan_epoch"
  echo "snapshot_id=$cache_snapshot_id"
  echo "targets_sha=$cache_targets_sha"
  echo "items_sha=$cache_items_sha"
  echo "manifest_sha=$cache_manifest_sha"
  echo "manifest_format=nul-v2"
  echo "manifest_items=$C_FILES"
  echo "whitelist_sha=$(file_sha "$WHITELIST")"
  echo "package_whitelist_sha=$(file_sha "$PACKAGE_WHITELIST")"
  echo "min_age_days=$cache_days"
  echo "max_file_bytes=$MAX_FILE_BYTES"
  echo "bytes=$C_BYTES"
  echo "files=$C_FILES"
  echo "items=$C_CANDIDATES"
  echo "targets=$C_TARGETS"
  echo "visited_files=$C_VISITED_FILES"
  echo "visited_dirs=$C_VISITED_DIRS"
  echo "package_index_entries=$INDEX_ENTRIES"
  echo "package_index_files=$INDEX_FILES"
  echo "package_lookups=$INDEX_LOOKUPS"
  echo "one_pass_app_dirs=$ONE_APP_DIRS"
  echo "one_pass_installed_dirs=$ONE_INSTALLED"
  echo "one_pass_orphan_dirs=$ONE_ORPHAN"
  echo "whitelist_index_entries=$WL_INDEX_ENTRIES"
  echo "whitelist_index_queries=$WL_INDEX_QUERIES"
  echo "whitelist_ancestor_hits=$WL_ANCESTOR_HITS"
  echo "whitelist_descendant_hits=$WL_DESCENDANT_HITS"
  echo "pruned_subtrees=$WL_PRUNED_SUBTREES"
  echo "first_result_ms=$C_FIRST"
  echo "engine_elapsed_ms=$C_ELAPSED"
  echo "items_per_second=$C_RATE"
  echo "engine=native-c-arm64-one-pass-path-index"
} >"$CACHE_STATE"
chmod 0600 "$CACHE_STATE" "$CACHE_TARGETS" "$CACHE_ITEMS" "$CACHE_MANIFEST" 2>/dev/null

mv -f "$CORPSE_TARGETS_TMP" "$CORPSE_TARGETS"
corpse_targets_sha=$(file_sha "$CORPSE_TARGETS")
corpse_snapshot_id="${scan_epoch}-$(printf '%s' "$corpse_targets_sha" | cut -c1-16)"
{
  echo "epoch=$scan_epoch"
  echo "snapshot_id=$corpse_snapshot_id"
  echo "targets_sha=$corpse_targets_sha"
  echo "whitelist_sha=$(file_sha "$WHITELIST")"
  echo "max_file_bytes=$MAX_FILE_BYTES"
  echo "bytes=$R_BYTES"
  echo "files=$((R_FILES + R_EMPTY))"
  echo "items=$R_CANDIDATES"
  echo "targets=$R_TARGETS"
  echo "visited_files=$R_VISITED_FILES"
  echo "visited_dirs=$R_VISITED_DIRS"
  echo "package_index_entries=$INDEX_ENTRIES"
  echo "package_index_files=$INDEX_FILES"
  echo "package_lookups=$INDEX_LOOKUPS"
  echo "one_pass_app_dirs=$ONE_APP_DIRS"
  echo "one_pass_installed_dirs=$ONE_INSTALLED"
  echo "one_pass_orphan_dirs=$ONE_ORPHAN"
  echo "whitelist_index_entries=$WL_INDEX_ENTRIES"
  echo "whitelist_index_queries=$WL_INDEX_QUERIES"
  echo "whitelist_ancestor_hits=$WL_ANCESTOR_HITS"
  echo "whitelist_descendant_hits=$WL_DESCENDANT_HITS"
  echo "pruned_subtrees=$WL_PRUNED_SUBTREES"
  echo "first_result_ms=$R_FIRST"
  echo "engine_elapsed_ms=$R_ELAPSED"
  echo "items_per_second=$R_RATE"
  echo "engine=native-c-arm64-one-pass-path-index"
} >"$CORPSE_STATE"
chmod 0600 "$CORPSE_STATE" "$CORPSE_TARGETS" 2>/dev/null

if [ "$CACHE_PREFIX" = "cache_scan" ]; then
  printf 'package\tcategory\tfiles\tbytes\n' >"$REPORT_DIR/apps-latest.tsv"
  printf 'package\tcategory\tfiles\tbytes\terrors\tsample_path\n' >"$REPORT_DIR/app-items-latest.tsv"
  awk -F '\t' 'NR==1{next}{print $1"\t"$2"\t"$3"\t"$4}' "$CACHE_ITEMS" >>"$REPORT_DIR/apps-latest.tsv"
  awk -F '\t' 'NR==1{next}{print $1"\t"$2"\t"$3"\t"$4"\t0\t"$6}' "$CACHE_ITEMS" >>"$REPORT_DIR/app-items-latest.tsv"
fi

C_SPACE=$(human_bytes "$C_BYTES")
R_SPACE=$(human_bytes "$R_BYTES")
if [ "$MODE" = "cache-scan" ]; then
  RESULT="联合扫描完成：应用缓存 $C_SPACE，卸载残留 $R_SPACE"
  PRIMARY_BYTES=$C_BYTES; PRIMARY_FILES=$C_FILES; PRIMARY_DIRS=$C_DIRS
  PRIMARY_ERRORS=$C_ERRORS; PRIMARY_SKIPPED=$C_SKIPPED; PRIMARY_PROTECTED=$C_PROTECTED
  PRIMARY_PROTECTED_BYTES=$C_PROTECTED_BYTES; PRIMARY_CANDIDATES=$C_CANDIDATES
  PRIMARY_VISITED_FILES=$C_VISITED_FILES; PRIMARY_VISITED_DIRS=$C_VISITED_DIRS
  PRIMARY_FIRST=$C_FIRST; PRIMARY_ELAPSED=$C_ELAPSED; PRIMARY_RATE=$C_RATE
  PRIMARY_REPORT=$CACHE_REPORT; PRIMARY_SNAPSHOT=$cache_snapshot_id; history_name="应用缓存"
else
  RESULT="联合扫描完成：卸载残留 $R_SPACE，应用缓存 $C_SPACE"
  PRIMARY_BYTES=$R_BYTES; PRIMARY_FILES=$R_FILES; PRIMARY_DIRS=$R_DIRS
  PRIMARY_ERRORS=$R_ERRORS; PRIMARY_SKIPPED=$R_SKIPPED; PRIMARY_PROTECTED=$R_PROTECTED
  PRIMARY_PROTECTED_BYTES=$R_PROTECTED_BYTES; PRIMARY_CANDIDATES=$R_CANDIDATES
  PRIMARY_VISITED_FILES=$R_VISITED_FILES; PRIMARY_VISITED_DIRS=$R_VISITED_DIRS
  PRIMARY_FIRST=$R_FIRST; PRIMARY_ELAPSED=$R_ELAPSED; PRIMARY_RATE=$R_RATE
  PRIMARY_REPORT=$CORPSE_REPORT; PRIMARY_SNAPSHOT=$corpse_snapshot_id; history_name="卸载残留"
fi

END_EPOCH=$(date +%s)
{
  echo "mode=$MODE"
  echo "time=$(date '+%Y-%m-%d %H:%M:%S')"
  echo "files=$PRIMARY_FILES"
  echo "regular_files=$PRIMARY_FILES"
  echo "empty_files=0"
  echo "empty_dirs=0"
  echo "hidden_items=0"
  echo "fragment_files=0"
  echo "bytes=$PRIMARY_BYTES"
  echo "skipped=$PRIMARY_SKIPPED"
  echo "errors=$PRIMARY_ERRORS"
  echo "protected_items=$PRIMARY_PROTECTED"
  echo "protected_bytes=$PRIMARY_PROTECTED_BYTES"
  echo "risk_low=$C_FILES"
  echo "risk_medium=0"
  echo "risk_high=$R_FILES"
  echo "risk_critical=0"
  echo "deep_slow_items=0"
  echo "deep_mount_items=0"
  echo "deep_truncated=0"
  echo "cache_slow_dirs=0"
  echo "cache_truncated=0"
  echo "deep_progress_current=$PRIMARY_CANDIDATES"
  echo "deep_progress_total=$PRIMARY_CANDIDATES"
  echo "whitelisted=0"
  echo "visited_files=$PRIMARY_VISITED_FILES"
  echo "visited_dirs=$PRIMARY_VISITED_DIRS"
  echo "package_index_entries=$INDEX_ENTRIES"
  echo "package_index_files=$INDEX_FILES"
  echo "package_lookups=$INDEX_LOOKUPS"
  echo "one_pass_app_dirs=$ONE_APP_DIRS"
  echo "one_pass_installed_dirs=$ONE_INSTALLED"
  echo "one_pass_orphan_dirs=$ONE_ORPHAN"
  echo "whitelist_index_entries=$WL_INDEX_ENTRIES"
  echo "whitelist_index_queries=$WL_INDEX_QUERIES"
  echo "whitelist_ancestor_hits=$WL_ANCESTOR_HITS"
  echo "whitelist_descendant_hits=$WL_DESCENDANT_HITS"
  echo "pruned_subtrees=$WL_PRUNED_SUBTREES"
  echo "first_result_ms=$PRIMARY_FIRST"
  echo "engine_elapsed_ms=$PRIMARY_ELAPSED"
  echo "items_per_second=$PRIMARY_RATE"
  echo "elapsed=$((END_EPOCH - START_EPOCH))"
  echo "engine=native-c-arm64-one-pass-path-index"
  echo "result=$RESULT"
} >"$STATE_DIR/latest.env"
cp -f "$PRIMARY_REPORT" "$REPORT_DIR/latest.tsv"
{
  echo "----------------------------------------"
  echo "$RESULT"
  echo "原生引擎: C arm64 43.2 Alpha 3 路径索引 One-pass"
  echo "Android/data 顶级目录: $ONE_APP_DIRS | 已安装: $ONE_INSTALLED | 残留: $ONE_ORPHAN"
  echo "共享索引: $INDEX_ENTRIES 项 / $INDEX_FILES 个用户文件 / $INDEX_LOOKUPS 次查询"
  echo "路径索引: $WL_INDEX_ENTRIES 项 / $WL_INDEX_QUERIES 次查询 / $WL_PRUNED_SUBTREES 个子树提前剪枝"
  echo "缓存候选: $C_CANDIDATES / $C_SPACE | 残留候选: $R_CANDIDATES / $R_SPACE"
} >>"$LOG_FILE"
cp -f "$LOG_FILE" "$LOG_DIR/latest.log"

if [ "${BAIZE_SUPPRESS_SCAN_HISTORY:-0}" != "1" ]; then
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$(date '+%Y-%m-%d %H:%M:%S')" "$MODE" "$PRIMARY_BYTES" "$PRIMARY_FILES" "0" "$PRIMARY_ERRORS" \
    "$RESULT" "$TRIGGER" "$history_name|$PRIMARY_BYTES|$PRIMARY_FILES" "$PRIMARY_SNAPSHOT" >>"$HISTORY_FILE"
  tail -n 100 "$HISTORY_FILE" >"$HISTORY_FILE.tmp.$$" 2>/dev/null && mv -f "$HISTORY_FILE.tmp.$$" "$HISTORY_FILE"
fi

echo "$RESULT"
echo "One-pass: Android/data 只枚举一次 | 应用目录 $ONE_APP_DIRS | 已安装 $ONE_INSTALLED | 残留 $ONE_ORPHAN"
cleanup_lock
trap - EXIT INT TERM
exit 0
