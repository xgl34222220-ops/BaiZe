#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OLD_VERSION = "2.5.2"
NEW_VERSION = "2.5.3"
OLD_TAG = f"v{OLD_VERSION}"
NEW_TAG = f"v{NEW_VERSION}"
OLD_CODE = "25002"
NEW_CODE = "25003"


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match for {old!r}, found {count}")
    target.write_text(text.replace(old, new), encoding="utf-8")


def replace_all(path: str, old: str, new: str, minimum: int = 1) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count < minimum:
        raise SystemExit(f"{path}: expected at least {minimum} matches for {old!r}, found {count}")
    target.write_text(text.replace(old, new), encoding="utf-8")


replace_once("v2/app/build.gradle.kts", 'versionCode = 25002', 'versionCode = 25003')
replace_once("v2/app/build.gradle.kts", 'versionName = "2.5.2"', 'versionName = "2.5.3"')

for module_prop in ("module.prop", "v2/module/module.prop"):
    replace_once(module_prop, "version=v2.5.2", "version=v2.5.3")
    replace_once(module_prop, "versionCode=25002", "versionCode=25003")

replace_once("v2/module/task-worker.sh", "detached-root-worker-v2.5.2", "detached-root-worker-v2.5.3")
replace_once("v2/module/customize.sh", 'ui_print "- 正在安装白泽 v2.5.2"', 'ui_print "- 正在安装白泽 v2.5.3"')

package_path = ROOT / "v2/scripts/package-module.sh"
package = package_path.read_text(encoding="utf-8")
for old, new, minimum in (
    ("BaiZe-v2.5.2-Module.zip", "BaiZe-v2.5.3-Module.zip", 1),
    ("detached-root-worker-v2.5.2", "detached-root-worker-v2.5.3", 1),
    ("^version=v2.5.2$", "^version=v2.5.3$", 1),
    ("^versionCode=25002$", "^versionCode=25003$", 1),
    ('ui_print "- 正在安装白泽 v2.5.2"', 'ui_print "- 正在安装白泽 v2.5.3"', 1),
    ("已生成白泽 v2.5.2", "已生成白泽 v2.5.3", 1),
):
    count = package.count(old)
    if count < minimum:
        raise SystemExit(f"package-module.sh: expected {old!r}")
    package = package.replace(old, new)
old_guard = "if unzip -p \"$OUTPUT\" customize.sh | grep -Eq 'v2\\.5\\.1|versionCode=25001|v2\\.5\\.0|versionCode=25000|v2\\.4\\.0|versionCode=24000'; then"
new_guard = "if unzip -p \"$OUTPUT\" customize.sh | grep -Eq 'v2\\.5\\.2|versionCode=25002|v2\\.5\\.1|versionCode=25001|v2\\.5\\.0|versionCode=25000|v2\\.4\\.0|versionCode=24000'; then"
if package.count(old_guard) != 1:
    raise SystemExit("package-module.sh: old-version guard mismatch")
package_path.write_text(package.replace(old_guard, new_guard), encoding="utf-8")

