#!/usr/bin/env python3
"""Regression checks for boot-bound recovery and protected-parent risk parity.

The preload shim models a persisted outcome whose unlink was lost at power loss.
Changing the mocked kernel boot UUID is essential: SIGKILL alone does not model
lost filesystem persistence. No production runtime override is introduced.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SHIM = r'''
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
static unsigned mutations;
FILE *fopen(const char *path, const char *mode) {
    FILE *(*real)(const char *, const char *) = dlsym(RTLD_NEXT, "fopen");
    const char *boot = getenv("TEST_BOOT_FILE");
    if (boot && !strcmp(path, "/proc/sys/kernel/random/boot_id")) path = boot;
    return real(path, mode);
}
int unlinkat(int fd, const char *path, int flags) {
    int (*real)(int, const char *, int) = dlsym(RTLD_NEXT, "unlinkat");
    ++mutations;
    if (getenv("LOST_UNLINK") && mutations == 1) return 0;
    if (getenv("LOST_UNLINK") && mutations == 2) kill(getpid(), SIGKILL);
    int result = real(fd, path, flags);
    if (!result && mutations == 1) {
        if (getenv("KILL_IN_GAP")) kill(getpid(), SIGKILL);
        const char *stop = getenv("STOP_PATH");
        if (stop) { int f = open(stop, O_CREAT | O_WRONLY, 0600); if (f >= 0) close(f); }
    }
    return result;
}
'''


def run(args, code=0, **env):
    if args and args[0] == "sudo" and os.geteuid() == 0:
        args = args[1:]
    result = subprocess.run([str(a) for a in args], env={**os.environ, **env},
                            capture_output=True, text=True, timeout=60)
    if result.returncode != code:
        raise AssertionError((args, result.returncode, result.stdout, result.stderr))
    return result


class DeepRecoveryBounds(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix='deep-recovery-bounds-', dir=os.environ.get('TMPDIR', '/tmp'))
        cls.work = Path(cls.workspace.name)
        cls.data = Path('/data/media') / cls.work.name
        run(['sudo', 'mkdir', '-p', cls.data])
        if os.geteuid() != 0:
            run(['sudo', 'chown', f'{os.getuid()}:{os.getgid()}', cls.data])
        cls.deep, cls.engine, cls.shim = [cls.work / p for p in ('deep', 'engine', 'faults.so')]
        run(['gcc', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', ROOT / 'native/baize_deep_snapshot.c', '-o', cls.deep])
        run(['gcc', '-std=c11', '-O2', ROOT / 'native/baize_engine_42_4.c', '-o', cls.engine])
        (cls.work / 'faults.c').write_text(SHIM)
        run(['gcc', '-shared', '-fPIC', '-O2', cls.work / 'faults.c', '-ldl', '-o', cls.shim])
        cls.boot_a, cls.boot_b = [cls.work / p for p in ('boot-a', 'boot-b')]
        cls.boot_a.write_text('11111111-1111-1111-1111-111111111111\n')
        cls.boot_b.write_text('22222222-2222-2222-2222-222222222222\n')

    @classmethod
    def tearDownClass(cls):
        run(['sudo', 'rm', '-rf', '--', cls.data])
        cls.workspace.cleanup()

    def fixture(self, name):
        state = self.work / name
        state.mkdir()
        target = self.data / name / 'cache'
        target.mkdir(parents=True)
        for i in range(2):
            (target / f'file-{i}').write_bytes(b'payload')
        (state / 'targets').write_text(f'{target}\tlow\n')
        run([self.deep, 'build', '--targets', state / 'targets', '--manifest', state / 'manifest', '--summary', state / 'build'])
        (state / 'cursor').write_text('0\n')
        return state, target

    def clean(self, state, boot, code=0, **env):
        return run([self.deep, 'clean', '--manifest', state / 'manifest', '--cursor', state / 'cursor',
                    '--report', state / 'report', '--summary', state / 'summary', '--stop', state / 'stop'],
                   code=code, LD_PRELOAD=str(self.shim), TEST_BOOT_FILE=str(boot), **env)

    def test_persisted_outcome_lost_unlink_rejected_after_reboot_and_retry(self):
        state, target = self.fixture('lost-unlink')
        self.clean(state, self.boot_a, code=-9, LOST_UNLINK='1')
        journal = (state / 'cursor').read_bytes()
        self.assertIn(b'R 1 1 0 7 ', journal)
        for _ in range(2):
            self.clean(state, self.boot_b, code=71)
            self.assertEqual(journal, (state / 'cursor').read_bytes())
            self.assertEqual(2, len(list(target.iterdir())))
            self.assertFalse((state / 'summary').exists())
        # An explicit fresh scan is the safe recovery path, not promotion of the old journal.
        run([self.deep, 'build', '--targets', state / 'targets', '--manifest', state / 'manifest', '--summary', state / 'build'])
        (state / 'cursor').write_text('0\n')
        self.clean(state, self.boot_b)
        self.assertIn('bytes=14\n', (state / 'summary').read_text())

    def test_durable_intent_without_outcome_is_also_boot_bound(self):
        state, target = self.fixture('intent-only')
        self.clean(state, self.boot_a, code=-9, KILL_IN_GAP='1')
        journal = (state / 'cursor').read_bytes()
        self.assertIn(b'B ', journal)
        self.assertNotIn(b'R ', journal)
        self.clean(state, self.boot_b, code=71)
        self.assertEqual(1, len(list(target.iterdir())))
        self.assertEqual(journal, (state / 'cursor').read_bytes())

    def test_stop_resumes_in_same_boot_but_reboot_requires_new_scan(self):
        state, target = self.fixture('stopped')
        self.clean(state, self.boot_a, code=9, STOP_PATH=str(state / 'stop'))
        (state / 'stop').unlink()
        journal = (state / 'cursor').read_bytes()
        self.clean(state, self.boot_b, code=71)
        self.assertEqual(journal, (state / 'cursor').read_bytes())
        self.assertEqual(1, len(list(target.iterdir())))
        self.clean(state, self.boot_a)
        self.assertIn('bytes=14\n', (state / 'summary').read_text())

    def test_unavailable_boot_identity_fails_before_mutation(self):
        state, target = self.fixture('missing-boot')
        self.clean(state, self.work / 'missing-boot-id', code=71)
        self.assertEqual(2, len(list(target.iterdir())))
        self.assertEqual('0\n', (state / 'cursor').read_text())

    def test_protected_parent_preserves_all_four_descendant_risk_levels(self):
        for risk in ('low', 'medium', 'high', 'critical'):
            with self.subTest(risk=risk):
                state, target = self.fixture('overlap-' + risk)
                for file in target.iterdir():
                    file.unlink()
                (target / 'huge').write_bytes(b'x' * 100)
                (target / 'small').write_bytes(b'keep')
                (state / 'rules').write_text(f'{target}|low\n{target}/small|{risk}\n')
                for mode in ('full', 'expand'):
                    env = {'BAIZE_DEEP_MANIFEST_ROOTS': str(state / 'roots')} if mode == 'expand' else {}
                    run([self.engine, 'scan-deep', '--rules', state / 'rules', '--targets', state / (mode + '.targets'),
                         '--summary', state / (mode + '.summary'), '--report', state / (mode + '.report'),
                         '--max-file-bytes', '10', '--allow-high-risk', '1', '--dir-budget-ms', '0', '--global-budget-ms', '0'], **env)
                    extra = ['--roots', state / 'roots'] if mode == 'expand' else []
                    run([self.deep, 'build', '--targets', state / (mode + '.targets'),
                         '--manifest', state / (mode + '.manifest'), '--summary', state / (mode + '.build'),
                         '--max-file-bytes', '10', *extra])
                expected = (state / 'full.manifest').read_bytes()
                self.assertEqual(expected, (state / 'expand.manifest').read_bytes())
                self.assertEqual(bool(expected), risk in ('low', 'medium'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
