#!/system/bin/sh
# Shared by APK foreground services and module workers. The inode is never removed.
# Scheduled compatible lanes share this gate; foreground mutations own it exclusively.
baize_acquire_operation_lock() {
  baize_lock_root=$1
  baize_lock_mode=$2
  mkdir -p "$baize_lock_root" || return 4
  exec 8>>"$baize_lock_root/operations.lock" || return 4
  case "$baize_lock_mode" in shared) baize_lock_flag=-s;; exclusive) baize_lock_flag=-x;; *) return 4;; esac
  if command -v flock >/dev/null 2>&1; then flock "$baize_lock_flag" -n 8 2>/dev/null && return 0; fi
  if [ -x /system/bin/flock ]; then /system/bin/flock "$baize_lock_flag" -n 8 2>/dev/null && return 0; fi
  if command -v toybox >/dev/null 2>&1; then toybox flock "$baize_lock_flag" -n 8 2>/dev/null && return 0; fi
  if [ -x /system/bin/toybox ]; then /system/bin/toybox flock "$baize_lock_flag" -n 8 2>/dev/null && return 0; fi
  if command -v busybox >/dev/null 2>&1; then busybox flock "$baize_lock_flag" -n 8 2>/dev/null && return 0; fi
  for baize_lock_busybox in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
    [ -x "$baize_lock_busybox" ] && "$baize_lock_busybox" flock "$baize_lock_flag" -n 8 2>/dev/null && return 0
  done
  exec 8>&-
  return 3
}

# The App uses the same source via sh -c. EOF releases the kernel lock even if its
# owning RootService dies. Module scripts only source the function above.
if [ "${1:-}" = --hold ]; then
  baize_acquire_operation_lock "$2" "$3" || { echo BUSY; exit 3; }
  echo READY
  IFS= read -r baize_release || true
  exec 8>&-
fi
