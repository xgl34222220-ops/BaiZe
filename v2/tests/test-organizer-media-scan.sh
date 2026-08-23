#!/usr/bin/env bash
# 媒体刷新必须是 durable pending/inflight 事务，不能回退到逐文件 am 或 Activity 直读 /data/adb。
set -uo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
SH="$ROOT/v2/module/organizer-worker.sh"
KT="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/FileOrganizerEngine.kt"
QUEUE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/RootMediaScanQueue.kt"
SERVICE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
ACT="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/FileOrganizerActivity.kt"
fail=0
say() { echo "  [FAIL] $1"; fail=$((fail + 1)); }
code_only() { grep -vE '^\s*(#|\*|/\*|//)' "$1"; }

echo "媒体刷新队列安全约束"

code_only "$SH" | grep -q 'am broadcast' && say "shell 又恢复了逐文件 am broadcast"
code_only "$KT" | grep -q '"/system/bin/am"' && say "Kotlin 又恢复了 ProcessBuilder am"
code_only "$KT" | grep -q 'MEDIA_SCANNER_SCAN_FILE' && say "Kotlin 又使用废弃广播"
grep -q 'RootMediaScanQueue.enqueue' "$KT" || say "FileOrganizerEngine 未持久化到 root 队列"

grep -q 'MediaScannerConnection.scanFile' "$QUEUE" || say "Root 队列未使用 MediaScannerConnection"
grep -q 'PENDING_NAME = "organizer-media-scan.nul"' "$QUEUE" || say "pending 文件名不一致"
grep -q 'INFLIGHT_NAME = "organizer-media-scan.inflight.nul"' "$QUEUE" || say "缺少 inflight 事务文件"
grep -q 'pending.renameTo(inflight)' "$QUEUE" || say "未原子 claim pending -> inflight"
grep -q 'recoverInflightLocked' "$QUEUE" || say "进程失败后不能恢复 inflight"
grep -q 'recoverSpoolsLocked' "$QUEUE" || say "不能恢复 shell/Kotlin spool"
grep -q 'CALLBACK_TIMEOUT_MS' "$QUEUE" || say "缺少 callback 超时保护"
# 删除 inflight 只能发生在 finish/空文件路径，不能在 scanFile 之前先删 pending。
scan_line=$(grep -n 'MediaScannerConnection.scanFile' "$QUEUE" | head -n1 | cut -d: -f1)
delete_line=$(grep -n 'acknowledged = !inflight.exists() || inflight.delete()' "$QUEUE" | head -n1 | cut -d: -f1)
case "$scan_line" in ''|*[!0-9]*) say "无法定位 scanFile" ;; esac
case "$delete_line" in ''|*[!0-9]*) say "无法定位 inflight ack" ;; esac
if [ -n "$scan_line" ] && [ -n "$delete_line" ]; then
  [ "$delete_line" -gt "$scan_line" ] || say "inflight 在提交媒体扫描前就被删除"
fi
grep -q 'MAX_PATHS' "$QUEUE" && say "不得用截断读取后删除整队列的 MAX_PATHS 方案"

grep -q 'organizer-media-scan.lock' "$SH" || say "shell 未使用共享短锁"
grep -q 'organizer-media-scan.spool.' "$SH" || say "shell 锁忙时没有 spool 兜底"
grep -q '>>"$MEDIA_SCAN_PENDING"' "$SH" || say "shell 不是 append pending"
code_only "$SH" | grep -q 'chmod 0644.*organizer-media-scan' && say "root 队列不应再开放给普通 Activity"

grep -q 'RootMediaScanQueue.onServiceStart' "$SERVICE" || say "RootService 启动时不恢复后台队列"
[ "$(grep -c 'RootMediaScanQueue.flush' "$SERVICE")" -ge 3 ] || say "归类/撤销/后台状态没有覆盖 flush 入口"
grep -q 'MediaScanQueue' "$ACT" && say "Activity 不应直接消费 /data/adb 队列"

# NUL 分隔必须保留空格/中文路径。
T=${TMPDIR:-/tmp}/baize-media-q; rm -rf "$T"; mkdir -p "$T"
printf '/sd/0/我的 文件/a.jpg\0/sd/10/b c.mp4\0' > "$T/q.nul"
n=0
while IFS= read -r -d '' p; do n=$((n+1)); case "$p" in */*) ;; *) say "路径被切断：$p" ;; esac; done < "$T/q.nul"
[ "$n" = 2 ] || say "NUL 队列应读回 2 条路径，实际 $n"

if [ "$fail" -eq 0 ]; then echo "媒体刷新 durable queue：ok"; else exit 1; fi
