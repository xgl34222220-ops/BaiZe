#!/system/bin/sh
# Aggregate the metadata already collected by the native shared index in one pass.
set -eu
case "$0" in */*) MODDIR=${0%/*} ;; *) MODDIR=. ;; esac
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
SHELL_BIN=${BAIZE_SHELL_BIN:-/system/bin/sh}
OUT="$STATE_DIR/reports/storage-analysis.tsv"
mkdir -p "${OUT%/*}"
"$SHELL_BIN" "$MODDIR/storage-index.sh" refresh storage-analysis >&2
TMP="$STATE_DIR/index/storage-analysis.$$"
mkdir "$TMP"
trap 'rm -rf -- "$TMP"' EXIT
trap 'exit 9' INT TERM
# Decode the index's Base64 path in awk instead of spawning stat/base64 once for
# every file. Size is from the same complete index generation. No Python needed.
LC_ALL=C awk -F '\t' '
function decode(s,    alphabet,out,i,a,b,c,d,v) {
  alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"; out=""
  for(i=1;i<=length(s);i+=4) {
    a=index(alphabet,substr(s,i,1))-1; b=index(alphabet,substr(s,i+1,1))-1
    c=index(alphabet,substr(s,i+2,1))-1; d=index(alphabet,substr(s,i+3,1))-1
    if(a<0 || b<0) break
    v=a*262144+b*4096+(c<0?0:c)*64+(d<0?0:d)
    out=out sprintf("%c",int(v/65536))
    if(c>=0) out=out sprintf("%c",int(v/256)%256)
    if(d>=0) out=out sprintf("%c",v%256)
  }
  return out
}
{
  name=decode($2); sub(/^.*\//,"",name)
  if(name !~ /\./ || name ~ /^\.[^.]*$/) ext="(无扩展名)"
  else {sub(/^.*\./,"",name); ext=tolower(name)}
  gsub(/[\t\r\n]/," ",ext); count[ext]++; bytes[ext]+=$1
}
END {for(ext in count) printf "%s\t%d\t%.0f\n",ext,count[ext],bytes[ext]}
' "$STATE_DIR/index/duplicate-candidates.tsv" >"$TMP/rows.tsv"
empty_count=$(tr -cd '\000' <"$STATE_DIR/index/empty-files.nul" | wc -c | tr -d ' ')
[ "$empty_count" -eq 0 ] || printf '(空文件)\t%s\t0\n' "$empty_count" >>"$TMP/rows.tsv"
printf 'group\tfiles\tbytes\n' >"$TMP/report.tsv"
sort -t "$(printf '\t')" -k3,3nr "$TMP/rows.tsv" >>"$TMP/report.tsv"
[ ! -f "$STATE_DIR/stop" ] || exit 9
chmod 0600 "$TMP/report.tsv"
mv -f "$TMP/report.tsv" "$OUT"
echo "$OUT"
