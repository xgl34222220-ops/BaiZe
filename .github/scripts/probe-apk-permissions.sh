#!/usr/bin/env bash
set -euo pipefail
APP=io.github.xgl34222220.baize
OUT="${RUNNER_TEMP}/baize-apk-permission-probe"
NAMESPACE="baize-apk-permission-probe-${GITHUB_RUN_ID}"
FIXTURE="/storage/emulated/0/Download/$NAMESPACE/fixture.apk"
mkdir -p "$OUT"
adb root
adb wait-for-device
adb install -r v2/app/build/outputs/apk/debug/app-debug.apk
adb shell mkdir -p "/sdcard/Download/$NAMESPACE"
adb push v2/app/build/outputs/apk/debug/app-debug.apk "/sdcard/Download/$NAMESPACE/fixture.apk"
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$FIXTURE"
for _ in $(seq 1 20); do
  adb shell content query --uri content://media/external/file --projection _id:_data --where "_data='$FIXTURE'" > "$OUT/root-index.txt"
  if grep -q 'Row:' "$OUT/root-index.txt"; then break; fi
  sleep 1
done
grep -q 'Row:' "$OUT/root-index.txt"
adb shell "stat -c '%d:%i:%s:%Y:%Z' '$FIXTURE' '/data/media/0/Download/$NAMESPACE/fixture.apk'" > "$OUT/root-stat.txt"
adb shell am force-stop "$APP"
adb shell appops set "$APP" MANAGE_EXTERNAL_STORAGE deny
stage() {
  local phase=$1
  adb shell am start -W -n "$APP/.ApkPermissionDeviceProbeActivity" --es phase "$phase" --es path "$FIXTURE"
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
adb logcat -d > "$OUT/logcat.txt"
python3 - "$OUT" <<'PY'
import json, pathlib, sys
out=pathlib.Path(sys.argv[1]); stages={p.stem:json.loads(p.read_text()) for p in out.glob('*.json')}
for name in ('denied','granted','revoked','restored','restarted'):
    assert stages[name].get('completed') and stages[name]['uid'] >= 10000 and stages[name]['api'] == 36, stages[name]
print(json.dumps(stages,ensure_ascii=False,indent=2))
(out/'summary.json').write_text(json.dumps(stages,ensure_ascii=False,indent=2))
PY
# The only external-storage fixture is this run's newly-created repository APK copy.
adb shell rm -f "$FIXTURE"
adb shell rmdir "/sdcard/Download/$NAMESPACE"
