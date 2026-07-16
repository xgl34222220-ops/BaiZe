#!/system/bin/sh
MODDIR=${0%/*}
STATE_DIR=/data/adb/safesweep
CONFIG="$STATE_DIR/config.conf"
WHITELIST="$STATE_DIR/whitelist.conf"

mkdir -p "$STATE_DIR/logs"
[ -f "$CONFIG" ] || cp -f "$MODDIR/config/default.conf" "$CONFIG"
[ -f "$WHITELIST" ] || cp -f "$MODDIR/config/whitelist.conf" "$WHITELIST"

validate_config_value() {
  cfg_key=$1
  cfg_value=$2
  cfg_min=
  cfg_max=
  case "$cfg_key" in
    enabled|screen_off_only|charging_only|device_idle_only|daily_schedule_enabled|schedule_cache_enabled|schedule_empty_enabled|schedule_rules_enabled|schedule_fragment_enabled|schedule_deep_enabled|clean_app_cache|clean_external_cache|clean_system_logs|clean_oem_logs|clean_empty_files|clean_empty_dirs|clean_app_rules|clean_hidden_junk|clean_fragments|clean_custom_rules|notify_on_complete|notify_zero_result|deep_high_risk_enabled)
      case "$cfg_value" in 0|1) return 0 ;; *) return 2 ;; esac
      ;;
    daily_schedule_hour) cfg_min=0; cfg_max=23 ;;
    daily_schedule_minute) cfg_min=0; cfg_max=59 ;;
    daily_grace_minutes) cfg_min=15; cfg_max=720 ;;
    interval_hours|schedule_cache_hours|schedule_empty_hours|schedule_rules_hours|schedule_fragment_hours|schedule_deep_hours) cfg_min=1; cfg_max=720 ;;
    min_battery) cfg_min=0; cfg_max=100 ;;
    max_battery_temp) cfg_min=30; cfg_max=60 ;;
    max_run_minutes) cfg_min=5; cfg_max=180 ;;
    app_cache_days|external_cache_days|system_logs_days|oem_logs_days|empty_file_days|hidden_junk_days) cfg_min=0; cfg_max=365 ;;
    fragment_days) cfg_min=1; cfg_max=365 ;;
    max_file_mb) cfg_min=1; cfg_max=4096 ;;
    *) return 2 ;;
  esac
  case "$cfg_value" in ''|*[!0-9]*) return 2 ;; esac
  [ "$cfg_value" -ge "$cfg_min" ] && [ "$cfg_value" -le "$cfg_max" ]
}

apply_config_value() {
  cfg_file=$1
  cfg_key=$2
  cfg_value=$3
  validate_config_value "$cfg_key" "$cfg_value" || return 2
  cfg_next="$cfg_file.next.$$"
  if grep -q "^$cfg_key=" "$cfg_file"; then
    sed "s/^$cfg_key=.*/$cfg_key=$cfg_value/" "$cfg_file" >"$cfg_next" || { rm -f "$cfg_next"; return 2; }
  else
    cp -f "$cfg_file" "$cfg_next" || return 2
    printf '%s=%s\n' "$cfg_key" "$cfg_value" >>"$cfg_next"
  fi
  mv -f "$cfg_next" "$cfg_file"
}

set_config() {
  staged="$STATE_DIR/config.set.$$"
  cp -f "$CONFIG" "$staged" || return 2
  apply_config_value "$staged" "$1" "$2" || { rm -f "$staged" "$staged.next.$$"; return 2; }
  chmod 0600 "$staged"
  mv -f "$staged" "$CONFIG"
}

pid_is_safesweep() {
  check_pid=$1
  [ "$check_pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$check_pid/cmdline" ] || return 1
  check_cmd=$(tr '\000' ' ' <"/proc/$check_pid/cmdline" 2>/dev/null)
  case "$check_cmd" in
    *safesweep*cleaner.sh*|*safesweep*job-runner.sh*|*safesweep*webctl.sh*) return 0 ;;
  esac
  return 1
}

