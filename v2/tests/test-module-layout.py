#!/usr/bin/env python3
"""Exercise runtime dispatch after moving implementations away from framework hooks."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'v2/module'

class ModuleLayoutTest(unittest.TestCase):
    def test_framework_root_contains_only_hooks_and_metadata(self):
        self.assertEqual({p.name for p in MODULE.iterdir()},
                         {'action.sh', 'customize.sh', 'service.sh', 'uninstall.sh',
                          'module.prop', 'skip_mount', 'scripts'})

    def test_dispatch_keeps_arguments_and_exit_status_in_nested_layout(self):
        with tempfile.TemporaryDirectory(prefix='baize layout ') as temp:
            module = Path(temp) / 'module'
            shutil.copytree(MODULE, module)
            native = module / 'scripts/native-cleaner.sh'
            native.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\nexit 17\n')
            native.chmod(0o755)
            result = subprocess.run(['sh', str(module / 'scripts/cleaner.sh'), 'cache-scan', 'manual task'],
                                    env={**os.environ, "BAIZE_SHELL": "/bin/sh",
                                         "BAIZE_STATE_DIR": str(Path(temp) / 'state')},
                                    capture_output=True, text=True)
            self.assertEqual(17, result.returncode, result.stderr)
            self.assertEqual(['cache-scan', 'manual task'], result.stdout.splitlines())

    def test_rules_resolve_from_module_root(self):
        with tempfile.TemporaryDirectory() as temp:
            module = Path(temp) / 'module'
            shutil.copytree(MODULE, module)
            shutil.copytree(ROOT / 'config', module / 'config')
            env = {**os.environ, 'BAIZE_STATE_DIR': str(Path(temp) / 'state')}
            result = subprocess.run(['sh', str(module / 'scripts/rules-validator.sh')],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_confirmed_icon_is_the_launcher_source(self):
        # Pin the actual build inputs, exported from the approved design. Large
        # editing masters are kept locally and are not required to build the app.
        self.assertEqual('9cd466477c318cf6b86caf8fb02ddab79b63e1e93e594165758dc46771bef84c',
                         hashlib.sha256((ROOT / 'v2/app/src/main/res/drawable-nodpi/ic_baize_art.webp').read_bytes()).hexdigest())
        self.assertEqual('9816d33e789ee230e36a1d4a7e4ceeaf292a4bb0cefec22ef0724be8665e64b4',
                         hashlib.sha256((ROOT / 'v2/app/src/main/res/drawable-nodpi/ic_baize_monochrome_art.png').read_bytes()).hexdigest())
        icon = (ROOT / 'design/app-icons/official-icon.webp').read_bytes()
        self.assertEqual(icon, (ROOT / 'v2/app/src/main/res/mipmap-xxxhdpi/ic_baize.webp').read_bytes())
        self.assertEqual(icon, (ROOT / 'branding/baize-app-icon.webp').read_bytes())
        res = ROOT / 'v2/app/src/main/res'
        android = '{http://schemas.android.com/apk/res/android}'
        manifest = ET.parse(ROOT / 'v2/app/src/main/AndroidManifest.xml').getroot().find('application')
        self.assertEqual('@mipmap/ic_baize', manifest.attrib[android + 'icon'])
        self.assertEqual('@mipmap/ic_baize', manifest.attrib[android + 'roundIcon'])
        for variant in ('mipmap-anydpi-v26', 'mipmap-anydpi-v33'):
            adaptive = ET.parse(res / variant / 'ic_baize.xml').getroot()
            self.assertEqual('adaptive-icon', adaptive.tag)
            self.assertEqual('@drawable/ic_baize_foreground', adaptive.find('foreground').attrib[android + 'drawable'])
        foreground = ET.parse(res / 'drawable/ic_baize_foreground.xml').getroot()
        self.assertEqual('@drawable/ic_baize_art', foreground.attrib[android + 'drawable'])
        self.assertEqual('9cd466477c318cf6b86caf8fb02ddab79b63e1e93e594165758dc46771bef84c',
                         hashlib.sha256((res / 'drawable-nodpi/ic_baize_art.webp').read_bytes()).hexdigest())

    def test_upgrade_stops_flat_and_grouped_compatibility_workers(self):
        source = (MODULE / 'customize.sh').read_text()
        block = source[source.index('# Stop old flat-layout workers'):source.index('rm -rf "$STATE_DIR/run.lock"')]
        with tempfile.TemporaryDirectory() as temp:
            calls = Path(temp) / 'signals.txt'
            # Replace process signalling, then execute the installer's actual stop block.
            stub = 'pkill() { printf "%s\\n" "$2" >> "$TEST_SIGNALS"; };\n'
            subprocess.run(['sh', '-c', stub + block], check=True,
                           env={**os.environ, 'TEST_SIGNALS': str(calls)})
            targets = calls.read_text().splitlines()
            for name in ('cleaner', 'cleaner-compat', 'task-worker', 'scheduler', 'supervisor'):
                for directory in ('', 'scripts/'):
                    self.assertIn(f'/data/adb/modules/baize_v2/{directory}{name}.sh', targets)

if __name__ == '__main__':
    unittest.main()
