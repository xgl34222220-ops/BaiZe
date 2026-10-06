#!/usr/bin/env bash
set -euo pipefail
OUT="${RUNNER_TEMP:-/tmp}/baize-apk-artwork-probe"
APP=io.github.xgl34222220.baize
mkdir -p "$OUT"
rm -f "$OUT/result.json" "$OUT/archive-icon.png"
adb shell am force-stop "$APP"
DEVICE="/data/user/0/$APP/files/apk-archive-probe"
adb shell "mkdir -p '$DEVICE/fixtures'"
adb push "${RUNNER_TEMP:-/tmp}/baize-apk-fixtures/first.apk" "$DEVICE/fixtures/first.apk"
adb push "${RUNNER_TEMP:-/tmp}/baize-apk-fixtures/second.apk" "$DEVICE/fixtures/second.apk"
adb shell "uid=\$(stat -c %u /data/user/0/$APP); chown \$uid:\$uid /data/user/0/$APP/files; chown -R \$uid:\$uid '$DEVICE'; restorecon -R '$DEVICE'"
# The disposable emulator has just installed this repository's debug APK.
adb shell rm -f "/data/user/0/$APP/files/apk-archive-probe/result.json"
adb shell am start -W -n "$APP/.ApkArchiveDeviceProbeActivity"
for _ in $(seq 1 45); do
  if adb shell test -f "/data/user/0/$APP/files/apk-archive-probe/result.json"; then break; fi
  sleep 1
done
adb shell cat "/data/user/0/$APP/files/apk-archive-probe/result.json" > "$OUT/result.json"
adb pull "/data/user/0/$APP/files/apk-archive-probe/archive-icon.png" "$OUT/" || true
adb pull "$DEVICE/first-icon.png" "$OUT/" || true
adb pull "$DEVICE/second-icon.png" "$OUT/" || true
cat "$OUT/result.json"
python3 - "$OUT/result.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
assert r.get('passed') is True and r.get('uid', 0) >= 10000, r
assert all(r.get(k) is True for k in ('packageMatches', 'versionMatches', 'archiveIconDecoded', 'repeatReadPassed',
    'repeatIconPixelsEqual', 'privateAliasesReleased', 'installedResourcesPrewarmed',
    'samePackageSameResourceIdsDistinctArchives', 'archiveSpecificLabelsVersionsAndPixels')), r
assert r.get('archiveLabel') and 0 < r.get('iconWidth', 0) <= 192 and 0 < r.get('iconHeight', 0) <= 192, r
PY
