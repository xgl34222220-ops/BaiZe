"""Real system document pickers, rotation and verified exports on the signed APK."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import struct
import subprocess
import time
import uuid

def module(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value

m = module("photo_device_base", "smoke-release-apk.py")
seven = module("photo_device_navigation", "smoke-seven-improvements.py")
m.OUT = Path(os.environ["RUNNER_TEMP"]) / "baize-photo-batch-device"
m.OUT.mkdir(parents=True, exist_ok=True)
assert "sdk" in m.adb("shell", "getprop", "ro.product.model").lower() or "generic" in m.adb("shell", "getprop", "ro.build.fingerprint")
assert m.adb("shell", "getprop", "ro.build.version.sdk") == "36"
folder = "baize-photo-device-" + uuid.uuid4().hex
remote = "/storage/emulated/0/Download/" + folder
date = b"2020:01:02 03:04:05\0"
make = b"L" * 8192 + b"\0"
tiff = b"II" + struct.pack("<HIH", 42, 8, 2)
tiff += struct.pack("<HHII", 0x10F, 2, len(make), 38)
tiff += struct.pack("<HHII", 0x132, 2, len(date), 38 + len(make))
tiff += struct.pack("<I", 0) + make + date
exif = b"Exif\0\0" + tiff
jpeg = seven.JPEG[:2] + b"\xff\xe1" + struct.pack(">H", len(exif) + 2) + exif + seven.JPEG[2:]
local = m.OUT / "owned-source.jpg"; local.write_bytes(jpeg)
expected = hashlib.sha256(jpeg).hexdigest()

def expect_top(component, name):
    text = m.adb("shell", "dumpsys", "activity", "activities"); m.save_text(name + "-stack.txt", text)
    pages = re.findall(r"\* Hist\s+#\d+: ActivityRecord\{[^\n]*?" + re.escape(m.APP) + r"/\.([\w.]+)", text)
    assert pages and pages[0] == component, (component, pages)
    return pages

nav = seven.NavigationSmoke(m, expect_top)

def picker_folder():
    tree = nav.tree("photo-batch-picker")
    assert any(n.attrib.get("package", "").endswith("documentsui") for n in tree.iter("node"))
    # OpenDocumentTree uses the actual shared-storage root without a roots
    # drawer on Android 16. Its Download child is deliberately spelled singular.
    if nav.matching(tree, "Download") and nav.matching(tree, "USE THIS FOLDER"):
        nav.tap("Download", "photo-batch-tree-download"); nav.tap(folder, "photo-batch-tree-owned-folder")
        return
    drawer = next((n for text in ("Show roots", "Open navigation drawer", "显示根目录", "显示位置", "打开导航抽屉") for n in nav.matching(tree, text)), None)
    if drawer is None: drawer = next((n for n in tree.iter("node") if n.attrib.get("resource-id") == "android:id/home"), None)
    assert drawer is not None
    nav.click_node(tree, drawer, "photo-batch-picker-drawer")
    tree = nav.tree("photo-batch-roots")
    downloads = [n for text in ("Downloads", "下载", "下载内容") for n in nav.matching(tree, text)]
    assert downloads; nav.click_node(tree, downloads[-1], "photo-batch-downloads")
    nav.tap(folder, "photo-batch-owned-folder")
    tree = nav.tree("photo-batch-folder")
    switch = next((n for n in tree.iter("node") if n.attrib.get("resource-id", "").endswith(":id/sub_menu_list")), None)
    if switch is not None: nav.click_node(tree, switch, "photo-batch-list-view")

def picker_action(texts, name):
    root = nav.tree(name)
    node = next((n for text in texts for n in nav.matching(root, text)), None)
    assert node is not None, seven.labels(root)
    nav.click_node(root, node, name)

try:
    m.adb("shell", "mkdir", "-p", remote)
    for name in ("preview.jpg", "duplicate.jpg"):
        path = remote + "/" + name
        m.adb("push", str(local), path)
        m.adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + path)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            row = m.adb("shell", "content", "query", "--uri", "content://media/external/file", "--projection", "_id:_data:mime_type",
                "--where", shlex.quote("_data='" + path + "'"), check=False)
            if "image/jpeg" in row and path in row: break
            time.sleep(.5)
        else: raise AssertionError("The owned JPEG was not indexed for the real image picker: " + path)
    m.launch("photo-batch-home", 2)
    nav.tap("照片瘦身", "photo-batch-entry"); expect_top("PhotoCompressionActivity", "photo-batch-top")
    nav.tap("选择照片", "photo-batch-select-single"); picker_folder(); nav.tap("preview.jpg", "photo-batch-owned-single")
    nav.wait_text("照片已读取，原图未修改", "photo-batch-imported", direction="down")
    nav.tap("60", "photo-batch-quality", direction="up")
    nav.tap("保留拍摄时间", "photo-batch-keep-date")
    nav.tap("生成压缩预览", "photo-batch-generate")
    nav.wait_text("预览已生成", "photo-batch-preview", direction="down")
    nav.tap("选择位置，另存副本", "photo-batch-export-single")
    picker_folder(); picker_action(("Save", "SAVE", "保存"), "photo-batch-single-save")
    nav.wait_text("已导出并核对，原图保留", "photo-batch-single-exported", direction="up")
    nav.tap("90", "photo-batch-quality-90", direction="up")
    nav.tap("选择多张照片", "photo-batch-select-multiple", direction="down")
    picker_folder()
    root, node = nav.find("preview.jpg", "photo-batch-first-long-press")
    rect = nav.action_bounds(root, node); x, y = (rect[0] + rect[2]) // 2, (rect[1] + rect[3]) // 2
    m.adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "800")
    nav.tap("duplicate.jpg", "photo-batch-second-select")
    picker_action(("Open", "OPEN", "Select", "SELECT", "打开", "选择"), "photo-batch-multiple-open")
    nav.wait_text("已选择 2 张", "photo-batch-two-selected", direction="down")
    m.adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    m.adb("shell", "settings", "put", "system", "user_rotation", "1"); time.sleep(2)
    nav.find("已选择 2 张", "photo-batch-rotation-retained", direction="down")
    m.adb("shell", "settings", "put", "system", "user_rotation", "0"); time.sleep(2)
    nav.tap("选择文件夹，批量另存", "photo-batch-export-multiple", direction="down")
    picker_folder(); picker_action(("Use this folder", "USE THIS FOLDER", "使用此文件夹"), "photo-batch-use-folder")
    picker_action(("Allow", "ALLOW", "允许"), "photo-batch-grant-folder")
    nav.wait_text("批量完成：1 张已导出，1 张跳过", "photo-batch-complete", direction="up")
    for name in ("preview.jpg", "duplicate.jpg"):
        assert m.adb("shell", "sha256sum", remote + "/" + name).split()[0] == expected
    files = [path for path in m.adb("shell", "find", remote, "-type", "f").splitlines()
        if path not in (remote + "/preview.jpg", remote + "/duplicate.jpg")]
    assert len(files) == 2, files
    for i, path in enumerate(files):
        result = subprocess.run(["adb", "exec-out", "cat", path], check=True, capture_output=True).stdout
        assert 0 < len(result) < len(jpeg) and date in result and b"L" * 1024 not in result
        (m.OUT / f"verified-output-{i}.jpg").write_bytes(result)
    nav.find("压缩记录", "photo-batch-history", direction="down")
    nav.evidence("photo-batch-final", "PhotoCompressionActivity")
    nav.back("MiuixDashboardActivity", "photo-batch-single-back"); m.alive()
    m.adb("shell", "am", "force-stop", m.APP)
    m.launch("photo-batch-process-restart", 3)
    m.tap_label("首页", "photo-batch-restart-home-tab")
    nav.tap("照片瘦身", "photo-batch-restart-entry")
    nav.find("压缩记录", "photo-history-after-process-restart", direction="down")
    after_restart = [path for path in m.adb("shell", "find", remote, "-type", "f").splitlines()
        if path not in (remote + "/preview.jpg", remote + "/duplicate.jpg")]
    assert sorted(after_restart) == sorted(files), "Restart must never replay an export"
    for name in ("preview.jpg", "duplicate.jpg"):
        assert m.adb("shell", "sha256sum", remote + "/" + name).split()[0] == expected
    nav.evidence("photo-history-restored", "PhotoCompressionActivity")
    actual_version = int(re.search(r"versionCode=(\d+)", m.adb("shell", "dumpsys", "package", m.APP)).group(1))
    m.save_text("passed.json", json.dumps({"versionCode": actual_version, "androidApi": 36, "realSingleAndMultipleDocumentPickers": True,
        "realFolderWriteGrant": True, "selectedUrisSurviveRotation": True, "singleAndBatchExportsVerified": True,
        "repeatImageSkipped": True, "captureTimePreserved": True, "unselectedCameraMetadataRemoved": True,
        "originalHashesUnchanged": True, "twoSmallerCopies": True, "historyVisible": True, "singleBackToOrigin": True,
        "completedHistorySurvivesProcessRestart": True, "noExportReplayedAfterRestart": True,
        "noCrashOrAnr": True}, indent=2))
    print((m.OUT / "passed.json").read_text())
except Exception:
    m.capture("photo-batch-failed"); raise
finally:
    m.adb("shell", "rm", "-rf", remote, check=False)
    m.adb("shell", "settings", "put", "system", "user_rotation", "0", check=False)
