#!/system/bin/sh
# BaiZe v2.4 unified Root scheduler.
# App only submits configuration/commands. This process owns fairness, conditions, locking and recovery.
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
STOP_FILE="$STATE_DIR/stop"
RUNNING_FILE="$STATE_DIR/running.env"
MIN_SLEEP_SECONDS=${BAIZE_MIN_SLEEP_SECONDS:-30}
MAX_SLEEP_SECONDS=${BAIZE_MAX_SLEEP_SECONDS:-900}
CONDITION_RETRY_SECONDS=${BAIZE_CONDITION_RETRY_SECONDS:-15}
NEXT_CHECK_EPOCH=0
SLEEP_PID=
QUEUE_COUNT=0
QUEUE_GROUPS=
NEXT_TASK=
BLOCKED_GROUPS=
INSTANCE_ID=${BAIZE_SUPERVISOR_INSTANCE:-scheduler-$$-$(date +%s)}

wake_scheduler() {
  if [ -n "${SLEEP_PID:-}" ]; then
    kill "$SLEEP_PID" 2>/dev/null || true
  fi
}
trap wake_scheduler USR1 HUP
trap 'wake_scheduler; exit 0' INT TERM

if [ "${BAIZE_SKIP_BOOT_WAIT:-0}" != 1 ]; then
  while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do sleep 2; done
fi

mkdir -p "$LOG_DIR" "$REQUEST_DIR" "$SKIP_DIR"
# v2.4.0 exposed legacy failure/pause markers. They are obsolete; retries are automatic now.
rm -f "$STATE_DIR"/scheduler-fail-*.count "$STATE_DIR"/scheduler-pause-*.until 2>/dev/null || true
[ -f "$CONFIG" ] || cp -f "$MODDIR/config/default.conf" "$CONFIG" 2>/dev/null || : >"$CONFIG"

config_value() { sed -n "s/^$1=//p" "$CONFIG" 2>/dev/null | tail -n 1; }
bool_value() { [ "$(config_value "$1")" = 1 ] && echo 1 || echo 0; }
uint_value() {
  uv_value=$(config_value "$1"); uv_fallback=$2; uv_min=$3; uv_max=$4
  case "$uv_value" in ''|*[!0-9]*) uv_value=$uv_fallback ;; esac
  [ "$uv_value" -lt "$uv_min" ] && uv_value=$uv_min
  [ "$uv_value" -gt "$uv_max" ] && uv_value=$uv_max
  echo "$uv_value"
}
valid_interval_seconds() {
  vi_minutes=$(config_value "$1")
  case "$vi_minutes" in ''|*[!0-9]*) vi_hours=$(uint_value "$2" 1 1 720); vi_minutes=$((vi_hours * 60)) ;; esac
  [ "$vi_minutes" -lt 5 ] && vi_minutes=5
  [ "$vi_minutes" -gt 43200 ] && vi_minutes=43200
  echo $((vi_minutes * 60))
}
sanitize_env() { printf '%s' "$1" | tr '\t\r\n' '   '; }
proc_start_ticks() {
  pst_pid=$1
  [ -r "/proc/$pst_pid/stat" ] || return 1
  awk '{print $22}' "/proc/$pst_pid/stat" 2>/dev/null
}

