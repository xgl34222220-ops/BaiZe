#!/system/bin/sh
# BaiZe shared storage index v4: directory-fingerprint incremental reuse + unified side indexes.
set -u
# $0 不含斜杠时 ${0%/*} 会原样返回脚本名，这里显式兜底
case "$0" in */*) MODDIR=${0%/*} ;; *) MODDIR=. ;; esac
MODE=${1:-ensure}
TRIGGER=${2:-manual}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
CONFIG=${BAIZE_CONFIG_PATH:-$STATE_DIR/config.conf}
INDEX_DIR="$STATE_DIR/index"
CACHE_DIR="$INDEX_DIR/roots"
INDEX_FILE="$INDEX_DIR/storage-files.nul"
COVERAGE_FILE="$INDEX_DIR/coverage.tsv"
META_FILE="$INDEX_DIR/meta.env"
APK_INDEX="$INDEX_DIR/apk-files.nul"
EMPTY_INDEX="$INDEX_DIR/empty-files.nul"
LARGE_INDEX="$INDEX_DIR/large-files.nul"
ORGANIZER_INDEX="$INDEX_DIR/organizer-files.nul"
DUPLICATE_CANDIDATES="$INDEX_DIR/duplicate-candidates.tsv"
LOCK_DIR="$STATE_DIR/index.lock"
STOP_FILE="$STATE_DIR/stop"
# 原生索引器。不可用时下面的逐文件循环会作为退路继续工作。
if [ -f "$MODDIR/abi-resolve.sh" ]; then
  . "$MODDIR/abi-resolve.sh"
  NATIVE_ENGINE=$(baize_resolve_engine "$MODDIR" baize_engine 2>/dev/null || true)
else
  NATIVE_ENGINE=""
fi
NATIVE_ENGINE=${BAIZE_NATIVE_ENGINE:-$NATIVE_ENGINE}
TTL=${BAIZE_INDEX_TTL_SECONDS:-$(sed -n 's/^shared_index_ttl_seconds=//p' "$CONFIG" 2>/dev/null | tail -n 1)}
case "$TTL" in ''|*[!0-9]*) TTL=300 ;; esac
[ "$TTL" -lt 30 ] && TTL=30
[ "$TTL" -gt 86400 ] && TTL=86400
mkdir -p "$INDEX_DIR" "$CACHE_DIR"

now=$(date +%s)
old_epoch=$(sed -n 's/^epoch=//p' "$META_FILE" 2>/dev/null | tail -n 1)
case "$old_epoch" in ''|*[!0-9]*) old_epoch=0 ;; esac
if [ "$MODE" = ensure ] && [ -s "$INDEX_FILE" ] && [ $((now - old_epoch)) -lt "$TTL" ]; then
  echo "共享索引仍在 TTL 内"
  exit 0
fi

proc_start_ticks() { [ -r "/proc/$1/stat" ] && awk '{print $22}' "/proc/$1/stat" 2>/dev/null || echo 0; }
index_lock_alive() {
  il_pid=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
  il_ticks=$(sed -n '1p' "$LOCK_DIR/start_ticks" 2>/dev/null)
  case "$il_pid" in ''|*[!0-9]*) return 1 ;; esac
  kill -0 "$il_pid" 2>/dev/null || return 1
  il_actual=$(proc_start_ticks "$il_pid")
  case "$il_ticks" in ''|*[!0-9]*) il_ticks=0 ;; esac
  [ "$il_ticks" -eq 0 ] || [ "$il_actual" = "$il_ticks" ]
}
waited=0
while ! mkdir "$LOCK_DIR" 2>/dev/null; do
  if ! index_lock_alive; then rm -rf -- "$LOCK_DIR" 2>/dev/null || true; continue; fi
  sleep 1; waited=$((waited + 1)); [ "$waited" -lt 60 ] || { echo "共享索引锁等待超时" >&2; exit 3; }
done
printf '%s\n' "$$" >"$LOCK_DIR/pid"
printf '%s\n' "$(proc_start_ticks $$)" >"$LOCK_DIR/start_ticks"
trap 'rm -rf "$LOCK_DIR" 2>/dev/null' EXIT INT TERM

TMP="$LOCK_DIR/tmp"
mkdir -p "$TMP" "$TMP/seen"
RECORDS="$TMP/storage-files.nul"
COVERAGE="$TMP/coverage.tsv"
ROOTS="$TMP/roots.tsv"
: >"$RECORDS"
printf 'status\tgroup\tuser\tvolume\tfiles\tbytes\tpath\treason\n' >"$COVERAGE"
: >"$ROOTS"
safe() { printf '%s' "$1" | tr '\t\r\n' '   '; }
hash_text() {
  if command -v sha256sum >/dev/null 2>&1; then printf '%s' "$1" | sha256sum | awk '{print $1}'
  else printf '%s' "$1" | cksum | awk '{print $1}'
  fi
}
canonical() { readlink -f "$1" 2>/dev/null || printf '%s' "$1"; }
add_root() {
  ar_group=$1; ar_user=$2; ar_volume=$3; ar_depth=$4; ar_root=$(canonical "${5%/}")
  [ -d "$ar_root" ] || return 0; [ ! -L "$ar_root" ] || return 0
  awk -F '\t' -v p="$ar_root" '$5==p{found=1} END{exit !found}' "$ROOTS" 2>/dev/null && return 0
  printf '%s\t%s\t%s\t%s\t%s\n' "$(safe "$ar_group")" "$ar_user" "$(safe "$ar_volume")" "$ar_depth" "$ar_root" >>"$ROOTS"
}

discover_app_user_roots() {
  dau_user=$1; dau_volume=$2; dau_root=$3
  for dau_pkg in "$dau_root"/Android/data/* "$dau_root"/Android/media/*; do
    [ -d "$dau_pkg" ] || continue
    dau_name=${dau_pkg##*/}; dau_list="$TMP/discovered.$dau_user.$(hash_text "$dau_pkg").nul"; : >"$dau_list"
    find "$dau_pkg" -xdev -mindepth 1 -maxdepth 8 -type d \
      \( -iname download -o -iname downloads -o -iname downloaded -o -iname 下载 \
         -o -iname received -o -iname receive -o -iname recv -o -iname file_recv \
         -o -iname qqfile_recv -o -iname qqmy_file_recv -o -iname qqfile_receive \
         -o -iname timfile_recv -o -iname tim_file_recv -o -iname attachment \
         -o -iname attachments -o -iname export -o -iname exports -o -iname saved \
         -o -iname shared -o -iname documents -o -iname document -o -iname transfer \
         -o -iname transfers -o -iname offline \) -print0 2>/dev/null >"$dau_list"
    while IFS= read -r -d '' dau_dir; do
      dau_relative=${dau_dir#"$dau_pkg"/}; dau_lower=$(printf '%s' "$dau_relative" | tr '[:upper:]' '[:lower:]')
      case "/$dau_lower/" in */cache/*|*/code_cache/*|*/databases/*|*/tmp/*|*/temp/*|*/no_backup/*) continue ;; esac
      add_root "应用用户文件:$dau_name:${dau_dir##*/}" "$dau_user" "$dau_volume" 14 "$dau_dir"
    done <"$dau_list"
  done
}
add_user_root() {
  au_user=$1; au_root=$2; au_volume=$3
  add_root "共享存储" "$au_user" "$au_volume" 2 "$au_root"
  add_root "QQ接收" "$au_user" "$au_volume" 12 "$au_root/Tencent/QQfile_recv"
  add_root "TIM接收" "$au_user" "$au_volume" 12 "$au_root/Tencent/Timfile_recv"
  for au_path in "$au_root"/Download "$au_root"/Downloads "$au_root"/Documents; do add_root "用户文件" "$au_user" "$au_volume" 12 "$au_path"; done
  for au_pkg in "$au_root"/Android/media/*; do [ -d "$au_pkg" ] && add_root "应用媒体:${au_pkg##*/}" "$au_user" "$au_volume" 14 "$au_pkg"; done
  for au_pkg in "$au_root"/Android/data/*; do
    [ -d "$au_pkg" ] || continue; au_name=${au_pkg##*/}
    add_root "应用文件:$au_name" "$au_user" "$au_volume" 12 "$au_pkg/files"
    add_root "应用下载:$au_name" "$au_user" "$au_volume" 10 "$au_pkg/Download"
    add_root "应用下载:$au_name" "$au_user" "$au_volume" 10 "$au_pkg/Downloads"
  done
  discover_app_user_roots "$au_user" "$au_volume" "$au_root"
}
for userdir in "$MEDIA_ROOT"/[0-9]*; do [ -d "$userdir" ] && add_user_root "${userdir##*/}" "$userdir" internal; done
if [ -n "${BAIZE_EXTRA_STORAGE_ROOTS:-}" ]; then
  old_ifs=$IFS; IFS=:
  for extra in $BAIZE_EXTRA_STORAGE_ROOTS; do [ -d "$extra" ] && add_user_root 0 "$extra" test; done
  IFS=$old_ifs
