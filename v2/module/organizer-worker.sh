#!/system/bin/sh
set -u

MODDIR=${0%/*}
MODE=${1:-organize}
TRIGGER=${2:-app}
TASK_ID=${3:-$(date +%s)-$$}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
SHELL_BIN=${BAIZE_SHELL_BIN:-/system/bin/sh}
INDEXER="$MODDIR/storage-index.sh"
ALL_INDEX="$STATE_DIR/index/storage-files.nul"
ORGANIZER_INDEX="$STATE_DIR/index/organizer-files.nul"
INDEX_FILE="$ORGANIZER_INDEX"
RUNNING_FILE="$STATE_DIR/running.env"
RESULT_FILE="$STATE_DIR/organizer-result.env"
UNDO_FILE="$STATE_DIR/organizer-last.json"
UNDO_DIR="$STATE_DIR/organizer-undo"
WORKER_FILE="$STATE_DIR/worker.env"
STOP_FILE="$STATE_DIR/stop"
LOCK_DIR="$STATE_DIR/run.lock"
LOG_FILE="$STATE_DIR/logs/organizer-$TASK_ID.log"
MEDIA_QUEUE="$STATE_DIR/media-scan-$TASK_ID.nul"

[ "$MODE" = organize ] || { echo "不支持的归类模式：$MODE" >&2; exit 2; }
[ -f "$INDEXER" ] || { echo "共享索引脚本缺失：$INDEXER" >&2; exit 5; }

config_value() { sed -n "s/^$1=//p" "$STATE_DIR/config.conf" 2>/dev/null | tail -n 1; }
uint_config() {
  uc_value=$(config_value "$1"); uc_default=$2; uc_min=$3; uc_max=$4
  case "$uc_value" in ''|*[!0-9]*) uc_value=$uc_default ;; esac
  [ "$uc_value" -lt "$uc_min" ] && uc_value=$uc_min
  [ "$uc_value" -gt "$uc_max" ] && uc_value=$uc_max
  echo "$uc_value"
}
proc_start_ticks() { [ -r "/proc/$1/stat" ] && awk '{print $22}' "/proc/$1/stat" 2>/dev/null || echo 0; }
lock_alive() {
  la_pid=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
  la_ticks=$(sed -n '1p' "$LOCK_DIR/start_ticks" 2>/dev/null)
  case "$la_pid" in ''|*[!0-9]*) return 1 ;; esac
  [ "$la_pid" -gt 1 ] && kill -0 "$la_pid" 2>/dev/null || return 1
  current_ticks=$(proc_start_ticks "$la_pid")
  case "$la_ticks" in ''|*[!0-9]*) la_ticks=0 ;; esac
  [ "$la_ticks" -eq 0 ] || [ "$current_ticks" = "$la_ticks" ] || return 1
  cmdline=$(tr '\000' ' ' <"/proc/$la_pid/cmdline" 2>/dev/null)
  case "$cmdline" in *organizer-worker.sh*|*worker-runner.sh*|*cleaner.sh*|*task-worker.sh*) return 0 ;; esac
  return 1
}

mkdir -p "$STATE_DIR/logs" "$UNDO_DIR"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  if lock_alive; then
    echo "已有扫描、清理或归类任务正在运行" >&2
    exit 3
  fi
  rm -rf -- "$LOCK_DIR" 2>/dev/null || true
  mkdir "$LOCK_DIR" 2>/dev/null || { echo "无法创建任务锁" >&2; exit 3; }
fi
printf '%s\n' "$$" >"$LOCK_DIR/pid"
proc_start_ticks $$ >"$LOCK_DIR/start_ticks"
printf '%s\n' "$TASK_ID" >"$LOCK_DIR/task_id"

sanitize_env() { printf '%s' "$1" | tr '\r\n' '  '; }
LAST_PROGRESS_EPOCH=0
LAST_PROGRESS_CURRENT=-1
write_running() {
  wr_phase=$1; wr_current=$2; wr_total=$3; wr_path=${4:-}; wr_force=${5:-0}
  wr_now=$(date +%s)
  if [ "$wr_force" != 1 ] && [ "$wr_current" -ne 0 ] && [ $((wr_current - LAST_PROGRESS_CURRENT)) -lt 25 ] && [ $((wr_now - LAST_PROGRESS_EPOCH)) -lt 1 ]; then
    return 0
  fi
  LAST_PROGRESS_EPOCH=$wr_now; LAST_PROGRESS_CURRENT=$wr_current
  wr_tmp="$RUNNING_FILE.tmp.$$"
  {
    echo "mode=organize"
    echo "operation=module-organize"
    echo "phase=$(sanitize_env "$wr_phase")"
    echo "progress_current=$wr_current"
    echo "progress_total=$wr_total"
    echo "current_path=$(sanitize_env "$wr_path")"
    echo "task_id=$TASK_ID"
    echo "trigger=$TRIGGER"
    echo "worker=detached-root-shell-v2.4"
    echo "started=$STARTED"
    echo "heartbeat=$wr_now"
  } >"$wr_tmp" && mv -f "$wr_tmp" "$RUNNING_FILE"
  chmod 0600 "$RUNNING_FILE" 2>/dev/null || true
}

write_result() {
  wr_phase=$1; wr_success=$2; wr_cancelled=$3; wr_requested=$4; wr_moved=$5; wr_skipped=$6; wr_failed=$7; wr_bytes=$8
  wr_tmp="$RESULT_FILE.tmp.$$"
  wr_undo_count=$(find "$UNDO_DIR" -maxdepth 1 -type f -name '*.json' 2>/dev/null | wc -l | tr -d ' ')
  case "$wr_undo_count" in ''|*[!0-9]*) wr_undo_count=0 ;; esac
  {
    echo "mode=organize"
    echo "operation=module-organize"
    echo "phase=$(sanitize_env "$wr_phase")"
    echo "success=$wr_success"
    echo "completed=1"
    echo "cancelled=$wr_cancelled"
    echo "requested=$wr_requested"
    echo "moved=$wr_moved"
    echo "skipped=$wr_skipped"
    echo "failed=$wr_failed"
    echo "renamed=$RENAMED"
    echo "deduplicated=$DEDUPLICATED"
    echo "bytes=$wr_bytes"
    echo "conflictPolicy=$CONFLICT_POLICY"
    echo "undoAvailable=$([ "$wr_undo_count" -gt 0 ] && echo true || echo false)"
    echo "undoCount=$wr_undo_count"
    echo "task_id=$TASK_ID"
    echo "trigger=$TRIGGER"
    echo "worker=detached-root-shell-v2.4"
    echo "completed_epoch=$(date +%s)"
  } >"$wr_tmp" && mv -f "$wr_tmp" "$RESULT_FILE"
  chmod 0600 "$RESULT_FILE" 2>/dev/null || true
}

cleanup() {
  rm -f "$RUNNING_FILE" "$MEDIA_QUEUE" 2>/dev/null || true
  if [ -f "$WORKER_FILE" ] && grep -q "^task_id=$TASK_ID$" "$WORKER_FILE" 2>/dev/null; then rm -f "$WORKER_FILE"; fi
  if [ -f "$LOCK_DIR/task_id" ] && [ "$(sed -n '1p' "$LOCK_DIR/task_id" 2>/dev/null)" = "$TASK_ID" ]; then rm -rf -- "$LOCK_DIR" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM

append_tree_files() {
  at_root=$1
  [ -d "$at_root" ] || return 0
  find "$at_root" -xdev -mindepth 1 -maxdepth 12 \
    \( -type d \( \
      -iname cache -o -iname code_cache -o -iname no_backup -o \
      -iname databases -o -iname shared_prefs -o -iname lib -o \
      -iname tmp -o -iname temp -o -iname thumbnails -o -iname .thumbnails -o \
      -iname stickers -o -iname emoji -o -iname crash -o -iname crashes -o \
      -iname assets -o -iname resources -o -iname res -o -iname textures -o \
      -iname sprites -o -iname shaders -o -iname bundles -o -iname streamingassets \
    \) -prune \) -o \( -type f -print0 \) 2>/dev/null >>"$INDEX_FILE"
}

append_known_app_roots() {
  user_root=$1
  for candidate in \
    "$user_root/Android/data/com.tencent.mobileqq" \
    "$user_root/Android/data/com.tencent.tim" \
    "$user_root/Android/data/com.android.chrome" \
    "$user_root/Android/data/com.chrome.beta" \
    "$user_root/Android/data/com.chrome.dev" \
    "$user_root/Android/data/org.mozilla.firefox" \
    "$user_root/Android/data/org.mozilla.fenix" \
    "$user_root/Android/data/com.microsoft.emmx" \
    "$user_root/Android/data/com.sec.android.app.sbrowser" \
    "$user_root/Android/data/com.heytap.browser" \
    "$user_root/Android/data/com.coloros.browser" \
    "$user_root/Android/data/com.oplus.browser" \
    "$user_root/Android/data/com.mi.globalbrowser" \
    "$user_root/Android/data/com.android.browser" \
    "$user_root/Android/data/com.quark.browser" \
    "$user_root/Android/data/com.UCMobile" \
    "$user_root/Android/data/com.kiwibrowser.browser" \
    "$user_root/Android/data/com.brave.browser" \
    "$user_root/Android/data/com.google.android.gm" \
    "$user_root/Android/data/com.tencent.androidqqmail" \
    "$user_root/Android/data/com.microsoft.office.outlook" \
    "$user_root/Android/data/com.android.email" \
    "$user_root/Android/data/com.netease.mail" \
    "$user_root/Android/data/com.netease.mobimail" \
    "$user_root/Android/media/org.telegram.messenger" \
    "$user_root/Android/media/org.telegram.messenger.web" \
    "$user_root/Android/media/tw.nekomimi.nekogram" \
    "$user_root/Android/media/nu.gpu.nagram" \
    "$user_root/Android/media/nu.gpu.nagramx"; do
    append_tree_files "$candidate"
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
    append_known_app_roots "$fb_user_root"
  done
  chmod 0600 "$INDEX_FILE" 2>/dev/null || true
}

b64() {
  if command -v base64 >/dev/null 2>&1; then
    base64 | tr -d '\r\n'
  elif command -v toybox >/dev/null 2>&1; then
    toybox base64 | tr -d '\r\n'
  else
    return 1
  fi
}

category_for() {
  name=${1##*/}
  ext=${name##*.}
  [ "$ext" = "$name" ] && ext=""
  ext=$(printf '%s' "$ext" | tr '[:upper:]' '[:lower:]')
  case "$ext" in
    jpg|jpeg|png|gif|webp|bmp|heic|heif|avif|dng|raw) echo 图片 ;;
    mp4|mkv|mov|avi|webm|flv|wmv|m4v|3gp|ts) echo 视频 ;;
    mp3|flac|wav|m4a|aac|ogg|opus|ape|wma|amr) echo 音频 ;;
    pdf|doc|docx|xls|xlsx|ppt|pptx|txt|rtf|csv|md|odt|ods|odp) echo 文档 ;;
    apk|apks|xapk|apkm|aab) echo 安装包 ;;
    zip|rar|7z|tar|gz|bz2|xz|zst|tgz|tbz2) echo 压缩包 ;;
    epub|mobi|azw|azw3|fb2|cbz|cbr|djvu) echo 电子书 ;;
    *) echo "" ;;
  esac
}

