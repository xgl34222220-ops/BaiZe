#!/system/bin/sh
set -u

# $0 不含斜杠时 ${0%/*} 会原样返回脚本名，这里显式兜底
case "$0" in */*) MODDIR=${0%/*} ;; *) MODDIR=. ;; esac
MODE=${1:-organize}
TRIGGER=${2:-app}
TASK_ID=${3:-$(date +%s)-$$}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
SHELL_BIN=${BAIZE_SHELL_BIN:-/system/bin/sh}
INDEXER="$MODDIR/storage-index.sh"
ALL_INDEX="$STATE_DIR/index/storage-files.nul"
ORGANIZER_INDEX="$STATE_DIR/index/organizer-files.nul"
# 兜底索引必须写自己的文件。此前 build_fallback_index 直接往 $INDEX_FILE 写，
# 而自动模式把 INDEX_FILE 指向了共享索引，等于定时归类会把
# organizer-files.nul 覆盖成自己的兜底清单；两者过滤规则不同，
# 于是 TTL 内的下一次手动归类行为会随上次定时任务而变化。
FALLBACK_INDEX="$STATE_DIR/index/organizer-fallback.nul"
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
limit_value() {
  lv_value=$1; lv_default=$2; lv_min=$3; lv_max=$4
  case "$lv_value" in ''|*[!0-9]*) lv_value=$lv_default ;; esac
  [ "$lv_value" -lt "$lv_min" ] && lv_value=$lv_min
  [ "$lv_value" -gt "$lv_max" ] && lv_value=$lv_max
  echo "$lv_value"
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
    echo "renamed=${RENAMED:-0}"
    echo "deduplicated=${DEDUPLICATED:-0}"
    echo "bytes=$wr_bytes"
    echo "conflictPolicy=${CONFLICT_POLICY:-1}"
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
  rm -f "$RUNNING_FILE" "${UNDO_RAW:-}" 2>/dev/null || true
  # 队列已经持久化到 pending/spool 时本地临时文件可删；若两条持久化路径都
  # 失败，则保留 media-scan-*.nul，RootService 只会在它老化后作为 orphan 恢复。
  [ "${MEDIA_SCAN_PRESERVE_LOCAL:-0}" = 1 ] || rm -f "$MEDIA_QUEUE" 2>/dev/null || true
  if [ -f "$WORKER_FILE" ] && grep -q "^task_id=$TASK_ID$" "$WORKER_FILE" 2>/dev/null; then rm -f "$WORKER_FILE"; fi
  if [ -f "$LOCK_DIR/task_id" ] && [ "$(sed -n '1p' "$LOCK_DIR/task_id" 2>/dev/null)" = "$TASK_ID" ]; then rm -rf -- "$LOCK_DIR" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM

append_tree_files() {
  at_root=$1
  [ -d "$at_root" ] || return 0
  if [ "${AUTO_TRIGGER:-0}" = 1 ] && command -v timeout >/dev/null 2>&1; then
    timeout "${AUTO_ROOT_SCAN_SECONDS:-20}" find "$at_root" -xdev -mindepth 1 -maxdepth 12       \( -type d \(         -iname cache -o -iname code_cache -o -iname no_backup -o         -iname databases -o -iname shared_prefs -o -iname lib -o         -iname tmp -o -iname temp -o -iname thumbnails -o -iname .thumbnails -o         -iname stickers -o -iname emoji -o -iname crash -o -iname crashes -o         -iname assets -o -iname resources -o -iname res -o -iname textures -o         -iname sprites -o -iname shaders -o -iname bundles -o -iname streamingassets       \) -prune \) -o \( -type f -print0 \) 2>/dev/null >>"$INDEX_FILE" || true
  else
    find "$at_root" -xdev -mindepth 1 -maxdepth 12       \( -type d \(         -iname cache -o -iname code_cache -o -iname no_backup -o         -iname databases -o -iname shared_prefs -o -iname lib -o         -iname tmp -o -iname temp -o -iname thumbnails -o -iname .thumbnails -o         -iname stickers -o -iname emoji -o -iname crash -o -iname crashes -o         -iname assets -o -iname resources -o -iname res -o -iname textures -o         -iname sprites -o -iname shaders -o -iname bundles -o -iname streamingassets       \) -prune \) -o \( -type f -print0 \) 2>/dev/null >>"$INDEX_FILE"
  fi
}

append_known_app_roots() {
  user_root=$1
  for candidate in     "$user_root/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv"     "$user_root/Android/data/com.tencent.mobileqq/files/QQfile_recv"     "$user_root/Android/data/com.tencent.tim/Tencent/TIMfile_recv"     "$user_root/Android/data/com.tencent.tim/files/TIMfile_recv"; do
    append_tree_files "$candidate"
  done
  for package in     com.android.chrome com.chrome.beta com.chrome.dev org.mozilla.firefox org.mozilla.fenix     com.microsoft.emmx com.sec.android.app.sbrowser com.heytap.browser com.coloros.browser     com.oplus.browser com.mi.globalbrowser com.android.browser com.quark.browser com.UCMobile     com.kiwibrowser.browser com.brave.browser; do
    package_root="$user_root/Android/data/$package"
    for suffix in Download Downloads files/Download files/Downloads files/download files/downloads private/received files/received; do
      append_tree_files "$package_root/$suffix"
    done
  done
  for package in com.google.android.gm com.tencent.androidqqmail com.microsoft.office.outlook com.android.email com.netease.mail com.netease.mobimail; do
    package_root="$user_root/Android/data/$package"
    for suffix in attachments Attachments files/attachments files/Attachments data/attachments files/download files/Download; do
      append_tree_files "$package_root/$suffix"
    done
  done
}

build_fallback_index() {
  INDEX_FILE="$FALLBACK_INDEX"
  mkdir -p "${INDEX_FILE%/*}"
  : >"$INDEX_FILE"
  for fb_user_root in "$MEDIA_ROOT"/[0-9]*; do
    [ -d "$fb_user_root" ] || continue
    find "$fb_user_root" -xdev -mindepth 1 -maxdepth 1 -type f -print0 2>/dev/null >>"$INDEX_FILE"
    for fb_public in       "$fb_user_root/Download" "$fb_user_root/Downloads" "$fb_user_root/Documents"       "$fb_user_root/Bluetooth" "$fb_user_root/Tencent/QQfile_recv" "$fb_user_root/Tencent/TIMfile_recv"; do
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

# 分类表从 config/organizer-categories.conf 载入，与索引侧共用同一份来源。
# 载入结果放进 BAIZE_CAT_MAP，形如 " jpg=图片 png=图片 mp4=视频 ..."，
# 匹配时用 case 做子串查找，全程零子进程。
BAIZE_CAT_MAP=" "
BAIZE_CAT_EXTS=""
organizer_categories_load() {
  ocl_file=${1:-$MODDIR/config/organizer-categories.conf}
  BAIZE_CAT_MAP=" "
  BAIZE_CAT_EXTS=""
  [ -f "$ocl_file" ] || return 1
  while IFS= read -r ocl_raw || [ -n "$ocl_raw" ]; do
    case "$ocl_raw" in ''|'#'*) continue ;; esac
    case "$ocl_raw" in *=*) ;; *) continue ;; esac
    ocl_name=${ocl_raw%%=*}
    ocl_list=${ocl_raw#*=}
    [ -n "$ocl_name" ] || continue
    for ocl_ext in $ocl_list; do
      [ -n "$ocl_ext" ] || continue
      BAIZE_CAT_MAP="$BAIZE_CAT_MAP$ocl_ext=$ocl_name "
      BAIZE_CAT_EXTS="$BAIZE_CAT_EXTS$ocl_ext "
    done
  done <"$ocl_file"
  [ "$BAIZE_CAT_MAP" != " " ]
}

# 结果写入全局 CATEGORY，不再用 $( ) 起子 shell。
# 扩展名转小写此前用 printf | tr（每个文件两次 fork），
# 现在用 shell 的字符替换逐字符处理，零 fork。
# 结果写入全局 OL_OUT。注意不能用 $( ) 取返回值——命令替换本身就是一次 fork，
# 那正是这里要消除的开销。
organizer_lower() {
  ol_in=$1
  OL_OUT=""
  ol_out=""
  while [ -n "$ol_in" ]; do
    ol_c=${ol_in%"${ol_in#?}"}
    case "$ol_c" in
      A) ol_c=a ;; B) ol_c=b ;; C) ol_c=c ;; D) ol_c=d ;; E) ol_c=e ;; F) ol_c=f ;;
      G) ol_c=g ;; H) ol_c=h ;; I) ol_c=i ;; J) ol_c=j ;; K) ol_c=k ;; L) ol_c=l ;;
      M) ol_c=m ;; N) ol_c=n ;; O) ol_c=o ;; P) ol_c=p ;; Q) ol_c=q ;; R) ol_c=r ;;
      S) ol_c=s ;; T) ol_c=t ;; U) ol_c=u ;; V) ol_c=v ;; W) ol_c=w ;; X) ol_c=x ;;
      Y) ol_c=y ;; Z) ol_c=z ;;
    esac
    ol_out="$ol_out$ol_c"
    ol_in=${ol_in#?}
  done
  OL_OUT=$ol_out
}

category_for() {
  CATEGORY=
  cf_name=${1##*/}
  cf_ext=${cf_name##*.}
  [ "$cf_ext" = "$cf_name" ] && return 0
  # 绝大多数扩展名本来就是小写，含大写时才走逐字符转换
  case "$cf_ext" in
    *[A-Z]*) organizer_lower "$cf_ext"; cf_ext=$OL_OUT ;;
  esac
  case "$BAIZE_CAT_MAP" in
    *" $cf_ext="*)
      cf_rest=${BAIZE_CAT_MAP#*" $cf_ext="}
      CATEGORY=${cf_rest%% *}
      ;;
  esac
  return 0
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
  # 此前这里写的是 "$MEDIA_ROOT"/[0-9]/*，[0-9] 只匹配一个字符，
  # 于是工作资料（user 10）和多用户（10 起编号）的文件全部被拒绝——
  # 它们进了索引、被遍历，最后在这一步被静默丢弃，只计入 skipped。
  # 索引侧用的是 [0-9]*，Kotlin 侧用的是 \d+，两边本来就是对的。
  relative=${path#"$MEDIA_ROOT"/}
  [ "$relative" != "$path" ] || return 1
  user=${relative%%/*}
  case "$user" in ''|*[!0-9]*) return 1 ;; esac
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

# 分类表必须在主循环前载入；载入失败直接退出，避免"扫了但一个都没归类"。
ORGANIZER_CATEGORIES=${BAIZE_ORGANIZER_CATEGORIES:-$MODDIR/config/organizer-categories.conf}
if ! organizer_categories_load "$ORGANIZER_CATEGORIES"; then
  write_result "归类分类表缺失或为空，请重新刷入完整模块" 0 1 0 0 0 0 0
  echo "无法载入 config/organizer-categories.conf" >&2
  exit 5
fi

STARTED=$(date +%s)
CONFLICT_POLICY=$(uint_config organizer_conflict_policy 1 0 2)
UNDO_RETENTION=$(uint_config organizer_undo_retention 10 1 20)
MEDIA_SCAN=$(uint_config organizer_media_scan 1 0 1)
AUTO_TRIGGER=0
case "$TRIGGER" in scheduler:*) AUTO_TRIGGER=1 ;; esac
AUTO_MAX_SECONDS=$(limit_value "${BAIZE_ORGANIZER_AUTO_MAX_SECONDS:-180}" 180 30 1800)
AUTO_MAX_FILES=$(limit_value "${BAIZE_ORGANIZER_AUTO_MAX_FILES:-2000}" 2000 1 20000)
AUTO_ROOT_SCAN_SECONDS=$(limit_value "${BAIZE_ORGANIZER_AUTO_ROOT_SCAN_SECONDS:-20}" 20 2 120)
[ "$AUTO_TRIGGER" = 1 ] && MEDIA_SCAN=0
RENAMED=0
DEDUPLICATED=0
rm -f "$STOP_FILE" "$RESULT_FILE" "$MEDIA_QUEUE"

if [ "$AUTO_TRIGGER" = 1 ]; then
  write_running "正在快速检查下载与接收目录" 0 0 "" 1
  build_fallback_index
else
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
fi
if [ ! -s "$INDEX_FILE" ]; then
  write_result "没有需要归类的新文件" 1 0 0 0 0 0 0
  exit 0
fi

TOTAL=$(tr '\000' '\n' <"$INDEX_FILE" 2>/dev/null | wc -l | tr -d ' ')
case "$TOTAL" in ''|*[!0-9]*) TOTAL=0 ;; esac
REQUESTED=0; MOVED=0; SKIPPED=0; FAILED=0; BYTES=0; CURRENT=0; AUTO_LIMIT_REACHED=0
AUTO_DEADLINE=$((STARTED + AUTO_MAX_SECONDS))
UNDO_TMP="$UNDO_DIR/.${TASK_ID}.tmp.$$"
# 移动过程中先按 NUL 记原始字段，收尾时一次性编码成 JSON。
UNDO_RAW="$UNDO_DIR/.${TASK_ID}.raw.$$"
: >"$UNDO_RAW"
FIRST_MOVE=1
LAST_DEST_DIR=
LAST_USER_ID=
ROOT_UID=0
ROOT_GID=0
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
  if [ "$AUTO_TRIGGER" = 1 ]; then
    auto_now=$(date +%s)
    if [ "$CURRENT" -ge "$AUTO_MAX_FILES" ] || [ "$auto_now" -ge "$AUTO_DEADLINE" ]; then
      AUTO_LIMIT_REACHED=1
      break
    fi
  fi
  CURRENT=$((CURRENT + 1))
  [ -f "$STOP_FILE" ] && break
  if [ $((CURRENT % 200)) -eq 0 ]; then
    write_running "正在检查可归类文件" "$CURRENT" "$TOTAL" "$FILE_PATH"
  fi
  [ -f "$FILE_PATH" ] || { SKIPPED=$((SKIPPED + 1)); continue; }
  [ ! -L "$FILE_PATH" ] || { SKIPPED=$((SKIPPED + 1)); continue; }
  skip_file "$FILE_PATH" && { SKIPPED=$((SKIPPED + 1)); continue; }
  category_for "$FILE_PATH"
  [ -n "$CATEGORY" ] || { SKIPPED=$((SKIPPED + 1)); continue; }
  allowed_source "$FILE_PATH" "$CATEGORY" || continue
  REQUESTED=$((REQUESTED + 1))
  write_running "正在归类 $CATEGORY" "$CURRENT" "$TOTAL" "$FILE_PATH"

  RELATIVE=${FILE_PATH#"$MEDIA_ROOT"/}; USER_ID=${RELATIVE%%/*}
  case "$USER_ID" in ''|*[!0-9]*) SKIPPED=$((SKIPPED + 1)); continue ;; esac
  NAME=${FILE_PATH##*/}; DEST_DIR="$MEDIA_ROOT/$USER_ID/BaiZe归类/$CATEGORY"; PLANNED_DEST="$DEST_DIR/$NAME"
  # 目标目录连续多个文件通常相同，只在变化时建目录并设权限
  if [ "$DEST_DIR" != "$LAST_DEST_DIR" ]; then
    mkdir -p "$DEST_DIR" 2>/dev/null || { FAILED=$((FAILED + 1)); continue; }
    if [ "$USER_ID" != "$LAST_USER_ID" ]; then
      # 目标根的属主是循环不变量，此前每个文件都重新 stat 两次
      ROOT_UID=$(stat -c %u "$MEDIA_ROOT/$USER_ID" 2>/dev/null || echo 0)
      ROOT_GID=$(stat -c %g "$MEDIA_ROOT/$USER_ID" 2>/dev/null || echo 0)
      case "$ROOT_UID" in ''|*[!0-9]*) ROOT_UID=0 ;; esac
      case "$ROOT_GID" in ''|*[!0-9]*) ROOT_GID=0 ;; esac
      LAST_USER_ID=$USER_ID
    fi
    chown "$ROOT_UID:$ROOT_GID" "$DEST_DIR" 2>/dev/null || true
    chmod 0770 "$DEST_DIR" 2>/dev/null || true
    LAST_DEST_DIR=$DEST_DIR
  fi
  resolve_destination "$FILE_PATH" "$PLANNED_DEST"; rd_code=$?
  case "$rd_code" in
    1) SKIPPED=$((SKIPPED + 1)); continue ;;
    2) DEDUPLICATED=$((DEDUPLICATED + 1)); SKIPPED=$((SKIPPED + 1)); continue ;;
  esac
  DEST=$RESOLVED_DEST
  [ "$COLLISION_ACTION" = renamed ] && RENAMED=$((RENAMED + 1))

  # 四个字段一次 stat 取回，取代此前四次独立调用（每次两个进程）。
  SIZE=0; SRC_UID=0; SRC_GID=0; SRC_MODE_OCT=644
  SRC_STAT=$(stat -c '%s %u %g %a' "$FILE_PATH" 2>/dev/null) || SRC_STAT=""
  if [ -n "$SRC_STAT" ]; then
    SIZE=${SRC_STAT%% *};              SRC_STAT=${SRC_STAT#* }
    SRC_UID=${SRC_STAT%% *};           SRC_STAT=${SRC_STAT#* }
    SRC_GID=${SRC_STAT%% *};           SRC_MODE_OCT=${SRC_STAT#* }
  fi
  case "$SIZE" in ''|*[!0-9]*) SIZE=0 ;; esac
  case "$SRC_UID" in ''|*[!0-9]*) SRC_UID=0 ;; esac
  case "$SRC_GID" in ''|*[!0-9]*) SRC_GID=0 ;; esac
  case "$SRC_MODE_OCT" in ''|*[!0-7]*) SRC_MODE_OCT=644 ;; esac
  SRC_MODE=$((8#$SRC_MODE_OCT))

  if mv "$FILE_PATH" "$DEST" 2>>"$LOG_FILE"; then
    chown "$ROOT_UID:$ROOT_GID" "$DEST" 2>/dev/null || true
    chmod 0660 "$DEST" 2>/dev/null || true
    # 指纹必须在移动成功后立刻记录。若拖到全批次结束才 stat，期间文件被别的
    # 应用修改会让撤销记录错误地把“修改后状态”当成归类时状态。路径 base64
    # 仍然留到收尾批处理，因此不会恢复旧版每文件两次 base64|tr 的 fork。
    DEST_FP=$(stat -c '%d:%i:%s:%Y' "$DEST" 2>/dev/null || echo "")
    printf '%s\0%s\0%s\0%s\0%s\0%s\0%s\0' \
      "$FILE_PATH" "$DEST" "$DEST_FP" "$SRC_UID" "$SRC_GID" "$SRC_MODE" "$COLLISION_ACTION" >>"$UNDO_RAW"
    queue_media_scan "$FILE_PATH"; queue_media_scan "$DEST"
    MOVED=$((MOVED + 1)); BYTES=$((BYTES + SIZE))
  else
    FAILED=$((FAILED + 1))
  fi
done <"$INDEX_FILE"

# 把 NUL 中间记录转成与旧版完全一致的 JSON。
# 路径的 base64 在这里一次性完成，取代此前每个文件两次 base64|tr。
if [ -s "$UNDO_RAW" ]; then
  undo_first=1
  while IFS= read -r -d '' u_src && IFS= read -r -d '' u_dst &&
        IFS= read -r -d '' u_fp && IFS= read -r -d '' u_uid &&
        IFS= read -r -d '' u_gid && IFS= read -r -d '' u_mode &&
        IFS= read -r -d '' u_action; do
    u_src_b64=$(printf '%s' "$u_src" | b64 2>/dev/null || echo "")
    u_dst_b64=$(printf '%s' "$u_dst" | b64 2>/dev/null || echo "")
    [ -n "$u_src_b64" ] && [ -n "$u_dst_b64" ] || continue
    [ "$undo_first" -eq 1 ] || printf ',' >>"$UNDO_TMP"
    undo_first=0
    printf '{"sourceB64":"%s","destinationB64":"%s","destinationFingerprint":"%s","sourceUid":%s,"sourceGid":%s,"sourceMode":%s,"collisionAction":"%s"}' \
      "$u_src_b64" "$u_dst_b64" "$u_fp" "$u_uid" "$u_gid" "$u_mode" "$u_action" >>"$UNDO_TMP"
  done <"$UNDO_RAW"
fi
rm -f "$UNDO_RAW"
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

# 媒体刷新队列由 RootService 消费。这里绝不覆盖 pending：拿到短目录锁就追加，
# 锁忙/异常则把本任务队列原子改名成独立 spool，后续 RootService 会合并。
if [ "$MEDIA_SCAN" = 1 ] && [ -s "$MEDIA_QUEUE" ]; then
  MEDIA_SCAN_PENDING="$STATE_DIR/organizer-media-scan.nul"
  MEDIA_SCAN_LOCK="$STATE_DIR/organizer-media-scan.lock"
  media_scan_owned=0
  media_scan_saved=0

  # 崩溃留下的锁只保护毫秒级文件操作，超过 30 秒可视为陈旧。
  if [ -d "$MEDIA_SCAN_LOCK" ]; then
    media_lock_mtime=$(stat -c %Y "$MEDIA_SCAN_LOCK" 2>/dev/null || echo 0)
    media_now=$(date +%s)
    case "$media_lock_mtime" in ''|*[!0-9]*) media_lock_mtime=0 ;; esac
    [ "$media_lock_mtime" -gt 0 ] && [ $((media_now - media_lock_mtime)) -gt 30 ] && rm -rf -- "$MEDIA_SCAN_LOCK" 2>/dev/null || true
  fi

  media_try=0
  while [ "$media_try" -lt 3 ]; do
    if mkdir "$MEDIA_SCAN_LOCK" 2>/dev/null; then media_scan_owned=1; break; fi
    media_try=$((media_try + 1))
    [ "$media_try" -lt 3 ] && sleep 1
  done

  if [ "$media_scan_owned" = 1 ]; then
    if cat "$MEDIA_QUEUE" >>"$MEDIA_SCAN_PENDING" 2>/dev/null; then
      chmod 0600 "$MEDIA_SCAN_PENDING" 2>/dev/null || true
      media_scan_saved=1
    fi
    rmdir "$MEDIA_SCAN_LOCK" 2>/dev/null || true
  fi

  if [ "$media_scan_saved" != 1 ]; then
    MEDIA_SCAN_SPOOL="$STATE_DIR/organizer-media-scan.spool.$(date +%s).$$.nul"
    if mv -f "$MEDIA_QUEUE" "$MEDIA_SCAN_SPOOL" 2>/dev/null; then
      chmod 0600 "$MEDIA_SCAN_SPOOL" 2>/dev/null || true
      media_scan_saved=1
    else
      MEDIA_SCAN_PRESERVE_LOCAL=1
      echo "媒体库刷新队列暂存失败，保留本任务队列供 RootService 延后恢复" >>"$LOG_FILE"
    fi
  fi
fi

if [ "$AUTO_LIMIT_REACHED" = 1 ]; then
  write_running "本轮自动归类达到安全上限，正在保存结果" "$CURRENT" "$TOTAL" "" 1
  write_result "本轮自动归类已完成，剩余文件下次继续" 1 0 "$REQUESTED" "$MOVED" "$SKIPPED" "$FAILED" "$BYTES"
  echo "自动归类达到安全上限：处理 $CURRENT/$TOTAL，移动 $MOVED 个，剩余文件下次继续" >>"$LOG_FILE"
  exit 0
fi
write_running "文件归类收尾中" "$TOTAL" "$TOTAL" "" 1
if [ -f "$STOP_FILE" ]; then write_result "文件归类已停止" 0 1 "$REQUESTED" "$MOVED" "$SKIPPED" "$FAILED" "$BYTES"; exit 9; fi
if [ "$FAILED" -gt 0 ]; then write_result "文件归类完成，但有 $FAILED 个文件失败" 0 0 "$REQUESTED" "$MOVED" "$SKIPPED" "$FAILED" "$BYTES"; exit 1; fi
write_result "文件归类完成" 1 0 "$REQUESTED" "$MOVED" "$SKIPPED" 0 "$BYTES"
echo "独立 Root 安全归类完成：移动 $MOVED 个，重命名 $RENAMED 个，重复 $DEDUPLICATED 个，失败 $FAILED 个" >>"$LOG_FILE"
exit 0
