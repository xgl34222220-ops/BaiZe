"""Real signed-APK navigation/upgrade checks on a disposable emulator only."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import time
from xml.sax.saxutils import escape

spec = importlib.util.spec_from_file_location("startup", Path(__file__).with_name("smoke-release-apk.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
apk = Path(sys.argv[1]).resolve()
baseline = Path(os.environ["BAIZE_BASELINE_APK"])
expected = sys.argv[2]
previous = Path(sys.argv[3]).resolve()
previous_30007 = Path(sys.argv[4]).resolve()
previous_30008 = Path(sys.argv[5]).resolve()
previous_30009 = Path(sys.argv[6]).resolve()
previous_30010 = Path(sys.argv[7]).resolve()
previous_30011 = Path(sys.argv[8]).resolve()
previous_30012 = Path(sys.argv[9]).resolve()
previous_30013 = Path(sys.argv[10]).resolve()
previous_30014 = Path(sys.argv[11]).resolve()
assert hashlib.sha256(baseline.read_bytes()).hexdigest() == "01949f5a8f5e87e70bf2cc18e475f38bce28cdcd835ad731d0d9ea6565f97c80"
if hashlib.sha256(previous.read_bytes()).hexdigest() != "5c327020842f2e8f71d8549d06fc7bbd6a6468638bb4e28e8cbcc9f31f4b97c0":
    raise AssertionError("30006 baseline must be the exact delivered APK")
assert hashlib.sha256(previous_30007.read_bytes()).hexdigest() == "5f2f846b151d1e832f4c00f25d1502c36edecf974558c906b9289c9e7fa45dda"
assert hashlib.sha256(previous_30008.read_bytes()).hexdigest() == "7c3bcaec6359972f45702a9022861bd1e5cb5b0eea45bc566a39de521f067756"

assert hashlib.sha256(previous_30009.read_bytes()).hexdigest() == "2bf86de59a6b1273aa7d6e28b67412b71155c8a630b434a5caafdfed8207c07b"
assert hashlib.sha256(previous_30010.read_bytes()).hexdigest() == "71e9581aed76ac978e05449c0c8dc6d192e7e73faa1e7b512a9cf350137e0410"

assert hashlib.sha256(previous_30011.read_bytes()).hexdigest() == "7dfc05fd05ee8f33078606689c674c1da5211d752e38e451494d6bb44c8d8c59"


assert hashlib.sha256(previous_30012.read_bytes()).hexdigest() == "3b998e4837a8b9259d46ca94f4727ee4562efbed22d094bbda129c4ed048b2b0"


assert hashlib.sha256(previous_30013.read_bytes()).hexdigest() == "b2d3d4c506e043a43e41159005f095ffd241b067742b5c3d38f781d48f7512e0"
assert hashlib.sha256(previous_30014.read_bytes()).hexdigest() == "c13af975fb3097de5233a1a0e8b322e1971b98ca13544add6be0252c80481cb5"


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
    m.adb("install", str(previous_30008), timeout=120)
    m.launch("baseline-30008")
    mark = marker("preserve-30008-data")
    m.adb("push", str(seed), target)
    m.adb("shell", f"uid=$(stat -c %u /data/user/0/{m.APP}); chown $uid:$uid {target}; restorecon {target}")
    installed_candidate()
    assert m.adb("shell", "cat", mark) == "preserve-30008-data"
    assert json.loads(m.adb("shell", "cat", target))["selected"] == ["upgrade-fixture"]
    m.launch("candidate-from-30008")
    m.adb("uninstall", m.APP)
    m.adb("install", str(previous_30009), timeout=120)
    m.launch("baseline-30009")
    mark = marker("preserve-30009-data")
    m.adb("push", str(seed), target)
    cached_versions = {"rootVersionName": "2.0.0", "rootVersionCode": 30009,
        "moduleVersionName": "2.0.0", "moduleVersionCode": 30008, "moduleName": "synthetic compatibility fixture", "module": True}
    prefs = m.OUT / "synthetic-connection-diagnostics.xml"
    prefs.write_text('<map><string name="versions">' + escape(json.dumps(cached_versions)) +
        '</string><long name="versions_observed_at" value="1"/><string name="observed_app_name">2.0.0</string>' +
        '<long name="observed_app_code" value="30009"/></map>')
    prefs_target = f"/data/user/0/{m.APP}/shared_prefs/connection_diagnostics.xml"
    m.adb("shell", "am", "force-stop", m.APP)
    m.adb("push", str(prefs), prefs_target)
    m.adb("shell", f"uid=$(stat -c %u /data/user/0/{m.APP}); chown $uid:$uid {target} {prefs_target}; restorecon {target} {prefs_target}")
    m.launch("baseline-30009-compatible-banner")
    old_text = "\n".join(n.attrib.get("text", "") for n in m.ui("baseline-30009-banner").iter("node"))
    assert "已验证兼容" in old_text, "Reproduce the delivered 30009 banner with a synthetic historical observation"
    installed_candidate()
    assert m.adb("shell", "cat", mark) == "preserve-30009-data"
    assert json.loads(m.adb("shell", "cat", target))["selected"] == ["upgrade-fixture"]
    m.launch("candidate-from-30009-quiet-home")
    new_text = "\n".join(n.attrib.get("text", "") for n in m.ui("candidate-quiet-home").iter("node"))
    assert "已验证兼容" not in new_text and "历史版本缓存" not in new_text
    m.tap_label("设置", "candidate-quiet-settings")
    tap("白泽状态", "candidate-connection-details")
    detail_text = "\n".join(n.attrib.get("text", "") for n in m.ui("candidate-version-details").iter("node"))
    for attempt in range(4):
        if "历史版本缓存" in detail_text and "30008" in detail_text:
            break
        m.adb("shell", "input", "swipe", "200", "650", "200", "280", "250")
        time.sleep(1)
        detail_text = "\n".join(n.attrib.get("text", "") for n in m.ui(f"candidate-version-details-{attempt}").iter("node"))
    assert "历史版本缓存" in detail_text and "30008" in detail_text
    m.adb("uninstall", m.APP)
    m.adb("install", str(previous_30010), timeout=120)
    m.launch("baseline-30010")
    mark = marker("preserve-30010-data")
    m.adb("push", str(seed), target)
    m.adb("shell", f"uid=$(stat -c %u /data/user/0/{m.APP}); chown $uid:$uid {target}; restorecon {target}")
    installed_candidate()
    assert m.adb("shell", "cat", mark) == "preserve-30010-data"
    assert json.loads(m.adb("shell", "cat", target))["selected"] == ["upgrade-fixture"]
    m.launch("candidate-from-30010")
    m.adb("uninstall", m.APP)
    m.adb("install", str(previous_30011), timeout=120)
    m.launch("baseline-30011")
    mark = marker("preserve-30011-data")
    m.adb("push", str(seed), target)
    m.adb("shell", f"uid=$(stat -c %u /data/user/0/{m.APP}); chown $uid:$uid {target}; restorecon {target}")
    installed_candidate()
    assert m.adb("shell", "cat", mark) == "preserve-30011-data"
    assert json.loads(m.adb("shell", "cat", target))["selected"] == ["upgrade-fixture"]
    m.launch("candidate-from-30011")
    m.adb("uninstall", m.APP)
    m.adb("install", str(previous_30012), timeout=120)
    m.launch("baseline-30012")
    mark = marker("preserve-30012-data")
    m.adb("push", str(seed), target)
    m.adb("shell", f"uid=$(stat -c %u /data/user/0/{m.APP}); chown $uid:$uid {target}; restorecon {target}")
    installed_candidate()
    assert m.adb("shell", "cat", mark) == "preserve-30012-data"
    assert json.loads(m.adb("shell", "cat", target))["selected"] == ["upgrade-fixture"]
    m.launch("candidate-from-30012")
    m.adb("uninstall", m.APP)
    m.adb("install", str(previous_30013), timeout=120)
    m.launch("baseline-30013")
    mark = marker("preserve-30013-data")
    m.adb("push", str(seed), target)
    m.adb("shell", f"uid=$(stat -c %u /data/user/0/{m.APP}); chown $uid:$uid {target}; restorecon {target}")
    installed_candidate()
    assert m.adb("shell", "cat", mark) == "preserve-30013-data"
    assert json.loads(m.adb("shell", "cat", target))["selected"] == ["upgrade-fixture"]
    m.launch("candidate-from-30013")
    m.adb("uninstall", m.APP)
    m.adb("install", str(previous_30014), timeout=120)
    m.launch("baseline-30014")
    mark = marker("preserve-30014-data")
    m.adb("push", str(seed), target)
    m.adb("shell", f"uid=$(stat -c %u /data/user/0/{m.APP}); chown $uid:$uid {target}; restorecon {target}")
    installed_candidate()
    assert m.adb("shell", "cat", mark) == "preserve-30014-data"
    assert json.loads(m.adb("shell", "cat", target))["selected"] == ["upgrade-fixture"]
    m.launch("candidate-from-30014")
    m.adb("uninstall", m.APP)
    m.adb("install", str(apk), timeout=120)
    m.adb("shell", "pm", "grant", m.APP, "android.permission.POST_NOTIFICATIONS", check=False)
    m.launch("candidate-fresh-install")
    m.tap_label("清理", "fresh-clean-tab")
    tap("扫描工作台", "fresh-workbench")
    expect_top("ScanWorkbenchActivity", "fresh-workbench", single_workbench=True)
    back("fresh-workbench-back")
    seven_spec = importlib.util.spec_from_file_location("seven_navigation", Path(__file__).with_name("smoke-seven-improvements.py"))
    seven = importlib.util.module_from_spec(seven_spec)
    seven_spec.loader.exec_module(seven)
    seven_navigation = seven.run(m, expect_top)
    # Only this repository's release APK is introduced into the disposable emulator.
    # The ordinary App process must read/render its real archive icon after minification.
    fixture_name = "BaiZe-preview-fixture.apk"
    fixture_path = f"/sdcard/Download/{fixture_name}"
    m.adb("push", str(apk), fixture_path)
    m.adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file://{fixture_path}")
    m.adb("shell", "appops", "set", m.APP, "MANAGE_EXTERNAL_STORAGE", "allow")
    m.adb("shell", "am", "start", "-W", "-n", f"{m.APP}/.ApkScanActivity")
    time.sleep(2)
    expect_top("ApkScanActivity", "apk-artwork-entry")
    tap("开始扫描", "apk-artwork-scan")
    apk_state = m.ui("apk-local-mode-before")
    local_mode_selected = any(n.attrib.get("text") == "仅本地清理" for n in apk_state.iter("node"))
    if local_mode_selected:
        tap("仅本地清理", "apk-local-mode-explanation")
        tap("使用本地模式", "apk-local-mode-confirmed")
        mode_text = "\n".join(n.attrib.get("text", "") for n in m.ui("apk-local-mode-result").iter("node"))
        assert "本地模式" in mode_text and "保护名单尚未核对" not in mode_text
    # The emulator can use English resources even though the review UI is Chinese.
    # Reuse the independently verified archive label, never guess it from the filename.
    artwork_probe = json.loads((Path(os.environ["RUNNER_TEMP"]) / "baize-apk-artwork-probe/result.json").read_text())
    expected_archive_label = artwork_probe["archiveLabel"]
    assert artwork_probe["passed"] and expected_archive_label
    expected_archive_version = re.search(r"versionName=([^\r\n]+)", m.adb("shell", "dumpsys", "package", m.APP)).group(1).strip()
    artwork_seen = False
    for attempt in range(15):
        tree = m.ui(f"apk-artwork-await-{attempt}")
        labels = "\n".join(n.attrib.get("text", "") for n in tree.iter("node"))
        descriptions = "\n".join(n.attrib.get("content-desc", "") for n in tree.iter("node"))
        label_lines = {line for node in tree.iter("node") for line in node.attrib.get("text", "").splitlines()}
        if fixture_name in labels and expected_archive_label in label_lines and f"版本 {expected_archive_version}" in labels and "来自安装包的应用图标" in descriptions:
            artwork_seen = True
            break
        # On a 320x640 device the first card extends below the viewport. Inspect the
        # actual scrollable result before concluding that its filename/artwork is absent.
        if attempt in (1, 3, 5):
            m.adb("shell", "input", "swipe", "160", "460", "160", "220", "300")
        time.sleep(1)
    assert artwork_seen, "The release APK must show its own archived label, version and decoded icon"
    m.capture("apk-artwork-final-release")
    tree = m.ui("apk-selection-before-rotation")
    checkbox = next(n for n in tree.iter("node") if n.attrib.get("content-desc") == f"选择安装包{fixture_name}")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", checkbox.attrib["bounds"]))
    m.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(1)
    def selected_apk_review(name):
        tree = m.ui(name)
        labels = "\n".join(n.attrib.get("text", "") for n in tree.iter("node"))
        assert "移入回收站 1 个安装包" in labels, "APK review selection must survive Activity recreation"
        expect_top("ApkScanActivity", name)
    selected_apk_review("apk-selected-portrait")
    m.adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    m.adb("shell", "settings", "put", "system", "user_rotation", "1")
    time.sleep(3)
    selected_apk_review("apk-selected-landscape")
    m.capture("apk-selection-retained-landscape")
    m.adb("shell", "settings", "put", "system", "user_rotation", "0")
    time.sleep(3)
    selected_apk_review("apk-selection-retained-portrait")
    m.capture("apk-selection-retained-portrait")
    m.alive()
    m.save_text("passed.json", json.dumps({"versionCode": int(expected), "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "android_api": m.adb("shell", "getprop", "ro.build.version.sdk"), "official_upgrade_preserved_data": True,
        "30006_upgrade_preserved_review_and_selection": True, "fresh_install": True, "four_navigation_tabs": True,
        "30007_upgrade_preserved_review_and_selection": True,
        "30008_upgrade_preserved_review_and_selection": True,
        "30009_upgrade_preserved_review_and_selection": True,
        "30010_upgrade_preserved_review_and_selection": True,
        "30011_upgrade_preserved_review_and_selection": True,
        "30012_upgrade_preserved_review_and_selection": True,
        "30013_upgrade_preserved_review_and_selection": True,
        "30014_upgrade_preserved_review_and_selection": True,
        "old_compatible_banner_reproduced_and_removed": True, "historical_versions_available_in_settings": True,
        "baseline_scan_stack": old_scan_stack, "candidate_scan_stack": new_scan_stack,
        "baseline_deep_stack": old_deep_stack, "candidate_deep_stack": new_deep_stack,
        "repeated_tap_single_page": True, "single_back_to_origin": True, "background_foreground": True,
        "organizer_without_module": True, "no_app_crash_or_anr": True,
        "release_apk_archive_icon_label_version": True,
        "apk_review_selection_survives_rotation": True,
        "seven_improvements_navigation": seven_navigation,
        "apk_explicit_local_mode_confirmed": local_mode_selected,
        "limit": "Emulator navigation and installation; actual root-manager cleaning remains unverified"}, ensure_ascii=False, indent=2))
    print((m.OUT / "passed.json").read_text())
except Exception:
    m.capture("failure")
    raise
