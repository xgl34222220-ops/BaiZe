#!/system/bin/sh
set -eu
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
INDEX="$STATE_DIR/index/storage-files.nul"
OUT="$STATE_DIR/reports/large-files.tsv"
mkdir -p "${OUT%/*}"
MIN_MB=${1:-100}; MIN=$((MIN_MB*1024*1024))
printf 'size\tmtime\tpath_b64\n' >"$OUT"
while IFS= read -r -d '' file; do
  [ -f "$file" ] && [ ! -L "$file" ] || continue
  size=$(stat -c %s "$file" 2>/dev/null || echo 0)
  [ "$size" -ge "$MIN" ] || continue
  mtime=$(stat -c %Y "$file" 2>/dev/null || echo 0)
  encoded=$(printf '%s' "$file" | base64 | tr -d '\n')
  printf '%s\t%s\t%s\n' "$size" "$mtime" "$encoded"
done <"$INDEX" | sort -t "$(printf '\t')" -k1,1nr >>"$OUT"
echo "$OUT"
