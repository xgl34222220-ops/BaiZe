#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
T=${TMPDIR:-/tmp}/baize-deep-clean-stream-test
rm -rf "$T"
mkdir -p "$T/module/config" "$T/state" "$T/media/0/Android/data/com.example/cache/nested" "$T/bin"
cp "$ROOT/module/profile-snapshot-clean-fast.sh" "$T/module/profile-cleaner.sh"
chmod +x "$T/module/profile-cleaner.sh"
printf '%s\n' "$T/media/0/Android/data/com.example/cache" >"$T/module/config/deep.rules"
: >"$T/state/whitelist.conf"
cat >"$T/state/config.conf" <<'CONF'
deep_clean_batch_files=2
CONF

write_snapshot() {
  target=$1
  files=$2
  bytes=$3
  printf '%s\tlow\n' "$target" >"$T/state/deep_scan.targets"
  cat >"$T/state/deep_scan.env" <<EOF
snapshot_id=test-$(date +%s)
epoch=$(date +%s)
targets_sha=$(sha256sum "$T/state/deep_scan.targets" | awk '{print $1}')
whitelist_sha=$(sha256sum "$T/state/whitelist.conf" | awk '{print $1}')
rules_sha=$(sha256sum "$T/module/config/deep.rules" | awk '{print $1}')
allow_high_risk=0
max_file_bytes=268435456
files=$files
bytes=$bytes
EOF
}

run_clean() {
  BAIZE_STATE_DIR="$T/state" BAIZE_MEDIA_ROOT="$T/media" \
    BAIZE_DATA_ROOT="$T/data" BAIZE_DEEP_RULES="$T/module/config/deep.rules" \
    BAIZE_SHELL_BIN=bash bash "$T/module/profile-cleaner.sh" deep-clean test
}

TARGET="$T/media/0/Android/data/com.example/cache"
printf old >"$TARGET/old.bin"
printf nested >"$TARGET/nested/old-nested.bin"
touch -d '5 minutes ago' "$TARGET/old.bin" "$TARGET/nested/old-nested.bin"
write_snapshot "$TARGET" 2 9
printf new >"$TARGET/new-after-scan.bin"
touch -d '5 minutes' "$TARGET/new-after-scan.bin"

run_clean >/dev/null

[ ! -e "$TARGET/old.bin" ]
[ ! -e "$TARGET/nested/old-nested.bin" ]
[ -e "$TARGET/new-after-scan.bin" ]
[ ! -d "$TARGET/nested" ]
grep -q '^engine=profile-snapshot-v42.8-stream-batch$' "$T/state/latest.env"
grep -q '^deep_truncated=0$' "$T/state/latest.env"
grep -q '^deep_slow_items=0$' "$T/state/latest.env"
grep -q '^deep_remaining_targets=0$' "$T/state/latest.env"
grep -q '^deep_stopped=0$' "$T/state/latest.env"
grep -q '^deep_clean_batch_files=32$' "$T/state/latest.env"
[ ! -e "$T/state/deep_scan.env" ]
[ ! -e "$T/state/deep_scan.targets" ]

# A slow traversal is still completed in the same task; it must not be skipped.
rm -rf "$TARGET"
mkdir -p "$TARGET/slow-nested"
for index in $(seq 1 70); do
  printf 'payload-%s' "$index" >"$TARGET/slow-nested/file-$index.bin"
  touch -d '5 minutes ago' "$TARGET/slow-nested/file-$index.bin"
done
cat >"$T/state/config.conf" <<'CONF'
deep_clean_batch_files=32
CONF
write_snapshot "$TARGET" 70 700
REAL_FIND=$(command -v find)
cat >"$T/bin/find" <<'SH'
#!/usr/bin/env bash
case "$*" in *slow-nested*) sleep 2 ;; esac
exec "$REAL_FIND" "$@"
SH
chmod +x "$T/bin/find"
export REAL_FIND
started=$(date +%s)
PATH="$T/bin:$PATH" run_clean >/dev/null
elapsed=$(( $(date +%s) - started ))
[ "$elapsed" -ge 2 ]
[ ! -d "$TARGET/slow-nested" ]
grep -q '^deep_remaining_targets=0$' "$T/state/latest.env"
grep -q '^deep_stopped=0$' "$T/state/latest.env"
! grep -q '慢目标\|目录超时\|跳过.*慢' "$T/state/latest.env"

# Stopping keeps the immutable snapshot so the user can explicitly resume later.
rm -rf "$TARGET"
mkdir -p "$TARGET/stop-case"
for index in $(seq 1 120); do
  printf 'payload-%s' "$index" >"$TARGET/stop-case/file-$index.bin"
  touch -d '5 minutes ago' "$TARGET/stop-case/file-$index.bin"
done
write_snapshot "$TARGET" 120 1200
REAL_RM=$(command -v rm)
cat >"$T/bin/rm" <<'SH'
#!/usr/bin/env bash
case "$*" in *stop-case*) sleep 0.05 ;; esac
exec "$REAL_RM" "$@"
SH
chmod +x "$T/bin/rm"
export REAL_RM
set +e
PATH="$T/bin:$PATH" run_clean >/tmp/baize-deep-stop.log 2>&1 &
clean_pid=$!
sleep 1
: >"$T/state/stop"
wait "$clean_pid"
stop_code=$?
set -e
[ "$stop_code" -eq 9 ]
[ -f "$T/state/deep_scan.env" ]
[ -f "$T/state/deep_scan.targets" ]
grep -q '^deep_stopped=1$' "$T/state/latest.env"
grep -q '进度已保留' "$T/state/latest.env"
remaining=$(find "$TARGET/stop-case" -type f | wc -l | tr -d ' ')
[ "$remaining" -gt 0 ]

echo 'deep clean continuous stream batches: ok'
