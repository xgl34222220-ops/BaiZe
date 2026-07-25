#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=${TMPDIR:-/tmp}/baize-organizer-auto-budget
rm -rf "$T"
mkdir -p "$T/module" "$T/media/0/Download" "$T/media/0/DCIM/Camera" "$T/state"
cp "$ROOT/v2/module/organizer-worker.sh" "$T/module/organizer-worker.sh"
cat >"$T/module/storage-index.sh" <<'SH'
#!/bin/sh
touch "${BAIZE_STATE_DIR}/full-index-was-called"
sleep 10
exit 0
SH
chmod 0755 "$T/module/storage-index.sh" "$T/module/organizer-worker.sh"
printf one >"$T/media/0/Download/one.pdf"
printf two >"$T/media/0/Download/two.pdf"
for i in $(seq 1 200); do printf ignored >"$T/media/0/DCIM/Camera/ignored-$i.jpg"; done
cat >"$T/state/config.conf" <<'CONF'
enabled=1
organizer_conflict_policy=1
organizer_undo_retention=2
organizer_media_scan=1
CONF
start=$(date +%s)
BAIZE_STATE_DIR="$T/state" \
BAIZE_MEDIA_ROOT="$T/media" \
BAIZE_CONFIG_PATH="$T/state/config.conf" \
BAIZE_SHELL_BIN=/usr/bin/busybox \
BAIZE_ORGANIZER_AUTO_MAX_FILES=1 \
BAIZE_ORGANIZER_AUTO_MAX_SECONDS=30 \
busybox ash "$T/module/organizer-worker.sh" organize scheduler:interval auto-budget
elapsed=$(( $(date +%s) - start ))
[ "$elapsed" -lt 8 ] || { echo "automatic organizer took too long: ${elapsed}s" >&2; exit 1; }
[ ! -e "$T/state/full-index-was-called" ] || { echo "automatic organizer incorrectly invoked full shared index" >&2; exit 1; }
grep -q '^success=1$' "$T/state/organizer-result.env"
grep -q '^phase=本轮自动归类已完成，剩余文件下次继续$' "$T/state/organizer-result.env"
grep -q '^moved=1$' "$T/state/organizer-result.env"
[ "$(find "$T/media/0/Download" -maxdepth 1 -type f | wc -l)" -eq 1 ]
[ "$(find "$T/media/0/BaiZe归类/文档" -maxdepth 1 -type f | wc -l)" -eq 1 ]
[ "$(find "$T/media/0/DCIM/Camera" -type f | wc -l)" -eq 200 ]
echo 'automatic organizer budget: ok'
