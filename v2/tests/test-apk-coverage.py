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
        self.data = self.root / 'data'
        self.data.mkdir()
        self.public = self.root / 'public'
        self.public.mkdir()
        self.sd = self.root / 'sd'
        self.sd.mkdir()
        for name in ('apk-snapshot-scan.sh', 'apk-snapshot-clean.sh', 'apk-paths.sh', 'whitelist-match.sh'):
            shutil.copy(ROOT / 'v2/module' / name, self.module / name)
        (self.state / 'config.conf').write_text('apk_package_days=30\napk_package_max_mb=4096\n')
        (self.state / 'whitelist.conf').touch()
        self.env = dict(
            os.environ,
            BAIZE_STATE_DIR=str(self.state),
            BAIZE_MEDIA_ROOT=str(self.media),
            BAIZE_DATA_ROOT=str(self.data),
            BAIZE_PUBLIC_MEDIA_ROOT=str(self.public),
            BAIZE_EXTRA_STORAGE_ROOTS=str(self.sd),
        )

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
        coverage = [line.split('\t') for line in (self.state / 'apk-coverage.tsv').read_text().splitlines()[1:]]
        self.assertEqual(sum(int(row[4]) for row in coverage), len(packages))
        self.assertEqual(sum(int(row[5]) for row in coverage), sum(p.stat().st_size for p in packages))
        self.run_task('clean')
        self.assertTrue(all(not p.exists() for p in packages))
        self.assertTrue(keep.exists())
        self.assertIn('files=121\n', (self.state / 'latest.env').read_text())

    def test_public_emulated_fallback_when_raw_media_is_missing(self):
        missing_raw = self.root / 'missing-media'
        public_apk = self.public / '0/Download/public.apk'
        public_apk.parent.mkdir(parents=True, exist_ok=True)
        public_apk.write_bytes(b'public package')
        env = dict(self.env, BAIZE_MEDIA_ROOT=str(missing_raw))
        proc = subprocess.run(
            ['bash', str(self.module / 'apk-snapshot-scan.sh'), 'apk-scan', 'app'],
            env=env, text=True, capture_output=True
        )
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(
            set((self.state / 'apk_scan.targets').read_bytes().split(b'\0')) - {b''},
            {os.fsencode(public_apk)}
        )

    def test_root_bruteforce_fallback_when_normal_discovery_is_zero(self):
        brute = self.root / 'brute-only'
        package = brute / 'deep/vendor/downloads/recovered.apk'
        package.parent.mkdir(parents=True, exist_ok=True)
        package.write_bytes(b'brute package')
        env = dict(
            self.env,
            BAIZE_MEDIA_ROOT=str(self.root / 'missing-raw'),
            BAIZE_PUBLIC_MEDIA_ROOT=str(self.root / 'missing-public'),
            BAIZE_EXTRA_STORAGE_ROOTS=str(self.sd),
            BAIZE_BRUTE_STORAGE_ROOTS=str(brute),
        )
        proc = subprocess.run(
            ['bash', str(self.module / 'apk-snapshot-scan.sh'), 'apk-scan', 'app'],
            env=env, text=True, capture_output=True
        )
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        targets = set((self.state / 'apk_scan.targets').read_bytes().split(b'\0')) - {b''}
        self.assertEqual(targets, {os.fsencode(package)})
        state = (self.state / 'apk_scan.env').read_text()
        self.assertIn('brute_force_used=1\n', state)
        self.assertIn('raw_candidates=1\n', state)
        coverage = (self.state / 'apk-coverage.tsv').read_text()
        self.assertIn('Root兜底 1', coverage)

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

    def private_package(self, relative, age=0):
        path = self.data / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'private package payload')
        stamp = time.time() - age * 86400
        os.utime(path, (stamp, stamp))
        return path

    def test_manual_scan_includes_private_app_cache_and_files_but_scheduler_does_not(self):
        private_cache = self.private_package('user/0/com.example.browser/cache/update.apk', 40)
        private_file = self.private_package('user/0/com.example.chat/files/downloads/share.apks', 40)
        private_de = self.private_package('user_de/0/com.example.installer/code_cache/staged.apkm', 40)
        shared = self.make('0/Download/shared.apk', 40)

        self.run_task('scan', 'app')
        self.assertEqual(
            self.targets(),
            {os.fsencode(private_cache), os.fsencode(private_file), os.fsencode(private_de), os.fsencode(shared)}
        )
        state = (self.state / 'apk_scan.env').read_text()
        self.assertIn('include_private=1\n', state)
        self.run_task('clean', 'app')
        self.assertTrue(all(not path.exists() for path in (private_cache, private_file, private_de, shared)))

        private_cache = self.private_package('user/0/com.example.browser/cache/again.apk', 40)
        shared = self.make('0/Download/scheduled.apk', 40)
        self.run_task('scan', 'scheduler:interval')
        self.assertEqual(self.targets(), {os.fsencode(shared)})
        self.assertTrue(private_cache.exists())
        self.assertIn('include_private=0\n', (self.state / 'apk_scan.env').read_text())

    def test_same_path_replacement_and_restored_mtime_are_protected(self):
        replaced = self.make('0/Download/replaced.apk', 3)
        modified = self.make('0/Download/modified.apk', 3)
        expected = {p: p.stat() for p in (replaced, modified)}
        self.run_task('scan')
        replacement = replaced.with_suffix('.new')
        replacement.write_bytes(b'package payload')
        os.replace(replacement, replaced)
        modified.write_bytes(b'changed payload')
        for path, before in expected.items():
            os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
        result = subprocess.run(
            ['bash', str(self.module / 'apk-snapshot-clean.sh'), 'apk-clean', 'manual'],
            env=self.env, text=True, capture_output=True
        )
        self.assertEqual(result.returncode, 8, result.stdout + result.stderr)
        self.assertTrue(replaced.exists() and modified.exists())
        self.assertIn('skipped=2\n', (self.state / 'latest.env').read_text())
        self.assertIn('未完全生效', result.stdout)

    def test_tampered_or_missing_identity_snapshot_refuses_deletion(self):
        package = self.make('0/Download/keep.apk')
        self.run_task('scan')
        (self.state / 'apk_scan.identities').write_bytes(b'changed\0')
        result = subprocess.run(['bash', str(self.module / 'apk-snapshot-clean.sh'), 'apk-clean'],
                                env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
        self.assertTrue(package.exists())

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
        result = subprocess.run(
            ['bash', str(self.module / 'apk-snapshot-clean.sh'), 'apk-clean', 'manual'],
            env=self.env, text=True, capture_output=True
        )
        self.assertEqual(result.returncode, 8, result.stdout + result.stderr)
        self.assertTrue((outside / package.name).exists() and new.exists())

if __name__ == '__main__':
    unittest.main()
