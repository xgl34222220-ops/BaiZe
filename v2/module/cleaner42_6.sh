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
  cache-clean)
    run_script "$MODDIR/cache-snapshot-clean.sh" "$MODE" "$TRIGGER"
    ;;
  deep-clean|corpse-clean)
    run_script "$MODDIR/profile-snapshot-clean.sh" "$MODE" "$TRIGGER"
    ;;
  cache-scan|deep-scan|corpse-scan)
    run_script "$MODDIR/native-scan.sh" "$MODE" "$TRIGGER"
    ;;
  *)
    run_script "$MODDIR/cleaner.sh.compat" "$@"
    ;;
esac
