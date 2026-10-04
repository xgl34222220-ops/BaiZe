#!/usr/bin/env bash
set -euo pipefail
APP=io.github.xgl34222220.baize
OUT="${RUNNER_TEMP:-/tmp}/baize-storage-collision-probe"
mkdir -p "$OUT"
adb root
adb wait-for-device
test "$(adb shell id -u | tr -d '\r')" = 0
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 36
adb shell getprop ro.build.fingerprint | rg 'generic|sdk'
adb install "$1"
adb shell appops set "$APP" MANAGE_EXTERNAL_STORAGE allow
adb shell am start -W -n "$APP/.StorageCollisionDeviceProbeActivity"
for _ in $(seq 1 90); do
  if adb shell test -f "/data/user/0/$APP/files/storage-collision-probe/result.json"; then break; fi
  sleep 1
done
adb pull "/data/user/0/$APP/files/storage-collision-probe/." "$OUT/"
adb logcat -d -v threadtime > "$OUT/logcat.txt"
cat "$OUT/result.json"
python3 - "$OUT/result.json" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]))
assert r.get('passed') is True and r.get('uid',0)>=10000, r
for key in ('sharedMtimeMillisCollisionObserved','mediaStoreSecondsCollisionObserved',
            'freshDuplicateScanRejectsChangedContent','staleReviewProofRejected',
            'forcedIdentityCollisionRejectedByContentHash','ambiguousLegacyCapacityNotDoubleCounted',
            'noDeletionPerformed','bothSharedFixturesPreserved'):
    assert r.get(key) is True, (key,r)
assert r['sameSecondLegacyAuditOccurrences']==2
# Full nanosecond stat equality is observed separately; never replace false with a mocked true.
assert isinstance(r['fullFilesystemIdentityCollisionObserved'],bool)
PY
