#!/system/bin/sh
# set -u：未定义变量视为错误。清理脚本以 root 身份删文件，
# 变量拼写错误静默展开成空串会造成 rm -rf "/foo" 这类事故。
set -u

APP_ID="io.github.xgl34222220.baize"
STATE_DIR="/data/adb/baize-v2"
OLD_MOD="/data/adb/modules/safesweep"
OLD_UPDATE="/data/adb/modules_update/safesweep"
OLD_STATE="/data/adb/safesweep"
APK="$MODPATH/app/baize.apk"
HASH_FILE="$MODPATH/app/baize.apk.sha256"
# 按设备实际 ABI 解析引擎路径，不再假定 arm64。
. "$MODPATH/abi-resolve.sh"

# KernelSU/MMRL 等管理器解压 ZIP 时可能不保留原生 ELF 的执行位。
# ABI 解析器按 -x 选择引擎，所以必须先恢复打包引擎权限再解析。
for engine in "$MODPATH"/bin/*/baize_engine "$MODPATH"/bin/*/baize_deep_snapshot; do
  [ -f "$engine" ] && chmod 0755 "$engine"
done

NATIVE_ENGINE=$(baize_resolve_engine "$MODPATH" baize_engine 2>/dev/null || true)
DEEP_SNAPSHOT_ENGINE=$(baize_resolve_engine "$MODPATH" baize_deep_snapshot 2>/dev/null || true)
DEVICE_ABIS=$(baize_device_abis 2>/dev/null | tr '\n' ' ')

for base in "$MODPATH" "/data/adb/modules/baize_v2" "/data/adb/modules_update/baize_v2"; do
  rm -rf "$base/webroot" "$base/webui" "$base/www" "$base/ksu-webui" 2>/dev/null || true
done

ui_print "- 正在安装白泽 v2.6.1"
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
[ -f "$MODPATH/profile-cleaner.sh" ] || abort "! 模块包中缺少卸载残留快照执行器"
[ -f "$MODPATH/deep-scan-manifest.sh" ] || abort "! 模块包中缺少深度不可变快照扫描器"
[ -f "$MODPATH/deep-manifest-clean.sh" ] || abort "! 模块包中缺少深度不可变快照清理器"
[ -f "$MODPATH/cleaner.sh.compat" ] || abort "! 模块包中缺少兼容清理引擎"
[ -n "$NATIVE_ENGINE" ] && [ -f "$NATIVE_ENGINE" ] || \
  abort "! 模块包中没有适配当前架构（$DEVICE_ABIS）的原生扫描器"
[ -n "$DEEP_SNAPSHOT_ENGINE" ] && [ -f "$DEEP_SNAPSHOT_ENGINE" ] || \
  abort "! 模块包中没有适配当前架构（$DEVICE_ABIS）的深度快照引擎"
ui_print "- 已匹配架构：$(dirname "$NATIVE_ENGINE" | sed 's|.*/||')"
[ -f "$MODPATH/scheduler.sh" ] || abort "! 模块包中缺少自动调度器"
[ -f "$MODPATH/supervisor.sh" ] || abort "! 模块包中缺少调度器守护进程"
[ -f "$MODPATH/autopilot-controller.sh" ] || abort "! 模块包中缺少自动驾驶控制器"
[ -f "$MODPATH/task-worker.sh" ] || abort "! 模块包中缺少统一 Root Worker"
[ -f "$MODPATH/cache-lane-worker.sh" ] || abort "! 模块包中缺少应用缓存并行 Worker"
[ -f "$MODPATH/organizer-worker.sh" ] || abort "! 模块包中缺少文件归类 Worker"
[ -f "$MODPATH/config/deep.rules" ] || abort "! 模块包中缺少完整深度规则库"

