#!/system/bin/sh
set -eu
ACTION=${1:-list}
shift || true
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
QUAR="$STATE_DIR/quarantine"
INDEX="$QUAR/index.tsv"
mkdir -p "$QUAR/files"
case "$ACTION" in
  put)
    src=${1:?path}
    [ -f "$src" ] || { echo "仅支持普通文件" >&2; exit 2; }
    case "$src" in /data/media/*|/storage/*|/sdcard/*) ;; *) echo "仅允许隔离用户共享存储文件" >&2; exit 3 ;; esac
    [ ! -L "$src" ] || { echo "拒绝隔离符号链接" >&2; exit 3; }
    id="$(date +%s)-$$-$(basename "$src" | tr -cd 'A-Za-z0-9._-' | cut -c1-80)"
    dst="$QUAR/files/$id"
    size=$(stat -c %s "$src" 2>/dev/null || echo 0)
    mv "$src" "$dst"
    printf '%s\t%s\t%s\t%s\n' "$id" "$(date +%s)" "$size" "$src" >>"$INDEX"
    echo "$id"
    ;;
  restore)
    id=${1:?id}
    row=$(awk -F '\t' -v id="$id" '$1==id{print;exit}' "$INDEX" 2>/dev/null)
    [ -n "$row" ] || exit 4
    original=$(printf '%s' "$row" | cut -f4-)
    [ -f "$QUAR/files/$id" ] || exit 4
    mkdir -p "${original%/*}"
    [ ! -e "$original" ] || { echo "原路径已有文件，拒绝覆盖" >&2; exit 5; }
    mv "$QUAR/files/$id" "$original"
    awk -F '\t' -v id="$id" '$1!=id' "$INDEX" >"$INDEX.tmp.$$" && mv -f "$INDEX.tmp.$$" "$INDEX"
    ;;
  purge)
    days=${1:-7}; now=$(date +%s)
    [ -f "$INDEX" ] || exit 0
    : >"$INDEX.tmp.$$"
    while IFS='\t' read -r id epoch size path; do
      case "$epoch" in ''|*[!0-9]*) epoch=0 ;; esac
      if [ $((now-epoch)) -ge $((days*86400)) ]; then rm -f "$QUAR/files/$id"; else printf '%s\t%s\t%s\t%s\n' "$id" "$epoch" "$size" "$path" >>"$INDEX.tmp.$$"; fi
    done <"$INDEX"
    mv -f "$INDEX.tmp.$$" "$INDEX"
    ;;
  list) cat "$INDEX" 2>/dev/null || true ;;
  *) echo "usage: put|restore|purge|list" >&2; exit 2 ;;
esac
