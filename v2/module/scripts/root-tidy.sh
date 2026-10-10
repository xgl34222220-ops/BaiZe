#!/system/bin/sh
# 根目录自动整理（系统维护的一步，由 storage-maintenance.sh 在同一维护窗口内调用）。
#   run     执行：只处理共享存储第一层。删除超过 1 天的空文件夹；按 App 写入的“禁止重建”名单维持零字节占位。
#   preview 只输出将要执行的动作，不改动任何文件。
#   status  输出最近一次结果。
# 永不：删除有内容的目录或文件、进入子目录遍历、处理标准目录 / 隐藏目录 / 白名单 / 链接。
# 只写固定文件：$STATE_DIR/root-tidy.env 与 $STATE_DIR/logs/root-tidy.log（最多 100 行）。
set -u
MODDIR=${BAIZE_MODULE_DIR:-${0%/*}}
case "$MODDIR" in */scripts) MODDIR=${MODDIR%/scripts} ;; esac
SCRIPTDIR="$MODDIR"
[ ! -d "$MODDIR/scripts" ] || SCRIPTDIR="$MODDIR/scripts"
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
CONFIG=${BAIZE_CONFIG_PATH:-$STATE_DIR/config.conf}
RULES="$STATE_DIR/root-tidy.rules"
RESULT="$STATE_DIR/root-tidy.env"
LOG="$STATE_DIR/logs/root-tidy.log"
ROOT=${BAIZE_ROOT_TIDY_ROOT:-/data/media/0}
MIN_AGE_SECONDS=${BAIZE_ROOT_TIDY_MIN_AGE_SECONDS:-86400}
MAX_ENTRIES=512
MAX_ACTIONS=32
ACTION=${1:-run}

[ -f "$SCRIPTDIR/state-retention.sh" ] && . "$SCRIPTDIR/state-retention.sh"
command -v baize_append_capped >/dev/null 2>&1 || baize_append_capped() { printf '%s\n' "$2" >>"$1"; }

case "$ACTION" in
  status) cat "$RESULT" 2>/dev/null; exit 0 ;;
  run|preview) ;;
  *) echo "用法: root-tidy.sh run|preview|status" >&2; exit 2 ;;
esac
[ -d "$STATE_DIR" ] || exit 0
[ "$(sed -n 's/^root_tidy_auto=//p' "$CONFIG" 2>/dev/null | tail -n 1)" = 1 ] || [ "$ACTION" = preview ] || exit 0
[ -d "$ROOT" ] && [ ! -L "$ROOT" ] || exit 0
[ ! -d "$STATE_DIR/run.lock" ] || exit 0
renice -n 19 -p "$$" >/dev/null 2>&1 || true
ionice -c 3 -p "$$" >/dev/null 2>&1 || true

lower() { printf '%s' "$1" | tr 'A-Z' 'a-z'; }
standard_name() {
  case "$(lower "$1")" in
    dcim|pictures|download|documents|music|movies|android|alarms|notifications|ringtones|podcasts|audiobooks|recordings|.baize-file-trash) return 0 ;;
  esac
  return 1
}
valid_name() {
  case "$1" in ''|.|..|*/*|*'|'*) return 1 ;; esac
  [ "${#1}" -le 128 ]
}
rule_names() { sed -n "s/^$1|//p" "$RULES" 2>/dev/null | head -n 200; }
listed() {
  want=$(lower "$2")
  rule_names "$1" | while IFS= read -r item; do [ "$(lower "$item")" = "$want" ] && { echo yes; break; }; done
}

NOW=$(date +%s)
REMOVED=0; PLACED=0; KEPT=0; ACTIONS=0
note() {
  [ "$ACTION" = run ] || { echo "$1"; return 0; }
  mkdir -p "$STATE_DIR/logs" 2>/dev/null
  baize_append_capped "$LOG" "$(date '+%F %T') $1" 100
}
owner=$(stat -c '%u:%g' "$ROOT" 2>/dev/null)

# 1) 禁止重建名单：同名空文件夹先移除，再放零字节占位；有内容的目录只记录，不处理。
while IFS= read -r name; do
  [ "$ACTIONS" -lt "$MAX_ACTIONS" ] || break
  valid_name "$name" || continue
  standard_name "$name" && continue
  target="$ROOT/$name"
  [ -L "$target" ] && continue
  if [ -f "$target" ]; then continue; fi
  if [ -d "$target" ]; then
    if [ -n "$(ls -A "$target" 2>/dev/null)" ]; then KEPT=$((KEPT + 1)); note "保留 $name（已被重建且有内容，请在 App 中复核）"; continue; fi
    if [ "$ACTION" = run ]; then rmdir "$target" 2>/dev/null || continue; fi
  fi
  if [ "$ACTION" = run ]; then
    ( set -C; : >"$target" ) 2>/dev/null || continue
    [ -z "$owner" ] || chown "$owner" "$target" 2>/dev/null || true
    chmod 0664 "$target" 2>/dev/null || true
  fi
  PLACED=$((PLACED + 1)); ACTIONS=$((ACTIONS + 1)); note "占位 $name（禁止重建）"
done <<EOF_BLOCK
$(rule_names block)
EOF_BLOCK

# 2) 空文件夹：只看第一层，超过最短存在时间、非隐藏、非标准、非白名单、非链接。
count=0
for target in "$ROOT"/*; do
  count=$((count + 1)); [ "$count" -le "$MAX_ENTRIES" ] || break
  [ "$ACTIONS" -lt "$MAX_ACTIONS" ] || break
  [ -d "$target" ] && [ ! -L "$target" ] || continue
  name=${target##*/}
  valid_name "$name" || continue
  case "$name" in .*) continue ;; esac
  standard_name "$name" && continue
  [ -z "$(listed allow "$name")" ] || continue
  [ -z "$(ls -A "$target" 2>/dev/null)" ] || continue
  mtime=$(stat -c %Y "$target" 2>/dev/null)
  case "$mtime" in ''|*[!0-9]*) continue ;; esac
  [ $((NOW - mtime)) -ge "$MIN_AGE_SECONDS" ] || continue
  if [ "$ACTION" = run ]; then rmdir "$target" 2>/dev/null || continue; fi
  REMOVED=$((REMOVED + 1)); ACTIONS=$((ACTIONS + 1)); note "移除空文件夹 $name"
done

[ "$ACTION" = run ] || exit 0
tmp="$RESULT.tmp.$$"
{ echo "schema=root-tidy-v1"; echo "last_epoch=$NOW"; echo "removed=$REMOVED"; echo "placeholders=$PLACED"; echo "kept=$KEPT"; } >"$tmp" && mv -f "$tmp" "$RESULT"
chmod 0600 "$RESULT" 2>/dev/null || true
exit 0
