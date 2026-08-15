#!/system/bin/sh
# set -u：未定义变量视为错误。清理脚本以 root 身份删文件，
# 变量拼写错误静默展开成空串会造成 rm -rf "/foo" 这类事故。
set -u

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
WORKER_PROFILE="$STATE_DIR/root-worker-profile.env"
# ABI 解析辅助。测试夹具可能只暂存部分脚本，缺失时退回到内联实现。
if [ -f "$MODDIR/abi-resolve.sh" ]; then
  . "$MODDIR/abi-resolve.sh"
else
  baize_device_abis() { printf 'arm64-v8a\narmeabi-v7a\nx86_64\n'; }
  baize_resolve_engine() {
    for _abi in $(baize_device_abis); do
      [ -x "$1/bin/$_abi/$2" ] && { printf '%s\n' "$1/bin/$_abi/$2"; return 0; }
    done
    return 1
  }
  baize_require_engine() {
    [ -n "${3:-}" ] && [ -x "$3" ] && { printf '%s\n' "$3"; return 0; }
    baize_resolve_engine "$1" "$2" && return 0
    echo "当前架构没有可用的 $2，请重新刷入完整模块" >&2
    return 8
  }
fi
NATIVE_ENGINE=$(baize_require_engine "$MODDIR" baize_engine "${BAIZE_NATIVE_ENGINE:-}") || exit 8
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
WORKER_PIDS=""
stop_workers() {
  : >"$STOP_FILE" 2>/dev/null
  for worker_pid in $WORKER_PIDS; do
    [ "$worker_pid" -gt 1 ] 2>/dev/null || continue
    child_pids=$(cat "/proc/$worker_pid/task/$worker_pid/children" 2>/dev/null)
    for child_pid in $child_pids; do kill "$child_pid" 2>/dev/null || true; done
    kill "$worker_pid" 2>/dev/null || true
  done
}
cleanup_lock() {
  [ "$LOCK_OWNED" = "1" ] || return 0
  rm -f "$RUNNING_FILE" 2>/dev/null
  rm -rf -- "$LOCK_DIR" 2>/dev/null
}
handle_signal() {
  trap - EXIT INT TERM
  : >"$STOP_FILE" 2>/dev/null
  stop_workers
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
    echo "engine=native-c-arm64-one-pass-path-index-adaptive-workers"
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

monotonic_ms() {
  awk 'NR==1 { printf "%.0f\n", $1 * 1000 }' /proc/uptime 2>/dev/null || echo 0
}
env_value() { file=$1 key=$2; sed -n "s/^$key=//p" "$file" 2>/dev/null | tail -n 1; }
max2() { [ "$1" -ge "$2" ] 2>/dev/null && echo "$1" || echo "$2"; }
min_positive() {
  a=$1 b=$2
  [ "$a" -le 0 ] 2>/dev/null && { echo "$b"; return; }
  [ "$b" -le 0 ] 2>/dev/null && { echo "$a"; return; }
  [ "$a" -le "$b" ] && echo "$a" || echo "$b"
}
profile_number() {
  value=$(env_value "$WORKER_PROFILE" "$1")
  case "$value" in ''|*[!0-9]*) value=${2:-0} ;; esac
  echo "$value"
}
profile_integer() {
  value=$(env_value "$WORKER_PROFILE" "$1")
  case "$value" in ''|-) value=${2:-0} ;; *[!0-9-]*|--*|-*-*) value=${2:-0} ;; esac
  echo "$value"
}
profile_text() {
  value=$(env_value "$WORKER_PROFILE" "$1")
  [ -n "$value" ] && echo "$value" || echo "${2:-}"
}
load_worker_profile() {
  PROFILE_SERIAL_SAMPLES=$(profile_number serial_samples 0)
  PROFILE_SERIAL_RATE=$(profile_number serial_rate 0)
  PROFILE_SERIAL_WALL_MS=$(profile_number serial_wall_ms 0)
  PROFILE_SERIAL_ITEMS=$(profile_number serial_items 0)
  PROFILE_PARALLEL_SAMPLES=$(profile_number parallel_samples 0)
  PROFILE_PARALLEL_RATE=$(profile_number parallel_rate 0)
  PROFILE_PARALLEL_WALL_MS=$(profile_number parallel_wall_ms 0)
  PROFILE_PARALLEL_ITEMS=$(profile_number parallel_items 0)
  PROFILE_SUCCESSFUL_RUNS=$(profile_number successful_runs 0)
  PROFILE_LAST_PROBE_RUN=$(profile_number last_probe_run 0)
  PROFILE_LAST_ITEMS=$(profile_number last_items 0)
  PROFILE_LAST_WORKERS=$(profile_number last_workers 1)
  PROFILE_RECOMMENDED=$(profile_number recommended_workers 1)
  PROFILE_GAIN_PERCENT=$(profile_integer parallel_gain_percent 0)
  PROFILE_PARALLEL_FAILURES=$(profile_number parallel_failures 0)
  PROFILE_BLOCKED_UNTIL=$(profile_number parallel_blocked_until 0)
  PROFILE_LAST_DECISION=$(profile_text last_decision none)
  PROFILE_UPDATED_EPOCH=$(profile_number updated_epoch 0)
}
write_worker_profile() {
  tmp="$WORKER_PROFILE.tmp.$$"
  {
    echo "profile_version=1"
    echo "serial_samples=$PROFILE_SERIAL_SAMPLES"
    echo "serial_rate=$PROFILE_SERIAL_RATE"
    echo "serial_wall_ms=$PROFILE_SERIAL_WALL_MS"
    echo "serial_items=$PROFILE_SERIAL_ITEMS"
    echo "parallel_samples=$PROFILE_PARALLEL_SAMPLES"
    echo "parallel_rate=$PROFILE_PARALLEL_RATE"
    echo "parallel_wall_ms=$PROFILE_PARALLEL_WALL_MS"
    echo "parallel_items=$PROFILE_PARALLEL_ITEMS"
    echo "successful_runs=$PROFILE_SUCCESSFUL_RUNS"
    echo "last_probe_run=$PROFILE_LAST_PROBE_RUN"
    echo "next_probe_run=$((PROFILE_LAST_PROBE_RUN + PARALLEL_REPROBE_RUNS))"
    echo "last_items=$PROFILE_LAST_ITEMS"
    echo "last_workers=$PROFILE_LAST_WORKERS"
    echo "recommended_workers=$PROFILE_RECOMMENDED"
    echo "parallel_gain_percent=$PROFILE_GAIN_PERCENT"
    echo "parallel_failures=$PROFILE_PARALLEL_FAILURES"
    echo "parallel_blocked_until=$PROFILE_BLOCKED_UNTIL"
    echo "last_decision=$PROFILE_LAST_DECISION"
    echo "updated_epoch=$PROFILE_UPDATED_EPOCH"
  } >"$tmp"
  chmod 0600 "$tmp" 2>/dev/null
  mv -f "$tmp" "$WORKER_PROFILE"
}
calculate_profile_gain() {
  if [ "$PROFILE_SERIAL_RATE" -gt 0 ] && [ "$PROFILE_PARALLEL_RATE" -gt 0 ]; then
    PROFILE_GAIN_PERCENT=$(((PROFILE_PARALLEL_RATE - PROFILE_SERIAL_RATE) * 100 / PROFILE_SERIAL_RATE))
  else
    PROFILE_GAIN_PERCENT=0
  fi
}
choose_root_workers() {
  requested=${BAIZE_ROOT_WORKERS:-$(get_config_uint scan_root_workers 0 0 2)}
  case "$requested" in 0|1|2) ;; *) requested=0 ;; esac
  PARALLEL_MIN_ITEMS=$(get_config_uint scan_parallel_min_items 5000 100 10000000)
  PARALLEL_MIN_GAIN=$(get_config_uint scan_parallel_min_gain_percent 15 5 50)
  PARALLEL_REPROBE_RUNS=$(get_config_uint scan_parallel_reprobe_runs 6 2 50)
  PARALLEL_COOLDOWN_HOURS=$(get_config_uint scan_parallel_failure_cooldown_hours 24 1 168)
  cpu_count=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null)
  case "$cpu_count" in ''|*[!0-9]*) cpu_count=1 ;; esac
  mem_kb=$(awk '/^MemAvailable:/ {print $2; exit}' /proc/meminfo 2>/dev/null)
  case "$mem_kb" in ''|*[!0-9]*) mem_kb=0 ;; esac
  has_internal=0
  [ -d "$DATA_ROOT/user" ] && has_internal=1
  [ -d "$DATA_ROOT/user_de" ] && has_internal=1
  has_external=0
  for userdir in "$MEDIA_ROOT"/[0-9]*; do
    [ -d "$userdir/Android/data" ] && { has_external=1; break; }
  done
  PARALLEL_ELIGIBLE=0
  [ "$cpu_count" -ge 4 ] && [ "$mem_kb" -ge 524288 ] && [ "$has_internal" = "1" ] && [ "$has_external" = "1" ] && PARALLEL_ELIGIBLE=1
  load_worker_profile
  ROOT_WORKERS=1
  WORKER_POLICY=auto
  WORKER_REASON=auto_bootstrap_serial
  RECOMMENDED_WORKERS=$PROFILE_RECOMMENDED
  [ "$RECOMMENDED_WORKERS" = "2" ] || RECOMMENDED_WORKERS=1
  now_epoch=$(date +%s)
  if [ "$requested" = "1" ]; then
    WORKER_POLICY=manual
    WORKER_REASON=manual_serial
    RECOMMENDED_WORKERS=1
    return
  fi
  if [ "$requested" = "2" ]; then
    WORKER_POLICY=manual
    if [ "$has_internal" = "1" ] && [ "$has_external" = "1" ]; then
      ROOT_WORKERS=2
      WORKER_REASON=manual_parallel
      RECOMMENDED_WORKERS=2
    else
      WORKER_REASON=manual_parallel_unavailable
      RECOMMENDED_WORKERS=1
    fi
    return
  fi
  if [ "$PARALLEL_ELIGIBLE" != "1" ]; then
    WORKER_REASON=auto_not_eligible
    RECOMMENDED_WORKERS=1
    return
  fi
  if [ "$PROFILE_BLOCKED_UNTIL" -gt "$now_epoch" ]; then
    WORKER_REASON=auto_parallel_cooldown
    RECOMMENDED_WORKERS=1
    return
  fi
  if [ "$PROFILE_SERIAL_SAMPLES" -le 0 ]; then
    WORKER_REASON=auto_bootstrap_serial
    RECOMMENDED_WORKERS=1
    return
  fi
  if [ "$PROFILE_LAST_ITEMS" -lt "$PARALLEL_MIN_ITEMS" ]; then
    WORKER_REASON=auto_small_workload
    RECOMMENDED_WORKERS=1
    return
  fi
  if [ "$PROFILE_PARALLEL_SAMPLES" -le 0 ]; then
    ROOT_WORKERS=2
    WORKER_REASON=auto_parallel_probe
    RECOMMENDED_WORKERS=1
    return
  fi
  calculate_profile_gain
  if [ "$PROFILE_GAIN_PERCENT" -ge "$PARALLEL_MIN_GAIN" ]; then RECOMMENDED_WORKERS=2; else RECOMMENDED_WORKERS=1; fi
  runs_since_probe=$((PROFILE_SUCCESSFUL_RUNS - PROFILE_LAST_PROBE_RUN))
  if [ "$runs_since_probe" -ge "$PARALLEL_REPROBE_RUNS" ]; then
    if [ "$RECOMMENDED_WORKERS" = "2" ]; then
      ROOT_WORKERS=1
      WORKER_REASON=auto_serial_reprobe
    else
      ROOT_WORKERS=2
      WORKER_REASON=auto_parallel_reprobe
    fi
  elif [ "$RECOMMENDED_WORKERS" = "2" ]; then
    ROOT_WORKERS=2
    WORKER_REASON=auto_parallel_faster
  else
    ROOT_WORKERS=1
    WORKER_REASON=auto_serial_faster
  fi
}
prepare_worker_profile_success() {
  measured_items=$((C_VISITED_FILES + C_VISITED_DIRS))
  measured_rate=$C_RATE
  measured_wall=$PARALLEL_WALL_MS
  PROFILE_SUCCESSFUL_RUNS=$((PROFILE_SUCCESSFUL_RUNS + 1))
  PROFILE_LAST_ITEMS=$measured_items
  PROFILE_LAST_WORKERS=$ROOT_WORKERS
  PROFILE_LAST_DECISION=$WORKER_REASON
  PROFILE_UPDATED_EPOCH=$(date +%s)
  if [ "$ROOT_WORKERS" = "2" ]; then
    PROFILE_PARALLEL_SAMPLES=$((PROFILE_PARALLEL_SAMPLES + 1))
    if [ "$PROFILE_PARALLEL_RATE" -gt 0 ]; then PROFILE_PARALLEL_RATE=$(((PROFILE_PARALLEL_RATE * 3 + measured_rate) / 4)); else PROFILE_PARALLEL_RATE=$measured_rate; fi
    if [ "$PROFILE_PARALLEL_WALL_MS" -gt 0 ]; then PROFILE_PARALLEL_WALL_MS=$(((PROFILE_PARALLEL_WALL_MS * 3 + measured_wall) / 4)); else PROFILE_PARALLEL_WALL_MS=$measured_wall; fi
    PROFILE_PARALLEL_ITEMS=$measured_items
    PROFILE_PARALLEL_FAILURES=0
    PROFILE_BLOCKED_UNTIL=0
  else
    PROFILE_SERIAL_SAMPLES=$((PROFILE_SERIAL_SAMPLES + 1))
    if [ "$PROFILE_SERIAL_RATE" -gt 0 ]; then PROFILE_SERIAL_RATE=$(((PROFILE_SERIAL_RATE * 3 + measured_rate) / 4)); else PROFILE_SERIAL_RATE=$measured_rate; fi
    if [ "$PROFILE_SERIAL_WALL_MS" -gt 0 ]; then PROFILE_SERIAL_WALL_MS=$(((PROFILE_SERIAL_WALL_MS * 3 + measured_wall) / 4)); else PROFILE_SERIAL_WALL_MS=$measured_wall; fi
    PROFILE_SERIAL_ITEMS=$measured_items
  fi
  case "$WORKER_REASON" in *probe*) PROFILE_LAST_PROBE_RUN=$PROFILE_SUCCESSFUL_RUNS ;; esac
  calculate_profile_gain
  if [ "$PROFILE_LAST_ITEMS" -ge "$PARALLEL_MIN_ITEMS" ] && [ "$PROFILE_SERIAL_SAMPLES" -gt 0 ] && [ "$PROFILE_PARALLEL_SAMPLES" -gt 0 ] && [ "$PROFILE_GAIN_PERCENT" -ge "$PARALLEL_MIN_GAIN" ]; then
    PROFILE_RECOMMENDED=2
  else
    PROFILE_RECOMMENDED=1
  fi
  PROFILE_NEXT_PROBE_RUN=$((PROFILE_LAST_PROBE_RUN + PARALLEL_REPROBE_RUNS))
}
record_parallel_failure() {
  [ "$ROOT_WORKERS" = "2" ] || return 0
  PROFILE_PARALLEL_FAILURES=$((PROFILE_PARALLEL_FAILURES + 1))
  PROFILE_BLOCKED_UNTIL=$(($(date +%s) + PARALLEL_COOLDOWN_HOURS * 3600))
  PROFILE_RECOMMENDED=1
  PROFILE_LAST_DECISION=auto_parallel_failed
  PROFILE_UPDATED_EPOCH=$(date +%s)
  write_worker_profile
}
write_parallel_progress() {
  internal_phase=$(env_value "$INTERNAL_PROGRESS" phase)
  external_phase=$(env_value "$EXTERNAL_PROGRESS" phase)
  internal_path=$(env_value "$INTERNAL_PROGRESS" current_path)
  external_path=$(env_value "$EXTERNAL_PROGRESS" current_path)
  [ -n "$internal_phase" ] || internal_phase="等待内部存储工作进程"
  [ -n "$external_phase" ] || external_phase="等待外部存储工作进程"
  current_path=$external_path
  [ -n "$current_path" ] || current_path=$internal_path
  tmp="$RUNNING_FILE.tmp.$$"
  {
    echo "mode=$MODE"
    echo "phase=有限并发：内部[$internal_phase] 外部[$external_phase]"
    echo "started=$START_EPOCH"
    echo "progress_current=0"
    echo "progress_total=0"
    echo "current_path=$current_path"
    echo "root_workers=2"
    echo "internal_worker_pid=$INTERNAL_PID"
    echo "external_worker_pid=$EXTERNAL_PID"
    echo "engine=native-c-arm64-one-pass-path-index-adaptive-workers"
  } >"$tmp"
  mv -f "$tmp" "$RUNNING_FILE"
}
write_combined_cache_summary() {
  internal=$1 external=$2 output=$3 wall_ms=$4
  sum_key() { a=$(summary_number "$internal" "$1"); b=$(summary_number "$external" "$1"); echo $((a + b)); }
  files=$(sum_key files); bytes=$(sum_key bytes); dirs=$(sum_key dirs); empty_dirs=$(sum_key empty_dirs)
  skipped=$(sum_key skipped); errors=$(sum_key errors); protected_items=$(sum_key protected_items)
  protected_bytes=$(sum_key protected_bytes); candidates=$(sum_key candidates); targets=$(sum_key targets)
  risk_low=$(sum_key risk_low); risk_medium=$(sum_key risk_medium); risk_high=$(sum_key risk_high); risk_critical=$(sum_key risk_critical)
  mount_items=$(sum_key mount_items); truncated=$(sum_key truncated); whitelisted=$(sum_key whitelisted)
  visited_files=$(sum_key visited_files); visited_dirs=$(sum_key visited_dirs)
  package_lookups=$(sum_key package_lookups)
  one_pass_app_dirs=$(sum_key one_pass_app_dirs); one_pass_installed_dirs=$(sum_key one_pass_installed_dirs); one_pass_orphan_dirs=$(sum_key one_pass_orphan_dirs)
  whitelist_index_queries=$(sum_key whitelist_index_queries); whitelist_ancestor_hits=$(sum_key whitelist_ancestor_hits)
  whitelist_descendant_hits=$(sum_key whitelist_descendant_hits); pruned_subtrees=$(sum_key pruned_subtrees)
  package_index_entries=$(max2 "$(summary_number "$internal" package_index_entries)" "$(summary_number "$external" package_index_entries)")
  package_index_files=$(max2 "$(summary_number "$internal" package_index_files)" "$(summary_number "$external" package_index_files)")
  whitelist_index_entries=$(max2 "$(summary_number "$internal" whitelist_index_entries)" "$(summary_number "$external" whitelist_index_entries)")
  first_result_ms=$(min_positive "$(summary_number "$internal" first_result_ms)" "$(summary_number "$external" first_result_ms)")
  visited=$((visited_files + visited_dirs))
  [ "$wall_ms" -gt 0 ] 2>/dev/null && items_per_second=$((visited * 1000 / wall_ms)) || items_per_second=0
  {
    echo "files=$files"; echo "bytes=$bytes"; echo "dirs=$dirs"; echo "empty_dirs=$empty_dirs"
    echo "skipped=$skipped"; echo "errors=$errors"; echo "protected_items=$protected_items"; echo "protected_bytes=$protected_bytes"
    echo "candidates=$candidates"; echo "targets=$targets"; echo "risk_low=$risk_low"; echo "risk_medium=$risk_medium"
    echo "risk_high=$risk_high"; echo "risk_critical=$risk_critical"; echo "mount_items=$mount_items"; echo "truncated=$truncated"
    echo "whitelisted=$whitelisted"; echo "visited_files=$visited_files"; echo "visited_dirs=$visited_dirs"
    echo "package_index_entries=$package_index_entries"; echo "package_index_files=$package_index_files"; echo "package_lookups=$package_lookups"
    echo "first_result_ms=$first_result_ms"; echo "one_pass_app_dirs=$one_pass_app_dirs"
    echo "one_pass_installed_dirs=$one_pass_installed_dirs"; echo "one_pass_orphan_dirs=$one_pass_orphan_dirs"
    echo "whitelist_index_entries=$whitelist_index_entries"; echo "whitelist_index_queries=$whitelist_index_queries"
    echo "whitelist_ancestor_hits=$whitelist_ancestor_hits"; echo "whitelist_descendant_hits=$whitelist_descendant_hits"
    echo "pruned_subtrees=$pruned_subtrees"; echo "elapsed_ms=$wall_ms"; echo "items_per_second=$items_per_second"
    echo "engine=native-c-arm64"; echo "version=43.6-alpha7-system-cache"
  } >"$output"
}

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
choose_root_workers
PARALLEL_WALL_MS=0
INTERNAL_WORKER_MS=0
EXTERNAL_WORKER_MS=0
PARALLEL_OVERLAP_MILLI=1000
CACHE_REPORT_WORK="$TMP_DIR/cache-report.tsv"
CORPSE_REPORT_WORK="$TMP_DIR/corpse-report.tsv"
rm -f "$CACHE_REPORT_WORK" "$CORPSE_REPORT_WORK" "$CACHE_TARGETS_TMP" "$CACHE_ITEMS_TMP" \
      "$CACHE_MANIFEST_TMP" "$CACHE_SUMMARY" "$CORPSE_TARGETS_TMP" "$CORPSE_SUMMARY"

