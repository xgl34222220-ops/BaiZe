#!/system/bin/sh
# 性能工具（实验）：Dex2oat 编译、数据库优化、进程压制、内存压制。全部默认关闭。
#   check         由 Supervisor 每分钟调用（仅当 perf-tools.conf 存在）：开机未稳定立即退出；
#                 按开关拉起/停止常驻轮询，按维护窗口（充电 + 息屏 + 开机 15 分钟后，每日一次）运行编译与数据库优化。
#   loop          进程压制 + 内存压制的低优先级轮询（≥30 秒一次），两项都关闭时自行退出并解冻全部。
#   start-dex2oat 手动「立即编译」：后台独立运行，进度写 perf-dex2oat.env。
#   stop-dex2oat  请求停止正在进行的编译。
#   dex2oat|dbopt [manual|auto]  直接运行（调试/内部使用）。
#   thaw-all      立即解冻白泽冻结的全部进程。
#   status        输出状态文件。
# 只写固定文件名：perf-tools.conf / perf-whitelist.conf / perf-db-blacklist.conf / perf-tools.env /
# perf-dex2oat.env / perf-dbopt.env / perf-maint.env / perf-frozen.tsv / perf-tools.log（最多 200 行）
# 以及 perf-loop.lock、perf-dex2oat.lock、perf-dbopt.lock 三个单实例锁目录。
# 绝不在开机阶段运行；绝不使用 am force-stop 代替冻结。
set -u
MODDIR=${BAIZE_MODULE_DIR:-${0%/*}}
case "$MODDIR" in */scripts) MODDIR=${MODDIR%/scripts} ;; esac
SCRIPTDIR="$MODDIR"
[ ! -d "$MODDIR/scripts" ] || SCRIPTDIR="$MODDIR/scripts"
SELF="$SCRIPTDIR/perf-tools.sh"
APP_ID=io.github.xgl34222220.baize
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
CONF="$STATE_DIR/perf-tools.conf"
USER_WL="$STATE_DIR/perf-whitelist.conf"
USER_DB_BL="$STATE_DIR/perf-db-blacklist.conf"
STATUS="$STATE_DIR/perf-tools.env"
DEX_STATUS="$STATE_DIR/perf-dex2oat.env"
DB_STATUS="$STATE_DIR/perf-dbopt.env"
MAINT_STATE="$STATE_DIR/perf-maint.env"
FROZEN="$STATE_DIR/perf-frozen.tsv"
LOG="$STATE_DIR/perf-tools.log"
LOOP_LOCK="$STATE_DIR/perf-loop.lock"
DEX_LOCK="$STATE_DIR/perf-dex2oat.lock"
DB_LOCK="$STATE_DIR/perf-dbopt.lock"
DEX_STOP="$STATE_DIR/perf-dex2oat.stop"
HISTORY="$STATE_DIR/history.tsv"
PROC=${BAIZE_PERF_PROC:-/proc}
CGROOT=${BAIZE_PERF_CGROUP:-/sys/fs/cgroup}
DATA_ROOT=${BAIZE_PERF_DATA_ROOT:-/data/data}
UPTIME_FILE=${BAIZE_PERF_UPTIME_FILE:-$PROC/uptime}
BOOT_ID_FILE=${BAIZE_PERF_BOOT_ID_FILE:-$PROC/sys/kernel/random/boot_id}
# 开机后至少等待：boot_completed 之后 120 秒（以 service.sh 写入的 boot_epoch 为准），且系统已运行 180 秒。
SETTLE_AFTER_BOOT=${BAIZE_PERF_SETTLE_SECONDS:-120}
MIN_UPTIME=${BAIZE_PERF_MIN_UPTIME:-180}
# 维护窗口（编译/数据库）：开机 15 分钟后，每 10 分钟最多检查一次充电/息屏。
MAINT_MIN_UPTIME=${BAIZE_PERF_MAINT_MIN_UPTIME:-900}
MAINT_CHECK_EVERY=${BAIZE_PERF_MAINT_CHECK_SECONDS:-600}
# 只压制“后台”进程：oom_score_adj ≥ 700（上一个应用 / 后台服务 / 缓存）；前台 0、可见 100、可感知 200 永不处理。
BG_MIN_ADJ=700
CACHED_MIN_ADJ=900
LOG_LINES=200
PS_BIN=${BAIZE_PERF_PS:-ps}
NL='
'
TAB=$(printf '\t')

[ -f "$SCRIPTDIR/state-retention.sh" ] && . "$SCRIPTDIR/state-retention.sh"
command -v baize_append_capped >/dev/null 2>&1 || baize_append_capped() { printf '%s\n' "$2" >>"$1"; }

# ---------- 配置 ----------
perf_conf() { pc_v=$(sed -n "s/^$1=//p" "$CONF" 2>/dev/null | tail -n 1); [ -n "$pc_v" ] && echo "$pc_v" || echo "$2"; }
perf_flag() { [ "$(perf_conf "$1" 0)" = 1 ]; }
perf_uint() {
  pu_v=$(perf_conf "$1" "$2")
  case "$pu_v" in ''|*[!0-9]*) pu_v=$2 ;; esac
  [ "$pu_v" -lt "$3" ] && pu_v=$3
  [ "$pu_v" -gt "$4" ] && pu_v=$4
  echo "$pu_v"
}
perf_now() { date +%s; }
perf_uptime() { pu_up=$(sed -n '1{s/[. ].*//;p;}' "$UPTIME_FILE" 2>/dev/null); case "$pu_up" in ''|*[!0-9]*) pu_up=0 ;; esac; echo "$pu_up"; }
perf_log() { baize_append_capped "$LOG" "$(date '+%F %T') $*" "$LOG_LINES"; }
# 记录/审计：写入 history.tsv（审计时间线读取它），只记汇总，不逐条刷屏。
perf_history() {
  # $1 操作  $2 项数  $3 失败数  $4 说明  $5 来源
  ph_msg=$(printf '%s' "$4" | tr '\t\r\n' '   ')
  # 第 11 列 releaseState=not_applicable：性能工具不释放空间，不计入清理效果统计。
  printf '%s\t%s\t0\t%s\t0\t%s\t%s\t%s\t\t\tnot_applicable\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1" "$2" "$3" "$ph_msg" "$5" >>"$HISTORY" 2>/dev/null || return 0
  tail -n 100 "$HISTORY" >"$HISTORY.tmp.$$" 2>/dev/null && mv -f "$HISTORY.tmp.$$" "$HISTORY"
  rm -f "$HISTORY.tmp.$$" 2>/dev/null
  return 0
}
perf_valid_name() { case "$1" in ''|*[!A-Za-z0-9._:]*|.*|:*) return 1 ;; esac; [ "${#1}" -le 200 ]; }
# 文件 → 合法条目（去注释、去空白、只保留包名/进程名字符），最多 500 行。
perf_read_list() {
  [ -f "$1" ] || return 0
  sed -e 's/#.*//' -e 's/[[:space:]]//g' "$1" 2>/dev/null | head -n 500 | while IFS= read -r prl; do
    perf_valid_name "$prl" && printf '%s\n' "$prl"
  done
}
perf_in_list() { case "$NL$2$NL" in *"$NL$1$NL"*) return 0 ;; esac; return 1; }

