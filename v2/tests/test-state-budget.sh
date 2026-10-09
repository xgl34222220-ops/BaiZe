#!/usr/bin/env bash
# /data/adb/baize-v2 文件数上限回归：init 每次开机都会逐文件 restorecon /data，
# 状态目录里每一种“按时间/任务生成名字”的文件都必须有上限，长期日志必须单文件轮转。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
SCRIPTS="$ROOT/v2/module/scripts"
T=$(mktemp -d "${TMPDIR:-/tmp}/baize-budget.XXXXXX")
trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

shells=(sh)
command -v busybox >/dev/null 2>&1 && shells+=("busybox ash")

seed() {
  S=$1
  rm -rf "$S"; mkdir -p "$S/reports" "$S/logs" "$S/task-results"
  echo keep >"$S/config.conf"; echo keep >"$S/whitelist.conf"; echo keep >"$S/history.tsv"
  echo keep >"$S/reports/latest.tsv"; echo keep >"$S/reports/apps-latest.tsv"; echo keep >"$S/logs/latest.log"
  # Simulate a year of daily runs of every task kind (≈ the shape that caused 1.29M files).
  for i in $(seq 1 400); do
    n=$(printf '%03d' "$i")
    : >"$S/reports/2026-01-01_00-00-$n-cache-clean.tsv"
    : >"$S/reports/2025-12-31_00-00-$n-rules-clean.tsv"
    : >"$S/reports/cache-lane-task$n-summary.tsv"
    : >"$S/logs/2026-01-01_00-00-$n-cache-clean.log"
    : >"$S/logs/organizer-task$n.log"
    : >"$S/logs/worker-task$n.log"
    : >"$S/logs/cache-lane-task$n-run.log"
    : >"$S/task-results/task$n.env"
  done
  # Newest files must survive: give a known file the latest mtime.
  touch -d '2030-01-01' "$S/reports/2026-01-01_00-00-400-cache-clean.tsv" "$S/logs/2026-01-01_00-00-400-cache-clean.log" 2>/dev/null || true
  head -c 1048576 /dev/zero | tr '\0' 'a' >"$S/logs/autopilot.log"
  head -c 1048576 /dev/zero | tr '\0' 'b' >"$S/logs/supervisor-scheduler.log"
  echo small >"$S/logs/state-migrate.log"
}
count() { find "$1" -mindepth 1 -maxdepth 1 -type f -name "$2" | wc -l | tr -d ' '; }

for SH in "${shells[@]}"; do
  S="$T/state"
  seed "$S"
  before=$(find "$S" | wc -l)
  inode=$(ls -i "$S/logs/autopilot.log" | awk '{print $1}')
  # shellcheck disable=SC2086
  $SH -c ". '$SCRIPTS/state-retention.sh'; baize_enforce_state_budget '$S'"
  [ "$(count "$S/reports" '20*-*.tsv')" -le 40 ] || fail "$SH reports cap"
  [ "$(count "$S/reports" 'cache-lane-*')" -le 40 ] || fail "$SH lane reports cap"
  [ "$(count "$S/logs" '20*-*.log')" -le 40 ] || fail "$SH run logs cap"
  [ "$(count "$S/logs" 'organizer-*.log')" -le 20 ] || fail "$SH organizer logs cap"
  [ "$(count "$S/logs" 'worker-*.log')" -le 200 ] || fail "$SH worker logs cap"
  [ "$(count "$S/logs" 'cache-lane-*')" -le 200 ] || fail "$SH lane logs cap"
  [ "$(count "$S/task-results" '*.env')" -le 200 ] || fail "$SH task results cap"
  for keep in config.conf whitelist.conf history.tsv reports/latest.tsv reports/apps-latest.tsv logs/latest.log logs/state-migrate.log; do
    [ -f "$S/$keep" ] || fail "$SH removed $keep"
  done
  if touch -d '2030-01-01' "$T/probe" 2>/dev/null; then
    [ -f "$S/reports/2026-01-01_00-00-400-cache-clean.tsv" ] || fail "$SH newest report pruned"
  fi
  size=$(wc -c <"$S/logs/autopilot.log" | tr -d ' ')
  [ "$size" -le 262144 ] || fail "$SH autopilot.log not rotated ($size)"
  [ "$size" -gt 0 ] || fail "$SH rotation lost the tail"
  [ "$(ls -i "$S/logs/autopilot.log" | awk '{print $1}')" = "$inode" ] || fail "$SH rotation must keep the inode for >> writers"
  [ ! -e "$S/logs/autopilot.log.1" ] || fail "$SH rotation must not add files"
  after=$(find "$S" | wc -l)
  [ "$after" -le 800 ] || fail "$SH total entries $after (from $before)"
  [ "$after" -lt "$before" ] || fail "$SH nothing pruned"

  # Hourly audit CLI: records a count in one fixed file; repeated runs never add files.
  # shellcheck disable=SC2086
  $SH "$SCRIPTS/state-retention.sh" budget "$S"
  grep -q '^over_budget=0$' "$S/state-budget.env" || fail "$SH audit under budget"
  stable=$(find "$S" | wc -l)
  for _ in 1 2 3; do
    # shellcheck disable=SC2086
    $SH "$SCRIPTS/state-retention.sh" budget "$S"
  done
  [ "$(find "$S" | wc -l)" = "$stable" ] || fail "$SH audit must be idempotent"
  for _ in $(seq 1 40); do
    # shellcheck disable=SC2086
    BAIZE_STATE_FILE_BUDGET=10 $SH "$SCRIPTS/state-retention.sh" budget "$S"
  done
  grep -q '^over_budget=1$' "$S/state-budget.env" || fail "$SH over-budget flag"
  [ "$(wc -l <"$S/logs/state-budget.log")" -le 30 ] || fail "$SH over-budget log cap"
  # Sourcing must not trigger the CLI.
  # shellcheck disable=SC2086
  $SH -c ". '$SCRIPTS/state-retention.sh'; echo sourced" | grep -qx sourced || fail "$SH sourcing ran the CLI"
done

# Every long-lived caller enforces the budget.
grep -q 'baize_enforce_state_budget "$STATE_DIR"' "$SCRIPTS/worker-runner.sh" || fail "worker-runner must enforce budget"
grep -q 'baize_enforce_state_budget "$STATE_DIR"' "$SCRIPTS/state-migrate.sh" || fail "boot migration must enforce budget"
grep -q 'state-retention.sh" budget "$STATE_DIR"' "$SCRIPTS/supervisor.sh" || fail "supervisor hourly audit"
echo "state budget passed"
