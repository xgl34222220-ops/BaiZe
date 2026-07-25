#!/system/bin/sh
set -u

APP_ID=${BAIZE_APP_ID:-io.github.xgl34222220.baize}
ADB_ROOT=${BAIZE_ADB_ROOT:-/data/adb}
MODULES_DIR=${BAIZE_MODULES_DIR:-$ADB_ROOT/modules}
MODULES_UPDATE_DIR=${BAIZE_MODULES_UPDATE_DIR:-$ADB_ROOT/modules_update}
STATE_DIR=${BAIZE_STATE_DIR:-$ADB_ROOT/baize-v2}
LEGACY_STATE_DIR=${BAIZE_LEGACY_STATE_DIR:-$ADB_ROOT/safesweep}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
RECOVERY_ROOT=${BAIZE_RECOVERY_ROOT:-$MEDIA_ROOT/0/Download/BaiZe恢复}
WAIT_SECONDS=${BAIZE_UNINSTALL_WAIT_SECONDS:-1}
MOD_ID=baize_v2
LEGACY_MOD_ID=safesweep
SELF_DIR=${0%/*}
SELF_PID=$$
PARENT_PID=${PPID:-0}

case "$WAIT_SECONDS" in ''|*[!0-9]*) WAIT_SECONDS=1 ;; esac
[ "$WAIT_SECONDS" -gt 5 ] && WAIT_SECONDS=5

safe_owned_path() {
  case "$1" in
    "$STATE_DIR"|"$LEGACY_STATE_DIR"|\
    "$MODULES_UPDATE_DIR/$MOD_ID"|"$MODULES_UPDATE_DIR/$LEGACY_MOD_ID"|\
    "$MODULES_DIR/$LEGACY_MOD_ID") return 0 ;;
  esac
  return 1
}

remove_owned_path() {
  target=$1
  safe_owned_path "$target" || {
    echo "拒绝删除非白泽目录：$target" >&2
    return 1
  }
  rm -rf -- "$target" 2>/dev/null || true
}

process_matches() {
  cmdline=$1
  case "$cmdline" in
    *"$MODULES_DIR/$MOD_ID/"*|*"$MODULES_UPDATE_DIR/$MOD_ID/"*|*"$STATE_DIR/"*|\
    *"$MODULES_DIR/$LEGACY_MOD_ID/"*|*"$MODULES_UPDATE_DIR/$LEGACY_MOD_ID/"*|*"$LEGACY_STATE_DIR/"*) return 0 ;;
  esac
  return 1
}

signal_owned_processes() {
  signal=$1
  for proc_dir in /proc/[0-9]*; do
    [ -d "$proc_dir" ] || continue
    pid=${proc_dir##*/}
    [ "$pid" = "$SELF_PID" ] && continue
    [ "$pid" = "$PARENT_PID" ] && continue
    [ -r "$proc_dir/cmdline" ] || continue
    cmdline=$(tr '\000' ' ' <"$proc_dir/cmdline" 2>/dev/null || true)
    [ -n "$cmdline" ] || continue
    if process_matches "$cmdline"; then
      kill "-$signal" "$pid" 2>/dev/null || true
    fi
  done
}

unique_recovery_path() {
  base=$1
  if [ ! -e "$base" ]; then
    printf '%s\n' "$base"
    return 0
  fi
  n=1
  while [ "$n" -le 999 ]; do
    candidate="$base ($n)"
    if [ ! -e "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
    n=$((n + 1))
  done
  return 1
}

restore_quarantine() {
  quarantine="$STATE_DIR/quarantine"
  index="$quarantine/index.tsv"
  [ -f "$index" ] || return 0

  failed=0
  tab=$(printf '\t')
  while IFS="$tab" read -r id epoch size original; do
    [ -n "${id:-}" ] || continue
    source="$quarantine/files/$id"
    [ -f "$source" ] || continue

    case "$original" in
      "$MEDIA_ROOT"/[0-9]/*|/storage/*|/sdcard/*) ;;
      *) original= ;;
    esac

    restored=0
    if [ -n "$original" ] && [ ! -e "$original" ]; then
      if mkdir -p "${original%/*}" 2>/dev/null && mv "$source" "$original" 2>/dev/null; then
        restored=1
      fi
    fi

    if [ "$restored" -eq 0 ]; then
      mkdir -p "$RECOVERY_ROOT" 2>/dev/null || true
      name=${original##*/}
      [ -n "$name" ] || name=$id
      fallback=$(unique_recovery_path "$RECOVERY_ROOT/$name" 2>/dev/null || true)
      if [ -n "$fallback" ] && mv "$source" "$fallback" 2>/dev/null; then
        restored=1
      fi
    fi

    [ "$restored" -eq 1 ] || failed=1
  done <"$index"

  return "$failed"
}

if [ -d "$STATE_DIR" ]; then
  touch "$STATE_DIR/stop" "$STATE_DIR/supervisor.stop" 2>/dev/null || true
fi

signal_owned_processes TERM
[ "$WAIT_SECONDS" -eq 0 ] || sleep "$WAIT_SECONDS"
signal_owned_processes KILL

QUARANTINE_RESTORED=1
if ! restore_quarantine; then
  QUARANTINE_RESTORED=0
  echo "部分隔离文件无法恢复，已保留隔离目录" >&2
fi

if command -v pm >/dev/null 2>&1; then
  pm uninstall "$APP_ID" >/dev/null 2>&1 || true
  pm uninstall --user 0 "$APP_ID" >/dev/null 2>&1 || true
fi

if [ "$QUARANTINE_RESTORED" -eq 1 ]; then
  remove_owned_path "$STATE_DIR"
else
  RECOVERY_STATE="$ADB_ROOT/baize-v2-quarantine-recovery"
  rm -rf -- "$RECOVERY_STATE" 2>/dev/null || true
  mv "$STATE_DIR/quarantine" "$RECOVERY_STATE" 2>/dev/null || true
  remove_owned_path "$STATE_DIR"
fi
remove_owned_path "$LEGACY_STATE_DIR"
remove_owned_path "$MODULES_UPDATE_DIR/$MOD_ID"
remove_owned_path "$MODULES_UPDATE_DIR/$LEGACY_MOD_ID"
remove_owned_path "$MODULES_DIR/$LEGACY_MOD_ID"

[ "$WAIT_SECONDS" -eq 0 ] || sleep "$WAIT_SECONDS"
signal_owned_processes KILL
remove_owned_path "$STATE_DIR"
remove_owned_path "$LEGACY_STATE_DIR"
remove_owned_path "$MODULES_UPDATE_DIR/$MOD_ID"
remove_owned_path "$MODULES_UPDATE_DIR/$LEGACY_MOD_ID"

case "$SELF_DIR" in
  "$MODULES_DIR/$MOD_ID") touch "$SELF_DIR/remove" 2>/dev/null || true ;;
esac

sync 2>/dev/null || true

echo "白泽后台进程、App、Root 状态和旧版目录已清理"
case "$SELF_DIR" in
  "$MODULES_DIR/$MOD_ID") echo "模块本体目录将在重启后由 Root 管理器移除" ;;
esac
[ "$QUARANTINE_RESTORED" -eq 1 ] || echo "未能恢复的隔离文件保存在 $ADB_ROOT/baize-v2-quarantine-recovery"
