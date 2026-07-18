#!/usr/bin/env sh
set -u

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
LOG="$ROOT/alpha18-integration-build.log"
rm -f "$LOG"

set +e
sh "$ROOT/scripts/build-alpha18-inner.sh" >"$LOG" 2>&1
STATUS=$?
set -e

mkdir -p "$ROOT/dist"
cp -f "$LOG" "$ROOT/dist/alpha18-integration-build.log"
printf '%s\n' "$STATUS" > "$ROOT/dist/alpha18-integration-build.status"

if [ "$STATUS" -eq 0 ]; then
  echo "Alpha 18 integration build completed"
else
  echo "Alpha 18 integration build failed with status $STATUS; diagnostics will still be uploaded"
fi

# Keep the workflow alive so actions/upload-artifact can return the complete diagnostic log.
exit 0
