#!/usr/bin/env bash
set -euo pipefail
OUT="${RUNNER_TEMP:-/tmp}/baize-apk-deletion-probe"
APP=io.github.xgl34222220.baize
mkdir -p "$OUT"
adb shell am force-stop "$APP"
adb shell appops set "$APP" MANAGE_EXTERNAL_STORAGE allow
adb shell rm -f "/data/user/0/$APP/files/apk-deletion-probe/result.json"
adb shell am start -W -n "$APP/.ApkDeletionDeviceProbeActivity"
for _ in $(seq 1 55); do
  if adb shell test -f "/data/user/0/$APP/files/apk-deletion-probe/result.json"; then break; fi
  sleep 1
done
adb shell cat "/data/user/0/$APP/files/apk-deletion-probe/result.json" > "$OUT/result.json"
cat "$OUT/result.json"
python3 - "$OUT/result.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
assert r.get('passed') is True and r.get('uid', 0) >= 10000, r
for key in ('physicalIdentityCaptured', 'conditionalMediaStoreDelete', 'unknownProtectionPreserved',
            'freshPathProtectionPreserved', 'replacedFilePreserved', 'unselectedPreserved'):
    assert r.get(key) is True, (key, r)
PY
