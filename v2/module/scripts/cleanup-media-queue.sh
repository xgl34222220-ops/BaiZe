#!/system/bin/sh
# Source-only producer; independent from organizer-media-scan. NUL is the wire format.
baize_cleanup_media_init() {
  [ "${BAIZE_CLEANUP_MEDIA_INITIALIZED:-0}" = 1 ] && return 0
  BAIZE_CLEANUP_MEDIA_STATE=${BAIZE_ROOT_STATE_DIR:-${BAIZE_STATE_DIR:-${STATE_DIR:-/data/adb/baize-v2}}}
  BAIZE_CLEANUP_MEDIA_QUEUE="$BAIZE_CLEANUP_MEDIA_STATE/cleanup-media"
  BAIZE_CLEANUP_MEDIA_USER=unknown
  _bcm_user=$(cmd activity get-current-user 2>/dev/null) && {
    case "$_bcm_user" in ''|*[!0-9]*) ;; *) BAIZE_CLEANUP_MEDIA_USER=$_bcm_user ;; esac
  }
  # Freeze this task's removable-storage user. Never guess a different user at retry.
  BAIZE_CLEANUP_MEDIA_INITIALIZED=1
  BAIZE_CLEANUP_MEDIA_BATCH=
  BAIZE_CLEANUP_MEDIA_UNCONFIRMED=0
}
baize_cleanup_media_begin() {
  baize_cleanup_media_init
  [ -z "${BAIZE_CLEANUP_MEDIA_BATCH:-}" ] || baize_cleanup_media_publish || return 1
  (umask 077; mkdir -p "$BAIZE_CLEANUP_MEDIA_QUEUE") || return 1
  _bcm_id=$(cat /proc/sys/kernel/random/uuid 2>/dev/null) || return 1
  case "$_bcm_id" in ''|*[!a-zA-Z0-9-]*) return 1 ;; esac
  BAIZE_CLEANUP_MEDIA_BATCH="$BAIZE_CLEANUP_MEDIA_QUEUE/.building-$_bcm_id"
  (umask 077; mkdir "$BAIZE_CLEANUP_MEDIA_BATCH") || { BAIZE_CLEANUP_MEDIA_BATCH=; return 1; }
  _bcm_ticks=$(awk '{print $22}' "/proc/$$/stat" 2>/dev/null) || _bcm_ticks=unknown
  printf '%s\n%s\n' "$$" "$_bcm_ticks" >"$BAIZE_CLEANUP_MEDIA_BATCH/owner" &&
    printf '%s\n' "$BAIZE_CLEANUP_MEDIA_USER" >"$BAIZE_CLEANUP_MEDIA_BATCH/user" &&
    : >"$BAIZE_CLEANUP_MEDIA_BATCH/paths.nul" || { BAIZE_CLEANUP_MEDIA_UNCONFIRMED=1; return 1; }
  BAIZE_CLEANUP_DELETED_NUL="$BAIZE_CLEANUP_MEDIA_BATCH/paths.nul"
}
baize_cleanup_media_publish() {
  [ -n "${BAIZE_CLEANUP_MEDIA_BATCH:-}" ] || return 0
  [ ! -f "$BAIZE_CLEANUP_MEDIA_BATCH/importing" ] || return 1
  # Atomic visibility across process death. Shell cannot promise file+directory fsync.
  _bcm_pending="$BAIZE_CLEANUP_MEDIA_QUEUE/pending-${BAIZE_CLEANUP_MEDIA_BATCH##*/.building-}"
  if mv "$BAIZE_CLEANUP_MEDIA_BATCH" "$_bcm_pending"; then
    BAIZE_CLEANUP_MEDIA_BATCH=
    return 0
  fi
  BAIZE_CLEANUP_MEDIA_UNCONFIRMED=1
  echo '已删除文件的媒体索引刷新未确认；记录保留待恢复' >&2
  return 1
}
baize_cleanup_media_import() {
  [ -f "$1" ] || return 1
  [ -s "$1" ] || return 0
  baize_cleanup_media_begin || { BAIZE_CLEANUP_MEDIA_UNCONFIRMED=1; return 1; }
  # A partial import is not a producer's completed deletion stream.
  : >"$BAIZE_CLEANUP_MEDIA_BATCH/importing" || return 1
  if ! cat "$1" >"$BAIZE_CLEANUP_DELETED_NUL"; then BAIZE_CLEANUP_MEDIA_UNCONFIRMED=1; return 1; fi
  rm -f "$BAIZE_CLEANUP_MEDIA_BATCH/importing" || return 1
  baize_cleanup_media_publish
}
baize_cleanup_media_kick() {
  [ -f "${SCRIPTDIR:-}/cleanup-media-worker.sh" ] || return 0
  BAIZE_ROOT_STATE_DIR="${BAIZE_CLEANUP_MEDIA_STATE:-${BAIZE_ROOT_STATE_DIR:-${STATE_DIR:-/data/adb/baize-v2}}}" \
    sh "$SCRIPTDIR/cleanup-media-worker.sh" >/dev/null 2>&1 &
}
