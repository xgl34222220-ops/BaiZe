"""Install the exact signed refactor APK on a disposable Android emulator, including 30002 upgrade."""
from __future__ import annotations
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import time

spec = importlib.util.spec_from_file_location('startup', Path(__file__).with_name('smoke-release-apk.py'))
assert spec and spec.loader
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def tap(text: str, case: str) -> None:
    for attempt in range(5):
        root = smoke.ui(f'{case}-search-{attempt}')
        nodes = [n for n in root.iter('node') if n.attrib.get('text') == text]
        if nodes:
            x1, y1, x2, y2 = max((list(map(int, re.findall(r'\d+', n.attrib['bounds']))) for n in nodes), key=lambda b: b[1])
            smoke.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(2)
            smoke.alive()
            return
        bounds = list(map(int, re.findall(r'\d+', smoke.adb('shell', 'wm', 'size'))))
        width, height = bounds[-2:]
        smoke.adb('shell', 'input', 'swipe', str(width//2), str(height*3//4), str(width//2), str(height//3), '400')
    raise AssertionError(f'Cannot find {text}')


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit('Usage: refactor-startup.py APK VERSION_CODE VERSION_NAME')
    apk = Path(sys.argv[1]).resolve()
    code, version = sys.argv[2:]
    baseline = Path(os.environ['BAIZE_BASELINE_APK']).resolve()
    assert hashlib.sha256(baseline.read_bytes()).hexdigest() == '600ac2de5f33fdf282f222a2c1099ec63f9ed7766482fe56f238e487836a4fc4'
    smoke.adb('shell', 'input', 'keyevent', '82')
    smoke.settle_emulator_boot()
    smoke.adb('install', '-r', str(baseline), timeout=120)
    smoke.adb('shell', 'pm', 'grant', smoke.APP, 'android.permission.POST_NOTIFICATIONS', check=False)
    time.sleep(8)
    smoke.launch('baseline-30002')
    smoke.adb('root', check=False)
    smoke.adb('wait-for-device')
    directory = f'/data/user/0/{smoke.APP}/files'
    marker = f'{directory}/refactor-upgrade-marker.txt'
    smoke.adb('shell', 'mkdir', '-p', directory)
    smoke.adb('shell', f'echo keep-existing-user-data > {marker}; uid=$(stat -c %u /data/user/0/{smoke.APP}); chown $uid:$uid {directory} {marker}; chmod 700 {directory}; chmod 600 {marker}; restorecon -RF {directory}')
    smoke.adb('install', '-r', str(apk), timeout=120)
    time.sleep(8)
    package = smoke.adb('shell', 'dumpsys', 'package', smoke.APP)
    assert f'versionCode={code} ' in package
    assert f'versionName={version}' in package
    smoke.save_text('package.txt', package)
    smoke.launch('refactor-upgrade')
    assert smoke.adb('shell', 'cat', marker) == 'keep-existing-user-data'
    smoke.launch('refactor-cold-start')
    for i, label in enumerate(['清理', '记录', '设置']):
        smoke.tap_label(label, f'refactor-tab-{i}')
    tap('应用白名单', 'whitelist-entry')
    root = smoke.ui('whitelist-apps')
    assert {'应用保护', '路径保护'}.issubset({n.attrib.get('text') for n in root.iter('node')})
    smoke.tap_label('路径保护', 'whitelist-paths')
    smoke.capture('whitelist-paths')
    smoke.tap_label('应用保护', 'whitelist-apps')
    smoke.adb('shell', 'input', 'keyevent', '4')
    time.sleep(2)
    smoke.tap_label('首页', 'refactor-home-return')
    smoke.adb('shell', 'input', 'keyevent', '3')
    smoke.adb('shell', 'am', 'start', '-W', '-n', smoke.ACTIVITY)
    time.sleep(3)
    smoke.alive()
    # Uninstall is only for the disposable emulator's fresh-install test, never a user's device.
    smoke.adb('uninstall', smoke.APP)
    smoke.adb('install', str(apk), timeout=120)
    smoke.adb('shell', 'pm', 'grant', smoke.APP, 'android.permission.POST_NOTIFICATIONS', check=False)
    time.sleep(8)
    smoke.launch('refactor-fresh-install')
    smoke.save_text('passed.json', json.dumps({'versionCode': int(code), 'versionName': version,
        'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'android_api': smoke.adb('shell', 'getprop', 'ro.build.version.sdk'),
        'graphics_settings_modified': False, 'upgrade_data_preserved': True,
        'whitelist_pages_opened': True, 'cases': smoke.RESULTS}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        smoke.capture('refactor-failure')
        raise
