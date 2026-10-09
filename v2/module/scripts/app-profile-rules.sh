#!/system/bin/sh
# 应用专项缓存规则编译器（按档位分级）。
#   compile TIER MEDIA RULES OUT_DATA OUT_EXT [MEDIA_DAYS]
#     TIER  0=保守 1=标准 2=增强；MEDIA 1=允许 enhanced-media（仅 TIER=2 时生效）。
#     MEDIA_DAYS：用户选择的聊天媒体保留天数（7~365），覆盖 enhanced-media 行自带的天数。
#     输出与 app.rules / external.rules 相同的“包名|相对路径|天数”格式，交给现有规则执行器。
#     含占位符的行原样输出，执行前必须再经 expand 展开（未展开的行永远不会交给执行器）。
#   expand IN OUT BASE...
#     把 {wx_account} / {hex2} 占位符展开成真实目录（只在清理任务中运行，绝不在开机时运行）：
#       {wx_account} 只能是“com.tencent.mm 的 MicroMsg/{wx_account}/<子路径>”，只匹配 BASE/MicroMsg 下
#                    名称为 ^[0-9a-f]{32}$ 的真实目录（非符号链接），每个 BASE 最多 16 个账号；
#       {hex2}       只能是 image2/{hex2} 或 voice2/{hex2} 的最后一级，只匹配 ^[0-9a-f]{2}$ 的真实目录。
#     只读两层固定目录（MicroMsg 与账号/分类），不递归、不跟随符号链接；输出最多 4096 行。
#   lint RULES
#     只校验；任一行不合规即返回 1。
#   wechat-usage [DATA_ROOT]
#     只读统计微信各类目录占用（du），供 App 展示；由用户在 App 中手动触发，不在开机或调度中运行。
# compile/lint 不触碰任何目标目录，只读规则文件、写输出文件；不在 /data/adb 生成额外文件。
set -u

# 任何档位（含增强+媒体）都永不触碰：数据库、配置、收藏、表情、收到的文件、聊天记录索引等。
APP_PROFILE_PROTECT='databases shared_prefs no_backup mmkv files/mmkv datastore download downloads weixin qqfile_recv filerecv fav favorite favorites draft drafts documents dcim pictures movies music attachment emoji record sfs filestorage backup backuprecord'
# 只有 enhanced-media 行可以触碰的聊天媒体目录。
APP_PROFILE_MEDIA='chatpic shortvideo ptt voice2 image2 video2 video'
APP_PROFILE_HEX32='[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]'
APP_PROFILE_MAX_ACCOUNTS=16
APP_PROFILE_MAX_EXPANDED=4096

