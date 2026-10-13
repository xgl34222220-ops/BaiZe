#!/usr/bin/env bash
# 3.3.4 regression (3.3.3 device report): rules/deep/cache tasks re-ran about every
# minute for hours. A run that finished with some items left in place exited 8 (the
# "launch failure" code), so the scheduler never stamped last_<group>_run and retried
# it with a 2–32 s backoff forever. Exit 10 = finished with item failures (cycle done);
# real failures get a bounded number of retries per cycle, then wait for the interval.
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=${TMPDIR:-/tmp}/baize-334-scheduler-partial
rm -rf "$T"; mkdir -p "$T/module/config" "$T/state/scheduler-requests" "$T/state/scheduler-skips"
cp "$ROOT/v2/module/scripts/scheduler.sh" "$T/module/scheduler.sh"
cat > "$T/state/config.conf" <<'CONF'
enabled=1
schedule_mode=1
autopilot_enabled=0
screen_off_only=0
charging_only=0
device_idle_only=0
min_battery=0
daily_schedule_enabled=0
schedule_cache_enabled=0
clean_apk_packages=0
schedule_empty_enabled=0
schedule_rules_enabled=1
schedule_rules_minutes=360
schedule_fragment_enabled=0
schedule_deep_enabled=0
schedule_organize_enabled=0
CONF
worker_exit() {
  cat > "$T/module/task-worker.sh" <<SH
#!/bin/sh
printf '%s\t%s\n' "\$1" "\$2" >>"\${BAIZE_STATE_DIR}/executed.tsv"
exit $1
SH
  chmod +x "$T/module/task-worker.sh"
}
run_once() {
  BAIZE_MODULE_DIR="$T/module" BAIZE_STATE_DIR="$T/state" BAIZE_CONFIG_PATH="$T/state/config.conf" \
  BAIZE_SKIP_BOOT_WAIT=1 BAIZE_SCHEDULER_ONCE=1 sh "$T/module/scheduler.sh"
}
runs() { [ -f "$T/state/executed.tsv" ] && wc -l <"$T/state/executed.tsv" | tr -d ' ' || echo 0; }

# 1) Partial completion (exit 10) completes the cycle: stamped, no retry, not re-run.
worker_exit 10
now=$(date +%s)
printf '%s\n' $((now - 7 * 3600)) >"$T/state/last_rules_run.epoch"
run_once
[ "$(runs)" -eq 1 ]
stamp=$(sed -n '1p' "$T/state/last_rules_run.epoch")
[ "$stamp" -ge "$now" ]
[ ! -e "$T/state/scheduler-retry-rules.until" ]
[ ! -e "$T/state/scheduler-retry-rules.count" ]
grep -q '^state=completed$' "$T/state/scheduler.env"
grep -q '部分项目未清理' "$T/state/scheduler.env"
for _ in 1 2 3; do run_once; done
[ "$(runs)" -eq 1 ]   # next run waits for the 6-hour interval

# 2) A real failure (exit 8) retries a bounded number of times, then waits for the interval.
: >"$T/state/executed.tsv"
worker_exit 8
printf '%s\n' $((now - 7 * 3600)) >"$T/state/last_rules_run.epoch"
for _ in 1 2 3 4 5 6 7 8; do
  rm -f "$T/state/scheduler-retry-rules.until"   # let every backoff expire immediately
  run_once
done
[ "$(runs)" -eq 4 ]   # first attempt + 3 retries, never more in one cycle
[ ! -e "$T/state/scheduler-retry-rules.count" ]
deferred=$(sed -n '1p' "$T/state/scheduler-deferred-rules.until")
[ "$deferred" -ge $((now + 360 * 60 - 5)) ]
grep -rq '已顺延到下一周期' "$T/state/auto-run-ledger"
! grep -rEq '连续失败|熔断|暂停|failed|paused' "$T/state/auto-run-ledger"
grep -q 'retries=3 next=interval' "$T/state/logs/scheduler-rules.log"
# last_run is untouched: the task did not complete, it was moved to the next interval.
[ "$(sed -n '1p' "$T/state/last_rules_run.epoch")" -lt "$now" ]

# 3) The real worker chain stamps completion for exit 10 too.
W="$T/worker"
mkdir -p "$W/module/config" "$W/state/task-results" "$W/state/logs"
cp "$ROOT/v2/module/scripts/task-worker.sh" "$ROOT/v2/module/scripts/worker-runner.sh" "$W/module/"
cp "$ROOT/config/operation-lock.sh" "$W/module/config/"
printf '#!/bin/sh\nexit 10\n' >"$W/module/cleaner.sh"
chmod +x "$W/module/"*.sh
set +e
BAIZE_STATE_DIR="$W/state" BAIZE_SHELL_BIN=/bin/sh timeout 8 sh "$W/module/task-worker.sh" rules-clean scheduler:interval partial wait
code=$?
set -e
[ "$code" -eq 10 ]
grep -q '^exit_code=10$' "$W/state/task-results/partial.env"
test -s "$W/state/last_rules_run.epoch"
[ ! -e "$W/state/worker.env" ]

# Contract: the cleaners use 10 for "finished, some items left" and the ledger explains it.
grep -q 'exit 10' "$ROOT/v2/module/scripts/cleaner-compat.sh"
grep -q '10 -> "执行结束，部分项目未清理' "$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRunLedger.kt"
echo 'scheduler partial completion and bounded retries: ok'
