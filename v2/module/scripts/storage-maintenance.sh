#!/system/bin/sh
# 存储维护：F2FS 垃圾回收 + TRIM（思路参考 CZero / BasicCleaner 的“充电息屏时 GC”）。
#   check  由 Supervisor 周期调用：未到期、未充电、亮屏、开机未稳定或有清理任务时立即退出。
#   run    跳过频率限制（仍要求充电且息屏），供调试使用。
#   status 输出最近一次结果。
# 结果只写两个固定文件：$STATE_DIR/maintenance.env 与 $STATE_DIR/logs/maintenance.log（最多 30 行）。
set -u
MODDIR=${BAIZE_MODULE_DIR:-${0%/*}}
case "$MODDIR" in */scripts) MODDIR=${MODDIR%/scripts} ;; esac
SCRIPTDIR="$MODDIR"
[ ! -d "$MODDIR/scripts" ] || SCRIPTDIR="$MODDIR/scripts"
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
CONFIG=${BAIZE_CONFIG_PATH:-$STATE_DIR/config.conf}
RESULT="$STATE_DIR/maintenance.env"
LOG="$STATE_DIR/logs/maintenance.log"
LOCK="$STATE_DIR/maintenance.lock"
F2FS_SYSFS=${BAIZE_MAINT_F2FS_SYSFS:-/sys/fs/f2fs}
MOUNTS=${BAIZE_MAINT_MOUNTS:-/proc/mounts}
UPTIME_FILE=${BAIZE_MAINT_UPTIME_FILE:-/proc/uptime}
POLL_SECONDS=${BAIZE_MAINT_POLL_SECONDS:-10}
BOOT_SETTLE_SECONDS=${BAIZE_MAINT_BOOT_SETTLE_SECONDS:-900}
LOG_LINES=30
ACTION=${1:-check}

[ -f "$SCRIPTDIR/state-retention.sh" ] && . "$SCRIPTDIR/state-retention.sh"
command -v baize_append_capped >/dev/null 2>&1 || baize_append_capped() { printf '%s\n' "$2" >>"$1"; }

config_value() { sed -n "s/^$1=//p" "$CONFIG" 2>/dev/null | tail -n 1; }
config_uint() {
  cu_value=$(config_value "$1")
  case "$cu_value" in ''|*[!0-9]*) cu_value=$2 ;; esac
  [ "$cu_value" -lt "$3" ] && cu_value=$3
  [ "$cu_value" -gt "$4" ] && cu_value=$4
  echo "$cu_value"
}
result_value() { sed -n "s/^$1=//p" "$RESULT" 2>/dev/null | tail -n 1; }

if [ "$ACTION" = status ]; then cat "$RESULT" 2>/dev/null; exit 0; fi
case "$ACTION" in check|run) ;; *) echo "用法: storage-maintenance.sh check|run|status" >&2; exit 2 ;; esac

[ -d "$STATE_DIR" ] || exit 0

# 0) 崩溃恢复：上一次运行被强杀（gc_urgent 仍为 1）时，按记录恢复原值。
if [ "$(result_value last_result)" = running ]; then
  stale_owner=$(sed -n '1p' "$LOCK/pid" 2>/dev/null)
  case "$stale_owner" in ''|*[!0-9]*) stale_owner=0 ;; esac
  if [ "$stale_owner" -le 1 ] || ! kill -0 "$stale_owner" 2>/dev/null; then
    stale_node=$(result_value gc_node); stale_value=$(result_value gc_original)
    case "$stale_node" in "$F2FS_SYSFS"/*) case "$stale_value" in ''|*[!0-9]*) ;; *)
      [ -w "$stale_node/gc_urgent" ] && printf '%s\n' "$stale_value" >"$stale_node/gc_urgent" 2>/dev/null ;; esac ;; esac
    sed 's/^last_result=running$/last_result=killed/' "$RESULT" >"$RESULT.tmp.$$" 2>/dev/null && mv -f "$RESULT.tmp.$$" "$RESULT"
    rm -rf -- "$LOCK" 2>/dev/null
  fi
fi
[ "$(config_value maintenance_enabled)" != 0 ] || exit 0
MIN_HOURS=$(config_uint maintenance_min_hours 24 24 168)
GC_MAX_SECONDS=$(config_uint maintenance_gc_max_seconds 600 30 1800)
NOW=$(date +%s)

# 1) 频率限制：每次尝试都记一次，失败或被打断也不会在当天重复执行。
if [ "$ACTION" = check ]; then
  last=$(result_value last_attempt_epoch)
  case "$last" in ''|*[!0-9]*) last=0 ;; esac
  [ "$last" -le "$NOW" ] || last=0
  [ $((NOW - last)) -ge $((MIN_HOURS * 3600)) ] || exit 0
  up=$(sed -n '1{s/[. ].*//;p;}' "$UPTIME_FILE" 2>/dev/null)
  case "$up" in ''|*[!0-9]*) up=0 ;; esac
  [ "$up" -ge "$BOOT_SETTLE_SECONDS" ] || exit 0