# ---------- 白名单 ----------
# 条目不含冒号：保护整个应用（全部进程）；含冒号：只保护该进程（如 com.tencent.mm:push）。
PERF_STATIC_WL="$APP_ID
com.tencent.mm
com.tencent.mm:push
com.tencent.mobileqq
com.tencent.mobileqq:MSF
com.tencent.tim
com.topjohnwu.magisk
me.weishu.kernelsu
me.bmax.apatch"
PERF_WL=$PERF_STATIC_WL
perf_whitelisted() {
  pw_pkg=${1%%:*}
  perf_in_list "$1" "$PERF_WL" && return 0
  perf_in_list "$pw_pkg" "$PERF_WL" && return 0
  return 1
}
perf_secure() {
  ps_v=$(cmd settings get secure "$1" 2>/dev/null)
  [ -n "$ps_v" ] || ps_v=$(settings get secure "$1" 2>/dev/null)
  [ "$ps_v" = null ] && ps_v=
  printf '%s' "$ps_v"
}
# 输入法、桌面、默认短信、默认拨号：运行时从系统读取。
perf_dynamic_wl() {
  { printf '%s:%s\n' "$(perf_secure enabled_input_methods)" "$(perf_secure default_input_method)" | tr ':' '\n'
    cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -n 1
    perf_secure sms_default_application; echo
    perf_secure dialer_default_application; echo
    telecom get-default-dialer 2>/dev/null | tail -n 1
  } | sed 's#[/;].*##' | while IFS= read -r pdw; do perf_valid_name "$pdw" && printf '%s\n' "$pdw"; done
}
perf_build_whitelist() {
  PERF_WL="$PERF_STATIC_WL$NL$(perf_dynamic_wl)$NL$(perf_read_list "$USER_WL")"
}

# ---------- 用户应用 ----------
PERF_USER_PKGS=; PERF_PKGS_AT=0
perf_refresh_user_pkgs() {
  prp_now=$(perf_now)
  [ -n "$PERF_USER_PKGS" ] && [ $((prp_now - PERF_PKGS_AT)) -lt 1800 ] && [ "$prp_now" -ge "$PERF_PKGS_AT" ] && return 0
  prp_list=$(cmd package list packages -3 2>/dev/null | sed -n 's/^package://p' | tr -d '\r')
  [ -n "$prp_list" ] || return 1
  PERF_USER_PKGS=$prp_list; PERF_PKGS_AT=$prp_now
  perf_build_whitelist
}

# 当前用户应用进程快照：每行 "pid adj 进程名"（只含第三方应用的 app uid）。
perf_snapshot() {
  "$PS_BIN" -A -o PID,UID,NAME 2>/dev/null | while read -r ps_pid ps_uid ps_name ps_rest; do
    case "$ps_pid" in ''|*[!0-9]*) continue ;; esac
    case "$ps_uid" in ''|*[!0-9]*) continue ;; esac
    ps_app=$((ps_uid % 100000)); [ "$ps_app" -ge 10000 ] && [ "$ps_app" -le 19999 ] || continue
    perf_in_list "${ps_name%%:*}" "$PERF_USER_PKGS" || continue
    ps_adj=; read -r ps_adj <"$PROC/$ps_pid/oom_score_adj" 2>/dev/null || continue
    case "$ps_adj" in ''|*[!0-9-]*) continue ;; esac
    printf '%s %s %s\n' "$ps_pid" "$ps_adj" "$ps_name"
  done
}
# 每个应用的最小 adj："包名 最小adj"。
perf_pkg_min_adj() { printf '%s\n' "$1" | awk 'NF>=3 {n=$3; sub(/:.*/, "", n); if (!(n in m) || $2+0 < m[n]) m[n]=$2+0} END {for (k in m) print k, m[k]}'; }

# ---------- 决策（纯函数，单元测试覆盖） ----------
# 后台时长是否已到：$1 首次进入后台时间 $2 现在 $3 分钟
perf_freeze_due() {
  case "$1:$2:$3" in *[!0-9:]*) return 1 ;; esac
  [ "$2" -ge "$1" ] || return 1
  [ $(( $2 - $1 )) -ge $(( $3 * 60 )) ]
}
# 内存决策：$1 可用KB $2 阈值MB $3 上次执行时间 $4 现在 $5 冷却秒 → none | low | critical
perf_mem_decision() {
  case "$1:$2:$3:$4:$5" in *[!0-9:]*|:*|*::*) echo none; return ;; esac
  [ "$1" -lt $(( $2 * 1024 )) ] || { echo none; return; }
  if [ "$3" -gt 0 ] && [ "$4" -ge "$3" ] && [ $(( $4 - $3 )) -lt "$5" ]; then echo none; return; fi
  if [ "$1" -lt $(( $2 * 512 )) ]; then echo critical; else echo low; fi
}
PERF_DEFAULT_DB_BL="com.tencent.mm
com.tencent.mobileqq
com.tencent.tim
$APP_ID"
perf_db_blacklisted() {
  perf_in_list "$1" "$PERF_DEFAULT_DB_BL" && return 0
  perf_in_list "$1" "$(perf_read_list "$USER_DB_BL")" && return 0
  return 1
}
# 跳过原因（空 = 可以优化）：$1 包名 $2 数据库路径
perf_db_skip_reason() {
  if perf_db_blacklisted "$1"; then echo blacklist; return; fi
  if ! perf_in_list "$1" "$PERF_USER_PKGS"; then echo system-app; return; fi
  if [ ! -f "$2" ] || [ -L "$2" ]; then echo not-regular-file; return; fi
  pdr_base=${2##*/}
  case "$pdr_base" in
    EnMicroMsg*|*[Ee]ncrypt*|*[Cc]ipher*|*[Ss]ecure*|*.enc|*.enc.db|*sqlcipher*) echo encrypted-name; return ;;
  esac
  if [ -s "$2-wal" ]; then echo wal-in-use; return; fi
  if [ -e "$2-journal" ]; then echo hot-journal; return; fi
  pdr_size=$(wc -c <"$2" 2>/dev/null | tr -d ' ')
  case "$pdr_size" in ''|*[!0-9]*) echo unreadable; return ;; esac
  if [ "$pdr_size" -lt 4096 ]; then echo too-small; return; fi
  if [ "$pdr_size" -gt 268435456 ]; then echo too-large; return; fi
  pdr_head=$(head -c 15 "$2" 2>/dev/null)
  if [ "$pdr_head" != "SQLite format 3" ]; then echo encrypted-or-not-sqlite; return; fi
  echo
}
perf_find_sqlite() {
  for pfs in ${BAIZE_PERF_SQLITE:-} /system/bin/sqlite3 /system/xbin/sqlite3 /vendor/bin/sqlite3 /product/bin/sqlite3 /system_ext/bin/sqlite3; do
    [ -x "$pfs" ] && { echo "$pfs"; return 0; }
  done
  [ -n "${BAIZE_PERF_SQLITE:-}" ] && return 1
  pfs=$(command -v sqlite3 2>/dev/null) && [ -x "$pfs" ] && { echo "$pfs"; return 0; }
  return 1
}

