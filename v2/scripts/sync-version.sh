#!/usr/bin/env sh
# 版本号单一真相源：module.prop。
#
# 此前发一个版本要手动同步 6 处：module.prop、v2/module/module.prop、
# v2/app/build.gradle.kts（versionCode + versionName）、update.json、
# v2/scripts/package-module.sh、v2/module/customize.sh 的安装提示。
# 漏改任何一处都会让 release 校验在 CI 上炸掉。
#
# 用法：
#   sh v2/scripts/sync-version.sh            把其余文件同步成 module.prop 的版本
#   sh v2/scripts/sync-version.sh --check    只校验一致性，不写文件（CI 用）
#   sh v2/scripts/sync-version.sh --set v2.6.0   先改 module.prop 再同步
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
MODULE_PROP="$ROOT/module.prop"
CHECK_ONLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --check) CHECK_ONLY=1 ;;
    --set)
      shift
      [ $# -gt 0 ] || { echo "--set 需要一个版本号，例如 v2.6.0" >&2; exit 2; }
      NEW="$1"
      case "$NEW" in
        v[0-9]*.[0-9]*.[0-9]*) ;;
        *) echo "版本号格式应为 vMAJOR.MINOR.PATCH，例如 v2.6.0" >&2; exit 2 ;;
      esac
      NEW_NAME=${NEW#v}
      MAJOR=$(echo "$NEW_NAME" | cut -d. -f1)
      MINOR=$(echo "$NEW_NAME" | cut -d. -f2)
      PATCH=$(echo "$NEW_NAME" | cut -d. -f3)
      NEW_CODE=$((MAJOR * 10000 + MINOR * 1000 + PATCH))
      sed -i.bak "s/^version=.*/version=$NEW/; s/^versionCode=.*/versionCode=$NEW_CODE/" "$MODULE_PROP"
      rm -f "$MODULE_PROP.bak"
      echo "module.prop 已更新为 $NEW (versionCode=$NEW_CODE)"
      ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
  shift
done

VERSION=$(sed -n 's/^version=//p' "$MODULE_PROP" | head -n1)
VERSION_CODE=$(sed -n 's/^versionCode=//p' "$MODULE_PROP" | head -n1)
[ -n "$VERSION" ] || { echo "module.prop 缺少 version" >&2; exit 1; }
[ -n "$VERSION_CODE" ] || { echo "module.prop 缺少 versionCode" >&2; exit 1; }
VERSION_NAME=${VERSION#v}

fail=0
note() { printf '  %s\n' "$1"; }

apply() {
  target=$1 pattern=$2 replacement=$3 description=$4
  if [ ! -f "$target" ]; then
    note "[缺失] $target"
    fail=$((fail + 1))
    return
  fi
  if grep -q -- "$replacement" "$target" 2>/dev/null; then
    return
  fi
  if [ "$CHECK_ONLY" = "1" ]; then
    note "[不一致] $(basename "$target"): 期望 $description"
    fail=$((fail + 1))
  else
    sed -i.bak "s|$pattern|$replacement|" "$target"
    rm -f "$target.bak"
    note "[已更新] $(basename "$target"): $description"
  fi
}

echo "版本单一来源：module.prop = $VERSION (versionCode=$VERSION_CODE)"

if ! cmp -s "$MODULE_PROP" "$ROOT/v2/module/module.prop"; then
  if [ "$CHECK_ONLY" = "1" ]; then
    note "[不一致] v2/module/module.prop 与根 module.prop 不同"
    fail=$((fail + 1))
  else
    cp -f "$MODULE_PROP" "$ROOT/v2/module/module.prop"
    note "[已更新] v2/module/module.prop"
  fi
fi

apply "$ROOT/v2/app/build.gradle.kts" \
  'versionCode = [0-9]*' "versionCode = $VERSION_CODE" "versionCode = $VERSION_CODE"
apply "$ROOT/v2/app/build.gradle.kts" \
  'versionName = "[^"]*"' "versionName = \"$VERSION_NAME\"" "versionName = \"$VERSION_NAME\""
apply "$ROOT/v2/scripts/package-module.sh" \
  'BaiZe-v[0-9.]*-Module.zip' "BaiZe-$VERSION-Module.zip" "BaiZe-$VERSION-Module.zip"
apply "$ROOT/v2/module/customize.sh" \
  'ui_print "- 正在安装白泽 v[0-9.]*"' "ui_print \"- 正在安装白泽 $VERSION\"" "安装提示 $VERSION"
apply "$ROOT/v2/module/task-worker.sh" \
  'detached-root-worker-v[0-9.]*' "detached-root-worker-$VERSION" "worker 标识 $VERSION"

# update.json：检测与下载都走 GitHub Raw，避免 Root 管理器对 Release 302 重定向兼容不一致。
# 正式 Release 仍作为人工下载与归档入口；downloads 分支只保存与正式版 SHA256 一致的模块 ZIP 镜像。
UPDATE_JSON="$ROOT/update.json"
EXPECTED_JSON=$(cat <<EOF
{
  "version": "$VERSION",
  "versionCode": $VERSION_CODE,
  "zipUrl": "https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/downloads/releases/$VERSION/BaiZe-$VERSION-Module.zip",
  "changelog": "https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/RELEASE_NOTES_$VERSION.md"
}
EOF
)
if [ "$(cat "$UPDATE_JSON" 2>/dev/null)" != "$EXPECTED_JSON" ]; then
  if [ "$CHECK_ONLY" = "1" ]; then
    note "[不一致] update.json"
    fail=$((fail + 1))
  else
    printf '%s\n' "$EXPECTED_JSON" > "$UPDATE_JSON"
    note "[已更新] update.json"
  fi
fi

if [ "$fail" -gt 0 ]; then
  echo
  echo "$fail 处版本不一致。运行 sh v2/scripts/sync-version.sh 自动修正。" >&2
  exit 1
fi
echo "版本一致性检查通过"
