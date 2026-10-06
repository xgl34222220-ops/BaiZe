#!/usr/bin/env bash
set -euo pipefail
# adb can close its first connection while restarting adbd as root. Require the
# actual UID after reconnecting; never turn a failed Root prerequisite into a pass.
ready=0
for attempt in 1 2 3; do
  timeout 30 adb wait-for-device
  timeout 15 adb root || true
  timeout 30 adb wait-for-device
  if [ "$(adb shell id -u | tr -d '\r[:space:]')" = "0" ]; then ready=1; break; fi
  sleep 2
done
if [ "$ready" != 1 ]; then
  echo 'Disposable emulator did not establish uid 0; App checks have not run' >&2
  exit 1
fi
python3 .github/scripts/smoke-workbench-apk.py candidate/*.apk 30011 baseline-30006/*.apk baseline-30007/*.apk baseline-30008/*.apk baseline-30009/*.apk baseline-30010/*.apk
