#!/usr/bin/env sh
# module.prop is the source; refactor display versions and Android build codes are independent.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
MODULE_PROP="$ROOT/module.prop"
CHECK_ONLY=0
SOURCE_ONLY=0
NEW=""
NEXT=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --check) CHECK_ONLY=1 ;;
    --source-only) SOURCE_ONLY=1 ;;
    --next) NEXT=1 ;;
    --set) shift; [ "$#" -gt 0 ] || { echo '缺少版本号' >&2; exit 2; }; NEW="$1" ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
  shift
done
VERSION=$(sed -n 's/^version=//p' "$MODULE_PROP" | head -n1)
VERSION_CODE=$(sed -n 's/^versionCode=//p' "$MODULE_PROP" | head -n1)
printf '%s\n' "$VERSION" | grep -Eq '^v[1-9][0-9]*\.[0-9]+\.[0-9]+$' || exit 2
case "$VERSION_CODE" in ''|*[!0-9]*) echo '构建号无效' >&2; exit 2 ;; esac
NAME=${VERSION#v}; MAJOR=${NAME%%.*}; REST=${NAME#*.}; MINOR=${REST%%.*}; PATCH=${REST#*.}
if [ "$MINOR" = 0 ] && [ "$PATCH" = 0 ]; then EXPECTED="v$MAJOR.$MAJOR.$MAJOR"
elif [ "$MINOR" = "$MAJOR" ] && [ "$PATCH" = "$MAJOR" ]; then EXPECTED="v$((MAJOR + 1)).0.0"
else echo '版本不属于重构版本线：n.0.0 → n.n.n → (n+1).0.0' >&2; exit 2; fi
if [ "$NEXT" = 1 ]; then
  [ -z "$NEW" ] || { echo '--next 与 --set 不能同时使用' >&2; exit 2; }
  NEW="$EXPECTED"
fi
if [ -n "$NEW" ] && [ "$NEW" != "$VERSION" ]; then
  [ "$CHECK_ONLY" = 0 ] || { echo '--check 不允许变更版本' >&2; exit 2; }
  [ "$NEW" = "$EXPECTED" ] || { echo "下一个正式版本必须是 $EXPECTED" >&2; exit 2; }
  OTA_CODE=$(sed -n 's/.*"versionCode"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$ROOT/update.json" 2>/dev/null | head -n1)
  case "$OTA_CODE" in ''|*[!0-9]*) OTA_CODE=0 ;; esac
  [ "$VERSION_CODE" -ge "$OTA_CODE" ] || VERSION_CODE="$OTA_CODE"
  VERSION_CODE=$((VERSION_CODE + 1))
  VERSION="$NEW"
  sed -i "s/^version=.*/version=$VERSION/;s/^versionCode=.*/versionCode=$VERSION_CODE/" "$MODULE_PROP"
fi
VERSION_NAME=${VERSION#v}
fail=0
apply() {
  target=$1 pattern=$2 replacement=$3
  [ -f "$target" ] || { echo "缺少文件：$target" >&2; fail=$((fail + 1)); return; }
  if grep -Fq -- "$replacement" "$target"; then return; fi
  if [ "$CHECK_ONLY" = 1 ]; then echo "版本不一致：$target，期望 $replacement" >&2; fail=$((fail + 1))
  else
    sed -i "s|$pattern|$replacement|" "$target"
    grep -Fq -- "$replacement" "$target" || { echo "无法同步：$target" >&2; fail=$((fail + 1)); }
  fi
}
if ! cmp -s "$MODULE_PROP" "$ROOT/v2/module/module.prop"; then
  if [ "$CHECK_ONLY" = 1 ]; then fail=$((fail + 1)); else cp "$MODULE_PROP" "$ROOT/v2/module/module.prop"; fi
fi
apply "$ROOT/v2/app/build.gradle.kts" 'versionCode = [0-9]*' "versionCode = $VERSION_CODE"
apply "$ROOT/v2/app/build.gradle.kts" 'versionName = "[^"]*"' "versionName = \"$VERSION_NAME\""
if ! grep -Fq 'OUTPUT="$OUT/BaiZe-$VERSION-Module.zip"' "$ROOT/v2/scripts/package-module.sh"; then
  apply "$ROOT/v2/scripts/package-module.sh" 'BaiZe-v[0-9.]*-Module.zip' "BaiZe-$VERSION-Module.zip"
fi
apply "$ROOT/v2/module/customize.sh" 'ui_print "- 正在安装白泽 v[0-9.]*"' "ui_print \"- 正在安装白泽 $VERSION\""
apply "$ROOT/v2/module/task-worker.sh" 'detached-root-worker-v[0-9.]*' "detached-root-worker-$VERSION"
EXPECTED_JSON=$(cat <<EOF
{
  "version": "$VERSION",
  "versionCode": $VERSION_CODE,
  "zipUrl": "https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/downloads/releases/refactor-$VERSION/BaiZe-$VERSION-Module.zip",
  "changelog": "https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/docs/releases/refactor-$VERSION.md"
}
EOF
)
if [ "$SOURCE_ONLY" = 1 ]; then echo 'OTA 保持不变，等待正式产物与镜像校验'
elif [ "$(cat "$ROOT/update.json" 2>/dev/null)" != "$EXPECTED_JSON" ]; then
  if [ "$CHECK_ONLY" = 1 ]; then echo 'update.json 未同步' >&2; fail=$((fail + 1))
  else printf '%s\n' "$EXPECTED_JSON" > "$ROOT/update.json"; fi
fi
[ "$fail" = 0 ] || { echo "$fail 处版本不一致" >&2; exit 1; }
echo "版本一致：$VERSION，内部构建号 $VERSION_CODE"
