#!/system/bin/sh
# BaiZe v2.5 resource-lane scheduler.
# Compatible cache + organizer jobs share one batch; overlapping destructive jobs remain serialized.
set -u

MODDIR=${BAIZE_MODULE_DIR:-${0%/*}}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
CONFIG=${BAIZE_CONFIG_PATH:-$STATE_DIR/config.conf}
LOG_DIR="$STATE_DIR/logs"
SCHEDULER_STATE="$STATE_DIR/scheduler.env"
QUEUE_FILE="$STATE_DIR/scheduler-queue.tsv"
REQUEST_DIR="$STATE_DIR/scheduler-requests"
SKIP_DIR="$STATE_DIR/scheduler-skips"
LOCK_DIR="$STATE_DIR/run.lock"
CACHE_LANE_WORKER="$MODDIR/cache-lane-worker.sh"
STOP_FILE="$STATE_DIR/stop"
RUNNING_FILE="$STATE_DIR/running.env"
MIN_SLEEP_SECONDS=${BAIZE_MIN_SLEEP_SECONDS:-1}
MAX_SLEEP_SECONDS=${BAIZE_MAX_SLEEP_SECONDS:-900}
CONDITION_RETRY_SECONDS=${BAIZE_CONDITION_RETRY_SECONDS:-5}
QUEUE_RETRY_SECONDS=${BAIZE_QUEUE_RETRY_SECONDS:-1}
EMPTY_FIELD=-
NEXT_CHECK_EPOCH=0
SLEEP_PID=
QUEUE_COUNT=0
QUEUE_GROUPS=
NEXT_TASK=
BLOCKED_GROUPS=
TASK_EXECUTED=0
INSTANCE_ID=${BAIZE_SUPERVISOR_INSTANCE:-scheduler-$$-$(date +%s)}

wake_scheduler() {
  [ -n "${SLEEP_PID:-}" ] && kill "$SLEEP_PID" 2>/dev/null || true
}
trap wake_scheduler USR1 HUP
trap 'wake_scheduler; exit 0' INT TERM

if [ "${BAIZE_SKIP_BOOT_WAIT:-0}" != 1 ]; then
  while [ "$(getprop sys.boot_completed 2>/dev/null)" != 1 ]; do sleep 2; done
fi
mkdir -p "$LOG_DIR" "$REQUEST_DIR" "$SKIP_DIR"
[ -f "$CONFIG" ] || cp -f "$MODDIR/config/default.conf" "$CONFIG" 2>/dev/null || : >"$CONFIG"
rm -f "$STATE_DIR"/scheduler-fail-*.count "$STATE_DIR"/scheduler-pause-*.until 2>/dev/null || true

config_value() { sed -n "s/^$1=//p" "$CONFIG" 2>/dev/null | tail -n 1; }
bool_value() { [ "$(config_value "$1")" = 1 ] && echo 1 || echo 0; }
uint_value() {
  value=$(config_value "$1"); fallback=$2; minimum=$3; maximum=$4
  case "$value" in ''|*[!0-9]*) value=$fallback ;; esac
  [ "$value" -lt "$minimum" ] && value=$minimum
  [ "$value" -gt "$maximum" ] && value=$maximum
  echo "$value"
}
schedule_mode_value() {
  value=$(config_value schedule_mode)
  case "$value" in
    0|1|2) echo "$value" ;;
    *)
      if [ "$(bool_value daily_schedule_enabled)" = 1 ]; then echo 2
      elif [ "$(config_value autopilot_enabled)" = 0 ]; then echo 1
      else echo 0
      fi
      ;;
  esac
}
daily_mode_enabled() { [ "$(schedule_mode_value)" = 2 ] && echo 1 || echo 0; }
valid_interval_seconds() {
  minutes=$(config_value "$1")
  case "$minutes" in ''|*[!0-9]*) hours=$(uint_value "$2" "$3" 1 720); minutes=$((hours * 60)) ;; esac
  [ "$minutes" -lt 5 ] && minutes=5
  [ "$minutes" -gt 43200 ] && minutes=43200
  echo $((minutes * 60))
}
sanitize_env() { printf '%s' "$1" | tr '\t\r\n' '   '; }
proc_start_ticks() { [ -r "/proc/$1/stat" ] && awk '{print $22}' "/proc/$1/stat" 2>/dev/null || echo 0; }

write_scheduler_state() {
  state=$1; group=${2:-}; reason=${3:-}; now=$(date +%s); tmp="$SCHEDULER_STATE.tmp.$$"
  {
    echo "state=$state"
    echo "group=$(sanitize_env "$group")"
    echo "reason=$(sanitize_env "$reason")"
    echo "updated=$now"
    echo "next_check_epoch=${NEXT_CHECK_EPOCH:-0}"
    echo "scheduler_pid=$$"
    echo "scheduler_start_ticks=$(proc_start_ticks $$)"
    echo "instance_id=$(sanitize_env "$INSTANCE_ID")"
    echo "heartbeat_epoch=$now"
    echo "queue_count=${QUEUE_COUNT:-0}"
    echo "queue_groups=$(sanitize_env "${QUEUE_GROUPS:-}")"
    echo "next_task=$(sanitize_env "${NEXT_TASK:-}")"
    echo "blocked_groups=$(sanitize_env "${BLOCKED_GROUPS:-}")"
    echo "queue_schema=fixed-seven-fields-v1"
  } >"$tmp" && mv -f "$tmp" "$SCHEDULER_STATE"
  chmod 0600 "$SCHEDULER_STATE" 2>/dev/null || true
}
refresh_next_check() {
  NEXT_CHECK_EPOCH=$1
  old_state=$(sed -n 's/^state=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  old_group=$(sed -n 's/^group=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  old_reason=$(sed -n 's/^reason=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  write_scheduler_state "${old_state:-waiting}" "$old_group" "${old_reason:-等待下一次定时检查}"
}
rotate_log() {
  file=$1; [ -f "$file" ] || return 0
  size=$(wc -c <"$file" 2>/dev/null | tr -d ' '); case "$size" in ''|*[!0-9]*) size=0 ;; esac
  [ "$size" -le 262144 ] || mv -f "$file" "$file.1" 2>/dev/null || : >"$file"
}

retry_count_file() { printf '%s/scheduler-retry-%s.count\n' "$STATE_DIR" "$1"; }
retry_until_file() { printf '%s/scheduler-retry-%s.until\n' "$STATE_DIR" "$1"; }
clear_group_retry() { rm -f "$(retry_count_file "$1")" "$(retry_until_file "$1")" 2>/dev/null || true; }
record_group_retry() {
  group=$1; base=${2:-15}; count_file=$(retry_count_file "$group"); until_file=$(retry_until_file "$group")
  case "$base" in ''|*[!0-9]*) base=15 ;; esac
  [ "$base" -lt 1 ] && base=1
  count=$(sed -n '1p' "$count_file" 2>/dev/null); case "$count" in ''|*[!0-9]*) count=0 ;; esac
  count=$((count + 1)); printf '%s\n' "$count" >"$count_file"
  case "$count" in 1) delay=$base;; 2) delay=$((base*2));; 3) delay=$((base*4));; 4) delay=$((base*8));; *) delay=$((base*16));; esac
  [ "$delay" -gt 300 ] && delay=300
  RETRY_DELAY_SECONDS=$delay
  printf '%s\n' $(( $(date +%s) + delay )) >"$until_file"
}
group_retry_remaining() {
  file=$(retry_until_file "$1"); until=$(sed -n '1p' "$file" 2>/dev/null)
  case "$until" in ''|*[!0-9]*) until=0 ;; esac
  now=$(date +%s)
  if [ "$until" -gt "$now" ]; then echo $((until-now)); return 0; fi
  [ "$until" -gt 0 ] && rm -f "$file"
  return 1
}

scheduler_task_alive() {
  pid=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null); ticks=$(sed -n '1p' "$LOCK_DIR/start_ticks" 2>/dev/null)
  case "$pid" in ''|*[!0-9]*) return 1 ;; esac
  [ "$pid" -gt 1 ] && kill -0 "$pid" 2>/dev/null || return 1
  actual=$(proc_start_ticks "$pid"); case "$ticks" in ''|*[!0-9]*) ticks=0 ;; esac
  [ "$ticks" -eq 0 ] || [ "$actual" = "$ticks" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in *task-worker.sh*|*worker-runner.sh*|*organizer-worker.sh*|*cleaner.sh*|*native-cleaner.sh*|*profile-cleaner.sh*|*baize_engine*) return 0;; esac
  return 1
}
clear_stale_task_markers() {
  if [ -d "$LOCK_DIR" ] && scheduler_task_alive; then return 0; fi
  [ -d "$LOCK_DIR" ] && rm -rf -- "$LOCK_DIR" 2>/dev/null || true
  [ -d "$STATE_DIR/cache-lane.lock" ] || rm -f "$RUNNING_FILE" 2>/dev/null || true
  rm -f "$STOP_FILE" 2>/dev/null || true
}
is_screen_off() { dumpsys power 2>/dev/null | grep -Eq 'Display Power: state=OFF|mWakefulness=Asleep|mInteractive=false'; }
is_device_idle() {
  dump=$(dumpsys deviceidle 2>/dev/null)
  printf '%s\n' "$dump" | grep -Eq 'mState=(IDLE|IDLE_MAINTENANCE)|mLightState=(IDLE|WAITING_FOR_NETWORK)'
}
conditions_allow_task() {
  group=${1:-cache}; SCHEDULE_REASON=
  [ "$(bool_value enabled)" = 1 ] || { SCHEDULE_REASON="自动任务总开关已关闭"; return 1; }
  [ ! -f "$STOP_FILE" ] || { SCHEDULE_REASON="已收到停止请求"; return 1; }
  screen_key=screen_off_only; idle_key=device_idle_only; charge_key=charging_only
  if [ "$group" = organize ]; then screen_key=organize_screen_off_only; idle_key=organize_device_idle_only; charge_key=organize_charging_only; fi
  if [ "$(bool_value "$screen_key")" = 1 ] && ! is_screen_off; then SCHEDULE_REASON="等待息屏"; return 1; fi
  if [ "$(bool_value "$idle_key")" = 1 ] && ! is_device_idle; then SCHEDULE_REASON="等待系统进入空闲状态"; return 1; fi
  battery=$(dumpsys battery 2>/dev/null)
  maximum_temp=$(uint_value max_battery_temp 42 30 60)
  temperature=$(printf '%s\n' "$battery" | sed -n 's/^[[:space:]]*temperature: //p' | head -n 1)
  case "$temperature" in ''|*[!0-9]*) temperature=0 ;; esac
  if [ "$temperature" -gt 0 ] && [ "$temperature" -ge $((maximum_temp * 10)) ]; then
    temp_whole=$((temperature / 10)); temp_decimal=$((temperature % 10))
    SCHEDULE_REASON="等待电池温度降低（当前 ${temp_whole}.${temp_decimal}°C，上限 ${maximum_temp}°C）"
    return 1
  fi
  if [ "$(bool_value "$charge_key")" = 1 ]; then
    if ! printf '%s\n' "$battery" | grep -Eq '^[[:space:]]*(AC powered|USB powered|Wireless powered|Dock powered): true'; then
      status=$(printf '%s\n' "$battery" | sed -n 's/^[[:space:]]*status: //p' | head -n 1)
      [ "$status" = 2 ] || [ "$status" = 5 ] || { SCHEDULE_REASON="等待设备充电"; return 1; }
    fi
  fi
  minimum=$(uint_value min_battery 25 0 100)
  level=$(printf '%s\n' "$battery" | sed -n 's/^[[:space:]]*level: //p' | head -n 1)
  case "$level" in ''|*[!0-9]*) level=100 ;; esac
  [ "$level" -ge "$minimum" ] || { SCHEDULE_REASON="等待电量达到 ${minimum}%（当前 ${level}%）"; return 1; }
  return 0
}

group_spec() {
  case "$1" in
    cache) SPEC_ENABLED=schedule_cache_enabled; SPEC_MINUTES=schedule_cache_minutes; SPEC_HOURS=schedule_cache_hours; SPEC_FALLBACK=24; SPEC_MODE=cache-auto;;
    empty) SPEC_ENABLED=schedule_empty_enabled; SPEC_MINUTES=schedule_empty_minutes; SPEC_HOURS=schedule_empty_hours; SPEC_FALLBACK=24; SPEC_MODE=empty-clean;;
    rules) SPEC_ENABLED=schedule_rules_enabled; SPEC_MINUTES=schedule_rules_minutes; SPEC_HOURS=schedule_rules_hours; SPEC_FALLBACK=24; SPEC_MODE=rules-clean;;
    fragment) SPEC_ENABLED=schedule_fragment_enabled; SPEC_MINUTES=schedule_fragment_minutes; SPEC_HOURS=schedule_fragment_hours; SPEC_FALLBACK=72; SPEC_MODE=fragment-clean;;
    deep) SPEC_ENABLED=schedule_deep_enabled; SPEC_MINUTES=schedule_deep_minutes; SPEC_HOURS=schedule_deep_hours; SPEC_FALLBACK=168; SPEC_MODE=deep-auto;;
    organize) SPEC_ENABLED=schedule_organize_enabled; SPEC_MINUTES=schedule_organize_minutes; SPEC_HOURS=schedule_organize_hours; SPEC_FALLBACK=24; SPEC_MODE=organize;;
    *) return 1;;
  esac
}

daily_cycle_info() {
  now=$(date +%s); hour=$(uint_value daily_schedule_hour 3 0 23); minute=$(uint_value daily_schedule_minute 30 0 59); grace=$(uint_value daily_grace_minutes 240 15 720)
  h=$(date +%H | sed 's/^0//'); [ -n "$h" ] || h=0
  m=$(date +%M | sed 's/^0//'); [ -n "$m" ] || m=0
  s=$(date +%S | sed 's/^0//'); [ -n "$s" ] || s=0
  now_min=$((h*60+m)); target=$((hour*60+minute)); DAILY_CYCLE=$(date +%F 2>/dev/null)
  if [ "$now_min" -ge "$target" ]; then elapsed=$((now_min-target)); [ "$elapsed" -le "$grace" ] || return 1
  else elapsed=$((now_min+1440-target)); [ "$elapsed" -le "$grace" ] || return 1; DAILY_CYCLE=$(date -d yesterday +%F 2>/dev/null || echo previous)
  fi
  DAILY_DUE_AT=$((now-elapsed*60-s))
}

CANDIDATES=
candidate_optional() {
  value=$(sanitize_env "${1:-}")
  [ -n "$value" ] && printf '%s' "$value" || printf '%s' "$EMPTY_FIELD"
}
add_candidate() {
  ac_request=$(candidate_optional "${6:-}")
  ac_cycle=$(candidate_optional "${7:-}")
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "$5" "$ac_request" "$ac_cycle" >>"$CANDIDATES"
}
decode_candidate_optional_fields() {
  request=${request:-$EMPTY_FIELD}; cycle=${cycle:-$EMPTY_FIELD}
  [ "$request" = "$EMPTY_FIELD" ] && request=
  [ "$cycle" = "$EMPTY_FIELD" ] && cycle=
}
collect_manual_requests() {
  for file in "$REQUEST_DIR"/*.env; do
    [ -f "$file" ] || continue
    group=$(sed -n 's/^group=//p' "$file" | tail -n 1); created=$(sed -n 's/^created=//p' "$file" | tail -n 1)
    case "$created" in ''|*[!0-9]*) created=0 ;; esac
    group_spec "$group" || { rm -f "$file"; continue; }
    add_candidate 0 "$created" "$group" "$SPEC_MODE" manual "$file" ""
  done
}
collect_scheduled_candidates() {
  [ "$(bool_value enabled)" = 1 ] || return 0
  now=$(date +%s); daily=0
  [ "$(daily_mode_enabled)" = 1 ] && daily_cycle_info && daily=1
  for group in cache empty rules fragment deep organize; do
    group_spec "$group" || continue
    [ "$(bool_value "$SPEC_ENABLED")" = 1 ] || continue
    if [ "$group" != organize ] && [ "$daily" = 1 ]; then
      stamp="$STATE_DIR/last_${group}_daily.date"
      [ "$(sed -n '1p' "$stamp" 2>/dev/null)" = "$DAILY_CYCLE" ] && continue
      add_candidate 1 "$DAILY_DUE_AT" "$group" "$SPEC_MODE" daily "" "$DAILY_CYCLE"
    elif [ "$group" != organize ] && [ "$(daily_mode_enabled)" = 1 ]; then
      continue
    else
      interval=$(valid_interval_seconds "$SPEC_MINUTES" "$SPEC_HOURS" "$SPEC_FALLBACK")
      stamp="$STATE_DIR/last_${group}_run.epoch"; last=$(sed -n '1p' "$stamp" 2>/dev/null)
      case "$last" in ''|*[!0-9]*) last=0 ;; esac
      due=$((last+interval)); [ "$due" -le "$now" ] || continue
      add_candidate 1 "$due" "$group" "$SPEC_MODE" interval "" ""
    fi
  done
}
apply_skip_requests() {
  for file in "$SKIP_DIR"/*.request; do
    [ -f "$file" ] || continue
    group=${file##*/}; group=${group%.request}
    if group_spec "$group"; then
      rm -f "$REQUEST_DIR"/*-"$group".env 2>/dev/null || true
      printf '%s\n' "$(date +%s)" >"$STATE_DIR/last_${group}_run.epoch"
      if [ "$group" != organize ] && [ "$(daily_mode_enabled)" = 1 ] && daily_cycle_info; then
        printf '%s\n' "$DAILY_CYCLE" >"$STATE_DIR/last_${group}_daily.date"
      fi
      write_scheduler_state skipped "$group" "已跳过本次任务"
    fi
    rm -f "$file"
  done
}
refresh_queue_snapshot() {
  QUEUE_COUNT=0; QUEUE_GROUPS=; NEXT_TASK=
  [ -s "$CANDIDATES" ] || { : >"$QUEUE_FILE"; return; }
  sort -n -k1,1 -k2,2 "$CANDIDATES" >"$QUEUE_FILE.tmp.$$" && mv -f "$QUEUE_FILE.tmp.$$" "$QUEUE_FILE"
  while IFS="$(printf '\t')" read -r priority due group mode kind request cycle; do
    [ -n "${group:-}" ] || continue
    QUEUE_COUNT=$((QUEUE_COUNT+1)); [ -n "$QUEUE_GROUPS" ] && QUEUE_GROUPS="$QUEUE_GROUPS,$group" || QUEUE_GROUPS=$group
    [ -n "$NEXT_TASK" ] || NEXT_TASK=$group
  done <"$QUEUE_FILE"
}

mark_group_completed() {
  group=$1; kind=$2; cycle=${3:-}; now=$(date +%s)
  printf '%s\n' "$now" >"$STATE_DIR/last_${group}_run.epoch"
  if [ "$kind" = daily ]; then
    case "$cycle" in
      [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]|previous) ;;
      *) daily_cycle_info && cycle=$DAILY_CYCLE || cycle=$(date +%F 2>/dev/null) ;;
    esac
    [ -n "$cycle" ] && printf '%s\n' "$cycle" >"$STATE_DIR/last_${group}_daily.date"
  fi
}
handle_task_result() {
  code=$1; group=$2; kind=$3; cycle=$4; request=$5; log=$6
  case "$code" in
    0) mark_group_completed "$group" "$kind" "$cycle"; clear_group_retry "$group"; [ -n "$request" ] && rm -f "$request"; write_scheduler_state completed "$group" "任务已完成，继续检查队列";;
    3) write_scheduler_state waiting "$group" "已有其他任务运行，稍后继续";;
    9) [ -n "$request" ] && rm -f "$request"; write_scheduler_state waiting "$group" "任务已停止";;
    4|5|6|7|8|127) clear_stale_task_markers; record_group_retry "$group" 2; echo "$(date '+%F %T') task=$group launch_exit=$code recovery=${RETRY_DELAY_SECONDS}s" >>"$log"; write_scheduler_state waiting "$group" "后台任务正在重新拉起";;
    *) record_group_retry "$group" 15; echo "$(date '+%F %T') task=$group exit=$code recovery=${RETRY_DELAY_SECONDS}s" >>"$log"; write_scheduler_state waiting "$group" "后台正在自动恢复";;
  esac
}

select_parallel_pair() {
  first=0; pc_found=0; po_found=0
  while IFS="$(printf '\t')" read -r priority due group mode kind request cycle; do
    [ -n "${group:-}" ] || continue
    decode_candidate_optional_fields
    group_retry_remaining "$group" >/dev/null && continue
    conditions_allow_task "$group" || continue
    first=$((first+1)); [ "$first" -le 2 ] || break
    case "$group:$kind" in
      cache:interval|cache:daily) pc_found=1; pc_mode=$mode; pc_kind=$kind; pc_request=$request; pc_cycle=$cycle;;
      organize:interval|organize:daily) po_found=1; po_mode=$mode; po_kind=$kind; po_request=$request; po_cycle=$cycle;;
      *) return 1;;
    esac
  done <"$QUEUE_FILE"
  [ "$first" -eq 2 ] && [ "$pc_found" -eq 1 ] && [ "$po_found" -eq 1 ] && [ -f "$CACHE_LANE_WORKER" ]
}
run_parallel_pair() {
  cache_log="$LOG_DIR/scheduler-cache.log"; organize_log="$LOG_DIR/scheduler-organize.log"
  rotate_log "$cache_log"; rotate_log "$organize_log"
  cache_id="scheduled-cache-$(date +%s)-$$"; organize_id="scheduled-organize-$(date +%s)-$$"
  write_scheduler_state running "cache+organize" "正在并行执行应用缓存与文件归类"
  BAIZE_STATE_DIR="$STATE_DIR" sh "$CACHE_LANE_WORKER" "$pc_mode" "scheduler:$pc_kind" "$cache_id" wait >>"$cache_log" 2>&1 & cache_pid=$!
  sh "$MODDIR/task-worker.sh" "$po_mode" "scheduler:$po_kind" "$organize_id" wait >>"$organize_log" 2>&1 & organize_pid=$!
  wait "$cache_pid" 2>/dev/null; cache_code=$?
  wait "$organize_pid" 2>/dev/null; organize_code=$?
  TASK_EXECUTED=1
  handle_task_result "$cache_code" cache "$pc_kind" "$pc_cycle" "$pc_request" "$cache_log"
  handle_task_result "$organize_code" organize "$po_kind" "$po_cycle" "$po_request" "$organize_log"
  if [ "$cache_code" -eq 0 ] && [ "$organize_code" -eq 0 ]; then
    write_scheduler_state completed "cache+organize" "2 项兼容任务已并行完成"
  else
    write_scheduler_state waiting "cache+organize" "并行批次部分完成，异常任务将自动恢复"
  fi
}
run_next_fair_task() {
  TASK_EXECUTED=0; BLOCKED_GROUPS=
  [ -s "$QUEUE_FILE" ] || { write_scheduler_state waiting "" "没有到期任务"; return 0; }
  if scheduler_task_alive; then write_scheduler_state waiting "" "已有手动任务运行，完成后继续"; return 0; fi
  if select_parallel_pair; then run_parallel_pair; return 0; fi
  while IFS="$(printf '\t')" read -r priority due group mode kind request cycle; do
    [ -n "${group:-}" ] || continue
    decode_candidate_optional_fields
    if group_retry_remaining "$group" >/dev/null; then reason="$group:后台正在自动恢复"; [ -n "$BLOCKED_GROUPS" ] && BLOCKED_GROUPS="$BLOCKED_GROUPS,$reason" || BLOCKED_GROUPS=$reason; continue; fi
    if ! conditions_allow_task "$group"; then reason="$group:$SCHEDULE_REASON"; [ -n "$BLOCKED_GROUPS" ] && BLOCKED_GROUPS="$BLOCKED_GROUPS,$reason" || BLOCKED_GROUPS=$reason; continue; fi
    write_scheduler_state running "$group" "按超期时间与请求顺序执行"
    log="$LOG_DIR/scheduler-${group}.log"; rotate_log "$log"; task_id="scheduled-${group}-$(date +%s)-$$"
    sh "$MODDIR/task-worker.sh" "$mode" "scheduler:$kind" "$task_id" wait >>"$log" 2>&1
    code=$?; TASK_EXECUTED=1; handle_task_result "$code" "$group" "$kind" "$cycle" "$request" "$log"; return 0
  done <"$QUEUE_FILE"
  write_scheduler_state waiting "" "${BLOCKED_GROUPS:-所有到期任务都在等待执行条件}"
}

clamp_sleep() { value=$1; [ "$value" -lt "$MIN_SLEEP_SECONDS" ] && value=$MIN_SLEEP_SECONDS; [ "$value" -gt "$MAX_SLEEP_SECONDS" ] && value=$MAX_SLEEP_SECONDS; echo "$value"; }
compute_next_sleep() {
  [ "$TASK_EXECUTED" = 1 ] && { echo "$MIN_SLEEP_SECONDS"; return; }
  [ "$QUEUE_COUNT" -gt 0 ] && { [ -n "$BLOCKED_GROUPS" ] && echo "$CONDITION_RETRY_SECONDS" || echo "$QUEUE_RETRY_SECONDS"; return; }
  now=$(date +%s); minimum=0
  for group in cache empty rules fragment deep organize; do
    group_spec "$group" || continue; [ "$(bool_value "$SPEC_ENABLED")" = 1 ] || continue
    if [ "$group" != organize ] && [ "$(daily_mode_enabled)" = 1 ]; then continue; fi
    interval=$(valid_interval_seconds "$SPEC_MINUTES" "$SPEC_HOURS" "$SPEC_FALLBACK")
    last=$(sed -n '1p' "$STATE_DIR/last_${group}_run.epoch" 2>/dev/null); case "$last" in ''|*[!0-9]*) last=$now ;; esac
    remaining=$((last+interval-now)); [ "$remaining" -lt 0 ] && remaining=0
    [ "$minimum" -eq 0 ] || [ "$remaining" -ge "$minimum" ] || minimum=$remaining
    [ "$minimum" -ne 0 ] || minimum=$remaining
  done
  if [ "$(daily_mode_enabled)" = 1 ]; then
    hour=$(uint_value daily_schedule_hour 3 0 23); minute=$(uint_value daily_schedule_minute 30 0 59)
    h=$(date +%H | sed 's/^0//'); [ -n "$h" ] || h=0; m=$(date +%M | sed 's/^0//'); [ -n "$m" ] || m=0; s=$(date +%S | sed 's/^0//'); [ -n "$s" ] || s=0
    now_min=$((h*60+m)); target=$((hour*60+minute))
    if [ "$now_min" -lt "$target" ]; then daily=$(((target-now_min)*60-s)); else daily=$(((1440-now_min+target)*60-s)); fi
    [ "$minimum" -eq 0 ] || [ "$daily" -ge "$minimum" ] || minimum=$daily
    [ "$minimum" -ne 0 ] || minimum=$daily
  fi
  [ "$minimum" -gt 0 ] || minimum=300
  clamp_sleep "$minimum"
}

while true; do
  clear_stale_task_markers
  apply_skip_requests
  CANDIDATES="$STATE_DIR/scheduler-candidates.tmp.$$"; : >"$CANDIDATES"
  collect_manual_requests
  collect_scheduled_candidates
  refresh_queue_snapshot
  run_next_fair_task
  rm -f "$CANDIDATES"
  [ "${BAIZE_SCHEDULER_ONCE:-0}" = 1 ] && exit 0
  sleep_seconds=$(compute_next_sleep); now=$(date +%s); NEXT_CHECK_EPOCH=$((now+sleep_seconds)); refresh_next_check "$NEXT_CHECK_EPOCH"
  sleep "$sleep_seconds" & SLEEP_PID=$!; wait "$SLEEP_PID" 2>/dev/null || true; SLEEP_PID=
done
