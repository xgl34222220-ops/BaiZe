#!/system/bin/sh
# One-time, background (late_start, after sys.boot_completed) state migration.
# Shrinks the loose-file count of /data/adb/baize-v2 that init relabels on every
# boot. Never deletes a cleanup-media receipt before its archive copy is fsynced
# and re-read byte-for-byte (CleanupMediaWorker --migrate); failures retry next boot.
set -u
MODDIR=${BAIZE_MODULE_DIR:-${0%/*}}
case "$MODDIR" in */scripts) MODDIR=${MODDIR%/scripts} ;; esac
SCRIPTDIR="$MODDIR"
[ ! -d "$MODDIR/scripts" ] || SCRIPTDIR="$MODDIR/scripts"
STATE_DIR=${BAIZE_ROOT_STATE_DIR:-${BAIZE_STATE_DIR:-/data/adb/baize-v2}}
APP_PROCESS=${BAIZE_APP_PROCESS:-/system/bin/app_process}
[ -d "$STATE_DIR" ] || exit 0
LOCK="$STATE_DIR/state-migrate.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
  owner=$(cat "$LOCK/pid" 2>/dev/null)
  case "$owner" in ''|*[!0-9]*) owner=0 ;; esac
  if [ "$owner" -gt 1 ] && kill -0 "$owner" 2>/dev/null; then exit 0; fi
  rm -rf -- "$LOCK" 2>/dev/null; mkdir "$LOCK" 2>/dev/null || exit 0
fi
printf '%s\n' "$$" >"$LOCK/pid"
trap 'rm -rf -- "$LOCK" 2>/dev/null' EXIT INT TERM
renice -n 19 -p "$$" >/dev/null 2>&1 || true
ionice -c 3 -p "$$" >/dev/null 2>&1 || true

echo "[$(date '+%Y-%m-%d %H:%M:%S')] state migration start"
if [ -f "$SCRIPTDIR/state-retention.sh" ]; then
  . "$SCRIPTDIR/state-retention.sh"
  baize_prune_task_results "$STATE_DIR/task-results"
  baize_prune_task_logs "$STATE_DIR/logs"
  echo "task-results/logs bounded"
fi

COMPLETED="$STATE_DIR/cleanup-media/completed"
if [ -d "$COMPLETED" ] && [ ! -f "$COMPLETED/.compacted-v1" ]; then
  APK="$MODDIR/app/baize.apk"
  if [ -f "$APK" ] && [ -x "$APP_PROCESS" ]; then
    if CLASSPATH="$APK" "$APP_PROCESS" /system/bin \
        io.github.xgl34222220.baize.root.CleanupMediaWorker --migrate "$STATE_DIR"; then
      echo "cleanup-media receipts compacted"
    else
      echo "cleanup-media migration not confirmed; originals kept, retry next boot"
    fi
  else
    echo "cleanup-media migration skipped: App runtime unavailable"
  fi
fi
echo "[$(date '+%Y-%m-%d %H:%M:%S')] state migration end"
exit 0
