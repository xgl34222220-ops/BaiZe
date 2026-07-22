#!/system/bin/sh
# baize-storage-index-v3-multi-volume-incremental
set -u
MODE=${1:-ensure}; TRIGGER=${2:-manual}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
INDEX_DIR="$STATE_DIR/index"; CACHE_DIR="$INDEX_DIR/roots"
INDEX_FILE="$INDEX_DIR/storage-files.nul"; COVERAGE_FILE="$INDEX_DIR/coverage.tsv"; META_FILE="$INDEX_DIR/meta.env"
LOCK_DIR="$STATE_DIR/index.lock"; STOP_FILE="$STATE_DIR/stop"
TTL=${BAIZE_INDEX_TTL_SECONDS:-300}
mkdir -p "$INDEX_DIR" "$CACHE_DIR"
now=$(date +%s); old_epoch=$(sed -n 's/^epoch=//p' "$META_FILE" 2>/dev/null | tail -n 1); case "$old_epoch" in ''|*[!0-9]*) old_epoch=0;; esac
[ "$MODE" = ensure ] && [ -s "$INDEX_FILE" ] && [ $((now-old_epoch)) -lt "$TTL" ] && { echo "共享索引仍然有效"; exit 0; }
waited=0; while ! mkdir "$LOCK_DIR" 2>/dev/null; do sleep 1; waited=$((waited+1)); [ "$waited" -lt 60 ] || exit 3; done
trap 'rm -rf "$LOCK_DIR" 2>/dev/null' EXIT INT TERM
TMP="$LOCK_DIR/tmp"; mkdir -p "$TMP"; RECORDS="$TMP/files.nul"; COVERAGE="$TMP/coverage.tsv"; ROOTS="$TMP/roots.tsv"
: >"$RECORDS"; printf 'status\tgroup\tuser\tvolume\tfiles\tbytes\tpath\treason\n' >"$COVERAGE"; : >"$ROOTS"
safe(){ printf '%s' "$1" | tr '\t\r\n' '   '; }
add_root(){ ar_group=$1; ar_user=$2; ar_volume=$3; ar_depth=$4; ar_root=${5%/}; [ -d "$ar_root" ] || return 0; [ ! -L "$ar_root" ] || return 0; grep -Fq "$(printf '\t%s\n' "$ar_root")" "$ROOTS" 2>/dev/null && return 0; printf '%s\t%s\t%s\t%s\t%s\n' "$(safe "$ar_group")" "$ar_user" "$(safe "$ar_volume")" "$ar_depth" "$ar_root" >>"$ROOTS"; }

discover_app_user_roots(){
  dau_user=$1; dau_volume=$2; dau_root=$3
  for dau_pkg in "$dau_root"/Android/data/* "$dau_root"/Android/media/*; do
    [ -d "$dau_pkg" ] || continue
    dau_name=${dau_pkg##*/}
    dau_list="$TMP/discovered.$dau_user.$dau_name.nul"
    : >"$dau_list"
    find "$dau_pkg" -xdev -mindepth 1 -maxdepth 8 -type d \
      \( -iname download -o -iname downloads -o -iname downloaded -o -iname 下载 \
         -o -iname received -o -iname receive -o -iname recv -o -iname file_recv \
         -o -iname qqfile_recv -o -iname qqmy_file_recv -o -iname qqfile_receive \
         -o -iname timfile_recv -o -iname tim_file_recv \
         -o -iname attachment -o -iname attachments -o -iname export -o -iname exports \
         -o -iname saved -o -iname shared -o -iname documents -o -iname document \
         -o -iname transfer -o -iname transfers -o -iname offline \) -print0 2>/dev/null >"$dau_list"
    while IFS= read -r -d '' dau_dir; do
      dau_lower=$(printf '%s' "$dau_dir" | tr '[:upper:]' '[:lower:]')
      case "$dau_lower" in */cache|*/cache/*|*/code_cache|*/code_cache/*|*/databases|*/databases/*|*/tmp|*/tmp/*|*/temp|*/temp/*) continue ;; esac
      add_root "应用用户文件:$dau_name:${dau_dir##*/}" "$dau_user" "$dau_volume" 14 "$dau_dir"
    done <"$dau_list"
  done
}
add_user_root(){ au_user=$1; au_root=$2; au_volume=$3; add_root "共享存储" "$au_user" "$au_volume" 2 "$au_root"; add_root "QQ接收" "$au_user" "$au_volume" 12 "$au_root/Tencent/QQfile_recv"; add_root "TIM接收" "$au_user" "$au_volume" 12 "$au_root/Tencent/Timfile_recv"; for au_path in "$au_root"/Download "$au_root"/Downloads "$au_root"/Documents; do add_root "用户文件" "$au_user" "$au_volume" 12 "$au_path"; done; for au_pkg in "$au_root"/Android/media/*; do [ -d "$au_pkg" ] && add_root "应用媒体:${au_pkg##*/}" "$au_user" "$au_volume" 14 "$au_pkg"; done; for au_pkg in "$au_root"/Android/data/*; do [ -d "$au_pkg" ] || continue; au_name=${au_pkg##*/}; add_root "应用文件:$au_name" "$au_user" "$au_volume" 12 "$au_pkg/files"; add_root "应用下载:$au_name" "$au_user" "$au_volume" 10 "$au_pkg/Download"; add_root "应用下载:$au_name" "$au_user" "$au_volume" 10 "$au_pkg/Downloads"; done; discover_app_user_roots "$au_user" "$au_volume" "$au_root"; }
for userdir in "$MEDIA_ROOT"/[0-9]*; do [ -d "$userdir" ] || continue; add_user_root "${userdir##*/}" "$userdir" internal; done
for vol in /storage/* /mnt/media_rw/*; do
  [ -d "$vol" ] || continue; [ ! -L "$vol" ] || continue
  name=${vol##*/}; case "$name" in emulated|self|enc_emulated|runtime) continue;; esac
  found=0; for userdir in "$vol"/[0-9]*; do [ -d "$userdir/Android" ] || continue; add_user_root "${userdir##*/}" "$userdir" "$name"; found=1; done
  [ "$found" = 1 ] || add_user_root 0 "$vol" "$name"
