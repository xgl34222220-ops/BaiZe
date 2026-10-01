#!/usr/bin/env bash
set -euo pipefail
APP=io.github.xgl34222220.baize
APK=${1:-v2/app/build/outputs/apk/debug/app-debug.apk}
ROOT_PROBE="/data/local/tmp/baize-permission-root-evidence-${GITHUB_RUN_ID}.apk"
OUT="${RUNNER_TEMP}/baize-apk-permission-probe"
NAMESPACE="baize-apk-permission-probe-${GITHUB_RUN_ID}"
FIXTURE="/storage/emulated/0/Download/$NAMESPACE/fixture.apk"
mkdir -p "$OUT"
adb root
adb wait-for-device
adb install -r "$APK"
adb push "$APK" "$ROOT_PROBE"
adb shell mkdir -p "/sdcard/Download/$NAMESPACE"
adb push "$APK" "/sdcard/Download/$NAMESPACE/fixture.apk"
adb shell timeout 20 /system/bin/content call --user 0 --uri content://media --method scan_file --arg "$FIXTURE" > "$OUT/initial-refresh.txt" 2>&1
adb logcat -d > "$OUT/setup-logcat.txt"
for _ in $(seq 1 20); do
  adb shell "content query --uri content://media/external/file --projection _id:_data --where \"_data='$FIXTURE'\"" > "$OUT/root-index.txt"
  if grep -q 'Row:' "$OUT/root-index.txt"; then break; fi
  sleep 1
done
grep -q 'Row:' "$OUT/root-index.txt"
adb shell "stat -c '%d:%i:%s:%Y:%Z' '$FIXTURE' '/data/media/0/Download/$NAMESPACE/fixture.apk'" > "$OUT/root-stat.txt"
adb shell am force-stop "$APP"
adb shell appops set "$APP" MANAGE_EXTERNAL_STORAGE deny
stage() {
  local phase=$1
  local root_evidence=""
  if [ "$phase" = stale ] || [ "$phase" = raw_refreshed ]; then
    adb shell "CLASSPATH='$ROOT_PROBE' app_process / io.github.xgl34222220.baize.root.ApkMissingIndexDeviceEvidence '$FIXTURE'" > "$OUT/$phase-root-evidence.log"
    root_evidence=$(python3 - "$OUT/$phase-root-evidence.log" <<'PYROOT'
import base64,json,pathlib,sys
records=[]
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    try: records.append(json.loads(line))
    except ValueError: pass
record=next(x for x in reversed(records) if x.get('evidenceVersion')==1)
print(base64.b64encode(json.dumps(record).encode()).decode())
PYROOT
)
  fi
  local extra=()
  if [ -n "$root_evidence" ]; then extra=(--es root_evidence "$root_evidence"); fi
  adb shell am start -W -n "$APP/.ApkPermissionDeviceProbeActivity" --es phase "$phase" --es path "$FIXTURE" "${extra[@]}"
  for _ in $(seq 1 30); do
    if adb shell test -f "/data/user/0/$APP/files/apk-permission-probe/$phase.json"; then break; fi
    sleep 1
  done
  adb shell cat "/data/user/0/$APP/files/apk-permission-probe/$phase.json" > "$OUT/$phase.json"
}
stage denied
adb shell appops set "$APP" MANAGE_EXTERNAL_STORAGE allow
stage granted
adb shell appops set "$APP" MANAGE_EXTERNAL_STORAGE deny
stage revoked
adb shell appops set "$APP" MANAGE_EXTERNAL_STORAGE allow
stage restored
adb shell am force-stop "$APP"
stage restarted
# Delete only the run-owned backing file, leaving its earlier MediaStore record for inspection.
adb shell rm -f "/data/media/0/Download/$NAMESPACE/fixture.apk"
stage stale
# The same command arguments used by RootMediaScanCommand, for this single run-owned file.
# First observe the raw path currently emitted by Root cleanup; then the public MediaStore path.
if adb shell timeout 15 /system/bin/content call --user 0 --uri content://media --method scan_file --arg "/data/media/0/Download/$NAMESPACE/fixture.apk" > "$OUT/raw-refresh.txt" 2>&1; then
  echo 0 > "$OUT/raw-refresh-exit.txt"
else
  echo "$?" > "$OUT/raw-refresh-exit.txt"
fi
stage raw_refreshed
if adb shell timeout 15 /system/bin/content call --user 0 --uri content://media --method scan_file --arg "$FIXTURE" > "$OUT/public-refresh.txt" 2>&1; then
  echo 0 > "$OUT/public-refresh-exit.txt"
else
  echo "$?" > "$OUT/public-refresh-exit.txt"
fi
stage public_refreshed
adb logcat -d > "$OUT/logcat.txt"
python3 - "$OUT" <<'PY'
import json, pathlib, sys
out=pathlib.Path(sys.argv[1]); stages={p.stem:json.loads(p.read_text()) for p in out.glob('*.json')}
for name in ('denied','granted','revoked','restored','restarted','stale','raw_refreshed','public_refreshed'):
    assert stages[name].get('completed') and stages[name]['uid'] >= 10000 and stages[name]['api'] == 36, stages[name]
for name in ('stale', 'raw_refreshed'):
    assert stages[name]['reviewConfirmedMissing'] == 1 and stages[name]['reviewCandidateCount'] == 0, stages[name]
    assert stages[name]['indexRowsAfterReview'] == 1, stages[name]
assert stages['public_refreshed']['indexRows'] == 0, stages['public_refreshed']
print(json.dumps(stages,ensure_ascii=False,indent=2))
(out/'summary.json').write_text(json.dumps(stages,ensure_ascii=False,indent=2))
PY
# The only external-storage fixture is this run's newly-created repository APK copy.
adb shell rm -f "$FIXTURE"
adb shell rmdir "/sdcard/Download/$NAMESPACE"
adb shell rm -f "$ROOT_PROBE"
