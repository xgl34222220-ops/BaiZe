#!/system/bin/sh

ui_print "- 白泽 v1.1.0-Beta3"
ui_print "- 深度安全项修复、扫描快照绑定与定时稳定性优化"

[ "$API" -lt 26 ] && abort "! 仅支持 Android 8.0 及以上系统"

STATE_DIR=/data/adb/safesweep
mkdir -p "$STATE_DIR/logs" "$STATE_DIR/reports"
rm -f "$STATE_DIR/running.env" "$STATE_DIR/deep_scan.env" "$STATE_DIR/deep_scan.targets" "$STATE_DIR/corpse_scan.env" "$STATE_DIR/corpse_scan.targets"
pid_is_safesweep() {
  pid=$1
  [ "$pid" -gt 1 ] 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null)
  case "$cmdline" in *safesweep*) return 0 ;; esac
  return 1
}

# 升级时结束旧版本仍在扫描的任务，避免旧进程与新脚本同时访问存储。
for lock in "$STATE_DIR/run.lock" "$STATE_DIR/launch.lock"; do
  pid=$(sed -n '1p' "$lock/pid" 2>/dev/null)
  case "$pid" in ''|*[!0-9]*) continue ;; esac
  [ "$pid" -gt 1 ] && pid_is_safesweep "$pid" && kill "$pid" 2>/dev/null
done
find "$STATE_DIR/run.lock" "$STATE_DIR/launch.lock" -type f -exec rm -f {} \; 2>/dev/null
rmdir "$STATE_DIR/run.lock/tmp" "$STATE_DIR/run.lock" "$STATE_DIR/launch.lock" 2>/dev/null

if [ ! -f "$STATE_DIR/config.conf" ]; then
  cp -f "$MODPATH/config/default.conf" "$STATE_DIR/config.conf"
fi

# 旧版配置迁移：保留原周期并将独立定时任务补齐，深度定时仍默认关闭。
old_scheduled=$(sed -n 's/^schedule_enabled=//p' "$STATE_DIR/config.conf" | tail -n 1)
[ "$old_scheduled" = "0" ] || old_scheduled=1
old_interval=$(sed -n 's/^interval_hours=//p' "$STATE_DIR/config.conf" | tail -n 1)
case "$old_interval" in ''|*[!0-9]*) old_interval=24 ;; esac
[ "$old_interval" -lt 1 ] && old_interval=6
[ "$old_interval" -gt 720 ] && old_interval=720
for item in \
  schedule_cache_enabled=$old_scheduled schedule_cache_hours=$old_interval \
  schedule_empty_enabled=$old_scheduled schedule_empty_hours=$old_interval \
  schedule_rules_enabled=$old_scheduled schedule_rules_hours=$old_interval \
  schedule_fragment_enabled=$old_scheduled schedule_fragment_hours=$old_interval \
  schedule_deep_enabled=0 schedule_deep_hours=168 \
  daily_schedule_enabled=0 daily_schedule_hour=3 daily_schedule_minute=30; do
  key=${item%%=*}
  grep -q "^$key=" "$STATE_DIR/config.conf" || echo "$item" >>"$STATE_DIR/config.conf"
done

# 新版独立计时从安装完成开始，避免升级后的首次开机连续触发多组任务。
schedule_seed=$(date +%s)
for group in cache empty rules fragment deep; do
  [ -f "$STATE_DIR/last_${group}_run.epoch" ] || echo "$schedule_seed" >"$STATE_DIR/last_${group}_run.epoch"
done

for item in clean_empty_files=1 clean_empty_dirs=1 clean_app_rules=1 clean_hidden_junk=1 clean_fragments=1 notify_on_complete=1 notify_zero_result=0 fragment_days=7 \
  charging_only=0 device_idle_only=0 max_battery_temp=45 max_run_minutes=45 daily_grace_minutes=240 deep_high_risk_enabled=0; do
  key=${item%%=*}
  grep -q "^$key=" "$STATE_DIR/config.conf" || echo "$item" >>"$STATE_DIR/config.conf"
