#!/usr/bin/env python3
"""Exercise the actual signed module helper and shell on an isolated Android emulator."""
import json,os,pathlib,shlex,subprocess,sys,tempfile,uuid,zipfile
out=pathlib.Path(os.environ.get('RUNNER_TEMP','/tmp'))/'baize-module-corpse-probe';out.mkdir(parents=True,exist_ok=True)
tag=uuid.uuid4().hex
remote='/data/local/tmp/baize-module-corpse-'+tag
state=remote+'/state';module=remote+'/module';target='/data/media/0/Android/data/io.baize.synthetic'+tag
result={'passed':False,'fixture':tag}
def shell(script,ok=(0,),timeout=100):
    p=subprocess.run(['adb','shell',script],capture_output=True,text=True,timeout=timeout)
    with (out/'commands.log').open('a') as f:f.write(f'code={p.returncode}\n{p.stdout}{p.stderr}\n')
    assert p.returncode in ok,(p.returncode,p.stdout,p.stderr)
    return p.stdout.strip()
def q(x):return shlex.quote(str(x))
def envs():return f'BAIZE_STATE_DIR={q(state)} BAIZE_NATIVE_ENGINE={q(module+"/bin/x86_64/baize_engine")}'
try:
    with tempfile.TemporaryDirectory(prefix='baize-module-corpse-') as d:
        with zipfile.ZipFile(sys.argv[1]) as z:z.extractall(d)
        shell('mkdir -p '+q(remote))
        subprocess.run(['adb','push',d+'/.',module+'/'],check=True,stdout=subprocess.DEVNULL)
    shell(f'chmod 755 {q(module)}/scripts/*.sh {q(module)}/bin/x86_64/*; mkdir -p {q(state)} {q(target)}/nested; cp {q(module)}/config/whitelist.conf {q(state)}/whitelist.conf; printf old >{q(target)}/nested/reviewed.bin')
    shell(f'{envs()} sh {q(module)}/scripts/one-pass-scan.sh corpse-scan test',timeout=180)
    targets=shell('cat '+q(state+'/corpse_scan.targets')).splitlines()
    assert targets==[target],('Unexpected scan scope; no cleanup performed',targets)
    frozen=json.loads(shell('cat '+q(state+'/corpse_scan.frozen.json')))
    assert [x['path'] for x in frozen['entries']]==[target]
    shell(f'printf newly-added >{q(target)}/nested/after-review.bin; touch -t 202001010000 {q(target)}/nested/after-review.bin')
    shell(f'{envs()} sh {q(module)}/scripts/profile-cleaner.sh corpse-clean test',ok=(8,),timeout=90)
    shell(f'test ! -e {q(target)}/nested/reviewed.bin; test "$(cat {q(target)}/nested/after-review.bin)" = newly-added; test -f {q(state)}/corpse_scan.frozen.json')
    first=dict(line.split('=',1) for line in shell('cat '+q(state+'/latest.env')).splitlines() if '=' in line)
    assert first['files']=='1' and first['bytes']=='3' and first['deep_remaining_targets']=='1',first
    shell(f'{envs()} sh {q(module)}/scripts/profile-cleaner.sh corpse-clean test',ok=(8,),timeout=90)
    shell(f'test "$(cat {q(target)}/nested/after-review.bin)" = newly-added')
    second=dict(line.split('=',1) for line in shell('cat '+q(state+'/latest.env')).splitlines() if '=' in line)
    assert second['files']=='0' and second['bytes']=='0',second
    # A fresh explicit scan authorizes the new content; the same packaged helper deletes it.
    shell(f'{envs()} sh {q(module)}/scripts/one-pass-scan.sh corpse-scan test',timeout=180)
    assert shell('cat '+q(state+'/corpse_scan.targets')).splitlines()==[target]
    shell(f'{envs()} sh {q(module)}/scripts/profile-cleaner.sh corpse-clean test',timeout=90)
    shell(f'test ! -e {q(target)}')
    result.update(passed=True,uid=int(shell('id -u')),signedPackagedHelperValidated=True,
        realNativeScanAndFrozenCapture=True,backdatedNewContentsPreserved=True,
        partialCountsAccurate=True,retryDoesNotDiscoverNewFiles=True,explicitRescanAuthorizesOnlyCurrentManifest=True,
        first=first,second=second)
except Exception as e:result['error']=str(e)
finally:
    # Exact UUID paths only, created by this script in the disposable emulator.
    subprocess.run(['adb','shell','rm -rf -- '+q(target)+' '+q(remote)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    (out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False))
if not result['passed']:raise SystemExit(1)
