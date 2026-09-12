#!/system/bin/sh
# Read-only duplicate inspection. Reuse index sizes, sample, then verify full hashes.
set -eu
case "$0" in */*) MODDIR=${0%/*} ;; *) MODDIR=. ;; esac
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
SHELL_BIN=${BAIZE_SHELL_BIN:-/system/bin/sh}
OUT="$STATE_DIR/reports/duplicates.tsv"
STOP_FILE="$STATE_DIR/stop"
mkdir -p "${OUT%/*}" "$STATE_DIR/index"
# A fresh generation prevents an earlier scan's size groups hiding new duplicates.
"$SHELL_BIN" "$MODDIR/storage-index.sh" refresh duplicates >&2
TMP="$STATE_DIR/index/duplicates.$$"
mkdir "$TMP"
trap 'rm -rf -- "$TMP"' EXIT
trap 'exit 9' INT TERM
cp "$STATE_DIR/index/duplicate-candidates.tsv" "$TMP/candidates.tsv"
check_stop() { [ ! -f "$STOP_FILE" ] || exit 9; }
decode_path() {
  # Keep a terminal newline in a filename: command substitution alone trims it.
  decoded=$(printf '%s' "$1" | base64 -d; printf '.')
  decoded=${decoded%.}
}
# Adjacent size groups are selected in ONE pass, instead of rescanning the whole
# table once per size. Base64 keeps arbitrary filenames out of the TSV grammar.
sort -n -k1,1 "$TMP/candidates.tsv" | awk -F '\t' '
  $1==size {if(!emitted){print previous; emitted=1} print; next}
  {size=$1; previous=$0; emitted=0}
' >"$TMP/collisions.tsv"
: >"$TMP/samples.tsv"
: >"$TMP/hashes.tsv"
TAB=$(printf '\t')
while IFS="$TAB" read -r size encoded; do
  check_stop
  case "$size" in ''|*[!0-9]*) continue ;; esac
  decode_path "$encoded"; file=$decoded
  [ -f "$file" ] && [ ! -L "$file" ] || continue
  identity=$(stat -c '%d:%i:%s:%y:%z' "$file" 2>/dev/null) || continue
  metadata=${identity#*:*:}; actual_size=${metadata%%:*}
  [ "$actual_size" = "$size" ] || continue
  # Most same-size, different files stop after 64 KiB of I/O.
  head -c 65536 "$file" >"$TMP/sample" || exit 5
  sample_hash=$(sha256sum <"$TMP/sample" | awk '{print $1}')
  [ "$(stat -c '%d:%i:%s:%y:%z' "$file" 2>/dev/null)" = "$identity" ] || continue
  if [ "$size" -le 65536 ]; then
    # The prefix already is the whole file; do not read/hash small files twice.
    printf '%s\t%s\t%s\n' "$size" "$sample_hash" "$encoded" >>"$TMP/hashes.tsv"
  else
    printf '%s\t%s\t%s\t%s\n' "$size" "$sample_hash" "$encoded" "$identity" >>"$TMP/samples.tsv"
  fi
done <"$TMP/collisions.tsv"
# Full-file hashing is limited to matching size AND prefix groups.
sort -t "$TAB" -k1,1n -k2,2 "$TMP/samples.tsv" | awk -F '\t' '
  $1 FS $2==key {if(!emitted){print previous; emitted=1} print; next}
  {key=$1 FS $2; previous=$0; emitted=0}
' >"$TMP/full-candidates.tsv"
while IFS="$TAB" read -r size sample_hash encoded identity; do
  check_stop
  decode_path "$encoded"; file=$decoded
  [ -f "$file" ] && [ ! -L "$file" ] || continue
  [ "$(stat -c '%d:%i:%s:%y:%z' "$file" 2>/dev/null)" = "$identity" ] || continue
  # Hash stdin to avoid sha256sum's filename-escaping prefix for newline paths.
  sha256sum <"$file" >"$TMP/full-hash" || exit 5
  hash=$(awk '{print $1}' "$TMP/full-hash")
  [ "$(stat -c '%d:%i:%s:%y:%z' "$file" 2>/dev/null)" = "$identity" ] || continue
  printf '%s\t%s\t%s\n' "$size" "$hash" "$encoded" >>"$TMP/hashes.tsv"
done <"$TMP/full-candidates.tsv"
sort -t "$TAB" -k1,1n -k2,2 "$TMP/hashes.tsv" | awk -F '\t' 'BEGIN{OFS="\t"}
  $1 FS $2==key {if(!emitted){group++; emitted=1} print group,$1,$2,first,$3; next}
  {key=$1 FS $2; first=$3; emitted=0}
' >"$TMP/pairs.tsv"
printf 'group\tsize\tsha256\toriginal\tduplicate\n' >"$TMP/report.tsv"
while IFS="$TAB" read -r group size hash original duplicate; do
  check_stop
  decode_path "$original"; original_path=$decoded
  decode_path "$duplicate"; duplicate_path=$decoded
  printf '%s\t%s\t%s\t' "$group" "$size" "$hash" >>"$TMP/report.tsv"
  printf '%s' "$original_path" | tr '\t\r\n' '   ' >>"$TMP/report.tsv"
  printf '\t' >>"$TMP/report.tsv"
  printf '%s' "$duplicate_path" | tr '\t\r\n' '   ' >>"$TMP/report.tsv"
  printf '\n' >>"$TMP/report.tsv"
done <"$TMP/pairs.tsv"
check_stop
chmod 0600 "$TMP/report.tsv"
mv -f "$TMP/report.tsv" "$OUT"
echo "$OUT"