done

# 默认全量清理缓存；升级时保留用户已经修改过的保留天数。
for item in app_cache_days=0 external_cache_days=0 empty_file_days=0 hidden_junk_days=0; do
  key=${item%%=*}
  grep -q "^$key=" "$STATE_DIR/config.conf" || echo "$item" >>"$STATE_DIR/config.conf"
done

if [ ! -f "$STATE_DIR/whitelist.conf" ]; then
  cp -f "$MODPATH/config/whitelist.conf" "$STATE_DIR/whitelist.conf"
fi

# v0.9.2：首次升级时把旧版最近一次实际清理结果作为累计统计起点。
if [ ! -f "$STATE_DIR/totals.env" ] && [ -f "$STATE_DIR/latest.env" ]; then
  previous_mode=$(sed -n 's/^mode=//p' "$STATE_DIR/latest.env" | tail -n 1)
  case "$previous_mode" in
    clean|deep-clean)
      previous_files=$(sed -n 's/^regular_files=//p' "$STATE_DIR/latest.env" | tail -n 1)
      previous_empty_files=$(sed -n 's/^empty_files=//p' "$STATE_DIR/latest.env" | tail -n 1)
      previous_empty_dirs=$(sed -n 's/^empty_dirs=//p' "$STATE_DIR/latest.env" | tail -n 1)
      previous_hidden=$(sed -n 's/^hidden_items=//p' "$STATE_DIR/latest.env" | tail -n 1)
      previous_fragments=$(sed -n 's/^fragment_files=//p' "$STATE_DIR/latest.env" | tail -n 1)
      previous_bytes=$(sed -n 's/^bytes=//p' "$STATE_DIR/latest.env" | tail -n 1)
      previous_elapsed=$(sed -n 's/^elapsed=//p' "$STATE_DIR/latest.env" | tail -n 1)
      previous_time=$(sed -n 's/^time=//p' "$STATE_DIR/latest.env" | tail -n 1 | awk '{print substr($1,6) " " substr($2,1,5)}')
      case "$previous_files" in ''|*[!0-9]*) previous_files=0 ;; esac
      case "$previous_empty_files" in ''|*[!0-9]*) previous_empty_files=0 ;; esac
      case "$previous_empty_dirs" in ''|*[!0-9]*) previous_empty_dirs=0 ;; esac
      case "$previous_hidden" in ''|*[!0-9]*) previous_hidden=0 ;; esac
      case "$previous_fragments" in ''|*[!0-9]*) previous_fragments=0 ;; esac
      case "$previous_bytes" in ''|*[!0-9]*) previous_bytes=0 ;; esac
      case "$previous_elapsed" in ''|*[!0-9]*) previous_elapsed=0 ;; esac
      [ -n "$previous_time" ] || previous_time=$(date '+%m-%d %H:%M')
      {
        echo 'runs=1'
        echo "regular_files=$previous_files"
        echo "empty_files=$previous_empty_files"
        echo "empty_dirs=$previous_empty_dirs"
        echo "hidden_items=$previous_hidden"
        echo "fragment_files=$previous_fragments"
        echo "bytes=$previous_bytes"
        echo "elapsed=$previous_elapsed"
        echo "last_time=$previous_time"
      } >"$STATE_DIR/totals.env"
      ;;
  esac
fi

