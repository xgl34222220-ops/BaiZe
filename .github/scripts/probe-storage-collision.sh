#!/usr/bin/env bash
set -euo pipefail
APP=io.github.xgl34222220.baize
OUT="${RUNNER_TEMP:-/tmp}/baize-storage-collision-probe"
mkdir -p "$OUT"
trap 'timeout 20s adb logcat -d -v threadtime > "$OUT/logcat.txt" || true' EXIT
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
case "$api" in 26|36) ;; *) echo 'Only acceptance API 26/36 is allowed' >&2; exit 1 ;; esac
fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')
case "$fingerprint" in
  *generic*|*sdk*) printf '%s\n' "$fingerprint" ;;
  *) echo 'Only a disposable emulator is allowed' >&2; exit 1 ;;
esac
# Check the disposable AVD before changing adbd. A just-booted emulator may close
# the first root connection; bounded retries do not restart or wipe the AVD.
for attempt in $(seq 1 5); do
  if [ "$(adb shell id -u 2>/dev/null | tr -d '\r')" = 0 ]; then
    break
  fi
  timeout 20s adb root >> "$OUT/adbd-startup.txt" 2>&1 || true
  sleep 2
done
test "$(adb shell id -u | tr -d '\r')" = 0
sha256sum "$1" > "$OUT/debug-apk-sha256.txt"
if [ -n "${BAIZE_EXPECTED_DEBUG_SHA:-}" ]; then
  test "$(cut -d ' ' -f 1 "$OUT/debug-apk-sha256.txt")" = "$BAIZE_EXPECTED_DEBUG_SHA"
fi
adb install "$1"
if [ "$api" = 26 ]; then
  adb shell pm grant "$APP" android.permission.READ_EXTERNAL_STORAGE
  adb shell pm grant "$APP" android.permission.WRITE_EXTERNAL_STORAGE
else
  adb shell appops set "$APP" MANAGE_EXTERNAL_STORAGE allow
fi
# This headless debug probe has no first frame to await on Android 8.0.
# Its result file, not ActivityManager's draw waiter, is the completion barrier.
timeout 30s adb shell am start -n "$APP/.StorageCollisionDeviceProbeActivity" > "$OUT/launch.txt" 2>&1
cat "$OUT/launch.txt"
python3 - "$APP" "$OUT" <<'PY'
import json,re,stat,subprocess,sys,time
from pathlib import Path
app,out=sys.argv[1],Path(sys.argv[2])
assert app=='io.github.xgl34222220.baize'
remote=f'/data/user/0/{app}/files/storage-collision-probe'
def adb(*args,check=True):
    return subprocess.run(['adb',*args],check=check,capture_output=True,text=True,timeout=15)
def exists(path):
    # Legacy adb shell may not preserve a predicate's exit code. Read an explicit
    # response instead of treating a transport success as filesystem evidence.
    return adb('shell',f'[ -f {path} ] && echo baize-fixture-file',check=False).stdout.strip()=='baize-fixture-file'
processed=set()
tool_help=adb('shell','touch','--help',check=False)
(out/'fixture-touch-help.json').write_text(json.dumps({'stdout':tool_help.stdout,
    'stderr':tool_help.stderr,'exitCode':tool_help.returncode},indent=2))
