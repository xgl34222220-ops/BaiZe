#!/usr/bin/env bash
# 存储维护（F2FS GC + TRIM）：只在充电 + 息屏 + 开机稳定后运行，每日最多一次，
# gc_urgent 一定恢复，结果只写固定的两个文件且日志行数有上限。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
SCRIPTS="$ROOT/v2/module/scripts"
T=$(mktemp -d "${TMPDIR:-/tmp}/baize-maint.XXXXXX")
trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

mkdir -p "$T/bin" "$T/module/scripts"
cp "$SCRIPTS/storage-maintenance.sh" "$SCRIPTS/state-retention.sh" "$T/module/scripts/"
cat >"$T/bin/dumpsys" <<'EOF'
#!/bin/sh
case "$1" in
  battery) if [ "$(cat "$FAKE/charging")" = 1 ]; then printf '  AC powered: true\n  status: 2\n  level: 80\n'; else printf '  AC powered: false\n  USB powered: false\n  status: 3\n  level: 80\n'; fi ;;
  power) if [ "$(cat "$FAKE/screen")" = off ]; then echo 'Display Power: state=OFF'; else echo 'Display Power: state=ON'; fi ;;
esac
EOF
cat >"$T/bin/fstrim" <<'EOF'
#!/bin/sh
echo "/data: 1073741824 bytes trimmed"
echo "$*" >>"$FAKE/fstrim.calls"
EOF
cat >"$T/bin/renice" <<'EOF'
#!/bin/sh
exit 0
EOF
cp "$T/bin/renice" "$T/bin/ionice"
chmod 0755 "$T/bin/"*

shells=(sh)
command -v busybox >/dev/null 2>&1 && shells+=("busybox ash")

reset_case() {
  rm -rf "$T/state" "$T/fake"
  mkdir -p "$T/state/logs" "$T/fake/f2fs/dm-5"
  echo 1 >"$T/fake/charging"; echo off >"$T/fake/screen"
  echo 0 >"$T/fake/f2fs/dm-5/gc_urgent"; echo 50 >"$T/fake/f2fs/dm-5/dirty_segments"
  echo '/dev/block/dm-5 /data f2fs rw,lazytime 0 0' >"$T/fake/mounts"
  echo '5000.12 100.00' >"$T/fake/uptime"
  printf 'maintenance_enabled=1\nmaintenance_min_hours=24\nmaintenance_gc_max_seconds=30\n' >"$T/state/config.conf"
}
run_maint() {
  # shellcheck disable=SC2086
  PATH="$T/bin:$PATH" FAKE="$T/fake" BAIZE_STATE_DIR="$T/state" BAIZE_MODULE_DIR="$T/module" \
    BAIZE_MAINT_F2FS_SYSFS="$T/fake/f2fs" BAIZE_MAINT_MOUNTS="$T/fake/mounts" \
    BAIZE_MAINT_UPTIME_FILE="$T/fake/uptime" BAIZE_MAINT_POLL_SECONDS=1 BAIZE_MAINT_FSTRIM="$T/bin/fstrim" \
    $SHELL_UNDER_TEST "$T/module/scripts/storage-maintenance.sh" "${1:-check}"
}
value() { sed -n "s/^$1=//p" "$T/state/maintenance.env" | tail -n 1; }

