#!/usr/bin/env python3
"""根目录自动整理（系统维护的一步）：只删空文件夹、维持禁止重建占位，永不碰内容与标准目录。"""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / 'v2/module/scripts'
SCRIPT = SCRIPTS / 'root-tidy.sh'
SCAN = re.compile(r'(^|[\s;|&(`])(find|du|restorecon|chcon|tar|sha256sum|md5sum)\b|ls\s+-[a-zA-Z]*R|ch(mod|own)\s+-R|cp\s+-[a-zA-Z]*[ar]')


class RootTidy(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = Path(self.temp.name)
        self.sd = base / 'media0'; self.state = base / 'state'
        self.sd.mkdir(); (self.state / 'logs').mkdir(parents=True)
        (self.state / 'config.conf').write_text('root_tidy_auto=1\n')
        for name in ('DCIM', 'download', 'baidu', 'emptyA', '.hiddenEmpty', 'keepme', 'recent', 'full', 'UCDownloads', 'tencent'):
            (self.sd / name).mkdir()
        (self.sd / 'full' / 'a.txt').write_text('data')
        (self.sd / 'tencent' / 'x.jpg').write_text('img')
        old = time.time() - 3 * 86400
        for name in ('DCIM', 'download', 'baidu', 'emptyA', '.hiddenEmpty', 'keepme', 'full', 'UCDownloads'):
            os.utime(self.sd / name, (old, old))
        (self.state / 'root-tidy.rules').write_text('auto=0\nallow|KeepMe\nblock|UCDownloads\nblock|tencent\nblock|kuwo\nblock|DCIM\nblock|../etc\n')

    def tearDown(self):
        self.temp.cleanup()

    def run_tidy(self, action='run', **extra):
        env = {**os.environ, 'BAIZE_STATE_DIR': str(self.state), 'BAIZE_ROOT_TIDY_ROOT': str(self.sd),
               'BAIZE_MODULE_DIR': str(SCRIPTS.parent), **extra}
        return subprocess.run(['sh', str(SCRIPT), action], env=env, capture_output=True, text=True, timeout=30)

    def test_removes_only_old_empty_non_standard_visible_folders(self):
        self.assertEqual(0, self.run_tidy().returncode)
        names = {p.name for p in self.sd.iterdir()}
        self.assertNotIn('baidu', names); self.assertNotIn('emptyA', names)
        for kept in ('DCIM', 'download', '.hiddenEmpty', 'keepme', 'recent', 'full'):
            self.assertIn(kept, names, kept)
        self.assertEqual('data', (self.sd / 'full' / 'a.txt').read_text())
        result = (self.state / 'root-tidy.env').read_text()
        self.assertIn('removed=2', result)

    def test_block_list_places_placeholders_but_never_replaces_content(self):
        self.run_tidy()
        self.assertTrue((self.sd / 'UCDownloads').is_file()); self.assertEqual(0, (self.sd / 'UCDownloads').stat().st_size)
        self.assertTrue((self.sd / 'kuwo').is_file())
        self.assertTrue((self.sd / 'tencent' / 'x.jpg').is_file(), 'non-empty blocked folder must be kept')
        self.assertTrue((self.sd / 'DCIM').is_dir())
        self.assertFalse((self.sd.parent / 'etc').exists())
        log = (self.state / 'logs' / 'root-tidy.log').read_text()
        self.assertIn('保留 tencent', log); self.assertIn('占位 kuwo', log)
        # 再跑一次是幂等的。
        self.run_tidy()
        self.assertIn('placeholders=0', (self.state / 'root-tidy.env').read_text())

    def test_preview_and_disabled_change_nothing(self):
        before = sorted(p.name for p in self.sd.iterdir())
        out = self.run_tidy('preview')
        self.assertIn('移除空文件夹 baidu', out.stdout)
        self.assertEqual(before, sorted(p.name for p in self.sd.iterdir()))
        (self.state / 'config.conf').write_text('root_tidy_auto=0\n')
        self.run_tidy()
        self.assertEqual(before, sorted(p.name for p in self.sd.iterdir()))
        self.assertFalse((self.state / 'root-tidy.env').exists())

    def test_running_clean_task_blocks_tidy(self):
        (self.state / 'run.lock').mkdir()
        self.run_tidy()
        self.assertTrue((self.sd / 'baidu').is_dir())


class Contracts(unittest.TestCase):
    def test_never_scans_recursively(self):
        for number, raw in enumerate(SCRIPT.read_text(encoding='utf-8').splitlines(), 1):
            line = '' if raw.lstrip().startswith('#') else raw
            self.assertIsNone(SCAN.search(line), f'root-tidy.sh:{number}: {raw}')
            self.assertNotIn('rm -r', line)

    def test_runs_inside_maintenance_window_only(self):
        text = (SCRIPTS / 'storage-maintenance.sh').read_text(encoding='utf-8')
        call = text.index('root-tidy.sh" run')
        for gate in ('BOOT_SETTLE_SECONDS', 'is_charging || exit 0', 'is_screen_off || exit 0', 'ionice -c 3', 'run.lock'):
            self.assertLess(text.index(gate), call, gate)
        self.assertIn('config_value root_tidy_auto)" = 1', text)
        self.assertNotIn('root-tidy', (ROOT / 'v2/module/service.sh').read_text(encoding='utf-8'))
        self.assertIn('root_tidy_auto=0', (ROOT / 'config/default.conf').read_text(encoding='utf-8'))
        script = SCRIPT.read_text(encoding='utf-8')
        self.assertLess(script.index('root_tidy_auto'), script.index('renice'))
        self.assertIn('MAX_ACTIONS=32', script); self.assertIn('MAX_ENTRIES=512', script)


if __name__ == '__main__':
    unittest.main()
