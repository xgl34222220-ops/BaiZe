#!/usr/bin/env python3
"""Storage tools: real native indexes, deep coverage, staged hashes and publication."""
import base64
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class StorageTools(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory(prefix='baize-tools-engine-')
        cls.engine = Path(cls.build.name) / 'baize_engine'
        subprocess.run(['cc', '-std=c11', '-O1', str(ROOT / 'v2/native/baize_engine_42_4.c'), '-o', str(cls.engine)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='baize-storage-tools-')
        self.work = Path(self.temp.name)
        self.media = self.work / 'media'
        self.state = self.work / 'state'
        self.state.mkdir()
        self.config = self.state / 'config.conf'
        self.config.write_text('max_file_mb=256\nshared_index_ttl_seconds=300\n')
        self.env = dict(os.environ, BAIZE_STATE_DIR=str(self.state), BAIZE_MEDIA_ROOT=str(self.media),
                        BAIZE_NATIVE_ENGINE=str(self.engine), BAIZE_SHELL_BIN='/bin/bash',
                        BAIZE_EXTRA_STORAGE_ROOTS=str(self.work / 'no-extra-volumes'),
                        BAIZE_ORGANIZER_CATEGORIES=str(ROOT / 'config/organizer-categories.conf'))

    def tearDown(self):
        self.temp.cleanup()

    def make(self, path, data=b'x'):
        target = self.media / '0' / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return target

    def run_tool(self, name, *args, success=True):
        result = subprocess.run(['bash', str(ROOT / 'v2/module' / name), *map(str, args)],
                                env=self.env, capture_output=True, text=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def report(self, name):
        return [line.split('\t') for line in (self.state / 'reports' / name).read_text().splitlines()[1:]]

    def test_storage_analysis_deep_case_empty_and_dotted_directory(self):
        self.make('DCIM/Camera/year/Photo.JPG', b'12345')
        self.make('DCIM/Camera/year/another.jpg', b'123')
        self.make('custom.folder/no-extension', b'ab')
        self.make('Download/空文件.txt', b'')
        self.make('Download/name\tline\n.PDF', b'1234')
        self.run_tool('storage-analyzer.sh')
        rows = {r[0]: tuple(map(int, r[1:])) for r in self.report('storage-analysis.tsv')}
        self.assertEqual(rows, {'jpg': (2, 8), '(无扩展名)': (1, 2), 'pdf': (1, 4), '(空文件)': (1, 0)})

    def test_large_threshold_uses_size_table_and_validates_input(self):
        big = self.make('Pictures/nested/big.jpg')
        with big.open('wb') as f:
            f.truncate(2 * 1024 * 1024)
        self.make('Pictures/small.jpg')
        self.run_tool('large-file-scanner.sh', 1)
        rows = self.report('large-files.tsv')
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][2], str(big))
        previous = (self.state / 'reports/large-files.tsv').read_bytes()
        self.assertEqual(self.run_tool('large-file-scanner.sh', 'bad', success=False).returncode, 2)
        self.assertEqual((self.state / 'reports/large-files.tsv').read_bytes(), previous)

    def test_duplicate_groups_require_full_hash_and_handle_unusual_names(self):
        prefix = b'a' * 65536
        self.make('Download/first.bin', prefix + b'first')
        self.make('Download/copy\n\t.bin', prefix + b'first')
        self.make('Download/same-prefix-different.bin', prefix + b'other')
        self.make('Download/second.bin', b'z' * (65536 + 5))
        self.make('Download/second-copy.bin', b'z' * (65536 + 5))
        self.make('Download/no-match.bin', b'q' * (65536 + 5))
        self.make('Download/unique-size.bin', b'only size')
        self.run_tool('duplicate-scanner.sh')
        rows = self.report('duplicates.tsv')
        self.assertEqual(len(rows), 2)
        self.assertEqual(len({r[0] for r in rows}), 2)
        self.assertTrue(all(len(r) == 5 and len(r[2]) == 64 for r in rows))
        self.assertNotIn('same-prefix-different', repr(rows))
        self.assertNotIn('no-match', repr(rows))
        self.assertEqual(len(list(self.media.rglob('*.bin'))), 7)

    def test_failed_hash_preserves_previous_report(self):
        self.make('Download/a.bin', b'content')
        self.make('Download/b.bin', b'content')
        self.run_tool('duplicate-scanner.sh')
        report = self.state / 'reports/duplicates.tsv'
        before = report.read_bytes()
        fake = self.work / 'fake'
        fake.mkdir()
        (fake / 'head').write_text('#!/bin/sh\nexit 5\n')
        (fake / 'head').chmod(0o755)
        self.env['PATH'] = str(fake) + ':' + os.environ['PATH']
        self.assertEqual(self.run_tool('duplicate-scanner.sh', success=False).returncode, 5)
        self.assertEqual(report.read_bytes(), before)

    def test_diagnostic_runs_after_cancel_without_clearing_another_task_stop(self):
        self.make('Download/a.pdf', b'content')
        self.make('Download/b.pdf', b'content')
        # Covers both a stale stop and one still being consumed by an active
        # worker: the diagnostic must never unlink or overwrite this request.
        stop = self.state / 'stop'
        stop.write_bytes(b'cancel-active-cleaner')
        before = stop.stat()
        for script in ('duplicate-scanner.sh', 'large-file-scanner.sh', 'storage-analyzer.sh'):
            with self.subTest(script=script):
                self.run_tool(script)
                self.assertEqual(stop.read_bytes(), b'cancel-active-cleaner')
                self.assertEqual(stop.stat().st_ino, before.st_ino)
                self.assertEqual(stop.stat().st_mtime_ns, before.st_mtime_ns)
        private_stop = self.work / 'private-diagnostic-stop'
        private_stop.touch()
        self.env['BAIZE_DIAGNOSTIC_STOP_FILE'] = str(private_stop)
        self.assertEqual(self.run_tool('duplicate-scanner.sh', success=False).returncode, 9)
        self.assertEqual(stop.read_bytes(), b'cancel-active-cleaner')

    def test_ttl_invalidates_when_threshold_or_side_index_changes(self):
        self.make('Download/big.pdf', b'x' * (1024 * 1024))
        self.run_tool('storage-index.sh', 'refresh', 'manual')
        self.assertEqual((self.state / 'index/large-files.nul').read_bytes(), b'')
        self.config.write_text('max_file_mb=1\nshared_index_ttl_seconds=300\n')
        self.run_tool('storage-index.sh', 'ensure', 'manual')
        self.assertIn(b'big.pdf', (self.state / 'index/large-files.nul').read_bytes())
        (self.state / 'index/empty-files.nul').unlink()
        self.run_tool('storage-index.sh', 'ensure', 'manual')
        self.assertTrue((self.state / 'index/empty-files.nul').is_file())


if __name__ == '__main__':
    unittest.main()
