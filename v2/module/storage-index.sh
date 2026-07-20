#!/system/bin/sh
# baize-storage-index-v2.2
set -u

MODE=${1:-ensure}
TRIGGER=${2:-manual}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
INDEX_DIR="$STATE_DIR/index"
INDEX_FILE="$INDEX_DIR/storage-files.nul"
COVERAGE_FILE="$INDEX_DIR/coverage.tsv"
META_FILE="$INDEX_DIR/meta.env"
LOCK_DIR="$STATE_DIR/index.lock"
STOP_FILE="$STATE_DIR/stop"
TTL=${BAIZE_INDEX_TTL_SECONDS:-300}

mkdir -p "$INDEX_DIR"
now=$(date +%s)
old_epoch=$(sed -n 's/^epoch=//p' "$META_FILE" 2>/dev/null | tail -n 1)
case "$old_epoch" in ''|*[!0-9]*) old_epoch=0 ;; esac
if [ "$MODE" = ensure ] && [ -s "$INDEX_FILE" ] && [ -s "$COVERAGE_FILE" ] && [ $((now - old_epoch)) -lt "$TTL" ]; then
  echo "共享存储索引仍然有效"
  exit 0
fi

waited=0
while ! mkdir "$LOCK_DIR" 2>/dev/null; do
  if [ -s "$INDEX_FILE" ] && [ $waited -ge 20 ]; then exit 0; fi
  sleep 1
  waited=$((waited + 1))
  [ $waited -lt 60 ] || { echo "等待共享索引锁超时" >&2; exit 3; }
done
cleanup() { rm -rf -- "$LOCK_DIR" 2>/dev/null; }
trap cleanup EXIT INT TERM

TMP="$LOCK_DIR/tmp"
mkdir -p "$TMP"
RECORDS_TMP="$TMP/storage-files.nul"
COVERAGE_TMP="$TMP/coverage.tsv"
ROOTS="$TMP/roots.tsv"
: >"$RECORDS_TMP"
printf 'status\tgroup\tfiles\tbytes\tpath\treason\n' >"$COVERAGE_TMP"
: >"$ROOTS"

safe_field() { printf '%s' "$1" | tr '\t\r\n' '   '; }
add_root() {
  group=$1 depth=$2 root=${3%/}
  [ -d "$root" ] || return 0
  grep -Fq "$(printf '\t%s\n' "$root")" "$ROOTS" 2>/dev/null && return 0
  printf '%s\t%s\t%s\n' "$(safe_field "$group")" "$depth" "$root" >>"$ROOTS"
}

for userdir in "$MEDIA_ROOT"/[0-9]*; do
  [ -d "$userdir" ] || continue
  add_root "内部存储根目录" 1 "$userdir"
  add_root "QQ接收:公共目录" 12 "$userdir/Tencent/QQfile_recv"
  add_root "TIM接收:公共目录" 12 "$userdir/Tencent/Timfile_recv"
  for top in "$userdir"/*; do
    [ -d "$top" ] || continue
    name=${top##*/}
    case "$name" in
      Android|Tencent|DCIM|Pictures|Movies|Music|Podcasts|Ringtones|Alarms|Notifications|Audiobooks|BaiZe归类|LOST.DIR) continue ;;
    esac
    add_root "共享下载目录:$name" 12 "$top"
  done
  for pkg in "$userdir"/Android/media/*; do
    [ -d "$pkg" ] && add_root "应用媒体:${pkg##*/}" 14 "$pkg"
  done
  for pkg in "$userdir"/Android/data/*; do
    [ -d "$pkg" ] || continue
    package=${pkg##*/}
    add_root "应用文件:$package" 12 "$pkg/files"
    add_root "应用下载:$package" 10 "$pkg/Download"
    add_root "应用下载:$package" 10 "$pkg/Downloads"
    add_root "应用文档:$package" 10 "$pkg/Documents"
    add_root "Telegram:$package" 12 "$pkg/Telegram"
    add_root "QQ接收:$package" 12 "$pkg/Tencent/QQfile_recv"
    add_root "TIM接收:$package" 12 "$pkg/Tencent/Timfile_recv"
  done
done

root_total=$(wc -l <"$ROOTS" 2>/dev/null | tr -d ' ')
case "$root_total" in ''|*[!0-9]*) root_total=0 ;; esac
root_current=0
total_files=0
total_bytes=0

TAB=$(printf '\t')
while IFS="$TAB" read -r group depth root || [ -n "${root:-}" ]; do
  [ -d "${root:-}" ] || continue
  [ -f "$STOP_FILE" ] && { echo "索引任务已停止" >&2; exit 9; }
  root_current=$((root_current + 1))
  LIST="$TMP/root.$root_current.nul"
  : >"$LIST"
  find "$root" -xdev -mindepth 1 -maxdepth "$depth" \
    \( -type d \( -iname cache -o -iname code_cache -o -iname no_backup -o -iname databases -o -iname shared_prefs -o -iname lib -o -iname tmp -o -iname temp -o -iname thumbnails -o -iname .thumbnails -o -iname stickers -o -iname emoji -o -iname crash -o -iname crashes \) -prune \) -o \
    \( -type f -print0 \) 2>/dev/null >"$LIST"
  code=$?
  files=0
  bytes=0
  while IFS= read -r -d '' file; do
    case "$file" in *.part|*.partial|*.download|*.tmp|*.temp|*.crdownload) continue ;; esac
    printf '%s\0' "$file" >>"$RECORDS_TMP"
    size=$(stat -c %s "$file" 2>/dev/null)
    case "$size" in ''|*[!0-9]*) size=0 ;; esac
    files=$((files + 1))
    bytes=$((bytes + size))
  done <"$LIST"
  total_files=$((total_files + files))
  total_bytes=$((total_bytes + bytes))
  status=scanned; reason=""
  [ "$code" -eq 0 ] || { status=partial; reason="部分目录无法读取"; }
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$status" "$(safe_field "$group")" "$files" "$bytes" "$(safe_field "$root")" "$reason" >>"$COVERAGE_TMP"
done <"$ROOTS"

mv -f "$RECORDS_TMP" "$INDEX_FILE"
mv -f "$COVERAGE_TMP" "$COVERAGE_FILE"
{
  echo "epoch=$(date +%s)"
  echo "trigger=$TRIGGER"
  echo "roots=$root_total"
  echo "files=$total_files"
  echo "bytes=$total_bytes"
  echo "engine=baize-storage-index-v2.2"
} >"$META_FILE"
chmod 0600 "$INDEX_FILE" "$COVERAGE_FILE" "$META_FILE" 2>/dev/null

echo "共享存储索引完成：$root_total 个来源，$total_files 个文件"
exit 0
