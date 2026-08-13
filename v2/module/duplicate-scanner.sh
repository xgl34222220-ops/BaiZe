#!/system/bin/sh
set -eu
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
CANDIDATES="$STATE_DIR/index/duplicate-candidates.tsv"
OUT="$STATE_DIR/reports/duplicates.tsv"
CACHE="$STATE_DIR/index/hash-cache.tsv"
CACHE_TTL=${BAIZE_HASH_CACHE_TTL_SECONDS:-1800}
CACHE_MAX_ROWS=${BAIZE_HASH_CACHE_MAX_ROWS:-200000}
case "$CACHE_TTL" in ''|*[!0-9]*) CACHE_TTL=1800 ;; esac
case "$CACHE_MAX_ROWS" in ''|*[!0-9]*) CACHE_MAX_ROWS=200000 ;; esac
[ "$CACHE_TTL" -lt 60 ] && CACHE_TTL=60
[ "$CACHE_TTL" -gt 86400 ] && CACHE_TTL=86400
[ "$CACHE_MAX_ROWS" -lt 1000 ] && CACHE_MAX_ROWS=1000
[ "$CACHE_MAX_ROWS" -gt 500000 ] && CACHE_MAX_ROWS=500000
mkdir -p "${OUT%/*}" "${CACHE%/*}"
[ -s "$CANDIDATES" ] || { echo "共享索引不存在" >&2; exit 6; }
TMP="$STATE_DIR/index/duplicates.$$"
mkdir -p "$TMP"
trap 'rm -rf "$TMP" 2>/dev/null' EXIT INT TERM
printf 'group\tsize\tsha256\tkeeper_b64\tduplicate_b64\treclaimable\n' >"$OUT"
[ -f "$CACHE" ] || : >"$CACHE"
TAB=$(printf '\t')

file_key() { stat -c '%d:%i:%s:%Y:%Z' "$1" 2>/dev/null || return 1; }
cached_field() { awk -F '\t' -v key="$1" '$1==key {value=$2 FS $3 FS $4} END{if(value)print value}' "$CACHE" 2>/dev/null; }
quick_hash() { { head -c 65536 "$1" 2>/dev/null; tail -c 65536 "$1" 2>/dev/null; } | sha256sum | awk '{print $1}'; }

awk -F '\t' '{count[$1]++} END{for(size in count)if(count[size]>1)print size}' "$CANDIDATES" | sort -n >"$TMP/collision-sizes"
: >"$TMP/new-cache"
while IFS= read -r size; do
  : >"$TMP/quick.tsv"
  awk -F '\t' -v size="$size" '$1==size{print $2}' "$CANDIDATES" | while IFS= read -r encoded; do
    raw_path=$(printf '%s' "$encoded" | base64 -d 2>/dev/null; printf '\001')
    file=${raw_path%?}
    [ -f "$file" ] && [ ! -L "$file" ] || continue
    key=$(file_key "$file") || continue
    cached=$(cached_field "$key")
    cached_quick=$(printf '%s\n' "$cached" | awk -F '\t' '{print $1}')
    full=$(printf '%s\n' "$cached" | awk -F '\t' '{print $2}')
    cached_at=$(printf '%s\n' "$cached" | awk -F '\t' '{print $3}')
    quick=$(quick_hash "$file")
    [ -n "$quick" ] || continue
    ctime=${key##*:}
    case "$cached_at:$ctime" in *[!0-9:]*|:|*:) full= ;; *)
      [ "$cached_quick" = "$quick" ] && [ "$ctime" -lt "$cached_at" ] || full=
      ;;
    esac
    printf '%s\t%s\t%s\t%s\n' "$quick" "$encoded" "$key" "$full" >>"$TMP/quick.tsv"
  done
  awk -F '\t' '{count[$1]++} END{for(hash in count)if(count[hash]>1)print hash}' "$TMP/quick.tsv" >"$TMP/quick-collisions"
  : >"$TMP/full.tsv"
  while IFS= read -r quick; do
    awk -F '\t' -v quick="$quick" '$1==quick{print}' "$TMP/quick.tsv" | while IFS="$TAB" read -r q encoded key full; do
      raw_path=$(printf '%s' "$encoded" | base64 -d 2>/dev/null; printf '\001')
      file=${raw_path%?}
      # Read through stdin so GNU sha256sum never escapes the digest line for newline-bearing names.
      [ -n "$full" ] || full=$(sha256sum <"$file" 2>/dev/null | awk '{print $1}')
      [ -n "$full" ] || continue
      printf '%s\t%s\t%s\n' "$full" "$encoded" "$key" >>"$TMP/full.tsv"
      printf '%s\t%s\t%s\t%s\n' "$key" "$q" "$full" "$(date +%s)" >>"$TMP/new-cache"
    done
  done <"$TMP/quick-collisions"
  sort -t "$(printf '\t')" -k1,1 -k2,2 "$TMP/full.tsv" | awk -F '\t' -v size="$size" 'BEGIN{OFS="\t"}
    $1!=last {last=$1; keeper=$2; next}
    {print substr($1,1,20),size,$1,keeper,$2,size}' >>"$OUT"
done <"$TMP/collision-sizes"

cache_now=$(date +%s)
{ cat "$CACHE"; cat "$TMP/new-cache"; } |
  awk -F '\t' -v now="$cache_now" -v ttl="$CACHE_TTL" 'NF>=4 && $4 ~ /^[0-9]+$/ && now-$4<=ttl {row[$1]=$0} END{for(key in row)print row[key]}' |
  sort | tail -n "$CACHE_MAX_ROWS" >"$CACHE.tmp.$$"
mv -f "$CACHE.tmp.$$" "$CACHE"
chmod 0600 "$OUT" "$CACHE" 2>/dev/null || true
echo "$OUT"
