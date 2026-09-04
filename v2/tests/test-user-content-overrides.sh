#!/usr/bin/env bash
# 回归合同：明确承载用户内容的顶层目录必须在模块内置保护表里保持 critical。
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
OVERRIDES="$ROOT/config/risk-overrides.conf"
[ -f "$OVERRIDES" ] || { echo "缺少 $OVERRIDES" >&2; exit 1; }

MUST_BE_CRITICAL='
/storage/emulated/0/Bluetooth
/storage/emulated/0/bluetooth
/storage/emulated/0/TWRP
/storage/emulated/0/SHRP
/storage/emulated/0/AppBackup
/storage/emulated/0/SwiftBackup
/storage/emulated/0/LSpeedBackup
/storage/emulated/0/GCam
/storage/emulated/0/Gcam
/storage/emulated/0/SGCAM
/storage/emulated/0/DJI
/storage/emulated/0/Snapseed
/storage/emulated/0/TouchRetouch
/storage/emulated/0/PicsArt
/storage/emulated/0/CamScanner
/storage/emulated/0/Camscanner
/storage/emulated/0/Kingsoftoffice
/storage/emulated/0/PDFExtra
/storage/emulated/0/GstarCAD
/storage/emulated/0/AnkiDroid
/storage/emulated/0/DaysMatter
/storage/emulated/0/ImportantDays
/storage/emulated/0/PocketBook
/storage/emulated/0/BlueDict
/storage/emulated/0/EhViewer
/storage/emulated/0/RetroArch
/storage/emulated/0/J2ME-Loader
/storage/emulated/0/DraStic
/storage/emulated/0/MyBoy
/storage/emulated/0/PSP
/storage/emulated/0/EKA2L1
/storage/emulated/0/PonyEmu
/storage/emulated/0/ExaGear
/storage/emulated/0/StardewValley
/storage/emulated/0/SurvivalCraft2.3
/storage/emulated/0/BombSquad
/storage/emulated/0/AIDE
/storage/emulated/0/AndLua
/storage/emulated/0/AndroLua
/storage/emulated/0/AndroidIDEProjects
/storage/emulated/0/AppProjects
/storage/emulated/0/Scripts
/storage/emulated/0/ApkEditor
/storage/emulated/0/Apktool_M
/storage/emulated/0/MT2
/storage/emulated/0/APKExport
/storage/emulated/0/Fonts
/storage/emulated/0/fonts
/storage/emulated/0/font
/storage/emulated/0/Sounds
/storage/emulated/0/Notifications
/storage/emulated/0/Subtitles
/storage/emulated/0/ScreenRecorder
/storage/emulated/0/Screenrecorder
/storage/emulated/0/Qq_screenshot
/storage/emulated/0/Qqscreenshot
/storage/emulated/0/Recovered
/storage/emulated/0/MIUI
/storage/emulated/0/.$Trash$
/storage/emulated/0/.360ExplorerRecycleBin
/storage/emulated/0/.ColorOSGalleryRecycler
/storage/emulated/0/.File_Recycle
/storage/emulated/0/.RecycleBinHW
/storage/emulated/0/.vivoFileRecycleBin
/storage/emulated/0/.vivoRecycleBin
/storage/emulated/0/vivoFileRecycleBin
/storage/emulated/0/vivoRecycleBin
/storage/emulated/0/Android/.Trash
/storage/emulated/0/导出的文件
/storage/emulated/0/微云保存的文件
/storage/emulated/0/一键另存
/storage/emulated/0/享做笔记
/storage/emulated/0/云上写作
/storage/emulated/0/一木记账
/storage/emulated/0/WakeUp课程表
/storage/emulated/0/棋谱
/storage/emulated/0/歌曲
/storage/emulated/0/八门神器相册
/storage/emulated/0/测量员
/storage/emulated/0/测量员导出文件
/storage/emulated/0/河南省房屋市政调查
/storage/emulated/0/方舟脚本
/storage/emulated/0/脚本
/storage/emulated/0/存储空间清理备份
'

fail=0
checked=0
while IFS= read -r path; do
  [ -n "$path" ] || continue
  checked=$((checked + 1))
  if ! grep -qxF "$path|critical" "$OVERRIDES"; then
    echo "  [FAIL] 内置保护缺少或未标为 critical：$path"
    fail=$((fail + 1))
  fi
done <<< "$MUST_BE_CRITICAL"

dups=$(grep '^/' "$OVERRIDES" | sed 's/|.*//' | sort | uniq -d || true)
if [ -n "$dups" ]; then
  echo "  [FAIL] 内置风险保护表存在重复路径："
  printf '    %s\n' $dups
  fail=$((fail + 1))
fi

echo "$checked 项断言，$fail 项失败"
[ "$fail" -eq 0 ] || exit 1
echo "用户内容内置保护检查通过"