# Stop both the legacy and immutable-manifest pipelines before replacing module files.
touch "$STATE_DIR/stop" 2>/dev/null
pkill -f '/data/adb/modules/baize_v2/cleaner.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/native-cleaner.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/cache-snapshot-clean.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/cache-transaction.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/cache-lane-worker.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/apk-scanner.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/apk-cleaner.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/profile-cleaner.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/deep-scan-manifest.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/deep-manifest-clean.sh' >/dev/null 2>&1 || true
# 匹配任意 ABI 目录下的引擎进程
pkill -f '/data/adb/modules/baize_v2/bin/.*/baize_engine' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/bin/.*/baize_deep_snapshot' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/organizer-worker.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/worker-runner.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/task-worker.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/scheduler.sh' >/dev/null 2>&1 || true
pkill -f '/data/adb/modules/baize_v2/supervisor.sh' >/dev/null 2>&1 || true
rm -rf "$STATE_DIR/run.lock" "$STATE_DIR/cache-lane.lock" "$STATE_DIR/cache-lane"
rm -f "$STATE_DIR/running.env" "$STATE_DIR/stop"
rm -f "$STATE_DIR/cache_scan.env" "$STATE_DIR/cache_scan.targets" "$STATE_DIR/cache_scan.items.tsv" "$STATE_DIR/cache_scan.manifest0"
rm -f "$STATE_DIR/cache_auto.env" "$STATE_DIR/cache_auto.targets" "$STATE_DIR/cache_auto.items.tsv" "$STATE_DIR/cache_auto.manifest0"
rm -f "$STATE_DIR/apk_scan.env" "$STATE_DIR/apk_scan.targets"
rm -f "$STATE_DIR/deep_scan.env" "$STATE_DIR/deep_scan.targets" "$STATE_DIR/deep_scan.manifest0" \
  "$STATE_DIR/deep_scan.cursor" "$STATE_DIR/deep_scan.manifest.env" "$STATE_DIR/deep_clean.accum.env"
rm -f "$STATE_DIR/corpse_scan.env" "$STATE_DIR/corpse_scan.targets"
rm -f "$STATE_DIR/worker.env" "$STATE_DIR/scheduler.env" "$STATE_DIR/supervisor.env" \
  "$STATE_DIR/scheduler-queue.tsv" "$STATE_DIR/scheduler-candidates.tmp" \
  "$STATE_DIR/supervisor.stop" "$STATE_DIR/stop"
rm -f "$STATE_DIR"/scheduler-retry-*.count "$STATE_DIR"/scheduler-retry-*.until \
  "$STATE_DIR"/scheduler-fail-*.count "$STATE_DIR"/scheduler-pause-*.until 2>/dev/null || true
rm -rf "$STATE_DIR/scheduler-requests" "$STATE_DIR/scheduler-skips"
mkdir -p "$STATE_DIR/scheduler-requests" "$STATE_DIR/scheduler-skips"
echo 'deep-manifest-v1' >"$STATE_DIR/runtime-schema"

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

# Upgrade only the untouched legacy default schedule. Explicit user-customized schedules are preserved.
CONFIG_FILE="$STATE_DIR/config.conf"
if [ -f "$CONFIG_FILE" ] && [ ! -f "$STATE_DIR/autopilot-defaults-migrated" ]; then
  value_of() { sed -n "s/^$1=//p" "$CONFIG_FILE" 2>/dev/null | tail -n 1; }
  if [ "$(value_of screen_off_only)" = 0 ] && \
     [ "$(value_of schedule_cache_minutes)" = 60 ] && \
     [ "$(value_of schedule_empty_minutes)" = 60 ] && \
     [ "$(value_of schedule_rules_minutes)" = 360 ] && \
     [ "$(value_of schedule_fragment_minutes)" = 720 ] && \
     [ "$(value_of app_cache_days)" = 0 ] && \
     [ "$(value_of external_cache_days)" = 0 ]; then
    sed -i \
      -e 's/^screen_off_only=0$/screen_off_only=1/' \
      -e 's/^max_battery_temp=0$/max_battery_temp=42/' \
      -e 's/^schedule_cache_hours=1$/schedule_cache_hours=24/' \
      -e 's/^schedule_empty_hours=1$/schedule_empty_hours=24/' \
      -e 's/^schedule_rules_hours=1$/schedule_rules_hours=24/' \
      -e 's/^schedule_fragment_hours=1$/schedule_fragment_hours=72/' \
      -e 's/^schedule_cache_minutes=60$/schedule_cache_minutes=1440/' \
      -e 's/^schedule_empty_minutes=60$/schedule_empty_minutes=1440/' \
      -e 's/^schedule_rules_minutes=360$/schedule_rules_minutes=1440/' \
      -e 's/^schedule_fragment_minutes=720$/schedule_fragment_minutes=4320/' \
      -e 's/^app_cache_days=0$/app_cache_days=2/' \
      -e 's/^external_cache_days=0$/external_cache_days=2/' \
      "$CONFIG_FILE"
    ui_print "- 已将旧默认计划升级为自动驾驶安全周期"
  fi
  touch "$STATE_DIR/autopilot-defaults-migrated"