for SHELL_UNDER_TEST in "${shells[@]}"; do
  # A: charging + screen off + settled → trim + GC, gc_urgent restored, compact result.
  reset_case
  run_maint
  [ "$(value last_result)" = ok ] || { cat "$T/state/maintenance.env"; fail "$SHELL_UNDER_TEST A result"; }
  [ "$(value gc)" = completed ] || fail "$SHELL_UNDER_TEST A gc"
  case "$(value trim)" in fstrim:*) ;; *) fail "$SHELL_UNDER_TEST A trim $(value trim)";; esac
  [ "$(cat "$T/fake/f2fs/dm-5/gc_urgent")" = 0 ] || fail "$SHELL_UNDER_TEST A gc_urgent not restored"
  [ "$(wc -l <"$T/state/logs/maintenance.log")" = 1 ] || fail "$SHELL_UNDER_TEST A log line"
  [ ! -e "$T/state/maintenance.lock" ] || fail "$SHELL_UNDER_TEST A lock left behind"
  [ "$(find "$T/state" -type f | wc -l)" -le 3 ] || { find "$T/state"; fail "$SHELL_UNDER_TEST A extra files"; }

  # B: rate limit — a second check the same day does nothing (no dumpsys, no trim).
  run_maint
  [ "$(wc -l <"$T/fake/fstrim.calls")" = 1 ] || fail "$SHELL_UNDER_TEST B rate limit"
  [ "$(wc -l <"$T/state/logs/maintenance.log")" = 1 ] || fail "$SHELL_UNDER_TEST B log"

  # C/D/E/F/G: screen on, unplugged, early boot, cleaning task, disabled → untouched.
  for gate in screen charge uptime lock disabled; do
    reset_case
    case "$gate" in
      screen) echo on >"$T/fake/screen" ;;
      charge) echo 0 >"$T/fake/charging" ;;
      uptime) echo '120.00 1.00' >"$T/fake/uptime" ;;
      lock) mkdir "$T/state/run.lock" ;;
      disabled) echo maintenance_enabled=0 >>"$T/state/config.conf" ;;
    esac
    run_maint
    [ ! -e "$T/state/maintenance.env" ] || fail "$SHELL_UNDER_TEST gate $gate must skip"
    [ ! -e "$T/fake/fstrim.calls" ] || fail "$SHELL_UNDER_TEST gate $gate trimmed"
  done

  # H: dirty segments stay high and the screen turns on → GC stops, value restored.
  reset_case
  echo 90000 >"$T/fake/f2fs/dm-5/dirty_segments"
  run_maint &
  pid=$!
  for _ in $(seq 1 50); do [ "$(cat "$T/fake/f2fs/dm-5/gc_urgent")" = 1 ] && break; sleep 0.1; done
  [ "$(cat "$T/fake/f2fs/dm-5/gc_urgent")" = 1 ] || fail "$SHELL_UNDER_TEST H gc never started"
  echo on >"$T/fake/screen"
  wait "$pid"
  [ "$(value gc)" = interrupted-screen-on ] || fail "$SHELL_UNDER_TEST H gc=$(value gc)"
  [ "$(value last_result)" = interrupted ] || fail "$SHELL_UNDER_TEST H result"
  [ "$(cat "$T/fake/f2fs/dm-5/gc_urgent")" = 0 ] || fail "$SHELL_UNDER_TEST H not restored"

  # I: a killed run (gc_urgent left at 1) is repaired by the next check, even when not due.
  reset_case
  echo 1 >"$T/fake/f2fs/dm-5/gc_urgent"
  printf 'schema=maintenance-v1\nlast_attempt_epoch=%s\nlast_result=running\ngc_node=%s\ngc_original=0\n' \
    "$(date +%s)" "$T/fake/f2fs/dm-5" >"$T/state/maintenance.env"
  run_maint
  [ "$(cat "$T/fake/f2fs/dm-5/gc_urgent")" = 0 ] || fail "$SHELL_UNDER_TEST I crash recovery"
  [ "$(value last_result)" = killed ] || fail "$SHELL_UNDER_TEST I marker"
  [ ! -e "$T/fake/fstrim.calls" ] || fail "$SHELL_UNDER_TEST I must still honour rate limit"

  # J: non-F2FS /data → trim only, no sysfs writes; log never grows past 30 lines.
  reset_case
  echo '/dev/block/dm-5 /data ext4 rw 0 0' >"$T/fake/mounts"
  for i in $(seq 1 80); do echo "old $i" >>"$T/state/logs/maintenance.log"; done
  run_maint
  [ "$(value gc)" = not-f2fs ] || fail "$SHELL_UNDER_TEST J gc"
  [ "$(value last_result)" = trim-only ] || fail "$SHELL_UNDER_TEST J result"
  [ "$(wc -l <"$T/state/logs/maintenance.log")" -le 30 ] || fail "$SHELL_UNDER_TEST J log cap"
  tail -n 1 "$T/state/logs/maintenance.log" | grep -q 'result=trim-only' || fail "$SHELL_UNDER_TEST J newest line kept"

  # K: 分类定时清理（默认关闭）——只有开关打开且仍在维护窗口内才调用 cleaner.sh category-clean。
  printf '#!/bin/sh\necho "$*" >>"$FAKE/cleaner.calls"\n' >"$T/module/scripts/cleaner.sh"
  reset_case
  run_maint
  [ ! -e "$T/fake/cleaner.calls" ] || fail "$SHELL_UNDER_TEST K default off must not clean"
  reset_case
  echo maint_clean_wechat=1 >>"$T/state/config.conf"
  run_maint
  [ "$(cat "$T/fake/cleaner.calls" 2>/dev/null)" = "category-clean maintenance" ] || fail "$SHELL_UNDER_TEST K category not run"
  run_maint
  [ "$(wc -l <"$T/fake/cleaner.calls")" = 1 ] || fail "$SHELL_UNDER_TEST K must honour daily rate limit"
  for gate in screen lock disabled; do
    reset_case
    echo maint_clean_logcat=1 >>"$T/state/config.conf"
    case "$gate" in
      screen) echo on >"$T/fake/screen" ;;
      lock) mkdir "$T/state/run.lock" ;;
      disabled) echo maintenance_enabled=0 >>"$T/state/config.conf" ;;
    esac
    run_maint
    [ ! -e "$T/fake/cleaner.calls" ] || fail "$SHELL_UNDER_TEST K gate $gate must skip category cleanup"
  done
  rm -f "$T/module/scripts/cleaner.sh"
done

# Supervisor wiring: only a cheap periodic gate, always backgrounded.
S="$SCRIPTS/supervisor.sh"
grep -q 'storage-maintenance.sh" check' "$S" || fail "supervisor must call the maintenance gate"
grep -A1 'storage-maintenance.sh" check' "$S" | grep -q '& maint_pid=\$!' || fail "maintenance must run in background"
grep -q '^maintenance_min_hours=24$' "$ROOT/config/default.conf" || fail "default once per day"
echo "storage maintenance passed"
