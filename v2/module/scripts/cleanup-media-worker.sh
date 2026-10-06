#!/system/bin/sh
set -u
MODDIR=${BAIZE_MODULE_DIR:-${0%/*}}
case "$MODDIR" in */scripts) MODDIR=${MODDIR%/scripts} ;; esac
STATE_DIR=${BAIZE_ROOT_STATE_DIR:-${BAIZE_STATE_DIR:-/data/adb/baize-v2}}
[ -d "$STATE_DIR/cleanup-media" ] || exit 0
work=0
new_pending=0
for batch in "$STATE_DIR/cleanup-media"/pending-*; do
  [ -d "$batch" ] || continue
  work=1; new_pending=1; break
done
if [ "$work" = 0 ]; then
  for batch in "$STATE_DIR/cleanup-media"/inflight-* "$STATE_DIR/cleanup-media"/.building-*; do
    [ -d "$batch" ] || continue
    work=1; break
  done
fi
[ "$work" = 1 ] || exit 0
# Completed history alone must not start a JVM every supervisor heartbeat.
# New published work wakes immediately; retry/orphan polling is at most once
# per 30 seconds. Correctness still rests on the consumer's stable kernel lock.
now=$(date +%s)
next=$(cat "$STATE_DIR/cleanup-media/next-launch" 2>/dev/null || true)
case "$next" in ''|*[!0-9]*) next=0 ;; esac
if [ "$new_pending" = 0 ] && [ "$next" -gt "$now" ] && [ $((next - now)) -le 30 ]; then exit 0; fi
printf '%s\n' "$((now + 30))" >"$STATE_DIR/cleanup-media/next-launch.$$" &&
  mv -f "$STATE_DIR/cleanup-media/next-launch.$$" "$STATE_DIR/cleanup-media/next-launch"
# Use the module's matching APK, including its standalone main class.
APK="$MODDIR/app/baize.apk"
[ -f "$APK" ] && [ -x /system/bin/app_process ] || exit 0
CLASSPATH="$APK" exec /system/bin/app_process /system/bin \
  io.github.xgl34222220.baize.root.CleanupMediaWorker "$STATE_DIR"
