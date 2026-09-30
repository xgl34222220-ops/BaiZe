"""Real signed-APK navigation/upgrade checks on a disposable emulator only."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import time

spec = importlib.util.spec_from_file_location("startup", Path(__file__).with_name("smoke-release-apk.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
apk = Path(sys.argv[1]).resolve()
baseline = Path(os.environ["BAIZE_BASELINE_APK"])
expected = sys.argv[2]
previous = Path(sys.argv[3]).resolve()
previous_30007 = Path(sys.argv[4]).resolve()
assert hashlib.sha256(baseline.read_bytes()).hexdigest() == "01949f5a8f5e87e70bf2cc18e475f38bce28cdcd835ad731d0d9ea6565f97c80"
if hashlib.sha256(previous.read_bytes()).hexdigest() != "5c327020842f2e8f71d8549d06fc7bbd6a6468638bb4e28e8cbcc9f31f4b97c0":
    raise AssertionError("30006 baseline must be the exact delivered APK")
assert hashlib.sha256(previous_30007.read_bytes()).hexdigest() == "5f2f846b151d1e832f4c00f25d1502c36edecf974558c906b9289c9e7fa45dda"


def tap(text, name, repeats=1):
    root = m.ui(name + "-before")
    nodes = [n for n in root.iter("node") if n.attrib.get("text") == text]
    if not nodes:
        raise AssertionError(f"Missing action {text}")
    bounds = list(map(int, re.findall(r"\d+", nodes[0].attrib["bounds"])))
    x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
    m.adb("shell", "; ".join([f"input tap {x} {y}"] * repeats))
    time.sleep(3)
    m.alive()
    m.capture(name)
    return m.ui(name)


def stack(name):
    raw = m.adb("shell", "dumpsys", "activity", "activities")
    m.save_text(name + "-stack.txt", raw)
    return re.findall(r"\* Hist\s+#\d+: ActivityRecord\{[^\n]*?" + re.escape(m.APP) + r"/\.([\w.]+)", raw)


def expect_top(component, name, single_workbench=False):
    pages = stack(name)
    if not pages or pages[0] != component:
        raise AssertionError(f"Unexpected page stack: {pages}; expected {component}")
    if single_workbench and pages.count("ScanWorkbenchActivity") != 1:
        raise AssertionError(f"Duplicate workbench Activity: {pages}")
    return pages


def back(name):
    m.adb("shell", "input", "keyevent", "4")
    time.sleep(2)
    m.alive()
    expect_top("MiuixDashboardActivity", name)


def marker(text):
    path = f"/data/user/0/{m.APP}/files/workbench-upgrade-marker.txt"
    m.adb("shell", f"mkdir -p /data/user/0/{m.APP}/files; echo {text} > {path}; uid=$(stat -c %u /data/user/0/{m.APP}); chown $uid:$uid {path}; restorecon {path}")
    return path


def installed_candidate():
    m.adb("install", "-r", str(apk), timeout=120)
    if f"versionCode={expected} " not in m.adb("shell", "dumpsys", "package", m.APP):
        raise AssertionError("Installed version is not the requested candidate")


try:
    m.settle_emulator_boot()
    m.adb("root"); m.adb("wait-for-device")
    m.adb("install", "-r", str(baseline), timeout=120)
    m.launch("baseline-official")
    mark = marker("preserve-official-data")
    installed_candidate()
    assert m.adb("shell", "cat", mark) == "preserve-official-data"
    m.launch("candidate-from-official")

    m.adb("uninstall", m.APP)
    m.adb("install", str(previous), timeout=120)
    m.launch("baseline-30006")
    m.tap_label("清理", "baseline-clean-tab")
    tap("扫描工作台", "baseline-scan-entry")
    old_scan_stack = expect_top("ResumableSmartScanActivity", "baseline-scan")
    back("baseline-scan-back")
    tap("深度清理", "baseline-deep-entry")
    old_deep_stack = expect_top("ProfileActivity", "baseline-deep")
    back("baseline-deep-back")
    m.save_text("baseline-navigation.json", json.dumps({"scan": old_scan_stack, "deep": old_deep_stack,
        "limit": "Root-free emulator reproduces entry stack; old Profile follow-up is covered by source and JVM navigation assertions"}, indent=2))
    mark = marker("preserve-30006-data")
    # A synthetic expired review verifies real upgrade/read/render; no real file is scanned or deleted.
    review = {"items": [{"id": "upgrade-fixture", "source": "profile", "profile": "rules", "packageName": "test.fixture",
        "appName": "升级验证应用", "category": "rule_trash", "groupKey": "app:test.fixture", "groupTitle": "升级验证应用",
        "title": "升级前扫描记录", "risk": "low", "path": "/synthetic/upgrade-only.log", "bytes": 1024, "files": 1,
        "directories": 0, "reason": "仅测试记录", "selectable": True}], "selected": ["upgrade-fixture"],
        "scanReady": False, "phase": "升级前扫描记录", "notice": "WARNING"}
    seed = m.OUT / "synthetic-review.json"
    seed.write_text(json.dumps(review, ensure_ascii=False))
    target = f"/data/user/0/{m.APP}/files/scan-review-safe.json"
    m.adb("push", str(seed), target)
    m.adb("shell", f"uid=$(stat -c %u /data/user/0/{m.APP}); chown $uid:$uid {target}; restorecon {target}")
    installed_candidate()
    assert m.adb("shell", "cat", mark) == "preserve-30006-data"
    assert json.loads(m.adb("shell", "cat", target))["selected"] == ["upgrade-fixture"]
    m.launch("candidate-from-30006")
    m.tap_label("清理", "candidate-clean-tab")
    tap("扫描工作台", "candidate-workbench-repeated-tap", repeats=3)
    new_scan_stack = expect_top("ScanWorkbenchActivity", "candidate-scan", single_workbench=True)
    assert "ResumableSmartScanActivity" not in new_scan_stack and "ProfileActivity" not in new_scan_stack
    m.adb("shell", "input", "keyevent", "3")
    m.adb("shell", "am", "start", "-W", "-n", f"{m.APP}/.ScanWorkbenchActivity")
    time.sleep(2)
    expect_top("ScanWorkbenchActivity", "candidate-foreground", single_workbench=True)
    back("candidate-single-back")
    tap("扫描工作台", "candidate-reopen")
    expect_top("ScanWorkbenchActivity", "candidate-reopen", single_workbench=True)
    assert json.loads(m.adb("shell", "cat", target))["items"][0]["id"] == "upgrade-fixture"
    back("candidate-reopen-back")
    tap("深度清理", "candidate-deep")
    new_deep_stack = expect_top("ScanWorkbenchActivity", "candidate-deep", single_workbench=True)
    assert "ProfileActivity" not in new_deep_stack
    back("candidate-deep-back")
    tap("卸载残留", "candidate-corpses")
    expect_top("ScanWorkbenchActivity", "candidate-corpses", single_workbench=True)
    back("candidate-corpses-back")
    for index, label in enumerate(["记录", "设置", "首页", "清理"]):
        m.tap_label(label, f"candidate-navigation-{index}")
    tap("文件归类", "organizer-no-module")
    expect_top("FileOrganizerActivity", "organizer-no-module")
    m.adb("uninstall", m.APP)
    m.adb("install", str(previous_30007), timeout=120)
    m.launch("baseline-30007")
    mark = marker("preserve-30007-data")
    m.adb("push", str(seed), target)
    m.adb("shell", f"uid=$(stat -c %u /data/user/0/{m.APP}); chown $uid:$uid {target}; restorecon {target}")
    installed_candidate()
    assert m.adb("shell", "cat", mark) == "preserve-30007-data"
    assert json.loads(m.adb("shell", "cat", target))["selected"] == ["upgrade-fixture"]
    m.launch("candidate-from-30007")
    m.adb("uninstall", m.APP)
    m.adb("install", str(apk), timeout=120)
    m.adb("shell", "pm", "grant", m.APP, "android.permission.POST_NOTIFICATIONS", check=False)
    m.launch("candidate-fresh-install")
    m.tap_label("清理", "fresh-clean-tab")
    tap("扫描工作台", "fresh-workbench")
    expect_top("ScanWorkbenchActivity", "fresh-workbench", single_workbench=True)
    m.alive()
    m.save_text("passed.json", json.dumps({"versionCode": int(expected), "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "android_api": m.adb("shell", "getprop", "ro.build.version.sdk"), "official_upgrade_preserved_data": True,
        "30006_upgrade_preserved_review_and_selection": True, "fresh_install": True, "four_navigation_tabs": True,
        "30007_upgrade_preserved_review_and_selection": True,
        "baseline_scan_stack": old_scan_stack, "candidate_scan_stack": new_scan_stack,
        "baseline_deep_stack": old_deep_stack, "candidate_deep_stack": new_deep_stack,
        "repeated_tap_single_page": True, "single_back_to_origin": True, "background_foreground": True,
        "organizer_without_module": True, "no_app_crash_or_anr": True,
        "limit": "Emulator navigation and installation; actual root-manager cleaning remains unverified"}, ensure_ascii=False, indent=2))
    print((m.OUT / "passed.json").read_text())
except Exception:
    m.capture("failure")
    raise
