#!/usr/bin/env bash
# 归类分类表必须四处同源，且归类器与索引器覆盖同一组扩展名。
#
# 历史缺陷：这份清单在 organizer-worker.sh、storage-index.sh、
# baize_engine_42_4.c、FileOrganizerEngine.kt 各写一遍且已经不一致——
# 索引侧只收 31 个扩展名而归类器认识 68 个，差集里的
# .m4a .aac .ogg .opus .webm .flv .3gp .epub 等文件永远归类不到。
set -uo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
CONF="$ROOT/config/organizer-categories.conf"
fail=0

[ -f "$CONF" ] || { echo "  [FAIL] 缺少 $CONF"; exit 1; }

# ——— 配置本身的格式 ———
conf_exts=$(awk -F= '!/^#/ && NF>1 {print $2}' "$CONF" | tr ' ' '\n' | sed '/^$/d' | sort)
conf_count=$(printf '%s\n' "$conf_exts" | wc -l | tr -d ' ')
dupes=$(printf '%s\n' "$conf_exts" | uniq -d)
[ -z "$dupes" ] || { echo "  [FAIL] 分类表有重复扩展名：$dupes"; fail=$((fail+1)); }
if printf '%s\n' "$conf_exts" | grep -qE '[^a-z0-9]'; then
  echo "  [FAIL] 扩展名必须是小写字母数字，实际含非法字符"; fail=$((fail+1))
fi
if printf '%s\n' "$conf_exts" | grep -q '^\.'; then
  echo "  [FAIL] 扩展名不应带点"; fail=$((fail+1))
fi
echo "分类表：$(awk -F= '!/^#/ && NF>1' "$CONF" | wc -l | tr -d ' ') 个分类，$conf_count 个扩展名"

# ——— 各处不得再有写死的扩展名清单 ———
if grep -qE '\*\.(jpe?g|mp4|mp3)\|' "$ROOT/v2/module/storage-index.sh"; then
  echo "  [FAIL] storage-index.sh 仍有写死的 organizer 扩展名清单"; fail=$((fail+1))
fi
grep -q 'organizer-exts' "$ROOT/v2/module/storage-index.sh" || {
  echo "  [FAIL] storage-index.sh 未把分类表传给原生索引器"; fail=$((fail+1)); }
grep -q 'organizer_categories_load' "$ROOT/v2/module/organizer-worker.sh" || {
  echo "  [FAIL] organizer-worker.sh 未从分类表载入"; fail=$((fail+1)); }
grep -q 'load_organizer_exts' "$ROOT/v2/native/baize_engine_42_4.c" || {
  echo "  [FAIL] 原生引擎未从分类表载入"; fail=$((fail+1)); }

# ——— Kotlin 的内置回退副本必须与配置一致 ———
KT="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/FileOrganizerEngine.kt"
kt_exts=$(sed -n '/BUILTIN_CATEGORIES/,/^        }$/p' "$KT" \
  | grep -o '"[a-z0-9][a-z0-9]*"' | tr -d '"' | sed '/^$/d' | sort)
if [ "$(printf '%s\n' "$kt_exts")" != "$(printf '%s\n' "$conf_exts")" ]; then
  echo "  [FAIL] Kotlin 内置回退副本与分类表不一致"
  echo "         仅配置有: $(comm -23 <(printf '%s\n' "$conf_exts") <(printf '%s\n' "$kt_exts") | tr '\n' ' ')"
  echo "         仅 Kotlin 有: $(comm -13 <(printf '%s\n' "$conf_exts") <(printf '%s\n' "$kt_exts") | tr '\n' ' ')"
  fail=$((fail+1))
fi

# ——— 用真实的 category_for 验证每个扩展名都能归到类 ———
T=${TMPDIR:-/tmp}/baize-org-cat; rm -rf "$T"; mkdir -p "$T"
sed -n '/^BAIZE_CAT_MAP=/,/^}$/p' "$ROOT/v2/module/organizer-worker.sh" > "$T/fn.sh"
sed -n '/^organizer_lower()/,/^}$/p' "$ROOT/v2/module/organizer-worker.sh" >> "$T/fn.sh"
sed -n '/^category_for()/,/^}$/p' "$ROOT/v2/module/organizer-worker.sh" >> "$T/fn.sh"
# shellcheck disable=SC1090
. "$T/fn.sh"
organizer_categories_load "$CONF" || { echo "  [FAIL] 载入分类表失败"; exit 1; }

while IFS= read -r ext; do
  [ -n "$ext" ] || continue
  category_for "/sd/Download/sample.$ext"
  [ -n "$CATEGORY" ] || { echo "  [FAIL] .$ext 归不到任何分类"; fail=$((fail+1)); }
  # 大写形式必须归到同一类
  upper=$(printf '%s' "$ext" | tr '[:lower:]' '[:upper:]')
  lower_cat=$CATEGORY
  category_for "/sd/Download/sample.$upper"
  [ "$CATEGORY" = "$lower_cat" ] || {
    echo "  [FAIL] .$ext 与 .$upper 分类不一致：$lower_cat vs $CATEGORY"; fail=$((fail+1)); }
done <<<"$conf_exts"

# ——— 未知扩展名与无扩展名必须归空 ———
for p in /sd/Download/x.unknownext /sd/Download/noextension /sd/Download/.hidden; do
  category_for "$p"
  [ -z "$CATEGORY" ] || { echo "  [FAIL] $p 不应被归类，实际 $CATEGORY"; fail=$((fail+1)); }
done

echo
if [ "$fail" -eq 0 ]; then echo "分类表四处同源：ok"; else echo "$fail 项失败"; exit 1; fi