else
  for vol in /storage/* /mnt/media_rw/*; do
    [ -d "$vol" ] || continue; [ ! -L "$vol" ] || continue
    name=${vol##*/}; case "$name" in emulated|self|enc_emulated|runtime) continue ;; esac
    found=0
    for userdir in "$vol"/[0-9]*; do [ -d "$userdir/Android" ] || continue; add_user_root "${userdir##*/}" "$userdir" "$name"; found=1; done
    [ "$found" = 1 ] || add_user_root 0 "$vol" "$name"
  done
fi

# Directory mtimes/inodes at the actual scan depth detect add/remove/rename without restatting every file.
fingerprint() {
  fp_root=$1; fp_depth=$2
  {
    stat -c '%d:%i:%Y:%s:%n' "$fp_root" 2>/dev/null
    find "$fp_root" -xdev -mindepth 1 -maxdepth "$fp_depth" -type d -print0 2>/dev/null |
      while IFS= read -r -d '' fp_dir; do stat -c '%d:%i:%Y:%s:%n' "$fp_dir" 2>/dev/null; done
  } | if command -v sha256sum >/dev/null 2>&1; then sha256sum; else cksum; fi | awk '{print $1}'
}

root_total=$(wc -l <"$ROOTS" | tr -d ' '); current=0; total_files=0; total_bytes=0
total_reused=0; total_scanned=0; total_duplicates=0; TAB=$(printf '\t')
: >"$TMP/apk.nul"; : >"$TMP/empty.nul"; : >"$TMP/large.nul"; : >"$TMP/organizer.nul"; : >"$TMP/duplicates.tsv"
large_mb=$(sed -n 's/^max_file_mb=//p' "$CONFIG" 2>/dev/null | tail -n 1); case "$large_mb" in ''|*[!0-9]*) large_mb=256 ;; esac
large_bytes=$((large_mb * 1024 * 1024))
while IFS="$TAB" read -r group user volume depth root || [ -n "${root:-}" ]; do
  [ -d "${root:-}" ] || continue; [ ! -f "$STOP_FILE" ] || exit 9; current=$((current + 1))
  key=$(hash_text "$root"); cache="$CACHE_DIR/$key.nul"; meta="$CACHE_DIR/$key.env"
  fp=$(fingerprint "$root" "$depth"); oldfp=$(sed -n 's/^fingerprint=//p' "$meta" 2>/dev/null | tail -n 1)
  list="$TMP/root.$current.nul"; : >"$list"; status=scanned; reason=
  if [ "$MODE" != refresh ] && [ -f "$cache" ] && [ "$fp" = "$oldfp" ]; then
    cp -f "$cache" "$list"; status=reused; reason="目录树指纹未变化"; total_reused=$((total_reused + 1))
  else
    find "$root" -xdev -mindepth 1 -maxdepth "$depth" \
      \( -type d \( -iname cache -o -iname code_cache -o -iname no_backup -o -iname databases -o -iname shared_prefs -o -iname lib -o -iname tmp -o -iname temp \) -prune \) \
      -o \( -type f -print0 \) 2>/dev/null >"$list"
    code=$?; [ "$code" -eq 0 ] || { status=partial; reason="部分目录无法读取"; }
    cp -f "$list" "$cache"
    { echo "fingerprint=$fp"; echo "path=$(safe "$root")"; echo "depth=$depth"; echo "updated=$(date +%s)"; } >"$meta"
    total_scanned=$((total_scanned + 1))
  fi
  files=0; bytes=0; root_duplicates=0
  # 逐文件分桶交给原生索引器。
  #
  # 旧实现是一个 shell 循环，每个文件 fork 约 9 个进程：
  #   stat -c '%d_%i'（2）、stat -c %s（2，同一文件被 stat 两遍）、
  #   printf | tr 转小写（2）、printf | base64 | tr（3），
  # 外加为每个唯一 inode 在临时目录创建一个标记文件做去重。
  # 实测 6000 个文件 51 秒；真机共享存储 5 万到 20 万个文件对应 7 到 28 分钟，
  # 而 APK 扫描与文件归类每次都会触发它。原生索引器同数据 0.012 秒。
  if [ -n "${NATIVE_ENGINE:-}" ] && [ -x "$NATIVE_ENGINE" ]; then
    idx_sum="$TMP/index.$current.env"
    if "$NATIVE_ENGINE" index-files \
        --list "$list" --seen "$TMP/seen.bin" \
        --records "$RECORDS" --apk "$TMP/apk.nul" --empty "$TMP/empty.nul" \
        --large "$TMP/large.nul" --organizer "$TMP/organizer.nul" \
        --duplicates "$TMP/duplicates.tsv" --large-bytes "$large_bytes" \
        --stop "$STOP_FILE" --summary "$idx_sum" 2>/dev/null; then
      files=$(sed -n 's/^files=//p' "$idx_sum" 2>/dev/null | tail -n 1)
      bytes=$(sed -n 's/^bytes=//p' "$idx_sum" 2>/dev/null | tail -n 1)
      root_duplicates=$(sed -n 's/^duplicates=//p' "$idx_sum" 2>/dev/null | tail -n 1)
      case "$files" in ''|*[!0-9]*) files=0 ;; esac
      case "$bytes" in ''|*[!0-9]*) bytes=0 ;; esac
      case "$root_duplicates" in ''|*[!0-9]*) root_duplicates=0 ;; esac
      total_duplicates=$((total_duplicates + root_duplicates))
    else
      status=partial
      reason="${reason}${reason:+；}原生索引器执行失败"
    fi
  else
    # 原生索引器不可用时退回旧的逐文件实现，保证功能不缺失。
    mkdir -p "$TMP/seen"
    while IFS= read -r -d '' file; do
      [ -f "$file" ] || continue; [ ! -L "$file" ] || continue
      case "${file##*/}" in *.part|*.partial|*.download|*.crdownload) continue ;; esac
      inode=$(stat -c '%d_%i' "$file" 2>/dev/null || echo "path_$(hash_text "$file")")
      if [ -e "$TMP/seen/$inode" ]; then root_duplicates=$((root_duplicates + 1)); total_duplicates=$((total_duplicates + 1)); continue; fi
      : >"$TMP/seen/$inode"
      size=$(stat -c %s "$file" 2>/dev/null || echo 0); case "$size" in ''|*[!0-9]*) size=0 ;; esac
      printf '%s\0' "$file" >>"$RECORDS"; files=$((files + 1)); bytes=$((bytes + size))
      lower=$(printf '%s' "${file##*/}" | tr '[:upper:]' '[:lower:]')
      case "$lower" in *.apk|*.apks|*.xapk|*.apkm|*.zip.apk) printf '%s\0' "$file" >>"$TMP/apk.nul" ;; esac
      [ "$size" -ne 0 ] || printf '%s\0' "$file" >>"$TMP/empty.nul"
      [ "$size" -lt "$large_bytes" ] || printf '%s\0' "$file" >>"$TMP/large.nul"
      case "$lower" in *.jpg|*.jpeg|*.png|*.webp|*.gif|*.heic|*.mp4|*.mkv|*.mov|*.avi|*.mp3|*.flac|*.wav|*.pdf|*.doc|*.docx|*.xls|*.xlsx|*.ppt|*.pptx|*.txt|*.md|*.zip|*.7z|*.rar|*.tar|*.gz|*.apk|*.apks|*.xapk|*.apkm) printf '%s\0' "$file" >>"$TMP/organizer.nul" ;; esac
      [ "$size" -le 0 ] || printf '%s\t%s\n' "$size" "$(printf '%s' "$file" | base64 | tr -d '\n')" >>"$TMP/duplicates.tsv"
    done <"$list"
  fi
  total_files=$((total_files + files)); total_bytes=$((total_bytes + bytes))
  [ "$root_duplicates" -eq 0 ] || reason="${reason}${reason:+；}去重 $root_duplicates 个重叠文件"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$status" "$(safe "$group")" "$user" "$(safe "$volume")" "$files" "$bytes" "$(safe "$root")" "$(safe "$reason")" >>"$COVERAGE"
