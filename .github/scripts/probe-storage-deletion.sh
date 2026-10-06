#!/usr/bin/env bash
set -euo pipefail
OUT="${RUNNER_TEMP:-/tmp}/baize-storage-deletion-probe"
APP=io.github.xgl34222220.baize
mkdir -p "$OUT"
adb shell am force-stop "$APP"
adb shell appops set "$APP" MANAGE_EXTERNAL_STORAGE allow
adb shell rm -f "/data/user/0/$APP/files/storage-deletion-probe/result.json"
adb shell am start -W -n "$APP/.StorageDeletionDeviceProbeActivity"
for _ in $(seq 1 90); do
  if adb shell test -f "/data/user/0/$APP/files/storage-deletion-probe/result.json"; then break; fi
  sleep 1
done
adb shell cat "/data/user/0/$APP/files/storage-deletion-probe/result.json" > "$OUT/result.json"
adb shell cat "/data/user/0/$APP/files/storage-deletion-probe/stale-zip-diagnostic.json" > "$OUT/stale-zip-diagnostic.json" || true
adb pull "/data/user/0/$APP/files/storage-deletion-probe/." "$OUT/" >/dev/null || true
adb logcat -d -v threadtime > "$OUT/logcat.txt" || true
cat "$OUT/result.json"
python3 - "$OUT/result.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
assert r.get('passed') is True and r.get('uid', 0) >= 10000, r
assert set(r['categoriesActuallyDeletedAndIndexRemoved']) == {'archive','document','image','video','audio','apk','other'}, r
for key in ('changedSameSizeAndMtimePreserved','unknownProtectionAndProtectedFilePreserved',
            'unselectedPreserved','staleIndexNotSelectableOrCounted','duplicateContentRecheckedAndOneCopyPreserved',
            'ordinaryTrashDurableMoveAndConflictRestoreVerified','ordinaryTrashParentChangesPreservePayload'):
    assert r.get(key) is True, (key, r)
PY
