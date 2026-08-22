#!/usr/bin/env bash
# 验证零子进程白名单匹配与旧实现语义完全一致，并覆盖旧实现没处理好的边界。
set -uo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=${TMPDIR:-/tmp}/baize-wl-match
rm -rf "$T"; mkdir -p "$T"
WHITELIST="$T/whitelist.conf"

# ——— 旧实现（逐字保留，作为对照基准）———
legacy_conflicts() {
  target=${1%/}
  [ -f "$WHITELIST" ] || return 1
  while IFS= read -r raw || [ -n "$raw" ]; do
    item=$(printf '%s' "$raw" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    case "$item" in ''|'#'*) continue ;; esac
    case "$item" in /*) ;; *) continue ;; esac
    item=${item%/}
    [ -n "$item" ] || item=/
    case "$target" in "$item"|"$item"/*) return 0 ;; esac
    case "$item" in "$target"|"$target"/*) return 0 ;; esac
  done <"$WHITELIST"
  return 1
}

# shellcheck disable=SC1090
. "$ROOT/v2/module/whitelist-match.sh"

fail=0
check() {
  local target=$1 want=$2 why=$3
  legacy_conflicts "$target"; local legacy=$?
  path_conflicts_whitelist "$target"; local fast=$?
  if [ "$fast" != "$want" ]; then
    printf '  [FAIL] %-60s 期望 %s 实际 %s (%s)\n' "$target" "$want" "$fast" "$why"
    fail=$((fail + 1))
  fi
  if [ "$legacy" != "$fast" ]; then
    printf '  [DIFF] %-60s 旧=%s 新=%s (%s)\n' "$target" "$legacy" "$fast" "$why"
    fail=$((fail + 1))
  fi
}

echo "白名单匹配：新旧语义一致性"
echo

cat > "$WHITELIST" <<'CONF'
# 注释行应被忽略
/storage/emulated/0/Keep
   /storage/emulated/0/Trimmed   
/data/data/com.example/files/
相对路径应被忽略
/storage/emulated/0/Deep/A/B
CONF
baize_whitelist_load "$WHITELIST"

echo "— 基本匹配 —"
check "/storage/emulated/0/Keep"            0 "精确命中"
check "/storage/emulated/0/Keep/sub/file"   0 "白名单是祖先"
check "/storage/emulated/0/Keep2"           1 "前缀相同但不是同一分段"
check "/storage/emulated/0/Other"           1 "无关路径"
check "/storage/emulated/0"                 0 "目标是白名单的祖先，也算冲突"

echo "— trim 与格式 —"
check "/storage/emulated/0/Trimmed"         0 "首尾空白应被裁掉"
check "/storage/emulated/0/Trimmed/x"       0 "裁剪后仍能匹配子路径"
check "/data/data/com.example/files"        0 "白名单尾部斜杠应被裁掉"
check "/storage/emulated/0/Deep/A/B/c/d"    0 "多层祖先"
check "/storage/emulated/0/Deep/A"          0 "目标是祖先"

echo "— 注释与非法行被忽略 —"
check "/相对路径应被忽略"                    1 "不以 / 开头的行不参与匹配"
check "/# 注释行应被忽略"                    1 "注释行不参与匹配"

echo "— 路径含空格（旧实现同样支持，不能退化）—"
printf '/storage/emulated/0/My Folder\n' > "$WHITELIST"
baize_whitelist_load "$WHITELIST"
check "/storage/emulated/0/My Folder"       0 "含空格的白名单项"
check "/storage/emulated/0/My Folder/a.txt" 0 "含空格项的子路径"
check "/storage/emulated/0/My"              1 "只匹配到一半不算命中"

echo "— 路径含 glob 字符（不得被当成通配符展开）—"
printf '/storage/emulated/0/Star*Dir\n/storage/emulated/0/Brack[et]\n' > "$WHITELIST"
baize_whitelist_load "$WHITELIST"
check "/storage/emulated/0/Star*Dir"        0 "字面量 * 应精确匹配"
check "/storage/emulated/0/Brack[et]"       0 "字面量 [] 应精确匹配"
check "/storage/emulated/0/StarXDir"        1 "* 不应作为通配符生效"

echo "— 空白名单 / 文件不存在 —"
: > "$WHITELIST"
baize_whitelist_load "$WHITELIST"
check "/storage/emulated/0/Anything"        1 "空白名单不拦截任何路径"
rm -f "$WHITELIST"
baize_whitelist_load "$WHITELIST"
check "/storage/emulated/0/Anything"        1 "白名单文件缺失不拦截"

echo "— 根目录白名单（旧实现在此有保护性漏洞，已按更安全方向修正）—"
printf '/\n' > "$WHITELIST"
baize_whitelist_load "$WHITELIST"
# 旧实现里 item="/" 时 "$item"/* 展开成 "//*"，匹配不上任何真实路径，
# 白名单等于失效。这里只断言新实现，不与旧实现比对。
path_conflicts_whitelist "/data/data/com.example" || {
  echo "  [FAIL] 根目录白名单未覆盖 /data/data/com.example"; fail=$((fail+1)); }
path_conflicts_whitelist "/storage/emulated/0/x" || {
  echo "  [FAIL] 根目录白名单未覆盖 /storage/emulated/0/x"; fail=$((fail+1)); }

echo "— 调用后不得污染 IFS 与 glob 开关 —"
printf '/storage/emulated/0/Keep\n' > "$WHITELIST"
baize_whitelist_load "$WHITELIST"
before_ifs=$(printf '%s' "$IFS" | od -An -c | tr -s ' ')
before_f=$-
path_conflicts_whitelist "/storage/emulated/0/Other" || true
after_ifs=$(printf '%s' "$IFS" | od -An -c | tr -s ' ')
after_f=$-
[ "$before_ifs" = "$after_ifs" ] || { echo "  [FAIL] IFS 被污染"; fail=$((fail+1)); }
[ "$before_f" = "$after_f" ] || { echo "  [FAIL] shell 选项被污染: $before_f -> $after_f"; fail=$((fail+1)); }

echo
if [ "$fail" -eq 0 ]; then echo "全部通过"; else echo "$fail 项失败"; exit 1; fi
