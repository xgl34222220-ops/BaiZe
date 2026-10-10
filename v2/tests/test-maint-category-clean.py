#!/usr/bin/env python3
"""分类定时清理（cleaner-compat category-clean）端到端：只按所选应用包名执行应用专项规则，
默认全部关闭；结束时写入今日统计与模块描述，且不改动常规调度的 last_run.epoch。

夹具做法与 test-app-profile-clean.py 相同：只在副本里把 /data 重映射到临时目录。
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
RULES = '''conservative|data|com.tencent.mm|files/xlog|0
conservative|data|com.ss.android.ugc.aweme|cache/log|0
conservative|data|com.example.other|files/xlog|0
standard|data|com.tencent.mm|databases|0
'''
WX = 'user/0/com.tencent.mm/files/xlog/old.xlog'
DY = 'user/0/com.ss.android.ugc.aweme/cache/log/old.log'
OTHER = 'user/0/com.example.other/files/xlog/old.xlog'
DB = 'user/0/com.tencent.mm/databases/EnMicroMsg.db'
ALL = (WX, DY, OTHER, DB)


class MaintCategoryClean(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix='baize-category-')
        cls.work = Path(cls.workspace.name)
        cls.compat_engine = cls.work / 'baize_compat_filter'
        subprocess.run([os.environ.get('CC', 'cc'), '-std=c11', '-O2', '-Wall', '-Wextra',
                        '-Werror', str(ROOT / 'v2/native/baize_compat_filter.c'),
                        '-o', str(cls.compat_engine)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def run_category(self, flags):
        case = Path(tempfile.mkdtemp(dir=self.work))
        data, state, module = (case / p for p in ('data', 'state', 'module'))
        data.mkdir(); state.mkdir(); module.mkdir()
        shutil.copytree(ROOT / 'config', module / 'config')
        shutil.copy(ROOT / 'v2/module/scripts/cleanup-media-queue.sh', module / 'cleanup-media-queue.sh')
        shutil.copy(ROOT / 'v2/module/scripts/app-profile-rules.sh', module / 'app-profile-rules.sh')
        source = (ROOT / 'v2/module/scripts/cleaner-compat.sh').read_text()
        mapped = re.sub(r'(?<![A-Za-z0-9_/])/data(?=/|\b|_)', str(data), source)
        (module / 'cleaner.sh').write_text(mapped)
        (module / 'module.prop').write_text('id=baize_v2\nname=白泽\ndescription=old\nversion=v3.0.0\n')
        for name in ('app.rules', 'external.rules', 'hidden.rules'):
            (module / 'config' / name).write_text('')
        (module / 'config/app-profiles.rules').write_text(RULES)
        config = (ROOT / 'config/default.conf').read_text() + '\n'
        config += 'notify_on_complete=0\napp_profile_enabled=1\napp_profile_tier=1\nmaint_clean_logcat=0\n'
        config += ''.join(f'{key}={value}\n' for key, value in flags.items())
        (state / 'config.conf').write_text(config)
        (state / 'whitelist.conf').write_text('')
        for relative in ALL:
            path = data / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'payload')
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
        result = subprocess.run(['bash', str(module / 'cleaner.sh'), 'category-clean', 'maintenance'],
                                env=env, text=True, capture_output=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        gone = {r for r in ALL if not (data / r).exists()}
        return gone, state, module

    def test_defaults_clean_nothing(self):
        gone, state, _ = self.run_category({})
        self.assertEqual(set(), gone)
        self.assertFalse((state / 'last_run.epoch').exists())

    def test_only_selected_packages_and_protected_paths_stay(self):
        gone, state, module = self.run_category({'maint_clean_wechat': 1})
        self.assertEqual({WX}, gone)
        gone, _, _ = self.run_category({'maint_clean_shortvideo': 1})
        self.assertEqual({DY}, gone)
        gone, state, module = self.run_category({'maint_clean_wechat': 1, 'maint_clean_shortvideo': 1, 'maint_clean_qq': 1})
        self.assertEqual({WX, DY}, gone)
        self.assertTrue((ROOT / 'config/default.conf').exists())
        # 常规调度不因维护窗口里的分类清理而顺延。
        self.assertFalse((state / 'last_run.epoch').exists())
        totals = dict(l.split('=', 1) for l in (state / 'totals.env').read_text().splitlines() if '=' in l)
        self.assertEqual(time.strftime('%Y-%m-%d'), totals['today'])
        self.assertEqual('1', totals['today_runs'])
        self.assertEqual('2', totals['today_files'])
        description = next(l for l in (module / 'module.prop').read_text().splitlines() if l.startswith('description='))
        self.assertTrue(description.startswith('description=今日清理 '), description)
        self.assertIn('· 2 项 · 1 次 | 累计清理 ', description)

    def test_default_conf_ships_all_categories_off(self):
        conf = (ROOT / 'config/default.conf').read_text()
        for key in ('maint_clean_wechat', 'maint_clean_qq', 'maint_clean_shortvideo', 'maint_clean_logcat'):
            self.assertIn(f'\n{key}=0\n', conf)


if __name__ == '__main__':
    unittest.main(verbosity=2)