# ---------- 系统状态 ----------
perf_settled() {
  [ "$(getprop sys.boot_completed 2>/dev/null)" = 1 ] || return 1
  ps_up=$(perf_uptime); [ "$ps_up" -ge "$MIN_UPTIME" ] || return 1
  ps_boot=$(sed -n 's/^boot_epoch=//p' "$STATE_DIR/module.env" 2>/dev/null | tail -n 1)
  case "$ps_boot" in ''|*[!0-9]*) [ "$ps_up" -ge $((MIN_UPTIME + SETTLE_AFTER_BOOT)) ]; return ;; esac
  ps_now=$(perf_now)
  [ "$ps_now" -ge "$ps_boot" ] && [ $((ps_now - ps_boot)) -ge "$SETTLE_AFTER_BOOT" ]
}
perf_charging() {
  pc_bat=$(dumpsys battery 2>/dev/null)
  printf '%s\n' "$pc_bat" | grep -Eq '^[[:space:]]*(AC powered|USB powered|Wireless powered|Dock powered): true' && return 0
  pc_st=$(printf '%s\n' "$pc_bat" | sed -n 's/^[[:space:]]*status: //p' | head -n 1)
  [ "$pc_st" = 2 ] || [ "$pc_st" = 5 ]
}
perf_screen_off() { dumpsys power 2>/dev/null | grep -Eq 'Display Power: state=OFF|mWakefulness=Asleep|mInteractive=false'; }
perf_in_window() { perf_charging && perf_screen_off; }
perf_pkg_running() { "$PS_BIN" -A -o NAME 2>/dev/null | awk -v p="$1" '$1==p || index($1, p ":")==1 {f=1} END {exit !f}'; }
perf_low_priority() { renice -n 19 -p "$$" >/dev/null 2>&1 || true; ionice -c 3 -p "$$" >/dev/null 2>&1 || true; }
perf_lock() {
  if mkdir "$1" 2>/dev/null; then printf '%s\n' "$$" >"$1/pid"; return 0; fi
  pl_owner=$(sed -n '1p' "$1/pid" 2>/dev/null); case "$pl_owner" in ''|*[!0-9]*) pl_owner=0 ;; esac
  if [ "$pl_owner" -gt 1 ] && kill -0 "$pl_owner" 2>/dev/null; then return 1; fi
  rm -rf -- "$1" 2>/dev/null; mkdir "$1" 2>/dev/null || return 1
  printf '%s\n' "$$" >"$1/pid"
}
perf_lock_alive() {
  pla=$(sed -n '1p' "$1/pid" 2>/dev/null); case "$pla" in ''|*[!0-9]*) return 1 ;; esac
  [ "$pla" -gt 1 ] && kill -0 "$pla" 2>/dev/null
}
perf_detach() {
  if [ -n "${BAIZE_PERF_DETACH_LOG:-}" ]; then echo "sh $SELF $*" >>"$BAIZE_PERF_DETACH_LOG"; return 0; fi
  if command -v setsid >/dev/null 2>&1; then setsid sh "$SELF" "$@" </dev/null >/dev/null 2>&1 &
  else nohup sh "$SELF" "$@" </dev/null >/dev/null 2>&1 & fi
}
perf_env_value() { sed -n "s/^$2=//p" "$1" 2>/dev/null | tail -n 1; }

