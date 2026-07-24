#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
BIN=${TMPDIR:-/tmp}/baize-deep-snapshot-test
HOST_ROOT="/data/media/baize-deep-manifest-test-$$"
WORK=${TMPDIR:-/tmp}/baize-deep-manifest-state-$$

cleanup() {
  rm -rf "$WORK" "$BIN"
  sudo rm -rf "$HOST_ROOT" 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$WORK"
sudo mkdir -p "$HOST_ROOT"
sudo chown -R "$(id -u):$(id -g)" "$HOST_ROOT"

gcc -std=c11 -O2 -Wall -Wextra -Werror "$ROOT/native/baize_deep_snapshot.c" -o "$BIN"

TARGET="$HOST_ROOT/cache"
mkdir -p "$TARGET/nested"
printf 'old-a' >"$TARGET/old-a.bin"
printf 'old-b' >"$TARGET/nested/old-b.bin"
printf 'change-me' >"$TARGET/changed.bin"
printf '%s\tlow\n' "$TARGET" >"$WORK/targets.tsv"
: >"$WORK/whitelist.conf"

"$BIN" build \
  --targets "$WORK/targets.tsv" \
  --manifest "$WORK/manifest0" \
  --summary "$WORK/build.env" \
  --progress "$WORK/progress.env" \
  --stop "$WORK/stop" \
  --max-file-bytes 1048576

grep -q '^engine=deep-manifest-v1$' "$WORK/build.env"
records=$(sed -n 's/^records=//p' "$WORK/build.env")
files=$(sed -n 's/^files=//p' "$WORK/build.env")
dirs=$(sed -n 's/^dirs=//p' "$WORK/build.env")
[ "$records" -eq 5 ]
[ "$files" -eq 3 ]
[ "$dirs" -eq 2 ]
[ -s "$WORK/manifest0" ]

# A stop request must preserve cursor 0 and every snapshotted file.
printf 'stop\n' >"$WORK/stop"
printf '0\n' >"$WORK/cursor"
set +e
"$BIN" clean \
  --manifest "$WORK/manifest0" \
  --cursor "$WORK/cursor" \
  --report "$WORK/stopped.tsv" \
  --summary "$WORK/stopped.env" \
  --whitelist "$WORK/whitelist.conf" \
  --progress "$WORK/progress.env" \
  --stop "$WORK/stop" \
  --max-file-bytes 1048576
stopped_code=$?
set -e
[ "$stopped_code" -eq 9 ]
[ "$(cat "$WORK/cursor")" -eq 0 ]
[ -e "$TARGET/old-a.bin" ]
[ -e "$TARGET/nested/old-b.bin" ]

# Files changed or created after the snapshot must survive. Unchanged records are consumed directly
# from the manifest, and the post-order directory record removes only the now-empty old directory.
rm -f "$WORK/stop"
sleep 1
printf 'changed-after-scan' >"$TARGET/changed.bin"
printf 'new-after-scan' >"$TARGET/new.bin"
mkdir -p "$TARGET/new-empty-dir"
"$BIN" clean \
  --manifest "$WORK/manifest0" \
  --cursor "$WORK/cursor" \
  --report "$WORK/clean.tsv" \
  --summary "$WORK/clean.env" \
  --whitelist "$WORK/whitelist.conf" \
  --progress "$WORK/progress.env" \
  --stop "$WORK/stop" \
  --max-file-bytes 1048576

[ ! -e "$TARGET/old-a.bin" ]
[ ! -e "$TARGET/nested/old-b.bin" ]
[ ! -d "$TARGET/nested" ]
[ -e "$TARGET/changed.bin" ]
[ -e "$TARGET/new.bin" ]
[ -d "$TARGET/new-empty-dir" ]
[ "$(cat "$WORK/cursor")" -eq "$records" ]
grep -q '^remaining=0$' "$WORK/clean.env"
grep -q '^files=2$' "$WORK/clean.env"
grep -q $'^changed\tlow\t' "$WORK/clean.tsv"

# Re-running at the completed cursor is idempotent and never starts a directory discovery pass.
"$BIN" clean \
  --manifest "$WORK/manifest0" \
  --cursor "$WORK/cursor" \
  --report "$WORK/idempotent.tsv" \
  --summary "$WORK/idempotent.env" \
  --whitelist "$WORK/whitelist.conf" \
  --progress "$WORK/progress.env" \
  --stop "$WORK/stop" \
  --max-file-bytes 1048576
grep -q '^processed=0$' "$WORK/idempotent.env"

# A whitelist added after scanning protects the exact manifest record while still advancing safely.
PROTECTED="$HOST_ROOT/protected-cache"
mkdir -p "$PROTECTED"
printf 'protected' >"$PROTECTED/item.bin"
printf '%s\tlow\n' "$PROTECTED" >"$WORK/protected-targets.tsv"
"$BIN" build \
  --targets "$WORK/protected-targets.tsv" \
  --manifest "$WORK/protected.manifest0" \
  --summary "$WORK/protected-build.env" \
  --progress "$WORK/progress.env" \
  --stop "$WORK/stop" \
  --max-file-bytes 1048576
printf '%s\n' "$PROTECTED" >"$WORK/whitelist.conf"
printf '0\n' >"$WORK/protected.cursor"
"$BIN" clean \
  --manifest "$WORK/protected.manifest0" \
  --cursor "$WORK/protected.cursor" \
  --report "$WORK/protected.tsv" \
  --summary "$WORK/protected.env" \
  --whitelist "$WORK/whitelist.conf" \
  --progress "$WORK/progress.env" \
  --stop "$WORK/stop" \
  --max-file-bytes 1048576
[ -e "$PROTECTED/item.bin" ]
grep -q '^files=0$' "$WORK/protected.env"
grep -q $'^protected\tlow\t' "$WORK/protected.tsv"

# Cleanup scripts are manifest consumers; directory rediscovery is forbidden.
! grep -Eq '(^|[[:space:]])find[[:space:]]|xargs[[:space:]]' "$ROOT/module/deep-manifest-clean.sh"
grep -q 'snapshot_schema=deep-file-manifest-v1' "$ROOT/module/deep-scan-manifest.sh"
grep -q 'deep_manifest_cursor' "$ROOT/module/deep-manifest-clean.sh"

echo 'deep immutable manifest and resume cursor: ok'
