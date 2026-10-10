#!/usr/bin/env bash
# allowed_source 的用户 ID 与来源判定。
#
# 历史缺陷：判定写的是 "$MEDIA_ROOT"/[0-9]/*，[0-9] 只匹配一个字符，
# 于是工作资料（user 10）与多用户（10 起编号）的文件全部被拒绝。
# 索引侧用的是 [0-9]*、Kotlin 侧用的是 \d+，两边本来就是对的，
# 结果同一台设备上手动归类能处理工作资料、定时归类不能。
set -uo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
SRC="$ROOT/v2/module/scripts/organizer-worker.sh"
T=${TMPDIR:-/tmp}/baize-org-src; rm -rf "$T"; mkdir -p "$T"

MEDIA_ROOT=/data/media
for fn in normalized_path is_suspicious_app_resource is_browser_package is_mail_package \
          is_telegram_package allowed_app_source is_public_user_path allowed_source; do
  sed -n "/^$fn() {/,/^}$/p" "$SRC" >> "$T/fn.sh"
done
# shellcheck disable=SC1090
. "$T/fn.sh"

fail=0
check() {
  local path=$1 want=$2 why=$3
  allowed_source "$path" 图片
  local got=$?
  if [ "$got" != "$want" ]; then
    printf '  [FAIL] %-58s 期望 %s 实际 %s (%s)\n' "$path" "$want" "$got" "$why"
    fail=$((fail + 1))
  fi
}

echo "allowed_source 用户 ID 与来源判定"
echo
echo "— 多位用户 ID（回归） —"
check "/data/media/0/Download/a.jpg"   0 "主用户"
check "/data/media/10/Download/a.jpg"  0 "工作资料 user 10"
check "/data/media/11/Download/a.jpg"  0 "多用户 user 11"
check "/data/media/123/Download/a.jpg" 0 "三位用户 ID"

echo "— 非法用户 ID —"
check "/data/media/abc/Download/a.jpg" 1 "非数字用户"
check "/data/media//Download/a.jpg"    1 "空用户段"
check "/data/media/0"                  1 "只有用户段，没有文件"
check "/other/root/0/Download/a.jpg"   1 "不在 MEDIA_ROOT 下"

echo "— 已归类目录不得再次归类 —"
check "/data/media/0/BaiZe归类/图片/a.jpg"        1 "自己的输出目录"
check "/data/media/10/BaiZe归类/视频/a.mp4"       1 "工作资料下的输出目录"
check "/data/media/0/sub/BaiZe归类/图片/a.jpg"    1 "嵌套的输出目录"

echo "— 公共目录 —"
check "/data/media/0/Download/a.jpg"              0 "Download"
check "/data/media/0/Documents/a.pdf"             0 "Documents"
check "/data/media/0/Bluetooth/a.jpg"             0 "蓝牙接收"
check "/data/media/0/Tencent/QQfile_recv/a.jpg"   0 "QQ 接收"
check "/data/media/10/Tencent/QQfile_recv/a.jpg"  0 "工作资料的 QQ 接收"
check "/data/media/0/Pictures/a.jpg"              1 "相册不是归类来源"
check "/data/media/0/DCIM/a.jpg"                  1 "DCIM 不是归类来源"

echo "— 3.0.0 参考 Aurora 补齐的下载来源 —"
check "/data/media/0/Android/data/org.telegram.messenger/files/Telegram/Telegram Files/a.apk" 0 "Telegram files 下载"
check "/data/media/0/Android/data/tw.nekomimi.nekogram/files/Telegram/Telegram Files/b.zip"    0 "Telegram 分支"
check "/data/media/0/Android/data/com.tencent.mobileqqi/Tencent/QQfile_recv/a.apk.1"             0 "腾讯系 file_recv"
check "/data/media/0/Android/data/com.tencent.minihd.qq/Tencent/MobileQQfile_recv/a.apk"         0 "腾讯系 *file_recv"
check "/data/media/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/x/file_recv/a.apk"      1 "file_recv 只认 Tencent 下一层"
check "/data/media/0/Android/data/com.tencent.android.qqdownloader/files/tassistant/apk/a.apk"   0 "应用宝 tassistant/apk"
check "/data/media/0/Android/data/com.coolapk.market/files/Download/a.apk"                       0 "酷安下载"
check "/data/media/0/Android/data/com.baidu.searchbox/files/downloads/a.zip"                     0 "百度 App 下载"
check "/data/media/0/Android/data/com.android.chrome/files/Download/a.apk"                       0 "Chrome 下载"
check "/data/media/0/123云盘/a.apk"            0 "123云盘"
check "/data/media/0/BaiduNetdisk/a.zip"       0 "百度网盘"
check "/data/media/0/AliYunPan/a.zip"          0 "阿里云盘"
check "/data/media/0/微云保存的文件/a.pdf"     0 "微云"
check "/data/media/0/Quark/Download/a.apk"     0 "夸克"
check "/data/media/0/UCDownloads/a.apk"        0 "UC 下载"
check "/data/media/0/UCTurbo/Download/a.apk"   0 "UC 极速版"
check "/data/media/0/QQBrowser/a.apk"          0 "QQ 浏览器"
check "/data/media/0/Android/data/org.telegram.messenger/cache/a.apk"  1 "Telegram cache 不归类"
check "/data/media/0/Android/data/com.example/files/Telegram/a.apk"    1 "非 Telegram Files 子目录"

echo "— 相机 / 相册 / 录屏永不归类 —"
check "/data/media/0/DCIM/Camera/a.jpg"                 1 "DCIM/Camera"
check "/data/media/0/Pictures/Screenshots/a.png"        1 "Pictures"
check "/data/media/0/Movies/a.mp4"                      1 "Movies"
check "/data/media/0/MIUI/ScreenRecorder/a.mp4"         1 "录屏"

echo "— 内部存储根目录直接放的文件 —"
check "/data/media/0/random.jpg"   0 "根目录文件允许"
check "/data/media/10/random.jpg"  0 "工作资料根目录文件允许"

echo "— Android 目录 —"
check "/data/media/0/Android/obb/com.x/a.jpg"  1 "obb 不允许"
check "/data/media/0/Android/a.jpg"            1 "Android 下的散文件不允许"

echo
if [ "$fail" -eq 0 ]; then echo "全部通过"; else echo "$fail 项失败"; exit 1; fi