fi

# 2) 清理任务运行中不做维护，避免与删除 I/O 叠加。
[ ! -d "$STATE_DIR/run.lock" ] || exit 0
[ ! -d "$STATE_DIR/cache-lane.lock" ] || exit 0

is_charging() {
  bat=$(dumpsys battery 2>/dev/null)
  printf '%s\n' "$bat" | grep -Eq '^[[:space:]]*(AC powered|USB powered|Wireless powered|Dock powered): true' && return 0
  st=$(printf '%s\n' "$bat" | sed -n 's/^[[:space:]]*status: //p' | head -n 1)
  [ "$st" = 2 ] || [ "$st" = 5 ]
}
is_screen_off() {
  dumpsys power 2>/dev/null | grep -Eq 'Display Power: state=OFF|mWakefulness=Asleep|mInteractive=false'
}
is_charging || exit 0
is_screen_off || exit 0

# 3) 单实例锁（一个目录 + 一个 pid 文件，结束即删除）。
if ! mkdir "$LOCK" 2>/dev/null; then
  owner=$(sed -n '1p' "$LOCK/pid" 2>/dev/null)
  case "$owner" in ''|*[!0-9]*) owner=0 ;; esac
  if [ "$owner" -gt 1 ] && kill -0 "$owner" 2>/dev/null; then exit 0; fi
  rm -rf -- "$LOCK" 2>/dev/null; mkdir "$LOCK" 2>/dev/null || exit 0
fi
printf '%s\n' "$$" >"$LOCK/pid"
renice -n 19 -p "$$" >/dev/null 2>&1 || true
ionice -c 3 -p "$$" >/dev/null 2>&1 || true

GC_NODE=""; GC_ORIGINAL=""
restore_gc() {
  if [ -n "$GC_NODE" ] && [ -n "$GC_ORIGINAL" ]; then
    printf '%s\n' "$GC_ORIGINAL" >"$GC_NODE/gc_urgent" 2>/dev/null || true
  fi
  GC_ORIGINAL=""
}
finish() { restore_gc; rm -rf -- "$LOCK" 2>/dev/null; }
trap finish EXIT
trap 'exit 9' INT TERM

write_result() {
  tmp="$RESULT.tmp.$$"
  {
    echo "schema=maintenance-v1"
    echo "last_attempt_epoch=$NOW"
    echo "last_result=$1"
    echo "trim=$TRIM_RESULT"
    echo "gc=$GC_RESULT"
    echo "gc_seconds=$GC_SECONDS"
    echo "dirty_before=$DIRTY_BEFORE"
    echo "dirty_after=$DIRTY_AFTER"
    echo "duration_seconds=$(( $(date +%s) - NOW ))"
    [ "$1" != running ] || [ -z "$GC_NODE" ] || { echo "gc_node=$GC_NODE"; echo "gc_original=$GC_ORIGINAL"; }
  } >"$tmp" && mv -f "$tmp" "$RESULT"
  chmod 0600 "$RESULT" 2>/dev/null || true
}
# 先写一次尝试时间，即使后续被系统杀掉也不会立刻重复。
TRIM_RESULT=pending; GC_RESULT=pending; GC_SECONDS=0; DIRTY_BEFORE=-; DIRTY_AFTER=-
write_result running

