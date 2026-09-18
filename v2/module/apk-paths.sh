#!/system/bin/sh
# One discovery/deletion boundary for interactive and scheduled package cleanup.
# Installed applications (/data/app, /system) are never storage roots.

apk_list_append() {
  _apk_list_name=$1
  _apk_value=$2
  eval "_apk_current=\${$_apk_list_name:-}"
  case "
$_apk_current
" in *"
$_apk_value
"*) return 0 ;; esac
  if [ -n "$_apk_current" ]; then
    eval "$_apk_list_name=\"\$_apk_current
\$_apk_value\""
  else
    eval "$_apk_list_name=\"\$_apk_value\""
  fi
}

apk_root_safe() {
  case "$1" in
    /|/data|/data/app|/data/app/*|/system|/system/*|/vendor|/vendor/*|/product|/product/*) return 1 ;;
  esac
  return 0
}

apk_add_root() {
  [ ! -L "$1" ] || return 0
  _apk_root=$(readlink -f "$1" 2>/dev/null) || return 0
  [ -d "$_apk_root" ] || return 0
  apk_root_safe "$_apk_root" || return 0
  apk_list_append APK_ROOTS "$_apk_root"
}

apk_add_fallback_root() {
  [ ! -L "$1" ] || return 0
  _apk_root=$(readlink -f "$1" 2>/dev/null) || return 0
  [ -d "$_apk_root" ] || return 0
  apk_root_safe "$_apk_root" || return 0
  apk_list_append APK_FALLBACK_ROOTS "$_apk_root"
}

apk_load_roots() {
  APK_ROOTS=
  APK_FALLBACK_ROOTS=
  APK_PRIVATE_BOUNDARIES=
  _apk_media_root=${MEDIA_ROOT:-/data/media}
  APK_PUBLIC_MEDIA_ROOT=${BAIZE_PUBLIC_MEDIA_ROOT:-/storage/emulated}
  _apk_media_users=0

  # Prefer the underlying media tree. It bypasses scoped-storage/FUSE quirks
  # on Android 13-16 while remaining outside installed-app locations.
  for _apk_user in "$_apk_media_root"/[0-9]*; do
    _apk_id=${_apk_user##*/}
    case "$_apk_id" in ''|*[!0-9]*) continue ;; esac
    [ -d "$_apk_user" ] || continue
    apk_add_root "$_apk_user"
    _apk_media_users=$((_apk_media_users + 1))
    # Keep the emulated-storage view as a read fallback only. It is scanned
    # only when the physical media view yields no package files.
    apk_add_fallback_root "$APK_PUBLIC_MEDIA_ROOT/$_apk_id"
  done

  # Some ROM/root namespaces do not expose /data/media even though the public
  # emulated volume is mounted. Promote the public view in that case.
  if [ "$_apk_media_users" -eq 0 ]; then
    for _apk_user in "$APK_PUBLIC_MEDIA_ROOT"/[0-9]*; do
      _apk_id=${_apk_user##*/}
      case "$_apk_id" in ''|*[!0-9]*) continue ;; esac
      apk_add_root "$_apk_user"
    done
  fi

  # ADB/package-manager helpers commonly leave user supplied APKs here. This
  # directory is safe to enumerate and is not an installed-app directory.
  apk_add_root /data/local/tmp

  if [ -n "${BAIZE_EXTRA_STORAGE_ROOTS:-}" ]; then
    _apk_ifs=$IFS; IFS=:
    for _apk_volume in $BAIZE_EXTRA_STORAGE_ROOTS; do apk_add_root "$_apk_volume"; done
    IFS=$_apk_ifs
  else
    # Prefer the raw removable-volume view and keep /storage/<uuid> as fallback.
    _apk_external_found=0
    for _apk_volume in /mnt/media_rw/*; do
      [ -d "$_apk_volume" ] || continue
      apk_add_root "$_apk_volume"
      _apk_uuid=${_apk_volume##*/}
      apk_add_fallback_root "/storage/$_apk_uuid"
      _apk_external_found=1
    done
    # Some ROMs only expose removable volumes through /storage/<uuid>.
    if [ "$_apk_external_found" -eq 0 ]; then
      for _apk_volume in /storage/*; do
        [ -d "$_apk_volume" ] || continue
        _apk_uuid=${_apk_volume##*/}
        case "$_apk_uuid" in emulated|self|enc_emulated|runtime) continue ;; esac
        apk_add_root "$_apk_volume"
      done
    fi
  fi
}

