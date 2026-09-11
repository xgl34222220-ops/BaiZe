#!/usr/bin/env python3
"""Host functional tests for the real module clean -> compatibility route.

Only the fixture copy remaps absolute Android data paths. Production has no
root override. The optional benchmark compares the same manifests and shell
functions with/without the helper, not phone storage or end-to-end latency.
"""
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / 'cleaner.sh').read_text()
SCRATCH = Path(os.environ.get('TMPDIR', '/tmp'))


def function(name):
    return re.search(r'^' + name + r'\(\) \{\n.*?^\}', SOURCE, re.M | re.S)[0]


def run(command, **kwargs):
    return subprocess.run(command, text=True, capture_output=True, timeout=90, **kwargs)


class CompatClean(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix='baize-compat-', dir=SCRATCH)
        cls.base = Path(cls.workspace.name)
        cls.engine = cls.base / 'baize_compat_filter'
        subprocess.run([os.environ.get('CC', 'cc'), '-std=c11', '-O2', '-Wall', '-Wextra',
                        '-Werror', str(ROOT / 'v2/native/baize_compat_filter.c'),
                        '-o', str(cls.engine)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(dir=self.base))

    def filter(self, paths, engine=True, seen=b'', stop=False):
        work = Path(tempfile.mkdtemp(dir=self.tmp))
        candidates = work / 'list'
        candidates.write_bytes(b''.join(os.fsencode(p) + b'\0' for p in paths))
        (work / 'seen').write_bytes(seen)
        if stop:
            (work / 'stop').touch()
        definitions = '\n'.join(function(n) for n in (
            'canonical_rule_path', 'should_stop', 'ensure_timeout_runtime',
            'run_with_watchdog', 'run_limited_command', 'filter_processed_list'))
        script = f'''STATE_DIR={shlex.quote(str(work))}
TMP_DIR=$STATE_DIR
PROCESSED_PATHS=$STATE_DIR/seen
COMMAND_TIMEOUT_MODE=""
WATCHDOG_SEQ=0
COMPAT_FILTER_ENGINE={shlex.quote(str(self.engine) if engine else '')}
log_line() {{ printf '%s\\n' "$1"; }}
{definitions}
filter_processed_list "$STATE_DIR/list"
'''
        result = run(['bash', '-c', script])
        return result, candidates.read_bytes(), (work / 'seen').read_bytes()

    def test_filter_matches_legacy_control_characters_aliases_and_overlap(self):
        paths = []
        for name in ['normal', 'white space', 'tab\tfile', 'line\nfile', 'trailing\n',
                     'carriage\rreturn', '-dash', "quote'file", 'unicode-白泽']:
            p = self.tmp / name
            p.touch()
            paths.append(p)
        alias = self.tmp / 'alias'
        alias.symlink_to(paths[0])
        paths += [alias, paths[1], self.tmp / '.' / 'normal']
        old = self.filter(paths, engine=False)
        new = self.filter(paths)
        self.assertEqual(new[0].returncode, 0, new[0].stderr)
        self.assertEqual(new[1:], old[1:])
        again = self.filter(paths, seen=new[2])
        self.assertEqual(again[1], b'')
        self.assertEqual(again[2], new[2])

    def test_missing_target_falls_back_without_partial_seen_commit(self):
        existing = self.tmp / 'existing'
        existing.touch()
        paths = [existing, self.tmp / 'missing', existing]
        old = self.filter(paths, engine=False)
        new = self.filter(paths)
        self.assertEqual(new[0].returncode, 0)
        self.assertIn('兼容过滤', new[0].stdout)
        self.assertEqual(new[1:], old[1:])

    def test_stop_leaves_manifests_untouched(self):
        p = self.tmp / 'file'
        p.touch()
        for engine in [False, True]:
            result, output, seen = self.filter([p], engine=engine, stop=True)
            self.assertEqual(result.returncode, 9)
            self.assertEqual(output, os.fsencode(p) + b'\0')
            self.assertEqual(seen, b'')

    def test_native_rejects_truncated_input_and_symlink_output(self):
        source, seen, out, next_seen = [self.tmp / x for x in ('in', 'seen', 'out', 'next')]
        source.write_bytes(b'/unterminated')
        seen.write_bytes(b'')
        cmd = [str(self.engine), str(source), str(seen), str(out), str(next_seen), str(self.tmp / 'stop')]
        self.assertEqual(run(cmd).returncode, 5)
        self.assertEqual(source.read_bytes(), b'/unterminated')
        self.assertEqual(seen.read_bytes(), b'')
        out.unlink()
        next_seen.unlink()
        out.symlink_to(source)
        self.assertEqual(run(cmd).returncode, 5)
        self.assertEqual(source.read_bytes(), b'/unterminated')
        (self.tmp / 'stop').touch()
        self.assertEqual(run(cmd).returncode, 9)
        self.assertEqual(source.read_bytes(), b'/unterminated')

    def fixture(self, native=True, config_overrides=None, stop_at_filter=False):
        module, state, data = [self.tmp / x for x in ('module', 'state', 'data')]
        module.mkdir(); state.mkdir(); data.mkdir()
        shutil.copytree(ROOT / 'config', module / 'config')
        shutil.copy(ROOT / 'v2/module/cleaner.sh', module / 'cleaner.sh')
        compat = re.sub(r'(?<![A-Za-z0-9_/])/data(?=/|\b|_)', str(data), SOURCE)
        (module / 'cleaner.sh.compat').write_text(compat)
        (module / 'abi-resolve.sh').write_text('baize_resolve_engine() {\n'
                                              ' [ -x "$1/bin/x86_64/$2" ] || return 1\n'
                                              ' printf "%s\\n" "$1/bin/x86_64/$2"\n}\n')
        if native:
            binary = module / 'bin/x86_64/baize_compat_filter'
            binary.parent.mkdir(parents=True)
            binary.write_text('#!/bin/bash\n' +
                              f'printf "call\\n" >>{shlex.quote(str(self.tmp / "helper.calls"))}\n' +
                              (f'touch {shlex.quote(str(state / "stop"))}\nexit 9\n' if stop_at_filter else
                               f'exec {shlex.quote(str(self.engine))} "$@"\n'))
            binary.chmod(0o755)
        config = (ROOT / 'config/default.conf').read_text()
        values = dict(enabled=1, clean_empty_files=1, clean_empty_dirs=1,
                      clean_root_shells=1, clean_app_cache=1, clean_external_cache=1,
                      clean_app_rules=1, clean_system_logs=1, clean_oem_logs=1,
                      clean_hidden_junk=1, clean_fragments=1, clean_apk_packages=1,
                      clean_installer_temp=1, clean_custom_rules=1, notify_on_complete=0,
                      app_cache_days=0, external_cache_days=0, hidden_junk_days=0,
                      fragment_days=0, apk_package_days=0)
        values.update(config_overrides or {})
        (state / 'config.conf').write_text(config + '\n' + ''.join(f'{k}={v}\n' for k, v in values.items()))
        (state / 'custom.rules').write_text(f'{data}/local/tmp|0\n')
        (module / 'config/app.rules').write_text('com.example.app|files/generated|0\n')
        (module / 'config/external.rules').write_text('com.example.app|files/generated|0\n')
        (module / 'config/hidden.rules').write_text('dir|.cache|0\n')
        deleted = [
            'user/0/com.example.app/cache/cache.bin',
            'user/0/com.example.app/code_cache/code.bin',
            'user_de/10/com.example.app/cache/de.bin',
            'media/0/Android/data/com.example.app/cache/ext.bin',
            'user/0/com.example.app/files/generated/rule.bin',
            'media/0/Android/data/com.example.app/files/generated/rule.bin',
            'user/0/com.example.app/app_webview/Default/Cache/http.bin',
            'anr/old.bin', 'media/0/MIUI/debug_log/old.bin',
            'media/0/Scratch/.cache/hidden.bin', 'media/0/Scratch/old.tmp',
            'media/0/Download/old.apk', 'local/tmp/session.apk.tmp', 'local/tmp/custom.bin',
            'media/0/Scratch/empty', 'media/0/Abandoned/.nomedia',
        ]
        protected = [
            'user/0/com.example.app/cache/keep.bin',
            'user/0/com.protected.app/cache/protected.bin',
            'media/0/Android/data/com.protected.app/cache/protected.bin',
            'user/0/com.example.app/databases/private.db',
            'user/0/com.example.app/app_webview/Default/Cookies',
            'media/0/Documents/user.tmp', 'media/0/DCIM/photo.jpg',
            'media/0/Scratch/.git/settings', 'media/0/Scratch/.nomedia',
        ]
        for name in deleted + protected:
            p = data / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b'' if name.endswith(('/empty', '/.nomedia')) else b'fixture-data')
            os.utime(p, (1_500_000_000, 1_500_000_000))
        os.utime(data / 'media/0/Abandoned', (1_500_000_000, 1_500_000_000))
        empty_dir = data / 'media/0/EmptyDirectory'
        empty_dir.mkdir()
        symlink = data / 'user/0/com.example.app/cache/linked'
        symlink.symlink_to(data / 'user/0/com.example.app/databases', target_is_directory=True)
        (state / 'whitelist.conf').write_text(str(data / protected[0]) + '\n')
        (state / 'native-cache-packages.conf').write_text(' # comment\n  com.protected.app  \n')
        env = {**os.environ, 'BAIZE_STATE_DIR': str(state), 'BAIZE_SHELL': shutil.which('bash')}
        return module, state, data, deleted, protected, env

    def test_all_enabled_categories_native_and_shell_preserve_protection_and_history(self):
        summaries = []
        for native in [False, True]:
            with self.subTest(native=native):
                self.tmp = Path(tempfile.mkdtemp(dir=self.base))
                module, state, data, deleted, protected, env = self.fixture(native)
                result = run(['bash', str(module / 'cleaner.sh'), 'clean', 'app'], env=env)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                for name in deleted:
                    self.assertFalse((data / name).exists(), name + '\n' + result.stderr)
                for name in protected:
                    self.assertTrue((data / name).exists(), name)
                self.assertFalse((data / 'media/0/EmptyDirectory').exists())
                self.assertFalse((state / 'run.lock').exists())
                self.assertFalse((state / 'running.env').exists())
                self.assertEqual(len((state / 'history.tsv').read_text().splitlines()), 1)
                self.assertIn('\tclean\t', (state / 'history.tsv').read_text())
                self.assertIn('mode=clean\n', (state / 'latest.env').read_text())
                self.assertIn('com.example.app', (state / 'reports/apps-latest.tsv').read_text())
                self.assertEqual((self.tmp / 'helper.calls').exists(), native)
                fields = dict(line.split('=', 1) for line in (state / 'latest.env').read_text().splitlines() if '=' in line)
                summaries.append({k: fields[k] for k in ('files', 'regular_files', 'empty_files',
                    'empty_dirs', 'hidden_items', 'fragment_files', 'bytes', 'skipped', 'errors')})
        self.assertEqual(summaries[0], summaries[1])

    def test_disabled_categories_do_not_delete(self):
        flags = {k: 0 for k in ('clean_empty_files', 'clean_empty_dirs', 'clean_root_shells',
            'clean_app_cache', 'clean_external_cache', 'clean_app_rules', 'clean_system_logs',
            'clean_oem_logs', 'clean_hidden_junk', 'clean_fragments', 'clean_apk_packages',
            'clean_installer_temp', 'clean_custom_rules')}
        module, state, data, deleted, protected, env = self.fixture(config_overrides=flags)
        result = run(['bash', str(module / 'cleaner.sh'), 'clean', 'app'], env=env)
        self.assertEqual(result.returncode, 0, result.stderr)
        for name in deleted + protected:
            self.assertTrue((data / name).exists(), name)
        self.assertFalse((self.tmp / 'helper.calls').exists())

    def test_disabled_module_does_not_run_any_category(self):
        module, state, data, deleted, protected, env = self.fixture(config_overrides=dict(enabled=0))
        result = run(['bash', str(module / 'cleaner.sh'), 'clean', 'app'], env=env)
        self.assertEqual(result.returncode, 0, result.stderr)
        for name in deleted + protected:
            self.assertTrue((data / name).exists(), name)
        self.assertFalse((state / 'history.tsv').exists())
        self.assertFalse((self.tmp / 'helper.calls').exists())

    def test_existing_mount_guard_rejects_root_shell(self):
        directory = self.tmp / 'mount'
        directory.mkdir()
        script = function('root_shell_effectively_empty') + '\n' + \
                 'is_mount_target() { return 0; }\nroot_shell_effectively_empty "$1"\n'
        result = run(['bash', '-c', script, 'test', str(directory)])
        self.assertEqual(result.returncode, 1)
        self.assertTrue(directory.is_dir())

    def test_stop_during_native_filter_propagates_to_clean_history(self):
        module, state, data, deleted, protected, env = self.fixture(
            config_overrides=dict(clean_empty_files=0, clean_empty_dirs=0, clean_root_shells=0),
            stop_at_filter=True)
        result = run(['bash', str(module / 'cleaner.sh'), 'clean', 'app'], env=env)
        self.assertEqual(result.returncode, 9, result.stdout + result.stderr)
        for name in deleted + protected:
            self.assertTrue((data / name).exists(), name)
        self.assertIn('停止', (state / 'history.tsv').read_text())
        self.assertFalse((state / 'totals.env').exists())
        self.assertFalse((state / 'run.lock').exists())

    def test_config_last_value_and_package_validation(self):
        config = self.tmp / 'config'
        config.write_text('enabled=0\nenabled=1\nempty=\nnumber=002\nmalicious=$(touch nope)')
        script = f'CONFIG={shlex.quote(str(config))}\n{function("get_value")}\n' + \
                 'get_value enabled; get_value empty; get_value missing; get_value malicious\n'
        result = run(['bash', '-c', script])
        self.assertEqual(result.stdout, '1\n\n\n$(touch nope)\n')
        packages = ['com.example', 'a.b', 'a..', 'a.', '.a.b', 'a b.c', 'a/b.c', 'a', 'a\nb.c']
        for p in packages:
            result = run(['bash', '-c', function('valid_package_name') + '\nvalid_package_name "$1"', 'test', p])
            self.assertEqual(result.returncode == 0, bool(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*\.[A-Za-z0-9._-]+', p)))

    @unittest.skipUnless(os.environ.get('BAIZE_COMPAT_BENCH') == '1', 'opt-in host benchmark')
    def test_benchmark_same_manifest(self):
        paths = [self.tmp / f'file-{n}' for n in range(1000)]
        for p in paths:
            p.touch()
        timings, outputs = {}, {}
        for native in [False, True]:
            start = time.monotonic()
            result, output, seen = self.filter(paths, engine=native)
            timings[native] = time.monotonic() - start
            self.assertEqual(result.returncode, 0, result.stderr)
            outputs[native] = (output, seen)
        self.assertEqual(outputs[False], outputs[True])
        print(f'HOST manifest filter, 1000 files: shell={timings[False]:.4f}s '
              f'native={timings[True]:.4f}s ratio={timings[False]/timings[True]:.1f}x', flush=True)


if __name__ == '__main__':
    unittest.main(verbosity=2)