write_scheduler_state() {
  ws_state=$1; ws_group=${2:-}; ws_reason=${3:-}
  ws_now=$(date +%s)
  ws_old_state=$(sed -n 's/^state=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  ws_old_group=$(sed -n 's/^group=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  ws_old_reason=$(sed -n 's/^reason=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  ws_old_queue=$(sed -n 's/^queue_groups=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  ws_old_updated=$(sed -n 's/^updated=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  case "$ws_old_updated" in ''|*[!0-9]*) ws_old_updated=0 ;; esac
  case "$ws_state" in running|completed|interrupted|skipped) ;;
    *)
      if [ "$ws_state" = "$ws_old_state" ] && [ "$ws_group" = "$ws_old_group" ] &&
         [ "$ws_reason" = "$ws_old_reason" ] && [ "$QUEUE_GROUPS" = "$ws_old_queue" ] &&
         [ $((ws_now - ws_old_updated)) -lt 120 ]; then return 0; fi
      ;;
  esac
  ws_tmp="$SCHEDULER_STATE.tmp.$$"
  {
    echo "state=$ws_state"
    echo "group=$(sanitize_env "$ws_group")"
    echo "reason=$(sanitize_env "$ws_reason")"
    echo "updated=$ws_now"
    echo "next_check_epoch=${NEXT_CHECK_EPOCH:-0}"
    echo "scheduler_pid=$$"
    echo "scheduler_start_ticks=$(proc_start_ticks $$ 2>/dev/null || echo 0)"
    echo "instance_id=$(sanitize_env "$INSTANCE_ID")"
    echo "heartbeat_epoch=$ws_now"
    echo "queue_count=${QUEUE_COUNT:-0}"
    echo "queue_groups=$(sanitize_env "${QUEUE_GROUPS:-}")"
    echo "next_task=$(sanitize_env "${NEXT_TASK:-}")"
    echo "blocked_groups=$(sanitize_env "${BLOCKED_GROUPS:-}")"
  } >"$ws_tmp" && mv -f "$ws_tmp" "$SCHEDULER_STATE"
  chmod 0600 "$SCHEDULER_STATE" 2>/dev/null || true
}

refresh_next_check() {
  NEXT_CHECK_EPOCH=$1
  rn_state=$(sed -n 's/^state=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  rn_group=$(sed -n 's/^group=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  rn_reason=$(sed -n 's/^reason=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  write_scheduler_state "${rn_state:-waiting}" "$rn_group" "${rn_reason:-等待下一次定时检查}"
}

rotate_scheduler_log() {
  rl_file=$1; [ -f "$rl_file" ] || return 0
  rl_size=$(wc -c <"$rl_file" 2>/dev/null | tr -d ' ')
  case "$rl_size" in ''|*[!0-9]*) rl_size=0 ;; esac
  [ "$rl_size" -le 262144 ] || mv -f "$rl_file" "$rl_file.1" 2>/dev/null || : >"$rl_file"
}
retry_count_file() { printf '%s/scheduler-retry-%s.count\n' "$STATE_DIR" "$1"; }
retry_until_file() { printf '%s/scheduler-retry-%s.until\n' "$STATE_DIR" "$1"; }
clear_group_retry() {
  rm -f "$(retry_count_file "$1")" "$(retry_until_file "$1")" \
    "$STATE_DIR/scheduler-fail-$1.count" "$STATE_DIR/scheduler-pause-$1.until" 2>/dev/null || true
}
record_group_retry() {
  rr_group=$1; rr_count_file=$(retry_count_file "$rr_group"); rr_until_file=$(retry_until_file "$rr_group")
  rr_count=$(sed -n '1p' "$rr_count_file" 2>/dev/null)
  case "$rr_count" in ''|*[!0-9]*) rr_count=0 ;; esac
  rr_count=$((rr_count + 1)); printf '%s\n' "$rr_count" >"$rr_count_file"
  case "$rr_count" in
    1) rr_delay=300 ;;
    2) rr_delay=900 ;;
    3) rr_delay=1800 ;;
    4) rr_delay=3600 ;;
    *) rr_delay=7200 ;;
  esac
  RETRY_DELAY_SECONDS=$rr_delay
  printf '%s\n' $(( $(date +%s) + rr_delay )) >"$rr_until_file"
}
group_retry_remaining() {
  gr_group=$1; gr_until_file=$(retry_until_file "$gr_group")
  gr_until=$(sed -n '1p' "$gr_until_file" 2>/dev/null)
  case "$gr_until" in ''|*[!0-9]*) gr_until=0 ;; esac
  gr_now=$(date +%s)
  if [ "$gr_until" -gt "$gr_now" ]; then echo $((gr_until - gr_now)); return 0; fi
  [ "$gr_until" -gt 0 ] && rm -f "$gr_until_file"
  return 1
}