app_profile_awk() {
  # $1 mode, $2 tier, $3 media, $4 rules, $5 out_data, $6 out_ext, $7 media days override (0 = rule value)
  awk -F '|' -v mode="$1" -v tier="$2" -v media="$3" -v out_data="$5" -v out_ext="$6" -v mdays="${7:-0}" \
      -v protect="$APP_PROFILE_PROTECT" -v mediadirs="$APP_PROFILE_MEDIA" '
    BEGIN {
      n = split(protect, d, " "); for (i = 1; i <= n; i++) protected[d[i]] = 1
      n = split(mediadirs, d, " "); for (i = 1; i <= n; i++) mediadir[d[i]] = 1
      rank["conservative"] = 0; rank["standard"] = 1; rank["enhanced"] = 2; rank["enhanced-media"] = 3
      bad = 0; kept = 0
    }
    # index() instead of a bracket expression: busybox/BWK awk parse escaped brackets differently.
    function wild(v) { return index(v, "*") || index(v, "?") || index(v, "[") || index(v, "]") || index(v, "\\") }
    function reject(why) { bad++; printf "[拒绝:%s] 第 %d 行：%s\n", why, NR, $0 > "/dev/stderr" }
    function hex32(v) { return v ~ /^[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]$/ }
    # 受保护路径：任何档位都拒绝。返回 1 表示触及。
    function protected_path(rel,   parts, k, m, low, two) {
      m = split(rel, parts, "/")
      for (k = 1; k <= m; k++) {
        low = tolower(parts[k])
        if (low in protected) return 1
        if (low ~ /\.db(-wal|-shm|-journal)?$/ || low ~ /^wxfileindex/ || low ~ /\.(cfg|ini|bin)$/) return 1
        if (k < m) { two = low "/" tolower(parts[k + 1]); if (two in protected) return 1 }
        # 字面写出的账号目录（32 位十六进制）一律拒绝：账号目录只能经 {wx_account} 受限展开。
        if (hex32(low)) return 1
      }
      return 0
    }
    function touches_media(rel,   parts, k, m) {
      m = split(rel, parts, "/")
      for (k = 1; k <= m; k++) if (tolower(parts[k]) in mediadir) return 1
      return 0
    }
    # 占位符语法：返回空串表示合规，否则返回拒绝原因。
    function placeholder_error(pkg, rel,   parts, k, m, acct, hex, other) {
      if (!index(rel, "{") && !index(rel, "}")) return ""
      m = split(rel, parts, "/"); acct = 0; hex = 0; other = 0
      for (k = 1; k <= m; k++) {
        if (parts[k] == "{wx_account}") { acct++; if (k != 2) other++ }
        else if (parts[k] == "{hex2}") { hex++; if (k != m || k != 4) other++ }
        else if (index(parts[k], "{") || index(parts[k], "}")) other++
      }
      if (other || acct != 1 || hex > 1) return "占位符位置"
      if (pkg != "com.tencent.mm" || parts[1] != "MicroMsg") return "占位符仅限微信 MicroMsg"
      if (m < 3) return "占位符不能指向整个账号目录"
      if (hex && parts[3] != "image2" && parts[3] != "voice2") return "{hex2} 仅限 image2/voice2"
      return ""
    }
    /^[[:space:]]*$/ || /^[[:space:]]*#/ { next }
    {
      if (NF != 5) { reject("字段数"); next }
      t = $1; scope = $2; pkg = $3; rel = $4; days = $5
      if (!(t in rank)) { reject("档位"); next }
      if (scope != "data" && scope != "ext") { reject("范围"); next }
      if (pkg !~ /^[A-Za-z0-9][A-Za-z0-9._-]*$/) { reject("包名"); next }
      if (rel == "" || rel ~ /^\// || rel ~ /\/$/ || rel ~ /\/\// || rel ~ /(^|\/)\.\.?(\/|$)/ || wild(rel)) { reject("相对路径"); next }
      if (days !~ /^[0-9]+$/ || days + 0 > 365) { reject("天数"); next }
      why = placeholder_error(pkg, rel)
      if (why != "") { reject(why); next }
      if (protected_path(rel)) { reject("受保护路径"); next }
      if (t == "enhanced-media") {
        if (days + 0 < 7) { reject("媒体保留天数不足 7 天"); next }
        if (!touches_media(rel)) { reject("媒体档位只能指向聊天媒体目录"); next }
        if (mdays + 0 >= 7) days = mdays + 0
      } else if (touches_media(rel)) { reject("非媒体档位触及用户数据"); next }
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
  ap_tier=$1 ap_media=$2 ap_rules=$3 ap_out_data=$4 ap_out_ext=$5 ap_mdays=${6:-0}
  case "$ap_tier" in 0|1|2) ;; *) ap_tier=1 ;; esac
  case "$ap_media" in 1) ;; *) ap_media=0 ;; esac
  case "$ap_mdays" in ''|*[!0-9]*) ap_mdays=0 ;; esac
  [ "$ap_mdays" -le 365 ] || ap_mdays=365
  [ -f "$ap_rules" ] || { : >"$ap_out_data"; : >"$ap_out_ext"; echo "data=0 ext=0 rejected=0"; return 0; }
  # 不合规的行只被跳过，不阻断其余合规规则；lint 模式才把它当作失败。
  app_profile_awk compile "$ap_tier" "$ap_media" "$ap_rules" "$ap_out_data" "$ap_out_ext" "$ap_mdays" || true
}

app_profile_lint() {
  app_profile_awk lint 2 1 "$1" /dev/null /dev/null
}

