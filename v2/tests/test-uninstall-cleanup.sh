#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=${TMPDIR:-/tmp}/baize-uninstall-cleanup-test
rm -rf "$T"
mkdir -p \
  "$T/adb/modules/baize_v2" \
  "$T/adb/modules/safesweep" \
  "$T/adb/modules_update/baize_v2" \
  "$T/adb/modules_update/safesweep" \
  "$T/adb/baize-v2/quarantine/files" \
  "$T/adb/safesweep" \
  "$T/media/0/Download" \
  "$T/bin" \
  "$T/not-owned"

cp "$ROOT/v2/module/uninstall.sh" "$T/adb/modules/baize_v2/uninstall.sh"
chmod 0755 "$T/adb/modules/baize_v2/uninstall.sh"
printf 'legacy\n' >"$T/adb/safesweep/config.conf"
printf 'new-state\n' >"$T/adb/baize-v2/config.conf"
printf 'staged\n' >"$T/adb/modules_update/baize_v2/module.prop"
printf 'staged-old\n' >"$T/adb/modules_update/safesweep/module.prop"
printf 'old-module\n' >"$T/adb/modules/safesweep/module.prop"
printf 'keep\n' >"$T/not-owned/file"
printf 'quarantined-payload\n' >"$T/adb/baize-v2/quarantine/files/q1"
printf 'q1\t0\t20\t%s\n' "$T/media/0/Download/restored.txt" >"$T/adb/baize-v2/quarantine/index.tsv"

cat >"$T/bin/pm" <<'SH'
#!/usr/bin/env sh
printf '%s\n' "$*" >>"$BAIZE_PM_LOG"
exit 0
SH
chmod 0755 "$T/bin/pm"

# Both processes deliberately expose BaiZe-owned paths in cmdline. One recreates
# the state directory when TERM is received, reproducing the original residue.
busybox ash -c 'while :; do sleep 1; done' \
  "$T/adb/modules/baize_v2/worker-runner.sh" &
worker_pid=$!
busybox ash -c 'trap '\''mkdir -p "$1"; printf recreated >"$1/recreated"'\'' TERM; while :; do sleep 1; done' \
  "$T/adb/modules/baize_v2/supervisor.sh" "$T/adb/baize-v2" &
recreator_pid=$!

cleanup() {
  kill -KILL "$worker_pid" "$recreator_pid" 2>/dev/null || true
  rm -rf "$T"
}
trap cleanup EXIT
sleep 1

PATH="$T/bin:$PATH" \
BAIZE_PM_LOG="$T/pm.log" \
BAIZE_ADB_ROOT="$T/adb" \
BAIZE_MEDIA_ROOT="$T/media" \
BAIZE_RECOVERY_ROOT="$T/media/0/Download/BaiZe恢复" \
BAIZE_UNINSTALL_WAIT_SECONDS=1 \
busybox ash "$T/adb/modules/baize_v2/uninstall.sh"

# The uninstall script must survive its own process matching and reach cleanup.
[ -f "$T/adb/modules/baize_v2/remove" ]
[ -f "$T/adb/modules/baize_v2/uninstall.sh" ]

# Root manager removes the active module directory on reboot; everything else
# owned by BaiZe must already be gone before that reboot.
[ ! -e "$T/adb/baize-v2" ]
[ ! -e "$T/adb/safesweep" ]
[ ! -e "$T/adb/modules_update/baize_v2" ]
[ ! -e "$T/adb/modules_update/safesweep" ]
[ ! -e "$T/adb/modules/safesweep" ]
[ -f "$T/not-owned/file" ]

# Quarantined user data must be restored instead of being deleted with state.
grep -qx 'quarantined-payload' "$T/media/0/Download/restored.txt"
[ ! -e "$T/adb/baize-v2-quarantine-recovery" ]

# Both normal and user-0 package uninstall paths are attempted.
grep -qx 'uninstall io.github.xgl34222220.baize' "$T/pm.log"
grep -qx 'uninstall --user 0 io.github.xgl34222220.baize' "$T/pm.log"

# Workers that previously recreated state must be dead, and the second cleanup
# pass must have removed anything created by their TERM trap.
! kill -0 "$worker_pid" 2>/dev/null
! kill -0 "$recreator_pid" 2>/dev/null
[ ! -e "$T/adb/baize-v2" ]

# Prevent the original self-termination bug from returning.
! grep -Eq 'pkill[[:space:]].*-f.*modules/baize_v2' "$ROOT/v2/module/uninstall.sh"
grep -q 'pid" = "$SELF_PID' "$ROOT/v2/module/uninstall.sh"
grep -q 'signal_owned_processes TERM' "$ROOT/v2/module/uninstall.sh"
grep -q 'remove_owned_path "$MODULES_UPDATE_DIR/$MOD_ID"' "$ROOT/v2/module/uninstall.sh"

echo 'complete uninstall cleanup: ok'