normalized_path() {
  printf '/%s/' "$1" | tr '[:upper:]' '[:lower:]' | tr '. -' '___'
}

is_suspicious_app_resource() {
  normalized=$(normalized_path "$1")
  case "$normalized" in
    */assets/*|*/asset/*|*/resources/*|*/resource/*|*/res/*|*/textures/*|*/texture/*|*/sprites/*|*/sprite/*|*/atlases/*|*/atlas/*|*/shaders/*|*/shader/*|*/bundles/*|*/bundle/*|*/streamingassets/*|*/addressables/*|*/unitycache/*|*/unity/*|*/ue4game/*|*/unreal/*|*/cocos/*|*/il2cpp/*|*/gameassets/*|*/game_resources/*|*/levels/*|*/level/*|*/maps/*|*/map/*|*/skins/*|*/skin/*|*/icons/*|*/icon/*) return 0 ;;
  esac
  return 1
}

is_browser_package() {
  case "$1" in
    com.android.chrome|com.chrome.beta|com.chrome.dev|com.google.android.apps.chrome|org.mozilla.firefox|org.mozilla.fenix|com.microsoft.emmx|com.sec.android.app.sbrowser|com.heytap.browser|com.coloros.browser|com.oplus.browser|com.mi.globalbrowser|com.android.browser|com.quark.browser|com.UCMobile|com.kiwibrowser.browser|com.brave.browser) return 0 ;;
  esac
  return 1
}

is_mail_package() {
  case "$1" in
    com.google.android.gm|com.tencent.androidqqmail|com.microsoft.office.outlook|com.android.email|com.netease.mail|com.netease.mobimail) return 0 ;;
  esac
  return 1
}

is_telegram_package() {
  case "$1" in
    org.telegram.messenger|org.telegram.messenger.web|tw.nekomimi.nekogram|nu.gpu.nagram|nu.gpu.nagramx) return 0 ;;
  esac
  return 1
}

allowed_app_source() {
  package=$1 tail=$2 root_kind=$3
  is_suspicious_app_resource "$tail" && return 1

  case "$package:$tail" in
    com.tencent.mobileqq:Tencent/QQfile_recv/*|com.tencent.mobileqq:files/QQfile_recv/*|com.tencent.tim:Tencent/TIMfile_recv/*|com.tencent.tim:files/TIMfile_recv/*) return 0 ;;
  esac

  if is_browser_package "$package"; then
    case "$tail" in
      Download/*|Downloads/*|files/Download/*|files/Downloads/*|files/download/*|files/downloads/*|private/received/*|files/received/*) return 0 ;;
    esac
  fi

  if is_mail_package "$package"; then
    case "$tail" in
      attachments/*|Attachments/*|files/attachments/*|files/Attachments/*|data/attachments/*|files/download/*|files/Download/*) return 0 ;;
    esac
  fi

  if [ "$root_kind" = media ] && is_telegram_package "$package"; then
    case "$tail" in
      Telegram/Telegram\ Documents/*|Telegram/Telegram\ Images/*|Telegram/Telegram\ Video/*|Telegram/Telegram\ Audio/*|Telegram/Telegram\ Files/*|Nagram/Nagram\ Documents/*|Nagram/Nagram\ Images/*|Nagram/Nagram\ Video/*|Nagram/Nagram\ Audio/*|NagramX/NagramX\ Documents/*|NagramX/NagramX\ Images/*|NagramX/NagramX\ Video/*|NagramX/NagramX\ Audio/*) return 0 ;;
    esac
  fi

  return 1
}

is_public_user_path() {
  case "$1" in
    Download/*|Downloads/*|Documents/*|Bluetooth/*|Tencent/QQfile_recv/*|Tencent/TIMfile_recv/*) return 0 ;;
    Telegram/Telegram\ Documents/*|Telegram/Telegram\ Images/*|Telegram/Telegram\ Video/*|Telegram/Telegram\ Audio/*|Telegram/Telegram\ Files/*) return 0 ;;
    Nagram/Nagram\ Documents/*|Nagram/Nagram\ Images/*|Nagram/Nagram\ Video/*|Nagram/Nagram\ Audio/*) return 0 ;;
    NagramX/NagramX\ Documents/*|NagramX/NagramX\ Images/*|NagramX/NagramX\ Video/*|NagramX/NagramX\ Audio/*) return 0 ;;
  esac
  return 1
}

allowed_source() {
  path=$1 category=$2
  case "$path" in "$MEDIA_ROOT"/[0-9]/*) ;; *) return 1 ;; esac
  relative=${path#"$MEDIA_ROOT"/}
  user=${relative%%/*}
  rest=${relative#*/}
  [ "$rest" != "$relative" ] || return 1
  case "$rest" in BaiZe归类/*|*/BaiZe归类/*) return 1 ;; esac
  case "$rest" in */*) ;; *) return 0 ;; esac

  case "$rest" in
    Android/data/*/*)
      app_part=${rest#Android/data/}
      package=${app_part%%/*}
      tail=${app_part#*/}
      allowed_app_source "$package" "$tail" data
      return $?
      ;;
    Android/media/*/*)
      app_part=${rest#Android/media/}
      package=${app_part%%/*}
      tail=${app_part#*/}
      allowed_app_source "$package" "$tail" media
      return $?
      ;;
    Android/*)
      return 1
      ;;
  esac

  is_public_user_path "$rest"
}

skip_file() {
  name=$(printf '%s' "${1##*/}" | tr '[:upper:]' '[:lower:]')
  case "$name" in
    .nomedia|.*) return 0 ;;
    *.lock|*.lck|*.db|*.sqlite|*.sqlite3|*-wal|*-shm|*.journal|*.part|*.partial|*.crdownload|*.download|*.tmp|*.temp) return 0 ;;
    *.bytes|*.vfs|*.blob|*.bin|*.dat|*.pak|*.obb|*.bundle|*.asset|*.cache|*.idx|*.index|*.dex|*.odex|*.vdex|*.so) return 0 ;;
  esac
  stem=${name%.*}
  case "$stem" in
    *[!0-9a-f]*) ;;
    *) [ "${#stem}" -ge 24 ] && return 0 ;;
  esac
  return 1
}

