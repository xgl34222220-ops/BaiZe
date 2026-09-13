#!/bin/sh
set -eu

TOP="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
CURRENT_VERSION="$(sed -n 's/^version=//p' "$TOP/module.prop")"
CURRENT_CODE="$(sed -n 's/^versionCode=//p' "$TOP/module.prop")"

case "$CURRENT_VERSION" in
    v[1-9]*.*.*) ;;
    *) echo "重构版版本号无效：$CURRENT_VERSION"; exit 1 ;;
esac
case "$CURRENT_CODE" in
    ''|*[!0-9]*) echo "内部构建号无效：$CURRENT_CODE"; exit 1 ;;
esac

[ -f "$TOP/docs/releases/refactor-$CURRENT_VERSION.md" ] || {
    echo "缺少当前重构版发布说明：docs/releases/refactor-$CURRENT_VERSION.md"
    exit 1
}

# 当前源码元数据必须自洽；这个测试不再依赖旧 v2/v3 标签。
sh "$TOP/v2/scripts/sync-version.sh" --source-only --check

calc_next_version() {
    version=${1#v}
    major=${version%%.*}
    rest=${version#*.}
    minor=${rest%%.*}
    patch=${rest##*.}
    if [ "$minor" -eq 0 ] && [ "$patch" -eq 0 ]; then
        printf 'v%s.%s.%s\n' "$major" "$major" "$major"
    elif [ "$minor" -eq "$major" ] && [ "$patch" -eq "$major" ]; then
        printf 'v%s.0.0\n' "$((major + 1))"
    else
        echo "版本不属于重构版本线：$version" >&2
        return 1
    fi
}

EXPECTED_NEXT="$(calc_next_version "$CURRENT_VERSION")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT INT TERM
STAGE="$TMP/repo"
mkdir -p "$STAGE"

# 只依赖当前提交内容，确保 shallow checkout、无历史 tag 的 CI 也能执行。
git -C "$TOP" archive HEAD | tar -xf - -C "$STAGE"
cp "$STAGE/update.json" "$TMP/update-before.json"

sh "$STAGE/v2/scripts/sync-version.sh" --source-only --next
NEXT_VERSION="$(sed -n 's/^version=//p' "$STAGE/module.prop")"
NEXT_CODE="$(sed -n 's/^versionCode=//p' "$STAGE/module.prop")"

[ "$NEXT_VERSION" = "$EXPECTED_NEXT" ] || {
    echo "下一正式版本错误：期望 $EXPECTED_NEXT，实际 $NEXT_VERSION"
    exit 1
}
[ "$NEXT_CODE" -gt "$CURRENT_CODE" ] || {
    echo "内部构建号没有递增"
    exit 1
}

# --source-only 只能同步源码元数据，不能提前切 OTA。
cmp "$STAGE/update.json" "$TMP/update-before.json"

[ "$(sed -n 's/^version=//p' "$STAGE/v2/module/module.prop")" = "$NEXT_VERSION" ]
[ "$(sed -n 's/^versionCode=//p' "$STAGE/v2/module/module.prop")" = "$NEXT_CODE" ]
grep -Fq "versionCode = $NEXT_CODE" "$STAGE/v2/app/build.gradle.kts"
grep -Fq "versionName = \"${NEXT_VERSION#v}\"" "$STAGE/v2/app/build.gradle.kts"
grep -Fq "BaiZe-$NEXT_VERSION-Module.zip" "$STAGE/v2/scripts/package-module.sh"
grep -Fq "正在安装白泽 $NEXT_VERSION" "$STAGE/v2/module/customize.sh"
grep -Fq "detached-root-worker-$NEXT_VERSION" "$STAGE/v2/module/task-worker.sh"

echo "重构版发布暂存测试通过：$CURRENT_VERSION -> $NEXT_VERSION"
