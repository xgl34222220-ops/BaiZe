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

ui_print "- 正在安装白泽 v2.4.1"
ui_print "- 白泽是 Android Root 垃圾清理与文件归类模块"
ui_print "- 用于扫描清理缓存、安装包、卸载残留和深度垃圾"
ui_print "- 可整理应用下载、接收、附件与导出文件"
ui_print "- 配套白泽 App 用于操作、白名单和定时任务设置"

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
[ -f "$MODPATH/supervisor.sh" ] || abort "! 模块包中缺少调度器守护进程"
[ -f "$MODPATH/task-worker.sh" ] || abort "! 模块包中缺少统一 Root Worker"
[ -f "$MODPATH/supervisor.sh" ] || abort "! 模块包中缺少调度器守护进程"
[ -f "$MODPATH/task-worker.sh" ] || abort "! 模块包中缺少统一 Root Worker"
[ -f "$MODPATH/organizer-worker.sh" ] || abort "! 模块包中缺少文件归类 Worker"
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
pkill -f '/data/adb/modules/baize_v2/organizer-worker.sh' >/dev/null 2>&1 || true
rm -rf "$STATE_DIR/run.lock"
rm -f "$STATE_DIR/running.env" "$STATE_DIR/stop"
rm -f "$STATE_DIR/cache_scan.env" "$STATE_DIR/cache_scan.targets" "$STATE_DIR/cache_scan.items.tsv" "$STATE_DIR/cache_scan.manifest0"
rm -f "$STATE_DIR/cache_auto.env" "$STATE_DIR/cache_auto.targets" "$STATE_DIR/cache_auto.items.tsv" "$STATE_DIR/cache_auto.manifest0"
rm -f "$STATE_DIR/apk_scan.env" "$STATE_DIR/apk_scan.targets"
rm -f "$STATE_DIR/deep_scan.env" "$STATE_DIR/deep_scan.targets"
rm -f "$STATE_DIR/corpse_scan.env" "$STATE_DIR/corpse_scan.targets"
# v2.4.1 test builds used a fixed six-hour fuse. Drop those stale counters on upgrade;
# the new scheduler uses short adaptive retry windows and user-triggered retries clear their own group.
rm -f "$STATE_DIR"/scheduler-fail-*.count "$STATE_DIR"/scheduler-fail-*.env "$STATE_DIR"/scheduler-pause-*.until 2>/dev/null || true

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
    ui_print "- 已迁移旧版设置"
  fi
fi

chmod 0600 "$STATE_DIR/config.conf" "$STATE_DIR/whitelist.conf" "$STATE_DIR/custom.rules" 2>/dev/null
chmod 0644 "$APK" "$HASH_FILE" 2>/dev/null
chmod 0755 "$MODPATH/cleaner.sh" "$MODPATH/native-cleaner.sh" "$MODPATH/cache-snapshot-clean.sh" "$MODPATH/cache-transaction.sh" "$MODPATH/one-pass-scan.sh" "$MODPATH/apk-scanner.sh" "$MODPATH/apk-cleaner.sh" "$MODPATH/profile-cleaner.sh" 2>/dev/null
chmod 0755 "$MODPATH/cleaner.sh.compat" "$MODPATH/scheduler.sh" "$MODPATH/notify.sh" "$NATIVE_ENGINE" 2>/dev/null
chmod 0755 "$MODPATH/task-worker.sh" "$MODPATH/worker-runner.sh" "$MODPATH/supervisor.sh" "$MODPATH/app-installer.sh" "$MODPATH/diagnostics-export.sh" "$MODPATH/storage-analyzer.sh" "$MODPATH/duplicate-scanner.sh" "$MODPATH/large-file-scanner.sh" "$MODPATH/quarantine-manager.sh" "$MODPATH/rules-validator.sh" 2>/dev/null
chmod 0755 "$MODPATH/task-worker.sh" "$MODPATH/organizer-worker.sh" "$MODPATH/worker-runner.sh" "$MODPATH/supervisor.sh" "$MODPATH/app-installer.sh" "$MODPATH/diagnostics-export.sh" "$MODPATH/storage-analyzer.sh" "$MODPATH/duplicate-scanner.sh" "$MODPATH/large-file-scanner.sh" "$MODPATH/quarantine-manager.sh" "$MODPATH/rules-validator.sh" 2>/dev/null

install_app() {
  pm install -r -d --user 0 "$APK" >/dev/null 2>&1 && return 0
  pm install -r -d "$APK" >/dev/null 2>&1 && return 0
  return 1
}

if command -v pm >/dev/null 2>&1; then
  if install_app; then
    ui_print "- 白泽 App 已安装或更新"
    [ -f "$HASH_FILE" ] && cp -f "$HASH_FILE" "$STATE_DIR/installed-app.sha256"
  elif pm path "$APP_ID" >/dev/null 2>&1; then
    ui_print "! 白泽 App 更新失败，请手动安装模块内 APK"
  else
    ui_print "! 白泽 App 安装失败，请手动安装模块内 APK"
  fi
else
  ui_print "- 重启后将再次尝试安装白泽 App"
fi
