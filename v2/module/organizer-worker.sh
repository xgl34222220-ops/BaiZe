#!/system/bin/sh
set -u

MODDIR=${0%/*}
MODE=${1:-organize}
TRIGGER=${2:-app}
TASK_ID=${3:-$(date +%s)-$$}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
INDEXER="$MODDIR/storage-index.sh"
INDEX_FILE="$STATE_DIR/index/storage-files.nul"
RUNNING_FILE="$STATE_DIR/running.env"
RESULT_FILE="$STATE_DIR/organizer-result.env"
UNDO_FILE="$STATE_DIR/organizer-last.json"
WORKER_FILE="$STATE_DIR/worker.env"
STOP_FILE="$STATE_DIR/stop"
LOCK_DIR="$STATE_DIR/run.lock"
LOG_FILE="$STATE_DIR/logs/organizer-$TASK_ID.log"

[ "$MODE" = organize ] || { echo "不支持的归类模式：$MODE" >&2; exit 2; }
[ -f "$INDEXER" ] || { echo "共享索引脚本缺失：$INDEXER" >&2; exit 5; }

mkdir -p "$STATE_DIR/logs"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  OLD_PID=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
  case "$OLD_PID" in ''|*[!0-9]*) OLD_PID=0 ;; esac
  if [ "$OLD_PID" -gt 1 ] && kill -0 "$OLD_PID" 2>/dev/null; then
    echo "已有扫描、清理或归类任务正在运行" >&2
    exit 3
  fi
  rm -rf -- "$LOCK_DIR" 2>/dev/null || true
  mkdir "$LOCK_DIR" 2>/dev/null || { echo "无法创建任务锁" >&2; exit 3; }
fi
echo $$ >"$LOCK_DIR/pid"

sanitize_env() { printf '%s' "$1" | tr '\r\n' '  '; }
write_running() {
  phase=$1 current=$2 total=$3 current_path=${4:-}
  tmp="$RUNNING_FILE.tmp.$$"
  {
    echo "mode=organize"
    echo "operation=module-organize"
    echo "phase=$(sanitize_env "$phase")"
    echo "progress_current=$current"
    echo "progress_total=$total"
    echo "current_path=$(sanitize_env "$current_path")"
    echo "task_id=$TASK_ID"
    echo "trigger=$TRIGGER"
    echo "worker=detached-root-shell"
    echo "started=$STARTED"
  } >"$tmp"
  mv -f "$tmp" "$RUNNING_FILE"
  chmod 0600 "$RUNNING_FILE" 2>/dev/null || true
}

write_result() {
  phase=$1 success=$2 cancelled=$3 requested=$4 moved=$5 skipped=$6 failed=$7 bytes=$8
  tmp="$RESULT_FILE.tmp.$$"
  {
    echo "mode=organize"
    echo "operation=module-organize"
    echo "phase=$(sanitize_env "$phase")"
    echo "success=$success"
    echo "completed=1"
    echo "cancelled=$cancelled"
    echo "requested=$requested"
    echo "moved=$moved"
    echo "skipped=$skipped"
    echo "failed=$failed"
    echo "bytes=$bytes"
    echo "undoAvailable=$([ "$moved" -gt 0 ] && echo true || echo false)"
    echo "task_id=$TASK_ID"
    echo "trigger=$TRIGGER"
    echo "worker=detached-root-shell"
    echo "completed_epoch=$(date +%s)"
  } >"$tmp"
  mv -f "$tmp" "$RESULT_FILE"
  chmod 0600 "$RESULT_FILE" 2>/dev/null || true
}

cleanup() {
  rm -f "$RUNNING_FILE" 2>/dev/null || true
  if [ -f "$WORKER_FILE" ] && grep -q "^task_id=$TASK_ID$" "$WORKER_FILE" 2>/dev/null; then
    rm -f "$WORKER_FILE" 2>/dev/null || true
  fi
  rm -rf -- "$LOCK_DIR" 2>/dev/null || true
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
      -iname stickers -o -iname emoji -o -iname crash -o -iname crashes \
    \) -prune \) -o \( -type f -print0 \) 2>/dev/null >>"$INDEX_FILE"
}

build_fallback_index() {
  mkdir -p "${INDEX_FILE%/*}"
  : >"$INDEX_FILE"
  for fb_user_root in "$MEDIA_ROOT"/[0-9]*; do
    [ -d "$fb_user_root" ] || continue
    find "$fb_user_root" -xdev -mindepth 1 -maxdepth 1 -type f -print0 2>/dev/null >>"$INDEX_FILE"
    for fb_pkg in "$fb_user_root"/Android/data/* "$fb_user_root"/Android/media/*; do
      append_tree_files "$fb_pkg"
    done
    for fb_public in "$fb_user_root"/*; do
      [ -d "$fb_public" ] || continue
      case "${fb_public##*/}" in Android|BaiZe归类|LOST.DIR) continue ;; esac
      append_tree_files "$fb_public"
    done
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
    pdf|doc|docx|xls|xlsx|ppt|pptx|txt|rtf|csv|md|odt|ods|odp|log|json|xml|yaml|yml|conf|ini) echo 文档 ;;
    apk|apks|xapk|apkm|aab) echo 安装包 ;;
    zip|rar|7z|tar|gz|bz2|xz|zst|tgz|tbz2) echo 压缩包 ;;
    epub|mobi|azw|azw3|fb2|cbz|cbr|djvu) echo 电子书 ;;
    *) echo 其他 ;;
  esac
}