STARTED=$(date +%s)
CONFLICT_POLICY=$(uint_config organizer_conflict_policy 1 0 2)
UNDO_RETENTION=$(uint_config organizer_undo_retention 10 1 20)
MEDIA_SCAN=$(uint_config organizer_media_scan 1 0 1)
RENAMED=0
DEDUPLICATED=0
rm -f "$STOP_FILE" "$RESULT_FILE" "$MEDIA_QUEUE"
write_running "正在建立增量共享索引" 0 0 "" 1

if ! BAIZE_STATE_DIR="$STATE_DIR" BAIZE_MEDIA_ROOT="$MEDIA_ROOT" "$SHELL_BIN" "$INDEXER" ensure organizer-detached >>"$LOG_FILE" 2>&1; then
  echo "共享索引失败，切换独立 Root 安全兜底索引" >>"$LOG_FILE"
fi
[ -s "$ORGANIZER_INDEX" ] && INDEX_FILE="$ORGANIZER_INDEX" || INDEX_FILE="$ALL_INDEX"
if [ ! -s "$INDEX_FILE" ]; then
  INDEX_FILE="$ALL_INDEX"
  write_running "共享索引为空，正在执行安全兜底发现" 0 0 "$MEDIA_ROOT" 1
  build_fallback_index
fi
if [ ! -s "$INDEX_FILE" ]; then
  write_result "没有需要归类的新文件" 1 0 0 0 0 0 0
  exit 0
