#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
os.chdir(ROOT)


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if text.count(old) != 1:
        raise SystemExit(f"expected one match in {path}, found {text.count(old)}: {old!r}")
    file.write_text(text.replace(old, new, 1))


replace_once("module.prop", "version=v2.5.6\nversionCode=25006", "version=v2.5.7\nversionCode=25007")
replace_once("v2/module/module.prop", "version=v2.5.6\nversionCode=25006", "version=v2.5.7\nversionCode=25007")
replace_once(
    "v2/app/build.gradle.kts",
    'versionCode = 25006\n        versionName = "2.5.6"',
    'versionCode = 25007\n        versionName = "2.5.7"',
)
replace_once("v2/module/customize.sh", "正在安装白泽 v2.5.6", "正在安装白泽 v2.5.7")
replace_once("v2/module/task-worker.sh", "detached-root-worker-v2.5.6", "detached-root-worker-v2.5.7")

package = Path("v2/scripts/package-module.sh")
package_text = package.read_text().replace("v2.5.6", "v2.5.7").replace("25006", "25007")
old_guard = "grep -Eq 'v2\\.5\\.5|versionCode=25005|"
new_guard = "grep -Eq 'v2\\.5\\.6|versionCode=25006|v2\\.5\\.5|versionCode=25005|"
if old_guard not in package_text:
    raise SystemExit("package old-version guard was not found")
package.write_text(package_text.replace(old_guard, new_guard, 1))

Path("update.json").write_text(
    json.dumps(
        {
            "version": "v2.5.7",
            "versionCode": 25007,
            "zipUrl": "https://github.com/xgl34222220-ops/BaiZe/releases/download/v2.5.7/BaiZe-v2.5.7-Module.zip",
            "changelog": "https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_v2.5.7.md",
        },
        ensure_ascii=False,
        indent=2,
    ) + "\n"
)

Path("RELEASE_NOTES_v2.5.7.md").write_text(
    """# 白泽 v2.5.7

本版本修复正式版设置页面开关在操作后自动回退的问题，并加固设置保存与后台状态轮询之间的同步逻辑。

## 设置开关不再自动回退

- 设置页新增本地配置草稿，切换开关后会立即保留用户选择。
- Root 服务前台轮询只更新任务运行状态、队列、下次执行时间和守护进程状态，不再覆盖尚未保存的设置。
- 点击“保存设置”并由服务端确认后，界面才结束草稿状态并同步最终配置。
- 保存失败或服务端暂时返回旧配置时，当前修改会继续保留，可直接再次保存。

## 覆盖范围

- 修复“仅在息屏时执行”“仅在充电时执行”“仅在系统空闲时执行”。
- 修复“归类时等待息屏”“归类时等待充电”“归类时等待系统空闲”。
- 同时保护最低电量、单文件上限、任务完成通知和零结果通知等尚未保存的设置。

## 兼容与安全

- 未修改模块 ID 与 App 包名，可直接从正式 v2.5.6 升级。
- 保留原有 Root 调度器、不可变扫描快照、白名单和清理保护逻辑。
- 正式 APK 继续使用既有白泽发布证书签名。
- 模块 ZIP 内嵌 APK 与独立 APK 完全一致，并同时发布 SHA-256 校验文件与签名证书信息。
"""
)

Path("v2/tests/test-settings-draft-rollback-contract.sh").write_text(
    r'''#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ROUTE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/settings/SettingsRoute.kt"
for expected in \
  'var draft by remember { mutableStateOf(scheduler.copy(saving = false)) }' \
  'var dirty by remember { mutableStateOf(false) }' \
  'var saveRequested by remember { mutableStateOf(false) }' \
  'LaunchedEffect(scheduler)' \
  'scheduler.hasSameEditableConfig(draft)' \
  'draft.withRuntimeFrom(scheduler)' \
  'private fun SchedulerUiState.hasSameEditableConfig' \
  'private fun SchedulerUiState.withRuntimeFrom(remote: SchedulerUiState)'; do
  grep -Fq "$expected" "$ROUTE"
done
grep -Fq 'onUpdateScheduler = { updated ->' "$ROUTE"
grep -Fq 'onSaveScheduler = { requested ->' "$ROUTE"
! grep -Fq 'onUpdateScheduler = dashboardActions.updateScheduler' "$ROUTE"
! grep -Fq 'onSaveScheduler = dashboardActions.saveScheduler' "$ROUTE"
echo 'settings draft rollback regression contract passed'
'''
)

Path("v2/tests/test-release-v2.5.7-contract.sh").write_text(
    r'''#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/v2.5.7-release.yml"
cmp "$ROOT/module.prop" "$ROOT/v2/module/module.prop"
grep -q 'versionName = "2.5.7"' "$ROOT/v2/app/build.gradle.kts"
grep -q 'versionCode = 25007' "$ROOT/v2/app/build.gradle.kts"
grep -qx 'version=v2.5.7' "$ROOT/module.prop"
grep -qx 'versionCode=25007' "$ROOT/module.prop"
grep -q 'BaiZe-v2.5.7-Module.zip' "$ROOT/v2/scripts/package-module.sh"
grep -q 'detached-root-worker-v2.5.7' "$ROOT/v2/module/task-worker.sh"
grep -Fqx 'ui_print "- 正在安装白泽 v2.5.7"' "$ROOT/v2/module/customize.sh"
grep -q 'BAIZE_VERSION: v2.5.7' "$WORKFLOW"
grep -q "BAIZE_VERSION_CODE: '25007'" "$WORKFLOW"
grep -q '.github/release-v2.5.7.publish' "$WORKFLOW"
grep -q 'test-settings-draft-rollback-contract.sh' "$WORKFLOW"
grep -q -- "--title '白泽 v2.5.7'" "$WORKFLOW"
grep -q -- '--latest' "$WORKFLOW"
test -s "$ROOT/RELEASE_NOTES_v2.5.7.md"
python3 - "$ROOT/update.json" <<'PY'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == {
    'version': 'v2.5.7',
    'versionCode': 25007,
    'zipUrl': 'https://github.com/xgl34222220-ops/BaiZe/releases/download/v2.5.7/BaiZe-v2.5.7-Module.zip',
    'changelog': 'https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_v2.5.7.md',
}
PY
echo 'v2.5.7 release metadata contract passed'
'''
)

os.chmod("v2/tests/test-settings-draft-rollback-contract.sh", 0o755)
os.chmod("v2/tests/test-release-v2.5.7-contract.sh", 0o755)
Path("v2/scripts/prepare-release-v2.5.7.py").unlink(missing_ok=True)

paths = [
    "module.prop",
    "v2/module/module.prop",
    "v2/app/build.gradle.kts",
    "v2/module/customize.sh",
    "v2/module/task-worker.sh",
    "v2/scripts/package-module.sh",
    "update.json",
    "RELEASE_NOTES_v2.5.7.md",
    "v2/tests/test-settings-draft-rollback-contract.sh",
    "v2/tests/test-release-v2.5.7-contract.sh",
    "v2/scripts/prepare-release-v2.5.7.py",
]
subprocess.run(["git", "add", "--", *paths], check=True)
subprocess.run(["git", "diff", "--cached", "--check"], check=True)
subprocess.run(["git", "commit", "-m", "release: prepare BaiZe v2.5.7 stable content"], check=True)
