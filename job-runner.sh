#!/system/bin/sh
MODDIR=${0%/*}
STATE_DIR=/data/adb/safesweep
mode=$1

case "$mode" in scan|clean|cache-clean|empty-clean|rules-clean|fragment-scan|fragment-clean|deep-scan|deep-clean|corpse-scan|corpse-clean) ;; *) exit 2 ;; esac

sh "$MODDIR/cleaner.sh" "$mode" webui
code=$?
printf '%s\n' "$code" >"$STATE_DIR/last_exit"
rm -f "$STATE_DIR/launch.lock/pid"
rmdir "$STATE_DIR/launch.lock" 2>/dev/null
exit "$code"
