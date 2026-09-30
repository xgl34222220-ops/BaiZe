#!/usr/bin/env bash
set -euo pipefail
APK=$1
OUT="${RUNNER_TEMP:-/tmp}/baize-root-cache-probe"
mkdir -p "$OUT"
adb root
adb wait-for-device
adb push "$APK" /data/local/tmp/baize-root-cache-probe.apk
adb logcat -c
set +e
adb shell 'CLASSPATH=/data/local/tmp/baize-root-cache-probe.apk app_process / io.github.xgl34222220.baize.root.CacheRootDeviceProbe' >"$OUT/result.log" 2>&1
code=$?
set -e
adb logcat -d >"$OUT/logcat.txt"
adb shell rm /data/local/tmp/baize-root-cache-probe.apk
cat "$OUT/result.log"
test "$code" = 0
python3 - "$OUT/result.log" "$OUT/passed.json" <<'PY'
import json, pathlib, sys
records = []
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    try: records.append(json.loads(line))
    except ValueError: pass
result = next((r for r in records if r.get('scanThenSelectedClean') is True), None)
assert result and result['passed'] and result['uid'] == 0 and result['deletedBytes'] == 12288, records
pathlib.Path(sys.argv[2]).write_text(json.dumps(result, ensure_ascii=False, indent=2))
PY