apk_load_private_roots() {
  _apk_data_root=${BAIZE_DATA_ROOT:-/data}
  for _apk_scope in user user_de; do
    for _apk_user in "$_apk_data_root/$_apk_scope"/[0-9]*; do
      _apk_id=${_apk_user##*/}
      case "$_apk_id" in ''|*[!0-9]*) continue ;; esac
      [ -d "$_apk_user" ] || continue
      [ ! -L "$_apk_user" ] || continue
      _apk_real=$(readlink -f "$_apk_user" 2>/dev/null) || continue
      apk_list_append APK_PRIVATE_BOUNDARIES "$_apk_real"
    done
  done
}

apk_private_path_allowed() {
  _apk_private_real=$1
  _apk_save_ifs=$IFS
  IFS='
'
  set -f
  for _apk_base in $APK_PRIVATE_BOUNDARIES; do
    case "$_apk_private_real" in
      "$_apk_base"/*)
        _apk_relative=${_apk_private_real#"$_apk_base"/}
        _apk_package=${_apk_relative%%/*}
        _apk_tail=${_apk_relative#*/}
        case "$_apk_package" in ''|*/*) continue ;; esac
        case "$_apk_tail" in
          cache/*|code_cache/*|files/*)
            set +f
            IFS=$_apk_save_ifs
            return 0
            ;;
        esac
        ;;
    esac
  done
  set +f
  IFS=$_apk_save_ifs
  return 1
}

apk_collect_private_candidates() {
  _apk_private_out=$1
  : >"$_apk_private_out"
  _apk_save_ifs=$IFS
  IFS='
'
  set -f
  for _apk_base in $APK_PRIVATE_BOUNDARIES; do
    [ -d "$_apk_base" ] || continue
    find "$_apk_base" -xdev -mindepth 3 -maxdepth 12 -type f \
      \( -iname '*.apk' -o -iname '*.apks' -o -iname '*.xapk' -o -iname '*.apkm' -o -iname '*.aab' \) \
      \( -path "$_apk_base/*/cache/*" -o -path "$_apk_base/*/code_cache/*" -o -path "$_apk_base/*/files/*" \) \
      -print0 >>"$_apk_private_out" 2>/dev/null || true
  done
  set +f
  IFS=$_apk_save_ifs
}

apk_path_allowed() {
  case "$1" in
    *.[aA][pP][kK]|*.[aA][pP][kK][sS]|*.[xX][aA][pP][kK]|*.[aA][pP][kK][mM]|*.[aA][aA][bB]) ;;
    *) return 1 ;;
  esac
  [ ! -L "$1" ] || return 1
  _apk_real=$(readlink -f "$1" 2>/dev/null) || return 1
  _apk_save_ifs=$IFS
  IFS='
'
  set -f
  for _apk_base in $APK_ROOTS $APK_FALLBACK_ROOTS; do
    _apk_base_real=$(readlink -f "$_apk_base" 2>/dev/null) || continue
    case "$_apk_real" in
      "$_apk_base_real"/*)
        set +f
        IFS=$_apk_save_ifs
        return 0
        ;;
    esac
  done
  set +f
  IFS=$_apk_save_ifs
  apk_private_path_allowed "$_apk_real"
}

apk_find_into() {
  _apk_base=$1
  _apk_out=$2
  find "$_apk_base" -xdev -type f \
    \( -iname '*.apk' -o -iname '*.apks' -o -iname '*.xapk' -o -iname '*.apkm' -o -iname '*.aab' \) \
    -print0 >"$_apk_out" 2>/dev/null
}

apk_fallback_for_root() {
  case "$1" in
    /data/media/[0-9]*)
      _apk_id=${1##*/}
      printf '%s\n' "$APK_PUBLIC_MEDIA_ROOT/$_apk_id"
      ;;
    /mnt/media_rw/*)
      _apk_uuid=${1##*/}
      printf '%s\n' "/storage/$_apk_uuid"
      ;;
    *)
      printf '\n'
      ;;
  esac
}

