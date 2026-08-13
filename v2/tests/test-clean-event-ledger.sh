#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
STATE=$(mktemp -d)
trap 'rm -rf "$STATE"' EXIT

for n in $(seq 1 12); do
  BAIZE_ROOT_STATE_DIR="$STATE" BAIZE_EVENT_COMPACT_AT=7 BAIZE_EVENT_RETAIN_IDS=20 \
    sh "$ROOT/module/record-clean-event.sh" "event-$n" test 100 2 1 1 1 3 test &
done
wait

grep -q '^runs=12$' "$STATE/totals.env"
grep -q '^bytes=1200$' "$STATE/totals.env"
grep -q '^regular_files=24$' "$STATE/totals.env"
grep -q '^empty_files=12$' "$STATE/totals.env"
grep -q '^empty_dirs=12$' "$STATE/totals.env"
grep -q '^fragment_files=12$' "$STATE/totals.env"
grep -q '^elapsed=36$' "$STATE/totals.env"

# Replaying an event after compaction remains idempotent.
BAIZE_ROOT_STATE_DIR="$STATE" sh "$ROOT/module/record-clean-event.sh" event-3 test 999 9 9 9 9 9 test
grep -q '^runs=12$' "$STATE/totals.env"
grep -q '^bytes=1200$' "$STATE/totals.env"
echo 'clean event ledger concurrency and idempotency: ok'
