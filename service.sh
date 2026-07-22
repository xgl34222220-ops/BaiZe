#!/system/bin/sh
MODDIR=${0%/*}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
CONFIG="$STATE_DIR/config.conf"
LOG_DIR="$STATE_DIR/logs"
SCHEDULER_STATE="$STATE_DIR/scheduler.env"
LOCK_DIR="$STATE_DIR/run.lock"
STOP_FILE="$STATE_DIR/stop"
RUNNING_FILE="$STATE_DIR/running.env"
MIN_SLEEP_SECONDS=30
MAX_SLEEP_SECONDS=900
CONDITION_RETRY_SECONDS=60
FAILURE_LIMIT=3
FAILURE_PAUSE_SECONDS=21600
NEXT_CHECK_EPOCH=0
SLEEP_PID=

wake_scheduler() {
  if [ -n "${SLEEP_PID:-}" ]; then
    kill "$SLEEP_PID" 2>/dev/null || true
  fi
}
trap wake_scheduler USR1 HUP

while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 10
done
# 等待存储、包管理器和通知服务稳定，避免开机阶段误扫。
sleep 120

mkdir -p "$LOG_DIR"
[ -f "$CONFIG" ] || cp -f "$MODDIR/config/default.conf" "$CONFIG"

config_value() {
  sed -n "s/^$1=//p" "$CONFIG" 2>/dev/null | tail -n 1
}

bool_value() {
  [ "$(config_value "$1")" = "1" ] && echo 1 || echo 0
}

uint_value() {
  value=$(config_value "$1")
  fallback=$2
  min=$3
  max=$4
  case "$value" in ''|*[!0-9]*) value=$fallback ;; esac
  [ "$value" -lt "$min" ] && value=$min
  [ "$value" -gt "$max" ] && value=$max
  echo "$value"
}

valid_interval_seconds() {
  minutes_key=$1 hours_key=$2 fallback_minutes=$3
  minutes=$(config_value "$minutes_key")
  case "$minutes" in ''|*[!0-9]*) hours=$(uint_value "$hours_key" 1 1 720); minutes=$((hours * 60)) ;; esac
  [ "$minutes" -lt 5 ] && minutes=5
  [ "$minutes" -gt 43200 ] && minutes=43200
  echo $((minutes * 60))
}

daily_value() {
  uint_value "$1" "$2" 0 "$3"
}

write_scheduler_state() {
  state=$1
  group=${2:-}
  reason=${3:-}
  now=$(date +%s)
  old_state=$(sed -n 's/^state=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  old_group=$(sed -n 's/^group=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  old_reason=$(sed -n 's/^reason=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  old_updated=$(sed -n 's/^updated=//p' "$SCHEDULER_STATE" 2>/dev/null | tail -n 1)
  case "$old_updated" in ''|*[!0-9]*) old_updated=0 ;; esac
  case "$state" in
    running|completed|failed|interrupted|paused) ;;
    *)
      if [ "$state" = "$old_state" ] && [ "$group" = "$old_group" ] &&
         [ "$reason" = "$old_reason" ] && [ $((now - old_updated)) -lt 300 ]; then
        return 0
      fi
      ;;
  esac
  tmp="$SCHEDULER_STATE.tmp.$$"
  {
    echo "state=$state"
    echo "group=$group"
    echo "reason=$reason"
    echo "updated=$now"
    echo "next_check_epoch=${NEXT_CHECK_EPOCH:-0}"
    echo "scheduler_pid=$$"
    echo "heartbeat_epoch=$now"
  } >"$tmp" && mv -f "$tmp" "$SCHEDULER_STATE"
}

refresh_next_check() {
  next=$1
  [ -f "$SCHEDULER_STATE" ] || {
    NEXT_CHECK_EPOCH=$next
    write_scheduler_state "waiting" "" "等待下一次定时检查"
    return
  }
  state=$(sed -n 's/^state=//p' "$SCHEDULER_STATE" | tail -n 1)
  group=$(sed -n 's/^group=//p' "$SCHEDULER_STATE" | tail -n 1)
  reason=$(sed -n 's/^reason=//p' "$SCHEDULER_STATE" | tail -n 1)
  updated=$(sed -n 's/^updated=//p' "$SCHEDULER_STATE" | tail -n 1)
  case "$updated" in ''|*[!0-9]*) updated=$(date +%s) ;; esac
  tmp="$SCHEDULER_STATE.tmp.$$"
  {
    echo "state=${state:-waiting}"
    echo "group=$group"
    echo "reason=${reason:-等待下一次定时检查}"
    echo "updated=$updated"
    echo "next_check_epoch=$next"
    echo "scheduler_pid=$$"
    echo "heartbeat_epoch=$(date +%s)"
  } >"$tmp" && mv -f "$tmp" "$SCHEDULER_STATE"
}

rotate_scheduler_log() {
  file=$1
  [ -f "$file" ] || return 0
  size=$(wc -c <"$file" 2>/dev/null | tr -d ' ')
  case "$size" in ''|*[!0-9]*) size=0 ;; esac
  if [ "$size" -gt 262144 ]; then
    mv -f "$file" "$file.1" 2>/dev/null || : >"$file"
  fi
}

failure_count_file() { printf '%s/scheduler-fail-%s.count\n' "$STATE_DIR" "$1"; }
pause_until_file() { printf '%s/scheduler-pause-%s.until\n' "$STATE_DIR" "$1"; }

clear_group_failure() {
  rm -f "$(failure_count_file "$1")" "$(pause_until_file "$1")"
}

record_group_failure() {
  group=$1
  count_file=$(failure_count_file "$group")
  pause_file=$(pause_until_file "$group")
  count=$(sed -n '1p' "$count_file" 2>/dev/null)
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  count=$((count + 1))
  printf '%s\n' "$count" >"$count_file"
  if [ "$count" -ge "$FAILURE_LIMIT" ]; then
    until=$(( $(date +%s) + FAILURE_PAUSE_SECONDS ))
    printf '%s\n' "$until" >"$pause_file"
    FAILURE_REASON="连续失败 ${count} 次，已暂停 6 小时"
    return 1
  fi
  FAILURE_REASON="连续失败 ${count}/${FAILURE_LIMIT} 次"
  return 0
}

group_pause_remaining() {
  group=$1
  pause_file=$(pause_until_file "$group")
  until=$(sed -n '1p' "$pause_file" 2>/dev/null)
  case "$until" in ''|*[!0-9]*) until=0 ;; esac
  now=$(date +%s)
  if [ "$until" -gt "$now" ]; then
    echo $((until - now))
    return 0
  fi
  [ "$until" -gt 0 ] && rm -f "$pause_file" "$(failure_count_file "$group")"
  return 1
}

scheduler_task_alive() {
  pid=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
  case "$pid" in ''|*[!0-9]*) return 1 ;; esac
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in
    *baize_v2*cleaner.sh*|*baize-v2*cleaner.sh*|*cache-transaction.sh*|*native-scan.sh*|*cache-snapshot-clean.sh*|*profile-snapshot-clean.sh*|*apk-snapshot-*|*organizer-worker.sh*|*worker-runner.sh*organize*|*baize_engine*) return 0 ;;
  esac
  return 1
}

clear_stale_task_markers() {
  if [ -d "$LOCK_DIR" ] && scheduler_task_alive; then
    return 0
  fi
  [ -d "$LOCK_DIR" ] && rm -rf -- "$LOCK_DIR" 2>/dev/null
  rm -f "$STOP_FILE" "$RUNNING_FILE" 2>/dev/null
}

is_screen_off() {
  dumpsys power 2>/dev/null | grep -Eq 'Display Power: state=OFF|mWakefulness=Asleep|mInteractive=false'
}

is_device_idle() {
  idle_dump=$(dumpsys deviceidle 2>/dev/null)
  printf '%s\n' "$idle_dump" | grep -Eq 'mState=(IDLE|IDLE_MAINTENANCE)|mLightState=(IDLE|WAITING_FOR_NETWORK)'
}

conditions_allow_task() {
  task_group=${1:-clean}
  SCHEDULE_REASON=""
  [ "$(bool_value enabled)" = "1" ] || { SCHEDULE_REASON="Root 定时任务总开关已关闭"; return 1; }
  [ -f "$STATE_DIR/stop" ] && { SCHEDULE_REASON="已收到停止请求"; return 1; }

  screen_key=screen_off_only
  idle_key=device_idle_only
  charging_key=charging_only
  if [ "$task_group" = organize ]; then
    screen_key=organize_screen_off_only
    idle_key=organize_device_idle_only
    charging_key=organize_charging_only
  fi

  if [ "$(bool_value "$screen_key")" = "1" ] && ! is_screen_off; then
    SCHEDULE_REASON="等待息屏"
    return 1
  fi

  if [ "$(bool_value "$idle_key")" = "1" ] && ! is_device_idle; then
    SCHEDULE_REASON="等待系统进入空闲状态"
    return 1
  fi

  battery_dump=$(dumpsys battery 2>/dev/null)
  if [ "$(bool_value "$charging_key")" = "1" ]; then
    if ! printf '%s\n' "$battery_dump" | grep -Eq '^[[:space:]]*(AC powered|USB powered|Wireless powered|Dock powered): true'; then
      status=$(printf '%s\n' "$battery_dump" | sed -n 's/^[[:space:]]*status: //p' | head -n 1)
      if [ "$status" != "2" ] && [ "$status" != "5" ]; then
        SCHEDULE_REASON="等待设备充电"
        return 1
      fi
    fi
  fi

  min_battery=$(uint_value min_battery 25 0 100)
  battery=$(printf '%s\n' "$battery_dump" | sed -n 's/^[[:space:]]*level: //p' | head -n 1)
  case "$battery" in ''|*[!0-9]*) battery=100 ;; esac
  [ "$battery" -ge "$min_battery" ] || {
    SCHEDULE_REASON="等待电量达到 ${min_battery}%（当前 ${battery}%）"
    return 1
  }

  max_temp=$(uint_value max_battery_temp 45 30 60)
  raw_temp=$(printf '%s\n' "$battery_dump" | sed -n 's/^[[:space:]]*temperature: //p' | head -n 1)
  case "$raw_temp" in ''|*[!0-9]*) raw_temp=0 ;; esac
  if [ "$raw_temp" -gt 0 ]; then
    max_raw=$((max_temp * 10))
    if [ "$raw_temp" -gt "$max_raw" ]; then
      temp_text=$(awk -v t="$raw_temp" 'BEGIN {printf "%.1f", t/10}')
      SCHEDULE_REASON="等待电池降温（当前 ${temp_text}°C，上限 ${max_temp}°C）"
      return 1
    fi
  fi
  return 0
}

handle_cleaner_result() {
  code=$1
  group=$2
  success_reason=$3
  run_log=$4
  stamp_type=$5
  stamp_value=$6
  case "$code" in
    0)
      case "$stamp_type" in
        epoch) date +%s >"$stamp_value" ;;
        date) printf '%s\n' "$stamp_value" >"$STATE_DIR/last_${group}_daily.date" ;;
      esac
      clear_group_failure "$group"
      write_scheduler_state "completed" "$group" "$success_reason"
      ;;
    3)
      write_scheduler_state "waiting" "$group" "已有手动或其他清理任务正在运行，稍后重试"
      ;;
    9)
      write_scheduler_state "interrupted" "$group" "任务已停止或达到运行时长上限，本周期不会记为完成"
      ;;
    *)
      if record_group_failure "$group"; then
        write_scheduler_state "failed" "$group" "执行失败（代码 $code，$FAILURE_REASON），请查看 $run_log"
      else
        write_scheduler_state "paused" "$group" "执行失败（代码 $code）；$FAILURE_REASON，请查看 $run_log"
      fi
      ;;
  esac
}

run_due_group() {
  group=$1
  enabled_key=$2
  minutes_key=$3
  hours_key=$4
  fallback_minutes=$5
  mode=$6
  [ "$(bool_value "$enabled_key")" = "1" ] || return 0
  if remaining=$(group_pause_remaining "$group"); then
    write_scheduler_state "paused" "$group" "连续失败熔断中，约 $(( (remaining + 59) / 60 )) 分钟后重试"
    return 11
  fi
  interval_seconds=$(valid_interval_seconds "$minutes_key" "$hours_key" "$fallback_minutes")
  interval_minutes=$((interval_seconds / 60))
  stamp="$STATE_DIR/last_${group}_run.epoch"
  now=$(date +%s)
  last=$(sed -n '1p' "$stamp" 2>/dev/null)
  case "$last" in ''|*[!0-9]*) last=0 ;; esac
  due=$interval_seconds
  [ $((now - last)) -ge "$due" ] || return 0
  if ! conditions_allow_task "$group"; then
    write_scheduler_state "waiting" "$group" "$SCHEDULE_REASON"
    return 10
  fi
  write_scheduler_state "running" "$group" "定时任务执行中"
  run_log="$LOG_DIR/scheduler-${group}.log"
  rotate_scheduler_log "$run_log"
  sh "$MODDIR/task-worker.sh" "$mode" "scheduled:$group" "scheduled-$group-$(date +%s)" wait >>"$run_log" 2>&1
  code=$?
  handle_cleaner_result "$code" "$group" "已完成；下次约 ${interval_minutes} 分钟后" "$run_log" epoch "$stamp"
  return "$code"
}

run_daily_group() {
  group=$1
  enabled_key=$2
  mode=$3
  daily_cycle=$4
  daily_label=$5
  legacy_cycle_date=$6
  [ "$(bool_value "$enabled_key")" = "1" ] || return 0
  if remaining=$(group_pause_remaining "$group"); then
    write_scheduler_state "paused" "$group" "连续失败熔断中，约 $(( (remaining + 59) / 60 )) 分钟后重试"
    return 11
  fi
  stamp="$STATE_DIR/last_${group}_daily.date"
  stamp_value=$(sed -n '1p' "$stamp" 2>/dev/null)
  [ "$stamp_value" = "$daily_cycle" ] && return 0
  [ -n "$legacy_cycle_date" ] && [ "$stamp_value" = "$legacy_cycle_date" ] && return 0
  if ! conditions_allow_task "$group"; then
    write_scheduler_state "waiting" "$group" "$SCHEDULE_REASON"
    return 10
  fi
  write_scheduler_state "running" "$group" "每日定时任务执行中"
  run_log="$LOG_DIR/scheduler-${group}.log"
  rotate_scheduler_log "$run_log"
  sh "$MODDIR/task-worker.sh" "$mode" "daily:$group" "daily-$group-$(date +%s)" wait >>"$run_log" 2>&1
  code=$?
  handle_cleaner_result "$code" "$group" "每日任务已完成（$daily_label）" "$run_log" date "$daily_cycle"
  return "$code"
}

try_scheduled_clean() {
  any=0
  for key in schedule_cache_enabled schedule_empty_enabled schedule_rules_enabled schedule_fragment_enabled schedule_deep_enabled schedule_organize_enabled; do
    [ "$(bool_value "$key")" = "1" ] && any=1
  done
  [ "$any" = "1" ] || { write_scheduler_state "disabled" "" "所有定时任务均已关闭"; return 0; }

  # 文件归类始终使用自己的周期；它与清理任务共享锁、状态、恢复和历史。
  run_due_group organize schedule_organize_enabled schedule_organize_minutes schedule_organize_hours 1440 organize
  organize_code=$?
  case "$organize_code" in
    3|9) return 0 ;;
    0|10|11) ;;
    *) write_scheduler_state "failed" "organize" "文件归类调度失败（代码 $organize_code）" ;;
  esac

  # 每日模式优先且独占；超出补做窗口后跳过当天，避免白天突然执行。
  if [ "$(bool_value daily_schedule_enabled)" = "1" ]; then
    hour=$(daily_value daily_schedule_hour 3 23)
    minute=$(daily_value daily_schedule_minute 30 59)
    grace=$(uint_value daily_grace_minutes 240 15 720)
    now_epoch=$(date +%s)
    now_hour=$(date +%H | sed 's/^0//'); [ -n "$now_hour" ] || now_hour=0
    now_minute=$(date +%M | sed 's/^0//'); [ -n "$now_minute" ] || now_minute=0
    now_second=$(date +%S | sed 's/^0//'); [ -n "$now_second" ] || now_second=0
    now_total=$((now_hour * 60 + now_minute))
    target_total=$((hour * 60 + minute))
    schedule_text=$(printf '%02d:%02d' "$hour" "$minute")
    daily_context="今日 $schedule_text"
    legacy_cycle_date=$(date +%F 2>/dev/null)

    if [ "$now_total" -ge "$target_total" ]; then
      elapsed_minutes=$((now_total - target_total))
      if [ "$elapsed_minutes" -gt "$grace" ]; then
        write_scheduler_state "missed" "daily" "已超过今日补做窗口（${grace} 分钟），等待明日"
        return 0
      fi
    else
      previous_elapsed=$((now_total + 1440 - target_total))
      if [ "$previous_elapsed" -le "$grace" ]; then
        elapsed_minutes=$previous_elapsed
        daily_context="跨午夜补做 $schedule_text"
        legacy_cycle_date=$(date -d 'yesterday' +%F 2>/dev/null)
      else
        if [ $((target_total + grace)) -ge 1440 ]; then
          write_scheduler_state "missed" "daily" "已超过上一日补做窗口，等待今日 $schedule_text"
        else
          write_scheduler_state "waiting" "daily" "等待每日 $schedule_text"
        fi
        return 0
      fi
    fi

    daily_cycle=$((now_epoch - elapsed_minutes * 60 - now_second))
    failed_groups=""
    for daily_spec in \
      "cache schedule_cache_enabled cache-auto" \
      "empty schedule_empty_enabled empty-clean" \
      "rules schedule_rules_enabled rules-clean" \
      "fragment schedule_fragment_enabled fragment-clean" \
      "deep schedule_deep_enabled deep-clean"; do
      set -- $daily_spec
      group_name=$1
      run_daily_group "$1" "$2" "$3" "$daily_cycle" "$daily_context" "$legacy_cycle_date"
      group_code=$?
      case "$group_code" in
        3|9|10) return 0 ;;
        0|11) ;;
        *) failed_groups="${failed_groups}${failed_groups:+、}${group_name}(代码${group_code})" ;;
      esac
    done
    [ -n "$failed_groups" ] && write_scheduler_state "failed" "multiple" "部分每日任务失败：$failed_groups"
    return 0
  fi

  failed_groups=""
  for interval_spec in \
    "cache schedule_cache_enabled schedule_cache_minutes schedule_cache_hours 30 cache-auto" \
    "empty schedule_empty_enabled schedule_empty_minutes schedule_empty_hours 30 empty-clean" \
    "rules schedule_rules_enabled schedule_rules_minutes schedule_rules_hours 30 rules-clean" \
    "fragment schedule_fragment_enabled schedule_fragment_minutes schedule_fragment_hours 30 fragment-clean" \
    "deep schedule_deep_enabled schedule_deep_minutes schedule_deep_hours 10080 deep-clean"; do
    set -- $interval_spec
    group_name=$1
    run_due_group "$1" "$2" "$3" "$4" "$5" "$6"
    group_code=$?
    case "$group_code" in
      3|9|10) return 0 ;;
      0|11) ;;
      *) failed_groups="${failed_groups}${failed_groups:+、}${group_name}(代码${group_code})" ;;
    esac
  done
  [ -n "$failed_groups" ] && write_scheduler_state "failed" "multiple" "部分间隔任务失败：$failed_groups"
  return 0
}

clamp_sleep() {
  value=$1
  [ "$value" -lt "$MIN_SLEEP_SECONDS" ] && value=$MIN_SLEEP_SECONDS
  [ "$value" -gt "$MAX_SLEEP_SECONDS" ] && value=$MAX_SLEEP_SECONDS
  echo "$value"
}

compute_daily_sleep() {
  hour=$(daily_value daily_schedule_hour 3 23)
  minute=$(daily_value daily_schedule_minute 30 59)
  grace=$(uint_value daily_grace_minutes 240 15 720)
  now_hour=$(date +%H | sed 's/^0//'); [ -n "$now_hour" ] || now_hour=0
  now_minute=$(date +%M | sed 's/^0//'); [ -n "$now_minute" ] || now_minute=0
  now_second=$(date +%S | sed 's/^0//'); [ -n "$now_second" ] || now_second=0
  now_total=$((now_hour * 60 + now_minute))
  target_total=$((hour * 60 + minute))
  if [ "$now_total" -ge "$target_total" ] && [ $((now_total - target_total)) -le "$grace" ]; then
    echo "$CONDITION_RETRY_SECONDS"
    return
  fi
  previous_elapsed=$((now_total + 1440 - target_total))
  if [ "$now_total" -lt "$target_total" ] && [ "$previous_elapsed" -le "$grace" ]; then
    echo "$CONDITION_RETRY_SECONDS"
    return
  fi
  if [ "$now_total" -lt "$target_total" ]; then
    seconds=$(((target_total - now_total) * 60 - now_second))
  else
    seconds=$(((1440 - now_total + target_total) * 60 - now_second))
  fi
  clamp_sleep "$seconds"
}

compute_interval_sleep() {
  now=$(date +%s)
  minimum=0
  for spec in \
    "cache schedule_cache_enabled schedule_cache_minutes schedule_cache_hours 30" \
    "empty schedule_empty_enabled schedule_empty_minutes schedule_empty_hours 30" \
    "rules schedule_rules_enabled schedule_rules_minutes schedule_rules_hours 30" \
    "fragment schedule_fragment_enabled schedule_fragment_minutes schedule_fragment_hours 30" \
    "deep schedule_deep_enabled schedule_deep_minutes schedule_deep_hours 10080" \
    "organize schedule_organize_enabled schedule_organize_minutes schedule_organize_hours 1440"; do
    set -- $spec
    group=$1
    [ "$(bool_value "$2")" = "1" ] || continue
    interval_seconds=$(valid_interval_seconds "$3" "$4" "$5")
    stamp="$STATE_DIR/last_${group}_run.epoch"
    last=$(sed -n '1p' "$stamp" 2>/dev/null)
    case "$last" in ''|*[!0-9]*) last=0 ;; esac
    due_at=$((last + interval_seconds))
    pause_file=$(pause_until_file "$group")
    paused_until=$(sed -n '1p' "$pause_file" 2>/dev/null)
    case "$paused_until" in ''|*[!0-9]*) paused_until=0 ;; esac
    [ "$paused_until" -gt "$due_at" ] && due_at=$paused_until
    remaining=$((due_at - now))
    [ "$remaining" -lt 0 ] && remaining=0
    if [ "$minimum" -eq 0 ] || [ "$remaining" -lt "$minimum" ]; then minimum=$remaining; fi
  done
  [ "$minimum" -eq 0 ] && minimum=$CONDITION_RETRY_SECONDS
  clamp_sleep "$minimum"
}

compute_next_sleep() {
  [ "$(bool_value enabled)" = "1" ] || { echo 300; return; }
  if [ "$(bool_value daily_schedule_enabled)" = "1" ]; then
    compute_daily_sleep
  else
    compute_interval_sleep
  fi
}

while true; do
  clear_stale_task_markers
  try_scheduled_clean
  sleep_seconds=$(compute_next_sleep)
  now=$(date +%s)
  NEXT_CHECK_EPOCH=$((now + sleep_seconds))
  refresh_next_check "$NEXT_CHECK_EPOCH"
  sleep "$sleep_seconds" &
  SLEEP_PID=$!
  wait "$SLEEP_PID" 2>/dev/null || true
  SLEEP_PID=
done