# 在 BASE 下列出合规账号目录（只读 BASE/MicroMsg 一层）。
app_profile_accounts() {
  apa_root=$1/MicroMsg
  [ -d "$apa_root" ] && [ ! -L "$1" ] && [ ! -L "$apa_root" ] || return 0
  apa_count=0
  for apa_dir in "$apa_root"/*; do
    apa_name=${apa_dir##*/}
    # shellcheck disable=SC2254
    case "$apa_name" in $APP_PROFILE_HEX32) ;; *) continue ;; esac
    [ -d "$apa_dir" ] && [ ! -L "$apa_dir" ] || continue
    apa_count=$((apa_count + 1))
    [ "$apa_count" -le "$APP_PROFILE_MAX_ACCOUNTS" ] || break
    printf '%s\n' "$apa_name"
  done
}

app_profile_expand() {
  ape_in=$1 ape_out=$2
  shift 2
  ape_raw="$ape_out.raw"
  : >"$ape_raw"
  if [ -s "$ape_in" ]; then
    while IFS='|' read -r ape_pkg ape_rel ape_days ape_extra || [ -n "$ape_pkg$ape_rel$ape_days$ape_extra" ]; do
      [ -n "$ape_pkg" ] && [ -z "$ape_extra" ] || continue
      case "$ape_rel" in
        *'{'*|*'}'*) ;;
        *) printf '%s|%s|%s\n' "$ape_pkg" "$ape_rel" "$ape_days" >>"$ape_raw"; continue ;;
      esac
      # 纵深防御：再次核对占位符语法（compile 已校验）。
      [ "$ape_pkg" = "com.tencent.mm" ] || continue
      case "$ape_rel" in *..*|*//*|*'*'*|*'?'*|*'['*) continue ;; MicroMsg/'{wx_account}'/?*) ;; *) continue ;; esac
      ape_tail=${ape_rel#MicroMsg/}
      ape_tail=${ape_tail#*/}
      ape_hexcat=
      case "$ape_tail" in
        image2/'{hex2}'|voice2/'{hex2}') ape_hexcat=${ape_tail%/*} ;;
        *'{'*|*'}'*) continue ;;
      esac
      for ape_base in "$@"; do
        for ape_acct in $(app_profile_accounts "$ape_base"); do
          if [ -z "$ape_hexcat" ]; then
            printf '%s|MicroMsg/%s/%s|%s\n' "$ape_pkg" "$ape_acct" "$ape_tail" "$ape_days" >>"$ape_raw"
            continue
          fi
          ape_cat="$ape_base/MicroMsg/$ape_acct/$ape_hexcat"
          [ -d "$ape_cat" ] && [ ! -L "$ape_cat" ] || continue
          for ape_sub in "$ape_cat"/*; do
            ape_two=${ape_sub##*/}
            case "$ape_two" in [0-9a-f][0-9a-f]) ;; *) continue ;; esac
            [ -d "$ape_sub" ] && [ ! -L "$ape_sub" ] || continue
            printf '%s|MicroMsg/%s/%s/%s|%s\n' "$ape_pkg" "$ape_acct" "$ape_hexcat" "$ape_two" "$ape_days" >>"$ape_raw"
          done
        done
      done
    done <"$ape_in"
  fi
  awk -v max="$APP_PROFILE_MAX_EXPANDED" '!seen[$0]++ { if (++n > max) exit; print }' "$ape_raw" >"$ape_out"
  rm -f "$ape_raw"
  printf 'expanded=%s\n' "$(wc -l <"$ape_out" | tr -d ' ')"
}

# 只读统计：输出“键|名称|字节|档位”，档位为 conservative/standard/enhanced/media/protected/other。
app_profile_du_kb() {
  apd_sum=0
  for apd_path in "$@"; do
    [ -d "$apd_path" ] && [ ! -L "$apd_path" ] || continue
    apd_kb=$(du -sk "$apd_path" 2>/dev/null | awk 'NR == 1 { print $1 }')
    case "$apd_kb" in ''|*[!0-9]*) apd_kb=0 ;; esac
    apd_sum=$((apd_sum + apd_kb))
  done
  printf '%s\n' "$apd_sum"
}

