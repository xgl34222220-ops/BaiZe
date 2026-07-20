#!/system/bin/sh

APP_ID="io.github.xgl34222220.baize"
STATE_DIR="/data/adb/baize-v2"
OLD_MOD="/data/adb/modules/safesweep"
OLD_UPDATE="/data/adb/modules_update/safesweep"
OLD_STATE="/data/adb/safesweep"
APK="$MODPATH/app/baize.apk"
HASH_FILE="$MODPATH/app/baize.apk.sha256"
NATIVE_ENGINE="$MODPATH/bin/arm64-v8a/baize_engine"

# The native App is the only control surface. Some module managers preserve files from an
# older same-ID installation, so delete every known legacy WebUI directory explicitly.
for base in "$MODPATH" "/data/adb/modules/baize_v2" "/data/adb/modules_update/baize_v2"; do
  rm -rf "$base/webroot" "$base/webui" "$base/www" "$base/ksu-webui" 2>/dev/null || true
done

ui_print "- 安装白泽 v2.1.0 Alpha 4 有限并发预览版"
ui_print "- 原生 App 已接管全部清理与定时设置，旧 WebUI 将被彻底移除"
ui_print "- C 原生高速扫描：应用缓存、完整深度规则与卸载残留"
ui_print "- 缓存、安装包、深度清理与卸载残留均只消费扫描快照，不重复扫描"
ui_print "- MIUIx / Material 双界面，支持 Monet、明暗模式、AMOLED、玻璃与模糊"
ui_print "- 智能扫描显示真实任务阶段、目标路径与停止状态"
ui_print "- 自动清理、白名单、大文件、软链接、挂载点与风险分级保护均已启用"
ui_print "- 旧版 v1 将在迁移配置后彻底移除，不再保留双模块"

mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"

[ -f "$APK" ] || abort "! 模块包中缺少 app/baize.apk"
[ -f "$MODPATH/cleaner.sh" ] || abort "! 模块包中缺少清理总入口"
[ -f "$MODPATH/native-cleaner.sh" ] || abort "! 模块包中缺少原生扫描执行器"
[ -f "$MODPATH/cache-snapshot-clean.sh" ] || abort "! 模块包中缺少缓存快照执行器"
[ -f "$MODPATH/cache-transaction.sh" ] || abort "! 模块包中缺少自动缓存事务执行器"
[ -f "$MODPATH/apk-scanner.sh" ] || abort "! 模块包中缺少安装包快照扫描器"
[ -f "$MODPATH/apk-cleaner.sh" ] || abort "! 模块包中缺少安装包快照清理器"
[ -f "$MODPATH/profile-cleaner.sh" ] || abort "! 模块包中缺少深度/残留快照执行器"
[ -f "$MODPATH/cleaner.sh.compat" ] || abort "! 模块包中缺少兼容清理引擎"
[ -f "$NATIVE_ENGINE" ] || abort "! 模块包中缺少 arm64 原生扫描器"
[ -f "$MODPATH/scheduler.sh" ] || abort "! 模块包中缺少自动调度器"
[ -f "$MODPATH/config/deep.rules" ] || abort "! 模块包中缺少完整深度规则库"

touch "$STATE_DIR/stop" 2>/dev/null
pkill -f '/data/adb/modules/baize_v2/cleaner.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/native-cleaner.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/cache-snapshot-clean.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/cache-transaction.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/apk-scanner.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/apk-cleaner.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/profile-cleaner.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/bin/arm64-v8a/baize_engine' >/dev/null 2>&1 || true
rm -rf "$STATE_DIR/run.lock"
rm -f "$STATE_DIR/running.env" "$STATE_DIR/stop"
rm -f "$STATE_DIR/cache_scan.env" "$STATE_DIR/cache_scan.targets" "$STATE_DIR/cache_scan.items.tsv" "$STATE_DIR/cache_scan.manifest0"
rm -f "$STATE_DIR/cache_auto.env" "$STATE_DIR/cache_auto.targets" "$STATE_DIR/cache_auto.items.tsv" "$STATE_DIR/cache_auto.manifest0"
rm -f "$STATE_DIR/apk_scan.env" "$STATE_DIR/apk_scan.targets"
rm -f "$STATE_DIR/deep_scan.env" "$STATE_DIR/deep_scan.targets"
rm -f "$STATE_DIR/corpse_scan.env" "$STATE_DIR/corpse_scan.targets"

if [ -f "$OLD_MOD/module.prop" ] || [ -d "$OLD_UPDATE" ] || [ -d "$OLD_STATE" ]; then
  migrated=0
  for name in config.conf whitelist.conf custom.rules; do
    source="$OLD_STATE/$name"
    target="$STATE_DIR/$name"
    if [ ! -f "$target" ] && [ -f "$source" ]; then
      cp -f "$source" "$target"
      migrated=1
    fi
  done

  pkill -f '/data/adb/modules/safesweep' >/dev/null 2>&1 || true
  pkill -f '/data/adb/safesweep' >/dev/null 2>&1 || true
  rm -rf "$OLD_MOD" "$OLD_UPDATE" "$OLD_STATE"
  touch "$STATE_DIR/legacy-v1-removed"

  if [ "$migrated" -eq 1 ]; then
    ui_print "- 已迁移 v1 配置并彻底移除旧模块"
  else
    ui_print "- 已彻底移除旧版 v1 模块"
  fi
fi

chmod 0600 "$STATE_DIR/config.conf" "$STATE_DIR/whitelist.conf" "$STATE_DIR/custom.rules" 2>/dev/null
chmod 0644 "$APK" "$HASH_FILE" 2>/dev/null
chmod 0755 "$MODPATH/cleaner.sh" "$MODPATH/native-cleaner.sh" "$MODPATH/cache-snapshot-clean.sh" "$MODPATH/cache-transaction.sh" "$MODPATH/one-pass-scan.sh" "$MODPATH/apk-scanner.sh" "$MODPATH/apk-cleaner.sh" "$MODPATH/profile-cleaner.sh" 2>/dev/null
chmod 0755 "$MODPATH/cleaner.sh.compat" "$MODPATH/scheduler.sh" "$MODPATH/notify.sh" "$NATIVE_ENGINE" 2>/dev/null

install_app() {
  pm install -r -d --user 0 "$APK" >/dev/null 2>&1 && return 0
  pm install -r -d "$APK" >/dev/null 2>&1 && return 0
  return 1
}

if command -v pm >/dev/null 2>&1; then
  if install_app; then
    ui_print "- 白泽 App 已安装或覆盖更新"
    [ -f "$HASH_FILE" ] && cp -f "$HASH_FILE" "$STATE_DIR/installed-app.sha256"
  elif pm path "$APP_ID" >/dev/null 2>&1; then
    ui_print "! App 覆盖更新失败，已保留当前安装与全部设置"
    ui_print "! 可能是历史签名不兼容；请在确认备份后手动处理，模块不会自动卸载 App"
  else
    ui_print "! 白泽 App 安装失败；模块不会删除或替换其他应用"
    ui_print "! 开机服务将继续记录失败原因，便于在 App 或日志中诊断"
  fi
else
  ui_print "- 当前安装阶段无法调用包管理器"
  ui_print "- 开机完成后模块会尝试安全覆盖安装，不会自动卸载现有 App"
fi