apk_fallback_allowed() {
  _apk_candidate=$1
  _apk_candidate_real=$(readlink -f "$_apk_candidate" 2>/dev/null) || return 1
  _apk_save_ifs=$IFS
  IFS='
'
  set -f
  for _apk_base in $APK_FALLBACK_ROOTS; do
    _apk_base_real=$(readlink -f "$_apk_base" 2>/dev/null) || continue
    [ "$_apk_base_real" = "$_apk_candidate_real" ] && {
      set +f
      IFS=$_apk_save_ifs
      return 0
    }
  done
  set +f
  IFS=$_apk_save_ifs
  return 1
}

apk_collect_candidates() {
  _apk_output=$1
  APK_SCAN_ROOT_ERRORS=0
  APK_SCAN_FALLBACKS=0
  APK_SCAN_FAILED_ROOTS=
  _apk_success_roots=0
  _apk_index=0
  : >"$_apk_output"

  _apk_save_ifs=$IFS
  IFS='
'
  set -f
  for _apk_base in $APK_ROOTS; do
    [ ! -f "${STOP_FILE:-/nonexistent}" ] || {
      set +f
      IFS=$_apk_save_ifs
      return 9
    }
    _apk_index=$((_apk_index + 1))
    _apk_tmp="${_apk_output}.root.$$.$_apk_index"
    : >"$_apk_tmp"
    if apk_find_into "$_apk_base" "$_apk_tmp"; then
      _apk_success_roots=$((_apk_success_roots + 1))
    else
      APK_SCAN_ROOT_ERRORS=$((APK_SCAN_ROOT_ERRORS + 1))
      apk_list_append APK_SCAN_FAILED_ROOTS "$_apk_base"
    fi

    if [ -s "$_apk_tmp" ]; then
      cat "$_apk_tmp" >>"$_apk_output"
      rm -f "$_apk_tmp"
      continue
    fi
    rm -f "$_apk_tmp"

    # HyperOS and some Android 16 mount namespaces expose the same user files
    # only through /storage/emulated/<user>. Retry that view only when the
    # preferred physical view produced no package candidates, avoiding duplicates.
    _apk_fallback=$(apk_fallback_for_root "$_apk_base")
    if [ -n "$_apk_fallback" ] && apk_fallback_allowed "$_apk_fallback" && [ -d "$_apk_fallback" ]; then
      _apk_tmp="${_apk_output}.fallback.$$.$_apk_index"
      : >"$_apk_tmp"
      if apk_find_into "$_apk_fallback" "$_apk_tmp"; then
        _apk_success_roots=$((_apk_success_roots + 1))
        APK_SCAN_FALLBACKS=$((APK_SCAN_FALLBACKS + 1))
      else
        APK_SCAN_ROOT_ERRORS=$((APK_SCAN_ROOT_ERRORS + 1))
        apk_list_append APK_SCAN_FAILED_ROOTS "$_apk_fallback"
      fi
      [ ! -s "$_apk_tmp" ] || cat "$_apk_tmp" >>"$_apk_output"
      rm -f "$_apk_tmp"
    fi
  done
  set +f
  IFS=$_apk_save_ifs

  if [ -n "${APK_PRIVATE_BOUNDARIES:-}" ]; then
    _apk_private_tmp="${_apk_output}.private.$"
    apk_collect_private_candidates "$_apk_private_tmp"
    if [ -s "$_apk_private_tmp" ]; then
      cat "$_apk_private_tmp" >>"$_apk_output"
      _apk_success_roots=$((_apk_success_roots + 1))
    fi
    rm -f "$_apk_private_tmp"
  fi

  # One unreadable subtree must never discard packages found elsewhere. Only
  # fail when every storage root was unreadable and no candidate could be read.
  [ "$_apk_success_roots" -gt 0 ] || [ -s "$_apk_output" ] || return 5
  return 0
}
