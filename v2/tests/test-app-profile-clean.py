#!/usr/bin/env python3
"""End-to-end: per-app profile tiers through the real rules-clean compatibility route.

Only the fixture copy remaps absolute Android data paths (same technique as
test-rule-engine-followup.py); discovery uses the shell fallback and deletion uses
the real native identity helper.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
RULES = '''conservative|data|com.example.chat|files/xlog|0
standard|ext|com.example.chat|cache|0
enhanced|data|com.example.chat|files/sns|0
enhanced-media|ext|com.example.chat|chatpic|7
standard|data|com.example.chat|databases|0
'''
LOG = 'user/0/com.example.chat/files/xlog/old.xlog'
CACHE = 'media/0/Android/data/com.example.chat/cache/old.bin'
SNS = 'user/0/com.example.chat/files/sns/old.jpg'
MEDIA = 'media/0/Android/data/com.example.chat/chatpic/old.jpg'
DB = 'user/0/com.example.chat/databases/EnMicroMsg.db'
FRESH_MEDIA = 'media/0/Android/data/com.example.chat/chatpic/new.jpg'


class AppProfileClean(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix='baize-app-profile-')
        cls.work = Path(cls.workspace.name)
        cls.compat_engine = cls.work / 'baize_compat_filter'
        subprocess.run([os.environ.get('CC', 'cc'), '-std=c11', '-O2', '-Wall', '-Wextra',
                        '-Werror', str(ROOT / 'v2/native/baize_compat_filter.c'),
                        '-o', str(cls.compat_engine)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def clean(self, tier, media, enabled=1):
        case = Path(tempfile.mkdtemp(dir=self.work))
        data, state, module = (case / p for p in ('data', 'state', 'module'))
        data.mkdir(); state.mkdir(); module.mkdir()
        shutil.copytree(ROOT / 'config', module / 'config')
        shutil.copy(ROOT / 'v2/module/scripts/cleanup-media-queue.sh', module / 'cleanup-media-queue.sh')
        shutil.copy(ROOT / 'v2/module/scripts/app-profile-rules.sh', module / 'app-profile-rules.sh')
        source = (ROOT / 'v2/module/scripts/cleaner-compat.sh').read_text()
        mapped = re.sub(r'(?<![A-Za-z0-9_/])/data(?=/|\b|_)', str(data), source)
        (module / 'cleaner.sh').write_text(mapped)
        for name in ('app.rules', 'external.rules', 'hidden.rules'):
            (module / 'config' / name).write_text('')
        (module / 'config/app-profiles.rules').write_text(RULES)
        flags = ('clean_app_cache', 'clean_external_cache', 'clean_system_logs', 'clean_oem_logs',
                 'clean_fragments', 'clean_apk_packages', 'clean_installer_temp', 'clean_custom_rules',
                 'clean_root_shells', 'clean_hidden_junk', 'clean_empty_dirs')
        config = (ROOT / 'config/default.conf').read_text() + '\n'
        config += ''.join(f'{key}=0\n' for key in flags)
        config += (f'enabled=1\nclean_app_rules=1\nclean_empty_files=1\nnotify_on_complete=0\n'
                   f'app_profile_enabled={enabled}\napp_profile_tier={tier}\napp_profile_user_media={media}\n')
        (state / 'config.conf').write_text(config)
        (state / 'whitelist.conf').write_text('')
        for relative in (LOG, CACHE, SNS, MEDIA, DB, FRESH_MEDIA):
            path = data / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'payload')
            if relative != FRESH_MEDIA:
                epoch = time.time() - 9 * 86400
                os.utime(path, (epoch, epoch))
        (module / 'abi-resolve.sh').write_text('baize_resolve_engine() {\n'
            ' [ -x "$1/bin/x86_64/$2" ] || return 1\n'
            ' printf "%s\\n" "$1/bin/x86_64/$2"\n}\n')
        helper = module / 'bin/x86_64/baize_compat_filter'
        helper.parent.mkdir(parents=True)
        shutil.copy(self.compat_engine, helper)
        env = os.environ.copy(); env['BAIZE_STATE_DIR'] = str(state)
        time.sleep(2.05)
        result = subprocess.run(['bash', str(module / 'cleaner.sh'), 'rules-clean', 'manual'],
                                env=env, text=True, capture_output=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        gone = {r for r in (LOG, CACHE, SNS, MEDIA, DB, FRESH_MEDIA) if not (data / r).exists()}
        # Compiled per-task rules never outlive the task.
        self.assertEqual([], list(state.rglob('app-profile-*.rules')))
        return gone

    def test_tiers_are_cumulative_and_user_data_needs_explicit_enhanced_opt_in(self):
        self.assertEqual(self.clean(0, 1), {LOG})
        self.assertEqual(self.clean(1, 1), {LOG, CACHE})
        self.assertEqual(self.clean(2, 0), {LOG, CACHE, SNS})
        self.assertEqual(self.clean(2, 1), {LOG, CACHE, SNS, MEDIA})

    def test_disabled_profile_cleans_nothing(self):
        self.assertEqual(self.clean(2, 1, enabled=0), set())


if __name__ == '__main__':
    unittest.main(verbosity=2)
