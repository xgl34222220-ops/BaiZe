#!/usr/bin/env python3
"""Legacy direct corpse entry routes before defaults or locks; no fallback rm-rf."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[2]
class Routing(unittest.TestCase):
    def test_routing_preserves_arguments_exit_and_does_not_initialize_state(self):
        for mode,name in [('corpse-scan','one-pass-scan.sh'),('corpse-clean','profile-cleaner.sh'), ('deep-scan','deep-scan-manifest.sh'),('deep-clean','deep-manifest-clean.sh')]:
            with self.subTest(mode=mode),tempfile.TemporaryDirectory(prefix='baize-legacy-corpse-') as d:
                root=Path(d);module=root/'module/scripts';module.mkdir(parents=True)
                shutil.copy(ROOT/'v2/module/scripts/cleaner-compat.sh',module/'cleaner-compat.sh')
                (module/name).write_text('#!/bin/sh\nprintf "%s\\n" "$@" >"$ARGUMENTS"\nexit 17\n')
                state=root/'missing-state';arguments=root/'args'
                p=subprocess.run(['sh',str(module/'cleaner-compat.sh'),mode,'manual with spaces'],capture_output=True,text=True,
                    env={**os.environ,'BAIZE_STATE_DIR':str(state),'ARGUMENTS':str(arguments)})
                self.assertEqual(17,p.returncode,p.stdout+p.stderr)
                self.assertEqual(mode+'\nmanual with spaces\n',arguments.read_text())
                self.assertFalse(state.exists())
    def test_missing_new_component_fails_closed_without_state_changes(self):
        for mode in ('corpse-scan','corpse-clean','deep-scan','deep-clean'):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory(prefix='baize-legacy-missing-') as d:
                root=Path(d);module=root/'module';module.mkdir()
                shutil.copy(ROOT/'v2/module/scripts/cleaner-compat.sh',module/'cleaner-compat.sh')
                p=subprocess.run(['sh',str(module/'cleaner-compat.sh'),mode],capture_output=True,text=True,
                                 env={**os.environ,'BAIZE_STATE_DIR':str(root/'state')})
                self.assertEqual(8,p.returncode,p.stdout+p.stderr);self.assertFalse((root/'state').exists())
    def test_legacy_directory_deletion_and_package_fallback_are_removed(self):
        source=(ROOT/'v2/module/scripts/cleaner-compat.sh').read_text()
        self.assertNotIn('corpse_process_target()',source)
        self.assertNotIn('run_corpse_cleanup()',source)
        self.assertNotIn('package_list_for_user()',source)
        self.assertNotIn('CORPSE_SCAN_MANIFEST_TMP',source)
        self.assertNotIn('deep_process_target()',source)
        self.assertNotIn('run_deep_rules()',source)
        self.assertNotIn('DEEP_SCAN_MANIFEST_TMP',source)
if __name__=='__main__':unittest.main(verbosity=2)