app_profile_wechat_usage() {
  apu_root=${1:-/data}
  apu_data='' apu_ext=''
  for apu_b in "$apu_root"/user/[0-9]*/com.tencent.mm; do [ -d "$apu_b" ] && apu_data="$apu_data $apu_b"; done
  for apu_b in "$apu_root"/media/[0-9]*/Android/data/com.tencent.mm; do [ -d "$apu_b" ] && apu_ext="$apu_ext $apu_b"; done
  apu_paths() { # $1 scope(data|ext|both) $2... relative paths; {a} = every account directory
    apu_scope=$1; shift
    case "$apu_scope" in data) apu_bases=$apu_data ;; ext) apu_bases=$apu_ext ;; *) apu_bases="$apu_data $apu_ext" ;; esac
    for apu_base in $apu_bases; do
      for apu_rel in "$@"; do
        case "$apu_rel" in
          '{a}'/*) for apu_acct in $(app_profile_accounts "$apu_base"); do printf '%s\n' "$apu_base/MicroMsg/$apu_acct/${apu_rel#*/}"; done ;;
          *) printf '%s\n' "$apu_base/$apu_rel" ;;
        esac
      done
    done
  }
  apu_sum=0
  apu_emit() { # key label tier scope rel...
    apu_key=$1 apu_label=$2 apu_tier=$3; shift 3
    # shellcheck disable=SC2046
    apu_kb=$(app_profile_du_kb $(apu_paths "$@"))
    apu_sum=$((apu_sum + apu_kb))
    printf '%s|%s|%s|%s\n' "$apu_key" "$apu_label" "$((apu_kb * 1024))" "$apu_tier"
  }
  apu_emit logs '日志与崩溃记录' conservative data app_bugly app_crashrecord files/tbslog files/xlog files/live_log
  apu_emit logs_ext '日志与崩溃记录（外部）' conservative ext MicroMsg/xlog MicroMsg/crash
  apu_emit cache '应用缓存' conservative both cache
  apu_emit cache_more '资源与网页缓存' standard data MicroMsg/webservice/codecache MicroMsg/webview_tmpl/tmpls app_textures app_xwalkplugin
  apu_emit cache_ext '更新残留与视频缓存' conservative ext MicroMsg/CheckResUpdate MicroMsg/.tmp MicroMsg/Cache files/VideoCache
  apu_emit appbrand '小程序缓存' conservative ext MicroMsg/wxacache MicroMsg/wxanewfiles
  apu_emit sns '朋友圈缓存' conservative both MicroMsg/sns '{a}/sns'
  apu_emit avatar '头像缓存' conservative both '{a}/avatar'
  apu_emit image '聊天图片' media both '{a}/image2'
  apu_emit video '聊天视频' media both '{a}/video'
  apu_emit voice '语音消息' media both '{a}/voice2'
  apu_emit received '收到的文件（不清理）' protected both MicroMsg/Download '{a}/attachment'
  apu_emit favorite '收藏与表情（不清理）' protected both '{a}/favorite' '{a}/emoji'
  apu_categorized=$apu_sum
  # shellcheck disable=SC2086
  apu_total=$(app_profile_du_kb $apu_data $apu_ext)
  apu_other=$((apu_total - apu_categorized))
  [ "$apu_other" -ge 0 ] || apu_other=0
  printf '%s|%s|%s|%s\n' other '数据库与其他（不清理）' "$((apu_other * 1024))" other
  printf '%s|%s|%s|%s\n' total '微信总占用' "$((apu_total * 1024))" total
  printf 'accounts|%s\n' "$(for apu_base in $apu_data $apu_ext; do app_profile_accounts "$apu_base"; done | sort -u | wc -l | tr -d ' ')"
}

case "${0##*/}" in
  app-profile-rules.sh)
    case "${1:-}" in
      compile) [ "$#" -eq 6 ] || [ "$#" -eq 7 ] || { echo "用法: app-profile-rules.sh compile TIER MEDIA RULES OUT_DATA OUT_EXT [MEDIA_DAYS]" >&2; exit 2; }
        app_profile_compile "$2" "$3" "$4" "$5" "$6" "${7:-0}" ;;
      expand) [ "$#" -ge 3 ] || { echo "用法: app-profile-rules.sh expand IN OUT BASE..." >&2; exit 2; }
        shift; app_profile_expand "$@" ;;
      wechat-usage) app_profile_wechat_usage "${2:-/data}" ;;
      lint) [ "$#" -eq 2 ] || { echo "用法: app-profile-rules.sh lint RULES" >&2; exit 2; }
        app_profile_lint "$2" ;;
      *) echo "用法: app-profile-rules.sh compile|expand|lint|wechat-usage ..." >&2; exit 2 ;;
    esac
    ;;
esac
