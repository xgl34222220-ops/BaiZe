#!/usr/bin/env python3
"""Behavioral regressions for actual native cleanup and package-aware rules."""
import os
import hashlib
from pathlib import Path
import shutil
import re
import shlex
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]


class Rules(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix='baize-rule-engine-')
        cls.work = Path(cls.workspace.name)
        cls.engine = cls.work / 'engine'
        subprocess.run([os.environ.get('CC', 'cc'), '-std=c11', '-O2', '-Wall', '-Wextra',
                        '-Wno-unused-result', '-Wno-misleading-indentation',
                        str(ROOT / 'v2/native/baize_engine_42_4.c'), '-o', str(cls.engine)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(dir=self.work))
        self.data = self.tmp / 'data'
        self.media = self.data / 'media'
        self.data.mkdir(); self.media.mkdir()
        self.whitelist = self.tmp / 'whitelist'
        self.whitelist.write_text('')

    def run_engine(self, command, *args, env=None, expected=0):
        result = subprocess.run([str(self.engine), command, '--data-root', str(self.data),
                                 '--media-root', str(self.media), '--whitelist', str(self.whitelist),
                                 *map(str, args)], capture_output=True, text=True, timeout=15, env=env)
        self.assertEqual(result.returncode, expected, result.stderr)
        return result

    def file(self, relative, content=b'cache', root=None):
        path = (root or self.data) / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return path

    def nul(self, path):
        return [os.fsdecode(p) for p in path.read_bytes().split(b'\0') if p]

    def test_external_cache_snapshot_actually_deletes_with_nested_default_roots(self):
        # Real Android uses /data/media below /data, not independent test roots.
        small = self.file('media/0/Android/data/com.example.app/cache/small')
        second = self.file('media/10/Android/data/com.example.app/cache/second')
        changed = self.file('media/0/Android/data/com.example.app/cache/changed')
        huge = self.file('media/0/Android/data/com.example.app/cache/huge', b'x' * 101)
        keep = self.file('media/0/Android/data/com.example.app/cache/pinned/keep')
        self.whitelist.write_text(str(keep.parent) + '\n')
        manifest, summary = self.tmp / 'manifest', self.tmp / 'summary'
        self.run_engine('scan-cache', '--report', self.tmp / 'scan.tsv', '--targets', self.tmp / 'targets',
                        '--items', self.tmp / 'items', '--summary', summary, '--manifest', manifest,
                        '--max-file-bytes', 100)
        self.assertIn('files=3\n', summary.read_text())
        self.assertIn('protected_items=1\n', summary.read_text())
        changed.write_bytes(b'changed since scan')
        self.run_engine('clean-cache-snapshot', '--report', self.tmp / 'clean.tsv',
                        '--summary', summary, '--manifest', manifest, '--max-file-bytes', 100)
        self.assertFalse(small.exists()); self.assertFalse(second.exists())
        self.assertTrue(changed.exists()); self.assertTrue(huge.exists()); self.assertTrue(keep.exists())
        self.assertIn('files=2\n', summary.read_text())
        self.assertIn('changed\t', (self.tmp / 'clean.tsv').read_text())

    def test_internal_external_cache_keep_their_own_retention(self):
        internal = self.file('user/0/com.example.app/cache/recent')
        external = self.file('media/0/Android/data/com.example.app/cache/recent')
        for file in (internal, external):
            epoch = time.time() - 86400 - 2
            os.utime(file, (epoch, epoch))
        manifest = self.tmp / 'manifest'
        self.run_engine('scan-cache', '--report', self.tmp / 'scan', '--targets', self.tmp / 'targets',
                        '--items', self.tmp / 'items', '--summary', self.tmp / 'summary',
                        '--manifest', manifest, '--min-age-days', 7, '--external-min-age-days', 0)
        self.assertNotIn(str(internal), self.nul(manifest))
        self.assertIn(str(external), self.nul(manifest))

    def test_deep_policy_edit_invalidates_existing_delete_snapshot(self):
        state = self.tmp / 'state'; state.mkdir()
        files = {key: state / name for key, name in {
            'manifest_sha': 'deep_scan.manifest0', 'targets_sha': 'deep_scan.targets',
            'whitelist_sha': 'whitelist.conf', 'rules_sha': 'rules',
            'builtin_risk_sha': 'builtin', 'user_risk_sha': 'risk-overrides.conf',
            'config_sha': 'config.conf',
        }.items()}
        for file in files.values(): file.write_text('original\n')
        hashes = {key: hashlib.sha256(file.read_bytes()).hexdigest() for key, file in files.items()}
        values = {'epoch': int(time.time()), 'snapshot_id': 'test-policy', 'max_file_bytes': 100, **hashes}
        (state / 'deep_scan.env').write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
        (state / 'deep_scan.cursor').write_text('0\n')
        engine = self.tmp / 'never-delete'
        engine.write_text('#!/bin/sh\nexit 99\n'); engine.chmod(0o755)
        env = os.environ.copy()
        env.update(BAIZE_STATE_DIR=str(state), BAIZE_DEEP_SNAPSHOT_ENGINE=str(engine),
                   BAIZE_DEEP_RULES=str(files['rules_sha']),
                   BAIZE_BUILTIN_RISK_OVERRIDES=str(files['builtin_risk_sha']))
        for key in ('builtin_risk_sha', 'user_risk_sha', 'config_sha'):
            with self.subTest(key=key):
                files[key].write_text('edited after scanning\n')
                result = subprocess.run(['bash', str(ROOT / 'v2/module/deep-manifest-clean.sh'),
                                         'deep-clean', 'manual'], env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
                self.assertIn('风险策略已变化', result.stdout)
                files[key].write_text('original\n')

    def test_one_walk_collects_exact_age_size_and_empty_files(self):
        root = self.data / 'user/0/com.example.app/files/cache'
        old = self.file('old', root=root)
        new = self.file('new', root=root)
        boundary = self.file('exact-limit', b'x' * 100, root=root)
        huge = self.file('too-large', b'x' * 101, root=root)
        empty = self.file('empty', b'', root=root)
        self.file('.nomedia', b'', root=root)
        self.file('active.lock', b'', root=root)
        self.file('line\nbreak', root=root)
        excluded = self.file('pinned/file', root=root)
        self.whitelist.write_text(str(excluded.parent) + '\n')
        (root / 'link').symlink_to(excluded.parent, target_is_directory=True)
        epoch = time.time() - 86400 - 2  # 1 day + 2 seconds, old -mtime +1 missed it.
        for p in (old, boundary, huge): os.utime(p, (epoch, epoch))
        files, empties = self.tmp / 'files', self.tmp / 'empty'
        self.run_engine('collect-rule-files', '--collection-root', root, '--targets', files,
                        '--empty', empties, '--min-age-days', 1, '--empty-age-days', 0,
                        '--max-file-bytes', 100)
        self.assertEqual(set(self.nul(files)), {str(old), str(boundary)})
        self.assertEqual(self.nul(empties), [str(empty)])
        self.assertTrue(new.exists()); self.assertTrue(huge.exists())
        stop = self.tmp / 'stop'; stop.touch()
        self.run_engine('collect-rule-files', '--collection-root', root, '--targets', files,
                        '--stop', stop, expected=9)
        self.assertEqual(files.read_bytes(), b'')

    def test_package_rule_discovery_matches_users_and_rejects_escape(self):
        direct = self.file('user/0/com.example.app/files/logs/a')
        de = self.file('user_de/10/com.example.app/files/logs/a')
        external = self.file('media/10/Android/data/com.example.app/files/logs/a')
        outside = self.file('user/0/com.other.app/files/secret')
        (direct.parent.parent / 'escape').symlink_to(outside.parent, target_is_directory=True)
        rules, targets = self.tmp / 'rules', self.tmp / 'targets'
        rules.write_text('com.missing.app|files/logs|0\ncom.example.app|files/logs|1\n'
                         'com.example.app|files/escape|0\n')
        self.run_engine('rule-targets', '--rules', rules, '--targets', targets)
        records = self.nul(targets)
        self.assertEqual({records[i + 2] for i in range(0, len(records), 3)}, {str(direct.parent), str(de.parent)})
        self.run_engine('rule-targets', '--rules', rules, '--targets', targets, '--rule-external')
        self.assertEqual(self.nul(targets), ['1', 'com.example.app', str(external.parent)])
        for relative in ('../com.other.app', 'files/../databases', 'files//logs', 'files/*', 'files/./logs'):
            rules.write_text(f'com.example.app|{relative}|0\n')
            self.run_engine('rule-targets', '--rules', rules, '--targets', targets, expected=7)

    def test_real_rules_clean_native_and_fallback_preserve_content(self):
        source = (ROOT / 'cleaner.sh').read_text()
        for native in (False, True):
            with self.subTest(native=native):
                case = self.tmp / str(native); case.mkdir()
                data, state, module = (case / p for p in ('data', 'state', 'module'))
                data.mkdir(); state.mkdir(); module.mkdir()
                shutil.copytree(ROOT / 'config', module / 'config')
                mapped = re.sub(r'(?<![A-Za-z0-9_/])/data(?=/|\b|_)', str(data), source)
                (module / 'cleaner.sh').write_text(mapped)
                (module / 'config/app.rules').write_text('com.example.app|files/logs|1\n')
                (module / 'config/external.rules').write_text('com.example.app|files/logs|1\n')
                flags = ('clean_app_cache', 'clean_external_cache', 'clean_system_logs',
                         'clean_oem_logs', 'clean_fragments', 'clean_apk_packages',
                         'clean_installer_temp', 'clean_custom_rules', 'clean_root_shells')
                config = (ROOT / 'config/default.conf').read_text() + '\n'
                config += ''.join(f'{key}=0\n' for key in flags)
                config += ('enabled=1\nclean_app_rules=1\nclean_hidden_junk=1\n'
                           'clean_empty_files=1\nclean_empty_dirs=0\nhidden_junk_days=0\nnotify_on_complete=0\n')
                (state / 'config.conf').write_text(config)
                (state / 'whitelist.conf').write_text('')
                expected = [
                    'user/0/com.example.app/files/logs/old',
                    'media/0/Android/data/com.example.app/files/logs/old',
                    'user_de/10/com.example.app/app_webview_remote/Default/Code Cache/old',
                    'user/0/com.example.app/app_x5webview_custom/Default/GPUCache/old',
                    'media/0/Scratch/.cache/old',
                ]
                protected = [
                    'user/0/com.example.app/files/logs/new',
                    'user_de/10/com.example.app/app_webview_remote/Default/Cookies',
                    'media/0/Scratch/.cache/.nomedia',
                    'media/0/.xlDownload/pending',
                ]
                for relative in expected + protected:
                    file = self.file(relative, b'' if relative.endswith('.nomedia') else b'keep', root=data)
                    if relative in expected:
                        epoch = time.time() - 86400 - 2
                        os.utime(file, (epoch, epoch))
                (module / 'abi-resolve.sh').write_text('baize_resolve_engine() {\n'
                    ' [ -x "$1/bin/x86_64/$2" ] || return 1\n'
                    ' printf "%s\\n" "$1/bin/x86_64/$2"\n}\n')
                if native:
                    binary = module / 'bin/x86_64/baize_engine'; binary.parent.mkdir(parents=True)
                    binary.write_text('#!/bin/bash\nexec ' + shlex.quote(str(self.engine)) +
                        ' "$@" --data-root ' + shlex.quote(str(data)) +
                        ' --media-root ' + shlex.quote(str(data / 'media')) + '\n')
                    binary.chmod(0o755)
                env = os.environ.copy(); env['BAIZE_STATE_DIR'] = str(state)
                result = subprocess.run(['bash', str(module / 'cleaner.sh'), 'rules-clean', 'manual'],
                    env=env, text=True, capture_output=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                for relative in expected: self.assertFalse((data / relative).exists(), relative)
                for relative in protected: self.assertTrue((data / relative).exists(), relative)
                self.assertIn('errors=0\n', (state / 'latest.env').read_text())

    def test_deep_wildcard_annotations_preserve_nested_risk_and_recovery_targets(self):
        # Keep Android's real path allowlist in the test; no prefix is bypassed.
        try:
            root = Path(tempfile.mkdtemp(prefix='.baize-rules-', dir='/data/media/0'))
        except PermissionError:
            self.skipTest('Cannot create Android-prefix deep engine fixture')
        self.addCleanup(shutil.rmtree, root)
        cache = root / 'first/cache'; cache.mkdir(parents=True)
        protected = cache / 'keep'; protected.mkdir()
        (protected / 'file').write_text('keep')
        rules = self.tmp / 'rules'; targets = self.tmp / 'targets'
        def scan(lines, overrides='', roots=False):
            rules.write_text(lines)
            over = self.tmp / 'overrides'; over.write_text(overrides)
            env = os.environ.copy()
            if roots: env['BAIZE_DEEP_MANIFEST_ROOTS'] = str(self.tmp / 'roots')
            self.run_engine('scan-deep', '--rules', rules, '--targets', targets,
                            '--report', self.tmp / 'report', '--summary', self.tmp / 'summary',
                            '--risk-overrides', over, '--max-auto-risk', 'low', env=env)
        scan(f'{root}/*/cache|critical\n')
        self.assertEqual(targets.read_text(), '')
        scan(f'{cache}\n', f'{root}/*/cache|critical\n')
        self.assertEqual(targets.read_text(), '')
        scan(f'{cache}|low\n{protected}|critical\n')
        self.assertEqual(targets.read_text(), '')
        scan(f'{cache}|low\n{protected}|low\n', roots=True)
        self.assertEqual(targets.read_text(), f'{cache}\tlow\n{protected}\tlow\n')
        scan(f'{cache}/../cache|low\n')
        self.assertEqual(targets.read_text(), '')


if __name__ == '__main__':
    unittest.main(verbosity=2)