if [ "$ROOT_WORKERS" = "2" ]; then
  set_phase "正在以 2 个受控工作进程扫描互不重叠根目录"
  mkdir -p "$TMP_DIR/empty-media" "$TMP_DIR/empty-data"
  INTERNAL_REPORT="$TMP_DIR/internal-cache.tsv"
  INTERNAL_TARGETS="$TMP_DIR/internal-cache.targets"
  INTERNAL_ITEMS="$TMP_DIR/internal-cache.items.tsv"
  INTERNAL_MANIFEST="$TMP_DIR/internal-cache.manifest0"
  INTERNAL_SUMMARY="$TMP_DIR/internal-cache.env"
  INTERNAL_PROGRESS="$TMP_DIR/internal-progress.env"
  INTERNAL_CODE="$TMP_DIR/internal.code"
  EXTERNAL_REPORT="$TMP_DIR/external-cache.tsv"
  EXTERNAL_TARGETS="$TMP_DIR/external-cache.targets"
  EXTERNAL_ITEMS="$TMP_DIR/external-cache.items.tsv"
  EXTERNAL_MANIFEST="$TMP_DIR/external-cache.manifest0"
  EXTERNAL_SUMMARY="$TMP_DIR/external-cache.env"
  EXTERNAL_PROGRESS="$TMP_DIR/external-progress.env"
  EXTERNAL_CODE="$TMP_DIR/external.code"
  EXTERNAL_CORPSE_REPORT="$TMP_DIR/external-corpse.tsv"
  EXTERNAL_CORPSE_TARGETS="$TMP_DIR/external-corpse.targets"
  EXTERNAL_CORPSE_SUMMARY="$TMP_DIR/external-corpse.env"
  rm -f "$INTERNAL_CODE" "$EXTERNAL_CODE" "$INTERNAL_PROGRESS" "$EXTERNAL_PROGRESS"
  PARALLEL_STARTED_MS=$(monotonic_ms)
  (
    worker_code=0
    "$NATIVE_ENGINE" scan-cache \
      --data-root "$DATA_ROOT" --media-root "$TMP_DIR/empty-media" \
      --whitelist "$WHITELIST" --package-whitelist "$PACKAGE_WHITELIST" \
      --min-age-days "$cache_days" --max-file-bytes "$MAX_FILE_BYTES" \
      --report "$INTERNAL_REPORT" --targets "$INTERNAL_TARGETS" --items "$INTERNAL_ITEMS" \
      --manifest "$INTERNAL_MANIFEST" --summary "$INTERNAL_SUMMARY" \
      --progress "$INTERNAL_PROGRESS" --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1 || worker_code=$?
    echo "$worker_code" >"$INTERNAL_CODE"
  ) &
  INTERNAL_PID=$!
  (
    worker_code=0
    "$NATIVE_ENGINE" scan-external-one-pass \
      --data-root "$TMP_DIR/empty-data" --media-root "$MEDIA_ROOT" --installed-root "$INSTALLED_ROOT" \
      --whitelist "$WHITELIST" --package-whitelist "$PACKAGE_WHITELIST" \
      --min-age-days "$cache_days" --max-file-bytes "$MAX_FILE_BYTES" \
      --report "$EXTERNAL_REPORT" --targets "$EXTERNAL_TARGETS" --items "$EXTERNAL_ITEMS" \
      --manifest "$EXTERNAL_MANIFEST" --summary "$EXTERNAL_SUMMARY" \
      --corpse-report "$EXTERNAL_CORPSE_REPORT" --corpse-targets "$EXTERNAL_CORPSE_TARGETS" \
      --corpse-summary "$EXTERNAL_CORPSE_SUMMARY" --progress "$EXTERNAL_PROGRESS" --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1 || worker_code=$?
    echo "$worker_code" >"$EXTERNAL_CODE"
  ) &
  EXTERNAL_PID=$!
  WORKER_PIDS="$INTERNAL_PID $EXTERNAL_PID"
  WORKER_FAILURE_TRIGGERED=0
  while [ ! -f "$INTERNAL_CODE" ] || [ ! -f "$EXTERNAL_CODE" ]; do
    if [ -f "$INTERNAL_CODE" ]; then
      internal_early=$(cat "$INTERNAL_CODE" 2>/dev/null)
      case "$internal_early" in 0|9) ;; *) WORKER_FAILURE_TRIGGERED=1; : >"$STOP_FILE" ;; esac
    fi
    if [ -f "$EXTERNAL_CODE" ]; then
      external_early=$(cat "$EXTERNAL_CODE" 2>/dev/null)
      case "$external_early" in 0|9) ;; *) WORKER_FAILURE_TRIGGERED=1; : >"$STOP_FILE" ;; esac
    fi
    write_parallel_progress
    sleep 0.2
  done
  wait "$INTERNAL_PID" 2>/dev/null || true
  wait "$EXTERNAL_PID" 2>/dev/null || true
  WORKER_PIDS=""
  internal_code=$(cat "$INTERNAL_CODE" 2>/dev/null); external_code=$(cat "$EXTERNAL_CODE" 2>/dev/null)
  case "$internal_code" in ''|*[!0-9]*) internal_code=70 ;; esac
  case "$external_code" in ''|*[!0-9]*) external_code=70 ;; esac
  PARALLEL_ENDED_MS=$(monotonic_ms)
  PARALLEL_WALL_MS=$((PARALLEL_ENDED_MS - PARALLEL_STARTED_MS))
  [ "$PARALLEL_WALL_MS" -ge 0 ] 2>/dev/null || PARALLEL_WALL_MS=0
  if [ "$WORKER_FAILURE_TRIGGERED" = "1" ] || { [ "$internal_code" -ne 0 ] && [ "$internal_code" -ne 9 ]; } || { [ "$external_code" -ne 0 ] && [ "$external_code" -ne 9 ]; }; then
    echo "有限并发扫描失败：内部=$internal_code 外部=$external_code；旧快照保持不变" >>"$LOG_FILE"
    record_parallel_failure
    exit 8
  fi
  if [ "$internal_code" -eq 9 ] || [ "$external_code" -eq 9 ]; then
    echo "联合扫描已停止"
    exit 9
  fi
  INTERNAL_WORKER_MS=$(summary_number "$INTERNAL_SUMMARY" elapsed_ms)
  EXTERNAL_WORKER_MS=$(summary_number "$EXTERNAL_SUMMARY" elapsed_ms)
  if [ "$PARALLEL_WALL_MS" -gt 0 ]; then
    PARALLEL_OVERLAP_MILLI=$(((INTERNAL_WORKER_MS + EXTERNAL_WORKER_MS) * 1000 / PARALLEL_WALL_MS))
  fi
  awk 'FNR == 1 && NR != 1 {next} {print}' "$INTERNAL_REPORT" "$EXTERNAL_REPORT" >"$CACHE_REPORT_WORK"
  cat "$INTERNAL_TARGETS" "$EXTERNAL_TARGETS" >"$CACHE_TARGETS_TMP"
  awk 'FNR == 1 && NR != 1 {next} {print}' "$INTERNAL_ITEMS" "$EXTERNAL_ITEMS" >"$CACHE_ITEMS_TMP"
  cat "$INTERNAL_MANIFEST" "$EXTERNAL_MANIFEST" >"$CACHE_MANIFEST_TMP"
  cp -f "$EXTERNAL_CORPSE_REPORT" "$CORPSE_REPORT_WORK"
  cp -f "$EXTERNAL_CORPSE_TARGETS" "$CORPSE_TARGETS_TMP"
  cp -f "$EXTERNAL_CORPSE_SUMMARY" "$CORPSE_SUMMARY"
  write_combined_cache_summary "$INTERNAL_SUMMARY" "$EXTERNAL_SUMMARY" "$CACHE_SUMMARY" "$PARALLEL_WALL_MS"