fi

TOTAL=$(tr '\000' '\n' <"$INDEX_FILE" 2>/dev/null | wc -l | tr -d ' ')
case "$TOTAL" in ''|*[!0-9]*) TOTAL=0 ;; esac
REQUESTED=0; MOVED=0; SKIPPED=0; FAILED=0; BYTES=0; CURRENT=0
UNDO_TMP="$UNDO_DIR/.${TASK_ID}.tmp.$$"
FIRST_MOVE=1
printf '{"createdAt":%s,"taskId":"%s","trigger":"%s","moves":[' "$(date +%s)000" "$TASK_ID" "$(sanitize_env "$TRIGGER")" >"$UNDO_TMP"

file_sha256() { sha256sum "$1" 2>/dev/null | awk '{print $1}'; }
unique_destination() {
  ud_dir=$1; ud_name=$2
  case "$ud_name" in
    *.*) ud_ext=.${ud_name##*.}; ud_stem=${ud_name%$ud_ext} ;;
    *) ud_ext=; ud_stem=$ud_name ;;
  esac
  ud_n=1
  while [ "$ud_n" -le 999 ]; do
    ud_candidate="$ud_dir/$ud_stem ($ud_n)$ud_ext"
    [ -e "$ud_candidate" ] || { printf '%s\n' "$ud_candidate"; return 0; }
    ud_n=$((ud_n + 1))
  done
  return 1
}
resolve_destination() {
  rd_source=$1; rd_planned=$2
  COLLISION_ACTION=none
  [ -e "$rd_planned" ] || { RESOLVED_DEST=$rd_planned; return 0; }
  case "$CONFLICT_POLICY" in
    0) COLLISION_ACTION=skipped; return 1 ;;
    2)
      rd_ss=$(stat -c %s "$rd_source" 2>/dev/null || echo -1)
      rd_ds=$(stat -c %s "$rd_planned" 2>/dev/null || echo -2)
      if [ "$rd_ss" = "$rd_ds" ] && [ "$rd_ss" -ge 0 ] 2>/dev/null; then
        rd_sh=$(file_sha256 "$rd_source"); rd_dh=$(file_sha256 "$rd_planned")
        if [ -n "$rd_sh" ] && [ "$rd_sh" = "$rd_dh" ]; then COLLISION_ACTION=deduplicated; return 2; fi
      fi
      ;;
  esac
  RESOLVED_DEST=$(unique_destination "${rd_planned%/*}" "${rd_planned##*/}") || { COLLISION_ACTION=skipped; return 1; }
  COLLISION_ACTION=renamed
  return 0
}
queue_media_scan() { [ "$MEDIA_SCAN" = 1 ] && { printf '%s\0' "$1" >>"$MEDIA_QUEUE"; } || true; }

