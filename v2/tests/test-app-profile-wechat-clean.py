#!/usr/bin/env python3
"""End-to-end: shipped WeChat profile rules through the real rules-clean compatibility route.

The account-hash placeholder is expanded at task time only into real 32-hex account
directories; databases, received files, favourites and look-alike/symlinked account
directories are never touched. Same fixture technique as test-app-profile-clean.py.
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
A = '0123456789abcdef0123456789abcdef'
DATA = 'user/0/com.tencent.mm'
EXT = 'media/0/Android/data/com.tencent.mm'
OLD, RECENT = 40, 10

FILES = {
    # cleanable media (enhanced + opt-in, older than the chosen days)
    'img_data': (f'{DATA}/MicroMsg/{A}/image2/ab/cd/old.jpg', OLD),
    'thumb_data': (f'{DATA}/MicroMsg/{A}/image2/ab/cd/th_old', OLD),
    'img_recent': (f'{DATA}/MicroMsg/{A}/image2/ab/cd/new.jpg', RECENT),
    'img_ext': (f'{EXT}/MicroMsg/{A}/image2/ef/01/old.jpg', OLD),
    'voice': (f'{DATA}/MicroMsg/{A}/voice2/12/34/msg_old.amr', OLD),
    'video': (f'{EXT}/MicroMsg/{A}/video/old.mp4', OLD),
    # caches (every tier)
    'sns': (f'{DATA}/MicroMsg/{A}/sns/old', OLD),
    'avatar': (f'{DATA}/MicroMsg/{A}/avatar/aa/bb/user_old.png', OLD),
    'cache': (f'{EXT}/cache/old.bin', OLD),
    'xlog': (f'{EXT}/MicroMsg/xlog/old.xlog', OLD),
    # protected, always kept
    'db': (f'{DATA}/MicroMsg/{A}/EnMicroMsg.db', OLD),
    'index': (f'{DATA}/MicroMsg/{A}/WxFileIndex.db', OLD),
    'attachment': (f'{DATA}/MicroMsg/{A}/attachment/old.pdf', OLD),
    'favorite': (f'{DATA}/MicroMsg/{A}/favorite/old', OLD),
    'emoji': (f'{DATA}/MicroMsg/{A}/emoji/old', OLD),
    'download': (f'{EXT}/MicroMsg/Download/old.zip', OLD),
    'prefs': (f'{DATA}/shared_prefs/old.xml', OLD),
    'cfg': (f'{DATA}/MicroMsg/systemInfo.cfg', OLD),
    'fake_account': (f'{DATA}/MicroMsg/0123456789abcdef0123456789abcdeg/image2/ab/old.jpg', OLD),
    'upper_account': (f'{DATA}/MicroMsg/{A.upper()}/image2/ab/old.jpg', OLD),
}
CACHES = {'sns', 'avatar', 'cache', 'xlog'}
MEDIA = {'img_data', 'thumb_data', 'img_ext', 'voice', 'video'}


class WeChatProfileClean(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix='baize-wechat-clean-')
        cls.work = Path(cls.workspace.name)
        cls.compat_engine = cls.work / 'baize_compat_filter'
        subprocess.run([os.environ.get('CC', 'cc'), '-std=c11', '-O2', '-Wall', '-Wextra',
                        '-Werror', str(ROOT / 'v2/native/baize_compat_filter.c'),
                        '-o', str(cls.compat_engine)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def clean(self, tier, media, media_days=None, big=False):
        case = Path(tempfile.mkdtemp(dir=self.work))
        data, state, module, outside = (case / p for p in ('data', 'state', 'module', 'outside'))
        for d in (data, state, module):
            d.mkdir()
        shutil.copytree(ROOT / 'config', module / 'config')
        shutil.copy(ROOT / 'v2/module/scripts/cleanup-media-queue.sh', module / 'cleanup-media-queue.sh')
        shutil.copy(ROOT / 'v2/module/scripts/app-profile-rules.sh', module / 'app-profile-rules.sh')
        source = (ROOT / 'v2/module/scripts/cleaner-compat.sh').read_text()
        mapped = re.sub(r'(?<![A-Za-z0-9_/])/data(?=/|\b|_)', str(data), source)
        (module / 'cleaner.sh').write_text(mapped)
        for name in ('app.rules', 'external.rules', 'hidden.rules'):
            (module / 'config' / name).write_text('')
        flags = ('clean_app_cache', 'clean_external_cache', 'clean_system_logs', 'clean_oem_logs',
                 'clean_fragments', 'clean_apk_packages', 'clean_installer_temp', 'clean_custom_rules',
                 'clean_root_shells', 'clean_hidden_junk', 'clean_empty_dirs')
        config = (ROOT / 'config/default.conf').read_text() + '\n'
        config += ''.join(f'{key}=0\n' for key in flags)
        config += (f'enabled=1\nclean_app_rules=1\nclean_empty_files=1\nnotify_on_complete=0\n'
                   f'app_profile_enabled=1\napp_profile_tier={tier}\napp_profile_user_media={media}\n')
        if media_days is not None:
            config += f'app_profile_media_days={media_days}\n'
        if big:
            # Global single-file cap of 1 MiB; chat media below are 2 MiB.
            config += 'max_file_mb=1\n'
        (state / 'config.conf').write_text(config)
        (state / 'whitelist.conf').write_text('')
        paths = {}
        for key, (relative, age) in FILES.items():
            path = data / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'payload')
            if big and key in ('video', 'img_data', 'cache'):
                with open(path, 'r+b') as handle:
                    handle.truncate(2 * 1048576)
            epoch = time.time() - age * 86400
            os.utime(path, (epoch, epoch))
            paths[key] = path
        # A 32-hex account entry that is a symlink to a directory outside the package.
        escaped = outside / 'image2/ab/old.jpg'
        escaped.parent.mkdir(parents=True)
        escaped.write_bytes(b'payload')
        epoch = time.time() - OLD * 86400
        os.utime(escaped, (epoch, epoch))
        (data / DATA / 'MicroMsg' / ('b' * 32)).symlink_to(outside, target_is_directory=True)
        paths['symlink_account'] = escaped
        (module / 'abi-resolve.sh').write_text('baize_resolve_engine() {\n'
            ' [ -x "$1/bin/x86_64/$2" ] || return 1\n'
            ' printf "%s\\n" "$1/bin/x86_64/$2"\n}\n')
        helper = module / 'bin/x86_64/baize_compat_filter'
        helper.parent.mkdir(parents=True)
        shutil.copy(self.compat_engine, helper)
        env = os.environ.copy(); env['BAIZE_STATE_DIR'] = str(state)
        time.sleep(2.05)
        result = subprocess.run(['bash', str(module / 'cleaner.sh'), 'rules-clean', 'manual'],
                                env=env, text=True, capture_output=True, timeout=120)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual([], list(state.rglob('app-profile-*')), 'compiled rules must not outlive the task')
        return {key for key, path in paths.items() if not path.exists()}

    def test_conservative_cleans_only_caches(self):
        self.assertEqual(self.clean(0, 1), CACHES)

    def test_enhanced_without_opt_in_keeps_chat_media(self):
        self.assertEqual(self.clean(2, 0), CACHES)

    def test_enhanced_media_default_30_days(self):
        self.assertEqual(self.clean(2, 1), CACHES | MEDIA)

    def test_enhanced_media_user_chosen_7_days(self):
        self.assertEqual(self.clean(2, 1, media_days=7), CACHES | MEDIA | {'img_recent'})

    def test_enhanced_media_ignores_global_file_size_cap(self):
        self.assertEqual(self.clean(2, 1, big=True), CACHES | MEDIA)

    def test_size_cap_still_applies_without_media_opt_in(self):
        self.assertEqual(self.clean(2, 0, big=True), CACHES - {'cache'})

    def test_enhanced_media_90_days_keeps_40_day_old_media(self):
        self.assertEqual(self.clean(2, 1, media_days=90), CACHES)


if __name__ == '__main__':
    unittest.main(verbosity=2)
