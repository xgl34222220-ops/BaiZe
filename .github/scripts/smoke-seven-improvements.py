"""Launcher-driven checks of the seven improvement surfaces on the signed APK.

Imported by smoke-workbench-apk.py after its disposable-emulator fresh install.
Only test-owned files are introduced. No feature Activity is directly started,
no preview rule is enabled, and no cleaning or export confirmation is accepted.
"""
import base64
import hashlib
import json
import re
import shlex
import time
import uuid

# Generated 64 x 48 RGB gradient, ordinary baseline SDR JPEG, no EXIF/ICC/private
# data. Embedded so the CI smoke runner needs neither Pillow nor a network fetch.
JPEG = base64.b64decode(
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAIBAQEBAQIBAQECAgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDAsKDAkKCgr/"
    "2wBDAQICAgICAgUDAwUKBwYHCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAAwAEADASIAAhEBAxEB/"
    "8QAFgABAQEAAAAAAAAAAAAAAAAABggJ/8QAHRAAAQUAAwEAAAAAAAAAAAAAAAUGIzGhAQMRIf/EABcBAQEBAQAAAAAAAAAAAAAAAAcFBAn/xAAbEQACAwEBAQAA"
    "AAAAAAAAAAAABgMEIjFhMv/aAAwDAQACEQMRAD8Aw/RWLUOC5FYtQ4N0Vi1DguRWLUOGWG56Yl1p5oDorFqHBcisWocHCKxahwWorFqHCrDcHZdaeaBC"
    "KxahwWorFqHBwisWvOnBaisWocKkNz0dl1p5oEIrFqHBaisWocHCKxqhwWorFqHCtDc9HVeaeaImRWLUOC1FYtQ4OEVi1DgtRWLUOAtDcOBy6080CEVi1D"
    "gtRWLUODhFYtQ4LkVi1DhVhuejsutPNAdFYtQ4LkVi1Dg3RWLUOC5FYtedOFWG4Oq80/OgOisWocFyKxahwborFqHBcisaocKkNz0dl5p5oiVFYvHyHBcisW"
    "ocG6KxahwXIrFqHAXhuenA5daeaA6KxahwXIrFqHBuisWocFyKxahwqw3B1Xmn50B0Vi1DguRWLUODhFYtQ4LUVi1DhUhuejsvNPNAhFYtQ4LUVi1Dg4R"
    "WLXnTgtRWLUOFaG56Oy6080f/2Q=="
)
JPEG_SHA256 = "e5b36edecc89cf5211cd94caf41d5b6ce1beb7a6b1234d2e03e72fb529dd628d"


def bounds(node):
    points = list(map(int, re.findall(r"-?\d+", node.attrib.get("bounds", ""))))
    return points if len(points) == 4 else [0, 0, 0, 0]


def labels(root):
    return "\n".join(n.attrib.get("text", "") for n in root.iter("node"))


