#!/system/bin/sh
# Shared by APK foreground services and module workers. The inode is never removed.
# Scheduled compatible lanes share this gate; foreground mutations own it exclusively.
baize_acquire_operation_lock() {
  baize_lock_root=$1
  baize_lock_mode=$2
  mkdir -p "$baize_lock_root" || return 4
  # mksh marks script-opened descriptors close-on-exec. Each provider must
  # explicitly inherit this descriptor; a successful lock then remains in this shell.
  exec 8>>"$baize_lock_root/operations.lock" || return 4
  case "$baize_lock_mode" in shared) baize_lock_flag=-s;; exclusive) baize_lock_flag=-x;; *) return 4;; esac
  baize_lock_busy=0
  BAIZE_OPERATION_LOCK_ERROR="没有可用的 flock 实现"
  if command -v flock >/dev/null 2>&1; then baize_try_operation_lock flock && return 0; fi
  if [ -x /system/bin/flock ]; then baize_try_operation_lock /system/bin/flock && return 0; fi
  if command -v toybox >/dev/null 2>&1; then baize_try_operation_lock toybox flock && return 0; fi
  if [ -x /system/bin/toybox ]; then baize_try_operation_lock /system/bin/toybox flock && return 0; fi
  if command -v busybox >/dev/null 2>&1; then baize_try_operation_lock busybox flock && return 0; fi
  for baize_lock_busybox in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
    [ -x "$baize_lock_busybox" ] && baize_try_operation_lock "$baize_lock_busybox" flock && return 0
  done
  exec 8>&-
  [ "$baize_lock_busy" = 0 ] || return 3
  return 4
}

# First validate this provider and FD without acquiring a lock. Unsupported applets
# and EBADF must report a tool failure, never masquerade as another running task.
baize_try_operation_lock() {
  baize_lock_error=$("$@" -u 8 8>&8 2>&1)
  if [ "$?" -ne 0 ]; then
    [ -z "$baize_lock_error" ] || BAIZE_OPERATION_LOCK_ERROR=$baize_lock_error
    return 1
  fi
  baize_lock_error=$("$@" "$baize_lock_flag" -n 8 8>&8 2>&1)
  baize_lock_code=$?
  [ "$baize_lock_code" -ne 0 ] || return 0
  case "$baize_lock_error" in
    ''|*'Resource temporarily unavailable'*|*'would block'*)
      [ "$baize_lock_code" -ne 1 ] || baize_lock_busy=1 ;;
    *) BAIZE_OPERATION_LOCK_ERROR=$baize_lock_error ;;
  esac
  return 1
}

# The App uses the same source via sh -c. EOF releases the kernel lock even if its
# owning RootService dies. Module scripts only source the function above.
if [ "${1:-}" = --hold ]; then
  baize_acquire_operation_lock "$2" "$3"
  baize_status=$?
  case "$baize_status" in
    0) ;;
    3) echo BUSY; exit 3 ;;
    *) printf 'ERROR: %s\n' "${BAIZE_OPERATION_LOCK_ERROR:-无法打开任务锁}"; exit 4 ;;
  esac
  echo READY
  IFS= read -r baize_release || true
  exec 8>&-
fi
