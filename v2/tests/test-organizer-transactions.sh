#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
ORGANIZER=${BAIZE_ORGANIZER_SOURCE:-$ROOT/v2/module/organizer-worker.sh}
INDEXER=${BAIZE_INDEXER_SOURCE:-$ROOT/v2/module/storage-index.sh}
run_case() {
  policy=$1; T=${TMPDIR:-/tmp}/baize-v241-organizer-$policy
  rm -rf "$T"; mkdir -p "$T/module" "$T/media/0/Download" "$T/media/0/BaiZe归类/文档" "$T/state"
  cp "$ORGANIZER" "$T/module/organizer-worker.sh"
  cp "$INDEXER" "$T/module/storage-index.sh"
  chmod +x "$T/module/organizer-worker.sh" "$T/module/storage-index.sh"
  printf old > "$T/media/0/BaiZe归类/文档/same.pdf"
  if [ "$policy" = 2 ]; then printf old > "$T/media/0/Download/same.pdf"; else printf new > "$T/media/0/Download/same.pdf"; fi
  cat > "$T/state/config.conf" <<CONF
enabled=1
shared_index_ttl_seconds=30
organizer_conflict_policy=$policy
organizer_undo_retention=2
organizer_media_scan=0
max_file_mb=256
CONF
  BAIZE_STATE_DIR="$T/state" BAIZE_MEDIA_ROOT="$T/media" BAIZE_CONFIG_PATH="$T/state/config.conf" BAIZE_SHELL_BIN=/usr/bin/busybox \
    busybox ash "$T/module/organizer-worker.sh" organize ci "case-$policy"
  if [ "$policy" = 1 ]; then
    test -f "$T/media/0/BaiZe归类/文档/same (1).pdf"
    grep -q '^renamed=1$' "$T/state/organizer-result.env"
    test -s "$T/state/organizer-last.json"
  else
    test -f "$T/media/0/Download/same.pdf"
    grep -q '^deduplicated=1$' "$T/state/organizer-result.env"
    grep -q '^moved=0$' "$T/state/organizer-result.env"
  fi
}
run_case 1
run_case 2

# One destination that became invalid must not make a successful batch a hard scheduler failure.
T=${TMPDIR:-/tmp}/baize-v241-organizer-partial
rm -rf "$T"; mkdir -p "$T/module" "$T/media/0/Download" "$T/media/0/BaiZe归类/文档" "$T/state"
cp "$ORGANIZER" "$T/module/organizer-worker.sh"
cp "$INDEXER" "$T/module/storage-index.sh"
chmod +x "$T/module/organizer-worker.sh" "$T/module/storage-index.sh"
printf good > "$T/media/0/Download/good.pdf"
printf fail > "$T/media/0/Download/fail.jpg"
printf blocked > "$T/media/0/BaiZe归类/图片"
cat > "$T/state/config.conf" <<'CONF'
enabled=1
shared_index_ttl_seconds=30
organizer_conflict_policy=1
organizer_undo_retention=2
organizer_media_scan=0
max_file_mb=256
CONF
set +e
BAIZE_STATE_DIR="$T/state" BAIZE_MEDIA_ROOT="$T/media" BAIZE_CONFIG_PATH="$T/state/config.conf" BAIZE_SHELL_BIN=/usr/bin/busybox \
  busybox ash "$T/module/organizer-worker.sh" organize ci partial
code=$?
set -e
[ "$code" = 10 ]
test -f "$T/media/0/BaiZe归类/文档/good.pdf"
test -f "$T/media/0/Download/fail.jpg"
grep -q '^success=1$' "$T/state/organizer-result.env"
grep -q '^moved=1$' "$T/state/organizer-result.env"
grep -q '^failed=1$' "$T/state/organizer-result.env"

echo 'organizer transactions and partial warning: ok'
