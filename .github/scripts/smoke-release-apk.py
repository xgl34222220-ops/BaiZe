"""Exercise the final minified APK on a disposable CI emulator.
ContentProvider startup is real. No App instrumentation or glass setting override.
"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APP = 'io.github.xgl34222220.baize'
ACTIVITY = f'{APP}/.MiuixDashboardActivity'
OUT = Path(os.environ['RUNNER_TEMP']) / 'baize-apk-startup'
OUT.mkdir(parents=True, exist_ok=True)
RESULTS: list[dict] = []


def adb(*args: str, timeout: int = 40, check: bool = True) -> str:
    p = subprocess.run(['adb', *args], capture_output=True, text=True, timeout=timeout)
    if check and p.returncode:
        raise RuntimeError(f'adb {args}: {p.stdout}\n{p.stderr}')
    return p.stdout.strip()


def save_text(name: str, text: str) -> None:
    (OUT / name).write_text(text, encoding='utf-8')


def capture(name: str) -> None:
    save_text(f'{name}-crash.txt', adb('logcat', '-d', '-b', 'crash', '-v', 'threadtime', check=False))
    save_text(f'{name}-logcat.txt', adb('logcat', '-d', '-v', 'threadtime', check=False))
    save_text(f'{name}-activities.txt', adb('shell', 'dumpsys', 'activity', 'activities', check=False))
    save_text(f'{name}-gfxinfo.txt', adb('shell', 'dumpsys', 'gfxinfo', APP, check=False))
    p = subprocess.run(['adb', 'exec-out', 'screencap', '-p'], capture_output=True, timeout=30)
    if p.returncode == 0:
        (OUT / f'{name}.png').write_bytes(p.stdout)


def ui(name: str) -> ET.Element:
    adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/baize-startup.xml', timeout=45)
    text = adb('shell', 'cat', '/sdcard/baize-startup.xml')
    save_text(f'{name}.xml', text)
    return ET.fromstring(text)


def alive() -> str:
    pid = adb('shell', 'pidof', APP, check=False)
    if not pid:
        raise AssertionError('Final signed APK process exited')
    crash = adb('logcat', '-d', '-b', 'crash')
    if f'Process: {APP}' in crash or f'>>> {APP} <<<' in crash:
        raise AssertionError('App crash detected in Android crash buffer')
    # A live PID is not proof of responsiveness. Do not click Wait or extend the
    # platform ANR timeout to make a failed startup pass.
    full = adb('logcat', '-d')
    if f'ANR in {APP}' in full:
        raise AssertionError('Android reported an App ANR')
    return pid


def launch(name: str, wait: int = 8) -> ET.Element:
    adb('shell', 'am', 'force-stop', APP)
    adb('logcat', '-c')
    save_text(f'{name}-launch.txt', adb('shell', 'am', 'start', '-W', '-n', ACTIVITY, timeout=45))
    time.sleep(wait)
    try:
        pid = alive()
        root = ui(name)
        labels = {n.attrib.get('text') for n in root.iter('node')}
        if not {'首页', '清理', '记录', '设置'}.issubset(labels):
            raise AssertionError(f'Launcher navigation is not displayed: {labels}')
        alive()
        RESULTS.append({'case': name, 'pid': pid, 'four_tabs_visible': True})
        return root
    finally:
        capture(name)


def tap_label(label: str, name: str) -> None:
    root = ui(name + '-before')
    nodes = [n for n in root.iter('node') if n.attrib.get('text') == label]
    if not nodes:
        raise AssertionError(f'No navigation label: {label}')
    points = [list(map(int, re.findall(r'\d+', n.attrib['bounds']))) for n in nodes]
    x1, y1, x2, y2 = max(points, key=lambda p: p[1])
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(2)
    alive()
    after = ui(name)
    selected = [n for n in after.iter('node') if n.attrib.get('selected') == 'true']
    if not any(any(child.attrib.get('text') == label for child in n.iter('node')) for n in selected):
        raise AssertionError(f'Tab selection not updated: {label}')
    RESULTS.append({'case': name, 'selected_tab': label, 'pid': alive()})
    capture(name)


def settle_emulator_boot() -> None:
    # First attempt coincided with 90% CPU pressure, memory reclaim and first-boot
    # Google package setup. Let the emulator finish booting BEFORE any App launch.
    # This does not change App effects, compile the App, or alter Android ANR limits.
    stats = []
    for _ in range(6):
        stats.append({'time': time.time(), 'boot': adb('shell', 'getprop', 'sys.boot_completed'),
                      'cpu_pressure': adb('shell', 'cat', '/proc/pressure/cpu', check=False),
                      'memory_pressure': adb('shell', 'cat', '/proc/pressure/memory', check=False)})
        time.sleep(15)
    save_text('emulator-boot.json', json.dumps(stats, indent=2))
    if stats[-1]['boot'] != '1':
        raise AssertionError('Emulator did not finish booting')


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit('Usage: smoke-release-apk.py MINIFIED_APK EXPECTED_VERSION_CODE')
    apk = Path(sys.argv[1]).resolve()
    code = sys.argv[2]
    baseline = Path(os.environ['BAIZE_BASELINE_APK']).resolve()
    if hashlib.sha256(baseline.read_bytes()).hexdigest() != '570ece2a97e348d392ed45fd89b7ab452937af3cb58f923744f6260ef9021f76':
        raise AssertionError('Baseline must be the exact failed 30001 formal APK')
    adb('shell', 'input', 'keyevent', '82')
    settle_emulator_boot()
    adb('install', '-r', str(baseline), timeout=120)
    time.sleep(8)
    adb('logcat', '-c')
    adb('shell', 'am', 'start', '-W', '-n', ACTIVITY, timeout=45, check=False)
    time.sleep(5)
    capture('baseline-30001')
    if 'WorkDatabase_Impl.<init>' not in (OUT / 'baseline-30001-crash.txt').read_text():
        raise AssertionError('Baseline did not reproduce the known constructor failure')
    adb('root', check=False)
    adb('wait-for-device')
    data_dir = f'/data/user/0/{APP}'
    marker = f'{data_dir}/files/startup-upgrade-marker.txt'
    adb('shell', 'mkdir', '-p', f'{data_dir}/files')
    adb('shell', f'echo preserve-30001-user-data > {marker}')
    adb('shell', f'uid=$(stat -c %u {data_dir}); chown $uid:$uid {data_dir}/files {marker}; chmod 700 {data_dir}/files; chmod 600 {marker}; restorecon -RF {data_dir}/files')
    adb('install', '-r', str(apk), timeout=120)
    adb('shell', 'pm', 'grant', APP, 'android.permission.POST_NOTIFICATIONS', check=False)
    time.sleep(8)
    package = adb('shell', 'dumpsys', 'package', APP)
    if f'versionCode={code} ' not in package:
        raise AssertionError('Wrong build installed')
    save_text('package.txt', package)
    launch('upgrade-first-launch')
    if adb('shell', 'cat', marker) != 'preserve-30001-user-data':
        raise AssertionError('Upgrade cleared App data')
    launch('upgrade-cold-relaunch')
    for index, label in enumerate(['清理', '记录', '设置', '首页']):
        tap_label(label, f'navigate-{index}')
    # Uninstall is confined to this disposable emulator's fresh-install test.
    adb('uninstall', APP)
    adb('install', str(apk), timeout=120)
    adb('shell', 'pm', 'grant', APP, 'android.permission.POST_NOTIFICATIONS', check=False)
    time.sleep(8)
    launch('fresh-install')
    adb('shell', 'input', 'keyevent', '3')
    adb('shell', 'am', 'start', '-W', '-n', ACTIVITY)
    time.sleep(3)
    RESULTS.append({'case': 'background-foreground', 'pid': alive()})
    save_text('passed.json', json.dumps({'versionCode': int(code), 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
        'android_api': adb('shell', 'getprop', 'ro.build.version.sdk'),
        'graphics_settings_modified': False, 'anr_check': True, 'emulator_boot_settle_seconds': 90,
        'cases': RESULTS}, ensure_ascii=False, indent=2))
    print((OUT / 'passed.json').read_text())


if __name__ == '__main__':
    try:
        main()
    except Exception:
        capture('failure')
        adb('root', check=False)
        adb('wait-for-device')
        adb('pull', '/data/anr', str(OUT / 'anr'), check=False, timeout=60)
        save_text('failure-exit-info.txt', adb('shell', 'dumpsys', 'activity', 'exit-info', APP, check=False))
        raise
