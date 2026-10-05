#!/usr/bin/env python3
"""30024 -> signed release upgrade smoke on a fresh, disposable AOSP emulator.

Usage: python3 .github/scripts/smoke-protection-trash-upgrade.py \
    delivered-30024.apk candidate-30025.apk 30025

Requires adb, aapt and apksigner from the Android SDK. Refuses physical devices,
non-QEMU targets, an already installed BaiZe, and reused evidence directories.
Only the verified public launcher is started directly. Trash is entered through
visible controls. Synthetic files, preferences and valid trash journals are
seeded with emulator adbd root; the signed app always runs with its ordinary UID.
No grant/appops/settings command, APK instrumentation, uninstall, data clearing,
restore, or permanent deletion is performed. Clear-all review is only cancelled.
The private external trash root is a supported production root and needs no
all-files access. This is UI/upgrade coverage, not shared-storage deletion proof.
The release workflow must build the supplied candidate with minification enabled.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import traceback
import uuid
import xml.etree.ElementTree as ET


APP = "io.github.xgl34222220.baize"
BASELINE_CODE = 30024
BASELINE_SHA256 = "c0a618bd5a7a062a5e03c5c5b8a66f60aa2f14fb2a56c34e3fc7445ba00a2cbc"
DATA = f"/data/user/0/{APP}"
EXTERNAL = f"/storage/emulated/0/Android/data/{APP}/files"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sdk_tool(name: str) -> str:
    installed = shutil.which(name)
    if installed:
        return installed
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if not os.environ.get(variable):
            continue
        candidates = list((Path(os.environ[variable]) / "build-tools").glob(f"*/{name}"))
        candidates.sort(key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.parent.name)), reverse=True)
        for candidate in candidates:
            if candidate.is_file() and os.access(candidate, os.X_OK):
                return str(candidate)
    raise RuntimeError(f"Android SDK tool is unavailable: {name}")


def bounds(node: ET.Element) -> tuple[int, int, int, int]:
    values = tuple(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
    require(len(values) == 4, f"Invalid UI bounds: {node.attrib}")
    return values


class Smoke:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.out = args.out.resolve()
        require(not self.out.exists() or not any(self.out.iterdir()),
                f"Evidence directory is not empty; use a new --out: {self.out}")
        self.out.mkdir(parents=True, exist_ok=True)
        self.serial = ""
        self.verified_emulator = False
        self.initial_transport_uid: str | None = None
        self.root_requested = False
        self.app_uid: int | None = None
        self.fixtures: dict[str, bytes] = {}
        self.preference_expectations: dict[str, tuple[str, str]] = {}
        self.cases: list[dict] = []
        self.result: dict = {"passed": False, "baseline_version_code": BASELINE_CODE,
                             "candidate_version_code": args.version, "cases": self.cases}

    def save(self, name: str, data: str) -> None:
        (self.out / name).write_text(data, encoding="utf-8")

    def command(self, argv: list[str], timeout: int = 45, check: bool = True) -> str:
        started = time.time()
        process = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
        with (self.out / "commands.jsonl").open("a", encoding="utf-8") as stream:
            stream.write(json.dumps({"command": argv, "exit_code": process.returncode,
                                     "seconds": round(time.time() - started, 3)}) + "\n")
        if check and process.returncode:
            raise RuntimeError(f"Command failed: {shlex.join(argv)}\n{process.stdout}\n{process.stderr}")
        return process.stdout.strip()

    def adb(self, *args: str, timeout: int = 45, check: bool = True) -> str:
        require(bool(self.serial), "No verified explicit adb serial selected")
        return self.command(["adb", "-s", self.serial, *args], timeout, check)

    def shell(self, script: str, **kwargs) -> str:
        return self.adb("shell", script, **kwargs)

    def identify_emulator(self) -> None:
        devices = self.command(["adb", "devices"])
        self.save("adb-devices.txt", devices)
        connected = re.findall(r"^(emulator-\d+)\s+device\s*$", devices, re.M)
        serial = self.args.serial or os.environ.get("ANDROID_SERIAL")
        if not serial:
            require(len(connected) == 1, "Expected exactly one connected disposable emulator")
            serial = connected[0]
        require(re.fullmatch(r"emulator-\d+", serial) is not None, "Refusing a non-emulator serial")
        require(serial in connected, "Selected emulator is not connected")
        self.serial = serial
        self.verify_target()
        self.verified_emulator = True
        self.initial_transport_uid = self.shell("id -u")
        self.save("emulator-properties.txt", self.adb("shell", "getprop"))
        self.result["emulator"] = {"serial": serial, "qemu": True,
                                   "api": self.adb("shell", "getprop", "ro.build.version.sdk")}

    def verify_target(self) -> None:
        require(self.adb("shell", "getprop", "ro.kernel.qemu") == "1", "Target is not QEMU")
        require(self.adb("shell", "getprop", "ro.hardware") in ("ranchu", "goldfish"),
                "Target is not a supported AOSP emulator")
        require(self.adb("shell", "am", "get-current-user") == "0", "Smoke requires emulator user 0")

    def inspect_apk(self, apk: Path, code: int, label: str) -> dict:
        require(apk.is_file(), f"Missing {label} APK: {apk}")
        sha = digest(apk.read_bytes())
        if label == "baseline":
            require(sha == BASELINE_SHA256, "Baseline must be the exact delivered 30024 APK")
        badging = self.command([sdk_tool("aapt"), "dump", "badging", str(apk)])
        self.save(f"{label}-badging.txt", badging)
        match = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", badging, re.M)
        require(match is not None and match.group(1) == APP and int(match.group(2)) == code,
                f"Wrong {label} package or versionCode")
        require("application-debuggable" not in badging and "application-testOnly" not in badging,
                f"{label} must be a non-debuggable production APK")
        certificates = self.command([sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", str(apk)])
        self.save(f"{label}-signature.txt", certificates)
        signers = sorted(re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
                                    certificates, re.M))
        require(bool(signers), f"No verified {label} APK signing certificate")
        return {"sha256": sha, "version_code": code, "signer_sha256": [s.lower() for s in signers],
                "debuggable": False, "package": APP}

    def settle_boot(self) -> None:
        deadline = time.monotonic() + 180
        while self.adb("shell", "getprop", "sys.boot_completed") != "1":
            require(time.monotonic() < deadline, "Emulator did not finish booting")
            time.sleep(3)
        # Let first-boot package setup settle. Keep actual animation/graphics and
        # ANR settings unchanged; do not force compilation or dismiss ANR dialogs.
        samples = []
        for _ in range(6):
            samples.append({"time": time.time(),
                            "cpu": self.shell("cat /proc/pressure/cpu", check=False),
                            "memory": self.shell("cat /proc/pressure/memory", check=False)})
            time.sleep(15)
        self.save("boot-pressure.json", json.dumps(samples, indent=2))

    def package(self, code: int, label: str) -> int:
        package = self.adb("shell", "dumpsys", "package", APP)
        self.save(f"{label}-package.txt", package)
        require(re.search(rf"\bversionCode={code}\b", package) is not None, "Wrong installed versionCode")
        require("DEBUGGABLE" not in package and "TEST_ONLY" not in package, "Installed APK is not production")
        match = re.search(r"\buserId=(\d+)", package)
        require(match is not None and int(match.group(1)) >= 10000, "No ordinary application UID")
        return int(match.group(1))

    def root_transport(self) -> None:
        self.verify_target()
        self.root_requested = True
        self.save("adbd-root.txt", self.adb("root"))
        self.adb("wait-for-device", timeout=60)
        self.verify_target()
        require(self.shell("id -u") == "0", "Disposable AOSP emulator adbd root is required to seed fixtures")

    def alive(self) -> str:
        pid = self.adb("shell", "pidof", APP, check=False)
        require(re.fullmatch(r"\d+", pid) is not None, "Signed application process exited or is ambiguous")
        crash = self.adb("logcat", "-d", "-b", "crash", "-v", "threadtime")
        require(re.search(rf"(?:Process:\s*|>>>\s*){re.escape(APP)}(?:[,\s:]|\s*<<<)", crash) is None,
                "Application fatal exception/native crash is in the crash buffer")
        events = self.adb("logcat", "-d", "-b", "main", "-b", "system", "-b", "events", "-v", "threadtime")
        require(re.search(rf"ANR in {re.escape(APP)}\b", events) is None and
                not any("am_anr" in line and APP in line for line in events.splitlines()),
                "Android reported an application ANR")
        # Numeric ps is readable even when the transport is not rooted.
        processes = self.adb("shell", "ps", "-A", "-n", "-o", "UID,PID,NAME")
        rows = [line.split() for line in processes.splitlines()]
        require(any(len(row) == 3 and row[1] == pid and row[2] == APP and
                    row[0].isdigit() and int(row[0]) == self.app_uid for row in rows),
                "Application is not running under its installed ordinary UID")
        return pid

    def tree(self, name: str) -> ET.Element:
        target = "/data/local/tmp/baize-protection-trash-ui.xml"
        # A failed dump must not reuse an older tree from a previous action.
        target = target.replace(".xml", f"-{uuid.uuid4().hex}.xml")
        self.adb("shell", "uiautomator", "dump", "--compressed", target, timeout=60)
        xml = self.adb("shell", "cat", target)
        self.save(f"{name}.xml", xml)
        root = ET.fromstring(xml)
        require(not any("isn't responding" in n.get("text", "") or "无响应" in n.get("text", "")
                        for n in root.iter("node")), "An Android ANR dialog is visible")
        return root

    def capture(self, name: str) -> None:
        for suffix, args in {
            "logcat.txt": ("logcat", "-d", "-b", "all", "-v", "threadtime"),
            "crash.txt": ("logcat", "-d", "-b", "crash", "-v", "threadtime"),
            "activities.txt": ("shell", "dumpsys", "activity", "activities"),
            "exit-info.txt": ("shell", "dumpsys", "activity", "exit-info", APP),
        }.items():
            self.save(f"{name}-{suffix}", self.adb(*args, check=False))
        screen = subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"],
                                capture_output=True, timeout=30)
        require(screen.returncode == 0 and screen.stdout.startswith(b"\x89PNG\r\n\x1a\n"), "Screenshot capture failed")
        (self.out / f"{name}.png").write_bytes(screen.stdout)

    @staticmethod
    def texts(root: ET.Element) -> set[str]:
        return {n.get("text", "") for n in root.iter("node")}

    def top(self, activity: str) -> None:
        deadline = time.monotonic() + 30
        while True:
            state = self.adb("shell", "dumpsys", "activity", "activities")
            resumed = [line for line in state.splitlines() if "mResumedActivity" in line or "topResumedActivity" in line]
            if any(re.search(re.escape(APP) + r"/(?:" + re.escape(APP) + r")?\." + re.escape(activity) + r"\b", line)
                   for line in resumed):
                return
            self.alive()
            require(time.monotonic() < deadline, f"Expected resumed {activity}; found {resumed}")
            time.sleep(.5)

    def click(self, root: ET.Element, node: ET.Element) -> None:
        parents = {child: parent for parent in root.iter() for child in parent}
        current = node
        clickable = False
        while current is not None:
            require(current.get("enabled") != "false", f"Disabled UI action: {node.attrib}")
            if current.get("clickable") == "true":
                clickable = True
                break
            current = parents.get(current)
        require(clickable, f"No clickable control for {node.attrib}")
        # Use the visible label's rectangle; large Compose parent rectangles can
        # cover several controls. Intersect scroll ancestors to avoid a clipped tap.
        x1, y1, x2, y2 = bounds(node)
        current = parents.get(node)
        while current is not None:
            if current.get("scrollable") == "true":
                a, b, c, d = bounds(current)
                x1, y1, x2, y2 = max(x1, a), max(y1, b), min(x2, c), min(y2, d)
            current = parents.get(current)
        require(x2 > x1 and y2 > y1, "Target is clipped outside its scroll container")
        self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(1)

    def find(self, label: str, name: str, scroll: bool = False) -> tuple[ET.Element, ET.Element]:
        for direction in (("down", "up") if scroll else ("down",)):
            previous = None
            for attempt in range(10 if scroll else 1):
                self.alive()
                root = self.tree(f"{name}-{direction}-{attempt}")
                nodes = [n for n in root.iter("node") if label in (n.get("text"), n.get("content-desc"))]
                if nodes:
                    return root, nodes[-1]
                require(scroll, f"Missing UI label {label!r}: {self.texts(root)}")
                signature = ET.tostring(root)
                if signature == previous:
                    break
                previous = signature
                containers = [n for n in root.iter("node") if n.get("scrollable") == "true"]
                require(bool(containers), f"Cannot scroll to {label!r}")
                x1, y1, x2, y2 = max((bounds(n) for n in containers), key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
                x = (x1 + x2) // 2
                high, low = y1 + (y2 - y1) // 5, y1 + (y2 - y1) * 4 // 5
                start, end = (low, high) if direction == "down" else (high, low)
                self.adb("shell", "input", "swipe", str(x), str(start), str(x), str(end), "350")
                time.sleep(.7)
        raise AssertionError(f"Launcher UI cannot reach {label!r}")

    def tap(self, label: str, name: str, scroll: bool = False) -> None:
        root, node = self.find(label, name, scroll)
        self.click(root, node)
        self.alive()

    def launch(self, name: str) -> None:
        self.adb("shell", "am", "force-stop", APP)
        query = self.adb("shell", "cmd", "package", "query-activities", "--brief", "--components",
                         "--query-flags", "0", "--user", "0", "-a", "android.intent.action.MAIN",
                         "-c", "android.intent.category.LAUNCHER", "-p", APP)
        self.save(f"{name}-launcher-query.txt", query)
        components = re.findall(re.escape(APP) + r"/[A-Za-z0-9_.$]+", query)
        allowed = (f"{APP}/.MiuixDashboardActivity", f"{APP}/{APP}.MiuixDashboardActivity")
        launcher = next((component for component in components if component in allowed), None)
        require(launcher is not None, "Expected public home launcher is not registered")
        # Launcher-only filters need not match implicit DEFAULT resolution. Start
        # only this queried public launcher, never a non-exported feature activity.
        self.save(f"{name}-launch.txt", self.adb("shell", "am", "start", "-W", "-n", launcher, timeout=60))
        deadline = time.monotonic() + 60
        while True:
            time.sleep(2)
            self.alive()
            root = self.tree(name)
            deny = [n for n in root.iter("node") if n.get("resource-id", "").endswith(":id/permission_deny_button")
                    and "permissioncontroller" in n.get("package", "")]
            if deny:
                self.click(root, deny[0])  # No permission expansion for this UI-only smoke.
                require(time.monotonic() < deadline, "A system permission dialog did not dismiss")
                continue
            if {"首页", "清理", "记录", "设置"}.issubset(self.texts(root)):
                break
            require(time.monotonic() < deadline, "Home navigation did not become responsive")
        self.top("MiuixDashboardActivity")
        self.capture(name)
        self.cases.append({"case": name, "public_launcher": launcher, "ordinary_uid": self.app_uid,
                           "pid": self.alive(), "home_tabs_visible": True})

    def read_bytes(self, remote: str) -> bytes:
        data = subprocess.run(["adb", "-s", self.serial, "exec-out", "cat", remote],
                              capture_output=True, timeout=30)
        require(data.returncode == 0, f"Cannot read seeded file {remote}: {data.stderr!r}")
        return data.stdout

    def seed_file(self, remote: str, data: bytes, label: str) -> None:
        local = self.out / "seed" / label
        local.parent.mkdir(parents=True, exist_ok=True)
        local.write_bytes(data)
        self.shell("mkdir -p " + shlex.quote(str(Path(remote).parent)))
        self.adb("push", str(local), remote)
        if remote.startswith(DATA + "/"):
            self.shell(f"chown {self.app_uid}:{self.app_uid} {shlex.quote(remote)} && chmod 600 {shlex.quote(remote)}")
        self.fixtures[remote] = data

    def seed_preference(self, filename: str, key: str, tag: str, value: str) -> None:
        remote = f"{DATA}/shared_prefs/{filename}.xml"
        exists = self.shell("if [ -f " + shlex.quote(remote) + " ]; then echo present; fi") == "present"
        root = ET.fromstring(self.read_bytes(remote)) if exists else ET.Element("map")
        require(root.tag == "map", f"Invalid existing preferences: {filename}")
        for old in list(root):
            if old.get("name") == key:
                root.remove(old)
        node = ET.SubElement(root, tag, {"name": key})
        if tag == "set":
            ET.SubElement(node, "string").text = value
        else:
            node.set("value", value)
        data = ET.tostring(root, encoding="utf-8", xml_declaration=True)
        self.seed_file(remote, data, filename + ".xml")
        # Android may rewrite/reorder preferences on launch: compare the seeded
        # values semantically, rather than incorrectly requiring byte identity.
        del self.fixtures[remote]
        self.preference_expectations[remote] = (key, value)

    def seed(self) -> None:
        self.adb("shell", "am", "force-stop", APP)
        self.root_transport()
        token = uuid.uuid4().hex[:12]
        self.seed_file(f"{DATA}/files/signed-trash-upgrade-{token}.txt",
                       f"preserve-delivered-30024-{token}\n".encode(), "upgrade-marker.txt")
        self.seed_preference("ordinary-trash", "budget", "long", str(1024 ** 3))
        self.seed_preference("baize_v2", "path_whitelist", "set",
                             f"/storage/emulated/0/Download/baize-upgrade-{token}")
        created = int(time.time() * 1000)
        self.entries = []
        for number in (1, 2):
            entry_id = str(uuid.uuid4())
            payload = f"Harmless BaiZe upgrade fixture {token} {number}.\n".encode()
            entry = {"id": entry_id, "original": f"{EXTERNAL}/baize-upgrade-{token}-{number}.txt",
                     "stored": f"{EXTERNAL}/.baize-file-trash/{entry_id}", "bytes": len(payload),
                     "hash": digest(payload), "created": created + number,
                     "expires": created + 30 * 86400000}
            self.seed_file(entry["stored"], payload, f"payload-{number}.txt")
            self.seed_file(f"{DATA}/files/ordinary-trash/{entry_id}.json",
                           json.dumps(entry).encode(), f"journal-{number}.json")
            self.entries.append(entry)
        # Correct only the created private directories/files, not security policy
        # or unrelated application contents. External storage ownership is exposed
        # by Android's per-app emulated-storage mount, not chmod/appops workarounds.
        for directory in (f"{DATA}/files", f"{DATA}/files/ordinary-trash", f"{DATA}/shared_prefs"):
            self.shell(f"chown {self.app_uid}:{self.app_uid} {shlex.quote(directory)} && chmod 700 {shlex.quote(directory)}")
        paths = [*self.fixtures, *self.preference_expectations,
                 f"{DATA}/files", f"{DATA}/files/ordinary-trash", f"{DATA}/shared_prefs"]
        private = [path for path in paths if path.startswith(DATA + "/")]
        self.shell("restorecon " + " ".join(shlex.quote(path) for path in private))
        self.save("seed-manifest.json", json.dumps({"trash": self.entries,
                  "files": {path: digest(data) for path, data in self.fixtures.items()},
                  "preferences": self.preference_expectations}, ensure_ascii=False, indent=2))
        self.verify_preserved("baseline-seeded")

    def verify_preserved(self, name: str) -> None:
        for remote, original in self.fixtures.items():
            require(self.read_bytes(remote) == original, f"Upgrade/cancel changed seeded file: {remote}")
        for remote, (key, value) in self.preference_expectations.items():
            root = ET.fromstring(self.read_bytes(remote))
            nodes = [node for node in root if node.get("name") == key]
            require(len(nodes) == 1, f"Seeded preference vanished: {key}")
            values = {node.text for node in nodes[0]} if nodes[0].tag == "set" else {nodes[0].get("value")}
            require(value in values, f"Seeded preference changed: {key}")
        self.cases.append({"case": name, "marker_payloads_and_journals_unchanged": True,
                           "budget_and_legacy_path_preserved": True})

    def open_trash(self, name: str) -> None:
        self.tap("首页", name + "-home")
        self.tap("回收站", name + "-open", scroll=True)
        self.top("FileTrashActivity")
        deadline = time.monotonic() + 45
        while True:
            root = self.tree(name)
            self.alive()
            text = self.texts(root)
            if "文件回收站" in text and any(label.startswith("2 项 ·") for label in text):
                break
            require(not any("列表读取失败" in label for label in text), "Trash ViewModel failed to load")
            require(time.monotonic() < deadline, f"Expected two valid trash entries, found {text}")
            time.sleep(1)
        self.capture(name)
        for index, entry in enumerate(self.entries):
            root, _ = self.find(Path(entry["original"]).name, f"{name}-fixture-{index}", scroll=True)
            errors = ("回收内容未找到", "回收路径已变化", "存储卷或回收目录暂不可用")
            require(not any(any(error in label for error in errors) for label in self.texts(root)),
                    "Synthetic trash payload is not readable through the production app")
            self.capture(f"{name}-fixture-{index}")
        self.cases.append({"case": name, "view_model_constructed_and_loaded": True,
                           "visible_fixture_count": 2, "pid": self.alive()})

    def cancel_clear_all(self, name: str, use_back: bool = False) -> None:
        self.tap("清空回收站", name + "-open", scroll=True)
        self.find("永久删除 2 项？", name + "-review-title")
        root, _ = self.find("保留文件", name + "-review-cancel")
        require("永久删除这 2 项" in self.texts(root), "Clear-all lacks its explicit final confirmation")
        self.capture(name + "-review")
        if use_back:
            self.adb("shell", "input", "keyevent", "4")
            time.sleep(1)
        else:
            self.tap("保留文件", name + "-keep-files")
        self.top("FileTrashActivity")
        root = self.tree(name + "-cancelled")
        require("永久删除 2 项？" not in self.texts(root), "Clear-all review was not dismissed")
        self.alive()
        self.capture(name + "-cancelled")
        self.verify_preserved(name + "-data-preserved")
        self.cases.append({"case": name, "reviewed_count": 2, "cancelled_with": "Back" if use_back else "保留文件",
                           "permanent_delete_confirmed": False})

    def run(self) -> None:
        require(self.args.version == 30025, "This regression targets the signed 30025 candidate")
        baseline = self.inspect_apk(self.args.baseline.resolve(), BASELINE_CODE, "baseline")
        candidate = self.inspect_apk(self.args.candidate.resolve(), self.args.version, "candidate")
        require(candidate["signer_sha256"] == baseline["signer_sha256"], "Candidate does not share the delivered signing certificate")
        self.result.update({"baseline": baseline, "candidate": candidate})
        self.identify_emulator()
        installed = self.adb("shell", "pm", "list", "packages", APP)
        require(f"package:{APP}" not in installed.splitlines(),
                "Refusing to replace existing application data; start a fresh disposable emulator")
        self.settle_boot()
        self.adb("shell", "input", "keyevent", "82")
        self.save("baseline-install.txt", self.adb("install", str(self.args.baseline.resolve()), timeout=180))
        self.app_uid = self.package(BASELINE_CODE, "baseline")
        self.adb("logcat", "-c")
        self.launch("baseline-home")
        self.seed()
        self.save("candidate-upgrade-install.txt",
                  self.adb("install", "-r", str(self.args.candidate.resolve()), timeout=180))
        require(self.package(self.args.version, "candidate") == self.app_uid, "Upgrade changed application UID")
        self.verify_preserved("upgrade-before-launch")
        self.adb("logcat", "-c")
        self.launch("upgrade-home")
        self.verify_preserved("upgrade-after-launch")
        self.open_trash("upgrade-trash")
        self.cancel_clear_all("clear-all-keep")
        self.cancel_clear_all("clear-all-back", use_back=True)
        self.adb("shell", "input", "keyevent", "4")
        time.sleep(1)
        self.top("MiuixDashboardActivity")
        self.tree("return-home")
        self.capture("return-home")
        self.launch("upgrade-cold-relaunch")
        self.open_trash("cold-relaunch-trash")
        self.tap("刷新回收站", "refresh-trash")
        self.find("文件回收站", "refreshed-trash")
        self.verify_preserved("final-preservation")
        self.capture("final-trash")
        self.result.update({"passed": True, "graphics_settings_modified": False,
                            "security_permissions_expanded": False, "data_cleared": False,
                            "permanent_deletion_executed": False, "private_activity_direct_launch": False,
                            "candidate_minification_source": "release build workflow",
                            "ordinary_app_uid": self.app_uid, "final_pid": self.alive()})

    def finish(self) -> None:
        if self.verified_emulator:
            try:
                self.capture("final-state")
            except Exception as error:
                self.save("final-capture-error.txt", str(error))
            try:
                self.tree("final-state")
            except Exception as error:
                self.save("final-tree-error.txt", str(error))
            if self.root_requested and self.initial_transport_uid != "0":
                try:
                    self.save("adbd-unroot.txt", self.adb("unroot"))
                    self.adb("wait-for-device", timeout=60)
                    require(self.shell("id -u") == self.initial_transport_uid, "adbd transport UID was not restored")
                except Exception as error:
                    self.result["passed"] = False
                    self.result["transport_restore_error"] = str(error)
        self.save("result.json", json.dumps(self.result, ensure_ascii=False, indent=2))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("baseline", type=Path, help="Exact delivered signed 30024 APK")
    parser.add_argument("candidate", type=Path, help="Minified signed 30025 APK from the release build")
    parser.add_argument("version", type=int, help="Expected candidate versionCode (30025)")
    parser.add_argument("--serial", help="Explicit emulator-NNNN serial; otherwise ANDROID_SERIAL or sole emulator")
    parser.add_argument("--out", type=Path, default=Path("evidence/signed-trash-upgrade"))
    smoke = Smoke(parser.parse_args())
    try:
        smoke.run()
    except Exception as error:
        smoke.result.update({"passed": False, "error": str(error)})
        smoke.save("failure-traceback.txt", traceback.format_exc())
    finally:
        smoke.finish()
    print(json.dumps(smoke.result, ensure_ascii=False, indent=2))
    return 0 if smoke.result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
