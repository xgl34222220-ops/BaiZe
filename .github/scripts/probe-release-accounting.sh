#!/usr/bin/env bash
set -euo pipefail
APK=$1
FIXTURES=$2
OUT="$RUNNER_TEMP/baize-release-accounting-device"
mkdir -p "$OUT"
adb root
adb wait-for-device
for kind in selected retained; do
  adb install "$FIXTURES/$kind.apk"
  adb shell "run-as test.baize.cache.$kind sh -c 'mkdir -p cache files; head -c 16384 /dev/zero > cache/owned.bin; printf BAIZE_CI_PERSISTENT_DATA > files/keep.txt'"
done
adb push "$APK" /data/local/tmp/baize-release-probe.apk
set +e
adb shell 'CLASSPATH=/data/local/tmp/baize-release-probe.apk app_process / io.github.xgl34222220.baize.root.ReleaseAccountingDeviceProbe' > "$OUT/result.log" 2>&1
code=$?
set -e
adb logcat -d > "$OUT/logcat.txt"
python3 - "$OUT/result.log" "$OUT/passed.json" "$code" <<'PY'
import json, pathlib, sys
records=[]
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    try: records.append(json.loads(line))
    except ValueError: pass
result=next((x for x in reversed(records) if isinstance(x,dict) and 'passed' in x),None)
assert sys.argv[3]=='0' and result and result['passed'], result or records
assert result['measuredDeletedBytes']==4096 and result['uid']==0 and result['androidApi']==36, result
for flag in ('actualSystemCacheRequest','systemRequestAmountUnknown','noDoubleCounting','zeroSeparateFromUnknown',
             'retainedContentPreserved','applicationDataPreserved','unselectedCachePreserved','unindexedDirectoryContentCounted'):
    assert result[flag] is True, (flag,result)
pathlib.Path(sys.argv[2]).write_text(json.dumps(result,ensure_ascii=False,indent=2))
PY
