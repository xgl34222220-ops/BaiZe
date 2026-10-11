#!/usr/bin/env python3
"""启动页契约：core-splashscreen、无额外 Activity、无延迟、品牌图与版本一致、图标为矢量白泽、浅青启动底（3.0.1）。"""
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
        # 3.0.1：浅色模式启动底是与图标同色系的浅青，深色模式保留深青。
        self.assertEqual('#E6F7F6', color)
        night = ET.parse(RES / 'values-night/splash.xml').getroot().find("color[@name='baize_splash_background']").text
        self.assertEqual('#0A4F55', night)
        for d in ('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi'):
            self.assertFalse((RES / f'drawable-{d}/ic_baize_splash_art.webp').exists())
            self.assertTrue((RES / f'drawable-{d}/baize_splash_branding.png').is_file())
            self.assertTrue((RES / f'drawable-night-{d}/baize_splash_branding.png').is_file())

    def test_branding_text_contrasts_with_its_splash_background(self):
        # 浅底配深青字、深底配白字：品牌图主笔画颜色与启动底的亮度差要足够大。
        import struct, zlib
        def ink(path):
            data = path.read_bytes()
            w, h = struct.unpack('>II', data[16:24])
            self.assertEqual((8, 6), (data[24], data[25]), 'branding must be 8-bit RGBA PNG')
            raw, i = b'', 8
            while i < len(data):
                n = struct.unpack('>I', data[i:i + 4])[0]
                if data[i + 4:i + 8] == b'IDAT':
                    raw += data[i + 8:i + 8 + n]
                i += 12 + n
            raw, stride, prev, rows = zlib.decompress(raw), w * 4, bytearray(w * 4), []
            for y in range(h):
                f, line = raw[y * (stride + 1)], bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
                for x in range(stride):
                    a = line[x - 4] if x >= 4 else 0
                    b = prev[x]
                    c = prev[x - 4] if x >= 4 else 0
                    if f == 1: line[x] = (line[x] + a) & 255
                    elif f == 2: line[x] = (line[x] + b) & 255
                    elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
                    elif f == 4:
                        p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                        line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
                rows.append(line); prev = line
            solid = [sum(r[x:x + 3]) / 3 for r in rows for x in range(0, stride, 4) if r[x + 3] >= 240]
            self.assertTrue(solid, path)
            return sum(solid) / len(solid)
        for folder, bg in (('drawable-mdpi', 0xE6F7F6), ('drawable-night-mdpi', 0x0A4F55)):
            luma = sum((bg >> s) & 255 for s in (16, 8, 0)) / 3
            self.assertGreater(abs(ink(RES / folder / 'baize_splash_branding.png') - luma), 120, folder)

    def test_splash_icon_is_the_launcher_vector_mark(self):
        # 3.0.1：启动页图标是 tools/icon/baize_icon.py 生成的同一套矢量（图标背景圆盘 + 前景），不另存位图。
        splash = ET.parse(RES / 'drawable/ic_baize_splash.xml').getroot()
        self.assertEqual('inset', splash.tag)
        self.assertEqual('@drawable/ic_baize_splash_mark', splash.attrib[A + 'drawable'])
        mark = (RES / 'drawable/ic_baize_splash_mark.xml').read_text(encoding='utf-8')
        foreground = (RES / 'drawable/ic_baize_foreground.xml').read_text(encoding='utf-8')
        self.assertEqual('vector', ET.fromstring(mark.encode()).tag)
        # 前景的每一条路径都原样出现在启动页图标里。
        for d in re.findall(r'android:pathData="([^"]+)"', foreground):
            self.assertIn(f'android:pathData="{d}"', mark)

    def test_static_branding_matches_version(self):
        # 品牌图把「BAIZE · v3.0.0」烘焙进位图；改版本号时必须同步重新导出品牌图。
        gradle = (APP / 'build.gradle.kts').read_text(encoding='utf-8')
        self.assertIn('versionName = "3.0.0"', gradle)
        self.assertIn('core-splashscreen', gradle)


if __name__ == '__main__':
    unittest.main()
