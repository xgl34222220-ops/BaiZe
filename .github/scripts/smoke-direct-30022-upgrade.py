"""Install the exact delivered 30021 -> 30022 pair on a disposable emulator.

No intermediate APK, uninstall, data clear, cleaning or export action is used.
The synthetic pre-upgrade records are explicitly fixtures, not measured phone data.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import sys
import uuid


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


m = module("upgrade_base", "smoke-release-apk.py")
seven = module("upgrade_navigation", "smoke-seven-improvements.py")
m.OUT = Path(os.environ["RUNNER_TEMP"]) / "baize-direct-30022-upgrade"
m.OUT.mkdir(parents=True, exist_ok=True)
old, new = (Path(value).resolve() for value in sys.argv[1:3])
repair = Path(sys.argv[3]).resolve() if len(sys.argv) > 3 else None
old_sha = "8bea9168b97b720ec1289dd3d4b0b68aa8d62615e433ff6f55c9d5509305c99f"
new_sha = "3fe8713a13fdc85da42e45d72a7d1603c359c03217bb1b9e071312291ead220e"
assert hashlib.sha256(old.read_bytes()).hexdigest() == old_sha
assert hashlib.sha256(new.read_bytes()).hexdigest() == new_sha
data = f"/data/user/0/{m.APP}"
remote = "/storage/emulated/0/Download/baize-upgrade-owned-" + uuid.uuid4().hex + ".jpg"


def version():
    return int(re.search(r"versionCode=(\d+)", m.adb("shell", "dumpsys", "package", m.APP)).group(1))


def seed(relative, value):
    local = m.OUT / (relative.replace("/", "_") + ".seed")
    local.write_text(value, encoding="utf-8")
    target = data + "/" + relative
    m.adb("shell", "mkdir", "-p", str(Path(target).parent))
    m.adb("push", str(local), target)
    # Only the owned CI App sandbox is seeded. Keep actual App UID and SELinux label.
    m.adb("shell", "sh", "-c", shlex.quote(
        f"uid=$(stat -c %u {shlex.quote(data)}); chown $uid:$uid {shlex.quote(target)}; restorecon {shlex.quote(target)}"))
    return target


def read(path):
    return m.adb("shell", "cat", path)


def top(component, name):
    raw = m.adb("shell", "dumpsys", "activity", "activities")
    m.save_text(name + "-stack.txt", raw)
    pages = re.findall(r"\* Hist\s+#\d+: ActivityRecord\{[^\n]*?" + re.escape(m.APP) + r"/\.([\w.]+)", raw)
    assert pages and pages[0] == component, pages
    return pages


nav = seven.NavigationSmoke(m, top)
try:
    m.settle_emulator_boot()
    assert "sdk" in m.adb("shell", "getprop", "ro.product.model").lower() or "generic" in m.adb("shell", "getprop", "ro.build.fingerprint")
    m.adb("root"); m.adb("wait-for-device")
    assert m.adb("shell", "id", "-u") == "0"
    assert m.APP not in m.adb("shell", "pm", "list", "packages"), "This must be a fresh disposable emulator"
    m.adb("install", str(old), timeout=120)
    assert version() == 30021
    m.adb("shell", "appops", "set", m.APP, "MANAGE_EXTERNAL_STORAGE", "allow")
    m.adb("shell", "pm", "grant", m.APP, "android.permission.POST_NOTIFICATIONS")
    m.launch("direct-upgrade-30021-first-launch", 3)
    m.adb("shell", "am", "force-stop", m.APP)
    photo = m.OUT / "owned-original.jpg"; photo.write_bytes(seven.JPEG)
    m.adb("push", str(photo), remote)
    original_sha = hashlib.sha256(seven.JPEG).hexdigest()
    review = {"items": [{"id": "direct-upgrade-review", "source": "profile", "profile": "rules", "packageName": "test.fixture",
        "appName": "升级夹具", "category": "rule_trash", "groupKey": "app:test.fixture", "groupTitle": "升级夹具",
        "title": "升级前评审", "risk": "low", "path": "/synthetic/never-delete.log", "bytes": 1024, "files": 1,
        "directories": 0, "reason": "仅测试记录", "selectable": True}], "selected": ["direct-upgrade-review"],
        "scanReady": False, "phase": "升级前评审", "notice": "WARNING"}
    history = {"entries": [{"title": "升级容量夹具", "time": "2026-10-03 12:34:56", "trigger": "App 手动",
        "result": "旧记录未提供可信容量", "bytes": 0, "files": 0, "cleaned": True}],
        "lifetimeRuns": 1, "lifetimeReleased": 0, "lifetimeFiles": 0, "lifetimeElapsed": 0}
    photos = [{"name": "升级压缩记录夹具.jpg", "sourceHash": original_sha, "outputHash": "a" * 64,
        "uri": "content://fixture.example/never-open", "before": 8192, "after": 1024,
        "quality": 80, "edge": 2048, "metadata": "STRIP", "epoch": 1791028800000}]
    paths = [seed("files/direct-upgrade-marker.txt", "preserve-30021-data"),
        seed("files/scan-review-safe.json", json.dumps(review, ensure_ascii=False)),
        seed("files/app-clean-history.json", json.dumps(history, ensure_ascii=False)),
        seed("files/photo-compression-history.json", json.dumps(photos, ensure_ascii=False))]
    before = {path: hashlib.sha256(read(path).encode()).hexdigest() for path in paths}
    m.adb("install", "-r", str(new), timeout=120)
    assert version() == 30022
    assert before == {path: hashlib.sha256(read(path).encode()).hexdigest() for path in paths}, "Install migration rewrote App data"
    assert "allow" in m.adb("shell", "appops", "get", m.APP, "MANAGE_EXTERNAL_STORAGE")
    m.launch("direct-upgrade-30022-first-launch", 3)
    m.tap_label("记录", "direct-upgrade-history-tab")
    original_history_loaded = "升级容量夹具" in seven.labels(nav.tree("direct-upgrade-30022-history-observed"))
    m.save_text("original-30021-30022-migration.json", json.dumps({
        "fromVersionCode": 30021, "toVersionCode": 30022, "baselineSha256": old_sha, "candidateSha256": new_sha,
        "directReplaceInstall": True, "privateDataBytePreservedAtInstall": True,
        "storageGrantPreserved": True, "historyLoadedWithoutRoot": original_history_loaded,
        "knownFailure": None if original_history_loaded else "30022 does not load App-owned history while Root is unavailable"}, indent=2))
    if repair is not None:
        m.adb("install", "-r", str(repair), timeout=120)
        assert version() == 30023
        assert before == {path: hashlib.sha256(read(path).encode()).hexdigest() for path in paths}
        m.launch("direct-upgrade-30023-repaired-launch", 3)
        m.tap_label("记录", "direct-upgrade-repaired-history-tab")
    nav.find("升级容量夹具", "direct-upgrade-legacy-row")
    row, _ = nav.find("无法测量", "direct-upgrade-unknown-capacity", contains=True)
    assert "实际释放 0" not in seven.labels(row)
    m.tap_label("清理", "direct-upgrade-clean-tab")
    nav.triple_tap("扫描工作台", "direct-upgrade-review-entry", "ScanWorkbenchActivity")
    assert json.loads(read(paths[1]))["selected"] == ["direct-upgrade-review"]
    assert not json.loads(read(paths[1]))["scanReady"], "Upgrade must not authorize a stale scan"
    nav.back("MiuixDashboardActivity", "direct-upgrade-review-back")
    m.tap_label("首页", "direct-upgrade-home-tab")
    nav.tap("照片瘦身", "direct-upgrade-photo-entry")
    nav.find("升级压缩记录夹具.jpg", "direct-upgrade-photo-history", direction="down")
    nav.back("MiuixDashboardActivity", "direct-upgrade-photo-back")
    m.adb("shell", "am", "force-stop", m.APP)
    m.launch("direct-upgrade-cold-relaunch", 3)
    assert read(paths[0]) == "preserve-30021-data"
    assert json.loads(read(paths[1]))["selected"] == ["direct-upgrade-review"]
    assert len(json.loads(read(paths[3]))) == 1, "Upgrade must never replay a photo export"
    assert m.adb("shell", "sha256sum", remote).split()[0] == original_sha
    m.alive()
    result = {"passed": True, "fromVersionCode": 30021, "toVersionCode": 30022,
        "repairedVersionCode": version() if repair else None,
        "repairedSha256": hashlib.sha256(repair.read_bytes()).hexdigest() if repair else None,
        "original30022HistoryLoadedWithoutRoot": original_history_loaded,
        "baselineSha256": old_sha, "candidateSha256": new_sha,
        "directReplaceInstall": True, "noIntermediateInstallBefore30022OrDataClear": True,
        "privateDataBytePreservedAtInstall": True, "legacyUnknownCapacityRendered": True,
        "reviewSelectionPreserved": True, "staleReviewNotAuthorized": True,
        "photoHistoryPreservedWithoutReplay": True, "storageGrantPreserved": True,
        "originalHashUnchanged": True, "singleBackAndRepeatedTap": True, "noCrashOrAnr": True,
        "fixtureScope": "synthetic CI emulator records; no cleaning/export or user-device operation"}
    m.save_text("passed.json", json.dumps(result, indent=2)); print(json.dumps(result, indent=2))
except Exception:
    m.capture("direct-upgrade-failed"); raise