has_user_directory_segment() {
  normalized=$(printf '/%s/' "$1" | tr '[:upper:]' '[:lower:]' | tr '. -' '___')
  case "$normalized" in
    */download/*|*/downloads/*|*/downloaded/*|*/下载/*|*/received/*|*/receive/*|*/recv/*|*/file_recv/*|*/qqfile_recv/*|*/qqmy_file_recv/*|*/qqfile_receive/*|*/timfile_recv/*|*/tim_file_recv/*|*/attachment/*|*/attachments/*|*/export/*|*/exports/*|*/saved/*|*/shared/*|*/document/*|*/documents/*|*/transfer/*|*/transfers/*|*/offline/*|*/telegram/*|*/telegram_documents/*|*/telegram_files/*|*/nagram/*|*/nagramx/*) return 0 ;;
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
  has_user_directory_segment "$rest" && return 0
  case "$rest" in
    Android/media/*/*|Android/data/*/files/*)
      [ "$category" != 其他 ] && return 0
      ;;
  esac
  return 1
}

skip_file() {
  name=$(printf '%s' "${1##*/}" | tr '[:upper:]' '[:lower:]')
  case "$name" in
    .nomedia|.*) return 0 ;;
    *.lock|*.lck|*.db|*.sqlite|*.sqlite3|*-wal|*-shm|*.journal|*.part|*.partial|*.crdownload|*.download|*.tmp|*.temp) return 0 ;;
  esac
  return 1
}

STARTED=$(date +%s)
rm -f "$STOP_FILE" "$RESULT_FILE"
write_running "正在由独立 Root Worker 建立全应用索引" 0 0 ""

if ! BAIZE_STATE_DIR="$STATE_DIR" BAIZE_MEDIA_ROOT="$MEDIA_ROOT" /system/bin/sh "$INDEXER" refresh organizer-detached >>"$LOG_FILE" 2>&1; then
  echo "共享索引失败，切换独立 Root 兜底索引" >>"$LOG_FILE"
fi
if [ ! -s "$INDEX_FILE" ]; then
  write_running "共享索引为空，正在执行独立 Root 兜底发现" 0 0 "$MEDIA_ROOT"
  build_fallback_index
fi
if [ ! -s "$INDEX_FILE" ]; then
  write_result "没有需要归类的新文件" 1 0 0 0 0 0 0
  exit 0
fi

TOTAL=$(tr '\000' '\n' <"$INDEX_FILE" 2>/dev/null | wc -l | tr -d ' ')
case "$TOTAL" in ''|*[!0-9]*) TOTAL=0 ;; esac
REQUESTED=0
MOVED=0
SKIPPED=0
FAILED=0
BYTES=0
CURRENT=0
UNDO_TMP="$UNDO_FILE.tmp.$$"
FIRST_MOVE=1
printf '{"createdAt":%s,"moves":[' "$(date +%s)000" >"$UNDO_TMP"

