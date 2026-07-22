#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=${TMPDIR:-/tmp}/baize-v240-index-test
rm -rf "$T"; mkdir -p "$T/media/0/Download" "$T/media/0/Android/data/com.example.browser/private/received" "$T/media/0/Android/data/com.example.browser/cache/Download" "$T/state"
printf apk > "$T/media/0/Download/a.apk"
printf zip > "$T/media/0/Android/data/com.example.browser/private/received/b.zip"
printf bad > "$T/media/0/Android/data/com.example.browser/cache/Download/must-not-index.zip"
cat > "$T/state/config.conf" <<'CONF'
shared_index_ttl_seconds=30
max_file_mb=1
CONF
run_index() { BAIZE_STATE_DIR="$T/state" BAIZE_MEDIA_ROOT="$T/media" BAIZE_CONFIG_PATH="$T/state/config.conf" BAIZE_INDEX_TTL_SECONDS=30 busybox ash "$ROOT/v2/module/storage-index.sh" "$1" test; }
run_index refresh
grep -q '^roots_scanned=' "$T/state/index/meta.env"
tr '\0' '\n' < "$T/state/index/storage-files.nul" | grep -q '/a.apk$'
tr '\0' '\n' < "$T/state/index/storage-files.nul" | grep -q '/b.zip$'
! tr '\0' '\n' < "$T/state/index/storage-files.nul" | grep -q 'must-not-index'
run_index incremental
grep -q '^roots_scanned=0$' "$T/state/index/meta.env"
grep -Eq '^roots_reused=[1-9]' "$T/state/index/meta.env"
sleep 1
printf pdf > "$T/media/0/Download/new.pdf"
run_index incremental
tr '\0' '\n' < "$T/state/index/organizer-files.nul" | grep -q '/new.pdf$'
grep -Eq '^roots_scanned=[1-9]' "$T/state/index/meta.env"
# Same inode reachable through overlapping roots is emitted once.
count=$(tr '\0' '\n' < "$T/state/index/storage-files.nul" | grep -c '/a.apk$')
[ "$count" -eq 1 ]
echo 'incremental index: ok'