deadline=time.monotonic()+120
while time.monotonic()<deadline:
    for request in (1,2,3):
        name=f'timestamp-request-{request}.json'
        if request in processed or not exists(f'{remote}/{name}'): continue
        text=adb('shell','cat',f'{remote}/{name}').stdout
        r=json.loads(text)
        (out/name).write_text(text)
        # Never accept arbitrary paths, links, different sizes or different clocks.
        assert 10000<=r['appUid']<100000 and r['mtimeMillis']==1500000000000, r
        assert re.fullmatch(r'/storage/emulated/0/Download/baize-collision-owned-[0-9a-f-]{36}/(first|second)\.bin',r['path']), r
        mode=adb('shell','stat','-c','%f',r['path']).stdout.strip()
        (out/f'fixture-lstat-{request}.json').write_text(json.dumps({'path':r['path'],'modeHex':mode}))
        assert re.fullmatch(r'[0-9a-fA-F]+',mode) and stat.S_ISREG(int(mode,16)), (r,mode)
        assert adb('shell','stat','-c','%s',r['path']).stdout.strip()=='131072', r
        before_hash=adb('shell','sha256sum',r['path']).stdout.split()[0]
        assert re.fullmatch(r'[0-9a-f]{64}',before_hash), r
        journal={'path':r['path'],'mtimeBefore':adb('shell','stat','-c','%Y',r['path']).stdout.strip(),
            'contentSha256Before':before_hash,'attempts':[]}
        def set_clock(path):
            # Legacy Toybox accepts ISO dates, not GNU @seconds, and historical
            # -m selected the wrong timespec. Set both times only on this owned
            # fixture; never infer success from the tool's exit status.
            args=('touch','-c','-d','2017-07-14T02:40:00Z',path)
            attempt=adb('shell',*args,check=False)
            observed=adb('shell','stat','-c','%Y',r['path']).stdout.strip()
            setter_time=adb('shell','stat','-c','%X,%Y',path).stdout.strip()
            after_hash=adb('shell','sha256sum',r['path']).stdout.split()[0]
            journal['attempts'].append({'setterPath':path,'command':list(args),'stdout':attempt.stdout,
                'stderr':attempt.stderr,'exitCode':attempt.returncode,'sharedMtimeSeconds':observed,
                'setterAtimeAndMtimeSeconds':setter_time,'contentSha256After':after_hash})
            (out/f'fixture-clock-{request}.json').write_text(json.dumps(journal,indent=2))
            assert after_hash==before_hash, journal
            return observed=='1500000000'
        applied=set_clock(r['path'])
        if not applied:
            assert adb('shell','getprop','ro.build.version.sdk').stdout.strip()=='26', journal
            # Android's user-0 emulated storage maps to /data/media/0. Only this
            # generated UUID leaf can be touched, after checking regular type,
            # exact size and the same full content through both views.
            lower=r['path'].replace('/storage/emulated/0/','/data/media/0/',1)
            lower_mode=adb('shell','stat','-c','%f',lower).stdout.strip()
            assert re.fullmatch(r'[0-9a-fA-F]+',lower_mode) and stat.S_ISREG(int(lower_mode,16)), (r,lower_mode)
            assert adb('shell','stat','-c','%s',lower).stdout.strip()=='131072', r
            hashes=adb('shell','sha256sum',r['path'],lower).stdout.splitlines()
            assert len(hashes)==2 and hashes[0].split()[0]==hashes[1].split()[0] and re.fullmatch(r'[0-9a-f]{64}',hashes[0].split()[0]), hashes
            journal.update({'lowerFixturePath':lower,'lowerModeHex':lower_mode,'bothViewContentSha256':hashes[0].split()[0]})
            applied=set_clock(lower)
        assert applied, journal
        adb('shell',f'echo 1500000000000 > {remote}/timestamp-ack-{request}.txt')
        processed.add(request)
    if exists(f'{remote}/result.json'): break
    time.sleep(.25)
else: raise AssertionError('Probe did not produce a result within 120 seconds')
# adb pull recursively follows directory symlinks. Export only fixed result files;
# leave scanner boundary fixtures, outside links and loops on the AVD unchanged.
for name in ('result.json','shared-observations.json','audit-observed.json'):
    if exists(f'{remote}/{name}'):
        adb('pull',f'{remote}/{name}',str(out/name))
PY
adb logcat -d -v threadtime > "$OUT/logcat.txt"
cat "$OUT/result.json"
python3 - "$OUT/result.json" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]))
assert r.get('passed') is True and r.get('uid',0)>=10000, r
assert r['versionCode']==30024 and len(r['mtimeSetters'])==3, r
assert all(s in ('app','guarded-ci-root-fixture-controller') for s in r['mtimeSetters']), r
for key in ('sharedMtimeMillisCollisionObserved','mediaStoreSecondsCollisionObserved',
            'freshDuplicateScanRejectsChangedContent','staleReviewProofRejected',
            'forcedIdentityCollisionRejectedByContentHash','ambiguousLegacyCapacityNotDoubleCounted',
            'noDeletionPerformed','bothSharedFixturesPreserved'):
    assert r.get(key) is True, (key,r)
assert r['sameSecondLegacyAuditOccurrences']==2
# Full nanosecond stat equality is observed separately; never replace false with a mocked true.
assert isinstance(r['fullFilesystemIdentityCollisionObserved'],bool)
for key in ('sameSizeContentMutationObserved','staleDuplicateGroupRefused'):
    assert r.get(key) is True, (key,r)
p=r['privateReadOnlyBoundaries']
assert p['appUid']>=10000 and p['realReadErrno']==13, p
for key in ('realReadFailureRejectsContentProof','outsideLinksAndLoopsNotTraversed',
            'entryLimitReportedIncomplete','missingRootReportedUnavailable',
            'cancellationPropagated','allFixtureContentsPreserved','linksPreserved'):
    assert p.get(key) is True, (key,p)
aliases=r['sharedAliasChecks']
assert isinstance(aliases['realSharedAliasObserved'],bool), aliases
if aliases['realSharedAliasObserved']:
    assert aliases['aliasScanRejectsDuplicate'] is True and aliases['aliasCannotAuthorizeSurvivor'] is True, aliases
else:
    assert aliases['aliasScanRejectsDuplicate'] is None and aliases['unverifiedReason'], aliases
links=p['hardLinkChecks']
assert isinstance(links['realHardlinkObserved'],bool), links
if links['realHardlinkObserved']:
    assert links['hardlinkScanRejectsDuplicate'] is True and links['bothLinkContentsPreserved'] is True, links
else:
    assert links['hardlinkScanRejectsDuplicate'] is None and links['unverifiedReason'], links
if r['api']==26:
    assert r['nanosecondStatFieldsAvailable'] is False and r['fullFilesystemIdentityCollisionObserved'] is False
PY
