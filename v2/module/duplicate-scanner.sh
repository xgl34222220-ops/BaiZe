#!/system/bin/sh
set -eu
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
INDEX="$STATE_DIR/index/storage-files.nul"
OUT="$STATE_DIR/reports/duplicates.tsv"
mkdir -p "${OUT%/*}"
TMP="$STATE_DIR/index/duplicates.$$"
mkdir -p "$TMP"
printf 'group\tsize\tsha256\toriginal\tduplicate\n' >"$OUT"
# Size grouping first, then full hash only for collisions. No deletion is performed.
while IFS= read -r -d '' file; do
  [ -f "$file" ] && [ ! -L "$file" ] || continue
  size=$(stat -c %s "$file" 2>/dev/null || echo 0)
  [ "$size" -gt 0 ] || continue
  printf '%s\t%s\n' "$size" "$file"
done <"$INDEX" | sort -n >"$TMP/sizes.tsv"
awk -F '\t' '{c[$1]++} END{for(k in c)if(c[k]>1)print k}' "$TMP/sizes.tsv" >"$TMP/collisions"
group=0
while IFS= read -r size; do
  group=$((group+1)); : >"$TMP/hashes.tsv"
  awk -F '\t' -v s="$size" '$1==s{print substr($0,index($0,$2))}' "$TMP/sizes.tsv" | while IFS= read -r file; do hash=$(sha256sum "$file" 2>/dev/null | awk '{print $1}'); [ -n "$hash" ] && printf '%s\t%s\n' "$hash" "$file"; done | sort >"$TMP/hashes.tsv"
  awk -F '\t' -v g="$group" -v s="$size" 'BEGIN{OFS="\t"}{if($1==last){print g,s,$1,first,$2}else{last=$1;first=$2}}' "$TMP/hashes.tsv" >>"$OUT"
done <"$TMP/collisions"
rm -rf "$TMP"
echo "$OUT"
