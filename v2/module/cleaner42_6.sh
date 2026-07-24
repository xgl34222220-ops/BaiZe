#!/system/bin/sh

MODDIR=${0%/*}
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
  cache-auto)
    run_script "$MODDIR/cache-transaction.sh" "$MODE" "$TRIGGER"
    ;;
  cache-clean)
    run_script "$MODDIR/cache-snapshot-clean.sh" "$MODE" "$TRIGGER"
    ;;
  apk-scan)
    run_script "$MODDIR/apk-scanner.sh" "$MODE" "$TRIGGER"
    ;;
  apk-clean)
    run_script "$MODDIR/apk-cleaner.sh" "$MODE" "$TRIGGER"
    ;;
  deep-scan)
    run_script "$MODDIR/deep-scan-manifest.sh" "$MODE" "$TRIGGER"
    ;;
  deep-clean)
    run_script "$MODDIR/deep-manifest-clean.sh" "$MODE" "$TRIGGER"
    ;;
  corpse-clean)
    run_script "$MODDIR/profile-cleaner.sh" "$MODE" "$TRIGGER"
    ;;
  cache-scan|corpse-scan)
    run_script "$MODDIR/native-cleaner.sh" "$MODE" "$TRIGGER"
    ;;
  *)
    run_script "$MODDIR/cleaner.sh.compat" "$@"
    ;;
esac
