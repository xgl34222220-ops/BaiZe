#!/usr/bin/env python3
"""3.0.0 启动页契约：core-splashscreen、无额外 Activity、无延迟、品牌图与版本一致。"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'v2/app'
RES = APP / 'src/main/res'
JAVA = APP / 'src/main/java/io/github/xgl34222220/baize'
A = '{http://schemas.android.com/apk/res/android}'


def style(path, name):
    for s in ET.parse(path).getroot().iter('style'):
        if s.attrib['name'] == name:
            return {i.attrib['name']: (i.text or '').strip() for i in s.iter('item')}, s.attrib.get('parent')
    raise AssertionError(f'{name} missing in {path}')


class BrandSplashContract(unittest.TestCase):
    def test_launcher_uses_starting_theme_without_extra_activity(self):
        app = ET.parse(APP / 'src/main/AndroidManifest.xml').getroot().find('application')
        launchers = [a for a in app.iter('activity')
                     if any(c.attrib.get(A + 'name') == 'android.intent.category.LAUNCHER' for c in a.iter('category'))]
        self.assertEqual(['.MiuixDashboardActivity'], [a.attrib[A + 'name'] for a in launchers])
        self.assertEqual('@style/Theme.BaiZe.Starting', launchers[0].attrib[A + 'theme'])
        names = ' '.join(a.attrib[A + 'name'] for a in app.iter('activity'))
        self.assertNotIn('Splash', names)

    def test_splash_is_installed_before_super_and_never_held(self):
        dash = (JAVA / 'MiuixDashboardActivity.kt').read_text(encoding='utf-8')
        body = dash[dash.index('override fun onCreate'):]
        self.assertLess(body.index('installSplashScreen()'), body.index('super.onCreate'))
        for kt in JAVA.rglob('*.kt'):
            self.assertNotIn('setKeepOnScreenCondition', kt.read_text(encoding='utf-8'), kt.name)
        exit_src = (JAVA / 'BaiZeSplashExit.kt').read_text(encoding='utf-8')
        duration = int(re.search(r'DURATION_MS = (\d+)L', exit_src).group(1))
        self.assertLessEqual(duration, 250)
        self.assertIn('provider.remove()', exit_src)

    def test_theme_values(self):
        for folder in ('values', 'values-v31'):
            items, parent = style(RES / folder / 'splash.xml', 'Theme.BaiZe.Starting')
            self.assertEqual('Theme.SplashScreen', parent)
            self.assertEqual('@color/baize_splash_background', items['windowSplashScreenBackground'])
            self.assertEqual('@drawable/ic_baize_splash', items['windowSplashScreenAnimatedIcon'])
            self.assertEqual('@style/Theme.BaiZe', items['postSplashScreenTheme'])
        items, _ = style(RES / 'values-v31/splash.xml', 'Theme.BaiZe.Starting')
        self.assertEqual('@drawable/baize_splash_branding', items['android:windowSplashScreenBrandingImage'])
        color = ET.parse(RES / 'values/splash.xml').getroot().find("color[@name='baize_splash_background']").text
        self.assertEqual('#0A4F55', color)
        for d in ('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi'):
            self.assertTrue((RES / f'drawable-{d}/ic_baize_splash_art.webp').is_file())
            self.assertTrue((RES / f'drawable-{d}/baize_splash_branding.png').is_file())

    def test_static_branding_matches_version(self):
        # 品牌图把「BAIZE · v3.0.0」烘焙进位图；改版本号时必须同步重新导出品牌图。
        gradle = (APP / 'build.gradle.kts').read_text(encoding='utf-8')
        self.assertIn('versionName = "3.0.0"', gradle)
        self.assertIn('core-splashscreen', gradle)


if __name__ == '__main__':
    unittest.main()
