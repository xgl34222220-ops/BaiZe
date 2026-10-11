#!/usr/bin/env bash
# 3.0.1 回归：定时文件归类不得搬走仍在下载/写入中的文件。
# 真机反馈「刚下载的视频没法播放，相册里也放不了」：下载器直接写最终文件名，
# 归类在写入中途把文件搬进 BaiZe归类，原下载继续写已不存在的路径，结果截断。
# 三道闸：最近 N 分钟修改过 / 仍被任何进程打开 / 移动前大小与修改时间变化。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
SH="$ROOT/v2/module/scripts/organizer-worker.sh"
KT="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/FileOrganizerEngine.kt"
GATE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/DownloadStabilityGate.kt"
T=${TMPDIR:-/tmp}/baize-organizer-stability
fail=0
say() { echo "  [FAIL] $1"; fail=$((fail + 1)); }

setup() {
  rm -rf "$T"
  mkdir -p "$T/module" "$T/media/0/Download" "$T/state" "$T/proc/4242/fd" "$T/proc/5151/fd" "$T/bin"
  cp "$SH" "$T/module/organizer-worker.sh"
  printf '#!/bin/sh\nexit 1\n' >"$T/module/storage-index.sh"
  chmod 0755 "$T/module/storage-index.sh" "$T/module/organizer-worker.sh"
  printf 'enabled=1\norganizer_conflict_policy=1\norganizer_undo_retention=3\norganizer_media_scan=1\n' >"$T/state/config.conf"
}
old() { touch -d "@$(( $(date +%s) - ${2:-3600} ))" "$1"; }
run() {
  env -u BAIZE_ORGANIZER_MIN_AGE_SECONDS \
    BAIZE_STATE_DIR="$T/state" BAIZE_MEDIA_ROOT="$T/media" BAIZE_PROC_ROOT="$T/proc" \
    BAIZE_ORGANIZER_CATEGORIES="$ROOT/config/organizer-categories.conf" \
    BAIZE_SHELL_BIN=/usr/bin/busybox "$@" \
    ${RUN_SHELL:-busybox ash} "$T/module/organizer-worker.sh" organize "${TRIGGER:-app}" stability >/dev/null 2>&1 || true
}
result() { sed -n "s/^$1=//p" "$T/state/organizer-result.env"; }

echo "下载稳定性三道闸"

# 1) 默认 10 分钟：刚写入的视频不动，一小时前的视频照常归类。
setup
printf 'downloading' >"$T/media/0/Download/fresh.mp4"
printf 'finished' >"$T/media/0/Download/done.mp4"; old "$T/media/0/Download/done.mp4"
run
[ -f "$T/media/0/Download/fresh.mp4" ] || say "10 分钟内修改过的视频被搬走了"
[ -f "$T/media/0/BaiZe归类/视频/done.mp4" ] || say "早已下载完成的视频没有被归类"
[ "$(result skippedRecent)" = 1 ] || say "skippedRecent 应为 1，实际 $(result skippedRecent)"
[ "$(result minAgeMinutes)" = 10 ] || say "默认年龄门槛应为 10 分钟"
grep -qa 'Download/done.mp4' "$T/state/organizer-media-scan.nul" 2>/dev/null || say "移动后未登记旧路径媒体刷新"
grep -qa 'BaiZe归类/视频/done.mp4' "$T/state/organizer-media-scan.nul" 2>/dev/null || say "移动后未登记新路径媒体刷新"
ls "$T/state/organizer-undo/"*.json >/dev/null 2>&1 || say "移动后缺少撤销记录"

# 2) 定时触发同样生效（用户开启的就是定时文件归类）。
setup
printf 'downloading' >"$T/media/0/Download/sched.mkv"
TRIGGER=scheduler:interval run
[ -f "$T/media/0/Download/sched.mkv" ] || say "定时归类搬走了刚写入的文件"

