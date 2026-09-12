#!/usr/bin/env python3
"""Real package snapshots: broad coverage, retention, boundaries and deletion."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]

class PackageCoverage(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory(prefix='baize-apk-')
        self.root = Path(self.work.name)
        self.module = self.root / 'module'
        self.module.mkdir()
        self.state = self.root / 'state'
        self.state.mkdir()
        self.media = self.root / 'media'
        self.sd = self.root / 'sd'
        self.sd.mkdir()
        for name in ('apk-snapshot-scan.sh', 'apk-snapshot-clean.sh', 'apk-paths.sh', 'whitelist-match.sh'):
            shutil.copy(ROOT / 'v2/module' / name, self.module / name)
        (self.state / 'config.conf').write_text('apk_package_days=30\napk_package_max_mb=4096\n')
        (self.state / 'whitelist.conf').touch()
        self.env = dict(os.environ, BAIZE_STATE_DIR=str(self.state), BAIZE_MEDIA_ROOT=str(self.media), BAIZE_EXTRA_STORAGE_ROOTS=str(self.sd))

    def tearDown(self):
        self.work.cleanup()

    def make(self, relative, age=0):
        path = self.media / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'package payload')
        stamp = time.time() - age * 86400
        os.utime(path, (stamp, stamp))
        return path

    def run_task(self, mode, trigger='manual'):
        proc = subprocess.run(['bash', str(self.module / f'apk-snapshot-{mode}.sh'), f'apk-{mode}', trigger], env=self.env, text=True, capture_output=True)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        return proc

    def targets(self):
        return set((self.state / 'apk_scan.targets').read_bytes().split(b'\0')) - {b''}

    def test_manual_120_packages_are_all_deleted_across_real_sources(self):
        roots = ['0/Downloads/a/b/c/d/e/f', '0/UCDownloads', '0/Quark/Download',
                 '10/Android/data/com.coolapk.market/cache/apk',
                 '0/Android/media/org.telegram/Telegram/Telegram Documents', '0/其他应用/安装包']
        packages = [self.make(f'{roots[i % len(roots)]}/中文 空格{i}.APK') for i in range(120)]
        sd_apk = self.sd / 'external.apkm'
        sd_apk.write_bytes(b'pkg')
        packages.append(sd_apk)
        keep = self.make('0/Download/downloading.apk.part')
        self.run_task('scan')
        self.assertEqual(self.targets(), {os.fsencode(p) for p in packages})
        self.run_task('clean')
        self.assertTrue(all(not p.exists() for p in packages))
        self.assertTrue(keep.exists())
        self.assertIn('files=121\n', (self.state / 'latest.env').read_text())

    def test_retention_whitelist_and_symlink_boundaries(self):
        old = self.make('0/Download/old.apk', 31)
        recent = self.make('0/Download/new.apk', 1)
        protected = self.make('0/Download/keep.apk', 40)
        outside = self.root / 'installed.apk'
        outside.write_bytes(b'installed')
        (old.parent / 'link.apk').symlink_to(outside)
        (self.state / 'whitelist.conf').write_text(str(protected) + '\n')
        self.run_task('scan', 'scheduler:interval')
        self.assertEqual(self.targets(), {os.fsencode(old)})
        self.run_task('clean')
        self.assertFalse(old.exists())
        self.assertTrue(recent.exists() and protected.exists() and outside.exists())
        self.run_task('scan')
        self.assertEqual(self.targets(), {os.fsencode(recent)})

    def test_replaced_directory_and_new_file_after_scan_are_not_deleted(self):
        package = self.make('0/Downloads/nested/original.apk')
        self.run_task('scan')
        package.unlink()
        package.parent.rmdir()
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / package.name).write_bytes(b'do not delete')
        package.parent.symlink_to(outside, target_is_directory=True)
        new = self.make('0/Downloads/new.apk')
        self.run_task('clean')
        self.assertTrue((outside / package.name).exists() and new.exists())

if __name__ == '__main__':
    unittest.main()