# ---------- 冻结（cgroup v2 freezer） ----------
perf_boot_id() { cat "$BOOT_ID_FILE" 2>/dev/null | tr -cd '0-9a-f-'; }
# 只接受 Android 应用 pid 级 cgroup：…/uid_<uid>/pid_<pid>/cgroup.freeze，绝不写 uid 级或根 cgroup。
perf_freeze_path() {
  pfp_cg=$(sed -n 's/^0:://p' "$PROC/$1/cgroup" 2>/dev/null | head -n 1)
  case "$pfp_cg" in */uid_[0-9]*/pid_"$1") ;; *) return 1 ;; esac
  case "$pfp_cg" in *..*) return 1 ;; esac
  printf '%s/cgroup.freeze\n' "$CGROOT$pfp_cg"
}
perf_valid_freeze_file() { case "$1" in "$CGROOT"/*uid_[0-9]*/pid_[0-9]*/cgroup.freeze) case "$1" in *..*) return 1 ;; esac; return 0 ;; esac; return 1; }
perf_thaw_file() { perf_valid_freeze_file "$1" && [ -f "$1" ] && printf '0\n' >"$1" 2>/dev/null; }
perf_frozen_init() {
  pfi_boot=$(perf_boot_id)
  if [ -f "$FROZEN" ] && [ "$(sed -n '1s/^#boot=//p' "$FROZEN")" != "$pfi_boot" ]; then rm -f "$FROZEN"; fi
  [ -f "$FROZEN" ] || printf '#boot=%s\n' "$pfi_boot" >"$FROZEN"
}
perf_frozen_count() { pfc=0; [ -f "$FROZEN" ] && pfc=$(grep -c "$TAB" "$FROZEN" 2>/dev/null); echo "${pfc:-0}"; }
# $1 = 包名（空 = 全部）；解冻并移出记录。
perf_thaw() {
  [ -f "$FROZEN" ] || return 0
  pt_n=0; pt_keep=
  while IFS="$TAB" read -r pt_f pt_pkg pt_pid pt_ts; do
    case "$pt_f" in '#'*) pt_keep="$pt_f"; continue ;; '') continue ;; esac
    if [ -z "$1" ] || [ "$pt_pkg" = "$1" ]; then perf_thaw_file "$pt_f" && pt_n=$((pt_n + 1))
    else pt_keep="$pt_keep$NL$pt_f$TAB$pt_pkg$TAB$pt_pid$TAB$pt_ts"; fi
  done <"$FROZEN"
  printf '%s\n' "$pt_keep" >"$FROZEN.tmp.$$" && mv -f "$FROZEN.tmp.$$" "$FROZEN"
  THAWED_TOTAL=$((THAWED_TOTAL + pt_n))
  [ "$pt_n" -gt 0 ] && perf_log "解冻 ${1:-全部} ×$pt_n"
  return 0
}
THAWED_TOTAL=0; FROZEN_TOTAL=0
# 去掉已退出或已被系统/前台唤醒解冻的记录；返回被外部解冻的包名（每行一个）。
perf_frozen_prune() {
  [ -f "$FROZEN" ] || return 0
  pfp_keep=; pfp_ext=
  while IFS="$TAB" read -r pfp_f pfp_pkg pfp_pid pfp_ts; do
    case "$pfp_f" in '#'*) pfp_keep="$pfp_f"; continue ;; '') continue ;; esac
    pfp_v=; read -r pfp_v <"$pfp_f" 2>/dev/null || continue
    [ -d "$PROC/$pfp_pid" ] || continue
    if [ "$pfp_v" != 1 ]; then pfp_ext="$pfp_ext$pfp_pkg$NL"; continue; fi
    pfp_keep="$pfp_keep$NL$pfp_f$TAB$pfp_pkg$TAB$pfp_pid$TAB$pfp_ts"
  done <"$FROZEN"
  printf '%s\n' "$pfp_keep" >"$FROZEN.tmp.$$" && mv -f "$FROZEN.tmp.$$" "$FROZEN"
  printf '%s' "$pfp_ext"
}
# $1 pid $2 包名：0 已冻结；2 不支持；3 已被系统冻结（不接管）
perf_freeze_pid() {
  pfz_f=$(perf_freeze_path "$1") || return 2
  [ -f "$pfz_f" ] && [ -w "$pfz_f" ] || return 2
  pfz_v=; read -r pfz_v <"$pfz_f" 2>/dev/null
  [ "$pfz_v" = 0 ] || return 3
  printf '1\n' >"$pfz_f" 2>/dev/null || return 2
  printf '%s\t%s\t%s\t%s\n' "$pfz_f" "$2" "$1" "$(perf_now)" >>"$FROZEN"
  FROZEN_TOTAL=$((FROZEN_TOTAL + 1))
  return 0
}

# 前台/焦点唤醒：阻塞读取系统 events 日志（空闲时不占 CPU），被冻结的应用一旦被切到前台立即解冻。
WATCH_PID=
perf_watch() {
  logcat -b events -T 1 -v brief -s wm_set_resumed_activity am_set_resumed_activity wm_task_to_front am_task_to_front \
    wm_create_activity am_create_activity wm_restart_activity am_restart_activity wm_new_intent am_new_intent \
    wm_focused_root_task am_focused_stack input_focus 2>/dev/null |
  while IFS= read -r pw_line; do
    [ -s "$FROZEN" ] || continue
    while IFS="$TAB" read -r pw_f pw_pkg pw_pid pw_ts; do
      case "$pw_f" in '#'*|'') continue ;; esac
      case "$pw_line" in
        *"$pw_pkg/"*|*"$pw_pkg:"*|*"$pw_pkg,"*|*"$pw_pkg]"*|*"$pw_pkg "*)
          perf_thaw_file "$pw_f" && printf '%s 前台唤醒解冻 %s\n' "$(date '+%F %T')" "$pw_pkg" >>"$LOG" ;;
      esac
    done <"$FROZEN"
  done
}
perf_watch_start() {
  [ -n "$WATCH_PID" ] && kill -0 "$WATCH_PID" 2>/dev/null && return 0
  command -v logcat >/dev/null 2>&1 || return 1
  perf_watch & WATCH_PID=$!
}
perf_watch_stop() {
  [ -n "$WATCH_PID" ] || return 0
  pkill -P "$WATCH_PID" 2>/dev/null || true
  kill "$WATCH_PID" 2>/dev/null || true
  WATCH_PID=
}

# ---------- 轮询：进程压制 + 内存压制 ----------
BG_LIST=; MEM_LAST=0; FREEZE_SUPPORT=unknown; FREEZE_REASON=
MEM_EVENTS=0; TRIM_TOTAL=0; KILL_TOTAL=0; MEM_AVAIL_MB=-1
H_FROZEN=0; H_THAWED=0; H_MEM=0; H_TRIM=0; H_KILL=0; H_AT=0
FROZEN_PIDS=; FROZEN_PKGS=
perf_frozen_lists() {
  FROZEN_PIDS=; FROZEN_PKGS=
  [ -f "$FROZEN" ] || return 0
  FROZEN_PIDS=$(awk -F '\t' 'NF>=3 && $1 !~ /^#/ {print $3}' "$FROZEN")
  FROZEN_PKGS=$(awk -F '\t' 'NF>=3 && $1 !~ /^#/ {print $2}' "$FROZEN" | sort -u)
}
# 后台计时表：每行 "包名 进入后台时间"。只保留整个应用都在后台（最小 adj ≥ 700）的应用；
# 被外部（系统 / 前台唤醒）解冻的应用重新计时。
perf_bg_update() {
  printf '%s\n' "$PKG_ADJ" | awk -v now="$1" -v min="$BG_MIN_ADJ" -v old="$BG_LIST" -v ext="$2" '
    BEGIN { n = split(old, o, "\n"); for (i = 1; i <= n; i++) { if (split(o[i], f, " ") == 2) s[f[1]] = f[2] }
            m = split(ext, e, "\n"); for (i = 1; i <= m; i++) if (e[i] != "") x[e[i]] = 1 }
    NF == 2 && $2 + 0 >= min { t = (($1 in s) && !($1 in x)) ? s[$1] : now; print $1, t }'
}
# 已到后台时长的应用（每行一个包名）。
perf_bg_due() { printf '%s\n' "$BG_LIST" | awk -v now="$1" -v after="$2" 'NF == 2 && now >= $2 && now - $2 >= after * 60 {print $1}'; }

