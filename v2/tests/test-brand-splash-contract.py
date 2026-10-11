#!/usr/bin/env python3
"""启动页契约：core-splashscreen、无额外 Activity、无延迟、品牌图与版本一致、图标为矢量白泽晶、弥散渐变玻璃启动底（3.0.1）。"""
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
        # 3.0.1：Android 12+ 纯色启动底 = 弥散渐变中心色；浅色、深色模式共用一份，不再有 values-night 覆盖。
        self.assertEqual('#1D888E', color)
        self.assertFalse((RES / 'values-night/splash.xml').exists())
        for d in ('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi'):
            self.assertFalse((RES / f'drawable-{d}/ic_baize_splash_art.webp').exists())
            self.assertTrue((RES / f'drawable-{d}/baize_splash_branding.png').is_file())
            self.assertFalse((RES / f'drawable-night-{d}/baize_splash_branding.png').exists())

    def test_branding_text_contrasts_with_its_splash_background(self):
        # 深青启动底配白字：品牌图主笔画颜色与启动底的亮度差要足够大。
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
        for folder, bg in (('drawable-mdpi', 0x1D888E), ('drawable-xxxhdpi', 0x1D888E)):
            luma = sum((bg >> s) & 255 for s in (16, 8, 0)) / 3
            self.assertGreater(abs(ink(RES / folder / 'baize_splash_branding.png') - luma), 120, folder)

    def test_splash_icon_is_the_launcher_vector_mark(self):
        # 3.0.1：启动图标是 tools/icon/baize_icon.py 生成的 ic_baize_splash_mark（玻璃光晕 + 与桌面图标逐字相同的前景）。
        splash = ET.parse(RES / 'drawable/ic_baize_splash.xml').getroot()
        self.assertEqual('inset', splash.tag)
        self.assertEqual('@drawable/ic_baize_splash_mark', splash.attrib[A + 'drawable'])
        mark = (RES / 'drawable/ic_baize_splash_mark.xml').read_text(encoding='utf-8')
        foreground = (RES / 'drawable/ic_baize_foreground.xml').read_text(encoding='utf-8')
        self.assertEqual('vector', ET.fromstring(mark.encode()).tag)
        for d in re.findall(r'android:pathData="([^"]+)"', foreground):
            self.assertIn(f'android:pathData="{d}"', mark)
        # 光晕落在系统 192dp 可见圆（108 视口内半径 36）内。
        for r in re.findall(r'android:gradientRadius="([\d.]+)"', mark):
            self.assertLessEqual(float(r), 36)

    def test_splash_background_is_diffuse_mesh_gradient(self):
        # 弥散渐变：白泽青底色 + 至少三团径向色斑，色斑最外圈完全透明（无硬边）；纯矢量。
        mesh = ET.parse(RES / 'drawable/baize_splash_mesh.xml').getroot()
        self.assertEqual('vector', mesh.tag)
        grads = list(mesh.iter('gradient'))
        radial = [g for g in grads if g.attrib[A + 'type'] == 'radial']
        self.assertEqual('linear', grads[0].attrib[A + 'type'])
        self.assertGreaterEqual(len(radial), 3)
        for g in radial:
            self.assertEqual('#00', g.findall('item')[-1].attrib[A + 'color'][:3])
        # Android 8–11：启动窗口 windowBackground = 渐变 + 居中 288dp 启动图标。
        items, _ = style(RES / 'values/splash.xml', 'Theme.BaiZe.Starting')
        self.assertEqual('@drawable/baize_splash_window', items['android:windowBackground'])
        layers = ET.parse(RES / 'drawable/baize_splash_window.xml').getroot().findall('item')
        self.assertEqual('@drawable/baize_splash_mesh', layers[0].attrib[A + 'drawable'])
        self.assertEqual('@drawable/ic_baize_splash', layers[1].attrib[A + 'drawable'])
        self.assertEqual(('288dp', '288dp', 'center'),
                         tuple(layers[1].attrib[A + k] for k in ('width', 'height', 'gravity')))
        # Android 12+ 只能纯色：首帧时由 BaiZeSplashExit 把同一张渐变淡入；8–11 的兼容视图也换成这张渐变。
        exit_src = (JAVA / 'BaiZeSplashExit.kt').read_text(encoding='utf-8')
        self.assertIn('R.drawable.baize_splash_mesh', exit_src)
        self.assertIn('BaiZeSplashExit.attach(this, installSplashScreen())',
                      (JAVA / 'MiuixDashboardActivity.kt').read_text(encoding='utf-8'))

    def test_static_branding_matches_version(self):
        # 品牌图把「BAIZE · v3.3.3」烘焙进位图；改版本号时必须同步重新导出品牌图。
        gradle = (APP / 'build.gradle.kts').read_text(encoding='utf-8')
        self.assertIn('versionName = "3.3.3"', gradle)
        self.assertIn('core-splashscreen', gradle)


if __name__ == '__main__':
    unittest.main()
