#!/usr/bin/env python3
"""Fault-injected native cleanup and real module-wrapper outcome regressions.

All data lives in newly allocated /tmp fixtures. The deep test translation unit
maps only its fixed /data/media/ allow-list prefix to /tmp/baize_/; it includes
the production source unchanged and adds no production runtime escape hatch.
"""
import errno
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
SHIM = r'''
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
static unsigned calls;
static int fault(const char *op, const char *path) {
    const char *wanted = getenv("TEST_FAULT_OP");
    const char *name = getenv("TEST_FAULT_NAME");
    const char *base = strrchr(path, '/'); base = base ? base + 1 : path;
    if (!wanted || !name || strcmp(op, wanted) || strcmp(base, name)) return 0;
    unsigned after = getenv("TEST_FAULT_AFTER") ? (unsigned)atoi(getenv("TEST_FAULT_AFTER")) : 1U;
    if (++calls < after) return 0;
    errno = atoi(getenv("TEST_FAULT_ERRNO"));
    return 1;
}
int lstat(const char *path, struct stat *st) {
    int (*real)(const char *, struct stat *) = dlsym(RTLD_NEXT, "lstat");
    return fault("lstat", path) ? -1 : real(path, st);
}
int fstatat(int fd, const char *path, struct stat *st, int flags) {
    int (*real)(int, const char *, struct stat *, int) = dlsym(RTLD_NEXT, "fstatat");
    return fault("fstatat", path) ? -1 : real(fd, path, st, flags);
}
int unlink(const char *path) {
    int (*real)(const char *) = dlsym(RTLD_NEXT, "unlink");
    return fault("unlink", path) ? -1 : real(path);
}
int unlinkat(int fd, const char *path, int flags) {
    int (*real)(int, const char *, int) = dlsym(RTLD_NEXT, "unlinkat");
    return fault("unlinkat", path) ? -1 : real(fd, path, flags);
}
int openat(int fd, const char *path, int flags, ...) {
    int (*real)(int, const char *, int, ...) = dlsym(RTLD_NEXT, "openat");
    if (fault("openat", path)) return -1;
    if (flags & O_CREAT) {
        va_list args; va_start(args, flags); mode_t mode = va_arg(args, mode_t); va_end(args);
        return real(fd, path, flags, mode);
    }
    return real(fd, path, flags);
}
'''


