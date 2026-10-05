#!/usr/bin/env bash
# Disposable AOSP emulator only. All mutated files are synthetic app-cache fixtures.
set -euo pipefail
OUT="${BAIZE_TRASH_EVIDENCE_DIR:-evidence/trash-batch}"
APP=io.github.xgl34222220.baize
mkdir -p "$OUT"

# Never pick a physical device, including one implicitly selected by adb.
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  SERIAL="$ANDROID_SERIAL"
else
  mapfile -t EMULATORS < <(adb devices | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')
  [[ "${#EMULATORS[@]}" == 1 ]] || { echo 'Expected exactly one disposable emulator' >&2; exit 1; }
  SERIAL="${EMULATORS[0]}"
fi
[[ "$SERIAL" =~ ^emulator-[0-9]+$ ]] || { echo 'Refusing a non-emulator adb target' >&2; exit 1; }
ADB=(adb -s "$SERIAL")
[[ "$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Target is not QEMU' >&2; exit 1; }
HARDWARE=$("${ADB[@]}" shell getprop ro.hardware | tr -d '\r')
[[ "$HARDWARE" == ranchu || "$HARDWARE" == goldfish ]] || { echo 'Target is not a supported disposable AOSP emulator' >&2; exit 1; }

if [[ $# -gt 0 ]]; then
  APK="$1"
else
  mapfile -t APKS < <(find verification -type f -name app-debug.apk)
  [[ "${#APKS[@]}" == 1 ]] || { echo 'Expected one verified debug APK' >&2; exit 1; }
  APK="${APKS[0]}"
fi
[[ -f "$APK" ]] || { echo 'Debug APK not found' >&2; exit 1; }
sha256sum "$APK" > "$OUT/apk-sha256.txt"
"${ADB[@]}" shell getprop > "$OUT/emulator-properties.txt"
# Root is only the CI transport for launching a non-exported activity. The probe
# checks that its own process retains the ordinary application UID.
"${ADB[@]}" root
"${ADB[@]}" wait-for-device
[[ "$("${ADB[@]}" shell id -u | tr -d '\r')" == 0 ]] || { echo 'AOSP debug adbd root is required' >&2; exit 1; }
"${ADB[@]}" install -r "$APK"
"${ADB[@]}" shell dumpsys package "$APP" > "$OUT/package.txt"
grep -q DEBUGGABLE "$OUT/package.txt" || { echo 'Installed application is not debuggable' >&2; exit 1; }

TOKEN=$(python3 -c 'import secrets; print(secrets.token_hex(16))')
RESULT="/data/user/0/$APP/cache/trash-batch-probe/result-$TOKEN.json"
trap '"${ADB[@]}" logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null || true' EXIT
"${ADB[@]}" shell am force-stop "$APP"
"${ADB[@]}" shell am start -W -n "$APP/.TrashBatchDeviceProbeActivity" --es probe_token "$TOKEN" | tee "$OUT/launch.txt"
FOUND=false
for _ in $(seq 1 120); do
  if "${ADB[@]}" shell test -f "$RESULT"; then FOUND=true; break; fi
  sleep 1
done
[[ "$FOUND" == true ]] || { echo 'Trash batch probe did not produce a result' >&2; exit 1; }
"${ADB[@]}" shell cat "$RESULT" > "$OUT/result.json"
cat "$OUT/result.json"
python3 - "$OUT/result.json" "$TOKEN" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as source:
    r = json.load(source)
assert r.get('passed') is True, r
assert r.get('token') == sys.argv[2], r
assert r.get('uid', 0) >= 10000, r
assert r.get('debug') is True and r.get('emulatorOnly') is True, r
for key in ('fixtureCacheOnly', 'reviewedSnapshotPurge', 'reviewedSnapshotRestore',
            'newArrivalExcluded', 'changedPayloadRejected', 'changedJournalRejected',
            'restoreConflictPreserved', 'cancelRemainingPreserved', 'mixedResultsAccounted',
            'fixtureCleanupComplete'):
    assert r.get(key) is True, (key, r)
assert r.get('batchHelper') == 'production', r
print('Verified disposable-emulator trash batch evidence.')
PY
