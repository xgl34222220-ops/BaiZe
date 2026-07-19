#!/system/bin/sh

APP_ID="io.github.xgl34222220.baize"
STATE_DIR="/data/adb/baize-v2"
OLD_MOD="/data/adb/modules/safesweep"
OLD_UPDATE="/data/adb/modules_update/safesweep"
OLD_STATE="/data/adb/safesweep"
APK="$MODPATH/app/baize.apk"
HASH_FILE="$MODPATH/app/baize.apk.sha256"

ui_print "- 安装白泽 v2 Alpha 42.1 深度扫描流程热修复版"
ui_print "- 内置原生 App、完整清理引擎、真实调度器与规则库"
ui_print "- 旧版 v1 将在迁移配置后彻底移除，不再保留双模块"

mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"

[ -f "$APK" ] || abort "! 模块包中缺少 app/baize.apk"
[ -f "$MODPATH/cleaner.sh" ] || abort "! 模块包中缺少一键清理引擎"
[ -f "$MODPATH/scheduler.sh" ] || abort "! 模块包中缺少自动调度器"
[ -f "$MODPATH/config/deep.rules" ] || abort "! 模块包中缺少完整深度规则库"

# v1 and v2 use different module IDs. Copy the user's configuration once, stop the old scheduler,
# then remove the legacy module and its state completely so future flashes have no duplicate module,
# no repeated warning and no chance of two cleaners running together.
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
chmod 0755 "$MODPATH/cleaner.sh" "$MODPATH/scheduler.sh" "$MODPATH/notify.sh" 2>/dev/null

install_app() {
  pm install -r -d --user 0 "$APK" >/dev/null 2>&1 && return 0
  pm install -r -d "$APK" >/dev/null 2>&1 && return 0

  # Alpha 调试包的签名可能随 CI 构建变化；覆盖失败时只替换旧 Alpha App。
  if pm path "$APP_ID" >/dev/null 2>&1; then
    ui_print "- 旧 Alpha App 签名不兼容，正在自动替换"
    pm uninstall --user 0 "$APP_ID" >/dev/null 2>&1 || pm uninstall "$APP_ID" >/dev/null 2>&1
  fi
  pm install -d --user 0 "$APK" >/dev/null 2>&1 && return 0
  pm install -d "$APK" >/dev/null 2>&1 && return 0
  return 1
}

if command -v pm >/dev/null 2>&1 && install_app; then
  ui_print "- 白泽 App 已安装或更新"
  [ -f "$HASH_FILE" ] && cp -f "$HASH_FILE" "$STATE_DIR/installed-app.sha256"
else
  ui_print "- 当前安装阶段无法调用包管理器"
  ui_print "- 开机完成后模块会自动补装 App"
fi