scheduler_task_alive() {
  sta_pid=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
  sta_ticks=$(sed -n '1p' "$LOCK_DIR/start_ticks" 2>/dev/null)
  case "$sta_pid" in ''|*[!0-9]*) return 1 ;; esac
  [ "$sta_pid" -gt 1 ] 2>/dev/null || return 1
  kill -0 "$sta_pid" 2>/dev/null || return 1
  sta_actual=$(proc_start_ticks "$sta_pid" 2>/dev/null || echo 0)
  case "$sta_ticks" in ''|*[!0-9]*) sta_ticks=0 ;; esac
  [ "$sta_ticks" -eq 0 ] || [ "$sta_actual" = "$sta_ticks" ] || return 1
  sta_cmdline=$(tr '\000' ' ' <"/proc/$sta_pid/cmdline" 2>/dev/null)
  case "$sta_cmdline" in
    *baize_v2*cleaner.sh*|*baize-v2*cleaner.sh*|*cache-transaction.sh*|*native-scan.sh*|*cache-snapshot-clean.sh*|*profile-snapshot-clean.sh*|*apk-snapshot-*|*organizer-worker.sh*|*worker-runner.sh*|*baize_engine*) return 0 ;;
  esac
  return 1
}
clear_stale_task_markers() {
  if [ -d "$LOCK_DIR" ] && scheduler_task_alive; then return 0; fi
  [ -d "$LOCK_DIR" ] && rm -rf -- "$LOCK_DIR" 2>/dev/null
  rm -f "$STOP_FILE" "$RUNNING_FILE" 2>/dev/null
}

is_screen_off() { dumpsys power 2>/dev/null | grep -Eq 'Display Power: state=OFF|mWakefulness=Asleep|mInteractive=false'; }
is_device_idle() {
  id_dump=$(dumpsys deviceidle 2>/dev/null)
  printf '%s\n' "$id_dump" | grep -Eq 'mState=(IDLE|IDLE_MAINTENANCE)|mLightState=(IDLE|WAITING_FOR_NETWORK)'
}
conditions_allow_task() {
  ca_group=${1:-cache}; SCHEDULE_REASON=
  [ "$(bool_value enabled)" = 1 ] || { SCHEDULE_REASON="Root 定时任务总开关已关闭"; return 1; }
  [ ! -f "$STOP_FILE" ] || { SCHEDULE_REASON="已收到停止请求"; return 1; }
  ca_screen=screen_off_only; ca_idle=device_idle_only; ca_charging=charging_only
  if [ "$ca_group" = organize ]; then ca_screen=organize_screen_off_only; ca_idle=organize_device_idle_only; ca_charging=organize_charging_only; fi
  if [ "$(bool_value "$ca_screen")" = 1 ] && ! is_screen_off; then SCHEDULE_REASON="等待息屏"; return 1; fi
  if [ "$(bool_value "$ca_idle")" = 1 ] && ! is_device_idle; then SCHEDULE_REASON="等待系统进入空闲状态"; return 1; fi
  ca_battery_dump=$(dumpsys battery 2>/dev/null)
  if [ "$(bool_value "$ca_charging")" = 1 ]; then
    if ! printf '%s\n' "$ca_battery_dump" | grep -Eq '^[[:space:]]*(AC powered|USB powered|Wireless powered|Dock powered): true'; then
      ca_status=$(printf '%s\n' "$ca_battery_dump" | sed -n 's/^[[:space:]]*status: //p' | head -n 1)
      [ "$ca_status" = 2 ] || [ "$ca_status" = 5 ] || { SCHEDULE_REASON="等待设备充电"; return 1; }
    fi
  fi
  ca_min=$(uint_value min_battery 25 0 100)
  ca_level=$(printf '%s\n' "$ca_battery_dump" | sed -n 's/^[[:space:]]*level: //p' | head -n 1)
  case "$ca_level" in ''|*[!0-9]*) ca_level=100 ;; esac
  [ "$ca_level" -ge "$ca_min" ] || { SCHEDULE_REASON="等待电量达到 ${ca_min}%（当前 ${ca_level}%）"; return 1; }
  return 0
}

