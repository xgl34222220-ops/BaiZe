#!/system/bin/sh
set -u

# Single Root-owned lifetime accounting boundary.
# Usage: record-clean-event.sh EVENT_ID MODE BYTES FILES EMPTY_FILES EMPTY_DIRS FRAGMENTS ELAPSED TRIGGER
STATE_DIR=${BAIZE_ROOT_STATE_DIR:-${BAIZE_STATE_DIR:-/data/adb/baize-v2}}
EVENT_ID=${1:-}
MODE=${2:-}
BYTES=${3:-0}
FILES=${4:-0}
EMPTY_FILES=${5:-0}
EMPTY_DIRS=${6:-0}
FRAGMENTS=${7:-0}
ELAPSED=${8:-0}
TRIGGER=${9:-manual}
TOTALS_FILE="$STATE_DIR/totals.env"
EVENTS_FILE="$STATE_DIR/clean-events.tsv"
BASE_FILE="$STATE_DIR/clean-events-base.env"
LOCK_DIR="$STATE_DIR/totals.lock"
SEEN_FILE="$STATE_DIR/clean-event-ids.log"
COMPACT_AT=${BAIZE_EVENT_COMPACT_AT:-2000}
RETAIN_IDS=${BAIZE_EVENT_RETAIN_IDS:-4000}

case "$EVENT_ID" in ''|*[!A-Za-z0-9._:-]*) echo "invalid clean event id" >&2; exit 2 ;; esac
case "$MODE" in ''|*[!A-Za-z0-9._:-]*) echo "invalid clean event mode" >&2; exit 2 ;; esac
for value in "$BYTES" "$FILES" "$EMPTY_FILES" "$EMPTY_DIRS" "$FRAGMENTS" "$ELAPSED"; do
  case "$value" in ''|*[!0-9]*) echo "invalid clean event metric" >&2; exit 2 ;; esac
done

mkdir -p "$STATE_DIR"
proc_start_ticks() { [ -r "/proc/$1/stat" ] && awk '{print $22}' "/proc/$1/stat" 2>/dev/null || echo 0; }
lock_alive() {
  lock_pid=$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null)
  lock_ticks=$(sed -n '1p' "$LOCK_DIR/start_ticks" 2>/dev/null)
  case "$lock_pid" in ''|*[!0-9]*)
    lock_mtime=$(stat -c %Y "$LOCK_DIR" 2>/dev/null || echo 0)
    lock_now=$(date +%s)
    case "$lock_mtime" in ''|*[!0-9]*) lock_mtime=0 ;; esac
    [ $((lock_now - lock_mtime)) -ge 5 ] && return 1
    return 0
    ;;
  esac
  [ "$lock_pid" -gt 1 ] && kill -0 "$lock_pid" 2>/dev/null || return 1
  actual_ticks=$(proc_start_ticks "$lock_pid")
  case "$lock_ticks" in ''|*[!0-9]*) lock_ticks=0 ;; esac
  [ "$lock_ticks" -eq 0 ] || [ "$actual_ticks" = "$lock_ticks" ]
}
attempt=0
while ! mkdir "$LOCK_DIR" 2>/dev/null; do
  if ! lock_alive; then
    rm -rf -- "$LOCK_DIR" 2>/dev/null || true
    continue
  fi
  attempt=$((attempt + 1))
  [ "$attempt" -lt 15 ] || { echo "clean event ledger busy" >&2; exit 3; }
  sleep 1
done
printf '%s\n' "$$" >"$LOCK_DIR/pid"
printf '%s\n' "$(proc_start_ticks $$)" >"$LOCK_DIR/start_ticks"
printf '%s\n' "$(date +%s)" >"$LOCK_DIR/created_epoch"
cleanup() { rm -rf -- "$LOCK_DIR" 2>/dev/null; }
trap cleanup EXIT INT TERM

read_total() {
  key=$1
  value=$(sed -n "s/^$key=//p" "$TOTALS_FILE" 2>/dev/null | tail -n 1)
  case "$value" in ''|*[!0-9]*) value=0 ;; esac
  echo "$value"
}

if [ ! -f "$BASE_FILE" ]; then
  base_tmp="$BASE_FILE.tmp.$$"
  {
    for key in runs regular_files empty_files empty_dirs hidden_items fragment_files bytes elapsed; do
      echo "$key=$(read_total "$key")"
    done
  } >"$base_tmp" && mv -f "$base_tmp" "$BASE_FILE"
