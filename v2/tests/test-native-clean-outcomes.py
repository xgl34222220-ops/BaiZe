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
#include <stdio.h>
#include <time.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
static unsigned calls;
static int read_has_expired;
int clock_gettime(clockid_t clock, struct timespec *value) {
    int (*real)(clockid_t,struct timespec*)=dlsym(RTLD_NEXT,"clock_gettime");int rc=real(clock,value);
    if(!rc && clock==CLOCK_MONOTONIC && read_has_expired)value->tv_sec+=20;
    return rc;
}
static int frozen_name(const char *path) {
    const char *name = getenv("TEST_FROZEN_NAME");
    const char *base = strrchr(path, '/'); base = base ? base + 1 : path;
    return name && !strcmp(name, base);
}
static int fd_frozen(int fd) {
    char name[64], path[4096]; snprintf(name,sizeof(name),"/proc/self/fd/%d",fd);
    ssize_t n=readlink(name,path,sizeof(path)-1); if(n<0)return 0;path[n]=0;return frozen_name(path);
}
static void freeze(struct stat *st) {
    st->st_ctim.tv_sec=strtoll(getenv("TEST_FROZEN_CTIME_SEC"),0,10);
    st->st_ctim.tv_nsec=strtol(getenv("TEST_FROZEN_CTIME_NSEC"),0,10);
}
int fstat(int fd,struct stat *st) {
    int (*real)(int,struct stat*)=dlsym(RTLD_NEXT,"fstat");int rc=real(fd,st);
    if(!rc&&fd_frozen(fd))freeze(st);return rc;
}
ssize_t read(int fd,void *buffer,size_t count) {
    ssize_t (*real)(int,void*,size_t)=dlsym(RTLD_NEXT,"read");
    ssize_t n=real(fd,buffer,count);
    if(n>0&&fd_frozen(fd)&&getenv("TEST_EXPIRE_AFTER_READ"))read_has_expired=1;
    if(n>0&&fd_frozen(fd)&&getenv("TEST_CONTENT_READ_LOG")){
        int log=open(getenv("TEST_CONTENT_READ_LOG"),O_WRONLY|O_CREAT|O_APPEND,0600);
        if(log>=0){write(log,"R",1);close(log);}
    }
    return n;
}
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
    int rc=fault("lstat", path) ? -1 : real(path, st); if(!rc&&frozen_name(path))freeze(st); return rc;
}
int fstatat(int fd, const char *path, struct stat *st, int flags) {
    int (*real)(int, const char *, struct stat *, int) = dlsym(RTLD_NEXT, "fstatat");
    int rc=fault("fstatat", path) ? -1 : real(fd, path, st, flags); if(!rc&&frozen_name(path))freeze(st); return rc;
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
                  'manifest_format': 'nul-v3-sha256', 'manifest_items': len(files), 'files': len(files),
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

    def test_personal_android_media_is_protected_in_both_corpse_scanners(self):
        personal = self.media / '0/Android/media/com.removed.app/export.zip'
        personal.parent.mkdir(parents=True)
        personal.write_bytes(b'user export')
        orphan = self.media / '0/Android/obb/com.removed.app/old.obb'
        orphan.parent.mkdir(parents=True)
        orphan.write_bytes(b'obb')
        installed = self.tmp / 'installed'
        installed.mkdir()
        (installed / '0.txt').write_text('android\ncom.installed.app\n')
        common = ['--data-root', self.data, '--media-root', self.media,
                  '--installed-root', installed, '--whitelist', self.whitelist,
                  '--package-whitelist', self.packages]
        for mode in ('scan-corpses', 'scan-external-one-pass'):
            with self.subTest(mode=mode):
                args = [self.engine, mode] + common + ['--report', self.state / 'scan.tsv',
                        '--targets', self.state / 'scan.targets', '--summary', self.state / 'scan.env']
                if mode == 'scan-external-one-pass':
                    args += ['--items', self.state / 'scan.items', '--manifest', self.state / 'scan.manifest0',
                             '--corpse-report', self.state / 'corpse.tsv', '--corpse-targets', self.state / 'corpse.targets',
                             '--corpse-summary', self.state / 'corpse.env']
                result = self.run_command(args)
                self.assertEqual(0, result.returncode, result.stderr)
                prefix = 'corpse' if mode == 'scan-external-one-pass' else 'scan'
                self.assertEqual([str(orphan.parent)], (self.state / (prefix + '.targets')).read_text().splitlines())
                summary = env_file(self.state / (prefix + '.env'))
                self.assertEqual('3', summary['bytes'])
                self.assertIn('个人媒体未确认', (self.state / (prefix + '.tsv')).read_text())
                self.assertEqual(b'user export', personal.read_bytes())

    def pin_old_ctime(self, path, before):
        self.env.update(LD_PRELOAD=str(self.shim), TEST_FROZEN_NAME=path.name,
                        TEST_FROZEN_CTIME_SEC=str(before.st_ctime_ns // 1000000000),
                        TEST_FROZEN_CTIME_NSEC=str(before.st_ctime_ns % 1000000000),
                        TEST_CONTENT_READ_LOG=str(self.state / 'content-read'))

    def test_cache_digest_blocks_changed_bytes_with_all_original_metadata(self):
        _, files = self.cache_fixture()
        path = files['fault.bin']; before = path.stat()
        path.write_bytes(b'changed')
        os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
        self.pin_old_ctime(path, before)
        result = self.cache_clean()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b'changed', path.read_bytes())
        self.assertIn('changed\t', (self.state / 'clean.tsv').read_text())
        self.assertTrue((self.state / 'content-read').read_bytes(), 'content must actually be hashed after matching metadata')
        self.assertEqual('0', env_file(self.state / 'clean.env')['files'])

    def test_deep_digest_blocks_changed_bytes_with_all_original_metadata(self):
        _, path = self.deep_fixture(); before = path.stat()
        path.write_bytes(b'changed')
        os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
        self.pin_old_ctime(path, before)
        result = self.deep_clean()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b'changed', path.read_bytes())
        self.assertIn('changed\t', (self.state / 'clean.tsv').read_text())
        self.assertTrue((self.state / 'content-read').read_bytes())
        self.assertEqual('0', env_file(self.state / 'clean.env')['files'])

    def test_apk_digest_blocks_changed_bytes_with_all_original_metadata(self):
        path = self.tmp / 'owned.apk'; path.write_bytes(b'payload')
        before = path.stat()
        identity = subprocess.check_output(['stat', '-c', '%d:%i:%s:%y:%z', str(path)], text=True).strip()
        targets, identities, hashed = [self.state / name for name in ('apk.targets', 'apk.identities', 'apk.hashed')]
        targets.write_bytes(os.fsencode(path) + b'\0'); identities.write_bytes(identity.encode() + b'\0')
        result = self.run_command([self.engine, 'hash-apk-snapshot', targets, identities, hashed, self.state / 'stop', 1048576])
        self.assertEqual(0, result.returncode, result.stderr)
        original = hashed.read_bytes().split(b'\0')[0].decode()
        self.assertIn('|sha256=' + hashlib.sha256(b'payload').hexdigest(), original)
        path.write_bytes(b'changed'); os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
        self.pin_old_ctime(path, before)
        result = self.run_command([self.engine, 'unlink-apk-snapshot-item', path, original, self.state / 'apk.deleted'])
        self.assertEqual(11, result.returncode, result.stderr)
        self.assertEqual(b'changed', path.read_bytes())
        self.assertEqual(b'', (self.state / 'apk.deleted').read_bytes())
        self.assertTrue((self.state / 'content-read').read_bytes())
        # A legacy identity must not get a hash retrofitted during clean.
        result = self.run_command([self.engine, 'unlink-apk-snapshot-item', path, identity, self.state / 'old.deleted'])
        self.assertEqual(11, result.returncode, result.stderr)
        self.assertTrue(path.exists())

    def test_apk_hash_cancellation_does_not_authorize_or_delete_content(self):
        path = self.tmp / 'owned.apk'; path.write_bytes(b'payload')
        identity = subprocess.check_output(['stat', '-c', '%d:%i:%s:%y:%z', str(path)], text=True).strip()
        targets, identities, hashed = [self.state / name for name in ('apk.targets', 'apk.identities', 'apk.hashed')]
        targets.write_bytes(os.fsencode(path) + b'\0'); identities.write_bytes(identity.encode() + b'\0')
        (self.state / 'stop').touch()
        result = self.run_command([self.engine, 'hash-apk-snapshot', targets, identities, hashed, self.state / 'stop', 1048576])
        self.assertEqual(9, result.returncode, result.stderr)
        self.assertEqual(b'', hashed.read_bytes())
        self.assertEqual(b'payload', path.read_bytes())

    def test_hash_budget_expiry_preserves_cache_and_deep_original_records(self):
        for mode in ('cache', 'deep'):
            with self.subTest(mode=mode):
                if mode == 'cache':
                    _, files = self.cache_fixture(); path = files['fault.bin']
                else:
                    _, path = self.deep_fixture()
                before = path.stat(); self.pin_old_ctime(path, before)
                self.env['TEST_EXPIRE_AFTER_READ'] = '1'
                result = self.cache_clean() if mode == 'cache' else self.deep_clean()
                self.assertEqual(8, result.returncode, result.stdout + result.stderr)
                self.assertTrue(path.exists())
                summary = env_file(self.state / 'clean.env')
                self.assertEqual('0', summary['files'])
                if mode == 'deep': self.assertEqual('0', summary['cursor'])
                self.env.pop('TEST_EXPIRE_AFTER_READ')
                for key in ('LD_PRELOAD','TEST_FROZEN_NAME','TEST_FROZEN_CTIME_SEC','TEST_FROZEN_CTIME_NSEC','TEST_CONTENT_READ_LOG'):
                    self.env.pop(key, None)

    def test_deep_discovery_roots_remain_distinct_from_hashed_review_manifest(self):
        root, path = self.deep_fixture()
        status = root.stat()
        # This is the native scanner's 11-field discovery wire format; it is
        # never consumed by clean. Build must add original file content hashes.
        root_fields = ['dir', 'low', str(root), str(status.st_dev), str(status.st_ino), str(status.st_size),
                       str(status.st_mtime_ns // 1000000000), str(status.st_mtime_ns % 1000000000),
                       str(status.st_ctime_ns // 1000000000), str(status.st_ctime_ns % 1000000000), str(root)]
        roots = self.state / 'roots.nul'
        roots.write_bytes(b'8000\0' + b'0\0' + b'\0'.join(x.encode() for x in root_fields) + b'\0')
        result = self.run_command([self.deep, 'build', '--targets', self.state / 'deep_scan.targets',
                                  '--roots', roots, '--manifest', self.state / 'hashed.manifest', '--summary', self.state / 'build.env'])
        self.assertEqual(0, result.returncode, result.stderr)
        fields = (self.state / 'hashed.manifest').read_bytes().split(b'\0')[:-1]
        self.assertEqual(0, len(fields) % 12)
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest().encode(), fields[11])
        self.assertEqual(b'-', fields[23])

    def test_old_cache_manifest_without_digest_is_rejected_before_deletion(self):
        _, files = self.cache_fixture()
        manifest = self.state / 'cache_scan.manifest0'
        fields = manifest.read_bytes().split(b'\0')[:-1]
        manifest.write_bytes(b'\0'.join(fields[:10]) + b'\0')
        result = self.cache_clean()
        self.assertEqual(7, result.returncode, result.stderr)
        self.assertTrue(files['fault.bin'].exists())

    def test_old_deep_manifest_without_digest_is_rejected_before_deletion(self):
        _, path = self.deep_fixture()
        manifest = self.state / 'deep_scan.manifest0'
        fields = manifest.read_bytes().split(b'\0')[:-1]
        old = [field for i, field in enumerate(fields) if i % 12 != 11]
        manifest.write_bytes(b'\0'.join(old) + b'\0')
        result = self.deep_clean()
        self.assertEqual(7, result.returncode, result.stderr)
        self.assertTrue(path.exists())

    def test_cache_parent_symlink_cannot_redirect_snapshot_deletion(self):
        root, files = self.cache_fixture()
        outside = self.tmp / 'outside-owned'
        root.rename(outside)
        root.symlink_to(outside, target_is_directory=True)
        result = self.cache_clean()
        self.assertTrue((outside / 'fault.bin').exists(), 'snapshot delete followed replaced ancestor symlink')
        self.assertEqual('0', env_file(self.state / 'clean.env')['files'])

    def test_missing_cache_whitelist_is_not_recreated_empty(self):
        _, files = self.cache_fixture()
        self.whitelist.unlink()
        result = self.cache_clean(wrapper=True)
        self.assertEqual(7, result.returncode, result.stdout + result.stderr)
        self.assertTrue(files['fault.bin'].exists())
        self.assertFalse(self.whitelist.exists())

    def test_missing_cache_package_whitelist_is_not_recreated_empty(self):
        _, files = self.cache_fixture()
        self.packages.unlink()
        result = self.cache_clean(wrapper=True)
        self.assertEqual(7, result.returncode, result.stdout + result.stderr)
        self.assertTrue(files['fault.bin'].exists())
        self.assertFalse(self.packages.exists())

    def test_missing_deep_whitelist_is_not_recreated_empty(self):
        _, path = self.deep_fixture()
        self.whitelist.unlink()
        result = self.deep_clean(wrapper=True)
        self.assertEqual(7, result.returncode, result.stdout + result.stderr)
        self.assertTrue(path.exists())
        self.assertFalse(self.whitelist.exists())

    def test_cache_persistent_nul_contains_only_actual_unlinks(self):
        weird = "space ' quote\n尾部\n.bin"
        _, files = self.cache_fixture((weird, 'failed.bin', 'missing.bin', 'changed.bin'))
        files['missing.bin'].unlink()
        files['changed.bin'].write_bytes(b'changed after original review')
        result = self.cache_clean(('unlinkat', 'failed.bin', errno.EIO), wrapper=True)
        self.assertEqual(8, result.returncode, result.stdout + result.stderr)
        streams = list((self.state / 'cleanup-media').glob('pending-*/paths.nul'))
        self.assertEqual(1, len(streams))
        self.assertEqual(os.fsencode(files[weird]) + b'\0', streams[0].read_bytes())
        self.assertTrue(Path(str(streams[0]) + '.writer-ready').exists())
        self.assertTrue(files['failed.bin'].exists() and files['changed.bin'].exists())
        self.assertEqual('1', env_file(self.state / 'latest.env')['files'])

    def test_deep_persistent_nul_contains_actual_files_and_removed_directories(self):
        root, path = self.deep_fixture(("space ' quote\n.bin",))
        result = self.deep_clean(wrapper=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        stream = next((self.state / 'cleanup-media').glob('pending-*/paths.nul'))
        self.assertEqual({os.fsencode(path), os.fsencode(root)}, set(stream.read_bytes().split(b'\0')) - {b''})
        self.assertFalse(path.exists())
        self.assertEqual('1', env_file(self.state / 'latest.env')['files'])

    def test_cache_unknown_stat_errors_are_not_missing(self):
        _, files = self.cache_fixture()
        for number in (errno.EACCES, errno.EIO, errno.ENOTDIR):
            for after in (1, 2):
                with self.subTest(number=number, after=after):
                    result = self.cache_clean(('fstatat', 'fault.bin', number, after))
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
        result = self.cache_clean(('unlinkat', 'fault.bin', errno.EACCES), wrapper=True)
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
        paths = [Path(os.fsdecode(fields[i + 10])) for i in range(0, len(fields), 12)
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
