#!/system/bin/sh
# One discovery/deletion boundary for interactive and scheduled package cleanup.
# Installed applications (/data/app, /system) are never storage roots.
apk_add_root() {
  [ ! -L "$1" ] || return 0
  _apk_root=$(readlink -f "$1" 2>/dev/null) || return 0
  [ -d "$_apk_root" ] || return 0
  case "$_apk_root" in /|/data|/data/app|/data/app/*|/system|/system/*|/vendor|/vendor/*|/product|/product/*) return 0 ;; esac
  case "
$APK_ROOTS
" in *"
$_apk_root
"*) return 0 ;; esac
  APK_ROOTS="${APK_ROOTS}${APK_ROOTS:+
}$_apk_root"
}
apk_load_roots() {
  APK_ROOTS=
  for _apk_user in "${MEDIA_ROOT:-/data/media}"/[0-9]*; do
    _apk_id=${_apk_user##*/}
    case "$_apk_id" in ''|*[!0-9]*) continue ;; esac
    apk_add_root "$_apk_user"
  done
  if [ -n "${BAIZE_EXTRA_STORAGE_ROOTS:-}" ]; then
    _apk_ifs=$IFS; IFS=:
    for _apk_volume in $BAIZE_EXTRA_STORAGE_ROOTS; do apk_add_root "$_apk_volume"; done
    IFS=$_apk_ifs
  else
    # /mnt/media_rw is the canonical removable-volume view: do not scan its
    # /storage FUSE alias a second time.
    for _apk_volume in /mnt/media_rw/*; do apk_add_root "$_apk_volume"; done
  fi
}
apk_path_allowed() {
  case "$1" in *.[aA][pP][kK]|*.[aA][pP][kK][sS]|*.[xX][aA][pP][kK]|*.[aA][pP][kK][mM]) ;; *) return 1 ;; esac
  _apk_real=$(readlink -f "$1" 2>/dev/null) || return 1
  [ "$_apk_real" = "$1" ] || return 1
  _apk_save_ifs=$IFS; IFS='
'
  for _apk_base in $APK_ROOTS; do
    case "$1" in "$_apk_base"/*) IFS=$_apk_save_ifs; return 0 ;; esac
  done
  IFS=$_apk_save_ifs
  return 1
}
apk_collect_candidates() {
  _apk_output=$1; _apk_status=0
  : >"$_apk_output"
  _apk_save_ifs=$IFS; IFS='
'
  for _apk_base in $APK_ROOTS; do
    [ ! -f "${STOP_FILE:-/nonexistent}" ] || { IFS=$_apk_save_ifs; return 9; }
    # Find only package names: no image hashing, per-file shell processes or
    # organizer/duplicate index construction before showing the APK list.
    find "$_apk_base" -xdev -type f \
      \( -iname '*.apk' -o -iname '*.apks' -o -iname '*.xapk' -o -iname '*.apkm' \) \
      -print0 >>"$_apk_output" 2>/dev/null || _apk_status=5
  done
  IFS=$_apk_save_ifs
  return "$_apk_status"
}
