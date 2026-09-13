"""Reuse ONLY the exact signed 30002 APK whose build/JVM/lint already passed.
Android startup was the failed stage; it must be rerun, not waived. This one-off
promotion avoids rebuilding identical application sources during incident repair.
"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

REPO = 'xgl34222220-ops/BaiZe'
RUN = 34735111483
SOURCE = '0e0b1f4a00b93f05213ea6101febfe35b6dc452e'
TRIGGER = '7e9e752776408f7c4be6656a432cc1f2d8db526f'
APK_SHA = '600ac2de5f33fdf282f222a2c1099ec63f9ed7766482fe56f238e487836a4fc4'
TEMP = Path(os.environ['RUNNER_TEMP'])


def run(*args: str) -> str:
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=240).stdout


def main() -> None:
    # Include the entire app/build/native/module/rules source boundary, not just Kotlin.
    run('git', 'diff', '--exit-code', SOURCE, 'HEAD', '--', 'v2', 'config', 'module.prop', 'cleaner.sh', 'notify.sh')
    meta = json.loads(run('gh', 'api', f'repos/{REPO}/actions/runs/{RUN}'))
    if meta['head_sha'] != TRIGGER or meta['run_attempt'] != 1 or meta['status'] != 'completed':
        raise ValueError('Unexpected source workflow identity')
    jobs = json.loads(run('gh', 'api', f'repos/{REPO}/actions/runs/{RUN}/jobs'))['jobs']
    checks = [s for j in jobs for s in j['steps'] if s['name'] == 'Verify all JVM tests, lint and signed App']
    if len(checks) != 1 or checks[0]['conclusion'] != 'success':
        raise ValueError('Original compile/JVM/lint step did not pass')
    candidate = TEMP / 'baize-reuse-candidate'
    evidence = TEMP / 'baize-reuse-evidence'
    run('gh', 'run', 'download', str(RUN), '-R', REPO, '-n', 'BaiZe-30002-Signed-Candidate', '-D', str(candidate))
    run('gh', 'run', 'download', str(RUN), '-R', REPO, '-n', 'BaiZe-v3.0.0-30002-Evidence', '-D', str(evidence))
    apk = candidate / 'apk/release/app-release.apk'
    if hashlib.sha256(apk.read_bytes()).hexdigest() != APK_SHA:
        raise ValueError('Candidate is not the original signed 30002 APK')
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    build = Path('v2/app/build')
    for file in evidence.rglob('TEST-*.xml'):
        test = ET.parse(file).getroot()
        for k in counts:
            counts[k] += int(test.attrib.get(k, 0))
        relative = str(file).split('/v2/app/build/', 1)[1]
        target = build / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(file, target)
    if counts != dict(tests=247, failures=0, errors=0, skipped=0):
        raise ValueError(f'Unexpected source-identical JVM results: {counts}')
    for file in evidence.rglob('lint-results-*.txt'):
        target = build / 'reports' / file.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(file, target)
    original_log = next(evidence.rglob('baize-formal-rebuild.log'))
    shutil.copy2(original_log, TEMP / 'baize-formal-rebuild.log')
    target_apk = build / 'outputs/apk/release/app-release.apk'
    target_apk.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(apk, target_apk)
    shutil.copytree(candidate / 'mapping/release', build / 'outputs/mapping/release', dirs_exist_ok=True)
    report = {'source_build_run': RUN, 'source_commit': SOURCE, 'source_unchanged': True,
              'apk_sha256': APK_SHA, 'jvm_results': counts, 'original_build_step': checks[0],
              'android_startup_status': 'must rerun; original ANR is NOT accepted'}
    (TEMP / 'baize-build-reuse.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