else
  set_phase "正在串行枚举内部缓存与 Android/data 双快照"
  SERIAL_STARTED_MS=$(monotonic_ms)
  code=0
  "$NATIVE_ENGINE" scan-external-one-pass \
    --data-root "$DATA_ROOT" --media-root "$MEDIA_ROOT" --installed-root "$INSTALLED_ROOT" \
    --whitelist "$WHITELIST" --package-whitelist "$PACKAGE_WHITELIST" \
    --min-age-days "$cache_days" --max-file-bytes "$MAX_FILE_BYTES" \
    --report "$CACHE_REPORT_WORK" --targets "$CACHE_TARGETS_TMP" --items "$CACHE_ITEMS_TMP" \
    --manifest "$CACHE_MANIFEST_TMP" --summary "$CACHE_SUMMARY" \
    --corpse-report "$CORPSE_REPORT_WORK" --corpse-targets "$CORPSE_TARGETS_TMP" \
    --corpse-summary "$CORPSE_SUMMARY" --progress "$RUNNING_FILE" --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1 || code=$?
  SERIAL_ENDED_MS=$(monotonic_ms)
  PARALLEL_WALL_MS=$((SERIAL_ENDED_MS - SERIAL_STARTED_MS))
  [ "$PARALLEL_WALL_MS" -ge 0 ] 2>/dev/null || PARALLEL_WALL_MS=0
  if [ "$code" -ne 0 ] && [ "$code" -ne 9 ]; then
    echo "C 原生 One-pass 扫描器失败，代码 $code；旧快照保持不变" >>"$LOG_FILE"
    exit "$code"
  fi
  [ "$code" -ne 9 ] || { echo "联合扫描已停止"; exit 9; }
  EXTERNAL_WORKER_MS=$(summary_number "$CACHE_SUMMARY" elapsed_ms)
