#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

VERSION_PATHS = [
    "module.prop",
    "v2/module/module.prop",
    "v2/app/build.gradle.kts",
    "v2/scripts/package-module.sh",
    "v2/module/task-worker.sh",
    "v2/module/customize.sh",
    "update.json",
]

for relative in VERSION_PATHS:
    path = ROOT / relative
    text = path.read_text()
    updated = text.replace("v2.5.4", "v2.5.5").replace("2.5.4", "2.5.5").replace("25004", "25005")
    if updated == text:
        raise SystemExit(f"{relative}: no v2.5.4 metadata replaced")
    path.write_text(updated)

(ROOT / "RELEASE_NOTES_v2.5.5.md").write_text("""# 白泽 v2.5.5

v2.5.5 是针对模块卸载后 `/data/adb` 残留问题的正式热修版本。

## 修复

- 修复卸载脚本使用 `pkill -f /data/adb/modules/baize_v2` 时可能把自身终止，导致后续目录清理没有执行的问题。
- 改为枚举 `/proc` 精确终止白泽后台进程，并明确跳过卸载脚本自身和 Root 管理器父进程。
- 卸载时停止调度器、守护进程、扫描器、清理器和文件归类 Worker。
- 首轮停止后再次终止并清理，防止旧后台进程重新创建 `/data/adb/baize-v2`。
- 删除 `/data/adb/baize-v2`、旧 `/data/adb/safesweep`、新旧 `modules_update` 暂存目录以及旧 `safesweep` 模块目录。
- 同时卸载白泽 App。
- 卸载前自动恢复隔离区中的用户文件；原路径冲突时转存至 `内部存储/Download/BaiZe恢复`。
- 当前 `/data/adb/modules/baize_v2` 模块本体仍按 Magisk、KernelSU、APatch 标准流程在重启后删除。

## 验证

- 动态测试从模拟的 `/data/adb/modules/baize_v2/uninstall.sh` 自身执行卸载，确认脚本不会自杀。
- 模拟后台进程在收到停止信号后重新创建状态目录，确认第二轮清理仍能彻底删除。
- 验证隔离区文件恢复、新旧目录清理、App 卸载调用、非白泽目录保护和标准 `remove` 标记。
- 正式封包强制检查 `uninstall.sh` 可执行权限与卸载清理合同。

安装 v2.5.5 后再执行卸载，并在 Root 管理器中完成重启，模块本体目录才会由管理器最终移除。
""")

(ROOT / "v2/tests/test-release-v2.5.4-contract.sh").write_text("""#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/v2.5.4-release.yml"

grep -q 'BAIZE_VERSION: v2.5.4' "$WORKFLOW"
grep -q "BAIZE_VERSION_CODE: '25004'" "$WORKFLOW"
grep -q 'BAIZE_RELEASE_TARGET_SHA: 9cf1056055a3f2fb1b74b566ec41cd268f5e853b' "$WORKFLOW"
grep -q 'BaiZe-v2.5.4-Module.zip' "$WORKFLOW"
grep -q -- '--latest' "$WORKFLOW"
test -s "$ROOT/RELEASE_NOTES_v2.5.4.md"
echo 'frozen v2.5.4 release metadata contract passed'
""")

(ROOT / "v2/tests/test-release-v2.5.5-contract.sh").write_text("""#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

grep -q 'versionName = "2.5.5"' "$ROOT/v2/app/build.gradle.kts"
grep -q 'versionCode = 25005' "$ROOT/v2/app/build.gradle.kts"
cmp "$ROOT/module.prop" "$ROOT/v2/module/module.prop"
grep -qx 'version=v2.5.5' "$ROOT/module.prop"
grep -qx 'versionCode=25005' "$ROOT/module.prop"
grep -q 'BaiZe-v2.5.5-Module.zip' "$ROOT/v2/scripts/package-module.sh"
grep -q 'test-uninstall-cleanup.sh' "$ROOT/v2/scripts/package-module.sh"
grep -q 'detached-root-worker-v2.5.5' "$ROOT/v2/module/task-worker.sh"
grep -Fqx 'ui_print "- 正在安装白泽 v2.5.5"' "$ROOT/v2/module/customize.sh"
grep -q 'BAIZE_VERSION: v2.5.5' "$ROOT/.github/workflows/v2.5.5-release.yml"
grep -q "BAIZE_VERSION_CODE: '25005'" "$ROOT/.github/workflows/v2.5.5-release.yml"
grep -q 'test-uninstall-cleanup.sh' "$ROOT/.github/workflows/v2.5.5-release.yml"
grep -q -- '--latest' "$ROOT/.github/workflows/v2.5.5-release.yml"
test -s "$ROOT/RELEASE_NOTES_v2.5.5.md"
python3 - "$ROOT/update.json" <<'PY'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == {
    'version': 'v2.5.5',
    'versionCode': 25005,
    'zipUrl': 'https://github.com/xgl34222220-ops/BaiZe/releases/download/v2.5.5/BaiZe-v2.5.5-Module.zip',
    'changelog': 'https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_v2.5.5.md',
}
PY
echo 'v2.5.5 release metadata contract passed'
""")

release_dir = ROOT / "v2/release"
release_dir.mkdir(parents=True, exist_ok=True)

source = (ROOT / ".github/workflows/v2.5.4-release.yml").read_text()
workflow = source.replace("v2.5.4", "v2.5.5").replace("2.5.4", "2.5.5").replace("25004", "25005").replace("v254", "v255")
workflow = workflow.replace("9cf1056055a3f2fb1b74b566ec41cd268f5e853b", "__BAIZE_V255_TARGET_SHA__")
needle = "          bash v2/tests/test-audit-center-contract.sh\n"
if needle not in workflow:
    raise SystemExit("release workflow test insertion point not found")
workflow = workflow.replace(needle, needle + "          bash v2/tests/test-uninstall-cleanup.sh\n", 1)
(release_dir / "v2.5.5-release.yml.template").write_text(workflow)

ci = (ROOT / ".github/workflows/v2.5-concurrent-scheduler-ci.yml").read_text()
ci = ci.replace("      - 'RELEASE_NOTES_v2.5.4.md'\n", "      - 'RELEASE_NOTES_v2.5.4.md'\n      - 'RELEASE_NOTES_v2.5.5.md'\n", 1)
ci = ci.replace("      - '.github/workflows/v2.5.4-release.yml'\n", "      - '.github/workflows/v2.5.4-release.yml'\n      - '.github/workflows/v2.5.5-release.yml'\n", 1)
ci = ci.replace("release/v2.5.4]", "release/v2.5.4, release/v2.5.5]", 1)
ci = ci.replace(
    "      - name: Verify v2.5.4 release metadata\n        run: bash v2/tests/test-release-v2.5.4-contract.sh\n",
    "      - name: Verify frozen v2.5.4 release metadata\n        run: bash v2/tests/test-release-v2.5.4-contract.sh\n      - name: Verify v2.5.5 release metadata\n        run: bash v2/tests/test-release-v2.5.5-contract.sh\n",
    1,
)
(release_dir / "v2.5-concurrent-scheduler-ci.yml.template").write_text(ci)

print("v2.5.5 release metadata prepared")