while IFS= read -r -d '' FILE_PATH; do
  CURRENT=$((CURRENT + 1))
  [ -f "$STOP_FILE" ] && break
  [ -f "$FILE_PATH" ] || { SKIPPED=$((SKIPPED + 1)); continue; }
  [ ! -L "$FILE_PATH" ] || { SKIPPED=$((SKIPPED + 1)); continue; }
  skip_file "$FILE_PATH" && { SKIPPED=$((SKIPPED + 1)); continue; }
  CATEGORY=$(category_for "$FILE_PATH")
  [ -n "$CATEGORY" ] || { SKIPPED=$((SKIPPED + 1)); continue; }
  allowed_source "$FILE_PATH" "$CATEGORY" || continue
  REQUESTED=$((REQUESTED + 1))
  write_running "正在归类 $CATEGORY" "$CURRENT" "$TOTAL" "$FILE_PATH"

  RELATIVE=${FILE_PATH#"$MEDIA_ROOT"/}; USER_ID=${RELATIVE%%/*}
  case "$USER_ID" in ''|*[!0-9]*) SKIPPED=$((SKIPPED + 1)); continue ;; esac
  NAME=${FILE_PATH##*/}; DEST_DIR="$MEDIA_ROOT/$USER_ID/BaiZe归类/$CATEGORY"; PLANNED_DEST="$DEST_DIR/$NAME"
  mkdir -p "$DEST_DIR" 2>/dev/null || { FAILED=$((FAILED + 1)); continue; }
  resolve_destination "$FILE_PATH" "$PLANNED_DEST"; rd_code=$?
  case "$rd_code" in
    1) SKIPPED=$((SKIPPED + 1)); continue ;;
    2) DEDUPLICATED=$((DEDUPLICATED + 1)); SKIPPED=$((SKIPPED + 1)); continue ;;
  esac
  DEST=$RESOLVED_DEST
  [ "$COLLISION_ACTION" = renamed ] && RENAMED=$((RENAMED + 1))

  SIZE=$(stat -c %s "$FILE_PATH" 2>/dev/null || echo 0)
  SRC_UID=$(stat -c %u "$FILE_PATH" 2>/dev/null || echo 0)
  SRC_GID=$(stat -c %g "$FILE_PATH" 2>/dev/null || echo 0)
  SRC_MODE_OCT=$(stat -c %a "$FILE_PATH" 2>/dev/null || echo 644)
  case "$SIZE" in ''|*[!0-9]*) SIZE=0 ;; esac
  case "$SRC_UID" in ''|*[!0-9]*) SRC_UID=0 ;; esac
  case "$SRC_GID" in ''|*[!0-9]*) SRC_GID=0 ;; esac
  case "$SRC_MODE_OCT" in ''|*[!0-7]*) SRC_MODE_OCT=644 ;; esac
  SRC_MODE=$(printf '%d' "0$SRC_MODE_OCT" 2>/dev/null || echo 420)
  ROOT_UID=$(stat -c %u "$MEDIA_ROOT/$USER_ID" 2>/dev/null || echo 0)
  ROOT_GID=$(stat -c %g "$MEDIA_ROOT/$USER_ID" 2>/dev/null || echo 0)

  if mv "$FILE_PATH" "$DEST" 2>>"$LOG_FILE"; then
    chown "$ROOT_UID:$ROOT_GID" "$DEST_DIR" "$DEST" 2>/dev/null || true
    chmod 0770 "$DEST_DIR" 2>/dev/null || true
    chmod 0660 "$DEST" 2>/dev/null || true
    DEST_FP=$(stat -c '%d:%i:%s:%Y' "$DEST" 2>/dev/null || echo "")
    SRC_B64=$(printf '%s' "$FILE_PATH" | b64 2>/dev/null || echo "")
    DEST_B64=$(printf '%s' "$DEST" | b64 2>/dev/null || echo "")
    if [ -n "$SRC_B64" ] && [ -n "$DEST_B64" ]; then
      [ "$FIRST_MOVE" -eq 1 ] || printf ',' >>"$UNDO_TMP"
      FIRST_MOVE=0
      printf '{"sourceB64":"%s","destinationB64":"%s","destinationFingerprint":"%s","sourceUid":%s,"sourceGid":%s,"sourceMode":%s,"collisionAction":"%s"}' \
        "$SRC_B64" "$DEST_B64" "$DEST_FP" "$SRC_UID" "$SRC_GID" "$SRC_MODE" "$COLLISION_ACTION" >>"$UNDO_TMP"
    fi
    queue_media_scan "$FILE_PATH"; queue_media_scan "$DEST"
    MOVED=$((MOVED + 1)); BYTES=$((BYTES + SIZE))
  else
    FAILED=$((FAILED + 1))
  fi
