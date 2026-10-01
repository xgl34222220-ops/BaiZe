#!/usr/bin/env bash
set -euo pipefail
APK=$1
OUT="${RUNNER_TEMP:-/tmp}/baize-root-cache-probe"
mkdir -p "$OUT"
rm -f "$OUT/passed.json" "$OUT/result.json" "$OUT/diagnostics.txt"
adb root
adb wait-for-device
adb push "$APK" /data/local/tmp/baize-root-cache-probe.apk
adb logcat -c
set +e
adb shell 'CLASSPATH=/data/local/tmp/baize-root-cache-probe.apk app_process / io.github.xgl34222220.baize.root.CacheRootDeviceProbe' >"$OUT/result.log" 2>&1
code=$?
set -e
adb logcat -b all -d >"$OUT/logcat.txt" 2>&1 || true
if [ "$code" -ne 0 ]; then
  adb shell 'id; getprop ro.build.fingerprint; getprop ro.product.model; ps -A; ls -l /data/adb/baize-v2/operations.lock; cat /proc/locks' >"$OUT/diagnostics.txt" 2>&1 || true
fi
adb shell rm /data/local/tmp/baize-root-cache-probe.apk || true
cat "$OUT/result.log"
python3 - "$OUT/result.log" "$OUT/passed.json" "$code" <<'PY'
import json, pathlib, sys
records = []
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    try: records.append(json.loads(line))
    except ValueError: pass
result = next((r for r in reversed(records) if isinstance(r, dict) and isinstance(r.get('passed'), bool)), None)
pathlib.Path(sys.argv[2]).with_name('result.json').write_text(json.dumps(result or {'passed': False, 'exitCode': int(sys.argv[3]), 'records': records}, ensure_ascii=False, indent=2))
assert sys.argv[3] == '0', f"Root probe exited {sys.argv[3]}: {result or records}"
assert result and result['passed'] and result['uid'] == 0 and result['deletedBytes'] == 12288, records
for field in ('scanThenSelectedClean', 'fdTransport', 'localBinderDescriptorCopies', 'unselectedPreserved',
              'unknownPathRejected', 'serviceRecreationSelectedClean', 'lowThenHighSameSnapshot', 'apkProtectionFdSnapshot',
              'corpseUnknownInventoryPreserved', 'corpseCrossUserRejected', 'corpseEmptyInventoryRejected', 'corpseReviewRetained',
              'emptyBoundarySelectedClean', 'emptyDirectoryCountSeparateFromFiles', 'emptyChangedContentPreserved',
              'emptyUnreviewedParentsPreserved', 'emptyPlaceholderAndSymlinkPreserved', 'emptyParentsRequireNewPreview'):
    assert result.get(field) is True, (field, result)
assert result.get('crossUidBinderValidated') is False and result.get('profileDeletedBytes') == 384, result
pathlib.Path(sys.argv[2]).write_text(json.dumps(result, ensure_ascii=False, indent=2))
PY
