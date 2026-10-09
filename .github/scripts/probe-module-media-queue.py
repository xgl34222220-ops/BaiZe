#!/usr/bin/env python3
"""Verify signed module app_process refresh on owned API36 emulator fixtures.
Usage: probe-module-media-queue.py paired-module.zip
The installed App stays force-stopped; organizer queues are never accessed.
"""
import json, os, pathlib, shlex, subprocess, sys, tempfile, uuid, zipfile
out = pathlib.Path(os.environ.get('RUNNER_TEMP', '/tmp')) / 'baize-module-media-probe'
out.mkdir(parents=True, exist_ok=True)
tag = uuid.uuid4().hex
remote = '/data/local/tmp/baize-module-media-' + tag
module, state = remote + '/module', remote + '/state'
user = None
fixture = None
result = {'passed': False, 'fixture': tag}
def q(value): return shlex.quote(str(value))
def shell(command, timeout=45):
    p = subprocess.run(['adb', 'shell', command], capture_output=True, text=True, timeout=timeout)
    with (out / 'commands.log').open('a') as log: log.write(f'code={p.returncode}\n{p.stdout}{p.stderr}\n')
    assert p.returncode == 0, (p.returncode, p.stdout, p.stderr)
    return p.stdout.strip()
def query(path):
    selection = "_data='" + path.replace("'", "''") + "'"
    return shell(f'content query --user {user} --uri content://media/external/file --projection _id --where {q(selection)}')
try:
    assert shell('id -u') == '0', 'This probe requires the disposable emulator adb-root shell'
    user = shell('cmd activity get-current-user')
    assert user.isdecimal(), user
    fixture = f'/storage/emulated/{user}/Download/baize-module-media-{tag}'
    path = fixture + "/quote ' and newline\nowned.apk"
    raw_path = path.replace("/storage/emulated/", "/data/media/", 1)
    with tempfile.TemporaryDirectory(prefix='baize-module-media-') as local:
        with zipfile.ZipFile(sys.argv[1]) as archive: archive.extractall(local)
        shell('mkdir -p ' + q(remote))
        subprocess.run(['adb', 'push', local + '/.', module + '/'], check=True, stdout=subprocess.DEVNULL)
    abi = shell('getprop ro.product.cpu.abi')
    assert abi in ('x86_64', 'arm64-v8a', 'armeabi-v7a', 'x86'), abi
    engine = module + '/bin/' + abi + '/baize_engine'
    shell(f'chmod 755 {q(module)}/scripts/*.sh {q(engine)}; mkdir -p {q(state)} {q(fixture)}; cp {q(module + "/app/baize.apk")} {q(path)}')
    scan = shell(f'content call --user {user} --uri content://media --method scan_file --arg {q(path)}')
    assert 'Result: Bundle[' in scan and 'android.intent.extra.STREAM=' in scan, scan
    before = query(path)
    assert 'Row: ' in before and '_id=' in before, ('fixture not indexed', before)
    shell('am force-stop io.github.xgl34222220.baize')
    # The real native consumer receives the ORIGINAL toybox stat identity. This
    # shell only publishes its successful NUL stream after the helper returns.
    run = '\n'.join([
        'set -eu', f'SCRIPTDIR={q(module + "/scripts")}', f'BAIZE_ROOT_STATE_DIR={q(state)}',
        '. "$SCRIPTDIR/cleanup-media-queue.sh"', 'baize_cleanup_media_init', 'baize_cleanup_media_begin',
        f'identity=$(stat -c {q("%d:%i:%s:%y:%z")} {q(raw_path)})',
        f'printf "%s\\0" {q(raw_path)} >{q(state + "/targets.nul")}',
        f'printf "%s\\0" "$identity" >{q(state + "/identity.nul")}',
        f'{q(engine)} hash-apk-snapshot {q(state + "/targets.nul")} {q(state + "/identity.nul")} {q(state + "/hashed.nul")} {q(state + "/stop")} 4294967296',
        f'IFS= read -r -d "" identity <{q(state + "/hashed.nul")}',
        f'{q(engine)} unlink-apk-snapshot-item {q(raw_path)} "$identity" "$BAIZE_CLEANUP_DELETED_NUL"',
        'baize_cleanup_media_publish', f'test ! -e {q(raw_path)}'
    ])
    shell(run)
    after_delete = query(path)
    # MediaProvider may refresh raw deletions automatically on some runs. Keep
    # that observation separate from release-main and exact-query ACK evidence.
    shell(f'BAIZE_MODULE_DIR={q(module)} BAIZE_ROOT_STATE_DIR={q(state)} sh {q(module + "/scripts/cleanup-media-worker.sh")}', timeout=40)
    after_refresh = query(path)
    assert after_refresh == 'No result found.', after_refresh
    ack = shell(f'find {q(state + "/cleanup-media")} -name progress.log -exec cat {{}} \\;')
    assert 'A 0 VERIFIED_ABSENT' in ack.splitlines(), ack
    dirs = shell(f'find {q(state + "/cleanup-media")} -maxdepth 2 -type d')
    assert '/done-' in dirs and '/inflight-' not in dirs and '/pending-' not in dirs, dirs
    result.update(passed=True, api=int(shell('getprop ro.build.version.sdk')), user=int(user),
        signedPackagedMainExecuted=True, appForceStopped=True, realNativeOriginalIdentityDelete=True, nativeSha256Snapshot=True,
        publicPathSuffixPreserved=True, exactSameUserQueryVerified=True, persistedQueueAcknowledged=True,
        staleRowObservedAfterDelete='Row: ' in after_delete, before=before,
        afterDelete=after_delete, afterRefresh=after_refresh)
except Exception as error:
    result['error'] = str(error)
finally:
    # Only this UUID fixture is removed; no MediaStore delete URI is ever called.
    if fixture is not None:
        subprocess.run(['adb', 'shell', 'rm -rf -- ' + q(fixture)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(['adb', 'shell', 'rm -rf -- ' + q(remote)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    (out / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False))
if not result['passed']: raise SystemExit(1)