done <"$ROOTS"

mv -f "$RECORDS" "$INDEX_FILE"; mv -f "$COVERAGE" "$COVERAGE_FILE"
mv -f "$TMP/apk.nul" "$APK_INDEX"; mv -f "$TMP/empty.nul" "$EMPTY_INDEX"; mv -f "$TMP/large.nul" "$LARGE_INDEX"; mv -f "$TMP/organizer.nul" "$ORGANIZER_INDEX"
sort -n -k1,1 "$TMP/duplicates.tsv" >"$DUPLICATE_CANDIDATES" 2>/dev/null || mv -f "$TMP/duplicates.tsv" "$DUPLICATE_CANDIDATES"
{
  echo "epoch=$(date +%s)"; echo "trigger=$TRIGGER"; echo "roots=$root_total"; echo "files=$total_files"; echo "bytes=$total_bytes"
  echo "roots_reused=$total_reused"; echo "roots_scanned=$total_scanned"; echo "overlap_duplicates=$total_duplicates"
  echo "engine=baize-storage-index-v4-incremental-unified"
} >"$META_FILE"
chmod 0600 "$INDEX_FILE" "$COVERAGE_FILE" "$META_FILE" "$APK_INDEX" "$EMPTY_INDEX" "$LARGE_INDEX" "$ORGANIZER_INDEX" "$DUPLICATE_CANDIDATES" 2>/dev/null || true
echo "共享增量索引完成：$root_total 个来源，$total_files 个唯一文件，复用 $total_reused 个来源"
