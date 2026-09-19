#!/system/bin/sh
# set -u：未定义变量视为错误。清理脚本以 root 身份删文件，
# 变量拼写错误静默展开成空串会造成 rm -rf "/foo" 这类事故。
set -u

case "$0" in */*) MODDIR=${0%/*} ;; *) MODDIR=. ;; esac
# Keep module data at the root; implementations live under scripts/.
case "$MODDIR" in */scripts) MODDIR=${MODDIR%/scripts} ;; esac
SCRIPTDIR="$MODDIR"
[ ! -d "$MODDIR/scripts" ] || SCRIPTDIR="$MODDIR/scripts"
MODE=${1:-scan}
TRIGGER=${2:-manual}
SHELL_BIN=${BAIZE_SHELL:-/system/bin/sh}

run_script() {
  script=$1
  shift
  [ -f "$script" ] || { echo "白泽任务组件缺失：${script##*/}，请重新刷入完整模块" >&2; exit 5; }
  exec "$SHELL_BIN" "$script" "$@"
}

case "$MODE" in
  apk-auto)
    run_script "$SCRIPTDIR/cleaner-compat.sh" apk-clean "$TRIGGER"
    ;;
  cache-auto)
    run_script "$SCRIPTDIR/cache-transaction.sh" "$MODE" "$TRIGGER"
    ;;
  cache-clean)
    run_script "$SCRIPTDIR/cache-snapshot-clean.sh" "$MODE" "$TRIGGER"
    ;;
  apk-scan)
    run_script "$SCRIPTDIR/apk-scanner.sh" "$MODE" "$TRIGGER"
    ;;
  apk-clean)
    run_script "$SCRIPTDIR/apk-cleaner.sh" "$MODE" "$TRIGGER"
    ;;
  deep-scan)
    # Keep a recognized task marker in /proc/<pid>/cmdline so the scheduler cannot clear a live lock.
    run_script "$SCRIPTDIR/deep-scan-manifest.sh" "$MODE" "$TRIGGER" "profile-cleaner.sh"
    ;;
  deep-clean)
    run_script "$SCRIPTDIR/deep-manifest-clean.sh" "$MODE" "$TRIGGER" "profile-cleaner.sh"
    ;;
  corpse-clean)
    run_script "$SCRIPTDIR/profile-cleaner.sh" "$MODE" "$TRIGGER"
    ;;
  cache-scan|corpse-scan)
    run_script "$SCRIPTDIR/native-cleaner.sh" "$MODE" "$TRIGGER"
    ;;
  *)
    run_script "$SCRIPTDIR/cleaner-compat.sh" "$@"
    ;;
esac
