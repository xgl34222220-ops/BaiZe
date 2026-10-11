#!/usr/bin/env python3
"""Exercise runtime dispatch after moving implementations away from framework hooks."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'v2/module'

class ModuleLayoutTest(unittest.TestCase):
    def test_framework_root_contains_only_hooks_and_metadata(self):
        self.assertEqual({p.name for p in MODULE.iterdir()},
                         {'action.sh', 'customize.sh', 'service.sh', 'uninstall.sh',
                          'module.prop', 'skip_mount', 'scripts'})

    def test_dispatch_keeps_arguments_and_exit_status_in_nested_layout(self):
        with tempfile.TemporaryDirectory(prefix='baize layout ') as temp:
            module = Path(temp) / 'module'
            shutil.copytree(MODULE, module)
            native = module / 'scripts/native-cleaner.sh'
            native.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\nexit 17\n')
            native.chmod(0o755)
            result = subprocess.run(['sh', str(module / 'scripts/cleaner.sh'), 'cache-scan', 'manual task'],
                                    env={**os.environ, "BAIZE_SHELL": "/bin/sh",
                                         "BAIZE_STATE_DIR": str(Path(temp) / 'state')},
                                    capture_output=True, text=True)
            self.assertEqual(17, result.returncode, result.stderr)
            self.assertEqual(['cache-scan', 'manual task'], result.stdout.splitlines())

    def test_rules_resolve_from_module_root(self):
        with tempfile.TemporaryDirectory() as temp:
            module = Path(temp) / 'module'
            shutil.copytree(MODULE, module)
            shutil.copytree(ROOT / 'config', module / 'config')
            env = {**os.environ, 'BAIZE_STATE_DIR': str(Path(temp) / 'state')}
            result = subprocess.run(['sh', str(module / 'scripts/rules-validator.sh')],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_confirmed_icon_is_the_launcher_source(self):
        # 3.0.1 矢量图标：tools/icon/baize_icon.py 是唯一几何来源，生成 SVG 与三层矢量；
        # 旧式 mipmap / 正式图标 / 品牌图由 tools/icon/render_legacy.py 从 SVG 渲染。钉住实际构建输入。
        res = ROOT / 'v2/app/src/main/res'
        pinned = {
            'drawable/ic_baize_background.xml': '42a4f681dbba732dca90cabda434d80ca397e123aa040d7ef0dcc30c0d52e38c',
            'drawable/ic_baize_foreground.xml': '19b57b2ff863ff1a7b05afb519f84a6870e37d69621dc9a358b467da8b57bfc3',
            'drawable/ic_baize_monochrome.xml': 'a912638ecaaf1588c21eb0530338ca02bca36cda9bb494d10f48cccbbb23b04d',
        }
        for name, digest in pinned.items():
            self.assertEqual(digest, hashlib.sha256((res / name).read_bytes()).hexdigest(), name)
        self.assertEqual('817676cd200dc7a3f7c84e3225da47d6fc568a07d362c61e0879d864a1baf653',
                         hashlib.sha256((ROOT / 'design/app-icons/official-icon.webp').read_bytes()).hexdigest())
        icon = (ROOT / 'design/app-icons/official-icon.webp').read_bytes()
        self.assertEqual(icon, (res / 'mipmap-xxxhdpi/ic_baize.webp').read_bytes())
        self.assertEqual(icon, (ROOT / 'branding/baize-app-icon.webp').read_bytes())
        android = '{http://schemas.android.com/apk/res/android}'
        manifest = ET.parse(ROOT / 'v2/app/src/main/AndroidManifest.xml').getroot().find('application')
        self.assertEqual('@mipmap/ic_baize', manifest.attrib[android + 'icon'])
        self.assertEqual('@mipmap/ic_baize', manifest.attrib[android + 'roundIcon'])
        for variant in ('mipmap-anydpi-v26', 'mipmap-anydpi-v33'):
            adaptive = ET.parse(res / variant / 'ic_baize.xml').getroot()
            self.assertEqual('adaptive-icon', adaptive.tag)
            self.assertEqual('@drawable/ic_baize_background', adaptive.find('background').attrib[android + 'drawable'])
            self.assertEqual('@drawable/ic_baize_foreground', adaptive.find('foreground').attrib[android + 'drawable'])
        monochrome = ET.parse(res / 'mipmap-anydpi-v33/ic_baize.xml').getroot().find('monochrome')
        self.assertEqual('@drawable/ic_baize_monochrome', monochrome.attrib[android + 'drawable'])
        for layer in ('background', 'foreground', 'monochrome'):
            self.assertEqual('vector', ET.parse(res / f'drawable/ic_baize_{layer}.xml').getroot().tag, layer)
        # 不再有位图图层。
        for stale in ('drawable-nodpi/ic_baize_art.webp', 'drawable-nodpi/ic_baize_monochrome_art.png', 'drawable/ic_baize_splash_mark.xml'):
            self.assertFalse((res / stale).exists(), stale)

    def test_icon_vectors_match_svg_source(self):
        # SVG 源与 Android 矢量出自同一份几何定义（纯 Python 校验，不需要 CairoSVG）。
        result = subprocess.run([sys.executable, str(ROOT / 'tools/icon/baize_icon.py'), '--check'],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_upgrade_stops_flat_and_grouped_compatibility_workers(self):
        source = (MODULE / 'customize.sh').read_text()
        block = source[source.index('# Stop old flat-layout workers'):source.index('rm -rf "$STATE_DIR/run.lock"')]
        with tempfile.TemporaryDirectory() as temp:
            calls = Path(temp) / 'signals.txt'
            # Replace process signalling, then execute the installer's actual stop block.
            stub = 'pkill() { printf "%s\\n" "$2" >> "$TEST_SIGNALS"; };\n'
            subprocess.run(['sh', '-c', stub + block], check=True,
                           env={**os.environ, 'TEST_SIGNALS': str(calls)})
            targets = calls.read_text().splitlines()
            for name in ('cleaner', 'cleaner-compat', 'task-worker', 'scheduler', 'supervisor'):
                for directory in ('', 'scripts/'):
                    self.assertIn(f'/data/adb/modules/baize_v2/{directory}{name}.sh', targets)

if __name__ == '__main__':
    unittest.main()
