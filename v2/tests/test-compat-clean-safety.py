#!/usr/bin/env python3
"""Exercise real compat entry points and native helper using owned host fixtures.
Only fixture copies remap Android roots. Fault hooks live outside production.
"""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('compat_home', HERE / 'test-compat-home-clean.py')
home = importlib.util.module_from_spec(spec)
spec.loader.exec_module(home)

MUTATE = r'''#!/usr/bin/env python3
import os
from pathlib import Path
p = Path(os.environ['FAULT_PATH'])
marker = Path(os.environ['FAULT_ONCE'])
if not marker.exists():
    marker.touch()
    action = os.environ['FAULT_ACTION']
    if action == 'write': p.write_bytes(b'new user content')
    elif action == 'replace':
        p.unlink(); p.write_bytes(b'replacement content')
    elif action == 'directory':
        p.unlink(); p.mkdir()
    elif action == 'symlink':
        p.unlink(); p.symlink_to(p.parent / 'absent-target')
    elif action == 'replace_same':
        st = p.stat(); n = st.st_size
        p.unlink(); p.write_bytes(b'x' * n)
        os.utime(p, ns=(st.st_atime_ns, st.st_mtime_ns))
    elif action == 'ancestor':
        parent = p.parent; old = parent.with_name(parent.name + '-original')
        parent.rename(old); parent.mkdir(); p.write_bytes(b'new ancestor content')
    elif action == 'ancestor_old':
        parent = p.parent; old = parent.with_name(parent.name + '-original')
        parent.rename(old); Path(os.environ['FAULT_DONOR']).rename(parent)
    elif action == 'denied': p.parent.chmod(0)
    elif action == 'unlink_denied': p.parent.chmod(0o500)
    elif action == 'missing': p.unlink()
'''


FAULT_SHIM = r"""
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
static int calls;
int fstatat(int fd, const char *path, struct stat *st, int flags) {
    int (*real)(int,const char *,struct stat *,int) = dlsym(RTLD_NEXT,"fstatat");
    const char *fault=getenv("NATIVE_FAULT"), *name=getenv("NATIVE_NAME");
    if (fault && name && !strcmp(path,name)) {
        calls++;
        if (!strcmp(fault,"terminate")) raise(SIGTERM);
        if (!strcmp(fault,"stat") && calls >= atoi(getenv("NATIVE_AFTER"))) {
            errno=atoi(getenv("NATIVE_ERRNO")); return -1;
        }
        int r=real(fd,path,st,flags);
        if (!r && !strcmp(fault,"coarse")) st->st_ctim.tv_nsec=0;
        if (!r && !strcmp(fault,"nanos")) st->st_ctim.tv_nsec ^= 1;
        return r;
    }
    return real(fd,path,st,flags);
}
int unlinkat(int fd, const char *path, int flags) {
    int (*real)(int,const char *,int) = dlsym(RTLD_NEXT,"unlinkat");
    const char *fault=getenv("NATIVE_FAULT"), *name=getenv("NATIVE_NAME");
    if (fault && name && !strcmp(path,name) && !strcmp(fault,"unlink")) { errno=EIO; return -1; }
    int r=real(fd,path,flags);
    if (!r && fault && !strcmp(fault,"cancel")) {
        int stop=open(getenv("NATIVE_STOP"),O_CREAT|O_WRONLY,0600); if(stop>=0)close(stop);
    }
    return r;
}
int clock_gettime(clockid_t clock, struct timespec *value) {
    int (*real)(clockid_t,struct timespec *) = dlsym(RTLD_NEXT,"clock_gettime");
    const char *fault=getenv("NATIVE_FAULT");
    if(fault && !strcmp(fault,"clock-error")) {errno=EIO;return -1;}
    int r=real(clock,value);
    if(!r && clock==CLOCK_REALTIME && fault && !strcmp(fault,"clock-step"))value->tv_sec+=3;
    return r;
}
"""


