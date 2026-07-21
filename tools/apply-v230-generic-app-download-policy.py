from pathlib import Path

root = Path(__file__).resolve().parents[1]
worker = root / "v2/module/organizer-worker.sh"
text = worker.read_text()


def replace_block(start_marker: str, end_marker: str, replacement: str, label: str) -> None:
    global text
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"{label} start anchor missing")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f"{label} end anchor missing")
    text = text[:start] + replacement.rstrip() + "\n\n" + text[end:]


fallback = r'''# generic-user-file-dir-policy-v2.3
append_user_file_dirs() {
  package_root=$1
  [ -d "$package_root" ] || return 0
  find "$package_root" -xdev -mindepth 1 -maxdepth 10 \
    \( -type d \( \
      -iname cache -o -iname code_cache -o -iname no_backup -o \
      -iname databases -o -iname shared_prefs -o -iname lib -o \
      -iname tmp -o -iname temp -o -iname thumbnails -o -iname .thumbnails -o \
      -iname stickers -o -iname emoji -o -iname crash -o -iname crashes -o \
      -iname assets -o -iname asset -o -iname resources -o -iname resource -o \
      -iname res -o -iname textures -o -iname texture -o -iname sprites -o \
      -iname sprite -o -iname atlases -o -iname atlas -o -iname shaders -o \
      -iname shader -o -iname bundles -o -iname bundle -o -iname streamingassets -o \
      -iname addressables -o -iname unitycache -o -iname gameassets -o \
      -iname game_resources -o -iname levels -o -iname maps -o -iname skins \
    \) -prune \) -o \
    \( -type d \( \
      -iname download -o -iname downloads -o -iname downloaded -o -iname '下载' -o \
      -iname received -o -iname receive -o -iname recv -o -iname file_recv -o \
      -iname qqfile_recv -o -iname qqmy_file_recv -o -iname qqfile_receive -o \
      -iname timfile_recv -o -iname tim_file_recv -o -iname attachment -o \
      -iname attachments -o -iname export -o -iname exports -o -iname saved -o \
      -iname saved_files -o -iname documents -o -iname document -o \
      -iname transfer -o -iname transfers -o -iname offline -o \
      -iname 'Telegram Documents' -o -iname 'Telegram Images' -o \
      -iname 'Telegram Video' -o -iname 'Telegram Audio' -o -iname 'Telegram Files' -o \
      -iname 'Nagram Documents' -o -iname 'Nagram Images' -o \
      -iname 'Nagram Video' -o -iname 'Nagram Audio' -o \
      -iname 'NagramX Documents' -o -iname 'NagramX Images' -o \
      -iname 'NagramX Video' -o -iname 'NagramX Audio' \
    \) -print0 \) 2>/dev/null |
  while IFS= read -r -d '' user_dir; do
    append_tree_files "$user_dir"
  done
}

build_fallback_index() {
  mkdir -p "${INDEX_FILE%/*}"
  : >"$INDEX_FILE"
  for fb_user_root in "$MEDIA_ROOT"/[0-9]*; do
    [ -d "$fb_user_root" ] || continue
    find "$fb_user_root" -xdev -mindepth 1 -maxdepth 1 -type f -print0 2>/dev/null >>"$INDEX_FILE"
    for fb_public in \
      "$fb_user_root/Download" "$fb_user_root/Downloads" "$fb_user_root/Documents" \
      "$fb_user_root/DCIM" "$fb_user_root/Pictures" "$fb_user_root/Movies" \
      "$fb_user_root/Music" "$fb_user_root/Podcasts" "$fb_user_root/Audiobooks" \
      "$fb_user_root/Recordings" "$fb_user_root/Bluetooth" "$fb_user_root/Tencent" \
      "$fb_user_root/Telegram" "$fb_user_root/Nagram" "$fb_user_root/NagramX"; do
      append_tree_files "$fb_public"
    done
    for fb_package_root in "$fb_user_root"/Android/data/* "$fb_user_root"/Android/media/*; do
      append_user_file_dirs "$fb_package_root"
    done
  done
  chmod 0600 "$INDEX_FILE" 2>/dev/null || true
}'''
replace_block("append_known_app_roots() {", "b64() {", fallback, "fallback discovery")

policy = r'''is_private_runtime_path() {
  normalized=$(normalized_path "$1")
  case "$normalized" in
    */cache/*|*/code_cache/*|*/no_backup/*|*/databases/*|*/database/*|*/shared_prefs/*|*/lib/*|*/tmp/*|*/temp/*|*/thumbnails/*|*/_thumbnails/*|*/crash/*|*/crashes/*|*/logs/*|*/log/*) return 0 ;;
  esac
  return 1
}

has_user_file_segment() {
  normalized=$(normalized_path "$1")
  case "$normalized" in
    */download/*|*/downloads/*|*/downloaded/*|*/下载/*|*/received/*|*/receive/*|*/recv/*|*/file_recv/*|*/qqfile_recv/*|*/qqmy_file_recv/*|*/qqfile_receive/*|*/timfile_recv/*|*/tim_file_recv/*|*/attachment/*|*/attachments/*|*/export/*|*/exports/*|*/saved/*|*/saved_files/*|*/document/*|*/documents/*|*/transfer/*|*/transfers/*|*/offline/*|*/telegram_documents/*|*/telegram_images/*|*/telegram_video/*|*/telegram_audio/*|*/telegram_files/*|*/nagram_documents/*|*/nagram_images/*|*/nagram_video/*|*/nagram_audio/*|*/nagramx_documents/*|*/nagramx_images/*|*/nagramx_video/*|*/nagramx_audio/*) return 0 ;;
  esac
  return 1
}

is_game_package() {
  package=$1
  case ",${BAIZE_GAME_PACKAGES:-}," in
    *,"$package",*) return 0 ;;
  esac
  cache_dir="$STATE_DIR/package-policy"
  cache_file="$cache_dir/$package.game"
  if [ -f "$cache_file" ]; then
    [ "$(cat "$cache_file" 2>/dev/null)" = 1 ]
    return $?
  fi
  is_game=0
  if command -v cmd >/dev/null 2>&1; then
    cmd package dump "$package" 2>/dev/null | grep -Eq '(^|[[:space:]])category=0([[:space:]]|$)' && is_game=1
  elif command -v dumpsys >/dev/null 2>&1; then
    dumpsys package "$package" 2>/dev/null | grep -Eq '(^|[[:space:]])category=0([[:space:]]|$)' && is_game=1
  fi
  mkdir -p "$cache_dir" 2>/dev/null || true
  printf '%s' "$is_game" >"$cache_file" 2>/dev/null || true
  [ "$is_game" -eq 1 ]
}

allowed_app_source() {
  package=$1 tail=$2 root_kind=$3
  is_private_runtime_path "$tail" && return 1
  is_suspicious_app_resource "$tail" && return 1
  is_game_package "$package" && return 1

  # Every package is eligible when the full path contains a high-confidence
  # user-file directory. Package names are not used as the primary allowlist.
  has_user_file_segment "$tail" && return 0

  # Keep explicit compatibility for clients whose public media tree uses
  # product-branded names rather than generic Download/received directories.
  if [ "$root_kind" = media ] && is_telegram_package "$package"; then
    case "$tail" in
      Telegram/*|Nagram/*|NagramX/*) return 0 ;;
    esac
  fi

  return 1
}'''
replace_block("allowed_app_source() {", "is_public_user_path() {", policy, "generic app policy")

worker.write_text(text)
print("generic all-app user download policy applied")
