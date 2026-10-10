#!/usr/bin/env bash
# 真机回归（HyperOS，Root）：
#   1. QQ / 微信收到的安装包常被改名为 xxx.apk.1，且 Android 11+ 位于 Android/data；
#      安装包扫描、共享索引与文件归类都只认最后一个扩展名，一个也找不到。
#   2. 文件归类永远不能把相机 / 相册 / 录屏原件（DCIM、Pictures、Movies、录屏目录）纳入计划。
set -uo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
SCRIPTS="$ROOT/v2/module/scripts"
T=${TMPDIR:-/tmp}/baize-chat-apk.$$; rm -rf "$T"; mkdir -p "$T"
trap 'rm -rf "$T"' EXIT
fail=0
ok() { printf '  ok    %s\n' "$1"; }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail + 1)); }

# ---------- 夹具：一部手机的 /data/media/0 ----------
M="$T/media/0"
mk() { mkdir -p "$(dirname "$M/$1")"; printf 'PK\003\004fixture' >"$M/$1"; }
mk "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/微信.apk"
mk "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/游戏.apk.1"
mk "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/工具.APK.2"
mk "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/子目录/深层.apk.12"
mk "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/半截.apk.tmp"
mk "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/说明.apk.txt"
mk "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/版本.apk.1000"
mk "Android/data/com.tencent.mm/MicroMsg/Download/安装包.apk.1"
mk "Tencent/QQfile_recv/旧版.apk"
mk "Download/WeiXin/导出.apk.1"
mk "DCIM/Camera/IMG_0001.jpg"

echo "— apk-paths.sh：查找与删除边界接受 .apk.1 —"
# shellcheck disable=SC1090
. "$SCRIPTS/apk-paths.sh"
OUT="$T/found.nul"
apk_find_into "$M" "$OUT"
found=$(tr '\0' '\n' <"$OUT" | sed "s#^$M/##" | sort)
for want in \
  "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/微信.apk" \
  "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/游戏.apk.1" \
  "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/工具.APK.2" \
  "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/子目录/深层.apk.12" \
  "Android/data/com.tencent.mm/MicroMsg/Download/安装包.apk.1" \
  "Tencent/QQfile_recv/旧版.apk" \
  "Download/WeiXin/导出.apk.1"; do
  if printf '%s\n' "$found" | grep -Fxq -- "$want"; then ok "找到 $want"; else bad "漏掉 $want"; fi
done
for reject in "半截.apk.tmp" "说明.apk.txt" "版本.apk.1000" "IMG_0001.jpg"; do
  if printf '%s\n' "$found" | grep -Fq -- "$reject"; then bad "不应命中 $reject"; else ok "未命中 $reject"; fi
done

APK_ROOTS=$M
APK_FALLBACK_ROOTS=
APK_PRIVATE_BOUNDARIES=
if apk_scan_candidate_allowed "$M/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/游戏.apk.1"; then ok "扫描校验接受 .apk.1"; else bad "扫描校验拒绝 .apk.1"; fi
if apk_path_allowed "$M/Android/data/com.tencent.mm/MicroMsg/Download/安装包.apk.1"; then ok "删除校验接受 .apk.1"; else bad "删除校验拒绝 .apk.1"; fi
if apk_path_allowed "$M/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/半截.apk.tmp"; then bad "删除校验接受了 .apk.tmp"; else ok "删除校验拒绝 .apk.tmp"; fi
if apk_scan_candidate_allowed "$M/DCIM/Camera/IMG_0001.jpg"; then bad "扫描校验接受了相机照片"; else ok "扫描校验拒绝相机照片"; fi

echo "— organizer-worker.sh：.apk.1 归入安装包，相机原件不参与归类 —"
FN="$T/org-fn.sh"
for fn in organizer_lower category_for normalized_path is_suspicious_app_resource is_browser_package is_mail_package \
          is_telegram_package allowed_app_source is_public_user_path allowed_source; do
  sed -n "/^$fn() {/,/^}$/p" "$SCRIPTS/organizer-worker.sh" >>"$FN"
done
# shellcheck disable=SC1090
. "$FN"
BAIZE_CAT_MAP=" apk=安装包 jpg=图片 mp4=视频 pdf=文档 "
for name in "a.apk" "a.apk.1" "a.APK.2" "a.apk.999"; do
  category_for "/x/$name"
  if [ "$CATEGORY" = 安装包 ]; then ok "$name → 安装包"; else bad "$name → '${CATEGORY}'"; fi
done
category_for "/x/a.apk.tmp"
if [ "$CATEGORY" = 安装包 ]; then bad "a.apk.tmp 不应归为安装包"; else ok "a.apk.tmp 不归为安装包"; fi

MEDIA_ROOT=/data/media
check_source() {
  allowed_source "$1" 图片; got=$?
  if [ "$got" = "$2" ]; then ok "$3"; else bad "$3（$1 期望 $2 实际 $got）"; fi
}
check_source /data/media/0/DCIM/Camera/IMG_1.jpg 1 "DCIM/Camera 不参与归类"
check_source /data/media/0/dcim/Camera/IMG_2.jpg 1 "小写 dcim 不参与归类"
check_source /data/media/0/Pictures/旅行/IMG_3.jpg 1 "Pictures 相册不参与归类"
check_source /data/media/0/Movies/家庭.mp4 1 "Movies 不参与归类"
check_source /data/media/0/MIUI/ScreenRecorder/r.mp4 1 "MIUI 录屏不参与归类"
check_source /data/media/0/ScreenRecorder/r.mp4 1 "录屏目录不参与归类"
check_source /data/media/0/Download/海报.jpg 0 "Download 仍可归类"
check_source /data/media/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/游戏.apk.1 0 "QQ 接收目录仍可归类"

echo "— 原生索引：is_apk / 归类扩展名接受 .apk.1 —"
CC=${CC:-$(command -v cc || command -v gcc || true)}
if [ -n "$CC" ]; then
  if grep -q 'is_apk_copy' "$ROOT/v2/native/baize_engine_42_4.c"; then ok "原生引擎识别 .apk.N 副本"; else bad "原生引擎缺少 .apk.N 识别"; fi
else
  echo "  skip  未找到 C 编译器"
fi

echo
if [ "$fail" -eq 0 ]; then echo "全部通过"; else echo "$fail 项失败"; exit 1; fi
