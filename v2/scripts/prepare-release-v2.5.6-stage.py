#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
os.chdir(ROOT)


def replace(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if old not in text:
        raise SystemExit(f"missing expected text in {path}: {old!r}")
    file.write_text(text.replace(old, new))


replace("module.prop", "version=v2.5.5\nversionCode=25005", "version=v2.5.6\nversionCode=25006")
replace("v2/module/module.prop", "version=v2.5.5\nversionCode=25005", "version=v2.5.6\nversionCode=25006")
replace(
    "v2/app/build.gradle.kts",
    'versionCode = 25005\n        versionName = "2.5.5"',
    'versionCode = 25006\n        versionName = "2.5.6"',
)
replace("v2/module/customize.sh", "正在安装白泽 v2.5.5", "正在安装白泽 v2.5.6")
replace("v2/module/task-worker.sh", "detached-root-worker-v2.5.5", "detached-root-worker-v2.5.6")

package = Path("v2/scripts/package-module.sh")
text = package.read_text().replace("v2.5.5", "v2.5.6").replace("25005", "25006")
text = text.replace(
    "unzip -p \"$OUTPUT\" apk-scanner.sh | grep -q 'storage-files.nul'",
    "unzip -p \"$OUTPUT\" apk-scanner.sh | grep -q 'apk-files.nul'",
)
text = text.replace(
    "grep -Eq 'v2\\.5\\.2|versionCode=25002|",
    "grep -Eq 'v2\\.5\\.5|versionCode=25005|v2\\.5\\.2|versionCode=25002|",
)
package.write_text(text)

Path("update.json").write_text(
    json.dumps(
        {
            "version": "v2.5.6",
            "versionCode": 25006,
            "zipUrl": "https://github.com/xgl34222220-ops/BaiZe/releases/download/v2.5.6/BaiZe-v2.5.6-Module.zip",
            "changelog": "https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_v2.5.6.md",
        },
        ensure_ascii=False,
        indent=2,
    ) + "\n"
)

Path("RELEASE_NOTES_v2.5.6.md").write_text(
    """# 白泽 v2.5.6

本版本完成原生 App 界面全量重构，并修复安装包扫描耗时长、手动扫描结果为空以及保留时间不可修改的问题。

## 全新原生 UI

- 首页、清理、记录、设置四个一级页面按参考视频重新设计。
- MIUIX 与 Material 3 共用相同的信息架构和交互路径，切换外观不再退回旧页面。
- 使用浅紫灰背景、大圆角分组卡片、紧凑标签页、系统设置式列表和悬浮玻璃底栏。
- 保留 Monet 动态取色、深色模式与 AMOLED 纯黑模式。
- 修复部分设备中圆角卡片内部出现矩形浅色块的渲染问题，主体卡片统一使用实色单层圆角容器。

## 安装包扫描与自动清理

- App 手动扫描始终显示当前可找到的全部 APK、APKS、XAPK 与 APKM，不再错误套用后台保留期。
- 扫描复用增量共享索引，并直接读取专用安装包索引，避免每次点击都强制重建并遍历全量文件索引。
- 后台安装包保留时间由固定 30 天改为可设置 0–365 天。
- `0 天`表示扫描到后即可进入后台自动清理范围；手动扫描始终不受该时间限制。
- 保留白名单、符号链接、文件大小、快照授权和删除前状态复核。

## 兼容与安全

- 首页一键清理与一键归类继续直接执行 Root 任务。
- 扫描工作台继续使用不可变快照，清理阶段不会重新发现或接受 UI 新增路径。
- 未修改模块 ID 与 App 包名，可直接从正式 v2.5.5 升级。
- 正式 APK 使用既有白泽发布证书签名，模块 ZIP 内嵌 APK 与独立 APK 完全一致。
"""
)

Path("v2/tests/test-apk-retention-contract.sh").write_text(
    r'''#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SCAN="$ROOT/v2/module/apk-snapshot-scan.sh"
CONTRACT="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanContract.kt"
ROUTE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanRoute.kt"
SCREEN="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/miuix/VideoCleanScreenMiuix.kt"

grep -Fq 'CONFIG_DAYS=$(get_uint apk_package_days 30 0 365)' "$SCAN"
grep -Fq 'manual|app|ui) DAYS=0' "$SCAN"
grep -Fq 'storage-index.sh" ensure "$TRIGGER"' "$SCAN"
grep -Fq 'APK_INDEX="$STATE_DIR/index/apk-files.nul"' "$SCAN"
grep -Fq 'done <"$APK_INDEX"' "$SCAN"
grep -Fq 'fun SchedulerUiState.withApkPackageDays(days: Int)' "$CONTRACT"
grep -Fq 'copy(apkPackageDays = days.coerceIn(0, 365))' "$CONTRACT"
grep -Fq 'onApkPackageDaysChanged = { days ->' "$ROUTE"
grep -Fq 'title = "安装包保留时间"' "$SCREEN"
grep -Fq 'range = 0..365' "$SCREEN"
grep -Fq '手动安装包扫描始终显示全部安装包' "$SCREEN"
echo 'apk retention and manual scan contract passed'
'''
)

Path("v2/tests/test-release-v2.5.6-contract.sh").write_text(
    r'''#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

grep -q 'versionName = "2.5.6"' "$ROOT/v2/app/build.gradle.kts"
grep -q 'versionCode = 25006' "$ROOT/v2/app/build.gradle.kts"
cmp "$ROOT/module.prop" "$ROOT/v2/module/module.prop"
grep -qx 'version=v2.5.6' "$ROOT/module.prop"
grep -qx 'versionCode=25006' "$ROOT/module.prop"
grep -q 'BaiZe-v2.5.6-Module.zip' "$ROOT/v2/scripts/package-module.sh"
grep -q "apk-scanner.sh | grep -q 'apk-files.nul'" "$ROOT/v2/scripts/package-module.sh"
grep -q 'detached-root-worker-v2.5.6' "$ROOT/v2/module/task-worker.sh"
grep -Fqx 'ui_print "- 正在安装白泽 v2.5.6"' "$ROOT/v2/module/customize.sh"
grep -q 'BAIZE_VERSION: v2.5.6' "$ROOT/.github/workflows/v2.5.6-release.yml"
grep -q "BAIZE_VERSION_CODE: '25006'" "$ROOT/.github/workflows/v2.5.6-release.yml"
grep -q 'test-apk-retention-contract.sh' "$ROOT/.github/workflows/v2.5.6-release.yml"
grep -q -- '--latest' "$ROOT/.github/workflows/v2.5.6-release.yml"
test -s "$ROOT/RELEASE_NOTES_v2.5.6.md"
python3 - "$ROOT/update.json" <<'PY'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == {
    'version': 'v2.5.6',
    'versionCode': 25006,
    'zipUrl': 'https://github.com/xgl34222220-ops/BaiZe/releases/download/v2.5.6/BaiZe-v2.5.6-Module.zip',
    'changelog': 'https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_v2.5.6.md',
}
PY
echo 'v2.5.6 release metadata contract passed'
'''
)
os.chmod("v2/tests/test-apk-retention-contract.sh", 0o755)
os.chmod("v2/tests/test-release-v2.5.6-contract.sh", 0o755)

# Build workflow contents as ordinary staging files. The GitHub connector will place them under .github/workflows.
release = Path(".github/workflows/v2.5.5-release.yml").read_text()
release = (
    release.replace("v2.5.5", "v2.5.6")
    .replace("2.5.5", "2.5.6")
    .replace("25005", "25006")
    .replace("v255", "v256")
)
release = re.sub(r"\n  BAIZE_RELEASE_TARGET_SHA: [0-9a-f]{40}", "", release)
old_checkout = '''      - name: Checkout immutable v2.5.6 target
        uses: actions/checkout@v4
        with:
          ref: ${{ env.BAIZE_RELEASE_TARGET_SHA }}
          fetch-depth: 0
'''
new_checkout = '''      - name: Checkout release trigger
        uses: actions/checkout@v4
        with:
          ref: main
          fetch-depth: 0

      - name: Resolve immutable v2.5.6 target
        shell: bash
        run: |
          set -euo pipefail
          TARGET=$(tr -d '[:space:]' < .github/release-v2.5.6.publish)
          case "$TARGET" in ''|*[!0-9a-f]*) echo 'invalid release target' >&2; exit 1 ;; esac
          test "${#TARGET}" -eq 40
          git cat-file -e "$TARGET^{commit}"
          git merge-base --is-ancestor "$TARGET" origin/main
          echo "BAIZE_RELEASE_TARGET_SHA=$TARGET" >> "$GITHUB_ENV"
          git checkout --detach "$TARGET"
'''
if old_checkout not in release:
    raise SystemExit("release checkout block not found")
release = release.replace(old_checkout, new_checkout)
release = release.replace(
    "          bash v2/tests/test-schedule-modes-contract.sh\n",
    "          bash v2/tests/test-schedule-modes-contract.sh\n"
    "          bash v2/tests/test-scan-workbench-contract.sh\n"
    "          bash v2/tests/test-home-one-tap-actions-contract.sh\n"
    "          bash v2/tests/test-apk-retention-contract.sh\n",
)
release = release.replace(
    "          unzip -p \"$ZIP\" task-worker.sh | grep -q 'detached-root-worker-v2.5.6'\n",
    "          unzip -p \"$ZIP\" task-worker.sh | grep -q 'detached-root-worker-v2.5.6'\n"
    "          unzip -p \"$ZIP\" apk-scanner.sh | grep -q 'apk-files.nul'\n"
    "          unzip -p \"$ZIP\" apk-scanner.sh | grep -q 'manual|app|ui'\n",
)

ci = Path(".github/workflows/v2.5-concurrent-scheduler-ci.yml").read_text()
ci = ci.replace(
    "      - 'RELEASE_NOTES_v2.5.5.md'\n",
    "      - 'RELEASE_NOTES_v2.5.5.md'\n      - 'RELEASE_NOTES_v2.5.6.md'\n",
)
ci = ci.replace(
    "      - '.github/workflows/v2.5.5-release.yml'\n",
    "      - '.github/workflows/v2.5.5-release.yml'\n      - '.github/workflows/v2.5.6-release.yml'\n",
)
ci = ci.replace(
    "release/v2.5.4, release/v2.5.5]",
    "release/v2.5.4, release/v2.5.5, release/v2.5.6]",
)
ci = ci.replace(
    "      - name: Existing scheduler regression\n",
    "      - name: Verify v2.5.6 release metadata\n"
    "        run: bash v2/tests/test-release-v2.5.6-contract.sh\n"
    "      - name: Existing scheduler regression\n",
)
ci = ci.replace(
    "      - name: Quarantine authorization and restore contract\n",
    "      - name: APK retention and manual scan contract\n"
    "        run: bash v2/tests/test-apk-retention-contract.sh\n"
    "      - name: Quarantine authorization and restore contract\n",
)

stage = Path("v2/build/release-prep")
stage.mkdir(parents=True, exist_ok=True)
(stage / "v2.5.6-release.yml").write_text(release)
(stage / "v2.5-concurrent-scheduler-ci.yml").write_text(ci)

# Remove one-time Python helpers; the workflow itself is removed later by the connector.
Path("v2/scripts/prepare-release-v2.5.6.py").unlink(missing_ok=True)
Path("v2/scripts/prepare-release-v2.5.6-stage.py").unlink(missing_ok=True)

subprocess.run(["git", "add", "module.prop", "v2/module/module.prop", "v2/app/build.gradle.kts",
                "v2/module/customize.sh", "v2/module/task-worker.sh", "v2/scripts/package-module.sh",
                "update.json", "RELEASE_NOTES_v2.5.6.md", "v2/tests/test-apk-retention-contract.sh",
                "v2/tests/test-release-v2.5.6-contract.sh", "v2/build/release-prep",
                "v2/scripts/prepare-release-v2.5.6.py", "v2/scripts/prepare-release-v2.5.6-stage.py"], check=True)
subprocess.run(["git", "commit", "-m", "release: prepare BaiZe v2.5.6 stable content"], check=True)
subprocess.run(["git", "push", "origin", "HEAD:release/v2.5.6"], check=True)
