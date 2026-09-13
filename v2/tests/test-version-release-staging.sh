#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"

TOP="$(CDPATH= cd -- .. && pwd)"
LATEST_TAG="$(git -C "$TOP" tag --list 'v[0-9]*' --sort=-v:refname | head -n 1)"
[ -n "$LATEST_TAG" ] || { echo "缺少正式版本标签"; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT INT TERM
git -C "$TOP" archive "$LATEST_TAG" | tar -xf - -C "$TMP"

OLD_VERSION="$(sed -n 's/^version=//p' "$TMP/module.prop")"
OLD_CODE="$(sed -n 's/^versionCode=//p' "$TMP/module.prop")"
NEXT_VERSION="$(sed -n 's/^version=//p' "$TOP/module.prop")"
NEXT_CODE="$(sed -n 's/^versionCode=//p' "$TOP/module.prop")"

[ "$OLD_VERSION" = "$LATEST_TAG" ] || { echo "标签与旧包版本不一致"; exit 1; }
[ "$OLD_CODE" -lt "$NEXT_CODE" ] || { echo "内部版本号没有继续递增"; exit 1; }

calc_next_version() {
    version=${1#v}
    major=${version%%.*}
    rest=${version#*.}
    minor=${rest%%.*}
    patch=${rest##*.}

    if [ "$patch" -gt 0 ]; then
        step_patch=1
    else
        step_patch=$((patch + 1))
    fi

    if [ "$minor" -eq 0 ] && [ "$patch" -eq 0 ]; then
        next_minor=1
        next_patch=1
    else
        next_minor=$minor
        next_patch=$step_patch
    fi
    printf 'v%s.%s.%s\n' "$major" "$next_minor" "$next_patch"
}

# 重构 1.x 使用独立版本序列校验；这里仍校验内部版本号和发布暂存，
# 但不再把旧 v2/v3 正式版本名推导套到重构版本上。
if [ ! -f "$TOP/docs/releases/refactor-$NEXT_VERSION.md" ]; then
    EXPECTED_VERSION="$(calc_next_version "$OLD_VERSION")"
    [ "$NEXT_VERSION" = "$EXPECTED_VERSION" ] || { echo "下一个正式版本必须是 $EXPECTED_VERSION"; exit 1; }
fi

STAGE_DIR="$TMP/stage"
mkdir -p "$STAGE_DIR"
MODULE_ARCHIVE="$TMP/BaiZe-$NEXT_VERSION.zip"

sh "$TOP/v2/scripts/sync-version.sh" \
    "$STAGE_DIR" "$MODULE_ARCHIVE" "$TMP/git-update.json" \
    "https://example.invalid/$NEXT_VERSION.zip" \
    "https://example.invalid/$NEXT_VERSION.md" \
    "https://example.invalid/checksums-$NEXT_VERSION.txt"

[ "$(sed -n 's/^version=//p' "$STAGE_DIR/module.prop")" = "$NEXT_VERSION" ] || {
    echo "发布暂存仍写入旧版本号"
    exit 1
}
[ "$(sed -n 's/^versionCode=//p' "$STAGE_DIR/module.prop")" = "$NEXT_CODE" ] || {
    echo "发布暂存仍写入旧内部版本号"
    exit 1
}
grep -q "\"versionCode\": $NEXT_CODE" "$TMP/git-update.json"
grep -q "\"version\": \"$NEXT_VERSION\"" "$TMP/git-update.json"

echo "版本切换与发布暂存测试通过"
