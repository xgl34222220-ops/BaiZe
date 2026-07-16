#!/system/bin/sh
MODDIR=${0%/*}
STATE_DIR=/data/adb/safesweep
CONFIG="$STATE_DIR/config.conf"
LOG_DIR="$STATE_DIR/logs"
SCHEDULER_STATE="$STATE_DIR/scheduler.env"
POLL_SECONDS=60

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

valid_interval() {
  uint_value "$1" "$2" 1 720
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
    running|completed|failed|interrupted) ;;
    *)
      if [ "$state" = "$old_state" ] && [ "$group" = "$old_group" ] && [ "$reason" = "$old_reason" ] && [ $((now - old_updated)) -lt 300 ]; then
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

is_screen_off() {
  dumpsys power 2>/dev/null | grep -Eq 'Display Power: state=OFF|mWakefulness=Asleep|mInteractive=false'
}


is_device_idle() {
  idle_dump=$(dumpsys deviceidle 2>/dev/null)
  printf '%s\n' "$idle_dump" | grep -Eq 'mState=(IDLE|IDLE_MAINTENANCE)|mLightState=(IDLE|WAITING_FOR_NETWORK)'
}

conditions_allow_clean() {
  SCHEDULE_REASON=""
  [ "$(bool_value enabled)" = "1" ] || { SCHEDULE_REASON="模块清理总开关已关闭"; return 1; }
  [ -f "$STATE_DIR/stop" ] && { SCHEDULE_REASON="已收到停止请求"; return 1; }

  if [ "$(bool_value screen_off_only)" = "1" ] && ! is_screen_off; then
    SCHEDULE_REASON="等待息屏"
    return 1
  fi

  if [ "$(bool_value device_idle_only)" = "1" ] && ! is_device_idle; then
    SCHEDULE_REASON="等待系统进入空闲状态"
    return 1
  fi

  battery_dump=$(dumpsys battery 2>/dev/null)
  if [ "$(bool_value charging_only)" = "1" ]; then
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
      write_scheduler_state "completed" "$group" "$success_reason"
      ;;
    3)
      write_scheduler_state "waiting" "$group" "已有手动或其他清理任务正在运行，稍后重试"
      ;;
    9)
      write_scheduler_state "interrupted" "$group" "任务已停止或达到运行时长上限，本周期不会记为完成"
      ;;
    *)
      write_scheduler_state "failed" "$group" "执行失败（代码 $code），请查看 $run_log"
      ;;
  esac
}

run_due_group() {
  group=$1
  enabled_key=$2
  hours_key=$3
  fallback=$4
  mode=$5
  [ "$(bool_value "$enabled_key")" = "1" ] || return 0
  interval=$(valid_interval "$hours_key" "$fallback")
  stamp="$STATE_DIR/last_${group}_run.epoch"
  now=$(date +%s)
  last=$(sed -n '1p' "$stamp" 2>/dev/null)
  case "$last" in ''|*[!0-9]*) last=0 ;; esac
  due=$((interval * 3600))
  [ $((now - last)) -ge "$due" ] || return 0
  if ! conditions_allow_clean; then
    write_scheduler_state "waiting" "$group" "$SCHEDULE_REASON"
    return 10
  fi
  write_scheduler_state "running" "$group" "定时任务执行中"
  run_log="$LOG_DIR/scheduler-${group}.log"
  rotate_scheduler_log "$run_log"
  sh "$MODDIR/cleaner.sh" "$mode" "scheduled:$group" >>"$run_log" 2>&1
  code=$?
  handle_cleaner_result "$code" "$group" "已完成；下次约 ${interval} 小时后" "$run_log" epoch "$stamp"
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
  stamp="$STATE_DIR/last_${group}_daily.date"
  stamp_value=$(sed -n '1p' "$stamp" 2>/dev/null)
  [ "$stamp_value" = "$daily_cycle" ] && return 0
  [ -n "$legacy_cycle_date" ] && [ "$stamp_value" = "$legacy_cycle_date" ] && return 0
  if ! conditions_allow_clean; then
    write_scheduler_state "waiting" "$group" "$SCHEDULE_REASON"
    return 10
  fi
  write_scheduler_state "running" "$group" "每日定时任务执行中"
  run_log="$LOG_DIR/scheduler-${group}.log"
  rotate_scheduler_log "$run_log"
  sh "$MODDIR/cleaner.sh" "$mode" "daily:$group" >>"$run_log" 2>&1
  code=$?
  handle_cleaner_result "$code" "$group" "每日任务已完成（$daily_label）" "$run_log" date "$daily_cycle"
  return "$code"
}

try_scheduled_clean() {
  any=0
  for key in schedule_cache_enabled schedule_empty_enabled schedule_rules_enabled schedule_fragment_enabled schedule_deep_enabled; do
    [ "$(bool_value "$key")" = "1" ] && any=1
  done
  [ "$any" = "1" ] || { write_scheduler_state "disabled" "" "所有定时任务均已关闭"; return 0; }

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

    # 使用本次计划时刻的 Epoch 作为周期标识，跨午夜后仍不会重复执行。
    daily_cycle=$((now_epoch - elapsed_minutes * 60 - now_second))
    failed_groups=""
    for daily_spec in \
      "cache schedule_cache_enabled cache-clean" \
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
        0) ;;
        *) failed_groups="${failed_groups}${failed_groups:+、}${group_name}(代码${group_code})" ;;
      esac
    done
    if [ -n "$failed_groups" ]; then
      write_scheduler_state "failed" "multiple" "部分每日任务失败：$failed_groups"
    fi
    return 0
  fi

  failed_groups=""
  for interval_spec in \
    "cache schedule_cache_enabled schedule_cache_hours 24 cache-clean" \
    "empty schedule_empty_enabled schedule_empty_hours 24 empty-clean" \
    "rules schedule_rules_enabled schedule_rules_hours 24 rules-clean" \
    "fragment schedule_fragment_enabled schedule_fragment_hours 24 fragment-clean" \
    "deep schedule_deep_enabled schedule_deep_hours 168 deep-clean"; do
    set -- $interval_spec
    group_name=$1
    run_due_group "$1" "$2" "$3" "$4" "$5"
    group_code=$?
    case "$group_code" in
      3|9|10) return 0 ;;
      0) ;;
      *) failed_groups="${failed_groups}${failed_groups:+、}${group_name}(代码${group_code})" ;;
    esac
  done
  if [ -n "$failed_groups" ]; then
    write_scheduler_state "failed" "multiple" "部分间隔任务失败：$failed_groups"
  fi
  return 0
}

while true; do
  try_scheduled_clean
  sleep "$POLL_SECONDS"
done