save_whitelist() {
  encoded=$1
  case "$encoded" in *[!A-Za-z0-9+/=]*) return 2 ;; esac
  tmp="$WHITELIST.tmp.$$"
  printf '%s' "$encoded" | base64 -d >"$tmp" 2>/dev/null || { rm -f "$tmp"; return 2; }

  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in
      /*) ;;
      *) rm -f "$tmp"; return 2 ;;
    esac
    case "$line" in *'/../'*|*'/..'|*'/./'*|*'/.'|*'//'*) rm -f "$tmp"; return 2 ;; esac
  done <"$tmp"
  chmod 0600 "$tmp"
  mv -f "$tmp" "$WHITELIST"
}

save_config_batch() {
  encoded=$1
  case "$encoded" in *[!A-Za-z0-9+/=]*) return 2 ;; esac
  incoming="$STATE_DIR/config.batch.$$"
  staged="$STATE_DIR/config.staged.$$"
  printf '%s' "$encoded" | base64 -d >"$incoming" 2>/dev/null || { rm -f "$incoming"; return 2; }
  cp -f "$CONFIG" "$staged" || { rm -f "$incoming"; return 2; }
  while IFS='=' read -r key value extra || [ -n "$key$value$extra" ]; do
    if [ -z "$key" ] || [ -n "$extra" ] || ! apply_config_value "$staged" "$key" "$value"; then
      rm -f "$incoming" "$staged" "$staged.next.$$"
      return 2
    fi
  done <"$incoming"
  chmod 0600 "$staged"
  mv -f "$staged" "$CONFIG"
  rm -f "$incoming"
}

start_job() {
  mode=$1
  case "$mode" in scan|clean|cache-clean|empty-clean|rules-clean|fragment-scan|fragment-clean|deep-scan|deep-clean|corpse-scan|corpse-clean) ;; *) return 2 ;; esac
  launch="$STATE_DIR/launch.lock"

  for lock in "$STATE_DIR/run.lock" "$launch"; do
    [ -d "$lock" ] || continue
    pid=$(sed -n '1p' "$lock/pid" 2>/dev/null)
    case "$pid" in ''|*[!0-9]*) pid=0 ;; esac
    if [ "$pid" -gt 1 ] && kill -0 "$pid" 2>/dev/null && pid_is_safesweep "$pid"; then
      echo "busy"
      return 0
    fi
    find "$lock" -type f -exec rm -f {} \; 2>/dev/null
    rmdir "$lock/tmp" "$lock" 2>/dev/null
  done

  if ! mkdir "$launch" 2>/dev/null; then
    echo "无法创建任务锁" >&2
    return 4
  fi

  rm -f "$STATE_DIR/stop" "$STATE_DIR/last_exit"
  out="$STATE_DIR/logs/webui-run.log"
  if command -v setsid >/dev/null 2>&1; then
    setsid sh "$MODDIR/job-runner.sh" "$mode" </dev/null >"$out" 2>&1 &
  else
    nohup sh "$MODDIR/job-runner.sh" "$mode" </dev/null >"$out" 2>&1 &
  fi
  printf '%s\n' "$!" >"$launch/pid"
  echo "started"
}

case "$1" in
  status) "$MODDIR/status.sh" ;;
  log) tail -n "${2:-240}" "$STATE_DIR/logs/latest.log" 2>/dev/null || echo "暂无日志" ;;
  report) tail -n "${2:-600}" "$STATE_DIR/reports/latest.tsv" 2>/dev/null || echo "暂无扫描报告" ;;
  history) tail -n "${2:-100}" "$STATE_DIR/history.tsv" 2>/dev/null || echo "暂无历史记录" ;;
  rule-audit)
    total=$(awk '/^[[:space:]]*\//{n++} END{print n+0}' "$MODDIR/config/deep.rules" 2>/dev/null)
    unique=$(sed -n '/^[[:space:]]*\//p' "$MODDIR/config/deep.rules" 2>/dev/null | sort -u | wc -l | tr -d ' ')
    case "$total" in ''|*[!0-9]*) total=0 ;; esac
    case "$unique" in ''|*[!0-9]*) unique=0 ;; esac
    duplicate=$((total - unique)); [ "$duplicate" -lt 0 ] && duplicate=0
    { echo "count=$total"; echo "unique=$unique"; echo "duplicates=$duplicate"; } >"$STATE_DIR/rule_audit.env"
    chmod 0600 "$STATE_DIR/rule_audit.env"
    if command -v sha256sum >/dev/null 2>&1; then
      rule_sha=$(sha256sum "$MODDIR/config/deep.rules" 2>/dev/null | awk 'NR == 1 {print $1}')
    elif command -v toybox >/dev/null 2>&1; then
      rule_sha=$(toybox sha256sum "$MODDIR/config/deep.rules" 2>/dev/null | awk 'NR == 1 {print $1}')
    else
      rule_sha="不可用"
    fi
    printf '总规则=%s
去重规则=%s
重复规则=%s
规则文件SHA256=%s
' "$total" "$unique" "$duplicate" "$rule_sha"
    ;;
  whitelist) cat "$WHITELIST" ;;
  save-whitelist) save_whitelist "$2" && echo "ok" || { echo "白名单格式不正确" >&2; exit 2; } ;;
  set) set_config "$2" "$3" && echo "ok" || { echo "配置值不正确" >&2; exit 2; } ;;
  save-config) save_config_batch "$2" && echo "ok" || { echo "配置内容不正确" >&2; exit 2; } ;;
  run)
    case "$2" in scan|clean|cache-clean|empty-clean|rules-clean|fragment-scan|fragment-clean|deep-scan|deep-clean|corpse-scan|corpse-clean) "$MODDIR/cleaner.sh" "$2" webui ;; *) exit 2 ;; esac
    ;;
  start) start_job "$2" ;;
  stop) touch "$STATE_DIR/stop"; echo "已请求停止" ;;
  resume) rm -f "$STATE_DIR/stop"; echo "已恢复" ;;
  reset-stats)
    rm -f "$STATE_DIR/totals.env"
    tmp="$MODDIR/module.prop.tmp.$$"
    if awk '/^description=/{print "description=清理缓存、日志与存储垃圾。"; next}{print}' "$MODDIR/module.prop" >"$tmp"; then
      chmod 0644 "$tmp"
      mv -f "$tmp" "$MODDIR/module.prop"
    else
      rm -f "$tmp"
      exit 1
    fi
    echo "累计统计已重置"
    ;;
  notify-test)
    result=$(sh "$MODDIR/notify.sh" "白泽通知测试" "通知通道工作正常，之后清理完成会显示本次与累计结果。" "通知通道工作正常" "baize-test" 2>&1)
    case "$result" in ok:*) echo "测试通知已发送（${result#ok:}）" ;; *) echo "通知发送失败：$result" >&2; exit 5 ;; esac
    ;;
  *) echo "unsupported" >&2; exit 2 ;;
esac
