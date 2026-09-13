#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"

TOP="$(CDPATH= cd -- .. && pwd)"
LATEST_TAG="$(git -C "$TOP" tag --list 'v[0-9]*' --sort=-v:refname | head -n 1)"
[ -n "$LATEST_TAG" ] || { echo "缺少正式版本标签"; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT INT TERM
OLD_TREE="$TMP/old"
mkdir -p "$OLD_TREE"
git -C "$TOP" archive "$LATEST_TAG" | tar -xf - -C "$OLD_TREE"

OLD_VERSION="$(sed -n 's/^version=//p' "$OLD_TREE/module.prop")"
OLD_CODE="$(sed -n 's/^versionCode=//p' "$OLD_TREE/module.prop")"
NEXT_VERSION="$(sed -n 's/^version=//p' "$TOP/module.prop")"
NEXT_CODE="$(sed -n 's/^versionCode=//p' "$TOP/module.prop")"

[ "$OLD_VERSION" = "$LATEST_TAG" ] || { echo "标签与旧包版本不一致"; exit 1; }
[ "$OLD_CODE" -lt "$NEXT_CODE" ] || { echo "内部版本号没有继续递增"; exit 1; }

# 重构正式版使用独立显示版本序列；旧 v2/v3 标签只作为覆盖升级的
# versionCode 基线，不能拿旧显示版本去推导重构版名称。
[ -f "$TOP/docs/releases/refactor-$NEXT_VERSION.md" ] || {
    echo "缺少重构版发布说明：docs/releases/refactor-$NEXT_VERSION.md"
    exit 1
}

# sync-version.sh 以它所在仓库树为工作根目录，只接受 --check、
# --source-only、--next、--set。发布暂存测试因此复制最小仓库树后，
# 在暂存树内调用脚本；不要把暂存目录当作位置参数传入。
STAGE_DIR="$TMP/stage"
mkdir -p "$STAGE_DIR/v2/scripts" "$STAGE_DIR/v2/module" "$STAGE_DIR/v2/app"
cp "$TOP/v2/scripts/sync-version.sh" "$TOP/v2/scripts/package-module.sh" "$STAGE_DIR/v2/scripts/"
cp "$TOP/module.prop" "$TOP/update.json" "$STAGE_DIR/"
cp "$TOP/v2/module/module.prop" "$TOP/v2/module/customize.sh" "$TOP/v2/module/task-worker.sh" "$STAGE_DIR/v2/module/"
cp "$TOP/v2/app/build.gradle.kts" "$STAGE_DIR/v2/app/"
cp "$STAGE_DIR/update.json" "$TMP/previous-update.json"

# 源码元数据必须已经是待发布版本，同时 OTA 仍停留在旧正式版。
sh "$STAGE_DIR/v2/scripts/sync-version.sh" --source-only --check
cmp "$STAGE_DIR/update.json" "$TMP/previous-update.json"
if sh "$STAGE_DIR/v2/scripts/sync-version.sh" --check >/dev/null 2>&1; then
    echo "正式发布前的全量检查必须拒绝尚未切换的 OTA"
    exit 1
fi

# 模拟正式产物已验证后切 OTA，再进行完整一致性检查。
sh "$STAGE_DIR/v2/scripts/sync-version.sh"
sh "$STAGE_DIR/v2/scripts/sync-version.sh" --check

[ "$(sed -n 's/^version=//p' "$STAGE_DIR/module.prop")" = "$NEXT_VERSION" ] || {
    echo "发布暂存仍写入旧版本号"
    exit 1
}
[ "$(sed -n 's/^versionCode=//p' "$STAGE_DIR/module.prop")" = "$NEXT_CODE" ] || {
    echo "发布暂存仍写入旧内部版本号"
    exit 1
}
[ "$NEXT_VERSION" = "$(sed -n 's/^version=//p' "$STAGE_DIR/v2/module/module.prop")" ]

grep -q "\"versionCode\": $NEXT_CODE" "$STAGE_DIR/update.json"
grep -q "\"version\": \"$NEXT_VERSION\"" "$STAGE_DIR/update.json"
grep -q "/releases/refactor-$NEXT_VERSION/BaiZe-$NEXT_VERSION-Module.zip" "$STAGE_DIR/update.json"
grep -q "/docs/releases/refactor-$NEXT_VERSION.md" "$STAGE_DIR/update.json"

echo "版本切换与发布暂存测试通过"