done
fingerprint(){ root=$1; { stat -c '%d:%i:%Y:%s' "$root" 2>/dev/null; for child in "$root"/*; do [ -d "$child" ] && stat -c '%d:%i:%Y:%s' "$child" 2>/dev/null; done; } | sha256sum | awk '{print $1}'; }
root_total=$(wc -l <"$ROOTS" | tr -d ' '); current=0; total_files=0; total_bytes=0; TAB=$(printf '\t')
while IFS="$TAB" read -r group user volume depth root || [ -n "${root:-}" ]; do
  [ -d "${root:-}" ] || continue; [ -f "$STOP_FILE" ] && exit 9; current=$((current+1))
  key=$(printf '%s' "$root" | sha256sum | awk '{print $1}'); cache="$CACHE_DIR/$key.nul"; meta="$CACHE_DIR/$key.env"; fp=$(fingerprint "$root"); oldfp=$(sed -n 's/^fingerprint=//p' "$meta" 2>/dev/null | tail -n 1)
  status=scanned; reason=""; list="$TMP/root.$current.nul"; : >"$list"
  if [ "$MODE" != refresh ] && [ -s "$cache" ] && [ "$fp" = "$oldfp" ]; then cp -f "$cache" "$list"; status=reused; reason="目录指纹未变化"; else
    find "$root" -xdev -mindepth 1 -maxdepth "$depth" \( -type d \( -iname cache -o -iname code_cache -o -iname no_backup -o -iname databases -o -iname shared_prefs -o -iname lib \) -prune \) -o \( -type f -print0 \) 2>/dev/null >"$list"; code=$?; [ "$code" -eq 0 ] || { status=partial; reason="部分目录无法读取"; }; cp -f "$list" "$cache"; { echo "fingerprint=$fp"; echo "path=$root"; echo "updated=$(date +%s)"; } >"$meta"
  fi
  files=0; bytes=0
  while IFS= read -r -d '' file; do case "$file" in *.part|*.partial|*.download|*.crdownload) continue;; esac; printf '%s\0' "$file" >>"$RECORDS"; size=$(stat -c %s "$file" 2>/dev/null || echo 0); files=$((files+1)); bytes=$((bytes+size)); done <"$list"
  total_files=$((total_files+files)); total_bytes=$((total_bytes+bytes)); printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$status" "$(safe "$group")" "$user" "$(safe "$volume")" "$files" "$bytes" "$(safe "$root")" "$reason" >>"$COVERAGE"
done <"$ROOTS"
mv -f "$RECORDS" "$INDEX_FILE"; mv -f "$COVERAGE" "$COVERAGE_FILE"
{
 echo "epoch=$(date +%s)"; echo "trigger=$TRIGGER"; echo "roots=$root_total"; echo "files=$total_files"; echo "bytes=$total_bytes"; echo "engine=baize-storage-index-v3-multi-volume-incremental";
} >"$META_FILE"
chmod 0600 "$INDEX_FILE" "$COVERAGE_FILE" "$META_FILE" 2>/dev/null || true
echo "共享索引完成：$root_total 个来源，$total_files 个文件"
