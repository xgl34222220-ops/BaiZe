"""Real runtime storage permission flow on a disposable API 28/29 emulator; read-only files."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import sys
import time
import uuid

def module(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value

m = module("legacy_storage_base", "smoke-release-apk.py")
seven = module("legacy_storage_navigation", "smoke-seven-improvements.py")
api = int(m.adb("shell", "getprop", "ro.build.version.sdk"))
assert api in (28, 29)
assert "sdk" in m.adb("shell", "getprop", "ro.product.model").lower() or "generic" in m.adb("shell", "getprop", "ro.build.fingerprint")
m.OUT = Path(os.environ["RUNNER_TEMP"]) / f"baize-legacy-storage-{api}"
m.OUT.mkdir(parents=True, exist_ok=True)
apk = Path(sys.argv[1]).resolve()
permissions = ("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE")
folder = "/storage/emulated/0/Download/baize-legacy-" + uuid.uuid4().hex
fixture = m.OUT / "unindexed.bin"
fixture.write_bytes(b"BAIZE_READ_ONLY_PERMISSION_FIXTURE" * 64)
expected = hashlib.sha256(fixture.read_bytes()).hexdigest()

def expect_top(component, name):
    raw = m.adb("shell", "dumpsys", "activity", "activities")
    m.save_text(name + "-stack.txt", raw)
    pages = re.findall(r"\* Hist\s+#\d+: ActivityRecord\{[^\n]*?" + re.escape(m.APP) + r"/\.([\w.]+)", raw)
    assert pages and pages[0] == component, (component, pages)
    return pages

nav = seven.NavigationSmoke(m, expect_top)

def system_permission(label, name):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        root = m.ui(name)
        nodes = [n for text in label for n in nav.matching(root, text)]
        if nodes:
            assert any("permissioncontroller" in n.attrib.get("package", "") or "packageinstaller" in n.attrib.get("package", "")
                       for n in root.iter("node")), "Must be the real Android permission dialog"
            nav.click_node(root, nodes[0], name); return
        time.sleep(.5)
    raise AssertionError("Permission dialog button not found: " + str(label))

def granted(permission):
    return bool(re.search(re.escape(permission) + r": granted=true", m.adb("shell", "dumpsys", "package", m.APP)))

def enter():
    m.launch("legacy-home", 3)
    m.tap_label("首页", "legacy-home-tab")
    nav.find("整理空间", "legacy-shortcuts", direction="up")
    nav.tap("存储分析", "legacy-analysis")
    expect_top("StorageToolsActivity", "legacy-analysis")

try:
    m.adb("install", "-r", str(apk))
    for permission in permissions: m.adb("shell", "pm", "revoke", m.APP, permission, check=False)
    m.adb("shell", "mkdir", "-p", folder)
    m.adb("push", str(fixture), folder + "/unindexed.bin")
    enter()
    nav.wait_text("开启存储读写权限", "legacy-initial-denied")
    nav.tap("开启存储读写权限", "legacy-first-request")
    system_permission(("Deny", "拒绝"), "legacy-real-deny")
    nav.wait_text("开启存储读写权限", "legacy-denied-no-scan")
    assert not any(granted(p) for p in permissions)
    nav.tap("开启存储读写权限", "legacy-second-request")
    system_permission(("Allow", "允许"), "legacy-real-grant")
    assert all(granted(p) for p in permissions), m.adb("shell", "dumpsys", "package", m.APP)
    nav.wait_text("存储分析完成", "legacy-granted-scan", timeout=90)
    nav.find("已遍历目录占用", "legacy-directory-count", direction="up")
    nav.evidence("legacy-real-read-write-grant", "StorageToolsActivity")
    m.adb("shell", "pm", "revoke", m.APP, permissions[1])
    enter()
    nav.wait_text("开启存储读写权限", "legacy-read-only-insufficient")
    assert granted(permissions[0]) and not granted(permissions[1])
    for permission in permissions:
        m.adb("shell", "pm", "revoke", m.APP, permission, check=False)
        m.adb("shell", "pm", "set-permission-flags", m.APP, permission, "user-set", "user-fixed")
    enter()
    nav.wait_text("开启存储读写权限", "legacy-permanent-denied")
    nav.tap("开启存储读写权限", "legacy-app-settings-fallback")
    root = m.ui("legacy-real-app-settings")
    assert any(n.attrib.get("package") == "com.android.settings" for n in root.iter("node"))
    assert "白泽" in seven.labels(root)
    for permission in permissions:
        m.adb("shell", "pm", "clear-permission-flags", m.APP, permission, "user-fixed", "user-set")
        m.adb("shell", "pm", "grant", m.APP, permission)
    m.adb("shell", "input", "keyevent", "4")
    nav.wait_text("存储分析完成", "legacy-return-from-settings", timeout=90)
    assert m.adb("shell", "sha256sum", folder + "/unindexed.bin").split()[0] == expected
    m.alive(); nav.evidence("legacy-final", "StorageToolsActivity")
    version = int(re.search(r"versionCode=(\d+)", m.adb("shell", "dumpsys", "package", m.APP)).group(1))
    m.save_text("passed.json", json.dumps({"passed":True,"androidApi":api,"versionCode":version,
        "actualRuntimePermissionDialog":True,"denyDoesNotStartScan":True,"bothReadAndWriteGranted":True,
        "readAloneInsufficient":True,"permanentDenialRoutesToAppSettings":True,"resumeAfterSettingsGrant":True,
        "directoryScannerVisible":True,"testFileHashUnchanged":True,"noUserFileDeletion":True,"noCrashOrAnr":True},indent=2))
except Exception:
    m.capture("legacy-failed"); raise
