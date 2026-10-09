#!/usr/bin/env bash
# 微信专项规则：{wx_account}/{hex2} 受限展开、各档位内容、受保护路径拒绝、只读占用统计。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
COMPILER="$ROOT/v2/module/scripts/app-profile-rules.sh"
RULES="$ROOT/config/app-profiles.rules"
T=$(mktemp -d "${TMPDIR:-/tmp}/baize-wechat.XXXXXX")
trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
A=0123456789abcdef0123456789abcdef
B=fedcba9876543210fedcba9876543210

shells=(sh)
command -v busybox >/dev/null 2>&1 && shells+=("busybox ash")

for shell in "${shells[@]}"; do
  W="$T/${shell// /-}"
  mkdir -p "$W"
  # ---- 档位内容 -------------------------------------------------------------
  for combo in "0 0" "1 0" "2 0" "2 1"; do
    set -- $combo
    # shellcheck disable=SC2086
    $shell "$COMPILER" compile "$1" "$2" "$RULES" "$W/d$1$2" "$W/e$1$2" >/dev/null
  done
  has() { grep -qxF "$2" "$W/$1" || fail "$shell: $1 missing $2"; }
  lacks() { if grep -qF "$2" "$W/$1"; then fail "$shell: $1 must not contain $2"; fi; }
  # 保守：纯缓存（cache、朋友圈、头像、更新残留、日志、小程序包缓存）
  has d00 'com.tencent.mm|cache|3'
  has e00 'com.tencent.mm|cache|3'
  has e00 'com.tencent.mm|MicroMsg/CheckResUpdate|3'
  has e00 'com.tencent.mm|MicroMsg/wxacache|3'
  has e00 'com.tencent.mm|MicroMsg/xlog|0'
  has d00 'com.tencent.mm|files/tbslog|0'
  has d00 'com.tencent.mm|MicroMsg/{wx_account}/sns|7'
  has e00 'com.tencent.mm|MicroMsg/{wx_account}/avatar|7'
  lacks d00 'app_xwalkplugin'
  # 标准：再加网页内核插件、视频播放缓存、小程序文件
  has d10 'com.tencent.mm|app_xwalkplugin|7'
  has e10 'com.tencent.mm|files/VideoCache|1'
  has e10 'com.tencent.mm|MicroMsg/wxanewfiles|7'
  has e10 'com.tencent.mm|cache|1'
  # 增强：缓存不留天数，但仍不含聊天媒体
  has e20 'com.tencent.mm|cache|0'
  has d20 'com.tencent.mm|MicroMsg/{wx_account}/sns|0'
  for f in d00 e00 d10 e10 d20 e20; do
    lacks "$f" 'image2'; lacks "$f" 'voice2'; lacks "$f" '/video|'
  done
  # 增强 + 聊天媒体：image2/voice2 分桶、video，默认 30 天
  has d21 'com.tencent.mm|MicroMsg/{wx_account}/image2/{hex2}|30'
  has e21 'com.tencent.mm|MicroMsg/{wx_account}/voice2/{hex2}|30'
  has d21 'com.tencent.mm|MicroMsg/{wx_account}/video|30'
  # 用户选择的天数覆盖媒体规则（QQ 同样适用），低于 7 天按规则值。
  # shellcheck disable=SC2086
  $shell "$COMPILER" compile 2 1 "$RULES" "$W/d90" "$W/e90" 90 >/dev/null
  grep -qxF 'com.tencent.mm|MicroMsg/{wx_account}/image2/{hex2}|90' "$W/d90" || fail "$shell: media days 90"
  grep -qxF 'com.tencent.mobileqq|Tencent/MobileQQ/chatpic|90' "$W/e90" || fail "$shell: QQ media days 90"
  grep -qxF 'com.tencent.mm|cache|0' "$W/d90" || fail "$shell: media days must not touch cache retention"
  # shellcheck disable=SC2086
  $shell "$COMPILER" compile 2 1 "$RULES" "$W/d3" "$W/e3" 3 >/dev/null
  grep -qxF 'com.tencent.mm|MicroMsg/{wx_account}/video|30' "$W/d3" || fail "$shell: media days < 7 must fall back"

  # ---- 受保护路径与占位符语法 -------------------------------------------------
  while IFS= read -r bad; do
    [ -n "$bad" ] || continue
    printf '%s\n' "$bad" >"$W/one.rules"
    # shellcheck disable=SC2086
    if $shell "$COMPILER" lint "$W/one.rules" 2>/dev/null; then fail "$shell: lint accepted: $bad"; fi
    # shellcheck disable=SC2086
    $shell "$COMPILER" compile 2 1 "$W/one.rules" "$W/od" "$W/oe" 30 >/dev/null 2>&1
    [ ! -s "$W/od" ] && [ ! -s "$W/oe" ] || fail "$shell: compiled hostile rule: $bad"
  done <<'EOF'
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/EnMicroMsg.db|30
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/WxFileIndex.db|30
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/image2/WxFileIndex3|30
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/attachment|30
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/favorite|30
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/fav|30
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/emoji|30
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/record|30
enhanced-media|ext|com.tencent.mm|MicroMsg/Download|30
enhanced-media|data|com.tencent.mm|shared_prefs|30
enhanced-media|data|com.tencent.mm|files/mmkv|30
enhanced-media|data|com.tencent.mm|MicroMsg/systemInfo.cfg|30
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/account.bin|30
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/sns|30
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/image2|3
enhanced|data|com.tencent.mm|MicroMsg/{wx_account}/image2|30
standard|data|com.tencent.mm|MicroMsg/{wx_account}/voice2/{hex2}|30
conservative|data|com.tencent.mm|MicroMsg/{wx_account}/video|30
conservative|data|com.tencent.mm|MicroMsg/{wx_account}|0
conservative|data|com.tencent.mm|MicroMsg/{wx_account}/{wx_account}/sns|0
conservative|data|com.tencent.mm|{wx_account}/sns|0
conservative|data|com.tencent.mm|files/{wx_account}/sns|0
conservative|data|com.tencent.mobileqq|MicroMsg/{wx_account}/sns|0
conservative|data|com.tencent.mm|MicroMsg/{account}/sns|0
conservative|data|com.tencent.mm|MicroMsg/{wx_account}/sns{|0
conservative|data|com.tencent.mm|MicroMsg/{wx_account}/../sns|0
conservative|data|com.tencent.mm|MicroMsg/{wx_account}/sns/*|0
conservative|data|com.tencent.mm|MicroMsg/0123456789abcdef0123456789abcdef/sns|0
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/sns/{hex2}|30
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/image2/{hex2}/x|30
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/{hex2}|30
enhanced-media|data|com.tencent.mm|MicroMsg/{hex2}/image2|30
enhanced-media|data|com.tencent.mm|MicroMsg/{wx_account}/image2/{hex2}/{hex2}|30
EOF

  # ---- 占位符展开 ------------------------------------------------------------
  F="$W/fixture"; rm -rf "$F"; mkdir -p "$F/outside/image2/ab" "$F/elsewhere/MicroMsg/$B"
  base="$F/user/0/com.tencent.mm"; mm="$base/MicroMsg"
  mkdir -p "$mm/$A/image2/ab" "$mm/$A/image2/0f" "$mm/$A/image2/zz" "$mm/$A/image2/abc" "$mm/$A/image2/AB" \
           "$mm/$A/voice2/12" "$mm/$A/sns" "$mm/${A^^}" "$mm/${A:0:31}" "$mm/${A}0" "$mm/0123456789abcdef0123456789abcdeg" \
           "$mm/Download" "$mm/$A/attachment"
  : >"$mm/$A/image2/cd"                      # 文件不是分桶目录
  : >"$mm/$B"                                # 文件不是账号目录
  ln -s "$F/outside/image2" "$mm/$A/image2/ee"  # 分桶符号链接
  ln -s "$F/outside" "$mm/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"  # 账号符号链接
  # 符号链接的 MicroMsg 与符号链接的 BASE 都不展开。
  mkdir -p "$F/user/10/com.tencent.mm"; ln -s "$F/elsewhere/MicroMsg" "$F/user/10/com.tencent.mm/MicroMsg"
  mkdir -p "$F/user/11"; ln -s "$F/elsewhere" "$F/user/11/com.tencent.mm"
  cat >"$W/in.rules" <<'EOF'
com.tencent.mm|cache|0
com.tencent.mm|MicroMsg/{wx_account}/sns|7
com.tencent.mm|MicroMsg/{wx_account}/image2/{hex2}|30
com.tencent.mm|MicroMsg/{wx_account}/voice2/{hex2}|30
com.tencent.mm|MicroMsg/{wx_account}|0
com.tencent.mm|MicroMsg/{wx_account}/../x|0
com.tencent.mm|MicroMsg/{wx_account}/sns/{hex2}|0
com.example|MicroMsg/{wx_account}/sns|0
EOF
  # shellcheck disable=SC2086
  $shell "$COMPILER" expand "$W/in.rules" "$W/out.rules" "$base" "$F/user/10/com.tencent.mm" "$F/user/11/com.tencent.mm" "$F/missing" >"$W/expand.out"
  cat >"$W/want.rules" <<EOF
com.tencent.mm|cache|0
com.tencent.mm|MicroMsg/$A/sns|7
com.tencent.mm|MicroMsg/$A/image2/0f|30
com.tencent.mm|MicroMsg/$A/image2/ab|30
com.tencent.mm|MicroMsg/$A/voice2/12|30
EOF
  diff <(sort "$W/want.rules") <(sort "$W/out.rules") || fail "$shell: expansion output"
  grep -qx 'expanded=5' "$W/expand.out" || fail "$shell: expansion summary $(cat "$W/expand.out")"
  if grep -Eq '\{|\}|\.\.|aaaaaaaa|outside|elsewhere' "$W/out.rules"; then fail "$shell: placeholder or escape survived"; fi
  # 账号数上限
  for i in $(seq 1 20); do mkdir -p "$F/many/MicroMsg/$(printf '%032x' "$i")/sns"; done
  printf 'com.tencent.mm|MicroMsg/{wx_account}/sns|0\n' >"$W/many.in"
  # shellcheck disable=SC2086
  $shell "$COMPILER" expand "$W/many.in" "$W/many.out" "$F/many" >/dev/null
  [ "$(wc -l <"$W/many.out")" -eq 16 ] || fail "$shell: account cap"
  # 空输入
  : >"$W/empty.in"
  # shellcheck disable=SC2086
  $shell "$COMPILER" expand "$W/empty.in" "$W/empty.out" "$base" >/dev/null
  [ -f "$W/empty.out" ] && [ ! -s "$W/empty.out" ] || fail "$shell: empty expansion"

  # ---- 只读占用统计 ----------------------------------------------------------
  U="$W/usage"; rm -rf "$U"
  mkdir -p "$U/user/0/com.tencent.mm/MicroMsg/$A/image2/ab" "$U/media/0/Android/data/com.tencent.mm/MicroMsg/$A/video" \
           "$U/user/0/com.tencent.mm/cache" "$U/media/0/Android/data/com.tencent.mm/MicroMsg/Download"
  head -c 409600 /dev/zero >"$U/user/0/com.tencent.mm/MicroMsg/$A/image2/ab/x.jpg"
  head -c 204800 /dev/zero >"$U/media/0/Android/data/com.tencent.mm/MicroMsg/$A/video/v.mp4"
  head -c 102400 /dev/zero >"$U/media/0/Android/data/com.tencent.mm/MicroMsg/Download/f.pdf"
  head -c 102400 /dev/zero >"$U/user/0/com.tencent.mm/MicroMsg/$A/EnMicroMsg.db"
  before=$(find "$U" -type f -exec md5sum {} + | sort)
  # shellcheck disable=SC2086
  $shell "$COMPILER" wechat-usage "$U" >"$W/usage.out"
  [ "$before" = "$(find "$U" -type f -exec md5sum {} + | sort)" ] || fail "$shell: usage must be read-only"
  field() { awk -F'|' -v k="$1" '$1 == k { print $3 }' "$W/usage.out"; }
  [ "$(field image)" -ge 409600 ] || fail "$shell: usage image $(cat "$W/usage.out")"
  [ "$(field video)" -ge 204800 ] || fail "$shell: usage video"
  [ "$(field received)" -ge 102400 ] || fail "$shell: usage received"
  [ "$(field other)" -ge 102400 ] || fail "$shell: usage other (databases)"
  [ "$(field total)" -ge 819200 ] || fail "$shell: usage total"
  grep -qx 'accounts|1' "$W/usage.out" || fail "$shell: usage accounts"
  grep -q '^received|收到的文件（不清理）|[0-9]*|protected$' "$W/usage.out" || fail "$shell: received marked protected"
done

# ---- 执行器接线 --------------------------------------------------------------
C="$ROOT/v2/module/scripts/cleaner-compat.sh"
grep -q 'app-profile-rules.sh" expand "$profile_data.in" "$profile_data" /data/user/\[0-9\]\*/com.tencent.mm' "$C" || fail "data expansion roots"
grep -q 'app-profile-rules.sh" expand "$profile_ext.in" "$profile_ext" /data/media/\[0-9\]\*/Android/data/com.tencent.mm' "$C" || fail "ext expansion roots"
grep -q 'get_uint app_profile_media_days 30 7 365' "$C" || fail "media days clamp"
grep -q '^app_profile_media_days=30$' "$ROOT/config/default.conf" || fail "default media days 30"
# 开机与调度路径从不展开或统计。
for f in "$ROOT/v2/module/service.sh" "$ROOT/v2/module/scripts/supervisor.sh" "$ROOT/v2/module/scripts/scheduler.sh"; do
  if grep -q 'app-profile-rules.sh' "$f"; then fail "$(basename "$f") must not run app-profile-rules.sh"; fi
done
echo "wechat profile rules passed"
