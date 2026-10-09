#!/system/bin/sh
# set -u：未定义变量视为错误。清理脚本以 root 身份删文件，
# 变量拼写错误静默展开成空串会造成 rm -rf "/foo" 这类事故。
set -u
MODDIR=${0%/*}
# Keep module data at the root; implementations live under scripts/.
case "$MODDIR" in */scripts) MODDIR=${MODDIR%/scripts} ;; esac
SCRIPTDIR="$MODDIR"
[ ! -d "$MODDIR/scripts" ] || SCRIPTDIR="$MODDIR/scripts"
APP_ID=io.github.xgl34222220.baize
rm -rf "$MODDIR/webroot" "$MODDIR/webui" "$MODDIR/www" "$MODDIR/ksu-webui" 2>/dev/null || true
if [ -x "$SCRIPTDIR/app-installer.sh" ]; then sh "$SCRIPTDIR/app-installer.sh" ensure >/dev/null 2>&1 || true; fi
# 操作按钮先显示简短状态（只读固定文件），再打开 App。
[ ! -f "$SCRIPTDIR/module-status.sh" ] || sh "$SCRIPTDIR/module-status.sh" summary "${BAIZE_STATE_DIR:-/data/adb/baize-v2}" "$MODDIR/module.prop" 2>/dev/null || true
pm path "$APP_ID" >/dev/null 2>&1 || { echo "白泽 App 未安装，请查看 /data/adb/baize-v2/app-install.env"; exit 1; }
am start -n "$APP_ID/.MiuixDashboardActivity" >/dev/null 2>&1 || monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