fi

chmod 0600 "$STATE_DIR/config.conf" "$STATE_DIR/whitelist.conf" "$STATE_DIR/custom.rules" 2>/dev/null
chmod 0644 "$APK" "$HASH_FILE" 2>/dev/null
chmod 0755 "$MODPATH/cleaner.sh" "$MODPATH/native-cleaner.sh" "$MODPATH/cache-snapshot-clean.sh" "$MODPATH/cache-transaction.sh" "$MODPATH/cache-lane-worker.sh" "$MODPATH/one-pass-scan.sh" "$MODPATH/apk-scanner.sh" "$MODPATH/apk-cleaner.sh" "$MODPATH/profile-cleaner.sh" "$MODPATH/deep-scan-manifest.sh" "$MODPATH/deep-manifest-clean.sh" 2>/dev/null
chmod 0755 "$MODPATH/cleaner.sh.compat" "$MODPATH/scheduler.sh" "$MODPATH/notify.sh" "$NATIVE_ENGINE" "$DEEP_SNAPSHOT_ENGINE" 2>/dev/null
chmod 0755 "$MODPATH/task-worker.sh" "$MODPATH/organizer-worker.sh" "$MODPATH/worker-runner.sh" "$MODPATH/supervisor.sh" "$MODPATH/autopilot-controller.sh" "$MODPATH/app-installer.sh" "$MODPATH/diagnostics-export.sh" "$MODPATH/storage-analyzer.sh" "$MODPATH/duplicate-scanner.sh" "$MODPATH/large-file-scanner.sh" "$MODPATH/quarantine-manager.sh" "$MODPATH/rules-validator.sh" 2>/dev/null

# 安装前校验 APK 完整性。打包脚本会生成 baize.apk.sha256，
# 此前该文件只被复制到 state 目录，从未真正比对过，等于没有校验。
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" 2>/dev/null | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" 2>/dev/null | awk '{print $1}'
  elif command -v toybox >/dev/null 2>&1; then
    toybox sha256sum "$1" 2>/dev/null | awk '{print $1}'
  fi
}

verify_apk() {
  [ -f "$APK" ] || { ui_print "! 模块内缺少 APK"; return 1; }
  [ -f "$HASH_FILE" ] || { ui_print "! 缺少 APK 校验文件，拒绝安装"; return 1; }
  expected=$(tr -d ' \t\r\n' <"$HASH_FILE")
  case "$expected" in
    ????????????????????????????????????????????????????????????????) ;;
    *) ui_print "! APK 校验文件格式非法，拒绝安装"; return 1 ;;
  esac
  actual=$(sha256_of "$APK")
  if [ -z "$actual" ]; then
    ui_print "! 设备缺少 sha256 工具，无法校验 APK，拒绝安装"
    return 1
  fi
  if [ "$actual" != "$expected" ]; then
    ui_print "! APK 校验失败，模块包可能已损坏或被篡改"
    ui_print "  期望 $expected"
    ui_print "  实际 $actual"
    return 1
  fi
  ui_print "- APK 校验通过"
  return 0
}

install_app() {
  pm install -r -d --user 0 "$APK" >/dev/null 2>&1 && return 0
  pm install -r -d "$APK" >/dev/null 2>&1 && return 0
  return 1
}

if ! verify_apk; then
  ui_print "! 已跳过 App 安装，模块脚本部分仍会安装"
  ui_print "  请重新下载完整模块 ZIP 后再刷入"
elif command -v pm >/dev/null 2>&1; then
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