fi

mv -f "$CACHE_REPORT_WORK" "$CACHE_REPORT" || { echo "无法发布缓存扫描报告" >&2; exit 8; }
mv -f "$CORPSE_REPORT_WORK" "$CORPSE_REPORT" || { echo "无法发布残留扫描报告" >&2; exit 8; }

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

prepare_worker_profile_success

scan_epoch=$(date +%s)
mv -f "$CACHE_TARGETS_TMP" "$CACHE_TARGETS" || { echo "无法发布缓存目标快照" >&2; exit 8; }
mv -f "$CACHE_ITEMS_TMP" "$CACHE_ITEMS" || { echo "无法发布缓存项目快照" >&2; exit 8; }
mv -f "$CACHE_MANIFEST_TMP" "$CACHE_MANIFEST" || { echo "无法发布缓存清单快照" >&2; exit 8; }
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
  echo "root_workers=$ROOT_WORKERS"
  echo "parallel_wall_ms=$PARALLEL_WALL_MS"
  echo "internal_worker_ms=$INTERNAL_WORKER_MS"
  echo "external_worker_ms=$EXTERNAL_WORKER_MS"
  echo "parallel_overlap_milli=$PARALLEL_OVERLAP_MILLI"
  echo "worker_policy=$WORKER_POLICY"
  echo "worker_reason=$WORKER_REASON"
  echo "recommended_workers=$PROFILE_RECOMMENDED"
  echo "parallel_gain_percent=$PROFILE_GAIN_PERCENT"
  echo "worker_profile_runs=$PROFILE_SUCCESSFUL_RUNS"
  echo "serial_profile_rate=$PROFILE_SERIAL_RATE"
  echo "parallel_profile_rate=$PROFILE_PARALLEL_RATE"
  echo "next_probe_run=$PROFILE_NEXT_PROBE_RUN"
  echo "parallel_blocked_until=$PROFILE_BLOCKED_UNTIL"
  echo "engine=native-c-arm64-one-pass-path-index-adaptive-workers"
} >"$CACHE_STATE"
chmod 0600 "$CACHE_STATE" "$CACHE_TARGETS" "$CACHE_ITEMS" "$CACHE_MANIFEST" 2>/dev/null

