#!/system/bin/sh
set -eu
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
INDEX="$STATE_DIR/index/storage-files.nul"
OUT="$STATE_DIR/reports/storage-analysis.tsv"
mkdir -p "${OUT%/*}"
[ -s "$INDEX" ] || { echo "共享索引不存在" >&2; exit 6; }
printf 'group\tfiles\tbytes\n' >"$OUT"
python3_bin=$(command -v python3 || true)
if [ -n "$python3_bin" ]; then
  "$python3_bin" - "$INDEX" >>"$OUT" <<'PY'
import os,sys,collections
p=sys.argv[1]; groups=collections.defaultdict(lambda:[0,0])
for raw in open(p,'rb').read().split(b'\0'):
    if not raw: continue
    path=raw.decode('utf-8','replace')
    ext=os.path.splitext(path)[1].lower().lstrip('.') or '(无扩展名)'
    try:size=os.path.getsize(path)
    except OSError:continue
    groups[ext][0]+=1; groups[ext][1]+=size
for k,(n,b) in sorted(groups.items(), key=lambda x:x[1][1], reverse=True): print(f'{k}\t{n}\t{b}')
PY
else
  while IFS= read -r -d '' file; do ext=${file##*.}; [ "$ext" = "$file" ] && ext='(无扩展名)'; size=$(stat -c %s "$file" 2>/dev/null || echo 0); printf '%s\t1\t%s\n' "$ext" "$size"; done <"$INDEX" | awk -F '\t' 'BEGIN{OFS="\t"}{n[$1]+=$2;b[$1]+=$3}END{for(k in n)print k,n[k],b[k]}' | sort -t "$(printf '\t')" -k3,3nr >>"$OUT"
fi
echo "$OUT"