fi

already_seen=0
if [ -f "$SEEN_FILE" ] && grep -Fqx -- "$EVENT_ID" "$SEEN_FILE" 2>/dev/null; then already_seen=1; fi
if [ "$already_seen" -eq 0 ] && [ -f "$EVENTS_FILE" ] && awk -F '\t' -v id="$EVENT_ID" '$1 == id { found=1; exit } END { exit !found }' "$EVENTS_FILE"; then already_seen=1; fi
if [ "$already_seen" -eq 0 ]; then
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$EVENT_ID" "$(date +%s)" "$MODE" "$BYTES" "$FILES" "$EMPTY_FILES" "$EMPTY_DIRS" "$FRAGMENTS" "$ELAPSED" "$TRIGGER" >>"$EVENTS_FILE"
  printf '%s\n' "$EVENT_ID" >>"$SEEN_FILE"
fi

base_value() {
  value=$(sed -n "s/^$1=//p" "$BASE_FILE" 2>/dev/null | tail -n 1)
  case "$value" in ''|*[!0-9]*) value=0 ;; esac
  echo "$value"
}
ledger_sums=$(awk -F '\t' '
  NF >= 9 { runs++; bytes += $4; files += $5; empty_files += $6; empty_dirs += $7; fragments += $8; elapsed += $9 }
  END { printf "%.0f %.0f %.0f %.0f %.0f %.0f %.0f\n", runs, files, empty_files, empty_dirs, fragments, bytes, elapsed }
' "$EVENTS_FILE" 2>/dev/null)
set -- $ledger_sums
runs=$(( $(base_value runs) + ${1:-0} ))
regular_files=$(( $(base_value regular_files) + ${2:-0} ))
empty_files=$(( $(base_value empty_files) + ${3:-0} ))
empty_dirs=$(( $(base_value empty_dirs) + ${4:-0} ))
fragment_files=$(( $(base_value fragment_files) + ${5:-0} ))
bytes=$(( $(base_value bytes) + ${6:-0} ))
elapsed=$(( $(base_value elapsed) + ${7:-0} ))
hidden_items=$(base_value hidden_items)

tmp="$TOTALS_FILE.tmp.$$"
{
  echo "runs=$runs"
  echo "regular_files=$regular_files"
  echo "empty_files=$empty_files"
  echo "empty_dirs=$empty_dirs"
  echo "hidden_items=$hidden_items"
  echo "fragment_files=$fragment_files"
  echo "bytes=$bytes"
  echo "elapsed=$elapsed"
  echo "last_time=$(date '+%m-%d %H:%M')"
} >"$tmp" && mv -f "$tmp" "$TOTALS_FILE"

event_count=$(wc -l <"$EVENTS_FILE" 2>/dev/null | tr -d ' ')
case "$event_count" in ''|*[!0-9]*) event_count=0 ;; esac
case "$COMPACT_AT" in ''|*[!0-9]*) COMPACT_AT=2000 ;; esac
case "$RETAIN_IDS" in ''|*[!0-9]*) RETAIN_IDS=4000 ;; esac
if [ "$event_count" -ge "$COMPACT_AT" ]; then
  base_tmp="$BASE_FILE.tmp.$$"
  {
    echo "runs=$runs"; echo "regular_files=$regular_files"; echo "empty_files=$empty_files"
    echo "empty_dirs=$empty_dirs"; echo "hidden_items=$hidden_items"; echo "fragment_files=$fragment_files"
    echo "bytes=$bytes"; echo "elapsed=$elapsed"
  } >"$base_tmp" && mv -f "$base_tmp" "$BASE_FILE"
  : >"$EVENTS_FILE"
  if [ -f "$SEEN_FILE" ]; then
    seen_tmp="$SEEN_FILE.tmp.$$"
    tail -n "$RETAIN_IDS" "$SEEN_FILE" >"$seen_tmp" 2>/dev/null && mv -f "$seen_tmp" "$SEEN_FILE"
  fi
fi
chmod 0600 "$TOTALS_FILE" "$BASE_FILE" "$EVENTS_FILE" "$SEEN_FILE" 2>/dev/null || true

exit 0