perf_freeze_round() {
  pfr_now=$1; pfr_after=$2
  pfr_ext=$(perf_frozen_prune)
  for pfr_p in $pfr_ext; do THAWED_TOTAL=$((THAWED_TOTAL + 1)); done
  BG_LIST=$(perf_bg_update "$pfr_now" "$pfr_ext")
  perf_frozen_lists
  # 回到前台 / 可见 / 可感知（adj < 700）的已冻结应用：立即解冻。
  for pfr_pkg in $(printf '%s\n' "$PKG_ADJ" | awk -v min="$BG_MIN_ADJ" 'NF == 2 && $2 + 0 < min {print $1}'); do
    perf_in_list "$pfr_pkg" "$FROZEN_PKGS" && perf_thaw "$pfr_pkg"
  done
  perf_frozen_lists
  for pfr_pkg in $(perf_bg_due "$pfr_now" "$pfr_after"); do
    perf_whitelisted "$pfr_pkg" && continue
    pfr_done=0
    while read -r pfr_pid pfr_adj pfr_name; do
      [ "${pfr_name%%:*}" = "$pfr_pkg" ] || continue
      perf_whitelisted "$pfr_name" && continue
      perf_in_list "$pfr_pid" "$FROZEN_PIDS" && continue
      perf_freeze_pid "$pfr_pid" "$pfr_pkg"; pfr_rc=$?
      if [ "$pfr_rc" = 0 ]; then pfr_done=$((pfr_done + 1)); FREEZE_SUPPORT=yes
      elif [ "$pfr_rc" = 2 ] && [ "$FREEZE_SUPPORT" != yes ]; then FREEZE_SUPPORT=no; FREEZE_REASON="未找到可写的 cgroup v2 进程冻结节点"; return 0; fi
    done <<EOF
$SNAPSHOT
EOF
    if [ "$pfr_done" -gt 0 ]; then
      pfr_since=$(printf '%s\n' "$BG_LIST" | awk -v p="$pfr_pkg" '$1 == p {print $2; exit}')
      perf_log "冻结 $pfr_pkg ×$pfr_done（后台 $(( (pfr_now - ${pfr_since:-$pfr_now}) / 60 )) 分钟）"
      H_FROZEN=$((H_FROZEN + pfr_done))
    fi
  done
  return 0
}

perf_mem_round() {
  pmr_now=$1; pmr_thr=$2; pmr_cool=$3; pmr_kill=$4
  pmr_avail=$(sed -n 's/^MemAvailable:[[:space:]]*\([0-9]*\).*/\1/p' "$PROC/meminfo" 2>/dev/null | head -n 1)
  case "$pmr_avail" in ''|*[!0-9]*) MEM_AVAIL_MB=-1; return 0 ;; esac
  MEM_AVAIL_MB=$((pmr_avail / 1024))
  pmr_d=$(perf_mem_decision "$pmr_avail" "$pmr_thr" "$MEM_LAST" "$pmr_now" "$pmr_cool")
  [ "$pmr_d" != none ] || return 0
  MEM_LAST=$pmr_now; MEM_EVENTS=$((MEM_EVENTS + 1)); H_MEM=$((H_MEM + 1))
  pmr_trims=0; pmr_kills=0
  perf_frozen_lists
  while read -r pmr_pkg pmr_min; do
    [ -n "$pmr_pkg" ] || continue
    [ "$pmr_min" -ge "$BG_MIN_ADJ" ] || continue
    perf_whitelisted "$pmr_pkg" && continue
    perf_in_list "$pmr_pkg" "$FROZEN_PKGS" && continue
    while read -r pmr_pid pmr_adj pmr_name; do
      [ "${pmr_name%%:*}" = "$pmr_pkg" ] || continue
      perf_whitelisted "$pmr_name" && continue
      [ "$pmr_trims" -lt 30 ] || break
      if [ "$pmr_d" = critical ] || [ "$pmr_adj" -ge "$CACHED_MIN_ADJ" ]; then pmr_level=COMPLETE; else pmr_level=RUNNING_LOW; fi
      cmd activity send-trim-memory "$pmr_pid" "$pmr_level" >/dev/null 2>&1 && pmr_trims=$((pmr_trims + 1))
    done <<EOF
$SNAPSHOT
EOF
    # 结束进程只针对整个应用都处于缓存状态（adj ≥ 900）的应用；am kill 只结束后台进程。
    if [ "$pmr_kill" = 1 ] && [ "$pmr_min" -ge "$CACHED_MIN_ADJ" ] && [ "$pmr_kills" -lt 10 ]; then
      cmd activity kill "$pmr_pkg" >/dev/null 2>&1 && pmr_kills=$((pmr_kills + 1))
    fi
  done <<EOF
$PKG_ADJ
EOF
  TRIM_TOTAL=$((TRIM_TOTAL + pmr_trims)); KILL_TOTAL=$((KILL_TOTAL + pmr_kills))
  H_TRIM=$((H_TRIM + pmr_trims)); H_KILL=$((H_KILL + pmr_kills))
  perf_log "内存低（可用 ${MEM_AVAIL_MB}MB < ${pmr_thr}MB，$pmr_d）：回收通知 $pmr_trims 个进程，结束 $pmr_kills 个缓存应用"
}

