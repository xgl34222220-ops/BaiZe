#!/system/bin/sh
set -eu
case "$0" in */*) MODDIR=${0%/*} ;; *) MODDIR=. ;; esac
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
SHELL_BIN=${BAIZE_SHELL_BIN:-/system/bin/sh}
OUT="$STATE_DIR/reports/large-files.tsv"
MIN_MB=${1:-100}
case "$MIN_MB" in ''|*[!0-9]*) echo "大文件阈值必须是正整数 MB" >&2; exit 2 ;; esac
[ "$MIN_MB" -ge 1 ] && [ "$MIN_MB" -le 16384 ] || { echo "大文件阈值超出范围（1–16384 MB）" >&2; exit 2; }
MIN=$((MIN_MB * 1024 * 1024))
mkdir -p "${OUT%/*}"
"$SHELL_BIN" "$MODDIR/storage-index.sh" refresh large-files >&2
TMP="$STATE_DIR/index/large-files.$$"
mkdir "$TMP"
trap 'rm -rf -- "$TMP"' EXIT
trap 'exit 9' INT TERM
# The shared large-files bucket may use a different threshold. Select from the
# unified size table instead, so lowering the screen's threshold never misses files.
awk -F '\t' -v minimum="$MIN" '$1>=minimum{print $2}' "$STATE_DIR/index/duplicate-candidates.tsv" >"$TMP/candidates"
: >"$TMP/rows.tsv"
while IFS= read -r encoded; do
  [ ! -f "$STATE_DIR/stop" ] || exit 9
  file=$(printf '%s' "$encoded" | base64 -d; printf '.'); file=${file%.}
  [ -f "$file" ] && [ ! -L "$file" ] || continue
  metadata=$(stat -c '%s %Y' "$file" 2>/dev/null) || continue
  size=${metadata%% *}; mtime=${metadata#* }
  [ "$size" -ge "$MIN" ] || continue
  printf '%s\t%s\t' "$size" "$mtime" >>"$TMP/rows.tsv"
  printf '%s' "$file" | tr '\t\r\n' '   ' >>"$TMP/rows.tsv"
  printf '\n' >>"$TMP/rows.tsv"
done <"$TMP/candidates"
printf 'size\tmtime\tpath\n' >"$TMP/report.tsv"
sort -t "$(printf '\t')" -k1,1nr "$TMP/rows.tsv" >>"$TMP/report.tsv"
[ ! -f "$STATE_DIR/stop" ] || exit 9
chmod 0600 "$TMP/report.tsv"
mv -f "$TMP/report.tsv" "$OUT"
echo "$OUT"
