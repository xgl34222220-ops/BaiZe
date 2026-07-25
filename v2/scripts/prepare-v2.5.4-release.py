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
    updated = text.replace("v2.5.3", "v2.5.4").replace("2.5.3", "2.5.4").replace("25003", "25004")
    if updated == text:
        raise SystemExit(f"{relative}: no v2.5.3 metadata replaced")
    path.write_text(updated)

notes = ROOT / "RELEASE_NOTES_v2.5.4.md"
notes.write_text("""# 白泽 v2.5.4

v2.5.4 是针对 v2.5.3 自动任务状态和文件归类问题的正式热修版本。

## 修复

- 修复文件自动归类运行时，其他任务全部错误显示“后台调度正在自动恢复”的问题。
- 调度器在长时间 Root Worker 执行期间持续刷新心跳，不再把正常运行误判为服务失联。
- 首页按真实任务组显示状态：当前任务显示正在执行，其他任务显示等待当前任务完成。
- 修复自动文件归类到期后可能重建整机共享存储索引、长时间占用后台的问题。
- 自动归类改为只扫描下载、文档、蓝牙、QQ/TIM 接收目录以及浏览器和邮箱下载目录。
- 自动归类单轮最多运行 3 分钟、最多检查 2000 个文件，剩余内容留到下次继续。
- 自动归类不再逐个文件无限发送媒体扫描广播，避免完成阶段长时间卡住。
- 手动“一键归类”仍保留完整共享索引能力。

## 验证

- 通过自动归类全盘索引陷阱测试，确认自动路径不会调用整机索引。
- 通过长任务心跳、并发调度、增量索引、归类事务和深度清理回归。
- 通过 Android Release 编译、Lint、正式证书和模块内嵌 APK 一致性校验。

升级模块后请完整重启设备，使旧的后台 Worker 和调度器进程全部替换为 v2.5.4。
""")

old_test = ROOT / "v2/tests/test-release-v2.5.3-contract.sh"
old_test.write_text("""#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/v2.5.3-release.yml"

grep -q 'BAIZE_VERSION: v2.5.3' "$WORKFLOW"
grep -q "BAIZE_VERSION_CODE: '25003'" "$WORKFLOW"
grep -q 'BAIZE_RELEASE_TARGET_SHA: 15c56f69f07c6a3d9b21ca664c24875a3735efa0' "$WORKFLOW"
grep -q 'BaiZe-v2.5.3-Module.zip' "$WORKFLOW"
grep -q -- '--latest' "$WORKFLOW"
test -s "$ROOT/RELEASE_NOTES_v2.5.3.md"
echo 'frozen v2.5.3 release metadata contract passed'
""")

new_test = ROOT / "v2/tests/test-release-v2.5.4-contract.sh"
new_test.write_text("""#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

grep -q 'versionName = "2.5.4"' "$ROOT/v2/app/build.gradle.kts"
grep -q 'versionCode = 25004' "$ROOT/v2/app/build.gradle.kts"
cmp "$ROOT/module.prop" "$ROOT/v2/module/module.prop"
grep -qx 'version=v2.5.4' "$ROOT/module.prop"
grep -qx 'versionCode=25004' "$ROOT/module.prop"
grep -q 'BaiZe-v2.5.4-Module.zip' "$ROOT/v2/scripts/package-module.sh"
grep -q 'detached-root-worker-v2.5.4' "$ROOT/v2/module/task-worker.sh"
grep -Fqx 'ui_print "- 正在安装白泽 v2.5.4"' "$ROOT/v2/module/customize.sh"
grep -q 'BAIZE_VERSION: v2.5.4' "$ROOT/.github/workflows/v2.5.4-release.yml"
grep -q "BAIZE_VERSION_CODE: '25004'" "$ROOT/.github/workflows/v2.5.4-release.yml"
grep -q -- '--latest' "$ROOT/.github/workflows/v2.5.4-release.yml"
test -s "$ROOT/RELEASE_NOTES_v2.5.4.md"
python3 - "$ROOT/update.json" <<'PY'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == {
    'version': 'v2.5.4',
    'versionCode': 25004,
    'zipUrl': 'https://github.com/xgl34222220-ops/BaiZe/releases/download/v2.5.4/BaiZe-v2.5.4-Module.zip',
    'changelog': 'https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_v2.5.4.md',
}
PY
echo 'v2.5.4 release metadata contract passed'
""")

source_workflow = (ROOT / ".github/workflows/v2.5.3-release.yml").read_text()
template = source_workflow.replace("v2.5.3", "v2.5.4").replace("v253", "v254").replace("25003", "25004")
template = template.replace(
    "15c56f69f07c6a3d9b21ca664c24875a3735efa0",
    "__BAIZE_V254_TARGET_SHA__",
)
release_dir = ROOT / "v2/release"
release_dir.mkdir(parents=True, exist_ok=True)
(release_dir / "v2.5.4-release.yml.template").write_text(template)

print("v2.5.4 release metadata prepared")
