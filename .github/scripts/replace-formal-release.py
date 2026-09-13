"""User-authorized replacement of release 387739651 only; verify before advancing OTA."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

REPO = 'xgl34222220-ops/BaiZe'
TAG = 'v3.0.0'
RELEASE_ID = 387739651
OLD_SHA = '79b62b5b1fe1ca94515105a1798cdf054304203e'
FILES = ['BaiZe-v3.0.0-Module.zip', 'BaiZe-v3.0.0-Module.zip.sha256',
         'BaiZe-v3.0.0.apk', 'BaiZe-v3.0.0.apk.sha256', 'BaiZe-v3.0.0-signing-certificate.txt']
TEMP = Path(os.environ['RUNNER_TEMP'])
BACKUP = TEMP / 'baize-formal-rollback'
DIST = Path('v2/dist').resolve()


def run(*args: str, cwd: Path | None = None, data: str | None = None) -> str:
    return subprocess.run(args, cwd=cwd, input=data, text=True, stdout=subprocess.PIPE,
                          check=True, timeout=300).stdout.strip()


def api(path: str, payload: dict | None = None) -> dict:
    args = ['gh', 'api', f'repos/{REPO}/{path}']
    if payload is not None:
        args += ['--method', 'PATCH', '--input', '-']
    return json.loads(run(*args, data=None if payload is None else json.dumps(payload)))


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(directory: Path, code: int) -> None:
    for name in FILES:
        if not (directory / name).is_file():
            raise ValueError(f'Missing release asset {name}')
    for name in FILES:
        if name.endswith('.sha256'):
            if (directory / name).read_text().split()[0] != digest(directory / name.removesuffix('.sha256')):
                raise ValueError(f'Checksum mismatch: {name}')
    with zipfile.ZipFile(directory / FILES[0]) as package:
        if package.testzip() is not None:
            raise ValueError('Corrupt module archive')
        if f'versionCode={code}' not in package.read('module.prop').decode().splitlines():
            raise ValueError('Unexpected module build number')
        if package.read('app/baize.apk') != (directory / 'BaiZe-v3.0.0.apk').read_bytes():
            raise ValueError('Embedded App differs from standalone APK')


def download(destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    args = ['gh', 'release', 'download', TAG, '-R', REPO, '--dir', str(destination), '--clobber']
    for name in FILES:
        args += ['--pattern', name]
    run(*args)


def backup() -> None:
    release = api(f'releases/{RELEASE_ID}')
    ref = api(f'git/ref/tags/{TAG}')
    if release['tag_name'] != TAG or release['draft'] or release['prerelease'] or release.get('immutable', False):
        raise ValueError('Release is not the authorized mutable formal release')
    if ref['object']['sha'] != OLD_SHA:
        raise ValueError('Release tag changed; refusing to replace another build')
    download(BACKUP / 'assets')
    verify(BACKUP / 'assets', 30000)
    (BACKUP / 'release.json').write_text(json.dumps(release, ensure_ascii=False, indent=2))
    (BACKUP / 'tag.json').write_text(json.dumps(ref, indent=2))
    print('Original formal assets backed up and verified.')


def publish() -> None:
    target = os.environ['BAIZE_RELEASE_TARGET_SHA']
    old = json.loads((BACKUP / 'release.json').read_text())
    verify(DIST, 30001)
    run('git', 'config', 'user.name', 'github-actions[bot]')
    run('git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
    run('git', 'fetch', 'origin', 'main', 'downloads')
    if run('git', 'show', 'origin/main:.github/rebuild-stable.publish') != target:
        raise ValueError('Replacement request superseded')
    if api(f'git/ref/tags/{TAG}')['object']['sha'] != OLD_SHA:
        raise ValueError('Release tag changed during build')
    mirror = TEMP / 'baize-formal-mirror'
    previous_mirror = run('git', 'rev-parse', 'origin/downloads')
    run('git', 'worktree', 'add', str(mirror), 'origin/downloads')
    destination = mirror / 'releases' / TAG
    for name in FILES:
        if digest(destination / name) != digest(BACKUP / 'assets' / name):
            raise ValueError('Existing mirror and formal release differ')
    mirror_pushed = False
    try:
        run('gh', 'release', 'upload', TAG, '-R', REPO, '--clobber', *(str(DIST / n) for n in FILES))
        published = TEMP / 'baize-formal-published'
        download(published)
        verify(published, 30001)
        for name in FILES:
            if digest(published / name) != digest(DIST / name):
                raise ValueError(f'Published asset mismatch: {name}')
            shutil.copy2(DIST / name, destination / name)
        run('git', 'add', '-f', f'releases/{TAG}', cwd=mirror)
        run('git', 'commit', '-m', 'release: replace v3.0.0 with verified UI build 30001', cwd=mirror)
        run('git', 'push', 'origin', 'HEAD:downloads', cwd=mirror)
        mirror_pushed = True
        if api(f'git/ref/tags/{TAG}')['object']['sha'] != OLD_SHA:
            raise ValueError('Release tag changed before promotion')
        api(f'git/refs/tags/{TAG}', {'sha': target, 'force': True})
        api(f'releases/{RELEASE_ID}', {'name': '白泽 v3.0.0 正式版 · 洛书同源 UI',
            'body': Path('RELEASE_NOTES_v3.0.0.md').read_text(), 'draft': False,
            'prerelease': False, 'make_latest': 'true', 'target_commitish': target})
        receipt = {'release_id': RELEASE_ID, 'tag': TAG, 'versionCode': 30001,
                   'source': target, 'previous_source': OLD_SHA,
                   'previous_downloads': previous_mirror,
                   'downloads': run('git', 'rev-parse', 'HEAD', cwd=mirror),
                   'sha256': {name: digest(DIST / name) for name in FILES}}
        (TEMP / 'baize-formal-receipt.json').write_text(json.dumps(receipt, indent=2))
        print(json.dumps(receipt, indent=2))
    except Exception:
        print('Replacement failed; restoring backed-up release assets.', file=sys.stderr)
        run('gh', 'release', 'upload', TAG, '-R', REPO, '--clobber', *(str(BACKUP / 'assets' / n) for n in FILES))
        if mirror_pushed:
            for name in FILES:
                shutil.copy2(BACKUP / 'assets' / name, destination / name)
            run('git', 'add', '-f', f'releases/{TAG}', cwd=mirror)
            run('git', 'commit', '-m', 'release: roll back incomplete v3.0.0 replacement', cwd=mirror)
            run('git', 'push', 'origin', 'HEAD:downloads', cwd=mirror)
        if api(f'git/ref/tags/{TAG}')['object']['sha'] == target:
            api(f'git/refs/tags/{TAG}', {'sha': OLD_SHA, 'force': True})
        api(f'releases/{RELEASE_ID}', {k: old[k] for k in ['name', 'body', 'draft', 'prerelease', 'target_commitish']})
        raise


if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in ('backup', 'publish'):
        raise SystemExit('Usage: replace-formal-release.py backup|publish')
    {'backup': backup, 'publish': publish}[sys.argv[1]]()
