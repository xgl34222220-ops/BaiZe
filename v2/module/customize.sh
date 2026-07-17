#!/system/bin/sh

APP_ID="io.github.xgl34222220.baize"
STATE_DIR="/data/adb/baize-v2"
APK="$MODPATH/app/baize.apk"
HASH_FILE="$MODPATH/app/baize.apk.sha256"

ui_print "- 安装白泽 v2 Alpha 5"
ui_print "- 单一模块包内置原生 App 与完整规则库"
ui_print "- App 提供完整清理界面，模块负责 Root、开机补装与后台能力"

mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"

if [ ! -f "$APK" ]; then
  abort "! 模块包中缺少 app/baize.apk"
fi
if [ ! -f "$MODPATH/config/deep.rules" ]; then
  abort "! 模块包中缺少完整深度规则库"
fi

chmod 0644 "$APK" "$HASH_FILE" 2>/dev/null

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