mv -f "$CORPSE_TARGETS_TMP" "$CORPSE_TARGETS" || { echo "无法发布卸载残留目标快照" >&2; exit 8; }
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
  echo "root_workers=$ROOT_WORKERS"
  echo "parallel_wall_ms=$PARALLEL_WALL_MS"
  echo "internal_worker_ms=$INTERNAL_WORKER_MS"
  echo "external_worker_ms=$EXTERNAL_WORKER_MS"
  echo "parallel_overlap_milli=$PARALLEL_OVERLAP_MILLI"
  echo "worker_policy=$WORKER_POLICY"
  echo "worker_reason=$WORKER_REASON"
  echo "recommended_workers=$PROFILE_RECOMMENDED"
  echo "parallel_gain_percent=$PROFILE_GAIN_PERCENT"
  echo "worker_profile_runs=$PROFILE_SUCCESSFUL_RUNS"
  echo "serial_profile_rate=$PROFILE_SERIAL_RATE"
  echo "parallel_profile_rate=$PROFILE_PARALLEL_RATE"
  echo "next_probe_run=$PROFILE_NEXT_PROBE_RUN"
  echo "parallel_blocked_until=$PROFILE_BLOCKED_UNTIL"
  echo "engine=native-c-arm64-one-pass-path-index-adaptive-workers"
} >"$CORPSE_STATE"
chmod 0600 "$CORPSE_STATE" "$CORPSE_TARGETS" 2>/dev/null
write_worker_profile

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
  echo "root_workers=$ROOT_WORKERS"
  echo "parallel_wall_ms=$PARALLEL_WALL_MS"
  echo "internal_worker_ms=$INTERNAL_WORKER_MS"
  echo "external_worker_ms=$EXTERNAL_WORKER_MS"
  echo "parallel_overlap_milli=$PARALLEL_OVERLAP_MILLI"
  echo "worker_policy=$WORKER_POLICY"
  echo "worker_reason=$WORKER_REASON"
  echo "recommended_workers=$PROFILE_RECOMMENDED"
  echo "parallel_gain_percent=$PROFILE_GAIN_PERCENT"
  echo "worker_profile_runs=$PROFILE_SUCCESSFUL_RUNS"
  echo "serial_profile_rate=$PROFILE_SERIAL_RATE"
  echo "parallel_profile_rate=$PROFILE_PARALLEL_RATE"
  echo "next_probe_run=$PROFILE_NEXT_PROBE_RUN"
  echo "parallel_blocked_until=$PROFILE_BLOCKED_UNTIL"
  echo "elapsed=$((END_EPOCH - START_EPOCH))"
  echo "engine=native-c-arm64-one-pass-path-index-adaptive-workers"
  echo "result=$RESULT"
} >"$STATE_DIR/latest.env"
cp -f "$PRIMARY_REPORT" "$REPORT_DIR/latest.tsv"
{
  echo "----------------------------------------"
  echo "$RESULT"
  echo "原生引擎: C arm64 43.4 Alpha 5 自适应根目录 One-pass"
  echo "Android/data 顶级目录: $ONE_APP_DIRS | 已安装: $ONE_INSTALLED | 残留: $ONE_ORPHAN"
  echo "共享索引: $INDEX_ENTRIES 项 / $INDEX_FILES 个用户文件 / $INDEX_LOOKUPS 次查询"
  echo "路径索引: $WL_INDEX_ENTRIES 项 / $WL_INDEX_QUERIES 次查询 / $WL_PRUNED_SUBTREES 个子树提前剪枝"
  echo "根目录策略: $WORKER_REASON | 本次 $ROOT_WORKERS 个 | 推荐 $PROFILE_RECOMMENDED 个 | 双进程增益 ${PROFILE_GAIN_PERCENT}%"
  echo "扫描耗时: 墙钟 ${PARALLEL_WALL_MS}ms | 内部 ${INTERNAL_WORKER_MS}ms | 外部 ${EXTERNAL_WORKER_MS}ms | 重叠系数 ${PARALLEL_OVERLAP_MILLI}‰"
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
echo "One-pass: Android/data 只枚举一次 | 策略 $WORKER_REASON | 本次 $ROOT_WORKERS | 推荐 $PROFILE_RECOMMENDED | 应用目录 $ONE_APP_DIRS | 已安装 $ONE_INSTALLED | 残留 $ONE_ORPHAN"
cleanup_lock
trap - EXIT INT TERM
exit 0