# 安装完成后立即把已有累计数据写到模块卡片，无需等待下一次清理。
if [ -f "$STATE_DIR/totals.env" ]; then
  total_runs=$(sed -n 's/^runs=//p' "$STATE_DIR/totals.env" | tail -n 1)
  total_files=$(sed -n 's/^regular_files=//p' "$STATE_DIR/totals.env" | tail -n 1)
  total_empty_files=$(sed -n 's/^empty_files=//p' "$STATE_DIR/totals.env" | tail -n 1)
  total_empty_dirs=$(sed -n 's/^empty_dirs=//p' "$STATE_DIR/totals.env" | tail -n 1)
  total_hidden=$(sed -n 's/^hidden_items=//p' "$STATE_DIR/totals.env" | tail -n 1)
  total_fragments=$(sed -n 's/^fragment_files=//p' "$STATE_DIR/totals.env" | tail -n 1)
  total_bytes=$(sed -n 's/^bytes=//p' "$STATE_DIR/totals.env" | tail -n 1)
  total_elapsed=$(sed -n 's/^elapsed=//p' "$STATE_DIR/totals.env" | tail -n 1)
  total_last_time=$(sed -n 's/^last_time=//p' "$STATE_DIR/totals.env" | tail -n 1)
  total_space=$(awk -v b="${total_bytes:-0}" 'BEGIN {
    if (b >= 1073741824) printf "%.2f GB", b/1073741824;
    else if (b >= 1048576) printf "%.2f MB", b/1048576;
    else if (b >= 1024) printf "%.2f KB", b/1024;
    else printf "%.0f B", b;
  }')
  summary="累计清理 $total_space | 文件:${total_files:-0} 空文件:${total_empty_files:-0} 空目录:${total_empty_dirs:-0} 碎片:${total_fragments:-0} | ${total_runs:-0} 次 累计耗时:${total_elapsed:-0}秒 | 上次:${total_last_time:-未知}"
  awk -v d="$summary" '/^description=/{print "description=" d; next}{print}' "$MODPATH/module.prop" >"$MODPATH/module.prop.tmp"
  mv -f "$MODPATH/module.prop.tmp" "$MODPATH/module.prop"
fi

# 缓存规则审计结果，避免 WebUI 每两秒重复排序数千条规则。
deep_rule_count=$(awk '/^[[:space:]]*\//{n++} END{print n+0}' "$MODPATH/config/deep.rules" 2>/dev/null)
deep_rule_unique=$(sed -n '/^[[:space:]]*\//p' "$MODPATH/config/deep.rules" 2>/dev/null | sort -u | wc -l | tr -d ' ')
case "$deep_rule_count" in ''|*[!0-9]*) deep_rule_count=0 ;; esac
case "$deep_rule_unique" in ''|*[!0-9]*) deep_rule_unique=0 ;; esac
deep_rule_duplicates=$((deep_rule_count - deep_rule_unique)); [ "$deep_rule_duplicates" -lt 0 ] && deep_rule_duplicates=0
{
  echo "count=$deep_rule_count"
  echo "unique=$deep_rule_unique"
  echo "duplicates=$deep_rule_duplicates"
} >"$STATE_DIR/rule_audit.env"

touch "$STATE_DIR/custom.rules"
chmod 0700 "$STATE_DIR"
chmod 0600 "$STATE_DIR"/*.conf "$STATE_DIR"/*.rules 2>/dev/null
chmod 0600 "$STATE_DIR/totals.env" "$STATE_DIR/history.tsv" "$STATE_DIR/rule_audit.env" 2>/dev/null
chmod 0700 "$STATE_DIR/reports" 2>/dev/null
chmod 0600 "$STATE_DIR/reports"/* 2>/dev/null
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/cleaner.sh" 0 0 0755
set_perm "$MODPATH/notify.sh" 0 0 0755
set_perm "$MODPATH/status.sh" 0 0 0755
set_perm "$MODPATH/webctl.sh" 0 0 0755
set_perm "$MODPATH/job-runner.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

ui_print "- 深度规则全部保留；修复安全子规则被高风险父目录连带跳过"
ui_print "- 手动深度与卸载残留严格绑定本次扫描候选"
ui_print "- 定时支持充电、空闲、温度与补做窗口条件"
ui_print "- 标准 Magisk 请使用模块 Action 按钮"
ui_print "- KernelSU/APatch 可使用 WebUI"
ui_print "- 配置和日志保存在 /data/adb/safesweep"
