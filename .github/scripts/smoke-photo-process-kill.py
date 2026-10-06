"""Kill the real App process while the second owned document is partial.

The external provider remains alive. All source/target hashes and the exact kill
point are recorded before recovery assertions; no export is replayed or deleted.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


m = module("photo_kill_base", "smoke-release-apk.py")
seven = module("photo_kill_navigation", "smoke-seven-improvements.py")
m.OUT = Path(os.environ["RUNNER_TEMP"]) / "baize-photo-process-kill"
m.OUT.mkdir(parents=True, exist_ok=True)
apk, fixture = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
scope = "/data/user/0/test.baize.photo.fixture/files/fixture"
app_files = f"/data/user/0/{m.APP}/files"


def top(component, name):
    raw = m.adb("shell", "dumpsys", "activity", "activities"); m.save_text(name + "-stack.txt", raw)
    pages = re.findall(r"\* Hist\s+#\d+: ActivityRecord\{[^\n]*?" + re.escape(m.APP) + r"/\.([\w.]+)", raw)
    assert pages and pages[0] == component, pages
    return pages


nav = seven.NavigationSmoke(m, top)


def choose_root(name):
    tree = nav.tree(name)
    assert any(n.attrib.get("package", "").endswith("documentsui") for n in tree.iter("node"))
    node = next((n for label in ("Show roots", "Open navigation drawer", "显示根目录", "显示位置", "打开导航抽屉") for n in nav.matching(tree, label)), None)
    if node is None: node = next((n for n in tree.iter("node") if n.attrib.get("resource-id") == "android:id/home"), None)
    assert node is not None, seven.labels(tree)
    nav.click_node(tree, node, name + "-drawer")
    nav.tap("BaiZe CI Photos", name + "-owned-provider")
    tree = nav.tree(name + "-provider-view")
    switch = next((n for n in tree.iter("node") if n.attrib.get("resource-id", "").endswith(":id/sub_menu_list")), None)
    if switch is not None: nav.click_node(tree, switch, name + "-list-view")


def action(labels, name):
    tree = nav.tree(name); node = next((n for label in labels for n in nav.matching(tree, label)), None)
    assert node is not None, seven.labels(tree); nav.click_node(tree, node, name)


def read_json(path):
    raw = m.adb("shell", "cat", path, check=False)
    return json.loads(raw) if raw.startswith(("[", "{")) else None


def snapshot():
    names = m.adb("shell", "find", scope, "-type", "f", "-name", "*.jpg").splitlines()
    return {path: m.adb("shell", "sha256sum", path).split()[0] for path in names}


try:
    m.settle_emulator_boot()
    assert "sdk" in m.adb("shell", "getprop", "ro.product.model").lower() or "generic" in m.adb("shell", "getprop", "ro.build.fingerprint")
    m.adb("root"); m.adb("wait-for-device"); assert m.adb("shell", "id", "-u") == "0"
    m.adb("install", str(fixture / "fixture.apk"), timeout=120)
    m.adb("install", "-r", str(apk), timeout=120)
    m.adb("shell", "pm", "grant", m.APP, "android.permission.POST_NOTIFICATIONS")
    # The document picker activates the provider. Seed only its private synthetic files.
    m.adb("shell", "content", "query", "--uri", "content://test.baize.photo.fixture.documents/roots")
    m.adb("shell", "mkdir", "-p", scope)
    originals = {}
    for index in (1, 2):
        source = fixture / f"0{index}-source.jpg"; target = scope + "/" + source.name
        m.adb("push", str(source), target); originals[target] = hashlib.sha256(source.read_bytes()).hexdigest()
    m.adb("shell", "touch", scope + "/armed")
    command = f"uid=$(stat -c %u /data/user/0/test.baize.photo.fixture); chown -R $uid:$uid {shlex.quote(scope)}; restorecon -R {shlex.quote(scope)}"
    m.adb("shell", "sh", "-c", shlex.quote(command))
    history_before = read_json(app_files + "/photo-compression-history.json") or []
    m.launch("photo-kill-launch", 3); nav.tap("照片瘦身", "photo-kill-entry")
    nav.tap("60", "photo-kill-quality")
    nav.tap("选择多张照片", "photo-kill-select", direction="down")
    choose_root("photo-kill-source-root")
    tree, node = nav.find("01-source.jpg", "photo-kill-first")
    rect = nav.action_bounds(tree, node); x, y = (rect[0] + rect[2]) // 2, (rect[1] + rect[3]) // 2
    m.adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "800")
    nav.tap("02-source.jpg", "photo-kill-second")
    action(("Open", "OPEN", "Select", "SELECT", "打开", "选择"), "photo-kill-selected")
    nav.wait_text("已选择 2 张", "photo-kill-selection", direction="down")
    nav.tap("选择文件夹，批量另存", "photo-kill-export", direction="down")
    choose_root("photo-kill-target-root")
    action(("Use this folder", "USE THIS FOLDER", "使用此文件夹"), "photo-kill-folder")
    action(("Allow", "ALLOW", "允许"), "photo-kill-folder-grant")
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        partial = m.adb("shell", "cat", scope + "/partial-marker", check=False)
        if partial.startswith("export-") and partial.endswith(".jpg"): break
        time.sleep(.2)
    else: raise AssertionError("The second copy never reached a durable partial write")
    target = scope + "/" + partial
    size = int(m.adb("shell", "stat", "-c", "%s", target)); assert 0 < size <= 64
    history = read_json(app_files + "/photo-compression-history.json"); assert len(history) == len(history_before) + 1, history
    checkpoint = read_json(app_files + "/photo-compression-journal.json")
    before_kill = snapshot(); assert all(before_kill[path] == sha for path, sha in originals.items())
    pid = m.adb("shell", "pidof", m.APP); assert pid and " " not in pid
    provider_pid = m.adb("shell", "pidof", "test.baize.photo.fixture"); assert provider_pid and provider_pid != pid
    m.save_text("kill-point.json", json.dumps({"appPid": pid, "providerPid": provider_pid,
        "partialBytes": size, "partialTarget": target, "completedHistory": history,
        "journalBeforeKill": checkpoint, "hashesBeforeKill": before_kill}, indent=2))
    m.adb("shell", "kill", "-9", pid)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline and pid in m.adb("shell", "pidof", m.APP, check=False).split(): time.sleep(.1)
    # Android can immediately restart the remaining dashboard Activity. The
    # killed PID must disappear; a replacement PID is independent evidence.
    replacement_pid = m.adb("shell", "pidof", m.APP, check=False)
    assert pid not in replacement_pid.split(), "Original App process was not killed"
    death_log = m.adb("logcat", "-d")
    m.save_text("actual-sigkill-logcat.txt", death_log)
    death_observed = f"Process {m.APP} (pid {pid}) has died" in death_log
    assert death_observed, "Android did not report death of the original App PID"
    assert m.adb("shell", "pidof", "test.baize.photo.fixture") == provider_pid, "Fixture must survive the App kill"
    m.adb("shell", "touch", scope + "/abort")
    m.launch("photo-kill-cold-restart", 3); m.tap_label("首页", "photo-kill-restart-home")
    nav.tap("照片瘦身", "photo-kill-restart-entry")
    # Search the real scrollable page: the options panel can fill the first
    # viewport on small emulators. No UI state is injected into the App.
    try:
        tree, _ = nav.find("上次导出待核对", "photo-kill-recovery-status")
        tree, _ = nav.find("上次导出已中断", "photo-kill-recovery-message", contains=True)
        recovery_seen = "上次导出已中断" in seven.labels(tree)
    except AssertionError:
        if "--expect-baseline-failure" not in sys.argv: raise
        recovery_seen = False
    after_restart = snapshot()
    result = {"versionCode": int(re.search(r"versionCode=(\d+)", m.adb("shell", "dumpsys", "package", m.APP)).group(1)),
        "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "realSystemDocumentGrants": True,
        "actualSigkillDuringSecondPartialCopy": True, "externalProviderSurvived": True,
        "killedAppPid": pid, "replacementPidObserved": replacement_pid,
        "androidReportedOriginalProcessDeath": death_observed,
        "partialBytesAtKill": size, "completedHistoryCountBeforeKill": len(history),
        "durableJournalBeforeKill": checkpoint is not None, "interruptionVisibleAfterColdRestart": recovery_seen,
        "noReplayAfterRestart": before_kill == after_restart,
        "completedHistoryPreserved": read_json(app_files + "/photo-compression-history.json") == history,
        "originalHashesUnchanged": all(after_restart[path] == sha for path, sha in originals.items()),
        "partialTargetPreserved": after_restart.get(target) == before_kill[target]}
    m.save_text("recovery-observed.json", json.dumps(result, indent=2)); print(json.dumps(result, indent=2))
    for key in ("noReplayAfterRestart", "completedHistoryPreserved", "originalHashesUnchanged", "partialTargetPreserved"): assert result[key], key
    if "--expect-baseline-failure" in sys.argv:
        assert checkpoint is None and not recovery_seen, "Frozen baseline no longer reproduces the expected recovery failure"
        result.update(expectedBaselineFailureObserved=True, baselineRecoveryPassed=False)
        m.alive(); m.save_text("baseline-observed.json", json.dumps(result, indent=2)); print(json.dumps(result, indent=2))
        sys.exit(0)
    assert checkpoint is not None, "No durable export checkpoint exists at the real kill point"
    assert recovery_seen, "Interrupted export silently disappeared after cold restart"
    nav.tap("核对上次导出", "photo-kill-verify-residual", direction="down")
    nav.wait_text("副本不完整或内容已变化", "photo-kill-residual-rejected", direction="up")
    nav.wait_enabled("核对上次导出", "photo-kill-review-completed")
    assert snapshot() == before_kill, "Read-only recovery must not modify or delete a copy"
    assert read_json(app_files + "/photo-compression-history.json") == history, "A partial copy must not become success history"
    nav.back("MiuixDashboardActivity", "photo-kill-back")
    m.alive(); result.update(passed=True, readOnlyRecoveryRejectedPartialCopy=True)
    m.save_text("passed.json", json.dumps(result, indent=2))
except Exception:
    m.capture("photo-kill-failed"); raise