perf_write_status() {
  pws_tmp="$STATUS.tmp.$$"
  {
    echo "schema=perf-tools-v1"
    echo "loop_pid=${LOOP_PID:-0}"
    echo "loop_state=$1"
    echo "freeze_enabled=$FREEZE_ON"
    echo "freeze_support=$FREEZE_SUPPORT"
    echo "freeze_reason=$FREEZE_REASON"
    echo "frozen_count=$(perf_frozen_count)"
    echo "frozen_total=$FROZEN_TOTAL"
    echo "thawed_total=$THAWED_TOTAL"
    echo "background_tracked=$(printf '%s\n' "$BG_LIST" | grep -c ' ')"
    echo "mem_enabled=$MEM_ON"
    echo "mem_avail_mb=$MEM_AVAIL_MB"
    echo "mem_events=$MEM_EVENTS"
    echo "trim_total=$TRIM_TOTAL"
    echo "kill_total=$KILL_TOTAL"
    echo "mem_last_epoch=$MEM_LAST"
    echo "updated=$(perf_now)"
  } >"$pws_tmp" && mv -f "$pws_tmp" "$STATUS"
  chmod 0600 "$STATUS" 2>/dev/null || true
}
perf_hourly_audit() {
  [ "$H_AT" -gt 0 ] || H_AT=$1
  [ $(( $1 - H_AT )) -ge 3600 ] || [ "${2:-}" = flush ] || return 0
  [ "$H_FROZEN" -gt 0 ] || [ "$H_THAWED" -gt 0 ] && perf_history "性能工具·进程压制" "$H_FROZEN" 0 "冻结 $H_FROZEN 个后台进程，解冻 $H_THAWED 次" "scheduled:perf-tools"
  [ "$H_MEM" -gt 0 ] && perf_history "性能工具·内存压制" "$H_TRIM" 0 "内存不足 $H_MEM 次：回收通知 $H_TRIM 次，结束 $H_KILL 个缓存应用" "scheduled:perf-tools"
  H_AT=$1; H_FROZEN=0; H_THAWED=0; H_MEM=0; H_TRIM=0; H_KILL=0
}

perf_loop() {
  perf_lock "$LOOP_LOCK" || exit 0
  LOOP_PID=$$
  perf_low_priority
  FREEZE_ON=0; MEM_ON=0
  pl_cleanup() {
    perf_watch_stop
    perf_thaw ""
    perf_hourly_audit "$(perf_now)" flush
    perf_write_status stopped
    rm -rf -- "$LOOP_LOCK" 2>/dev/null
  }
  trap pl_cleanup EXIT
  trap 'exit 0' INT TERM HUP
  until perf_settled; do sleep 30 & wait $! 2>/dev/null; done
  perf_frozen_init
  # 上次异常退出遗留的冻结记录：先全部解冻再开始。
  perf_thaw ""
  perf_log "轮询启动"
  pl_started=0
  while :; do
    [ -f "$CONF" ] || break
    # 模块被禁用 / 卸载 / 守护进程停止：解冻全部后退出。
    [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] && [ ! -f "$STATE_DIR/supervisor.stop" ] || break
    FREEZE_ON=$(perf_flag freeze_enabled && echo 1 || echo 0)
    MEM_ON=$(perf_flag mem_enabled && echo 1 || echo 0)
    [ "$FREEZE_ON" = 1 ] || [ "$MEM_ON" = 1 ] || break
    [ "$MEM_ON" = 1 ] || [ "$FREEZE_SUPPORT" != no ] || break
    pl_poll=$(perf_uint poll_seconds 60 30 600)
    pl_now=$(perf_now)
    if perf_refresh_user_pkgs; then
      SNAPSHOT=$(perf_snapshot)
      PKG_ADJ=$(perf_pkg_min_adj "$SNAPSHOT")
      pl_th_before=$THAWED_TOTAL
      if [ "$FREEZE_ON" = 1 ] && [ "$FREEZE_SUPPORT" != no ]; then
        perf_freeze_round "$pl_now" "$(perf_uint freeze_after_minutes 15 1 240)"
        [ "$FREEZE_SUPPORT" != no ] || { perf_thaw ""; perf_log "进程压制已停用：$FREEZE_REASON"
          perf_history "性能工具·进程压制" 0 1 "设备不支持 cgroup v2 进程冻结，已停用（不会改用强制停止）" "scheduled:perf-tools"; }
      elif [ "$FREEZE_ON" != 1 ]; then
        [ "$(perf_frozen_count)" -gt 0 ] && perf_thaw ""
      fi
      H_THAWED=$((H_THAWED + THAWED_TOTAL - pl_th_before))
      if [ "$(perf_frozen_count)" -gt 0 ]; then perf_watch_start || true; else perf_watch_stop; fi
      [ "$MEM_ON" = 1 ] && perf_mem_round "$pl_now" "$(perf_uint mem_threshold_mb 1024 100 16384)" \
        "$(perf_uint mem_cooldown_seconds 300 30 3600)" "$(perf_conf mem_kill 0)"
    fi
    [ "$pl_started" = 1 ] || { pl_started=1; perf_history "性能工具·轮询" 0 0 "进程压制 $([ "$FREEZE_ON" = 1 ] && echo 开 || echo 关)，内存压制 $([ "$MEM_ON" = 1 ] && echo 开 || echo 关)，每 ${pl_poll} 秒检查一次" "scheduled:perf-tools"; }
    perf_hourly_audit "$pl_now"
    perf_write_status running
    [ "${BAIZE_PERF_ONCE:-0}" = 1 ] && break
    sleep "$pl_poll" & wait $! 2>/dev/null
  done
  perf_log "轮询结束（开关已关闭）"
  exit 0
}