while IFS= read -r -d '' FILE_PATH; do
  CURRENT=$((CURRENT + 1))
  if [ -f "$STOP_FILE" ]; then break; fi
  [ -f "$FILE_PATH" ] || { SKIPPED=$((SKIPPED + 1)); continue; }
  [ ! -L "$FILE_PATH" ] || { SKIPPED=$((SKIPPED + 1)); continue; }
  skip_file "$FILE_PATH" && { SKIPPED=$((SKIPPED + 1)); continue; }

  CATEGORY=$(category_for "$FILE_PATH")
  allowed_source "$FILE_PATH" "$CATEGORY" || continue
  REQUESTED=$((REQUESTED + 1))
  write_running "正在归类 $CATEGORY" "$CURRENT" "$TOTAL" "$FILE_PATH"

  RELATIVE=${FILE_PATH#"$MEDIA_ROOT"/}
  USER_ID=${RELATIVE%%/*}
  case "$USER_ID" in ''|*[!0-9]*) SKIPPED=$((SKIPPED + 1)); continue ;; esac
  NAME=${FILE_PATH##*/}
  DEST_DIR="$MEDIA_ROOT/$USER_ID/BaiZe归类/$CATEGORY"
  DEST="$DEST_DIR/$NAME"
  [ ! -e "$DEST" ] || { SKIPPED=$((SKIPPED + 1)); continue; }

  SIZE=$(stat -c %s "$FILE_PATH" 2>/dev/null)
  SRC_UID=$(stat -c %u "$FILE_PATH" 2>/dev/null)
  SRC_GID=$(stat -c %g "$FILE_PATH" 2>/dev/null)
  SRC_MODE_OCT=$(stat -c %a "$FILE_PATH" 2>/dev/null)
  case "$SIZE" in ''|*[!0-9]*) SIZE=0 ;; esac
  case "$SRC_UID" in ''|*[!0-9]*) SRC_UID=0 ;; esac
  case "$SRC_GID" in ''|*[!0-9]*) SRC_GID=0 ;; esac
  case "$SRC_MODE_OCT" in ''|*[!0-7]*) SRC_MODE_OCT=644 ;; esac
  SRC_MODE=$(printf '%d' "0$SRC_MODE_OCT" 2>/dev/null || echo 420)

  mkdir -p "$DEST_DIR" 2>/dev/null || { FAILED=$((FAILED + 1)); continue; }
  ROOT_UID=$(stat -c %u "$MEDIA_ROOT/$USER_ID" 2>/dev/null)
  ROOT_GID=$(stat -c %g "$MEDIA_ROOT/$USER_ID" 2>/dev/null)
  case "$ROOT_UID" in ''|*[!0-9]*) ROOT_UID=0 ;; esac
  case "$ROOT_GID" in ''|*[!0-9]*) ROOT_GID=0 ;; esac

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
      printf '{"sourceB64":"%s","destinationB64":"%s","destinationFingerprint":"%s","sourceUid":%s,"sourceGid":%s,"sourceMode":%s}' \
        "$SRC_B64" "$DEST_B64" "$DEST_FP" "$SRC_UID" "$SRC_GID" "$SRC_MODE" >>"$UNDO_TMP"
    fi
    MOVED=$((MOVED + 1))
    BYTES=$((BYTES + SIZE))
  else
    FAILED=$((FAILED + 1))
  fi
done <"$INDEX_FILE"

printf ']}' >>"$UNDO_TMP"
if [ "$MOVED" -gt 0 ]; then
  mv -f "$UNDO_TMP" "$UNDO_FILE"
  chmod 0600 "$UNDO_FILE" 2>/dev/null || true
else
  rm -f "$UNDO_TMP" "$UNDO_FILE"
fi

if [ -f "$STOP_FILE" ]; then
  write_result "文件归类已停止" 0 1 "$REQUESTED" "$MOVED" "$SKIPPED" "$FAILED" "$BYTES"
  exit 9
fi
if [ "$FAILED" -gt 0 ]; then
  write_result "文件归类完成，但有 $FAILED 个文件失败" 0 0 "$REQUESTED" "$MOVED" "$SKIPPED" "$FAILED" "$BYTES"
  exit 1
fi
write_result "文件归类完成" 1 0 "$REQUESTED" "$MOVED" "$SKIPPED" 0 "$BYTES"
echo "独立 Root 文件归类完成：移动 $MOVED 个，跳过 $SKIPPED 个，失败 $FAILED 个" >>"$LOG_FILE"
exit 0