# 4) TRIM：优先 fstrim（busybox 提供），否则交给系统 idle-maint（vold 内部执行 fstrim）。
trim_data() {
  for fb in "${BAIZE_MAINT_FSTRIM:-fstrim}" /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
    case "$fb" in
      fstrim|*/fstrim) command -v "$fb" >/dev/null 2>&1 || continue
        out=$("$fb" -v /data 2>&1) && { TRIM_RESULT="fstrim:$(printf '%s' "$out" | tr -s ' \t\n' ' ' | cut -c1-80)"; return 0; } ;;
      *) [ -x "$fb" ] || continue
        out=$("$fb" fstrim -v /data 2>&1) && { TRIM_RESULT="busybox:$(printf '%s' "$out" | tr -s ' \t\n' ' ' | cut -c1-80)"; return 0; } ;;
    esac
  done
  if command -v sm >/dev/null 2>&1 && sm idle-maint run >/dev/null 2>&1; then TRIM_RESULT=idle-maint; return 0; fi
  TRIM_RESULT=unavailable; return 1
}
trim_data || true

# 5) F2FS GC：只对 /data 所在的 F2FS 设备，打开 gc_urgent，直到脏段足够少、超时、
#    亮屏或拔掉电源为止；退出时一定恢复原值。
data_dev=$(awk '$2=="/data" && $3=="f2fs" {print $1; exit}' "$MOUNTS" 2>/dev/null)
if [ -z "$data_dev" ]; then
  GC_RESULT=not-f2fs
else
  dev_name=${data_dev##*/}
  if [ -L "$data_dev" ] || [ ! -d "$F2FS_SYSFS/$dev_name" ]; then
    real=$(readlink -f "$data_dev" 2>/dev/null); dev_name=${real##*/}
  fi
  if [ -n "$dev_name" ] && [ -w "$F2FS_SYSFS/$dev_name/gc_urgent" ]; then
    GC_NODE="$F2FS_SYSFS/$dev_name"
    dirty() { v=$(cat "$GC_NODE/dirty_segments" 2>/dev/null); case "$v" in ''|*[!0-9]*) echo -1 ;; *) echo "$v" ;; esac; }
    DIRTY_BEFORE=$(dirty)
    GC_ORIGINAL=$(cat "$GC_NODE/gc_urgent" 2>/dev/null | tr -cd '0-9')
    [ -n "$GC_ORIGINAL" ] || GC_ORIGINAL=0
    write_result running
    if printf '1\n' >"$GC_NODE/gc_urgent" 2>/dev/null; then
      GC_RESULT=completed
      threshold=$(config_uint maintenance_gc_dirty_target 100 0 100000)
      while [ "$GC_SECONDS" -lt "$GC_MAX_SECONDS" ]; do
        sleep "$POLL_SECONDS"; GC_SECONDS=$((GC_SECONDS + POLL_SECONDS))
        d=$(dirty); [ "$d" -ge 0 ] && [ "$d" -le "$threshold" ] && break
        is_screen_off || { GC_RESULT=interrupted-screen-on; break; }
        is_charging || { GC_RESULT=interrupted-unplugged; break; }
        [ ! -d "$STATE_DIR/run.lock" ] || { GC_RESULT=interrupted-task; break; }
      done
      [ "$GC_RESULT" != completed ] || [ "$GC_SECONDS" -lt "$GC_MAX_SECONDS" ] || GC_RESULT=time-limit
      restore_gc
      DIRTY_AFTER=$(dirty)
    else
      GC_RESULT=gc-node-readonly
    fi
  else
    GC_RESULT=gc-node-missing
  fi
fi

case "$GC_RESULT:$TRIM_RESULT" in
  completed:*|time-limit:*) overall=ok ;;
  interrupted*) overall=interrupted ;;
  *:unavailable) overall=unsupported ;;
  *) overall=trim-only ;;
esac
write_result "$overall"
mkdir -p "$STATE_DIR/logs" 2>/dev/null
baize_append_capped "$LOG" "$(date '+%F %T') result=$overall trim=$TRIM_RESULT gc=$GC_RESULT gc_s=$GC_SECONDS dirty=$DIRTY_BEFORE->$DIRTY_AFTER" "$LOG_LINES"
exit 0