# ---------- Dex2oat ----------
perf_dex_status() {
  pds_tmp="$DEX_STATUS.tmp.$$"
  { echo "state=$1"; echo "trigger=$DEX_TRIGGER"; echo "mode=$DEX_MODE"; echo "scope=$DEX_SCOPE"; echo "force=$DEX_FORCE"
    echo "current=$DEX_I"; echo "total=$DEX_TOTAL"; echo "ok=$DEX_OK"; echo "failed=$DEX_FAIL"; echo "package=${2:-}"
    echo "started=$DEX_STARTED"; echo "updated=$(perf_now)"; } >"$pds_tmp" && mv -f "$pds_tmp" "$DEX_STATUS"
}
perf_dex2oat() {
  DEX_TRIGGER=${1:-manual}
  perf_lock "$DEX_LOCK" || { echo "编译已在进行" >&2; exit 3; }
  trap 'rm -rf -- "$DEX_LOCK" 2>/dev/null' EXIT
  trap 'exit 9' INT TERM
  perf_low_priority
  rm -f "$DEX_STOP"
  DEX_MODE=$(perf_conf dex2oat_mode speed-profile)
  case "$DEX_MODE" in speed-profile|speed|everything) ;; *) DEX_MODE=speed-profile ;; esac
  DEX_SCOPE=$(perf_conf dex2oat_scope user); case "$DEX_SCOPE" in user|all) ;; *) DEX_SCOPE=user ;; esac
  DEX_FORCE=$(perf_conf dex2oat_force 0); [ "$DEX_FORCE" = 1 ] || DEX_FORCE=0
  DEX_I=0; DEX_OK=0; DEX_FAIL=0; DEX_STARTED=$(perf_now)
  if [ "$DEX_SCOPE" = all ]; then pd_list=$(cmd package list packages 2>/dev/null | sed -n 's/^package://p' | tr -d '\r')
  else pd_list=$(cmd package list packages -3 2>/dev/null | sed -n 's/^package://p' | tr -d '\r'); fi
  DEX_TOTAL=$(printf '%s\n' "$pd_list" | grep -c .)
  perf_dex_status running
  perf_log "Dex2oat 开始：$DEX_MODE / $DEX_SCOPE / 强制=$DEX_FORCE / 共 $DEX_TOTAL 个（$DEX_TRIGGER）"
  pd_result=completed
  for pd_pkg in $pd_list; do
    perf_valid_name "$pd_pkg" || continue
    [ ! -f "$DEX_STOP" ] || { pd_result=cancelled; break; }
    if [ "$DEX_TRIGGER" = auto ] && [ $((DEX_I % 5)) = 0 ] && ! perf_in_window; then pd_result=interrupted; break; fi
    DEX_I=$((DEX_I + 1)); perf_dex_status running "$pd_pkg"
    if [ "$DEX_FORCE" = 1 ]; then pd_out=$(cmd package compile -m "$DEX_MODE" -f "$pd_pkg" 2>&1)
    else pd_out=$(cmd package compile -m "$DEX_MODE" "$pd_pkg" 2>&1); fi
    case "$pd_out" in *Success*) DEX_OK=$((DEX_OK + 1)) ;; *) DEX_FAIL=$((DEX_FAIL + 1)) ;; esac
  done
  rm -f "$DEX_STOP"
  perf_dex_status "$pd_result"
  pd_label=完成; [ "$pd_result" = cancelled ] && pd_label=已停止; [ "$pd_result" = interrupted ] && pd_label=因亮屏或拔电中断
  pd_src=app; [ "$DEX_TRIGGER" = auto ] && pd_src=scheduled:perf-tools
  perf_history "性能工具·Dex2oat编译" "$DEX_OK" "$DEX_FAIL" "Dex2oat $pd_label：$DEX_MODE，$DEX_I/$DEX_TOTAL 个，成功 $DEX_OK，失败 $DEX_FAIL" "$pd_src"
  perf_log "Dex2oat $pd_label：成功 $DEX_OK 失败 $DEX_FAIL（$DEX_I/$DEX_TOTAL）"
  exit 0
}

# ---------- 数据库优化 ----------
perf_db_status() {
  pdb_tmp="$DB_STATUS.tmp.$$"
  { echo "state=$1"; echo "sqlite=${DB_SQLITE:-}"; echo "optimized=${DB_OK:-0}"; echo "skipped=${DB_SKIP:-0}"; echo "failed=${DB_FAIL:-0}"
    echo "apps_skipped_running=${DB_RUNNING:-0}"; echo "saved_bytes=${DB_SAVED:-0}"; echo "reason=${2:-}"; echo "updated=$(perf_now)"; } >"$pdb_tmp" && mv -f "$pdb_tmp" "$DB_STATUS"
}
perf_dbopt() {
  pdo_trigger=${1:-auto}
  DB_OK=0; DB_SKIP=0; DB_FAIL=0; DB_RUNNING=0; DB_SAVED=0; DB_SQLITE=
  if ! DB_SQLITE=$(perf_find_sqlite); then
    DB_SQLITE=; perf_db_status unavailable "设备无 sqlite3，暂不可用"
    perf_log "数据库优化跳过：设备无 sqlite3，暂不可用"
    exit 0
  fi
  perf_lock "$DB_LOCK" || exit 3
  trap 'rm -rf -- "$DB_LOCK" 2>/dev/null' EXIT
  trap 'exit 9' INT TERM
  perf_low_priority
  perf_refresh_user_pkgs || { perf_db_status failed "无法读取应用列表"; exit 0; }
  pdo_force=$(perf_conf dbopt_force_stop 0)
  pdo_start=$(perf_now); pdo_count=0; pdo_result=completed
  perf_db_status running
  for pdo_pkg in $PERF_USER_PKGS; do
    perf_valid_name "$pdo_pkg" || continue
    perf_db_blacklisted "$pdo_pkg" && continue
    [ -d "$DATA_ROOT/$pdo_pkg/databases" ] || continue
    if [ "$pdo_trigger" = auto ] && ! perf_in_window; then pdo_result=interrupted; break; fi
    [ $(( $(perf_now) - pdo_start )) -lt 1800 ] || { pdo_result=time-limit; break; }
    if perf_pkg_running "$pdo_pkg"; then
      if [ "$pdo_force" = 1 ] && ! perf_whitelisted "$pdo_pkg"; then
        cmd activity force-stop "$pdo_pkg" >/dev/null 2>&1; sleep 2
      fi
      perf_pkg_running "$pdo_pkg" && { DB_RUNNING=$((DB_RUNNING + 1)); continue; }
    fi
    for pdo_db in "$DATA_ROOT/$pdo_pkg/databases/"*.db; do
      [ -f "$pdo_db" ] || continue
      [ "$pdo_count" -lt 300 ] || { pdo_result=count-limit; break 2; }
      pdo_reason=$(perf_db_skip_reason "$pdo_pkg" "$pdo_db")
      if [ -n "$pdo_reason" ]; then DB_SKIP=$((DB_SKIP + 1)); continue; fi
      perf_pkg_running "$pdo_pkg" && { DB_RUNNING=$((DB_RUNNING + 1)); continue 2; }
      pdo_count=$((pdo_count + 1))
      pdo_owner=$(stat -c '%u:%g' "$pdo_db" 2>/dev/null)
      pdo_ctx=$(ls -Zd "$pdo_db" 2>/dev/null | awk '{print $1}')
      pdo_before=$(wc -c <"$pdo_db" | tr -d ' ')
      pdo_t=; command -v timeout >/dev/null 2>&1 && pdo_t="timeout 300"
      pdo_ic=$($pdo_t "$DB_SQLITE" "$pdo_db" 'PRAGMA integrity_check;' 2>&1 | head -n 2)
      if [ "$pdo_ic" != ok ]; then DB_SKIP=$((DB_SKIP + 1)); perf_log "数据库完整性检查未通过，跳过 $pdo_pkg/${pdo_db##*/}"; continue; fi
      if $pdo_t "$DB_SQLITE" "$pdo_db" 'REINDEX; VACUUM;' >/dev/null 2>&1; then DB_OK=$((DB_OK + 1)); else DB_FAIL=$((DB_FAIL + 1)); fi
      for pdo_f in "$pdo_db" "$pdo_db-journal" "$pdo_db-wal" "$pdo_db-shm"; do
        [ -e "$pdo_f" ] || continue
        [ -n "$pdo_owner" ] && chown "$pdo_owner" "$pdo_f" 2>/dev/null
        [ -n "$pdo_ctx" ] && chcon "$pdo_ctx" "$pdo_f" 2>/dev/null
      done
      pdo_after=$(wc -c <"$pdo_db" | tr -d ' ')
      case "$pdo_before:$pdo_after" in *[!0-9:]*) ;; *) [ "$pdo_after" -lt "$pdo_before" ] && DB_SAVED=$((DB_SAVED + pdo_before - pdo_after)) ;; esac
    done
  done
  perf_db_status "$pdo_result"
  pdo_src=app; [ "$pdo_trigger" = auto ] && pdo_src=scheduled:perf-tools
  perf_history "性能工具·数据库优化" "$DB_OK" "$DB_FAIL" "数据库优化（$pdo_result）：优化 $DB_OK 个，跳过 $DB_SKIP 个，运行中跳过 $DB_RUNNING 个应用，节省 $((DB_SAVED / 1024)) KB" "$pdo_src"
  perf_log "数据库优化 $pdo_result：优化 $DB_OK 跳过 $DB_SKIP 失败 $DB_FAIL 运行中 $DB_RUNNING 节省 ${DB_SAVED}B"
  exit 0
}