done <"$INDEX_FILE"

printf ']}' >>"$UNDO_TMP"
if [ "$MOVED" -gt 0 ]; then
  UNDO_RECORD="$UNDO_DIR/$(date +%s)000-${TASK_ID}.json"
  mv -f "$UNDO_TMP" "$UNDO_RECORD"
  cp -f "$UNDO_RECORD" "$UNDO_FILE"
  chmod 0600 "$UNDO_RECORD" "$UNDO_FILE" 2>/dev/null || true
  ls -1t "$UNDO_DIR"/*.json 2>/dev/null | awk -v keep="$UNDO_RETENTION" 'NR>keep {print}' | while IFS= read -r old; do rm -f -- "$old"; done
else
  rm -f "$UNDO_TMP"
fi

if [ "$MEDIA_SCAN" = 1 ] && [ -s "$MEDIA_QUEUE" ] && command -v am >/dev/null 2>&1; then
  while IFS= read -r -d '' media_path; do
    media_user=${media_path#"$MEDIA_ROOT"/}; media_user=${media_user%%/*}; case "$media_user" in ''|*[!0-9]*) media_user=0 ;; esac
    am broadcast --user "$media_user" -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$media_path" >/dev/null 2>&1 || true
  done <"$MEDIA_QUEUE"
fi

write_running "文件归类收尾中" "$TOTAL" "$TOTAL" "" 1
if [ -f "$STOP_FILE" ]; then write_result "文件归类已停止" 0 1 "$REQUESTED" "$MOVED" "$SKIPPED" "$FAILED" "$BYTES"; exit 9; fi
if [ "$FAILED" -gt 0 ]; then write_result "文件归类完成，但有 $FAILED 个文件失败" 0 0 "$REQUESTED" "$MOVED" "$SKIPPED" "$FAILED" "$BYTES"; exit 1; fi
write_result "文件归类完成" 1 0 "$REQUESTED" "$MOVED" "$SKIPPED" 0 "$BYTES"
echo "独立 Root 安全归类完成：移动 $MOVED 个，重命名 $RENAMED 个，重复 $DEDUPLICATED 个，失败 $FAILED 个" >>"$LOG_FILE"
exit 0