group_spec() {
  case "$1" in
    cache) SPEC_ENABLED=schedule_cache_enabled; SPEC_MINUTES=schedule_cache_minutes; SPEC_HOURS=schedule_cache_hours; SPEC_FALLBACK=30; SPEC_MODE=cache-auto ;;
    empty) SPEC_ENABLED=schedule_empty_enabled; SPEC_MINUTES=schedule_empty_minutes; SPEC_HOURS=schedule_empty_hours; SPEC_FALLBACK=30; SPEC_MODE=empty-clean ;;
    rules) SPEC_ENABLED=schedule_rules_enabled; SPEC_MINUTES=schedule_rules_minutes; SPEC_HOURS=schedule_rules_hours; SPEC_FALLBACK=30; SPEC_MODE=rules-clean ;;
    fragment) SPEC_ENABLED=schedule_fragment_enabled; SPEC_MINUTES=schedule_fragment_minutes; SPEC_HOURS=schedule_fragment_hours; SPEC_FALLBACK=30; SPEC_MODE=fragment-clean ;;
    deep) SPEC_ENABLED=schedule_deep_enabled; SPEC_MINUTES=schedule_deep_minutes; SPEC_HOURS=schedule_deep_hours; SPEC_FALLBACK=10080; SPEC_MODE=deep-clean ;;
    organize) SPEC_ENABLED=schedule_organize_enabled; SPEC_MINUTES=schedule_organize_minutes; SPEC_HOURS=schedule_organize_hours; SPEC_FALLBACK=1440; SPEC_MODE=organize ;;
    *) return 1 ;;
  esac
  return 0
}

# Outputs DAILY_CYCLE, DAILY_DUE_AT and DAILY_LABEL when today's/previous day's grace window is active.
daily_cycle_info() {
  dci_now=$(date +%s); dci_hour=$(uint_value daily_schedule_hour 3 0 23); dci_minute=$(uint_value daily_schedule_minute 30 0 59); dci_grace=$(uint_value daily_grace_minutes 240 15 720)
  dci_h=$(date +%H | sed 's/^0//'); [ -n "$dci_h" ] || dci_h=0
  dci_m=$(date +%M | sed 's/^0//'); [ -n "$dci_m" ] || dci_m=0
  dci_s=$(date +%S | sed 's/^0//'); [ -n "$dci_s" ] || dci_s=0
  dci_now_min=$((dci_h * 60 + dci_m)); dci_target=$((dci_hour * 60 + dci_minute)); dci_elapsed=-1
  DAILY_CYCLE=$(date +%F 2>/dev/null); DAILY_LABEL=$(printf '%02d:%02d' "$dci_hour" "$dci_minute")
  if [ "$dci_now_min" -ge "$dci_target" ]; then
    dci_elapsed=$((dci_now_min - dci_target))
    [ "$dci_elapsed" -le "$dci_grace" ] || return 1
  else
    dci_previous=$((dci_now_min + 1440 - dci_target))
    [ "$dci_previous" -le "$dci_grace" ] || return 1
    dci_elapsed=$dci_previous
    DAILY_CYCLE=$(date -d 'yesterday' +%F 2>/dev/null || echo previous)
  fi
  DAILY_DUE_AT=$((dci_now - dci_elapsed * 60 - dci_s))
  return 0
}