# 3) 配置 organizer_min_age_minutes：1 分钟门槛下两分钟前的文件可以归类；越界值被夹到 1..1440。
setup
echo 'organizer_min_age_minutes=1' >>"$T/state/config.conf"
printf 'x' >"$T/media/0/Download/two-min.pdf"; old "$T/media/0/Download/two-min.pdf" 120
run
[ -f "$T/media/0/BaiZe归类/文档/two-min.pdf" ] || say "1 分钟门槛下两分钟前的文件未归类"
setup
echo 'organizer_min_age_minutes=0' >>"$T/state/config.conf"
printf 'x' >"$T/media/0/Download/zero.pdf"; old "$T/media/0/Download/zero.pdf" 20
run
[ -f "$T/media/0/Download/zero.pdf" ] || say "门槛 0 应被夹到 1 分钟，20 秒前的文件不该移动"
[ "$(result minAgeMinutes)" = 1 ] || say "门槛下限应夹到 1"
setup
echo 'organizer_min_age_minutes=99999' >>"$T/state/config.conf"
run
[ "$(result minAgeMinutes)" = 1440 ] || [ ! -f "$T/state/organizer-result.env" ] || say "门槛上限应夹到 1440"

# 4) 仍被进程打开（FUSE 视图 /storage/emulated、/mnt/user 与下层 /data/media 三种写法）。
setup
for n in a b c d; do printf 'x' >"$T/media/0/Download/busy-$n.mp4"; old "$T/media/0/Download/busy-$n.mp4"; done
printf 'x' >"$T/media/0/Download/idle.mp4"; old "$T/media/0/Download/idle.mp4"
ln -s "/storage/emulated/0/Download/busy-a.mp4" "$T/proc/4242/fd/7"
ln -s "/mnt/user/0/emulated/0/Download/busy-b.mp4" "$T/proc/4242/fd/8"
ln -s "/mnt/pass_through/0/emulated/0/Download/busy-c.mp4" "$T/proc/5151/fd/3"
ln -s "/data/media/0/Download/busy-d.mp4" "$T/proc/5151/fd/4"
ln -s "socket:[1234]" "$T/proc/5151/fd/5"
run
for n in a b c d; do
  [ -f "$T/media/0/Download/busy-$n.mp4" ] || say "被进程打开的 busy-$n.mp4 被搬走了"
done
[ -f "$T/media/0/BaiZe归类/视频/idle.mp4" ] || say "没有被打开的旧文件应正常归类"
[ "$(result skippedBusy)" = 4 ] || say "skippedBusy 应为 4，实际 $(result skippedBusy)"

# 5) 移动前复核：发现后到移动前大小/修改时间变了（用假 stat 模拟写入继续），必须跳过。
setup
printf 'x' >"$T/media/0/Download/growing.mp4"; old "$T/media/0/Download/growing.mp4"
REAL_STAT=$(command -v stat)
cat >"$T/bin/stat" <<SH
#!/bin/sh
if [ "\$2" = '%s %Y' ]; then echo "999999 1"; exit 0; fi
exec "$REAL_STAT" "\$@"
SH
chmod 0755 "$T/bin/stat"
# busybox ash 优先使用内置 stat，PATH 注入不生效；这一项改用 bash 运行同一脚本。
RUN_SHELL=bash run PATH="$T/bin:$PATH"
[ -f "$T/media/0/Download/growing.mp4" ] || say "移动前大小变化的文件被搬走了"
[ "$(result skippedChanged)" = 1 ] || say "skippedChanged 应为 1，实际 $(result skippedChanged)"
[ ! -e "$T/media/0/BaiZe归类/视频/growing.mp4" ] || say "移动前复核失败仍产生了目标文件"

# 6) 打开文件集合每轮只建一次，不能每个文件扫一遍 /proc。
[ "$(grep -c '^build_open_file_set$' "$SH")" = 1 ] || say "build_open_file_set 应在主循环前只调用一次"
awk '/^while IFS= read -r -d .. FILE_PATH/{inloop=1} inloop && /\/proc/{bad=1} /^done$/{inloop=0} END{exit bad}' "$SH" || say "主循环内不得直接扫描 /proc"

# 7) App 侧同样三道闸。
[ -f "$GATE" ] || say "缺少 App 侧 DownloadStabilityGate.kt"
grep -q 'DownloadStabilityGate' "$KT" || say "FileOrganizerEngine 未接入下载稳定性闸"
grep -q 'organizer_min_age_minutes' "$KT" || say "FileOrganizerEngine 未读取 organizer_min_age_minutes"
grep -q 'organizer_min_age_minutes' "$ROOT/config/default.conf" || say "default.conf 缺少 organizer_min_age_minutes"
grep -q '"organizer_min_age_minutes" to 1..1440' "$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRepository.kt" || say "SchedulerRepository 未校验 organizer_min_age_minutes 范围"

rm -rf "$T"
if [ "$fail" -eq 0 ]; then echo "下载稳定性三道闸：ok"; else exit 1; fi
