#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
T=${TMPDIR:-/tmp}/baize-deep-clean-budget-test
rm -rf "$T"
mkdir -p "$T/module/config" "$T/state" "$T/media/0/Android/data/com.example/cache/nested" "$T/bin"
cp "$ROOT/module/profile-snapshot-clean-fast.sh" "$T/module/profile-cleaner.sh"
chmod +x "$T/module/profile-cleaner.sh"
printf '%s\n' "$T/media/0/Android/data/com.example/cache" >"$T/module/config/deep.rules"
: >"$T/state/whitelist.conf"
cat >"$T/state/config.conf" <<'CONF'
deep_clean_target_timeout_seconds=10
deep_clean_stage_limit_seconds=30
CONF

REAL_FIND=$(command -v find)
cat >"$T/bin/find" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$FIND_LOG"
case "$*" in *slow-cache*) sleep 7 ;; esac
exec "$REAL_FIND" "$@"
SH
chmod +x "$T/bin/find"
export REAL_FIND FIND_LOG="$T/find.log"

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

TARGET="$T/media/0/Android/data/com.example/cache"
printf old >"$TARGET/old.bin"
printf nested >"$TARGET/nested/old-nested.bin"
touch -d '5 minutes ago' "$TARGET/old.bin" "$TARGET/nested/old-nested.bin"
write_snapshot "$TARGET" 2 9
printf new >"$TARGET/new-after-scan.bin"
touch -d '5 minutes' "$TARGET/new-after-scan.bin"

PATH="$T/bin:$PATH" BAIZE_STATE_DIR="$T/state" BAIZE_MEDIA_ROOT="$T/media" \
  BAIZE_DATA_ROOT="$T/data" BAIZE_DEEP_RULES="$T/module/config/deep.rules" \
  bash "$T/module/profile-cleaner.sh" deep-clean test >/dev/null

[ ! -e "$TARGET/old.bin" ]
[ ! -e "$TARGET/nested/old-nested.bin" ]
[ -e "$TARGET/new-after-scan.bin" ]
[ ! -d "$TARGET/nested" ]
find_calls=$(wc -l <"$T/find.log" | tr -d ' ')
[ "$find_calls" -le 2 ]
grep -q '^engine=profile-snapshot-v42.7-budgeted$' "$T/state/latest.env"
grep -q '^deep_truncated=0$' "$T/state/latest.env"

rm -f "$T/find.log" "$T/state/latest.env"
rm -rf "$T/state/run.lock"
SLOW="$T/media/0/Android/data/com.example/slow-cache"
mkdir -p "$SLOW"
printf keep >"$SLOW/slow.bin"
touch -d '5 minutes ago' "$SLOW/slow.bin"
cat >"$T/state/config.conf" <<'CONF'
deep_clean_target_timeout_seconds=5
deep_clean_stage_limit_seconds=30
CONF
write_snapshot "$SLOW" 1 4
started=$(date +%s)
PATH="$T/bin:$PATH" BAIZE_STATE_DIR="$T/state" BAIZE_MEDIA_ROOT="$T/media" \
  BAIZE_DATA_ROOT="$T/data" BAIZE_DEEP_RULES="$T/module/config/deep.rules" \
  bash "$T/module/profile-cleaner.sh" deep-clean test >/dev/null
elapsed=$(( $(date +%s) - started ))
[ "$elapsed" -lt 10 ]
[ -e "$SLOW/slow.bin" ]
grep -q '^deep_slow_items=1$' "$T/state/latest.env"
grep -q '^deep_truncated=0$' "$T/state/latest.env"
grep -q '跳过 1 个慢目标' "$T/state/latest.env"

echo 'deep clean single-pass budget: ok'
