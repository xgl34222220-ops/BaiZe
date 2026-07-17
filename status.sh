#!/system/bin/sh
MODDIR=${0%/*}
STATE_DIR=/data/adb/safesweep
CONFIG="$STATE_DIR/config.conf"
TOTALS_FILE="$STATE_DIR/totals.env"
SCHEDULER_STATE="$STATE_DIR/scheduler.env"
LATEST_ENV="$STATE_DIR/latest.env"

[ -f "$CONFIG" ] || CONFIG="$MODDIR/config/default.conf"

get_value() {
  sed -n "s/^$1=//p" "$CONFIG" 2>/dev/null | tail -n 1
}

env_value() {
  key=$1
  file=$2
  sed -n "s/^$key=//p" "$file" 2>/dev/null | tail -n 1
}

uint_or() {
  value=$1
  fallback=$2
  case "$value" in ''|*[!0-9]*) value=$fallback ;; esac
  printf '%s' "$value"
}

cfg_uint() {
  printf '%s' "$(uint_or "$(get_value "$1")" "$2")"
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/\\t/g; s/\r//g; s/\n/\\n/g'
}

pid_is_safesweep() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in
    *safesweep*cleaner.sh*|*safesweep*job-runner.sh*|*safesweep*webctl.sh*) return 0 ;;
  esac
  return 1
}

running=0
run_mode=""
run_phase=""
run_started=0
run_progress_current=0
run_progress_total=0
run_current_path=""
for lock in "$STATE_DIR/run.lock" "$STATE_DIR/launch.lock"; do
  [ -d "$lock" ] || continue
  lock_pid=$(uint_or "$(sed -n '1p' "$lock/pid" 2>/dev/null)" 0)
  if [ "$lock_pid" -gt 1 ] && kill -0 "$lock_pid" 2>/dev/null && pid_is_safesweep "$lock_pid"; then
    running=1
    run_phase="准备启动"
    break
  fi
done
if [ "$running" = "1" ] && [ -f "$STATE_DIR/running.env" ]; then
  run_mode=$(env_value mode "$STATE_DIR/running.env")
  run_phase=$(env_value phase "$STATE_DIR/running.env")
  run_started=$(uint_or "$(env_value started "$STATE_DIR/running.env")" 0)
  run_progress_current=$(uint_or "$(env_value progress_current "$STATE_DIR/running.env")" 0)
  run_progress_total=$(uint_or "$(env_value progress_total "$STATE_DIR/running.env")" 0)
  run_current_path=$(env_value current_path "$STATE_DIR/running.env")
fi

job_exit=$(uint_or "$(sed -n '1p' "$STATE_DIR/last_exit" 2>/dev/null)" 0)
last_mode=${last_mode:-$(env_value mode "$LATEST_ENV")}; [ -n "$last_mode" ] || last_mode=never
last_time=$(env_value time "$LATEST_ENV"); [ -n "$last_time" ] || last_time=从未运行
last_files=$(uint_or "$(env_value files "$LATEST_ENV")" 0)
last_regular_files=$(uint_or "$(env_value regular_files "$LATEST_ENV")" 0)
last_empty_files=$(uint_or "$(env_value empty_files "$LATEST_ENV")" 0)
last_empty_dirs=$(uint_or "$(env_value empty_dirs "$LATEST_ENV")" 0)
last_hidden_items=$(uint_or "$(env_value hidden_items "$LATEST_ENV")" 0)
last_fragment_files=$(uint_or "$(env_value fragment_files "$LATEST_ENV")" 0)
last_bytes=$(uint_or "$(env_value bytes "$LATEST_ENV")" 0)
last_elapsed=$(uint_or "$(env_value elapsed "$LATEST_ENV")" 0)
last_protected_items=$(uint_or "$(env_value protected_items "$LATEST_ENV")" 0)
last_protected_bytes=$(uint_or "$(env_value protected_bytes "$LATEST_ENV")" 0)
last_risk_low=$(uint_or "$(env_value risk_low "$LATEST_ENV")" 0)
last_risk_medium=$(uint_or "$(env_value risk_medium "$LATEST_ENV")" 0)
last_risk_high=$(uint_or "$(env_value risk_high "$LATEST_ENV")" 0)
last_risk_critical=$(uint_or "$(env_value risk_critical "$LATEST_ENV")" 0)
last_result=$(env_value result "$LATEST_ENV"); [ -n "$last_result" ] || last_result=等待首次清理

total_runs=$(uint_or "$(env_value runs "$TOTALS_FILE")" 0)
total_regular_files=$(uint_or "$(env_value regular_files "$TOTALS_FILE")" 0)
total_empty_files=$(uint_or "$(env_value empty_files "$TOTALS_FILE")" 0)
total_empty_dirs=$(uint_or "$(env_value empty_dirs "$TOTALS_FILE")" 0)
total_hidden_items=$(uint_or "$(env_value hidden_items "$TOTALS_FILE")" 0)
total_fragment_files=$(uint_or "$(env_value fragment_files "$TOTALS_FILE")" 0)
total_bytes=$(uint_or "$(env_value bytes "$TOTALS_FILE")" 0)
total_elapsed=$(uint_or "$(env_value elapsed "$TOTALS_FILE")" 0)
total_last_time=$(env_value last_time "$TOTALS_FILE"); [ -n "$total_last_time" ] || total_last_time=从未清理

scheduler_state=$(env_value state "$SCHEDULER_STATE"); [ -n "$scheduler_state" ] || scheduler_state=unknown
scheduler_group=$(env_value group "$SCHEDULER_STATE")
scheduler_reason=$(env_value reason "$SCHEDULER_STATE"); [ -n "$scheduler_reason" ] || scheduler_reason=尚未收到定时服务状态
scheduler_updated=$(uint_or "$(env_value updated "$SCHEDULER_STATE")" 0)

RULE_AUDIT="$STATE_DIR/rule_audit.env"
if [ ! -f "$RULE_AUDIT" ]; then
  deep_rule_count=$(awk '/^[[:space:]]*\//{n++} END{print n+0}' "$MODDIR/config/deep.rules" 2>/dev/null)
  deep_rule_unique=$(sed -n '/^[[:space:]]*\//p' "$MODDIR/config/deep.rules" 2>/dev/null | sort -u | wc -l | tr -d ' ')
  deep_rule_count=$(uint_or "$deep_rule_count" 0)
  deep_rule_unique=$(uint_or "$deep_rule_unique" 0)
  deep_rule_duplicates=$((deep_rule_count - deep_rule_unique)); [ "$deep_rule_duplicates" -lt 0 ] && deep_rule_duplicates=0
  audit_tmp="$RULE_AUDIT.tmp.$$"
  if { echo "count=$deep_rule_count"; echo "unique=$deep_rule_unique"; echo "duplicates=$deep_rule_duplicates"; } >"$audit_tmp" 2>/dev/null; then
    chmod 0600 "$audit_tmp" 2>/dev/null
    mv -f "$audit_tmp" "$RULE_AUDIT" 2>/dev/null
  else
    rm -f "$audit_tmp"
  fi
else
  deep_rule_count=$(uint_or "$(env_value count "$RULE_AUDIT")" 0)
  deep_rule_unique=$(uint_or "$(env_value unique "$RULE_AUDIT")" 0)
  deep_rule_duplicates=$(uint_or "$(env_value duplicates "$RULE_AUDIT")" 0)
fi
deep_scan_epoch=$(uint_or "$(env_value epoch "$STATE_DIR/deep_scan.env")" 0)
deep_scan_bytes=$(uint_or "$(env_value bytes "$STATE_DIR/deep_scan.env")" 0)
deep_scan_items=$(uint_or "$(env_value items "$STATE_DIR/deep_scan.env")" 0)
deep_scan_slow_items=$(uint_or "$(env_value slow_items "$STATE_DIR/deep_scan.env")" 0)
deep_scan_mount_items=$(uint_or "$(env_value mount_items "$STATE_DIR/deep_scan.env")" 0)
deep_scan_truncated=$(uint_or "$(env_value truncated "$STATE_DIR/deep_scan.env")" 0)
deep_scan_processed=$(uint_or "$(env_value processed "$STATE_DIR/deep_scan.env")" 0)
deep_scan_targets=$(uint_or "$(env_value targets "$STATE_DIR/deep_scan.env")" 0)
[ -f "$STATE_DIR/deep_scan.targets" ] || deep_scan_epoch=0
corpse_scan_epoch=$(uint_or "$(env_value epoch "$STATE_DIR/corpse_scan.env")" 0)
corpse_scan_bytes=$(uint_or "$(env_value bytes "$STATE_DIR/corpse_scan.env")" 0)
corpse_scan_items=$(uint_or "$(env_value items "$STATE_DIR/corpse_scan.env")" 0)
[ -f "$STATE_DIR/corpse_scan.targets" ] || corpse_scan_epoch=0
[ -f "$STATE_DIR/reports/latest.tsv" ] && report_lines=$(wc -l <"$STATE_DIR/reports/latest.tsv" 2>/dev/null | tr -d ' ') || report_lines=0
report_lines=$(uint_or "$report_lines" 0)
[ "$report_lines" -gt 0 ] && report_lines=$((report_lines - 1))

brand=$(getprop ro.product.brand)
model=$(getprop ro.product.model)
android=$(getprop ro.build.version.release)
set -- $(df -k /data 2>/dev/null | awk 'NR > 1 {line=$0} END {print $2, $3, $4, $5}')
data_total_kb=$(uint_or "${1:-0}" 0)
data_used_kb=$(uint_or "${2:-0}" 0)
data_free_kb=$(uint_or "${3:-0}" 0)
data_percent=$(printf '%s' "${4:-0}" | tr -d '%')
data_percent=$(uint_or "$data_percent" 0)

printf '{'
printf '"brand":"%s",' "$(json_escape "$brand")"
printf '"model":"%s",' "$(json_escape "$model")"
printf '"android":"%s",' "$(json_escape "$android")"
printf '"running":%s,' "$running"
printf '"stop_requested":%s,' "$( [ -f "$STATE_DIR/stop" ] && echo true || echo false )"
printf '"run_mode":"%s",' "$(json_escape "$run_mode")"
printf '"run_phase":"%s",' "$(json_escape "$run_phase")"
printf '"run_started":%s,' "$run_started"
printf '"run_progress_current":%s,' "$run_progress_current"
printf '"run_progress_total":%s,' "$run_progress_total"
printf '"run_current_path":"%s",' "$(json_escape "$run_current_path")"
printf '"job_exit":%s,' "$job_exit"
printf '"data_total_kb":%s,' "$data_total_kb"
printf '"data_used_kb":%s,' "$data_used_kb"
printf '"data_free_kb":%s,' "$data_free_kb"
printf '"data_percent":%s,' "$data_percent"
printf '"total_runs":%s,' "$total_runs"
printf '"total_regular_files":%s,' "$total_regular_files"
printf '"total_empty_files":%s,' "$total_empty_files"
printf '"total_empty_dirs":%s,' "$total_empty_dirs"
printf '"total_hidden_items":%s,' "$total_hidden_items"
printf '"total_fragment_files":%s,' "$total_fragment_files"
printf '"total_bytes":%s,' "$total_bytes"
printf '"total_elapsed":%s,' "$total_elapsed"
printf '"total_last_time":"%s",' "$(json_escape "$total_last_time")"
printf '"scheduler_state":"%s",' "$(json_escape "$scheduler_state")"
printf '"scheduler_group":"%s",' "$(json_escape "$scheduler_group")"
printf '"scheduler_reason":"%s",' "$(json_escape "$scheduler_reason")"
printf '"scheduler_updated":%s,' "$scheduler_updated"
printf '"deep_rule_count":%s,' "$deep_rule_count"
printf '"deep_rule_unique":%s,' "$deep_rule_unique"
printf '"deep_rule_duplicates":%s,' "$deep_rule_duplicates"
printf '"deep_scan_epoch":%s,' "$deep_scan_epoch"
printf '"deep_scan_bytes":%s,' "$deep_scan_bytes"
printf '"deep_scan_items":%s,' "$deep_scan_items"
printf '"deep_scan_slow_items":%s,' "$deep_scan_slow_items"
printf '"deep_scan_mount_items":%s,' "$deep_scan_mount_items"
printf '"deep_scan_truncated":%s,' "$deep_scan_truncated"
printf '"deep_scan_processed":%s,' "$deep_scan_processed"
printf '"deep_scan_targets":%s,' "$deep_scan_targets"
printf '"corpse_scan_epoch":%s,' "$corpse_scan_epoch"
printf '"corpse_scan_bytes":%s,' "$corpse_scan_bytes"
printf '"corpse_scan_items":%s,' "$corpse_scan_items"
printf '"report_lines":%s,' "$report_lines"

for spec in \
  enabled:1 screen_off_only:1 charging_only:0 device_idle_only:0 min_battery:25 max_battery_temp:45 max_run_minutes:45 \
  daily_schedule_enabled:0 daily_schedule_hour:3 daily_schedule_minute:30 daily_grace_minutes:240 \
  schedule_cache_enabled:1 schedule_cache_hours:24 schedule_empty_enabled:1 schedule_empty_hours:24 \
  schedule_rules_enabled:1 schedule_rules_hours:24 schedule_fragment_enabled:1 schedule_fragment_hours:24 \
  schedule_deep_enabled:0 schedule_deep_hours:168 clean_app_cache:1 clean_external_cache:1 clean_system_logs:1 \
  clean_oem_logs:0 clean_empty_files:1 clean_empty_dirs:1 clean_app_rules:1 clean_hidden_junk:1 clean_fragments:1 clean_custom_rules:0 \
  notify_on_complete:1 notify_zero_result:0 deep_high_risk_enabled:0 app_cache_days:0 external_cache_days:0 \
  system_logs_days:7 oem_logs_days:7 empty_file_days:0 hidden_junk_days:0 fragment_days:7 max_file_mb:256; do
  key=${spec%%:*}
  fallback=${spec#*:}
  printf '"%s":%s,' "$key" "$(cfg_uint "$key" "$fallback")"
done

printf '"last_mode":"%s",' "$(json_escape "$last_mode")"
printf '"last_time":"%s",' "$(json_escape "$last_time")"
printf '"last_files":%s,' "$last_files"
printf '"last_regular_files":%s,' "$last_regular_files"
printf '"last_empty_files":%s,' "$last_empty_files"
printf '"last_empty_dirs":%s,' "$last_empty_dirs"
printf '"last_hidden_items":%s,' "$last_hidden_items"
printf '"last_fragment_files":%s,' "$last_fragment_files"
printf '"last_bytes":%s,' "$last_bytes"
printf '"last_elapsed":%s,' "$last_elapsed"
printf '"last_protected_items":%s,' "$last_protected_items"
printf '"last_protected_bytes":%s,' "$last_protected_bytes"
printf '"last_risk_low":%s,' "$last_risk_low"
printf '"last_risk_medium":%s,' "$last_risk_medium"
printf '"last_risk_high":%s,' "$last_risk_high"
printf '"last_risk_critical":%s,' "$last_risk_critical"
printf '"last_result":"%s"' "$(json_escape "$last_result")"
printf '}\n'
