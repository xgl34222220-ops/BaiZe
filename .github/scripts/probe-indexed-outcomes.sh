#!/usr/bin/env bash
set -uo pipefail
APK=v2/app/build/outputs/apk/debug/app-debug.apk
adb root
adb wait-for-device
adb install "$APK" || exit 1
status=0
bash .github/scripts/probe-root-cache.sh "$APK" || status=1
for iteration in $(seq 1 12); do
  output="${RUNNER_TEMP:-/tmp}/baize-indexed-repeated/$iteration"
  mkdir -p "$output"
  RUNNER_TEMP="$output" bash .github/scripts/probe-indexed-cleanup.sh || status=1
done
bash .github/scripts/probe-apk-permissions.sh "$APK" || status=1
exit "$status"
