#!/usr/bin/env python3
"""Check the deliverable's layout, executable bits and exact approved launcher asset."""
from io import BytesIO
from pathlib import Path
import stat
import sys
import zipfile

archive_path, icon_path = map(Path, sys.argv[1:])
module_source = Path(__file__).resolve().parents[1] / 'module'
expected_root = {'module.prop', 'skip_mount', 'customize.sh', 'service.sh', 'action.sh', 'uninstall.sh'}
allowed_dirs = {'scripts', 'app', 'bin', 'config'}
with zipfile.ZipFile(archive_path) as module:
    names = {item.filename for item in module.infolist() if not item.is_dir()}
    assert {name for name in names if '/' not in name} == expected_root, 'Unexpected module root files'
    assert {name.split('/')[0] for name in names if '/' in name} == allowed_dirs, 'Unexpected module directories'
    expected_scripts = {'scripts/' + file.name for file in (module_source / 'scripts').glob('*.sh')}
    assert {name for name in names if name.startswith('scripts/')} == expected_scripts, 'Runtime payload differs from source'
    for name in names:
        assert not name.startswith('/') and '..' not in name.split('/'), 'Unsafe archive member'
        mode = module.getinfo(name).external_attr >> 16
        assert not stat.S_ISLNK(mode), 'Unexpected symlink'
        if (name.endswith('.sh') and name != 'scripts/abi-resolve.sh') or name.startswith('bin/'):
            assert mode & 0o111, f'Executable bit missing: {name}'
    with zipfile.ZipFile(BytesIO(module.read('app/baize.apk'))) as apk:
        icon = icon_path.read_bytes()
        assert any(apk.read(name) == icon for name in apk.namelist() if name.endswith('.webp')), 'Approved launcher icon missing from APK'
print('Module layout, runtime payload, permissions and approved launcher icon verified.')