class CompatSafety(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home.CompatClean.setUpClass()
        cls.base = home.CompatClean.base
        cls.engine = home.CompatClean.engine
        shim = cls.base / 'fault.c'
        shim.write_text(FAULT_SHIM)
        cls.shim = cls.base / 'fault.so'
        subprocess.run(['cc', '-shared', '-fPIC', '-Wall', '-Wextra', '-Werror', str(shim),
                        '-o', str(cls.shim), '-ldl'], check=True)

    @classmethod
    def tearDownClass(cls):
        home.CompatClean.tearDownClass()

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(dir=self.base))
        self.denied = None

    def tearDown(self):
        if self.denied:
            self.denied.chmod(0o700)

    def fixture(self, route, action=None, fault_at='delete', incomplete=False, collect_exit=0):
        f = home.CompatClean()
        f.tmp = self.tmp
        f.engine = self.engine
        flags = {k: 0 for k in ('clean_empty_files', 'clean_empty_dirs', 'clean_root_shells',
            'clean_app_cache', 'clean_external_cache', 'clean_app_rules', 'clean_system_logs',
            'clean_oem_logs', 'clean_hidden_junk', 'clean_fragments', 'clean_apk_packages',
            'clean_installer_temp', 'clean_custom_rules')}
        flag, mode, relative = {
            'apk': ('clean_apk_packages', 'apk-auto', 'media/0/Download/old.apk'),
            'empty': ('clean_empty_files', 'empty-clean', 'media/0/Scratch/empty'),
            'rules': ('clean_custom_rules', 'rules-clean', 'local/tmp/custom.bin'),
            'fragment': ('clean_fragments', 'fragment-clean', 'media/0/Scratch/old.tmp'),
            'hidden': ('clean_hidden_junk', 'rules-clean', 'media/0/Scratch/.cache/hidden.bin'),
            'installer': ('clean_installer_temp', 'rules-clean', 'local/tmp/session.apk.tmp'),
        }[route]
        flags[flag] = 1
        module, state, data, _, _, env = f.fixture(config_overrides=flags)
        # apk-paths also has absolute platform roots; map only the fixture copy.
        apk = module / 'apk-paths.sh'
        apk.write_text(apk.read_text().replace('/data', str(data)))
        target = data / relative
        hooks = self.tmp / 'hooks'
        hooks.mkdir()
        mutate = hooks / 'mutate'
        mutate.write_text(MUTATE); mutate.chmod(0o755)
        env.update(PATH=str(hooks) + os.pathsep + os.environ['PATH'],
                   FAULT_PATH=str(target), FAULT_ACTION=action or 'write',
                   FAULT_ONCE=str(self.tmp / 'fault.once'),
                   BAIZE_MEDIA_ROOT=str(data / 'media'),
                   BAIZE_PUBLIC_MEDIA_ROOT=str(data / 'missing-public'),
                   BAIZE_EXTRA_STORAGE_ROOTS=str(data / 'missing-extra'))
        if action == 'ancestor_old':
            donor = target.parent.with_name('replacement-source')
            donor.mkdir(); (donor / target.name).write_bytes(target.read_bytes())
            env['FAULT_DONOR'] = str(donor)
        if action and fault_at == 'delete':
            xargs = hooks / 'xargs'
            xargs.write_text('#!/bin/bash\ncase " $* " in *" rm "*) ' + str(mutate) + ';; esac\nexec ' + shutil.which('xargs') + ' "$@"\n')
            xargs.chmod(0o755)
        helper = module / 'bin/x86_64/baize_compat_filter'
        helper.write_text('#!/bin/bash\n' +
            ('case "$1" in --delete) ' + str(mutate) + ';; esac\n' if action and fault_at == 'delete' else '') +
            str(self.engine) + ' "$@"\nr=$?\n' +
            'if [ "$1" = --delete ] && [ -f "$4" ]; then cat "$4" >>' + str(self.tmp / 'deleted.nul') + '; fi\nexit "$r"\n')
        helper.chmod(0o755)
        if incomplete or collect_exit or (action and fault_at == 'collect'):
            finder = hooks / 'find'
            finder.write_text('#!/bin/bash\n' + shutil.which('find') + ' "$@"\nr=$?\ncase " $* " in *" -print0 "*)\n' +
                              (' ' + str(mutate) + '\n' if action else '') +
                              (f' exit {collect_exit or 1}\n' if incomplete or collect_exit else '') +
                              ';; esac\nexit "$r"\n')
            finder.chmod(0o755)
        if action in ('denied', 'unlink_denied'): self.denied = target.parent
        return module, state, target, env, mode

    def execute(self, fixture):
        module, state, target, env, mode = fixture
        # A same-second candidate is intentionally ambiguous on coarse storage.
        time.sleep(2.05)
        result = subprocess.run(['bash', str(module / 'cleaner.sh'), mode, 'scheduler:test'],
                                env=env, text=True, capture_output=True, timeout=40)
        fields = dict(line.split('=', 1) for line in (state / 'latest.env').read_text().splitlines() if '=' in line)
        return result, fields

    def test_old_unchanged_candidates_are_really_deleted(self):
        for route in ('apk', 'empty', 'rules', 'fragment'):
            with self.subTest(route=route):
                self.tmp = Path(tempfile.mkdtemp(dir=self.base))
                fixture = self.fixture(route)
                result, fields = self.execute(fixture)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertFalse(fixture[2].exists(), result.stdout)
                self.assertGreater(int(fields['files']), 0)

    def test_changed_file_retained_by_every_real_route(self):
        for route in ('apk', 'empty', 'rules', 'fragment'):
            with self.subTest(route=route):
                self.tmp = Path(tempfile.mkdtemp(dir=self.base))
                fixture = self.fixture(route, 'write')
                result, fields = self.execute(fixture)
                self.assertTrue(fixture[2].exists(), result.stdout + result.stderr)
                self.assertGreater(int(fields.get('protected_items', 0)), 0)

    def test_empty_written_after_find_is_not_deleted(self):
        fixture = self.fixture('empty', 'write', fault_at='collect')
        result, fields = self.execute(fixture)
        self.assertTrue(fixture[2].exists(), result.stdout + result.stderr)
        self.assertEqual(fields['empty_files'], '0')

    def test_directory_and_dangling_link_are_not_success(self):
        for action in ('directory', 'symlink'):
            with self.subTest(action=action):
                self.tmp = Path(tempfile.mkdtemp(dir=self.base))
                fixture = self.fixture('apk', action)
                result, fields = self.execute(fixture)
                self.assertTrue(fixture[2].is_dir() or fixture[2].is_symlink(), result.stdout)
                self.assertEqual(fields['files'], '0')
                self.assertEqual(fields['bytes'], '0')

    def test_inaccessible_target_is_failed_not_cleaned(self):
        fixture = self.fixture('apk', 'denied')
        result, fields = self.execute(fixture)
        self.assertEqual(fields['files'], '0', result.stdout)
        self.assertEqual(result.returncode, 8, result.stdout)
        self.assertGreater(int(fields['errors']), 0)
        self.assertNotIn('清理完成', fields['result'])

    def test_incomplete_traversal_never_deletes(self):
        for route in ('apk', 'empty', 'rules', 'fragment', 'hidden', 'installer'):
            with self.subTest(route=route):
                self.tmp = Path(tempfile.mkdtemp(dir=self.base))
                fixture = self.fixture(route, incomplete=True)
                result, fields = self.execute(fixture)
                self.assertTrue(fixture[2].exists(), result.stdout + result.stderr)
                self.assertEqual(result.returncode, 8, result.stdout)
                self.assertGreater(int(fields['errors']), 0)

    def test_cancelled_traversal_stays_cancelled_and_never_deletes(self):
        for route in ('empty', 'rules', 'fragment', 'hidden', 'installer'):
            with self.subTest(route=route):
                self.tmp = Path(tempfile.mkdtemp(dir=self.base))
                fixture = self.fixture(route, collect_exit=9)
                result, fields = self.execute(fixture)
                self.assertEqual(result.returncode, 9, result.stdout + result.stderr)
                self.assertTrue(fixture[2].exists())
                self.assertEqual(fields['files'], '0')
                self.assertRegex(fields['result'], '停止|中断')



    def test_same_size_mtime_and_ancestor_replacements_are_retained(self):
        for action in ('replace_same', 'ancestor'):
            with self.subTest(action=action):
                self.tmp = Path(tempfile.mkdtemp(dir=self.base))
                fixture = self.fixture('apk', action)
                result, fields = self.execute(fixture)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertTrue(fixture[2].exists())
                self.assertEqual(fields['files'], '0')
                self.assertGreater(int(fields['changed_files']), 0)
                self.assertEqual((self.tmp / 'deleted.nul').read_bytes(), b'')

    def test_old_directory_replacement_after_collection_is_not_adopted(self):
        fixture = self.fixture('apk', 'ancestor_old', fault_at='collect')
        result, fields = self.execute(fixture)
        self.assertTrue(fixture[2].exists(), result.stdout + result.stderr)
        self.assertEqual(fields['files'], '0')
        self.assertGreater(int(fields['protected_items']), 0)

    def test_unlink_failure_and_true_missing_have_distinct_results(self):
        for action in ('unlink_denied', 'missing'):
            with self.subTest(action=action):
                self.tmp = Path(tempfile.mkdtemp(dir=self.base))
                fixture = self.fixture('apk', action)
                result, fields = self.execute(fixture)
                if self.denied:
                    self.denied.chmod(0o700); self.denied = None
                self.assertEqual(result.returncode, 8 if action == 'unlink_denied' else 0, result.stdout)
                self.assertEqual(fields['files'], '0')
                self.assertEqual(fields['missing_files'], '1' if action == 'missing' else '0')
                self.assertEqual((self.tmp / 'deleted.nul').read_bytes(), b'')

    def test_success_nul_preserves_names_and_over_one_hundred_targets(self):
        fixture = self.fixture('apk')
        paths = [fixture[2]]
        for i in range(135):
            p = fixture[2].parent / f"synthetic-{i}- quote' newline\n.apk"
            p.write_bytes(b'apk fixture')
            paths.append(p)
        result, fields = self.execute(fixture)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(int(fields['files']), len(paths))
        confirmed = (self.tmp / 'deleted.nul').read_bytes().split(b'\0')
        self.assertEqual(set(confirmed[:-1]), {os.fsencode(p) for p in paths})
        self.assertEqual(confirmed[-1], b'')
        self.assertTrue(all(not p.exists() for p in paths))

    def test_terminated_helper_keeps_confirmed_counts_and_success_paths(self):
        fixture = self.fixture('apk')
        second = fixture[2].parent / 'second.apk'
        second.write_bytes(b'kept after helper termination')
        # Filesystem traversal order is unspecified. Sort this fixture's NUL
        # discovery stream so termination occurs after one confirmed deletion.
        finder = self.tmp / 'hooks/find'
        finder.write_text('#!/bin/bash\nset -o pipefail\n' + shutil.which('find') + ' "$@" | sort -z\n')
        finder.chmod(0o755)
        size = fixture[2].stat().st_size
        helper = fixture[0] / 'bin/x86_64/baize_compat_filter'
        old = str(self.engine) + ' "$@"\nr=$?\n'
        replacement = ('if [ "$1" = --delete ]; then\n' +
            f' LD_PRELOAD={self.shim} NATIVE_FAULT=terminate NATIVE_NAME={second.name} ' +
            str(self.engine) + ' "$@"\nelse\n ' + str(self.engine) + ' "$@"\nfi\nr=$?\n')
        self.assertIn(old, helper.read_text())
        helper.write_text(helper.read_text().replace(old, replacement))
        result, fields = self.execute(fixture)
        self.assertEqual(result.returncode, 8, result.stdout + result.stderr)
        self.assertFalse(fixture[2].exists())
        self.assertTrue(second.exists())
        self.assertEqual(fields['files'], '1')
        self.assertEqual(int(fields['bytes']), size)
        self.assertGreater(int(fields['errors']), 0)
        self.assertEqual((self.tmp / 'deleted.nul').read_bytes(), os.fsencode(fixture[2]) + b'\0')

    def test_missing_helper_preserves_files_and_reports_incomplete(self):
        fixture = self.fixture('apk')
        (fixture[0] / 'bin/x86_64/baize_compat_filter').unlink()
        result, fields = self.execute(fixture)
        self.assertEqual(result.returncode, 8, result.stdout + result.stderr)
        self.assertTrue(fixture[2].exists())
        self.assertEqual(fields['files'], '0')
        self.assertGreater(int(fields['errors']), 0)

    def test_native_boundary_failure_and_truncated_snapshot_fail_closed(self):
        import struct
        target = self.tmp / 'old'
        target.write_bytes(b'kept')
        time.sleep(2.05)
        boundary, paths, snapshot, summary, deleted, stop = [self.tmp / x for x in
            ('boundary', 'paths', 'snapshot', 'summary', 'deleted', 'stop')]
        paths.write_bytes(os.fsencode(target) + b'\0')
        def call(*args):
            return subprocess.run([str(self.engine), *map(str, args)], capture_output=True).returncode
        self.assertEqual(call('--begin', boundary), 0)
        original = boundary.read_bytes()
        for bad in (b'', struct.pack('=qqqq', 1, 0, -1, 0),
                    struct.pack('=qqqq', int(time.time()) + 3600, 0, 1, 0)):
            boundary.write_bytes(bad)
            self.assertNotEqual(call('--snapshot', paths, snapshot, boundary, 'file', stop), 0)
            if snapshot.exists(): snapshot.unlink()
            self.assertTrue(target.exists())
        boundary.write_bytes(original)
        self.assertEqual(call('--snapshot', paths, snapshot, boundary, 'file', stop), 0)
        snapshot.write_bytes(snapshot.read_bytes()[:-1])
        self.assertNotEqual(call('--delete', snapshot, summary, deleted, stop), 0)
        self.assertTrue(target.exists())



    def native_fixture(self):
        root = Path(tempfile.mkdtemp(dir=self.tmp))
        target = root / 'selected'
        target.write_bytes(b'fixture')
        second = root / 'second'
        second.write_bytes(b'other')
        time.sleep(2.05)
        boundary, paths, snapshot, summary, deleted, stop = [root / x for x in
            ('boundary', 'paths', 'snapshot', 'summary', 'deleted', 'stop')]
        paths.write_bytes(os.fsencode(target) + b'\0' + os.fsencode(second) + b'\0')
        def call(*args, fault=None, after=1, error=5):
            env = dict(os.environ)
            if fault:
                env.update(LD_PRELOAD=str(self.shim), NATIVE_FAULT=fault, NATIVE_NAME=target.name,
                           NATIVE_AFTER=str(after), NATIVE_ERRNO=str(error), NATIVE_STOP=str(stop))
            return subprocess.run([str(self.engine), *map(str, args)], env=env, capture_output=True).returncode
        self.assertEqual(call('--begin', boundary), 0)
        return target, second, boundary, paths, snapshot, summary, deleted, stop, call

    def test_native_stat_and_unlink_faults_count_only_confirmed_successes(self):
        import errno
        for error in (errno.EACCES, errno.EIO, errno.ENOTDIR):
            for after in (1, 2):
                with self.subTest(error=error, after=after):
                    target, second, boundary, paths, snapshot, summary, deleted, stop, call = self.native_fixture()
                    self.assertEqual(call('--snapshot', paths, snapshot, boundary, 'file', stop), 0)
                    self.assertEqual(call('--delete', snapshot, summary, deleted, stop,
                                          fault='stat', after=after, error=error), 8)
                    result = dict(line.split('=', 1) for line in summary.read_text().splitlines())
                    self.assertEqual(result['errors'], '1')
                    self.assertEqual(result['cleaned'], '1')
                    self.assertEqual(result['missing'], '0')
                    self.assertTrue(target.exists())
                    self.assertEqual(deleted.read_bytes(), os.fsencode(second) + b'\0')
        target, second, boundary, paths, snapshot, summary, deleted, stop, call = self.native_fixture()
        self.assertEqual(call('--snapshot', paths, snapshot, boundary, 'file', stop), 0)
        self.assertEqual(call('--delete', snapshot, summary, deleted, stop, fault='unlink'), 8)
        self.assertTrue(target.exists())
        self.assertEqual(deleted.read_bytes(), os.fsencode(second) + b'\0')

    def test_native_nanos_coarse_time_clock_and_cancellation(self):
        for fault in ('nanos', 'coarse', 'clock-error', 'clock-step', 'cancel'):
            with self.subTest(fault=fault):
                target, second, boundary, paths, snapshot, summary, deleted, stop, call = self.native_fixture()
                self.assertEqual(call('--snapshot', paths, snapshot, boundary, 'file', stop,
                                      fault='coarse' if fault == 'coarse' else None), 0)
                code = call('--delete', snapshot, summary, deleted, stop,
                            fault=None if fault == 'coarse' else fault)
                if fault in ('clock-error', 'clock-step'):
                    self.assertNotEqual(code, 0)
                    self.assertTrue(target.exists() and second.exists())
                    continue
                result = dict(line.split('=', 1) for line in summary.read_text().splitlines())
                self.assertEqual(code, 9 if fault == 'cancel' else 0)
                self.assertEqual(result['cleaned'], '1')
                self.assertEqual(result['errors'], '0')
                if fault == 'cancel':
                    self.assertFalse(target.exists())
                    self.assertTrue(second.exists())
                    self.assertEqual(deleted.read_bytes(), os.fsencode(target) + b'\0')
                else:
                    self.assertTrue(target.exists())
                    self.assertEqual(result['changed'], '1')
                    self.assertEqual(deleted.read_bytes(), os.fsencode(second) + b'\0')

    def test_hidden_newline_path_cannot_select_a_second_path(self):
        fixture = self.fixture('hidden')
        with tempfile.TemporaryDirectory(prefix='baize-line-victim-', dir='/tmp') as owned:
            victim = Path(owned) / '.DS_Store'
            victim.write_bytes(b'outside selected storage')
            scratch = fixture[2].parent.parent
            injected = scratch / ('prefix\n' + str(victim))
            injected.parent.mkdir(parents=True)
            injected.write_bytes(b'selected artifact')
            result, fields = self.execute(fixture)
            self.assertTrue(victim.exists(), result.stdout + result.stderr)
            self.assertEqual(victim.read_bytes(), b'outside selected storage')
            self.assertFalse(injected.exists(), result.stdout + result.stderr)
            self.assertIn(os.fsencode(injected), (self.tmp / 'deleted.nul').read_bytes().split(b'\0'))

    def test_all_native_categories_keep_existing_protections(self):
        f = home.CompatClean(); f.tmp = self.tmp; f.engine = self.engine
        module, state, data, removed, protected, env = f.fixture(native=True)
        time.sleep(2.05)
        result = subprocess.run(['bash', str(module / 'cleaner.sh'), 'clean', 'app'],
                                env=env, capture_output=True, text=True, timeout=45)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for path in removed:
            self.assertFalse((data / path).exists(), path + '\n' + result.stdout)
        for path in protected:
            self.assertTrue((data / path).exists(), path)
        self.assertFalse((data / 'media/0/EmptyDirectory').exists())
        self.assertFalse((state / 'run.lock').exists())
        self.assertEqual(len((state / 'history.tsv').read_text().splitlines()), 1)


if __name__ == '__main__': unittest.main()