# ---------- 维护窗口 ----------
perf_maint_check() {
  pmc_dex=$(perf_conf dex2oat_auto 0); pmc_db=$(perf_conf dbopt_auto 0)
  [ "$pmc_dex" = 1 ] || [ "$pmc_db" = 1 ] || return 0
  [ "$(perf_uptime)" -ge "$MAINT_MIN_UPTIME" ] || return 0
  pmc_now=$(perf_now)
  pmc_last=$(perf_env_value "$MAINT_STATE" last_check_epoch); case "$pmc_last" in ''|*[!0-9]*) pmc_last=0 ;; esac
  [ "$pmc_last" -le "$pmc_now" ] || pmc_last=0
  [ $((pmc_now - pmc_last)) -ge "$MAINT_CHECK_EVERY" ] || return 0
  pmc_dex_last=$(perf_env_value "$MAINT_STATE" dex2oat_epoch); case "$pmc_dex_last" in ''|*[!0-9]*) pmc_dex_last=0 ;; esac
  pmc_db_last=$(perf_env_value "$MAINT_STATE" dbopt_epoch); case "$pmc_db_last" in ''|*[!0-9]*) pmc_db_last=0 ;; esac
  pmc_dex_due=0; [ "$pmc_dex" = 1 ] && [ $((pmc_now - pmc_dex_last)) -ge 86400 ] && pmc_dex_due=1
  pmc_db_due=0; [ "$pmc_db" = 1 ] && [ $((pmc_now - pmc_db_last)) -ge 86400 ] && pmc_db_due=1
  { echo "last_check_epoch=$pmc_now"; echo "dex2oat_epoch=$pmc_dex_last"; echo "dbopt_epoch=$pmc_db_last"; } >"$MAINT_STATE.tmp.$$" && mv -f "$MAINT_STATE.tmp.$$" "$MAINT_STATE"
  [ "$pmc_dex_due" = 1 ] || [ "$pmc_db_due" = 1 ] || return 0
  [ ! -d "$STATE_DIR/run.lock" ] && [ ! -d "$STATE_DIR/cache-lane.lock" ] || return 0
  perf_in_window || return 0
  [ "$pmc_db_due" = 1 ] && pmc_db_last=$pmc_now
  [ "$pmc_dex_due" = 1 ] && pmc_dex_last=$pmc_now
  { echo "last_check_epoch=$pmc_now"; echo "dex2oat_epoch=$pmc_dex_last"; echo "dbopt_epoch=$pmc_db_last"; } >"$MAINT_STATE.tmp.$$" && mv -f "$MAINT_STATE.tmp.$$" "$MAINT_STATE"
  perf_detach maint-run "$pmc_db_due" "$pmc_dex_due"
}

perf_check() {
  [ -f "$CONF" ] || exit 0
  pck_loop=0; { perf_flag freeze_enabled || perf_flag mem_enabled; } && pck_loop=1
  pck_maint=0; { perf_flag dex2oat_auto || perf_flag dbopt_auto; } && pck_maint=1
  [ "$pck_loop" = 1 ] || [ "$pck_maint" = 1 ] || exit 0
  # 开机保护：boot_completed 之后 120 秒内什么都不做。
  perf_settled || exit 0
  if [ "$pck_loop" = 1 ] && ! perf_lock_alive "$LOOP_LOCK"; then perf_detach loop; fi
  [ "$pck_maint" = 1 ] && perf_maint_check
  exit 0
}

[ "${BAIZE_PERF_LIB:-0}" = 1 ] && return 0 2>/dev/null
ACTION=${1:-check}
[ -d "$STATE_DIR" ] || exit 0
case "$ACTION" in
  check) perf_check ;;
  loop) perf_loop ;;
  start-dex2oat)
    perf_lock_alive "$DEX_LOCK" && { echo busy; exit 3; }
    perf_detach dex2oat manual; echo started; exit 0 ;;
  stop-dex2oat) perf_lock_alive "$DEX_LOCK" && touch "$DEX_STOP"; exit 0 ;;
  dex2oat) perf_dex2oat "${2:-manual}" ;;
  dbopt) perf_dbopt "${2:-auto}" ;;
  maint-run)
    perf_low_priority
    [ "${2:-0}" = 1 ] && sh "$SELF" dbopt auto </dev/null >/dev/null 2>&1
    [ "${3:-0}" = 1 ] && perf_in_window && sh "$SELF" dex2oat auto </dev/null >/dev/null 2>&1
    exit 0 ;;
  thaw-all)
    THAWED_TOTAL=0; perf_thaw ""; echo "thawed=$THAWED_TOTAL"; exit 0 ;;
  status) cat "$STATUS" "$DEX_STATUS" "$DB_STATUS" 2>/dev/null; exit 0 ;;
  *) echo "用法: perf-tools.sh check|loop|start-dex2oat|stop-dex2oat|dex2oat|dbopt|thaw-all|status" >&2; exit 2 ;;
esac