def env_file(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class NativeCleanOutcomes(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix='baize-outcomes-')
        cls.work = Path(cls.workspace.name)
        cls.fixture_parent = Path('/tmp/baize_')
        cls.fixture_parent.mkdir(exist_ok=True)
        cls.deep_workspace = tempfile.TemporaryDirectory(prefix='outcomes-', dir=cls.fixture_parent)
        cls.deep_root = Path(cls.deep_workspace.name)
        cls.engine, cls.deep, cls.shim = [cls.work / name for name in ('engine', 'deep', 'faults.so')]
        cls.compile(ROOT / 'v2/native/baize_engine_42_4.c', cls.engine)
        harness = cls.work / 'deep-harness.c'
        harness.write_text('#define _GNU_SOURCE\n#include <string.h>\n'
                           'static int fixture_strncmp(const char *a, const char *b, size_t n) {\n'
                           '  if (!strcmp(b, "/data/media/")) return strncmp(a, "/tmp/baize_/", 12U);\n'
                           '  return strncmp(a, b, n);\n}\n#define strncmp fixture_strncmp\n'
                           f'#include "{ROOT / "v2/native/baize_deep_snapshot.c"}"\n')
        cls.compile(harness, cls.deep)
        shim_source = cls.work / 'faults.c'
        shim_source.write_text(SHIM)
        subprocess.run(['cc', '-shared', '-fPIC', '-O2', str(shim_source), '-ldl', '-o', str(cls.shim)], check=True)

    @classmethod
    def compile(cls, source, target):
        subprocess.run(['cc', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                        '-Wno-misleading-indentation', '-Wno-unused-result',
                        str(source), '-o', str(target)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.deep_workspace.cleanup()
        cls.workspace.cleanup()

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(dir=self.work))
        self.state = self.tmp / 'state'
        self.state.mkdir()
        self.data = self.tmp / 'data'
        self.media = self.data / 'media'
        self.media.mkdir(parents=True)
        self.whitelist = self.state / 'whitelist.conf'
        self.whitelist.write_text('')
        self.packages = self.state / 'native-cache-packages.conf'
        self.packages.write_text('')
        self.env = {**os.environ, 'BAIZE_STATE_DIR': str(self.state),
                    'BAIZE_DATA_ROOT': str(self.data), 'BAIZE_MEDIA_ROOT': str(self.media),
                    'BAIZE_NATIVE_ENGINE': str(self.engine), 'BAIZE_DEEP_SNAPSHOT_ENGINE': str(self.deep)}

    def run_command(self, args, fault=None):
        env = dict(self.env)
        if fault:
            op, name, number, *after = fault
            env.update(LD_PRELOAD=str(self.shim), TEST_FAULT_OP=op, TEST_FAULT_NAME=name,
                       TEST_FAULT_ERRNO=str(number), TEST_FAULT_AFTER=str(after[0] if after else 1))
        return subprocess.run(list(map(str, args)), env=env, text=True, capture_output=True, timeout=20)

    def cache_fixture(self, names=('fault.bin',)):
        root = self.media / '0/Android/data/com.example.fixture/cache'
        root.mkdir(parents=True)
        files = {}
        for name in names:
            files[name] = root / name
            files[name].write_bytes(b'payload')
        prefix = self.state / 'cache_scan'
        args = [self.engine, 'scan-cache', '--data-root', self.data, '--media-root', self.media,
                '--whitelist', self.whitelist, '--package-whitelist', self.packages,
                '--report', self.state / 'scan.tsv', '--targets', str(prefix) + '.targets',
                '--items', str(prefix) + '.items.tsv', '--manifest', str(prefix) + '.manifest0',
                '--summary', self.state / 'scan.env']
        result = self.run_command(args)
        self.assertEqual(0, result.returncode, result.stderr)
        values = {'epoch': int(time.time()), 'snapshot_id': 'synthetic-cache',
                  'manifest_format': 'nul-v2', 'manifest_items': len(files), 'files': len(files),
                  'bytes': len(files) * 7, 'max_file_bytes': 1048576,
                  'whitelist_sha': sha(self.whitelist), 'package_whitelist_sha': sha(self.packages)}
        for key, suffix in (('targets_sha', '.targets'), ('items_sha', '.items.tsv'), ('manifest_sha', '.manifest0')):
            values[key] = sha(Path(str(prefix) + suffix))
        (self.state / 'cache_scan.env').write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
        return root, files

    def cache_clean(self, fault=None, wrapper=False):
        if wrapper:
            args = ['bash', ROOT / 'v2/module/scripts/cache-snapshot-clean.sh', 'cache-clean', 'test']
        else:
            args = [self.engine, 'clean-cache-snapshot', '--data-root', self.data,
                    '--media-root', self.media, '--whitelist', self.whitelist,
                    '--package-whitelist', self.packages, '--manifest', self.state / 'cache_scan.manifest0',
                    '--report', self.state / 'clean.tsv', '--summary', self.state / 'clean.env']
        return self.run_command(args, fault)

    def deep_fixture(self, names=('fault.bin',)):
        root = Path(tempfile.mkdtemp(dir=self.deep_root)) / 'cache'
        root.mkdir()
        for name in names:
            (root / name).write_bytes(b'payload')
        path = root / names[0]
        (self.state / 'deep_scan.targets').write_text(f'{root}\tlow\n')
        (self.state / 'rules.conf').write_text(f'{root}|low\n')
        result = self.run_command([self.deep, 'build', '--targets', self.state / 'deep_scan.targets',
                                  '--manifest', self.state / 'deep_scan.manifest0',
                                  '--summary', self.state / 'deep_scan.manifest.env'])
        self.assertEqual(0, result.returncode, result.stderr)
        (self.state / 'deep_scan.cursor').write_text('0\n')
        values = {'epoch': int(time.time()), 'snapshot_id': 'synthetic-deep', 'max_file_bytes': 1048576,
                  'targets_sha': sha(self.state / 'deep_scan.targets'), 'whitelist_sha': sha(self.whitelist),
                  'rules_sha': sha(self.state / 'rules.conf'), 'manifest_sha': sha(self.state / 'deep_scan.manifest0')}
        (self.state / 'deep_scan.env').write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
        self.env['BAIZE_DEEP_RULES'] = str(self.state / 'rules.conf')
        return root, path

    def deep_clean(self, fault=None, wrapper=False):
        args = (['bash', ROOT / 'v2/module/scripts/deep-manifest-clean.sh', 'deep-clean', 'test'] if wrapper else
                [self.deep, 'clean', '--manifest', self.state / 'deep_scan.manifest0',
                 '--cursor', self.state / 'deep_scan.cursor', '--whitelist', self.whitelist,
                 '--report', self.state / 'clean.tsv', '--summary', self.state / 'clean.env'])
        return self.run_command(args, fault)

    def test_cache_unknown_stat_errors_are_not_missing(self):
        _, files = self.cache_fixture()
        for number in (errno.EACCES, errno.EIO, errno.ENOTDIR):
            for after in (1, 2):
                with self.subTest(number=number, after=after):
                    result = self.cache_clean(('lstat', 'fault.bin', number, after))
                    self.assertEqual(8, result.returncode, result.stdout + result.stderr)
                    self.assertNotIn('missing\t', (self.state / 'clean.tsv').read_text())
                    self.assertEqual('1', env_file(self.state / 'clean.env')['errors'])
                    self.assertTrue(files['fault.bin'].exists())

    def test_cache_enoent_is_missing_without_error_or_deletion_credit(self):
        _, files = self.cache_fixture()
        files['fault.bin'].unlink()
        result = self.cache_clean()
        self.assertEqual(0, result.returncode, result.stderr)
        summary = env_file(self.state / 'clean.env')
        self.assertEqual(('0', '0'), (summary['files'], summary['errors']))
        self.assertIn('missing\t', (self.state / 'clean.tsv').read_text())

    def test_cache_wrapper_failure_retains_snapshot_and_retry_scope(self):
        root, files = self.cache_fixture(('fault.bin', 'unchanged.bin'))
        result = self.cache_clean(('unlink', 'fault.bin', errno.EACCES), wrapper=True)
        self.assertEqual(8, result.returncode, result.stdout + result.stderr)
        self.assertTrue((self.state / 'cache_scan.manifest0').exists())
        self.assertTrue((self.state / 'cache_scan.env').exists())
        self.assertTrue(files['fault.bin'].exists())
        self.assertFalse(files['unchanged.bin'].exists())
        fresh = root / 'new.bin'
        fresh.write_bytes(b'new file must survive retry')
        result = self.cache_clean(wrapper=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(files['fault.bin'].exists())
        self.assertTrue(fresh.exists())

    def test_cache_candidate_units_and_changed_missing_reconcile(self):
        _, files = self.cache_fixture(('one.bin', 'two.bin', 'changed.bin', 'missing.bin'))
        files['changed.bin'].write_bytes(b'changed after scan')
        files['missing.bin'].unlink()
        result = self.cache_clean(wrapper=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        latest = env_file(self.state / 'latest.env')
        self.assertEqual('1', latest['authorized_candidates'])
        self.assertEqual('1', latest['processed_candidates'])
        self.assertEqual('1', latest['partial_candidates'])
        self.assertEqual('0', latest['cleaned_candidates'])
        self.assertEqual('1', latest['changed_files'])
        self.assertEqual('1', latest['missing_files'])
        self.assertEqual('2', latest['files'])

    def test_cache_complete_root_counts_once_and_all_missing_is_not_cleaned(self):
        _, files = self.cache_fixture(('one.bin', 'two.bin'))
        result = self.cache_clean(wrapper=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        latest = env_file(self.state / 'latest.env')
        self.assertEqual(('1', '1', '1', '2'), tuple(latest[key] for key in
                         ('authorized_candidates', 'processed_candidates', 'cleaned_candidates', 'files')))
        # Build another independent snapshot, then make it genuinely absent.
        self.media = self.data / 'other-media'
        self.media.mkdir()
        self.env['BAIZE_MEDIA_ROOT'] = str(self.media)
        _, files = self.cache_fixture(('one.bin', 'two.bin'))
        for path in files.values(): path.unlink()
        result = self.cache_clean(wrapper=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        latest = env_file(self.state / 'latest.env')
        self.assertEqual(('1', '1', '0', '0'), tuple(latest[key] for key in
                         ('processed_candidates', 'missing_candidates', 'cleaned_candidates', 'files')))

    def test_deep_stat_errors_do_not_advance_retry_cursor(self):
        _, path = self.deep_fixture()
        for number in (errno.EACCES, errno.EIO, errno.ENOTDIR):
            for after in (1, 2):
                with self.subTest(number=number, after=after):
                    result = self.deep_clean(('fstatat', 'fault.bin', number, after))
                    self.assertEqual(8, result.returncode, result.stdout + result.stderr)
                    self.assertNotIn('missing\t', (self.state / 'clean.tsv').read_text())
                    summary = env_file(self.state / 'clean.env')
                    self.assertEqual('0', summary['cursor'])
                    self.assertEqual('1', summary['errors'])
                    self.assertTrue(path.exists())

    def test_deep_enoent_is_missing_without_error_or_deletion_credit(self):
        root, path = self.deep_fixture()
        path.unlink()
        (root / 'new.bin').write_bytes(b'preserve')
        result = self.deep_clean()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        summary = env_file(self.state / 'clean.env')
        self.assertEqual(('0', '0'), (summary['files'], summary['errors']))
        self.assertIn('missing\t', (self.state / 'clean.tsv').read_text())

    def test_deep_wrapper_unlink_failure_retains_and_resumes_exact_record(self):
        root, path = self.deep_fixture()
        result = self.deep_clean(('unlinkat', 'fault.bin', errno.EACCES), wrapper=True)
        self.assertEqual(8, result.returncode, result.stdout + result.stderr)
        self.assertTrue(path.exists())
        self.assertTrue((self.state / 'deep_scan.manifest0').exists())
        self.assertTrue((self.state / 'deep_scan.env').exists())
        latest = env_file(self.state / 'latest.env')
        self.assertEqual('0', latest['deep_manifest_cursor'])
        self.assertGreater(int(latest['deep_remaining_records']), 0)
        fresh = root / 'new.bin'
        fresh.write_bytes(b'preserve')
        result = self.deep_clean(wrapper=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(path.exists())
        self.assertTrue(fresh.exists())
        latest = env_file(self.state / 'latest.env')
        self.assertEqual('1', latest['files'])
        self.assertEqual('0', latest['errors'])

    def test_deep_failure_after_success_commits_only_successful_prefix(self):
        root, _ = self.deep_fixture(('one.bin', 'two.bin'))
        fields = (self.state / 'deep_scan.manifest0').read_bytes().split(b'\0')[:-1]
        paths = [Path(os.fsdecode(fields[i + 10])) for i in range(0, len(fields), 11)
                 if fields[i] == b'file']
        self.assertEqual(2, len(paths))
        result = self.deep_clean(('unlinkat', paths[1].name, errno.EIO))
        self.assertEqual(8, result.returncode, result.stdout + result.stderr)
        summary = env_file(self.state / 'clean.env')
        self.assertEqual(('1', '1', '7', '1'), tuple(summary[key] for key in ('cursor', 'files', 'bytes', 'errors')))
        self.assertFalse(paths[0].exists())
        self.assertTrue(paths[1].exists())
        fresh = root / 'new.bin'
        fresh.write_bytes(b'preserve')
        result = self.deep_clean()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        summary = env_file(self.state / 'clean.env')
        self.assertEqual(('2', '14', '0'), tuple(summary[key] for key in ('files', 'bytes', 'errors')))
        self.assertTrue(fresh.exists())

    def test_deep_parent_and_directory_failures_are_retryable(self):
        for operation, name, expected_cursor in (('openat', 'cache', '0'),
                                                  ('fstatat', 'cache', '1'),
                                                  ('unlinkat', 'cache', '1')):
            with self.subTest(operation=operation):
                root, _ = self.deep_fixture()
                result = self.deep_clean((operation, name, errno.EACCES))
                self.assertEqual(8, result.returncode, result.stdout + result.stderr)
                summary = env_file(self.state / 'clean.env')
                self.assertEqual(expected_cursor, summary['cursor'])
                self.assertEqual('1', summary['errors'])
                self.assertNotIn('missing\t', (self.state / 'clean.tsv').read_text())
                self.assertTrue(root.exists())
                result = self.deep_clean()
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertFalse(root.exists())

    def test_deep_known_ancestor_symlink_stays_protected(self):
        root, path = self.deep_fixture()
        moved = root.with_name('moved')
        root.rename(moved)
        root.symlink_to(moved, target_is_directory=True)
        result = self.deep_clean()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue((moved / path.name).exists())
        summary = env_file(self.state / 'clean.env')
        self.assertEqual(('0', '0'), (summary['files'], summary['errors']))
        self.assertIn('protected\t', (self.state / 'clean.tsv').read_text())


if __name__ == '__main__':
    unittest.main(verbosity=2)