update = {
    "version": NEW_TAG,
    "versionCode": int(NEW_CODE),
    "zipUrl": f"https://github.com/xgl34222220-ops/BaiZe/releases/download/{NEW_TAG}/BaiZe-{NEW_TAG}-Module.zip",
    "changelog": f"https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_{NEW_TAG}.md",
}
(ROOT / "update.json").write_text(json.dumps(update, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

notes = """# 白泽 v2.5.3

白泽 v2.5.3 是面向自动清理可控性、主界面可用性和规则覆盖面的正式稳定版本。该版本在 v2.5.2 不可变深度快照基础上，加入三种明确调度模式、自动驾驶策略、界面精简和一批经过保护合同筛选的安全规则。

## 定时与自动驾驶

- 新增“智能定时”“严格间隔”“每日固定”三种显式调度模式。
- 严格间隔完全遵循用户设置周期，不会被自动驾驶延长。
- 每日固定支持指定时间和补做窗口，升级旧配置时保持兼容。
- 智能定时根据清理收益调整周期；存储压力较高时会恢复基础周期。
- 手动扫描和一键清理不受自动调度模式影响。
- 电池温度继续写入诊断状态，但不再阻止任何一种定时模式执行。

## 界面调整

- 移除记录页常驻“审计中心”悬浮按钮，避免遮挡应用列表和底部导航。
- 移除设置页常驻“自动任务体检”入口；后台自愈与诊断命令仍保留。
- 删除清理计划页重复的 Root 状态行，设置页状态改为紧凑胶囊。
- 最近结果使用简短任务标题，避免长清理文本与释放容量相互挤压。
- Material 与 MIUIx 两套界面同步收紧按钮、间距、标题和底部留白。

## 清理规则

- 新增 159 条应用私有目录规则和 119 条 Android/data 规则，共 278 条。
- 覆盖微信、QQ、小红书、微博、知乎、抖音、快手、哔哩哔哩、网易云音乐、支付宝、淘宝、京东、拼多多、美团、饿了么、高德、百度地图及常见小米系统应用。
- 规则仅包含日志、崩溃记录、性能记录、网络日志、纹理和可再生 WebView 缓存。
- 新增去重、格式和用户数据路径保护合同，明确拦截下载、草稿、数据库、聊天附件和用户媒体目录。

## 可靠性

- 保留 v2.5.2 的逐文件不可变深度清理 manifest、断点续清和元数据变化保护。
- 保留统一 Root Worker、Supervisor 自动恢复、缓存并行通道和文件归类事务。
- 三模式、自动驾驶、规则安全、审计兼容、调度健康、并发任务、深度清理、增量索引和归类事务回归全部纳入正式构建验证。
- Android Release 使用固定正式证书签名，并执行 Release Lint 与 Macrobenchmark 构建。

## 正式签名

白泽正式 APK 继续使用固定 SHA-256 证书指纹：

`9E:FA:84:80:01:CC:DC:16:8E:A9:67:53:D3:32:B1:71:3E:26:68:BA:6A:2F:A2:2D:5B:FD:98:DE:65:DB:1F:0F`
"""
(ROOT / f"RELEASE_NOTES_{NEW_TAG}.md").write_text(notes, encoding="utf-8")

release_workflow = r'''name: BaiZe v2.5.3 Immutable Release

on:
  push:
    branches:
      - main
    paths:
      - '.github/release-v2.5.3.publish'

permissions:
  contents: write

concurrency:
  group: baize-v253-immutable-release
  cancel-in-progress: false

env:
  BAIZE_VERSION: v2.5.3
  BAIZE_VERSION_NAME: 2.5.3
  BAIZE_VERSION_CODE: '25003'
  BAIZE_RELEASE_TARGET_SHA: __TARGET_SHA__
  BAIZE_CERT_SHA256: 9efa848001ccdc168ea96753d332b1713e2668ba6a2fa22d5bfd98de65db1f0f

jobs:
  verify-build-release:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout immutable v2.5.3 target
        uses: actions/checkout@v4
        with:
          ref: ${{ env.BAIZE_RELEASE_TARGET_SHA }}
          fetch-depth: 0

      - name: Verify v2.5.3 metadata
        shell: bash
        run: |
          set -euo pipefail
          test "$GITHUB_REF" = 'refs/heads/main'
          test "$(git rev-parse HEAD)" = "$BAIZE_RELEASE_TARGET_SHA"
          git diff --check
          cmp module.prop v2/module/module.prop
          grep -q 'versionName = "2.5.3"' v2/app/build.gradle.kts
          grep -q 'versionCode = 25003' v2/app/build.gradle.kts
          grep -qx 'version=v2.5.3' module.prop
          grep -qx 'versionCode=25003' module.prop
          grep -q 'BaiZe-v2.5.3-Module.zip' v2/scripts/package-module.sh
          grep -q 'detached-root-worker-v2.5.3' v2/module/task-worker.sh
          grep -Fqx 'ui_print "- 正在安装白泽 v2.5.3"' v2/module/customize.sh
          test -s RELEASE_NOTES_v2.5.3.md
          python3 - <<'PY'
          import json
          from pathlib import Path
          assert json.loads(Path('update.json').read_text()) == {
              'version': 'v2.5.3',
              'versionCode': 25003,
              'zipUrl': 'https://github.com/xgl34222220-ops/BaiZe/releases/download/v2.5.3/BaiZe-v2.5.3-Module.zip',
              'changelog': 'https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_v2.5.3.md',
          }
          PY

      - name: Create or verify immutable v2.5.3 tag
        shell: bash
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          set -euo pipefail
          remote=$(git ls-remote origin refs/tags/v2.5.3 | awk '{print $1}')
          if [ -z "$remote" ]; then
            gh api --method POST "repos/$GITHUB_REPOSITORY/git/refs" \
              -f ref='refs/tags/v2.5.3' \
              -f sha="$BAIZE_RELEASE_TARGET_SHA" >/dev/null
          else
            test "$remote" = "$BAIZE_RELEASE_TARGET_SHA"
          fi
          test "$(git ls-remote origin refs/tags/v2.5.3 | awk '{print $1}')" = "$BAIZE_RELEASE_TARGET_SHA"

      - name: Reject an existing v2.5.3 Release
        shell: bash
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          set -euo pipefail
          if gh release view v2.5.3 >/dev/null 2>&1; then
            echo 'v2.5.3 Release already exists; immutable assets must never be overwritten.' >&2
            exit 1
          fi

      - name: Install compatibility tools
        run: sudo apt-get update && sudo apt-get install -y busybox-static toybox

      - name: Verify Root scripts, native sources and regressions
        shell: bash
        run: |
          set -euo pipefail
          for script in v2/module/*.sh v2/scripts/*.sh v2/tests/*.sh service.sh cleaner.sh; do
            sh -n "$script"
            busybox ash -n "$script"
          done
          gcc -std=c11 -O2 -Wall -Wextra -Werror -Wformat=2 -Wshadow -Wconversion \
            v2/native/baize_engine_42_4.c -o /tmp/baize_engine
          gcc -std=c11 -O2 -Wall -Wextra -Werror -Wformat=2 -Wshadow -Wconversion \
            v2/native/baize_deep_snapshot.c -o /tmp/baize_deep_snapshot
          bash v2/tests/test-scheduler-fairness.sh
          bash v2/tests/test-scheduler-daily-fields.sh
          bash v2/tests/test-scheduler-health-contract.sh
          bash v2/tests/test-autopilot-controller.sh
          bash v2/tests/test-schedule-modes-contract.sh
          bash v2/tests/test-curated-rules-contract.sh
          bash v2/tests/test-audit-center-contract.sh
          bash v2/tests/test-concurrent-scheduler.sh
          bash v2/tests/test-deep-clean-budget.sh
          bash v2/tests/test-deep-manifest.sh
          bash v2/tests/test-incremental-index.sh
          bash v2/tests/test-organizer-transactions.sh

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Install Android platform and NDK
        run: yes | sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;27.2.12479018'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.13'

      - name: Prepare formal signing certificate
        shell: bash
        env:
          KEYSTORE_BASE64_RAW: ${{ secrets.BAIZE_KEYSTORE_BASE64 }}
          KEYSTORE_PASSWORD_RAW: ${{ secrets.BAIZE_KEYSTORE_PASSWORD }}
          KEY_ALIAS_RAW: ${{ secrets.BAIZE_KEY_ALIAS }}
          KEY_PASSWORD_RAW: ${{ secrets.BAIZE_KEY_PASSWORD }}
        run: |
          set -euo pipefail
          normalize_secret() {
            secret_name=$1
            raw_value=$2
            value=$(printf '%s' "$raw_value" | tr -d '\r\n')
            value=$(printf '%s' "$value" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
            case "$value" in "$secret_name="*) value=${value#*=} ;; esac
            case "$value" in
              \"*\") value=${value#\"}; value=${value%\"} ;;
              \'*\') value=${value#\'}; value=${value%\'} ;;
            esac
            printf '%s' "$value"
          }
          KEYSTORE_BASE64=$(normalize_secret BAIZE_KEYSTORE_BASE64 "$KEYSTORE_BASE64_RAW" | tr -d '[:space:]')
          KEYSTORE_PASSWORD=$(normalize_secret BAIZE_KEYSTORE_PASSWORD "$KEYSTORE_PASSWORD_RAW")
          KEY_ALIAS=$(normalize_secret BAIZE_KEY_ALIAS "$KEY_ALIAS_RAW")
          KEY_PASSWORD=$(normalize_secret BAIZE_KEY_PASSWORD "$KEY_PASSWORD_RAW")
          test -n "$KEYSTORE_BASE64"
          test -n "$KEYSTORE_PASSWORD"
          test -n "$KEY_ALIAS"
          test -n "$KEY_PASSWORD"
          KEYSTORE="$RUNNER_TEMP/baize-release.jks"
          printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$KEYSTORE"
          keytool -list -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" >/dev/null
          ACTUAL_CERT=$(keytool -exportcert -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" -rfc | openssl x509 -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':' | tr '[:upper:]' '[:lower:]')
          test "$ACTUAL_CERT" = "$BAIZE_CERT_SHA256"
          {
            echo "BAIZE_KEYSTORE_PATH=$KEYSTORE"
            echo "BAIZE_KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD"
            echo "BAIZE_KEY_ALIAS=$KEY_ALIAS"
            echo "BAIZE_KEY_PASSWORD=$KEY_PASSWORD"
          } >> "$GITHUB_ENV"

      - name: Build ARM64 native engines
        working-directory: v2
        run: sh scripts/build-native.sh

      - name: Build signed Android Release
        working-directory: v2
        shell: bash
        run: gradle --no-daemon :app:assembleRelease :app:lintRelease :macrobenchmark:assemble

      - name: Package v2.5.3 module
        working-directory: v2
        run: sh scripts/package-module.sh

      - name: Verify immutable formal artifacts
        shell: bash
        run: |
          set -euo pipefail
          ZIP='v2/dist/BaiZe-v2.5.3-Module.zip'
          APK='v2/app/build/outputs/apk/release/app-release.apk'
          RELEASE_APK='v2/dist/BaiZe-v2.5.3.apk'
          CERT='v2/dist/BaiZe-v2.5.3-signing-certificate.txt'
          test -s "$ZIP"
          test -s "$APK"
          unzip -tq "$ZIP"
          unzip -p "$ZIP" module.prop | grep -qx 'version=v2.5.3'
          unzip -p "$ZIP" module.prop | grep -qx 'versionCode=25003'
          unzip -p "$ZIP" customize.sh | grep -Fqx 'ui_print "- 正在安装白泽 v2.5.3"'
          unzip -p "$ZIP" task-worker.sh | grep -q 'detached-root-worker-v2.5.3'
          unzip -p "$ZIP" service.sh | grep -q 'RUNTIME_SCHEMA=deep-manifest-v1'
          unzip -p "$ZIP" deep-scan-manifest.sh | grep -q 'snapshot_schema=deep-file-manifest-v1'
          unzip -p "$ZIP" deep-manifest-clean.sh | grep -q 'deep_manifest_cursor'
          unzip -l "$ZIP" | grep -q 'bin/arm64-v8a/baize_engine'
          unzip -l "$ZIP" | grep -q 'bin/arm64-v8a/baize_deep_snapshot'
          PACKAGED_APK_SHA=$(unzip -p "$ZIP" app/baize.apk | sha256sum | awk '{print $1}')
          BUILT_APK_SHA=$(sha256sum "$APK" | awk '{print $1}')
          test "$PACKAGED_APK_SHA" = "$BUILT_APK_SHA"
          APKSIGNER=$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)
          "$APKSIGNER" verify --verbose --print-certs "$APK" > "$CERT"
          APK_CERT=$(awk -F': ' '/certificate SHA-256 digest/ {print tolower($NF); exit}' "$CERT" | tr -d ':[:space:]')
          test "$APK_CERT" = "$BAIZE_CERT_SHA256"
          cp "$APK" "$RELEASE_APK"
          sha256sum "$ZIP" > "$ZIP.sha256"
          sha256sum "$RELEASE_APK" > "$RELEASE_APK.sha256"

      - name: Upload verified artifacts
        uses: actions/upload-artifact@v4
        with:
          name: BaiZe-v2.5.3-Verified-Release
          if-no-files-found: error
          path: |
            v2/dist/BaiZe-v2.5.3-Module.zip
            v2/dist/BaiZe-v2.5.3-Module.zip.sha256
            v2/dist/BaiZe-v2.5.3.apk
            v2/dist/BaiZe-v2.5.3.apk.sha256
            v2/dist/BaiZe-v2.5.3-signing-certificate.txt

      - name: Publish immutable BaiZe v2.5.3 GitHub Release
        shell: bash
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          set -euo pipefail
          gh release create v2.5.3 \
            v2/dist/BaiZe-v2.5.3-Module.zip \
            v2/dist/BaiZe-v2.5.3-Module.zip.sha256 \
            v2/dist/BaiZe-v2.5.3.apk \
            v2/dist/BaiZe-v2.5.3.apk.sha256 \
            v2/dist/BaiZe-v2.5.3-signing-certificate.txt \
            --verify-tag \
            --title '白泽 v2.5.3' \
            --notes-file RELEASE_NOTES_v2.5.3.md \
            --latest

      - name: Upload release diagnostics
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: BaiZe-v2.5.3-Release-Diagnostics
          if-no-files-found: warn
          path: |
            v2/app/build/reports/lint-results-release.html
            v2/app/build/reports/lint-results-release.txt
            v2/dist
            /tmp/baize-deep-manifest-state-*
'''
(ROOT / ".github/workflows/v2.5.3-release.yml").write_text(release_workflow, encoding="utf-8")

contract = r'''#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

grep -q 'versionName = "2.5.3"' "$ROOT/v2/app/build.gradle.kts"
grep -q 'versionCode = 25003' "$ROOT/v2/app/build.gradle.kts"
cmp "$ROOT/module.prop" "$ROOT/v2/module/module.prop"
grep -qx 'version=v2.5.3' "$ROOT/module.prop"
grep -qx 'versionCode=25003' "$ROOT/module.prop"
grep -q 'BaiZe-v2.5.3-Module.zip' "$ROOT/v2/scripts/package-module.sh"
grep -q 'detached-root-worker-v2.5.3' "$ROOT/v2/module/task-worker.sh"
grep -Fqx 'ui_print "- 正在安装白泽 v2.5.3"' "$ROOT/v2/module/customize.sh"
grep -q 'BAIZE_RELEASE_TARGET_SHA: __TARGET_SHA__' "$ROOT/.github/workflows/v2.5.3-release.yml"
test -s "$ROOT/RELEASE_NOTES_v2.5.3.md"
python3 - "$ROOT/update.json" <<'PY'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == {
    'version': 'v2.5.3',
    'versionCode': 25003,
    'zipUrl': 'https://github.com/xgl34222220-ops/BaiZe/releases/download/v2.5.3/BaiZe-v2.5.3-Module.zip',
    'changelog': 'https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_v2.5.3.md',
}
PY
echo 'v2.5.3 release metadata contract passed'
'''
contract_path = ROOT / "v2/tests/test-release-v2.5.3-contract.sh"
contract_path.write_text(contract, encoding="utf-8")
contract_path.chmod(0o755)

print("prepared BaiZe v2.5.3 release metadata")
