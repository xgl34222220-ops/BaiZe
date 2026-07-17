#!/system/bin/sh

APP_ID="io.github.xgl34222220.baize"
STATE_DIR="/data/adb/baize-v2"
APK="$MODPATH/app/baize.apk"
HASH_FILE="$MODPATH/app/baize.apk.sha256"

ui_print "- 安装白泽 v2 Alpha 4"
ui_print "- 单一模块包已内置原生 App"
ui_print "- 模块负责 Root 服务、开机补装与后台能力"

mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"

if [ ! -f "$APK" ]; then
  abort "! 模块包中缺少 app/baize.apk"
fi

chmod 0644 "$APK" "$HASH_FILE" 2>/dev/null

install_app() {
  pm install -r -d --user 0 "$APK" >/dev/null 2>&1 && return 0
  pm install -r -d "$APK" >/dev/null 2>&1 && return 0
  return 1
}

if command -v pm >/dev/null 2>&1 && install_app; then
  ui_print "- 白泽 App 已安装或更新"
  [ -f "$HASH_FILE" ] && cp -f "$HASH_FILE" "$STATE_DIR/installed-app.sha256"
else
  ui_print "- 当前安装阶段无法调用包管理器"
  ui_print "- 开机完成后模块会自动补装 App"
fi
