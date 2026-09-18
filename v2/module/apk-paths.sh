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

apk_scan_candidate_allowed() {
  case "$1" in
    *.[aA][pP][kK]|*.[aA][pP][kK][sS]|*.[xX][aA][pP][kK]|*.[aA][pP][kK][mM]|*.[aA][aA][bB]) ;;
    *) return 1 ;;
  esac
  [ -f "$1" ] || return 1
  [ ! -L "$1" ] || return 1

  _apk_scan_save_ifs=$IFS
  IFS='
'
  set -f
  for _apk_base in $APK_ROOTS $APK_FALLBACK_ROOTS; do
    case "$1" in
      "$_apk_base"/*)
        set +f
        IFS=$_apk_scan_save_ifs
        return 0
        ;;
    esac
  done
  _apk_scan_real=$(readlink -f "$1" 2>/dev/null || true)
  if [ -n "$_apk_scan_real" ]; then
    for _apk_base in $APK_ROOTS $APK_FALLBACK_ROOTS; do
      _apk_base_real=$(readlink -f "$_apk_base" 2>/dev/null || true)
      [ -n "$_apk_base_real" ] || continue
      case "$_apk_scan_real" in
        "$_apk_base_real"/*)
          set +f
          IFS=$_apk_scan_save_ifs
          return 0
          ;;
      esac
    done
  fi
  for _apk_base in $APK_PRIVATE_BOUNDARIES; do
    case "$1" in
      "$_apk_base"/*)
        _apk_relative=${1#"$_apk_base"/}
        _apk_package=${_apk_relative%%/*}
        _apk_tail=${_apk_relative#*/}
        case "$_apk_package" in ''|*/*) continue ;; esac
        case "$_apk_tail" in
          cache/*|code_cache/*|files/*)
            set +f
            IFS=$_apk_scan_save_ifs
            return 0
            ;;
        esac
        ;;
    esac
  done
  set +f
  IFS=$_apk_scan_save_ifs
  return 1
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

apk_bruteforce_candidates() {
  _apk_out=$1
  : >"$_apk_out"
  _apk_seen_roots=
  for _apk_root in "${MEDIA_ROOT:-/data/media}" "${APK_PUBLIC_MEDIA_ROOT:-/storage/emulated}" /sdcard /storage /mnt/media_rw /data/local/tmp; do
    [ -d "$_apk_root" ] || continue
    [ ! -L "$_apk_root" ] || continue
    case "
$_apk_seen_roots
" in *"
$_apk_root
"*) continue ;; esac
    apk_list_append _apk_seen_roots "$_apk_root"
    apk_add_fallback_root "$_apk_root"
    find "$_apk_root" -type f \
      \( -iname '*.apk' -o -iname '*.apks' -o -iname '*.xapk' -o -iname '*.apkm' -o -iname '*.aab' \) \
      -print0 >>"$_apk_out" 2>/dev/null || true
  done
  if [ -n "${BAIZE_BRUTE_STORAGE_ROOTS:-}" ]; then
    _apk_old_ifs=$IFS
    IFS=:
    for _apk_root in $BAIZE_BRUTE_STORAGE_ROOTS; do
      [ -d "$_apk_root" ] || continue
      [ ! -L "$_apk_root" ] || continue
      case "
$_apk_seen_roots
" in *"
$_apk_root
"*) continue ;; esac
      apk_list_append _apk_seen_roots "$_apk_root"
      apk_add_fallback_root "$_apk_root"
      find "$_apk_root" -type f \
        \( -iname '*.apk' -o -iname '*.apks' -o -iname '*.xapk' -o -iname '*.apkm' -o -iname '*.aab' \) \
        -print0 >>"$_apk_out" 2>/dev/null || true
    done
    IFS=$_apk_old_ifs
  fi
  if [ -n "${APK_PRIVATE_BOUNDARIES:-}" ]; then
    _apk_private_tmp="${_apk_out}.private.$"
    apk_collect_private_candidates "$_apk_private_tmp"
    [ ! -s "$_apk_private_tmp" ] || cat "$_apk_private_tmp" >>"$_apk_out"
    rm -f "$_apk_private_tmp"
  fi
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

# --- BaiZe real-device storage discovery v3 ---
# Discover Android 16 / HyperOS storage views dynamically. Scan broadly, delete only from snapshots.
apk_realpath_or_self() {
  _apk_input=${1%/}
  [ -n "$_apk_input" ] || _apk_input=/
  _apk_resolved=$(readlink -f "$_apk_input" 2>/dev/null || true)
  if [ -n "$_apk_resolved" ]; then
    printf '%s\n' "$_apk_\ÛÛY[ÙB[	É\×ÈØ\×Ú[]BB\×ØYÜÛÝ

HÂÈYHH]\Ø\×Ç&ö÷CÒBµñÉ±ÁÑ¡}½É}Í±Ä¤(lµ}Á­}É½½ÐtñðÉÑÕÉ¸À(Á­}É½½Ñ}Í}Á­|root" || return 0
  apk_list_append APK_ROOTS "$_apk_ÛÝB\×ØYÙ[XÚ×ÜÛÝ

HÂÈYHH]\Ø\×Ç&ö÷CÒBµñÉ±ÁÑ¡}½É}Í±Ä¤(lµ}Á­}É½½ÐtñðÉÑÕÉ¸À(Á­}É½½Ñ}Í}Á­|root" || return 0
  apk_list_append APK_FALLBACK_ROOTS "$_apk_ÛÝB\×Û[Ý[Ý[\ØØ\J
HÂ[	É\ÉÈHÙY	ÜË×ÈÙÎÈË×LK×ÙÎÈË×LÍ×ÙÉÂB\×ØYÝ\Ù\ÝY]ÜÊ
HÂØ\×ÝZYIBØ\×ÛYYXWÜÛÝIÓQQPWÔÓÕKÙ]KÛYYX_BØ\×ÜXX×ÜÛÝIÐRVWÔPP×ÓQQPWÔÓÕKÜÝÜYÙKÙ[][]YB\×ØYÜÛÝØ\×ÛYYXWÜÛÝÉØ\×ÝZY\×ØYÙ[XÚ×ÜÛÝØ\×ÜXX×ÜÛÝÉØ\×ÝZYÜØ\×ÝY]È[Û[Ü[[YKÙY][Ù[][]YÉØ\×ÝZYÛ[Ü[[YKÜXYÙ[][]YÉØ\×ÝZYÛ[Ü[[YKÝÜ]KÙ[][]YÉØ\×ÝZYÛ[Ü[[YKÙ[Ù[][]YÉØ\×ÝZYÛ[Ú[Ý[\ÉØ\×ÝZYÙ[][]YÉØ\×ÝZYÛ[Ø[ÚYÜ]XKÉØ\×ÝZYÙ[][]YÉØ\×ÝZYÛ[Ü\Ü×ÝÝYÚÉØ\×ÝZYÙ[][]YÉØ\×ÝZYÛ[Ý\Ù\ÉØ\×ÝZYÜ[X\HÂ\×ØYÙ[XÚ×ÜÛÝØ\×ÝY]ÈÛBB\×Ù\ØÛÝ\Ü[[YWÜÛÝÊ
HÂØ\×ÜÙY[Ý\Ù\ÏBÜØ\×Ý\Ù\\[ÓQQPWÔÓÕKÙ]KÛYYX_HÖÌNWJÐRVWÔPP×ÓQQPWÔÓÕKÜÝÜYÙKÙ[][]YHÖÌNWJÈÂÈYØ\×Ý\Ù\\HÛÛ[YBØ\×ÝZYI×Ø\×Ý\Ù\\ÈÊßBØ\ÙHØ\×ÝZY[	ÉÈ
ÈLNWJHÛÛ[YHÎÈ\ØXÂØ\ÙHØ\×ÜÙY[Ý\Ù\Â[
Ø\×ÝZYHÛÛ[YHÎÈ\ØXÂ\×Û\ÝØ\[Ø\×ÜÙY[Ý\Ù\ÈØ\×ÝZY\×ÆFE÷W6W%÷fWw2"Eöµ÷VB ¢FöæP ¢öµö7W'&VçE÷W6W#ÒB¢6ÖB7FfGvWBÖ7W'&VçB×W6W"#âöFWböçVÆÂÇÂÒvWBÖ7W'&VçB×W6W"#âöFWböçVÆÂÇÂG'VRÀ¢G"Ö6BsÓÆârÂVBÖâ¢¢66R"Eöµö7W'&VçE÷W6W""ârwÂ¥²ÓÒ¢³²¢¢µöFE÷W6W%÷fWw2"Eöµö7W'&VçE÷W6W" ¢³°¢W60 ¢b²×"÷&ö2÷6VÆböÖ÷VçFæfòÓ²FVà¢vÆRe3Ò&VB×"öµöÖ÷VçEöÆæRÇÂ²Öâ"EöµöÖ÷VçEöÆæR"Ó²Fð¢öµöÖ÷VçE÷öçCÒB&çFbrW5Æâr"EöµöÖ÷VçEöÆæR"Âv²w·&çBCWÒr¢²Öâ"EöµöÖ÷VçE÷öçB"ÒÇÂ6öçFçVP¢öµöÖ÷VçE÷öçCÒBµöÖ÷VçE÷VæW66R"EöµöÖ÷VçE÷öçB"¢66R"EöµöÖ÷VçE÷öçB"à¢÷7F÷&vRöV×VÆFVBõ³ÓÒ§ÂöÖçB÷'VçFÖRò¢öV×VÆFVBõ³ÓÒ§ÂöÖçBöç7FÆÆW"õ³ÓÒ¢öV×VÆFVBõ³ÓÒ§ÂöÖçBöæG&öGw&F&ÆRõ³ÓÒ¢öV×VÆFVBõ³ÓÒ§ÂöÖçB÷75÷F&÷Vvõ³ÓÒ¢öV×VÆFVBõ³ÓÒ§ÂöÖçB÷W6W"õ³ÓÒ¢÷&Ö'¢µñ}±±­}É½½Ð}Á­}µ½Õ¹Ñ}Á½¥¹Ð(ìì(½µ¹Ð½µ¥}ÉÜ¼¨¤(Á­}}É½½Ð}Á­}µ½Õ¹Ñ}Á½¥¹Ð(}Á­}ÕÕ¥ôí}Á­}µ½Õ¹Ñ}Á½¥¹Ð¨½ô(Á­}}±±­}É½½Ð½ÍÑ½É¼}Á­}ÕÕ¥(ìì(½ÍÑ½É¼¨¤(}Á­}¹µôí}Á­}µ½Õ¹Ñ}Á½¥¹Ð¨½ô(Í}Á­}¹µ¥¸µÕ±ÑñÍ±ñ¹}µÕ±ÑñÉÕ¹Ñ¥µ¤½¹Ñ¥¹ÕììÍ(Á­|add_fallback_root "$_apk_mount_point"
          ;;
      esac
    done </proc/self/mountinfo
  fi
}

apk_load_roots() {
  APK_ROOTS=
  APK_FALLBACK_ROOTS=
  APK_PRIVATE_BOUNDARIES=
  apk_discover_runtime_roots
  apk_add_root /data/local/tmp

  if [ -n "${BAIZE_EXTRA_STORAGE_ROOTS:-}" ]; then
    _apk_old_ifs=$IFS
    IFS=:
    for _apk_volume in $BAIZE_EXTRA_STORAGE_ROOTS; do
      apk_YÜÛÝØ\×ÝÛ[YHÛBQÏIØ\×ÛÛÚYÂ[ÙBÜØ\×ÝÛ[YH[Û[ÛYYXWÜËÊÜÝÜYÙKÊÈÂÈYØ\×ÝÛ[YHHÛÛ[YBØ\×Û[YOI×Ø\×ÝÛ[YHÈÊßBØ\ÙHØ\×Û[YH[[][]YÙ[[×Ù[][]Y[[YJHÛÛ[YHÎÈ\ØXÂØ\ÙHØ\×ÝÛ[YH[Û[ÛYYXWÜËÊH\×ØYÜÛÝØ\×ÝÛ[YHÎÈ
H\×ØYÙ[XÚ×ÜÛÝØ\×ÝÛ[YHÎÈ\ØXÂÛBBB\×Ù[Ú[Ê
HÂØ\×Ø\ÙOIBØ\×ÛÝ]IØ\×ÛÝ]YÈ^ÜÞ\Ý[KØ[ÝÞXÞNÈ[ÜÞ\Ý[KØ[ÝÞXÞ[Ø\×Ø\ÙH]\H
Z[[YH	Ê\ÉÈ[ÈZ[[YH	Ê\ÜÉÈ[ÈZ[[YH	Ê\ÉÈ[ÈZ[[YH	Ê\ÛIÈ[ÈZ[[YH	ÊXXÈ
H\[Ø\×ÛÝ]Ù]Û[[ÙB[Ø\×Æ&6R"×GRbÀ¢ÂÖæÖRr¢æ²rÖòÖæÖRr¢æ·2rÖòÖæÖRr¢ç²rÖòÖæÖRr¢æ¶ÒrÖòÖæÖRr¢æ"rÂÀ¢×&çCâ"Eöµö÷WB"#âöFWböçVÆÀ¢f§Ð ¦µö''WFVf÷&6Uö6æFFFW2°¢öµö÷WCÒC¢¢â"Eöµö÷WB ¢µöF66÷fW%÷'VçFÖU÷&ö÷G0¢öµ÷6VVå÷&VÃÐ¢öµ÷&ö÷EöæóÓ ¢öµñ½±}¥Ìô%L(%Lô((ÍÐµ(½È}Á­}É½½Ð¥¸I-}I==QLA-}11	-}I==QLì¼(lµ}Á­}É½½Ðtñð½¹Ñ¥¹Õ(}Á­|real=$(apk_X[]ÛÜÜÙ[Ø\×ÜÛÝBØ\ÙHØ\×ÜÙY[ÜX[[
Ø\×ÜX[HÛÛ[YHÎÈ\ØXÂ\×Û\ÝØ\[Ø\×ÜÙY[ÜX[Ø\×Ç&VÂ ¢öµ÷&ö÷EöæóÒBöµ÷&ö÷Eöæò²¢öµ÷F×Ò"Gµöµö÷WGÒç&ö÷BâBBâEöµ÷&ö÷Eöæò ¢µöfæEöçFò"EöµñÉ½½Ð}Á­}ÑµÀñðÑÉÕ(lµÌ}Á­}ÑµÀtñðÐ}Á­}ÑµÀøø}Á­}½ÕÐ(É´µ}Á­}ÑµÀ(½¹(ÍÐ­(%Lô}Á­}½±}¥Ì((¥lµ¸íA-}AI%YQ}	=U9I%LèµôtìÑ¡¸(}Á­}ÁÉ¥ÙÑ}ÑµÀôí}Á­}½ÕÑô¹ÁÉ¥ÙÑ¸(Á­}½±±Ñ}ÁÉ¥ÙÑ}¹¥ÑÌ}Á­}ÁÉ¥ÙÑ}ÑµÀ(lµÌ}Á­}ÁÉ¥ÙÑ}ÑµÀtñðÐ}Á­}ÁÉ¥ÙÑ}ÑµÀøø}Á­}½ÕÐ(É´µ}Á­}ÁÉ¥ÙÑ}ÑµÀ(¤)ô(