"""Actual signed BaiZe -> Shizuku permission UI -> UserService UID 2000 -> cache-only.

Disposable SDK emulator only. Seeds and clears two test-owned packages, never a user app.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import sys
import time

def module(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value

m = module("shizuku_smoke_base", "smoke-release-apk.py")
seven = module("shizuku_smoke_navigation", "smoke-seven-improvements.py")
evidence = module("shizuku_smoke_evidence", "access-fixture-evidence.py")
m.OUT = Path(os.environ["RUNNER_TEMP"]) / "baize-shizuku-device"
m.OUT.mkdir(parents=True, exist_ok=True)
apk, manager, fixtures = (Path(p).resolve() for p in sys.argv[1:4])
mode = sys.argv[4] if len(sys.argv) > 4 else "shell"
assert mode in ("shell", "root")
expected_uid = "2000" if mode == "shell" else "0"
assert "sdk" in m.adb("shell", "getprop", "ro.product.model").lower() or "generic" in m.adb("shell", "getprop", "ro.build.fingerprint")
assert m.adb("shell", "getprop", "ro.build.version.sdk") == "36"
selected, retained = "test.baize.cache.selected", "test.baize.cache.retained"
m.settle_emulator_boot()

def command(package, text):
    assert package in (selected, retained)
    return m.adb("shell", f"run-as {package} sh -c " + shlex.quote(text))

def stack(name):
    raw = m.adb("shell", "dumpsys", "activity", "activities")
    m.save_text(name + "-stack.txt", raw)
    return re.findall(r"\* Hist\s+#\d+: ActivityRecord\{[^\n]*?" + re.escape(m.APP) + r"/\.([\w.]+)", raw)

def expect_top(component, name):
    pages = stack(name)
    assert pages and pages[0] == component, (component, pages)
    return pages

def wait_shell_process(token):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        processes = m.adb("shell", "ps", "-A", "-o", "UID,PID,NAME,ARGS")
        if any(line.split()[0] == expected_uid and token in line for line in processes.splitlines()): return processes
        time.sleep(.5)
    raise AssertionError("Privileged process did not become ready with UID " + expected_uid + ": " + token)

try:
    m.adb("install", "-r", str(apk))
    private = evidence.seed_private_data(m, apk)
    m.adb("install", str(manager))
    for package, kind in ((selected, "selected"), (retained, "retained")):
        m.adb("install", str(fixtures / f"{kind}.apk"))
        command(package, "mkdir -p cache files; head -c 16384 /dev/zero > cache/owned.bin; printf BAIZE_CI_PERSISTENT_DATA > files/keep.txt")
        assert command(package, "stat -c %s cache/owned.bin") == "16384"
    m.adb("unroot" if mode == "shell" else "root", check=False)
    m.adb("wait-for-device")
    assert m.adb("shell", "id", "-u") == expected_uid, "The Shizuku server must use the requested privilege mode"
    path = m.adb("shell", "pm", "path", "moe.shizuku.privileged.api").removeprefix("package:")
    assert path.endswith("/base.apk") and "\n" not in path, path
    starter = str(Path(path).parent / "lib/x86_64/libshizuku.so")
    m.save_text("manager-starter.txt", m.adb("shell", shlex.quote(starter), timeout=60))
    processes = wait_shell_process("shizuku_server")
    m.save_text("shell-server-processes.txt", processes)
    m.adb("shell", "pm", "grant", m.APP, "android.permission.POST_NOTIFICATIONS", check=False)
    m.launch("shizuku-fresh-home", 3)
    nav = seven.NavigationSmoke(m, expect_top)
    m.tap_label("清理", "shizuku-clean-tab")
    nav.triple_tap("免 Root 缓存清理", "shizuku-entry", "ShizukuCacheActivity")
    nav.tap("使用本地保护规则", "shizuku-local-protection")
    nav.tap("连接 Shizuku", "shizuku-connect")
    time.sleep(2)
    root = m.ui("shizuku-permission")
    assert any(n.attrib.get("package", "").startswith("moe.shizuku") for n in root.iter("node")), "The real manager permission prompt must be shown"
    assert any(label in seven.labels(root) for label in ("白泽", "BaiZe")), seven.labels(root)
    deny = next((n for label in ("Deny", "Don't allow", "拒绝", "不允许") for n in nav.matching(root, label)), None)
    assert deny is not None, seven.labels(root)
    nav.click_node(root, deny, "shizuku-real-deny")
    nav.wait_text("未授权 Shizuku，未执行清理", "shizuku-denied-no-service")
    assert not any("shizuku-cache" in line for line in m.adb("shell", "ps", "-A", "-o", "NAME,ARGS").splitlines())
    for package in (selected, retained):
        assert command(package, "stat -c %s cache/owned.bin") == "16384"
        assert command(package, "cat files/keep.txt") == "BAIZE_CI_PERSISTENT_DATA"
    nav.tap("连接 Shizuku", "shizuku-request-again")
    time.sleep(2)
    root = m.ui("shizuku-second-real-permission")
    assert any(n.attrib.get("package", "").startswith("moe.shizuku") for n in root.iter("node"))
    allow = next((n for label in ("Allow all the time", "始终允许") for n in nav.matching(root, label)), None)
    assert allow is not None, seven.labels(root)
    nav.click_node(root, allow, "shizuku-grant")
    nav.wait_text("Shizuku 已连接", "shizuku-connected", direction="up")
    processes = wait_shell_process("shizuku-cache")
    m.save_text("shell-userservice-processes.txt", processes)
    nav.tap("BaiZe CI Selected Cache", "shizuku-select-owned-package")
    if mode == "shell":
        nav.find("当前系统未向 Shizuku 授予只清缓存权限", "shizuku-permission-unavailable", contains=True, direction="up")
        root, action = nav.find("清除所选 1 个应用的缓存", "shizuku-batch-disabled", direction="up")
        parents = {child: parent for parent in root.iter() for child in parent}
        disabled = False
        while action is not None:
            disabled = disabled or action.attrib.get("enabled") == "false"
            action = parents.get(action)
        assert disabled, "Permission-limited Shizuku must never expose an enabled batch action"
        for package in (selected, retained):
            assert command(package, "stat -c %s cache/owned.bin") == "16384"
            assert command(package, "cat files/keep.txt") == "BAIZE_CI_PERSISTENT_DATA"
        nav.evidence("shizuku-capability-denied", "ShizukuCacheActivity")
        nav.tap("系统缓存设置：BaiZe CI Selected Cache", "shizuku-open-owned-system-settings")
        root = nav.tree("shizuku-real-settings")
        assert any(n.attrib.get("package") == "com.android.settings" for n in root.iter("node"))
        assert "BaiZe CI Selected Cache" in seven.labels(root)
        nav.tap("Storage & cache", "shizuku-system-storage")
        nav.tap("Clear cache", "shizuku-system-cache-only")
        assert command(selected, "if [ -e cache/owned.bin ]; then echo PRESENT; else echo ABSENT; fi") == "ABSENT"
        m.adb("shell", "input", "keyevent", "4"); time.sleep(.8)
        m.adb("shell", "input", "keyevent", "4"); time.sleep(.8)
        expect_top("ShizukuCacheActivity", "shizuku-system-settings-return")
    else:
        nav.tap("清除所选 1 个应用的缓存", "shizuku-clean-selected", direction="up")
        nav.find("BaiZe CI Selected Cache", "shizuku-review-owned-package", contains=True)
        nav.tap("确认清缓存", "shizuku-confirm-owned-package")
        nav.wait_text("处理结束", "shizuku-finished", direction="up")
        nav.find("成功 1 个", "shizuku-confirmed-result", contains=True)
    assert command(selected, "if [ -e cache/owned.bin ]; then echo PRESENT; else echo ABSENT; fi") == "ABSENT"
    assert command(selected, "cat files/keep.txt") == "BAIZE_CI_PERSISTENT_DATA"
    assert command(retained, "stat -c %s cache/owned.bin") == "16384"
    assert command(retained, "cat files/keep.txt") == "BAIZE_CI_PERSISTENT_DATA"
    nav.evidence("shizuku-cache-only-final", "ShizukuCacheActivity")
    nav.back("MiuixDashboardActivity", "shizuku-single-back")
    m.alive()
    private = evidence.verify_private_data(m, private)
    actual_version = int(re.search(r"versionCode=(\d+)", m.adb("shell", "dumpsys", "package", m.APP)).group(1))
    m.save_text("passed.json", json.dumps({**private, "passed": True, "versionCode": actual_version, "androidApi": 36, "adbUid": int(expected_uid),
        "serverUid": int(expected_uid), "userServiceUid": int(expected_uid), "realManagerPermissionUI": True,
        "denialDoesNotBindOrClear": True, "realManagerRegrant": True,
        "permissionLimitedBatchDisabled": mode == "shell", "realSystemCacheOnlyFallback": mode == "shell",
        "actualShizukuCacheOnlyOperation": mode == "root",
        "selectedCacheRemoved": True, "selectedPersistentDataPreserved": True,
        "unselectedCacheAndDataPreserved": True, "repeatedTapsSinglePage": True, "singleBackToOrigin": True,
        "noCrashOrAnr": True}, indent=2))
    print((m.OUT / "passed.json").read_text())
except Exception:
    m.capture("shizuku-failed")
    raise
