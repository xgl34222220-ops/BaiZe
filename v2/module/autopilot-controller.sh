#!/system/bin/sh
set -u

MODDIR=${BAIZE_MODULE_DIR:-${0%/*}}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
CONFIG=${BAIZE_CONFIG_PATH:-$STATE_DIR/config.conf}
HISTORY_FILE=${BAIZE_HISTORY_FILE:-$STATE_DIR/history.tsv}
GLOBAL_STATE="$STATE_DIR/autopilot.env"
LAST_CHECK_FILE="$STATE_DIR/autopilot-last-check.epoch"
FORCE=${BAIZE_FORCE_AUTOPILOT:-0}

mkdir -p "$STATE_DIR"

config_value() { sed -n "s/^$1=//p" "$CONFIG" 2>/dev/null | tail -n 1; }
bool_value() { [ "$(config_value "$1")" = 1 ] && echo 1 || echo 0; }
bool_value_default() {
  value=$(config_value "$1"); fallback=$2
  case "$value" in 0|1) echo "$value" ;; *) echo "$fallback" ;; esac
}
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
      elif [ "$(bool_value_default autopilot_enabled 1)" = 1 ]; then echo 0
      else echo 1
      fi
      ;;
  esac
}
read_state_value() { sed -n "s/^$2=//p" "$1" 2>/dev/null | tail -n 1; }
write_atomic() {
  target=$1; shift
  tmp="$target.tmp.$$"
  cat >"$tmp"
  chmod 0600 "$tmp" 2>/dev/null || true
  mv -f "$tmp" "$target"
}
now_epoch() {
  case "${BAIZE_NOW_EPOCH:-}" in ''|*[!0-9]*) date +%s ;; *) printf '%s\n' "$BAIZE_NOW_EPOCH" ;; esac
}
NOW=$(now_epoch)
CHECK_SECONDS=$(uint_value autopilot_check_seconds 60 15 3600)
LAST_CHECK=$(sed -n '1p' "$LAST_CHECK_FILE" 2>/dev/null)
case "$LAST_CHECK" in ''|*[!0-9]*) LAST_CHECK=0 ;; esac
if [ "$FORCE" != 1 ] && [ $((NOW - LAST_CHECK)) -ge 0 ] && [ $((NOW - LAST_CHECK)) -lt "$CHECK_SECONDS" ]; then
  exit 0
fi
printf '%s\n' "$NOW" >"$LAST_CHECK_FILE"
chmod 0600 "$LAST_CHECK_FILE" 2>/dev/null || true

SCHEDULE_MODE=$(schedule_mode_value)
if [ "$SCHEDULE_MODE" != 0 ] || [ "$(bool_value_default autopilot_enabled 1)" != 1 ]; then
  DISABLED_REASON=autopilot_disabled
  case "$SCHEDULE_MODE" in
    1) DISABLED_REASON=strict_interval ;;
    2) DISABLED_REASON=fixed_daily ;;
  esac
  write_atomic "$GLOBAL_STATE" <<EOF_STATE
status=disabled
reason=$DISABLED_REASON
schedule_mode=$SCHEDULE_MODE
updated=$NOW
EOF_STATE
  exit 0
fi

storage_used_percent() {
  case "${BAIZE_STORAGE_USED_PERCENT:-}" in
    ''|*[!0-9]*)
      value=$(df -P "$STATE_DIR" 2>/dev/null | awk 'NR==2 {gsub(/%/,"",$5); print $5; exit}')
      case "$value" in ''|*[!0-9]*) value=0 ;; esac
      ;;
    *) value=$BAIZE_STORAGE_USED_PERCENT ;;
  esac
  [ "$value" -gt 100 ] && value=100
  echo "$value"
}
interactive_state() {
  case "${BAIZE_SCREEN_INTERACTIVE:-}" in
    0|1) echo "$BAIZE_SCREEN_INTERACTIVE" ;;
    *)
      if dumpsys power 2>/dev/null | grep -Eq 'Display Power: state=ON|mWakefulness=Awake|mInteractive=true'; then echo 1; else echo 0; fi
      ;;
  esac
}
battery_temp_tenths() {
  case "${BAIZE_BATTERY_TEMP_TENTHS:-}" in
    ''|*[!0-9]*)
      value=$(dumpsys battery 2>/dev/null | sed -n 's/^[[:space:]]*temperature: //p' | head -n 1)
      case "$value" in ''|*[!0-9]*) value=0 ;; esac
      ;;
    *) value=$BAIZE_BATTERY_TEMP_TENTHS ;;
  esac
  echo "$value"
}

STORAGE_USED=$(storage_used_percent)
PRESSURE_PERCENT=$(uint_value autopilot_pressure_percent 90 70 99)
PRESSURE=0
[ "$STORAGE_USED" -ge "$PRESSURE_PERCENT" ] && PRESSURE=1
INTERACTIVE=$(interactive_state)
REQUIRE_SCREEN_OFF=$(bool_value_default autopilot_require_screen_off 1)
TEMP_TENTHS=$(battery_temp_tenths)
MAX_TEMP_C=$(config_value max_battery_temp)
case "$MAX_TEMP_C" in ''|0|*[!0-9]*) MAX_TEMP_C=42 ;; esac
[ "$MAX_TEMP_C" -lt 30 ] && MAX_TEMP_C=30
[ "$MAX_TEMP_C" -gt 60 ] && MAX_TEMP_C=60
HOT=0
[ "$TEMP_TENTHS" -gt 0 ] && [ "$TEMP_TENTHS" -ge $((MAX_TEMP_C * 10)) ] && HOT=1
HOLD_SECONDS=$(uint_value autopilot_condition_hold_minutes 5 1 60)
HOLD_SECONDS=$((HOLD_SECONDS * 60))

LOW_BYTES=$(( $(uint_value autopilot_low_yield_mb 16 1 4096) * 1024 * 1024 ))
HIGH_BYTES=$(( $(uint_value autopilot_high_yield_mb 256 16 16384) * 1024 * 1024 ))
MAX_FACTOR=$(uint_value autopilot_max_interval_factor 8 1 16)
LOW_STREAK_LIMIT=$(uint_value autopilot_low_yield_streak 3 1 20)
ZERO_STREAK_LIMIT=$(uint_value autopilot_zero_yield_streak 5 2 30)
ZERO_SLEEP_SECONDS=$(( $(uint_value autopilot_zero_sleep_hours 72 1 720) * 3600 ))
DAILY=$(bool_value_default daily_schedule_enabled 0)

latest_history_for_group() {
  group=$1
  [ -f "$HISTORY_FILE" ] || return 0
  awk -F '\t' -v group="$group" '
    function match_group(g,m) {
      return (g=="cache" && (m=="cache-auto" || m=="cache-clean")) ||
             (g=="empty" && m=="empty-clean") ||
             (g=="rules" && m=="rules-clean") ||
             (g=="fragment" && m=="fragment-clean") ||
             (g=="deep" && m=="deep-clean")
    }
    match_group(group,$2) { line=$0 }
    END { if (line != "") print line }
  ' "$HISTORY_FILE"
}
base_interval_seconds() {
  group=$1
  case "$group" in
    cache) fallback=1440 ;;
    empty) fallback=1440 ;;
    rules) fallback=1440 ;;
    fragment) fallback=4320 ;;
    deep) fallback=10080 ;;
    *) fallback=1440 ;;
  esac
  minutes=$(config_value "schedule_${group}_minutes")
  case "$minutes" in
    ''|*[!0-9]*)
      hours=$(config_value "schedule_${group}_hours")
      case "$hours" in ''|*[!0-9]*) minutes=$fallback ;; *) minutes=$((hours * 60)) ;; esac
      ;;
  esac
  [ "$minutes" -lt 5 ] && minutes=5
  [ "$minutes" -gt 43200 ] && minutes=43200
  echo $((minutes * 60))
}
state_number() {
  file=$1; key=$2; fallback=$3
  value=$(read_state_value "$file" "$key")
  case "$value" in ''|*[!0-9]*) value=$fallback ;; esac
  echo "$value"
}

GLOBAL_STATUS=idle
GLOBAL_REASON=normal
[ "$PRESSURE" = 1 ] && { GLOBAL_STATUS=pressure; GLOBAL_REASON=storage_pressure; }
[ "$REQUIRE_SCREEN_OFF" = 1 ] && [ "$INTERACTIVE" = 1 ] && { GLOBAL_STATUS=waiting; GLOBAL_REASON=screen_active; }

for group in cache empty rules fragment deep; do
  [ "$(bool_value "schedule_${group}_enabled")" = 1 ] || continue
  state="$STATE_DIR/autopilot-$group.env"
  factor=$(state_number "$state" factor 1)
  low_streak=$(state_number "$state" low_streak 0)
  zero_streak=$(state_number "$state" zero_streak 0)
  suspend_until=$(state_number "$state" suspend_until 0)
  last_actual_epoch=$(state_number "$state" last_actual_epoch 0)
  old_signature=$(read_state_value "$state" signature)
  last_bytes=$(state_number "$state" last_bytes 0)

  latest=$(latest_history_for_group "$group")
  signature=
  if [ -n "$latest" ]; then
    signature=$(printf '%s\n' "$latest" | awk -F '\t' '{print $1"|"$2"|"$3"|"$4"|"$6}')
    bytes=$(printf '%s\n' "$latest" | awk -F '\t' '{print $3}')
    case "$bytes" in ''|*[!0-9]*) bytes=0 ;; esac
    if [ "$signature" != "$old_signature" ]; then
      last_actual_epoch=$NOW
      last_bytes=$bytes
      if [ "$bytes" -eq 0 ]; then
        zero_streak=$((zero_streak + 1))
        low_streak=$((low_streak + 1))
      elif [ "$bytes" -lt "$LOW_BYTES" ]; then
        zero_streak=0
        low_streak=$((low_streak + 1))
      elif [ "$bytes" -ge "$HIGH_BYTES" ]; then
        zero_streak=0
        low_streak=0
        factor=1
        suspend_until=0
      else
        zero_streak=0
        low_streak=0
        [ "$factor" -gt 1 ] && factor=$((factor / 2))
        [ "$factor" -lt 1 ] && factor=1
        suspend_until=0
      fi
      if [ "$zero_streak" -ge "$ZERO_STREAK_LIMIT" ]; then
        factor=$MAX_FACTOR
        suspend_until=$((NOW + ZERO_SLEEP_SECONDS))
      elif [ "$low_streak" -ge "$LOW_STREAK_LIMIT" ]; then
        factor=$((factor * 2))
        [ "$factor" -gt "$MAX_FACTOR" ] && factor=$MAX_FACTOR
        low_streak=0
      fi
    fi
  fi

  [ "$factor" -lt 1 ] && factor=1
  [ "$factor" -gt "$MAX_FACTOR" ] && factor=$MAX_FACTOR
  base=$(base_interval_seconds "$group")
  stamp="$STATE_DIR/last_${group}_run.epoch"
  stamp_value=$(sed -n '1p' "$stamp" 2>/dev/null)
  case "$stamp_value" in ''|*[!0-9]*) stamp_value=0 ;; esac
  [ "$last_actual_epoch" -gt 0 ] || last_actual_epoch=$stamp_value

  effective_factor=$factor
  effective_suspend=$suspend_until
  if [ "$PRESSURE" = 1 ]; then
    effective_factor=1
    effective_suspend=0
  fi

  desired_due=0
  if [ "$last_actual_epoch" -gt 0 ]; then
    desired_due=$((last_actual_epoch + base * effective_factor))
  fi
  [ "$effective_suspend" -gt "$desired_due" ] && desired_due=$effective_suspend
  if [ "$REQUIRE_SCREEN_OFF" = 1 ] && [ "$INTERACTIVE" = 1 ]; then
    screen_due=$((NOW + HOLD_SECONDS))
    [ "$screen_due" -gt "$desired_due" ] && desired_due=$screen_due
  fi

  # Fixed daily schedules remain explicit user intent. Autopilot still records yield but does not rewrite their daily cycle.
  if [ "$DAILY" != 1 ] && [ "$desired_due" -gt 0 ]; then
    anchor=$((desired_due - base))
    [ "$anchor" -lt 0 ] && anchor=0
    printf '%s\n' "$anchor" >"$stamp"
    chmod 0600 "$stamp" 2>/dev/null || true
  fi

  write_atomic "$state" <<EOF_STATE
signature=$signature
factor=$factor
low_streak=$low_streak
zero_streak=$zero_streak
suspend_until=$suspend_until
last_actual_epoch=$last_actual_epoch
last_bytes=$last_bytes
desired_due=$desired_due
storage_pressure=$PRESSURE
screen_hold=$([ "$REQUIRE_SCREEN_OFF" = 1 ] && [ "$INTERACTIVE" = 1 ] && echo 1 || echo 0)
temperature_hold=0
updated=$NOW
EOF_STATE
done

write_atomic "$GLOBAL_STATE" <<EOF_STATE
status=$GLOBAL_STATUS
reason=$GLOBAL_REASON
storage_used_percent=$STORAGE_USED
storage_pressure=$PRESSURE
screen_interactive=$INTERACTIVE
require_screen_off=$REQUIRE_SCREEN_OFF
battery_temp_tenths=$TEMP_TENTHS
schedule_mode=$SCHEDULE_MODE
max_battery_temp_c=$MAX_TEMP_C
daily_schedule=$DAILY
updated=$NOW
EOF_STATE
exit 0
