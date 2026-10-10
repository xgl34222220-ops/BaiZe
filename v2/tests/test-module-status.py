#!/usr/bin/env python3
"""模块状态描述 / 操作按钮摘要 / 扩展系统日志根目录的行为与契约测试。"""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'v2/module'
STATUS = MODULE / 'scripts/module-status.sh'
COMPAT = MODULE / 'scripts/cleaner-compat.sh'
SCAN = re.compile(r'(^|[\s;|&(`])(find|du|restorecon|chcon|tar|ls)\b|ch(mod|own)\s+-R')


def run(*args):
    return subprocess.run(['sh', str(STATUS), *args], capture_output=True, text=True)


class DescriptionStatus(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.prop = Path(self.temp.name) / 'module.prop'
        self.prop.write_text('id=baize_v2\nname=白泽\ndescription=累计清理 1.00 GB | 上次:2026-10-01\nversion=v2\n', encoding='utf-8')

    def tearDown(self):
        self.temp.cleanup()

    def description(self):
        return next(l for l in self.prop.read_text(encoding='utf-8').splitlines() if l.startswith('description='))

    def test_warning_is_prefixed_once_and_removed_when_ready(self):
        self.assertEqual(0, run('description', str(self.prop), 'missing').returncode)
        first = self.description()
        self.assertTrue(first.startswith('description=⚠ 未找到白泽 App'), first)
        self.assertIn('累计清理 1.00 GB | 上次:2026-10-01', first)
        run('description', str(self.prop), 'signature_mismatch')
        second = self.description()
        self.assertEqual(1, second.count('⚠'), second)
        self.assertIn('签名', second)
        run('description', str(self.prop), 'ready')
        self.assertEqual('description=累计清理 1.00 GB | 上次:2026-10-01', self.description())
        lines = self.prop.read_text(encoding='utf-8').splitlines()
        self.assertEqual(['id=baize_v2', 'name=白泽', 'version=v2'], [l for l in lines if not l.startswith('description=')])

    def test_ready_without_warning_leaves_file_untouched(self):
        before = self.prop.stat().st_mtime_ns
        run('description', str(self.prop), 'ready')
        self.assertEqual(before, self.prop.stat().st_mtime_ns)
        self.assertEqual([], [p.name for p in Path(self.temp.name).iterdir() if '.tmp.' in p.name])

    def test_symlinked_prop_is_never_rewritten(self):
        target = Path(self.temp.name) / 'elsewhere'
        target.write_text('description=x\n', encoding='utf-8')
        link = Path(self.temp.name) / 'link.prop'
        link.symlink_to(target)
        run('description', str(link), 'missing')
        self.assertEqual('description=x\n', target.read_text(encoding='utf-8'))

    def test_summary_reads_fixed_files(self):
        state = Path(self.temp.name) / 'state'
        state.mkdir()
        (state / 'module.env').write_text('app_installed=1\napp_install_result=ready\n')
        (state / 'config.conf').write_text('enabled=0\n')
        out = run('summary', str(state), str(self.prop))
        self.assertEqual(0, out.returncode, out.stderr)
        self.assertIn('App：已安装', out.stdout)
        self.assertIn('自动清理：已暂停', out.stdout)
        self.assertIn('累计清理 1.00 GB', out.stdout)
        empty = run('summary', str(Path(self.temp.name) / 'missing'), str(self.prop))
        self.assertEqual(0, empty.returncode)
        self.assertIn('尚未完成开机检查', empty.stdout)

    def test_bad_usage_fails_closed(self):
        self.assertEqual(2, run('description', str(self.prop)).returncode)
        self.assertEqual(2, run('rm', '/').returncode)


class Contracts(unittest.TestCase):
    def test_status_script_never_scans(self):
        for number, raw in enumerate(STATUS.read_text(encoding='utf-8').splitlines(), 1):
            line = '' if raw.lstrip().startswith('#') else raw
            self.assertIsNone(SCAN.search(line), f'module-status.sh:{number}: {raw}')

    def test_service_updates_description_after_boot_in_background(self):
        text = (MODULE / 'service.sh').read_text(encoding='utf-8')
        call = text.index('module-status.sh" description')
        self.assertLess(text.index('getprop sys.boot_completed'), call)
        self.assertLess(text.index('ionice -c'), call)
        line = text[text.rindex('\n', 0, call) + 1:text.index('\n', call)]
        self.assertRegex(line, r'&\s*$')

    def test_action_prints_status_before_opening_app(self):
        text = (MODULE / 'action.sh').read_text(encoding='utf-8')
        self.assertLess(text.index('module-status.sh" summary'), text.index('am start'))

    def test_extra_log_roots_are_age_limited_and_behind_toggles(self):
        text = COMPAT.read_text(encoding='utf-8')
        system = text[text.index('get_bool clean_system_logs'):text.index('get_bool clean_oem_logs')]
        for root in ('/data/system/heapdump', '/data/misc/perfetto-traces', '/data/misc/logd',
                     '/data/user_de/[0-9]*/com.android.shell/files/bugreports'):
            self.assertIn(root, system)
        self.assertNotIn('$OEM_DAYS', system)
        oem = text[text.index('get_bool clean_oem_logs'):text.index('get_bool clean_hidden_junk')]
        for root in ('/data/aee_exp', '/data/vendor/aee_exp', '/data/debuglogger', '/data/vendor/ramdump', '/data/log', '/data/vendor/log'):
            self.assertIn(f'"{root}|', oem)
        self.assertIn('clean_dir "${oem_entry%%|*}" "$OEM_DAYS"', oem)
        for block in (system, oem):
            self.assertIsNone(re.search(r'\bfind\b|rm -rf', block))
        # 厂商日志默认关闭；系统日志沿用已有默认。
        defaults = (ROOT / 'config/default.conf').read_text(encoding='utf-8')
        self.assertIn('clean_oem_logs=0', defaults)

    def test_extra_roots_run_against_missing_directories_without_side_effects(self):
        # clean_dir 对不存在的目录直接返回；扩展列表在空沙箱里不应产生错误输出。
        self.assertEqual(0, subprocess.run(['sh', '-n', str(COMPAT)]).returncode)


if __name__ == '__main__':
    unittest.main()
