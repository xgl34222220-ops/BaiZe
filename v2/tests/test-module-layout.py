#!/usr/bin/env python3
"""Exercise runtime dispatch after moving implementations away from framework hooks."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

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
        icon = (ROOT / 'design/app-icons/official-icon.webp').read_bytes()
        self.assertEqual(icon, (ROOT / 'v2/app/src/main/res/mipmap-xxxhdpi/ic_baize.webp').read_bytes())
        self.assertEqual(icon, (ROOT / 'branding/baize-app-icon.webp').read_bytes())

if __name__ == '__main__':
    unittest.main()
