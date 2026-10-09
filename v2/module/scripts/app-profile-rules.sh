#!/system/bin/sh
# 应用专项缓存规则编译器（按档位分级）。
#   compile TIER MEDIA RULES OUT_DATA OUT_EXT
#     TIER  0=保守 1=标准 2=增强；MEDIA 1=允许 enhanced-media（仅 TIER=2 时生效）。
#     输出与 app.rules / external.rules 相同的“包名|相对路径|天数”格式，交给现有规则执行器。
#   lint RULES
#     只校验；任一行不合规即返回 1。
# 不触碰任何目标目录，只读规则文件、写两个输出文件；不在 /data/adb 生成额外文件。
set -u

APP_PROFILE_DENY='databases shared_prefs no_backup mmkv files/mmkv datastore download downloads weixin qqfile_recv filerecv chatpic shortvideo ptt voice2 image2 video2 emoji favorite favorites draft drafts documents dcim pictures movies music'

app_profile_awk() {
  # $1 mode, $2 tier, $3 media, $4 rules, $5 out_data, $6 out_ext
  awk -F '|' -v mode="$1" -v tier="$2" -v media="$3" -v out_data="$5" -v out_ext="$6" -v deny="$APP_PROFILE_DENY" '
    BEGIN {
      n = split(deny, d, " "); for (i = 1; i <= n; i++) denied[d[i]] = 1
      rank["conservative"] = 0; rank["standard"] = 1; rank["enhanced"] = 2; rank["enhanced-media"] = 3
      bad = 0; kept = 0
    }
    function reject(why) { bad++; printf "[拒绝:%s] 第 %d 行：%s\n", why, NR, $0 > "/dev/stderr" }
    function user_data(rel,   parts, k, m, low, two) {
      m = split(rel, parts, "/")
      for (k = 1; k <= m; k++) {
        low = tolower(parts[k])
        if (low in denied) return 1
        if (low ~ /\.db(-wal|-shm|-journal)?$/) return 1
        if (k < m) { two = low "/" tolower(parts[k + 1]); if (two in denied) return 1 }
        # 微信/QQ 账号目录（32 位十六进制）下全部视为聊天数据。
        if (low ~ /^[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]$/) return 1
      }
      return 0
    }
    /^[[:space:]]*$/ || /^[[:space:]]*#/ { next }
    {
      if (NF != 5) { reject("字段数"); next }
      t = $1; scope = $2; pkg = $3; rel = $4; days = $5
      if (!(t in rank)) { reject("档位"); next }
      if (scope != "data" && scope != "ext") { reject("范围"); next }
      if (pkg !~ /^[A-Za-z0-9][A-Za-z0-9._-]*$/) { reject("包名"); next }
      if (rel == "" || rel ~ /^\// || rel ~ /\/$/ || rel ~ /\/\// || rel ~ /(^|\/)\.\.?(\/|$)/ || rel ~ /[*?\[\]\\]/) { reject("相对路径"); next }
      if (days !~ /^[0-9]+$/ || days + 0 > 365) { reject("天数"); next }
      if (t == "enhanced-media") {
        if (days + 0 < 7) { reject("媒体保留天数不足 7 天"); next }
      } else if (user_data(rel)) { reject("非媒体档位触及用户数据"); next }
      if (mode != "compile") next
      want = rank[t]
      if (want == 3) { if (!(tier + 0 >= 2 && media + 0 == 1)) next }
      else if (want > tier + 0) next
      key = scope "|" pkg "|" rel
      if (!(key in best)) { order[++kept] = key; best[key] = days + 0 }
      else if (days + 0 < best[key]) best[key] = days + 0
    }
    END {
      if (mode == "compile") {
        printf "" > out_data; printf "" > out_ext; nd = 0; ne = 0
        for (i = 1; i <= kept; i++) {
          key = order[i]; split(key, f, "|")
          rel = substr(key, length(f[1]) + length(f[2]) + 3)
          line = f[2] "|" rel "|" best[key]
          if (f[1] == "data") { print line >> out_data; nd++ } else { print line >> out_ext; ne++ }
        }
        printf "data=%d ext=%d rejected=%d\n", nd, ne, bad
      }
      exit(bad > 0 ? 1 : 0)
    }
  ' "$4"
}

app_profile_compile() {
  ap_tier=$1 ap_media=$2 ap_rules=$3 ap_out_data=$4 ap_out_ext=$5
  case "$ap_tier" in 0|1|2) ;; *) ap_tier=1 ;; esac
  case "$ap_media" in 1) ;; *) ap_media=0 ;; esac
  [ -f "$ap_rules" ] || { : >"$ap_out_data"; : >"$ap_out_ext"; echo "data=0 ext=0 rejected=0"; return 0; }
  # 不合规的行只被跳过，不阻断其余合规规则；lint 模式才把它当作失败。
  app_profile_awk compile "$ap_tier" "$ap_media" "$ap_rules" "$ap_out_data" "$ap_out_ext" || true
}

app_profile_lint() {
  app_profile_awk lint 2 1 "$1" /dev/null /dev/null
}

case "${0##*/}" in
  app-profile-rules.sh)
    case "${1:-}" in
      compile) [ "$#" -eq 6 ] || { echo "用法: app-profile-rules.sh compile TIER MEDIA RULES OUT_DATA OUT_EXT" >&2; exit 2; }
        app_profile_compile "$2" "$3" "$4" "$5" "$6" ;;
      lint) [ "$#" -eq 2 ] || { echo "用法: app-profile-rules.sh lint RULES" >&2; exit 2; }
        app_profile_lint "$2" ;;
      *) echo "用法: app-profile-rules.sh compile|lint ..." >&2; exit 2 ;;
    esac
    ;;
esac
