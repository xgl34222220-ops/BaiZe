"""Test the signed candidate on a disposable emulator; never touch a user's device."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import time

spec = importlib.util.spec_from_file_location("startup", Path(__file__).with_name("smoke-release-apk.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
apk = Path(sys.argv[1]).resolve()
baseline = Path(os.environ["BAIZE_BASELINE_APK"])
expected = sys.argv[2]
if hashlib.sha256(baseline.read_bytes()).hexdigest() != "01949f5a8f5e87e70bf2cc18e475f38bce28cdcd835ad731d0d9ea6565f97c80":
    raise AssertionError("Upgrade baseline is not the verified v2.0.0 release")
try:
    m.settle_emulator_boot()
    m.adb("root")
    m.adb("wait-for-device")
    m.adb("install", "-r", str(baseline), timeout=120)
    m.launch("baseline-v2.0.0")
    marker = f"/data/user/0/{m.APP}/files/workbench-upgrade-marker.txt"
    m.adb("shell", f"mkdir -p /data/user/0/{m.APP}/files; echo preserve-workbench-history > {marker}; uid=$(stat -c %u /data/user/0/{m.APP}); chown $uid:$uid {marker}")
    m.adb("install", "-r", str(apk), timeout=120)
    if m.adb("shell", "cat", marker) != "preserve-workbench-history":
        raise AssertionError("Cover installation did not retain App data")
    installed = m.adb("shell", "dumpsys", "package", m.APP)
    if f"versionCode={expected} " not in installed:
        raise AssertionError("Installed candidate build code does not match")
    m.launch("candidate-upgrade")
    for index, label in enumerate(["清理", "记录", "设置", "首页"]):
        m.tap_label(label, f"candidate-navigation-{index}")
    m.adb("shell", "am", "start", "-W", "-n", f"{m.APP}/.FileOrganizerActivity")
    time.sleep(5)
    m.alive()
    root = m.ui("organizer-no-module")
    if "文件归类" not in {n.attrib.get("text") for n in root.iter("node")}:
        raise AssertionError("Organizer Activity did not render")
    m.capture("organizer-no-module")
    m.adb("uninstall", m.APP)
    m.adb("install", str(apk), timeout=120)
    m.adb("shell", "pm", "grant", m.APP, "android.permission.POST_NOTIFICATIONS", check=False)
    m.launch("candidate-fresh-install")
    m.adb("shell", "input", "keyevent", "3")
    m.adb("shell", "am", "start", "-W", "-n", m.ACTIVITY)
    time.sleep(3)
    m.alive()
    m.save_text("passed.json", json.dumps({"versionCode": int(expected), "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "android_api": m.adb("shell", "getprop", "ro.build.version.sdk"), "upgrade_preserved_data": True,
        "fresh_install": True, "four_navigation_tabs": True, "organizer_without_module": True,
        "background_foreground": True, "no_app_crash_or_anr": True,
        "limit": "Emulator UI and installation smoke; real root-manager device cleaning remains unverified"}, ensure_ascii=False, indent=2))
    print((m.OUT / "passed.json").read_text())
except Exception:
    m.capture("failure")
    raise
