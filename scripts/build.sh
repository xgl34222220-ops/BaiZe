#!/usr/bin/env sh
set -u

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
LOG="$ROOT/alpha18-source-finalize.log"
rm -f "$LOG"

set +e
sh "$ROOT/scripts/finalize-alpha18-source.sh" >"$LOG" 2>&1
STATUS=$?
set -e

mkdir -p "$ROOT/dist"
cp -f "$LOG" "$ROOT/dist/alpha18-source-finalize.log"
printf '%s\n' "$STATUS" > "$ROOT/dist/alpha18-source-finalize.status"

if [ "$STATUS" -eq 0 ]; then
  echo "Alpha 18 source finalization completed"
else
  echo "Alpha 18 source finalization failed with status $STATUS"
fi

exit 0