CANDIDATES=
add_candidate() {
  ac_priority=$1; ac_due=$2; ac_group=$3; ac_mode=$4; ac_kind=$5; ac_request=${6:-}; ac_cycle=${7:-}
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$ac_priority" "$ac_due" "$ac_group" "$ac_mode" "$ac_kind" "$ac_request" "$ac_cycle" >>"$CANDIDATES"
}
collect_manual_requests() {
  for cm_file in "$REQUEST_DIR"/*.env; do
    [ -f "$cm_file" ] || continue
    cm_group=$(sed -n 's/^group=//p' "$cm_file" | tail -n 1)
    cm_created=$(sed -n 's/^created=//p' "$cm_file" | tail -n 1)
    case "$cm_created" in ''|*[!0-9]*) cm_created=0 ;; esac
    group_spec "$cm_group" || { rm -f "$cm_file"; continue; }
    add_candidate 0 "$cm_created" "$cm_group" "$SPEC_MODE" manual "$cm_file" ""
  done
}
collect_scheduled_candidates() {
  [ "$(bool_value enabled)" = 1 ] || return 0
  cs_now=$(date +%s); cs_daily=0
  [ "$(bool_value daily_schedule_enabled)" = 1 ] && daily_cycle_info && cs_daily=1
  for cs_group in cache empty rules fragment deep organize; do
    group_spec "$cs_group" || continue
    [ "$(bool_value "$SPEC_ENABLED")" = 1 ] || continue
    if [ "$cs_group" != organize ] && [ "$cs_daily" = 1 ]; then
      cs_stamp="$STATE_DIR/last_${cs_group}_daily.date"
      [ "$(sed -n '1p' "$cs_stamp" 2>/dev/null)" = "$DAILY_CYCLE" ] && continue
      add_candidate 1 "$DAILY_DUE_AT" "$cs_group" "$SPEC_MODE" daily "" "$DAILY_CYCLE"
    elif [ "$cs_group" != organize ] && [ "$(bool_value daily_schedule_enabled)" = 1 ]; then
      continue
    else
      cs_interval=$(valid_interval_seconds "$SPEC_MINUTES" "$SPEC_HOURS" "$SPEC_FALLBACK")
      cs_stamp="$STATE_DIR/last_${cs_group}_run.epoch"
      cs_last=$(sed -n '1p' "$cs_stamp" 2>/dev/null); case "$cs_last" in ''|*[!0-9]*) cs_last=0 ;; esac
      cs_due=$((cs_last + cs_interval)); [ "$cs_due" -le "$cs_now" ] || continue
      add_candidate 1 "$cs_due" "$cs_group" "$SPEC_MODE" interval "" ""
    fi
  done
}

apply_skip_requests() {
  for as_file in "$SKIP_DIR"/*.request; do
    [ -f "$as_file" ] || continue
    as_group=${as_file##*/}; as_group=${as_group%.request}
    if group_spec "$as_group"; then
      rm -f "$REQUEST_DIR"/*-"$as_group".env 2>/dev/null || true
      as_now=$(date +%s); printf '%s\n' "$as_now" >"$STATE_DIR/last_${as_group}_run.epoch"
      if [ "$as_group" != organize ] && [ "$(bool_value daily_schedule_enabled)" = 1 ] && daily_cycle_info; then
        printf '%s\n' "$DAILY_CYCLE" >"$STATE_DIR/last_${as_group}_daily.date"
      fi
      QUEUE_COUNT=0; QUEUE_GROUPS=; NEXT_TASK=
      write_scheduler_state skipped "$as_group" "已跳过本次任务"
    fi
    rm -f "$as_file"
  done
}

refresh_queue_snapshot() {
  QUEUE_COUNT=0; QUEUE_GROUPS=; NEXT_TASK=
  [ -s "$CANDIDATES" ] || { : >"$QUEUE_FILE"; return; }
  sort -n -k1,1 -k2,2 "$CANDIDATES" >"$QUEUE_FILE.tmp.$$" && mv -f "$QUEUE_FILE.tmp.$$" "$QUEUE_FILE"
  while IFS="$(printf '\t')" read -r rq_priority rq_due rq_group rq_mode rq_kind rq_request rq_cycle; do
    [ -n "${rq_group:-}" ] || continue
    QUEUE_COUNT=$((QUEUE_COUNT + 1))
    [ -n "$QUEUE_GROUPS" ] && QUEUE_GROUPS="$QUEUE_GROUPS,$rq_group" || QUEUE_GROUPS=$rq_group
    [ -n "$NEXT_TASK" ] || NEXT_TASK=$rq_group
  done <"$QUEUE_FILE"
}

mark_group_completed() {
  mg_group=$1; mg_kind=$2; mg_cycle=${3:-}; mg_now=$(date +%s)
  printf '%s\n' "$mg_now" >"$STATE_DIR/last_${mg_group}_run.epoch"
  [ "$mg_kind" != daily ] || printf '%s\n' "$mg_cycle" >"$STATE_DIR/last_${mg_group}_daily.date"
}
handle_task_result() {
  hr_code=$1; hr_group=$2; hr_kind=$3; hr_cycle=$4; hr_request=$5; hr_run_log=$6
  case "$hr_code" in
    0)
      mark_group_completed "$hr_group" "$hr_kind" "$hr_cycle"; clear_group_retry "$hr_group"
      [ -n "$hr_request" ] && rm -f "$hr_request"
      write_scheduler_state completed "$hr_group" "任务已完成，继续检查队列"
      ;;
    3)
      write_scheduler_state waiting "$hr_group" "已有手动或其他任务运行，稍后继续队列"
      ;;
    9)
      [ -n "$hr_request" ] && rm -f "$hr_request"
      write_scheduler_state waiting "$hr_group" "等待自动重试"
      ;;
    *)
      record_group_retry "$hr_group"
      printf '%s\n' "$(date '+%Y-%m-%d %H:%M:%S') task=$hr_group exit=$hr_code retry=${RETRY_DELAY_SECONDS}s" >>"$hr_run_log"
      write_scheduler_state waiting "$hr_group" "等待自动重试"
      ;;
  esac
}

run_next_fair_task() {
  TASK_EXECUTED=0; BLOCKED_GROUPS=
  [ -s "$QUEUE_FILE" ] || { write_scheduler_state waiting "" "没有到期任务"; return 0; }
  if scheduler_task_alive; then write_scheduler_state waiting "" "已有手动任务运行，队列将在完成后继续"; return 0; fi
  while IFS="$(printf '\t')" read -r rn_priority rn_due rn_group rn_mode rn_kind rn_request rn_cycle; do
    [ -n "${rn_group:-}" ] || continue
    if group_retry_remaining "$rn_group" >/dev/null; then
      rn_reason="${rn_group}:等待自动重试"
      [ -n "$BLOCKED_GROUPS" ] && BLOCKED_GROUPS="$BLOCKED_GROUPS,$rn_reason" || BLOCKED_GROUPS=$rn_reason
      continue
    fi
    if ! conditions_allow_task "$rn_group"; then
      rn_reason="${rn_group}:${SCHEDULE_REASON}"
      [ -n "$BLOCKED_GROUPS" ] && BLOCKED_GROUPS="$BLOCKED_GROUPS,$rn_reason" || BLOCKED_GROUPS=$rn_reason
      continue
    fi
    write_scheduler_state running "$rn_group" "按超期时间与请求顺序执行队列"
    rn_log="$LOG_DIR/scheduler-${rn_group}.log"; rotate_scheduler_log "$rn_log"
    rn_task_id="scheduled-${rn_group}-$(date +%s)-$$"
    sh "$MODDIR/task-worker.sh" "$rn_mode" "scheduler:$rn_kind" "$rn_task_id" wait >>"$rn_log" 2>&1
    rn_code=$?
    TASK_EXECUTED=1
    handle_task_result "$rn_code" "$rn_group" "$rn_kind" "$rn_cycle" "$rn_request" "$rn_log"
    return 0
  done <"$QUEUE_FILE"
  write_scheduler_state waiting "" "${BLOCKED_GROUPS:-所有到期任务都在等待执行条件}"
  return 0
}

clamp_sleep() { cs_value=$1; [ "$cs_value" -lt "$MIN_SLEEP_SECONDS" ] && cs_value=$MIN_SLEEP_SECONDS; [ "$cs_value" -gt "$MAX_SLEEP_SECONDS" ] && cs_value=$MAX_SLEEP_SECONDS; echo "$cs_value"; }
compute_next_sleep() {
  [ "$TASK_EXECUTED" = 1 ] && { echo "$MIN_SLEEP_SECONDS"; return; }
  [ "$QUEUE_COUNT" -gt 0 ] && { echo "$CONDITION_RETRY_SECONDS"; return; }
  cn_now=$(date +%s); cn_minimum=0
  for cn_group in cache empty rules fragment deep organize; do
    group_spec "$cn_group" || continue; [ "$(bool_value "$SPEC_ENABLED")" = 1 ] || continue
    if [ "$cn_group" != organize ] && [ "$(bool_value daily_schedule_enabled)" = 1 ]; then continue; fi
    cn_interval=$(valid_interval_seconds "$SPEC_MINUTES" "$SPEC_HOURS" "$SPEC_FALLBACK")
    cn_last=$(sed -n '1p' "$STATE_DIR/last_${cn_group}_run.epoch" 2>/dev/null); case "$cn_last" in ''|*[!0-9]*) cn_last=$cn_now ;; esac
    cn_due=$((cn_last + cn_interval)); cn_remaining=$((cn_due - cn_now)); [ "$cn_remaining" -lt 0 ] && cn_remaining=0
    [ "$cn_minimum" -eq 0 ] || [ "$cn_remaining" -ge "$cn_minimum" ] || cn_minimum=$cn_remaining
    [ "$cn_minimum" -ne 0 ] || cn_minimum=$cn_remaining
  done
  if [ "$(bool_value daily_schedule_enabled)" = 1 ]; then
    cn_hour=$(uint_value daily_schedule_hour 3 0 23); cn_minute=$(uint_value daily_schedule_minute 30 0 59)
    cn_h=$(date +%H | sed 's/^0//'); [ -n "$cn_h" ] || cn_h=0; cn_m=$(date +%M | sed 's/^0//'); [ -n "$cn_m" ] || cn_m=0; cn_s=$(date +%S | sed 's/^0//'); [ -n "$cn_s" ] || cn_s=0
    cn_now_min=$((cn_h * 60 + cn_m)); cn_target=$((cn_hour * 60 + cn_minute))
    if [ "$cn_now_min" -lt "$cn_target" ]; then cn_daily=$(((cn_target - cn_now_min) * 60 - cn_s)); else cn_daily=$(((1440 - cn_now_min + cn_target) * 60 - cn_s)); fi
    [ "$cn_minimum" -eq 0 ] || [ "$cn_daily" -ge "$cn_minimum" ] || cn_minimum=$cn_daily
    [ "$cn_minimum" -ne 0 ] || cn_minimum=$cn_daily
  fi
  [ "$cn_minimum" -gt 0 ] || cn_minimum=300
  clamp_sleep "$cn_minimum"
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
  sleep_seconds=$(compute_next_sleep)
  now=$(date +%s); NEXT_CHECK_EPOCH=$((now + sleep_seconds)); refresh_next_check "$NEXT_CHECK_EPOCH"
  sleep "$sleep_seconds" & SLEEP_PID=$!; wait "$SLEEP_PID" 2>/dev/null || true; SLEEP_PID=
done
