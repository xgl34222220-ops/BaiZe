#!/system/bin/sh

APP_ID="io.github.xgl34222220.baize"
STATE_DIR="/data/adb/baize-v2"
OLD_MOD="/data/adb/modules/safesweep"
MIGRATION_MARKER="$STATE_DIR/legacy-v1-disabled-by-alpha6"

pm uninstall "$APP_ID" >/dev/null 2>&1 || true

# Only remove the legacy disable flag when Alpha 6 created it. A v1 module that was already disabled
# by the user remains disabled.
if [ -f "$MIGRATION_MARKER" ] && [ -f "$OLD_MOD/module.prop" ]; then
  rm -f "$OLD_MOD/disable"
fi

rm -rf "$STATE_DIR"