class NavigationSmoke:
    def __init__(self, smoke, expect_top):
        self.m = smoke
        self.expect_top = expect_top
        size = smoke.adb("shell", "wm", "size")
        self.width, self.height = map(int, re.findall(r"(\d+)x(\d+)", size)[-1])
        density = smoke.adb("shell", "wm", "density")
        values = re.findall(r"(?:Physical|Override) density:\s*(\d+)", density)
        self.density = int(values[-1]) / 160 if values else 1.0
        self.events = []

    def tree(self, name):
        return self.m.ui("seven-" + name)

    def content_bottom(self, root):
        parents = {child: parent for parent in root.iter() for child in parent}
        tab_tops = []
        for label in ("首页", "清理", "记录", "设置"):
            nodes = [n for n in root.iter("node") if n.attrib.get("text") == label and bounds(n)[1] > self.height * .6]
            if not nodes:
                return self.height
            node = max(nodes, key=lambda n: bounds(n)[1])
            while node is not None and node.attrib.get("clickable") != "true":
                node = parents.get(node)
            if node is None or bounds(node)[1] < self.height * .6:
                return self.height
            tab_tops.append(bounds(node)[1])
        # The floating dock is drawn over the home/settings LazyColumn. Visible
        # XML text beneath it is not a usable action. Exclude its actual tab
        # bounds plus the 6 dp shell inset, without guessing a pixel threshold.
        return min(tab_tops) - round(6 * self.density)

    def action_bounds(self, root, node):
        x1, y1, x2, y2 = bounds(node)
        if x2 <= x1 or y2 <= y1 or x1 >= self.width or y1 >= self.height or x2 <= 0 or y2 <= 0:
            return None
        parents = {child: parent for parent in root.iter() for child in parent}
        current = parents.get(node)
        left, top, right, bottom = 0, 0, self.width, self.content_bottom(root)
        while current is not None:
            if current.attrib.get("scrollable") == "true":
                a, b, c, d = bounds(current)
                left, top, right, bottom = max(left, a), max(top, b), min(right, c), min(bottom, d)
            current = parents.get(current)
        # Require the whole label to be clear vertically. If it is clipped by
        # the scroll viewport or covered by the dock, find() scrolls it clear.
        if y1 < top or y2 > bottom or x2 <= left or x1 >= right:
            return None
        return [max(left, x1), y1, min(right, x2), y2]

    def matching(self, root, label, contains=False):
        matches = []
        for node in root.iter("node"):
            values = (node.attrib.get("text", ""), node.attrib.get("content-desc", ""))
            if any(label in value if contains else label == value for value in values) and self.action_bounds(root, node):
                matches.append(node)
        return matches

    def scroll(self, root, direction="down"):
        # Respect the actual scroll viewport (including dialog content), rather
        # than swiping across the sticky header, selection bar or navigation tabs.
        regions = [bounds(n) for n in root.iter("node") if n.attrib.get("scrollable") == "true"]
        regions = [b for b in regions if b[3] - b[1] > self.height * .18 and b[2] > b[0]]
        if regions:
            x1, y1, x2, y2 = max(regions, key=lambda b: (b[3] - b[1]) * (b[2] - b[0]))
        else:
            x1, y1, x2, y2 = 0, int(self.height * .17), self.width, int(self.height * .82)
        x1, x2 = max(0, x1), min(self.width, x2)
        y1, y2 = max(0, y1), min(self.content_bottom(root), y2)
        x = (x1 + x2) // 2
        high, low = int(y1 + (y2 - y1) * .18), int(y1 + (y2 - y1) * .82)
        start, end = (low, high) if direction == "down" else (high, low)
        self.m.adb("shell", "input", "swipe", str(x), str(start), str(x), str(end), "350")
        time.sleep(.6)

    def find(self, label, name, contains=False, direction="down", swipes=12):
        # Re-entered Compose lists preserve their prior offset. Search the likely
        # direction first, then the other direction rather than assuming the
        # target must be below the current viewport. Stop at a stable boundary.
        for pass_index, search_direction in enumerate((direction, "up" if direction == "down" else "down")):
            previous = None
            for attempt in range(swipes + 1):
                root = self.tree(f"{name}-{pass_index}-{attempt}")
                found = self.matching(root, label, contains)
                if found:
                    return root, found[0]
                signature = tuple((n.attrib.get("text"), n.attrib.get("content-desc"), n.attrib.get("bounds")) for n in root.iter("node"))
                if signature == previous:
                    break
                previous = signature
                if attempt < swipes:
                    self.scroll(root, search_direction)
        raise AssertionError(f"Launcher navigation cannot reach {label!r}: {labels(root)}")

    def click_node(self, root, node, name):
        # Compose exposes a label underneath its clickable parent. Reject a
        # disabled ancestor too; tapping a disabled label is not route coverage.
        parents = {child: parent for parent in root.iter() for child in parent}
        current = node
        while current is not None:
            if current.attrib.get("enabled") == "false":
                raise AssertionError(f"Action is disabled: {name}")
            if current.attrib.get("clickable") == "true":
                break
            current = parents.get(current)
        target = self.action_bounds(root, node)
        if target is None:
            raise AssertionError(f"Action is clipped or covered by navigation: {name}")
        x1, y1, x2, y2 = target
        x, y = (x1 + x2) // 2, (y1 + y2) // 2
        self.m.adb("shell", "input", "tap", str(x), str(y))
        self.events.append({"action": name, "text": node.attrib.get("text", ""), "description": node.attrib.get("content-desc", "")})
        time.sleep(.8)
        self.m.alive()

    def tap(self, label, name, **kwargs):
        root, node = self.find(label, name + "-before", **kwargs)
        self.click_node(root, node, name)

    def wait_text(self, text, name, timeout=45, direction="up"):
        deadline = time.monotonic() + timeout
        attempt = 0
        while True:
            root = self.tree(f"{name}-{attempt}")
            if text in labels(root):
                self.m.alive()
                return root
            if time.monotonic() >= deadline:
                raise AssertionError(f"Timed out waiting for {text!r}: {labels(root)}")
            self.scroll(root, direction)
            time.sleep(.4)
            attempt += 1

    def evidence(self, name, component=None):
        if component:
            self.expect_top(component, "seven-" + name)
        self.m.capture("seven-" + name)

    def back(self, component, name):
        self.m.adb("shell", "input", "keyevent", "4")
        time.sleep(.8)
        self.m.alive()
        self.expect_top(component, "seven-" + name)

    def home(self, name):
        self.expect_top("MiuixDashboardActivity", "seven-" + name)
        self.m.tap_label("首页", "seven-" + name)
        self.find("整理空间", name + "-shortcuts", direction="up")

    def enter_analysis(self, name):
        self.tap("存储分析", name)
        self.expect_top("StorageToolsActivity", "seven-" + name)
        self.wait_text("存储分析完成", name + "-complete")

    def open_directory(self, folder, name):
        self.find("目录占用", name + "-section")
        self.tap("0", name + "-volume")
        self.find("/storage/emulated/0", name + "-volume-path", direction="up")
        self.tap("Download", name + "-downloads")
        self.find("/storage/emulated/0/Download", name + "-downloads-path", direction="up")
        self.tap(folder, name + "-fixture-directory")
        self.find("/storage/emulated/0/Download/" + folder, name + "-fixture-path", direction="up")

    def leave_directory(self, name):
        for level in range(3):
            self.back("StorageToolsActivity", f"{name}-{level}")
        # Directory-back must restore analysis, rather than accidentally exiting.
        self.find("打开照片瘦身", name + "-analysis-restored", direction="up")

    def pick_jpeg(self, folder):
        root = self.tree("photo-picker")
        packages = {n.attrib.get("package", "") for n in root.iter("node")}
        if not any(package.endswith("documentsui") for package in packages):
            raise AssertionError(f"Expected the real system document picker: {packages}")
        self.evidence("photo-system-picker")
        # Always navigate through the system picker. Never inject a content URI
        # into PhotoCompressionActivity or bypass its OpenDocument callback.
        drawer_labels = ("Show roots", "Open navigation drawer", "显示根目录", "显示位置", "打开导航抽屉")
        drawer = next((node for label in drawer_labels for node in self.matching(root, label)), None)
        if drawer is None:
            drawer = next((n for n in root.iter("node") if n.attrib.get("resource-id") == "android:id/home"), None)
        if drawer is None:
            raise AssertionError("DocumentsUI navigation drawer was not exposed")
        self.click_node(root, drawer, "photo-picker-open-roots")
        root = self.tree("photo-picker-roots")
        downloads = [node for label in ("Downloads", "下载", "下载内容") for node in self.matching(root, label)]
        if not downloads:
            raise AssertionError(f"Downloads is not reachable in DocumentsUI: {labels(root)}")
        self.click_node(root, downloads[-1], "photo-picker-downloads")
        self.tap(folder, "photo-picker-fixture-folder")
        self.tap("preview.jpg", "photo-picker-select-jpeg")
        self.expect_top("PhotoCompressionActivity", "seven-photo-import-return")

    def replace_rule(self, rule):
        self.find("路径模式|保留天数", "rule-input-visible")
        root = self.tree("rule-input")
        editors = [n for n in root.iter("node") if n.attrib.get("class") == "android.widget.EditText"]
        if len(editors) != 1:
            raise AssertionError(f"Expected one real rule editor, found {len(editors)}")
        self.click_node(root, editors[0], "rule-input-focus")
        self.m.adb("shell", "input", "keycombination", "113", "29")  # Ctrl+A on API 36.
        self.m.adb("shell", "input", "keyevent", "67")
        self.m.adb("shell", "input text " + shlex.quote(rule))
        root = self.tree("rule-input-replaced")
        if not any(n.attrib.get("class") == "android.widget.EditText" and n.attrib.get("text") == rule for n in root.iter("node")):
            raise AssertionError("The exact run-owned rule was not entered; refusing broader trial")
        self.m.adb("shell", "input", "keyevent", "4")  # Dismiss IME only.
        time.sleep(.6)
        self.expect_top("RuleBundleActivity", "seven-rule-after-keyboard")


def run(smoke, expect_top):
    nav = NavigationSmoke(smoke, expect_top)
    assert hashlib.sha256(JPEG).hexdigest() == JPEG_SHA256, "Synthetic JPEG changed"
    folder = "BaizeSmoke-" + uuid.uuid4().hex[:10]
    directory = "/storage/emulated/0/Download/" + folder
    fixture = smoke.OUT / (folder + ".jpg")
    fixture.write_bytes(JPEG)
    created = []

    def add_fixture(name):
        path = directory + "/" + name
        smoke.adb("push", str(fixture), path)
        created.append(path)
        smoke.adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + path)
        query = "content query --uri content://media/external/file --projection _id:_display_name:_data:_size --where " + shlex.quote("_data='" + path + "'")
        deadline = time.monotonic() + 45
        while True:
            indexed = smoke.adb("shell", query)
            if path in indexed and f"_size={len(JPEG)}" in indexed:
                smoke.save_text("seven-index-" + name + ".txt", indexed)
                break
            if time.monotonic() >= deadline:
                raise AssertionError(f"Synthetic fixture was not indexed: {path}; {indexed}")
            time.sleep(1)
        return path

    def rule_state():
        path = f"/data/user/0/{smoke.APP}/files/custom-preview-enabled.rules"
        return smoke.adb("shell", f"if [ -e {shlex.quote(path)} ]; then sha256sum {shlex.quote(path)}; else echo ABSENT; fi")

    smoke.adb("shell", "mkdir -p /storage/emulated/0/Download && mkdir " + shlex.quote(directory))
    try:
        photo = add_fixture("preview.jpg")
        add_fixture("duplicate.jpg")
        smoke.adb("shell", "appops", "set", smoke.APP, "MANAGE_EXTERNAL_STORAGE", "allow")
        # Resolve the public launcher intent. Every feature after this point is
        # reached by visible home/settings controls, with a checked return stack.
        # am start's implicit resolution requires a DEFAULT category, which a
        # launcher-only filter need not declare. Resolve the registered launcher
        # exactly as a launcher query does, then start only that verified home.
        launchers = smoke.adb("shell", "cmd", "package", "query-activities", "--brief", "--components",
            "--query-flags", "0", "--user", "0", "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER", "-p", smoke.APP)
        smoke.save_text("seven-launcher-query.txt", launchers)
        components = re.findall(re.escape(smoke.APP) + r"/[A-Za-z0-9_.$]+", launchers)
        expected_launcher = smoke.APP + "/.MiuixDashboardActivity"
        expanded_launcher = smoke.APP + "/" + smoke.APP + ".MiuixDashboardActivity"
        if not any(component in (expected_launcher, expanded_launcher) for component in components):
            raise AssertionError(f"Installed package has no expected registered home launcher: {launchers}")
        launcher = next(component for component in components if component in (expected_launcher, expanded_launcher))
        smoke.adb("shell", "am", "force-stop", smoke.APP)
        smoke.save_text("seven-launcher.txt", smoke.adb("shell", "am", "start", "-W", "-n", launcher))
        nav.wait_text("首页", "launcher-home")
        nav.home("launcher-home")
        nav.tap("照片瘦身", "home-open-photo")
        nav.expect_top("PhotoCompressionActivity", "seven-photo-entry")
        nav.tap("选择照片", "photo-select")
        nav.pick_jpeg(folder)
        nav.wait_text("照片已读取，原图未修改", "photo-imported", direction="down")
        nav.tap("生成压缩预览", "photo-generate-preview", direction="up")
        nav.wait_text("预览已生成", "photo-preview-complete", direction="down")
        nav.find("原图方向校正后的预览", "photo-original-image")
        nav.find("压缩副本效果预览", "photo-compressed-image")
        nav.evidence("photo-preview", "PhotoCompressionActivity")
        nav.back("MiuixDashboardActivity", "photo-back-to-home")
        nav.home("trash-home")
        nav.tap("回收站", "home-open-trash")
        nav.evidence("trash-from-home", "FileTrashActivity")
        nav.back("MiuixDashboardActivity", "trash-back-to-home")
        nav.home("analysis-home")
        nav.enter_analysis("home-storage-analysis")
        # Secondary entries remain reachable too, but the user's direct home
        # routes above are required and cannot be replaced by these older paths.
        nav.tap("打开照片瘦身", "analysis-open-photo-secondary")
        nav.evidence("photo-from-analysis-secondary", "PhotoCompressionActivity")
        nav.back("StorageToolsActivity", "photo-back-to-analysis")

        nav.find("最近谁长胖了", "growth-card")
        nav.evidence("growth-card", "StorageToolsActivity")
        nav.find("目录占用", "directory-section", direction="up")
        nav.open_directory(folder, "directory-first")
        nav.find("preview.jpg", "directory-source-file")
        nav.find("duplicate.jpg", "directory-copy-file")
        nav.evidence("directory-fixtures", "StorageToolsActivity")
        nav.leave_directory("directory-back")

        # A new indexed file proves that the visible refresh control really ran.
        # This does not infer a cache speedup from emulator timing.
        add_fixture("scan-added.jpg")
        nav.tap("重新扫描", "analysis-refresh", direction="up")
        nav.wait_text("存储分析完成", "analysis-refreshed")
        nav.open_directory(folder, "directory-refreshed")
        nav.find("scan-added.jpg", "refresh-new-file")
        nav.evidence("refresh-new-file", "StorageToolsActivity")
        nav.leave_directory("refresh-directory-back")
        nav.tap("回收站", "analysis-top-trash", direction="up")
        nav.evidence("trash-from-analysis", "FileTrashActivity")
        nav.back("StorageToolsActivity", "trash-back-to-analysis")
        nav.back("MiuixDashboardActivity", "analysis-back-to-home")

        nav.home("duplicate-home")
        nav.tap("重复文件", "home-duplicates")
        nav.expect_top("StorageToolsActivity", "seven-duplicates-entry")
        nav.wait_text("组内容相同的文件", "duplicates-complete")
        nav.tap("设置保留偏好", "duplicate-keeper-open")
        nav.tap("优先最早副本", "duplicate-keeper-oldest")
        nav.tap("应用", "duplicate-keeper-apply")
        nav.find("保留偏好：优先最早副本", "duplicate-keeper-applied", direction="up")
        nav.evidence("duplicate-keeper-applied", "StorageToolsActivity")
        nav.back("MiuixDashboardActivity", "duplicate-back-to-home")
        nav.home("duplicate-reopen-home")
        nav.tap("重复文件", "home-duplicates-reopen")
        nav.wait_text("组内容相同的文件", "duplicates-reopen-complete")
        nav.find("保留偏好：优先最早副本", "duplicate-keeper-persisted")
        nav.evidence("duplicate-keeper-persisted", "StorageToolsActivity")
        # Re-scan in the same ViewModel exercises the incremental digest path;
        # assert completion and navigation only, never unmeasured performance.
        nav.tap("重新扫描", "duplicate-refresh", direction="up")
        nav.wait_text("组内容相同的文件", "duplicate-refresh-complete")
        nav.evidence("duplicate-refresh", "StorageToolsActivity")
        nav.back("MiuixDashboardActivity", "duplicate-final-back")

        smoke.tap_label("设置", "seven-settings-tab")
        nav.tap("自动任务记录", "settings-task-history")
        nav.find("下次检查", "task-history-next-check")
        nav.find("最近执行与跳过原因", "task-history-reasons")
        nav.evidence("task-history-dialog", "MiuixDashboardActivity")
        nav.tap("完成", "task-history-dismiss")
        nav.expect_top("MiuixDashboardActivity", "seven-settings-after-dialog")
        # Reopen and cancel with Android Back to cover the dismiss path too.
        nav.tap("自动任务记录", "settings-task-history-reopen")
        nav.back("MiuixDashboardActivity", "task-history-back")
        before_rules = rule_state()
        nav.tap("规则版本与试跑", "settings-rule-bundle")
        rule_stack = nav.expect_top("RuleBundleActivity", "seven-rule-bundle-single-page")
        assert rule_stack.count("RuleBundleActivity") == 1, f"Duplicate rule page: {rule_stack}"
        nav.evidence("rule-bundle-entry", "RuleBundleActivity")
        nav.replace_rule(photo + "|0")
        nav.tap("预览命中", "rule-preview-read-only")
        nav.wait_text("只读试跑完成", "rule-preview-complete", direction="up")
        nav.find("命中 1 项", "rule-preview-one-fixture", contains=True)
        nav.find(photo, "rule-preview-exact-fixture")
        nav.evidence("rule-preview-result", "RuleBundleActivity")
        nav.back("MiuixDashboardActivity", "rules-back-to-settings")
        assert rule_state() == before_rules, "Read-only preview changed enabled rules"
        for path in created:
            actual = smoke.adb("shell", "sha256sum", path).split()[0]
            assert actual == JPEG_SHA256, f"Preview or scan modified a fixture: {path}"
        nav.home("all-routes-return-home")
        result = {
            "launcher_home_routes_only": True,
            "home_photo_system_picker_and_generated_preview": True,
            "home_trash_and_return": True,
            "analysis_secondary_photo_and_return": True,
            "duplicate_keeper_preference_saved_and_reopened": True,
            "directory_drilldown_and_back": True,
            "growth_card_reachable": True,
            "analysis_refresh_found_new_indexed_fixture": True,
            "duplicate_refresh_completed": True,
            "analysis_top_trash_and_return": True,
            "settings_task_history_next_check_and_reasons": True,
            "settings_rule_preview_exactly_one_owned_fixture": True,
            "rule_bundle_single_activity": True,
            "preview_rules_unchanged": True,
            "fixture_source_bytes_unchanged": True,
            "destructive_actions_performed": False,
            "cache_performance_measured": False,
            "fixture_directory": directory,
            "scope": "Signed release UI reachability and read-only preview; no root scheduler execution, rule-pack import, photo export, deletion, or cache benchmark claim",
        }
        smoke.save_text("seven-improvements-passed.json", json.dumps(result, ensure_ascii=False, indent=2))
        return result
    except Exception:
        smoke.capture("seven-navigation-failure")
        raise
    finally:
        smoke.save_text("seven-navigation-actions.json", json.dumps(nav.events, ensure_ascii=False, indent=2))
        # Removal is strictly confined to paths created above, on the disposable
        # emulator; never sweep Download or any pre-existing fixture/user file.
        for path in created:
            smoke.adb("shell", "rm", "-f", path, check=False)
            smoke.adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + path, check=False)
        smoke.adb("shell", "rmdir", directory, check=False)
