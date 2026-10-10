#!/usr/bin/env bash
# 性能工具（实验）：白名单、后台时长、内存阈值/冷却、数据库黑名单与跳过规则、开机保护、
# cgroup v2 冻结/解冻、不支持时停用（绝不强制停止）、Dex2oat 进度与审计记录。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
SCRIPTS="$ROOT/v2/module/scripts"
T=$(mktemp -d "${TMPDIR:-/tmp}/baize-perf.XXXXXX")
trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $*" >&2; cat "$FAKE/cmd.calls" "$FAKE/detach.calls" >&2 2>/dev/null; exit 1; }

mkdir -p "$T/bin" "$T/module/scripts"
cp "$SCRIPTS/perf-tools.sh" "$SCRIPTS/state-retention.sh" "$T/module/scripts/"
cat >"$T/bin/ps" <<'EOF'
#!/bin/sh
case "$*" in
  *PID,UID,NAME*) echo "PID UID NAME"; cat "$FAKE/ps.txt" ;;
  *NAME*) echo NAME; awk '{print $3}' "$FAKE/ps.txt" ;;
esac
EOF
cat >"$T/bin/cmd" <<'EOF'
#!/bin/sh
echo "$*" >>"$FAKE/cmd.calls"
case "$1 $2" in
  "package list") if [ "${4:-}" = -3 ]; then printf 'package:com.example.bg\npackage:com.example.fg\npackage:com.tencent.mm\npackage:com.example.cached\n'
                  else printf 'package:android\npackage:com.example.bg\n'; fi ;;
  "package compile") echo Success ;;
  "package resolve-activity") echo "priority=0"; echo "com.example.launcher/.Home" ;;
  "settings get") case "$4" in enabled_input_methods) echo "com.example.ime/.Ime;123:com.other.ime/.K" ;; sms_default_application) echo com.example.sms ;; *) echo null ;; esac ;;
  "activity send-trim-memory"|"activity kill"|"activity force-stop") : ;;
esac
EOF
cat >"$T/bin/getprop" <<'EOF'
#!/bin/sh
cat "$FAKE/boot_completed"
EOF
cat >"$T/bin/dumpsys" <<'EOF'
#!/bin/sh
case "$1" in
  battery) if [ "$(cat "$FAKE/charging")" = 1 ]; then echo '  AC powered: true'; else echo '  AC powered: false'; echo '  status: 3'; fi ;;
  power) if [ "$(cat "$FAKE/screen")" = off ]; then echo 'Display Power: state=OFF'; else echo 'Display Power: state=ON'; fi ;;
esac
EOF
printf '#!/bin/sh\nexit 0\n' >"$T/bin/renice"
for b in ionice telecom pkill logcat settings setsid; do cp "$T/bin/renice" "$T/bin/$b"; done
chmod 0755 "$T/bin/"*

shells=(sh)
command -v busybox >/dev/null 2>&1 && shells+=("busybox ash")

reset_case() {
  rm -rf "$T/state" "$T/fake"
  mkdir -p "$T/state/logs" "$T/fake/proc" "$T/fake/cg" "$T/fake/data"
  export FAKE="$T/fake"
  echo 1 >"$FAKE/boot_completed"; echo 1 >"$FAKE/charging"; echo off >"$FAKE/screen"
  echo "5000.00 1000.00" >"$FAKE/proc/uptime"
  echo "test-boot-1" >"$FAKE/boot_id"
  echo "boot_epoch=$(( $(date +%s) - 3600 ))" >"$T/state/module.env"
  : >"$FAKE/cmd.calls"
  # pid uid name adj
  : >"$FAKE/ps.txt"
  add_proc 2001 10100 com.example.bg 905
  add_proc 2002 10101 com.example.fg 0
  add_proc 2003 10102 com.tencent.mm 920
  add_proc 2004 10102 com.tencent.mm:push 920
  add_proc 2005 10103 com.example.cached 950
  add_proc 2006 1000 system_server -900
  printf 'MemTotal: 8000000 kB\nMemAvailable: 3000000 kB\n' >"$FAKE/proc/meminfo"
}
add_proc() {
  echo "$1 $2 $3" >>"$FAKE/ps.txt"
  mkdir -p "$FAKE/proc/$1" "$FAKE/cg/uid_$2/pid_$1"
  echo "$4" >"$FAKE/proc/$1/oom_score_adj"
  echo "0::/uid_$2/pid_$1" >"$FAKE/proc/$1/cgroup"
  echo 0 >"$FAKE/cg/uid_$2/pid_$1/cgroup.freeze"
}
export_env() {
  export PATH="$T/bin:$PATH" BAIZE_MODULE_DIR="$T/module" BAIZE_STATE_DIR="$T/state"
  export BAIZE_PERF_PROC="$T/fake/proc" BAIZE_PERF_CGROUP="$T/fake/cg" BAIZE_PERF_DATA_ROOT="$T/fake/data"
  export BAIZE_PERF_BOOT_ID_FILE="$T/fake/boot_id" BAIZE_PERF_PS="$T/bin/ps" BAIZE_PERF_DETACH_LOG="$T/fake/detach.calls"
}
# 在指定 shell 中以库模式加载脚本并执行一段代码
lib() { local shell=$1; shift; BAIZE_PERF_LIB=1 $shell -c ". \"$T/module/scripts/perf-tools.sh\"; $*"; }

for shell in "${shells[@]}"; do
  reset_case; export_env

  # --- 白名单：整应用条目 vs 单进程条目 ---
  lib "$shell" 'perf_whitelisted com.tencent.mm' || fail "$shell: com.tencent.mm 应默认受保护"
  lib "$shell" 'perf_whitelisted com.tencent.mm:tools' || fail "$shell: 整应用条目应覆盖其全部进程"
  lib "$shell" 'perf_whitelisted com.tencent.mobileqq:MSF' || fail "$shell: QQ MSF 应受保护"
  lib "$shell" 'perf_whitelisted com.example.bg' && fail "$shell: 普通应用不应在默认白名单"
  lib "$shell" 'PERF_WL="com.foo:push"; perf_whitelisted com.foo:push' || fail "$shell: 进程条目应保护该进程"
  lib "$shell" 'PERF_WL="com.foo:push"; perf_whitelisted com.foo' && fail "$shell: 进程条目不应保护主进程"
  printf 'com.user.keep  # 注释\nbad name!\n../etc\n' >"$T/state/perf-whitelist.conf"
  out=$(lib "$shell" 'perf_build_whitelist; printf "%s\n" "$PERF_WL"')
  for want in com.user.keep com.example.ime com.other.ime com.example.launcher com.example.sms; do
    printf '%s\n' "$out" | grep -qx "$want" || fail "$shell: 白名单缺少 $want"
  done
  printf '%s\n' "$out" | grep -q 'bad\|\.\./' && fail "$shell: 非法白名单条目未被丢弃"

  # --- 后台时长 ---
  lib "$shell" 'perf_freeze_due 1000 1599 10' && fail "$shell: 不足 10 分钟不应冻结"
  lib "$shell" 'perf_freeze_due 1000 1600 10' || fail "$shell: 满 10 分钟应冻结"
  lib "$shell" 'perf_freeze_due 2000 1000 1' && fail "$shell: 时钟回拨不应冻结"

  # --- 内存阈值与冷却 ---
  [ "$(lib "$shell" 'perf_mem_decision 2000000 1024 0 5000 300')" = none ] || fail "$shell: 内存充足应为 none"
  [ "$(lib "$shell" 'perf_mem_decision 900000 1024 0 5000 300')" = low ] || fail "$shell: 低于阈值应为 low"
  [ "$(lib "$shell" 'perf_mem_decision 400000 1024 0 5000 300')" = critical ] || fail "$shell: 低于一半应为 critical"
  [ "$(lib "$shell" 'perf_mem_decision 400000 1024 4900 5000 300')" = none ] || fail "$shell: 冷却期内不应执行"
  [ "$(lib "$shell" 'perf_mem_decision 400000 1024 4600 5000 300')" = critical ] || fail "$shell: 冷却结束应执行"
  [ "$(lib "$shell" 'perf_mem_decision abc 1024 0 5000 300')" = none ] || fail "$shell: 非法输入应为 none"

  # --- 数据库黑名单与跳过规则 ---
  db="$FAKE/data/com.example.bg/databases"; mkdir -p "$db"
  { printf 'SQLite format 3\000'; head -c 8192 /dev/zero; } >"$db/a.db"
  { printf 'garbage-bytes!!!'; head -c 8192 /dev/zero; } >"$db/b.db"
  cp "$db/a.db" "$db/EnMicroMsg.db"; cp "$db/a.db" "$db/w.db"; printf 'x' >"$db/w.db-wal"
  pk='PERF_USER_PKGS="com.example.bg
com.tencent.mm"'
  [ -z "$(lib "$shell" "$pk; perf_db_skip_reason com.example.bg '$db/a.db'")" ] || fail "$shell: 正常数据库不应跳过"
  [ "$(lib "$shell" "$pk; perf_db_skip_reason com.example.bg '$db/b.db'")" = encrypted-or-not-sqlite ] || fail "$shell: 非 SQLite 头应跳过"
  [ "$(lib "$shell" "$pk; perf_db_skip_reason com.example.bg '$db/EnMicroMsg.db'")" = encrypted-name ] || fail "$shell: EnMicroMsg 应跳过"
  [ "$(lib "$shell" "$pk; perf_db_skip_reason com.example.bg '$db/w.db'")" = wal-in-use ] || fail "$shell: WAL 使用中应跳过"
  [ "$(lib "$shell" "$pk; perf_db_skip_reason com.tencent.mm '$db/a.db'")" = blacklist ] || fail "$shell: 微信应在默认黑名单"
  [ "$(lib "$shell" "$pk; perf_db_skip_reason com.tencent.mobileqq '$db/a.db'")" = blacklist ] || fail "$shell: QQ 应在默认黑名单"
  [ "$(lib "$shell" "$pk; perf_db_skip_reason com.android.providers.contacts '$db/a.db'")" = system-app ] || fail "$shell: 系统应用应跳过"
  echo com.example.bg >"$T/state/perf-db-blacklist.conf"
  [ "$(lib "$shell" "$pk; perf_db_skip_reason com.example.bg '$db/a.db'")" = blacklist ] || fail "$shell: 用户黑名单应生效"
  rm -f "$T/state/perf-db-blacklist.conf"

  # --- 开机保护：无配置 / 全关 / 未稳定 时什么都不启动 ---
  : >"$FAKE/detach.calls"; : >"$FAKE/cmd.calls"
  $shell "$T/module/scripts/perf-tools.sh" check
  [ ! -s "$FAKE/detach.calls" ] || fail "$shell: 无配置不应启动任何东西"
  printf 'freeze_enabled=0\nmem_enabled=0\n' >"$T/state/perf-tools.conf"
  $shell "$T/module/scripts/perf-tools.sh" check
  [ ! -s "$FAKE/detach.calls" ] && [ ! -s "$FAKE/cmd.calls" ] || fail "$shell: 全部关闭时不应启动或查询系统"
  printf 'freeze_enabled=1\n' >"$T/state/perf-tools.conf"
  echo 0 >"$FAKE/boot_completed"
  $shell "$T/module/scripts/perf-tools.sh" check
  [ ! -s "$FAKE/detach.calls" ] || fail "$shell: boot_completed 之前不应启动"
  echo 1 >"$FAKE/boot_completed"; echo "boot_epoch=$(( $(date +%s) - 60 ))" >"$T/state/module.env"
  $shell "$T/module/scripts/perf-tools.sh" check
  [ ! -s "$FAKE/detach.calls" ] || fail "$shell: boot_completed 后 120 秒内不应启动"
  echo "boot_epoch=$(( $(date +%s) - 300 ))" >"$T/state/module.env"
  $shell "$T/module/scripts/perf-tools.sh" check
  grep -qx 'sh .*perf-tools.sh loop' "$FAKE/detach.calls" || fail "$shell: 稳定后应启动轮询"
  [ ! -s "$FAKE/cmd.calls" ] || fail "$shell: check 本身不应查询应用"

  # --- 冻结：首轮只计时，到时冻结；白名单、前台不冻结；回到前台解冻 ---
  round='perf_refresh_user_pkgs; SNAPSHOT=$(perf_snapshot); PKG_ADJ=$(perf_pkg_min_adj "$SNAPSHOT")'
  lib "$shell" "perf_frozen_init; $round; perf_freeze_round 1000 1; echo \"\$BG_LIST\" >\"$T/bg\"; perf_freeze_round 1030 1;
    echo \"\$FREEZE_SUPPORT\" >\"$T/support\"; [ \"\$(perf_frozen_count)\" = 0 ] || exit 7
    perf_freeze_round 1100 1; echo \"\$FREEZE_SUPPORT\" >\"$T/support\"; echo \"\$BG_LIST\" >\"$T/bg2\"
    cp \"\$FROZEN\" \"$T/frozen\"
    echo 0 >\"$FAKE/proc/2001/oom_score_adj\"; $round; perf_freeze_round 1130 1; perf_frozen_count >\"$T/after\""
  [ "$(cat "$FAKE/cg/uid_10100/pid_2001/cgroup.freeze")" = 0 ] || fail "$shell: 回到前台后应解冻"
  grep -q $'\tcom.example.bg\t2001\t' "$T/frozen" || fail "$shell: 后台应用到时应被冻结"
  grep -q $'\tcom.example.cached\t2005\t' "$T/frozen" || fail "$shell: 缓存应用到时应被冻结"
  grep -q 'com.tencent.mm' "$T/frozen" && fail "$shell: 白名单应用不应冻结"
  grep -q 'com.example.fg' "$T/frozen" && fail "$shell: 前台应用不应冻结"
  [ "$(cat "$T/support")" = yes ] || fail "$shell: 应识别 cgroup v2 冻结"
  [ "$(cat "$T/after")" = 1 ] || fail "$shell: 只应剩 cached 一个冻结记录"
  [ "$(cat "$FAKE/cg/uid_10102/pid_2003/cgroup.freeze")" = 0 ] || fail "$shell: 微信不应被写冻结"
  # 被系统/前台唤醒外部解冻：重新计时，不会立刻再冻
  echo 0 >"$FAKE/cg/uid_10103/pid_2005/cgroup.freeze"
  lib "$shell" "BG_LIST=\$(cat \"$T/bg2\"); $round; perf_freeze_round 1160 1; perf_frozen_count >\"$T/after2\""
  [ "$(cat "$T/after2")" = 0 ] || fail "$shell: 外部解冻后应重新计时"
  # thaw-all
  echo 1 >"$FAKE/cg/uid_10103/pid_2005/cgroup.freeze"
  printf '#boot=test-boot-1\n%s\tcom.example.cached\t2005\t1\n' "$FAKE/cg/uid_10103/pid_2005/cgroup.freeze" >"$T/state/perf-frozen.tsv"
  $shell "$T/module/scripts/perf-tools.sh" thaw-all | grep -qx 'thawed=1' || fail "$shell: thaw-all 应报告 1"
  [ "$(cat "$FAKE/cg/uid_10103/pid_2005/cgroup.freeze")" = 0 ] || fail "$shell: thaw-all 应解冻"
  # 记录文件被篡改指向 uid 级 / 任意路径：绝不写入
  mkdir -p "$FAKE/cg/uid_10103"; echo 1 >"$FAKE/cg/uid_10103/cgroup.freeze"; echo keep >"$T/victim"
  printf '#boot=test-boot-1\n%s\tx\t1\t1\n%s\tx\t1\t1\n' "$FAKE/cg/uid_10103/cgroup.freeze" "$T/victim" >"$T/state/perf-frozen.tsv"
  $shell "$T/module/scripts/perf-tools.sh" thaw-all >/dev/null
  [ "$(cat "$FAKE/cg/uid_10103/cgroup.freeze")" = 1 ] && [ "$(cat "$T/victim")" = keep ] || fail "$shell: 不应写入非 pid 级 cgroup 或任意文件"

  # --- 不支持 cgroup v2：停用，绝不 force-stop ---
  reset_case
  echo "0::/" >"$FAKE/proc/2001/cgroup"; echo "0::/" >"$FAKE/proc/2005/cgroup"
  : >"$FAKE/cmd.calls"
  lib "$shell" "perf_frozen_init; $round; perf_freeze_round 1000 1; perf_freeze_round 1100 1; echo \"\$FREEZE_SUPPORT\" >\"$T/support\""
  [ "$(cat "$T/support")" = no ] || fail "$shell: 无 pid 级 cgroup 应判定不支持"
  grep -q 'force-stop\|activity kill' "$FAKE/cmd.calls" && fail "$shell: 不支持时不得改用强制停止"

  # --- 内存压制：低于阈值发送回收；kill 只在开关打开且应用为缓存状态时；冷却 ---
  reset_case
  printf 'MemAvailable: 700000 kB\n' >"$FAKE/proc/meminfo"
  lib "$shell" "$round; perf_mem_round 5000 1024 300 0; perf_mem_round 5100 1024 300 0"
  grep -q 'activity send-trim-memory 2001 COMPLETE' "$FAKE/cmd.calls" || fail "$shell: 应向缓存进程发送 COMPLETE"
  grep -q 'send-trim-memory 2002' "$FAKE/cmd.calls" && fail "$shell: 前台进程不应收到回收"
  grep -q 'send-trim-memory 2003\|send-trim-memory 2004' "$FAKE/cmd.calls" && fail "$shell: 白名单进程不应收到回收"
  grep -q 'activity kill' "$FAKE/cmd.calls" && fail "$shell: 未开启结束进程不应 kill"
  [ "$(grep -c 'send-trim-memory 2001' "$FAKE/cmd.calls")" = 1 ] || fail "$shell: 冷却期内不应重复"
  : >"$FAKE/cmd.calls"
  lib "$shell" "$round; perf_mem_round 6000 1024 300 1"
  grep -q 'activity kill com.example.cached' "$FAKE/cmd.calls" || fail "$shell: 开启后应结束缓存应用"
  grep -q 'activity kill com.tencent.mm\|activity kill com.example.fg' "$FAKE/cmd.calls" && fail "$shell: 不应结束白名单/前台应用"

  # --- 轮询一次后退出：解冻全部并写状态与审计 ---
  reset_case
  printf 'freeze_enabled=1\nmem_enabled=1\nfreeze_after_minutes=1\n' >"$T/state/perf-tools.conf"
  BAIZE_PERF_ONCE=1 $shell "$T/module/scripts/perf-tools.sh" loop
  grep -q '^loop_state=stopped' "$T/state/perf-tools.env" || fail "$shell: 轮询退出应写 stopped"
  grep -q '性能工具·轮询' "$T/state/history.tsv" || fail "$shell: 轮询启动应写入审计"
  grep "性能工具·轮询" "$T/state/history.tsv" | awk -F "\t" '$11 != "not_applicable" {exit 1}' || fail "$shell: 审计记录应标记不计释放空间"
  [ ! -d "$T/state/perf-loop.lock" ] || fail "$shell: 退出后应释放锁"

  # --- 数据库优化：无 sqlite3 时明确不可用 ---
  reset_case
  BAIZE_PERF_SQLITE="$T/none/sqlite3" $shell "$T/module/scripts/perf-tools.sh" dbopt auto
  grep -q '^reason=设备无 sqlite3，暂不可用' "$T/state/perf-dbopt.env" || fail "$shell: 无 sqlite3 应提示不可用"
  # 有 sqlite3：完整性检查通过才 VACUUM；运行中、黑名单、加密跳过
  cat >"$T/bin/sqlite3" <<'EOF'
#!/bin/sh
echo "$*" >>"$FAKE/sqlite.calls"
case "$2" in *integrity_check*) case "$1" in *bad.db) echo "*** corrupt" ;; *) echo ok ;; esac ;; esac
EOF
  chmod 0755 "$T/bin/sqlite3"; : >"$FAKE/sqlite.calls"
  for p in com.example.bg com.example.fg com.tencent.mm; do
    mkdir -p "$FAKE/data/$p/databases"
    { printf 'SQLite format 3\000'; head -c 8192 /dev/zero; } >"$FAKE/data/$p/databases/main.db"
  done
  cp "$FAKE/data/com.example.bg/databases/main.db" "$FAKE/data/com.example.bg/databases/bad.db"
  # fg 正在运行（在 ps 里），bg 也在 ps 里 → 先移除 bg 进程，模拟未运行
  grep -v 'com.example.bg' "$FAKE/ps.txt" >"$FAKE/ps.new"; mv "$FAKE/ps.new" "$FAKE/ps.txt"
  BAIZE_PERF_SQLITE="$T/bin/sqlite3" $shell "$T/module/scripts/perf-tools.sh" dbopt auto
  grep -q 'com.example.bg/databases/main.db REINDEX; VACUUM;' "$FAKE/sqlite.calls" || fail "$shell: 未运行应用的数据库应被优化"
  grep -q 'bad.db REINDEX' "$FAKE/sqlite.calls" && fail "$shell: 完整性检查失败不应 VACUUM"
  grep -q 'com.example.fg' "$FAKE/sqlite.calls" && fail "$shell: 运行中的应用不应处理"
  grep -q 'com.tencent.mm' "$FAKE/sqlite.calls" && fail "$shell: 黑名单应用不应处理"
  grep -q 'force-stop' "$FAKE/cmd.calls" && fail "$shell: 未开启时不应强制停止"
  grep -q '^optimized=1' "$T/state/perf-dbopt.env" || fail "$shell: 应优化 1 个"
  grep -q '性能工具·数据库优化' "$T/state/history.tsv" || fail "$shell: 数据库优化应写入审计"

  # --- Dex2oat：进度、模式、审计 ---
  reset_case
  printf 'dex2oat_mode=speed\ndex2oat_scope=user\ndex2oat_force=1\n' >"$T/state/perf-tools.conf"
  $shell "$T/module/scripts/perf-tools.sh" dex2oat manual
  grep -q '^state=completed' "$T/state/perf-dex2oat.env" || fail "$shell: 编译应完成"
  grep -q '^total=4' "$T/state/perf-dex2oat.env" && grep -q '^ok=4' "$T/state/perf-dex2oat.env" || fail "$shell: 进度计数错误"
  grep -q 'package compile -m speed -f com.example.bg' "$FAKE/cmd.calls" || fail "$shell: 应使用所选模式与强制重新编译"
  grep -q '性能工具·Dex2oat编译' "$T/state/history.tsv" || fail "$shell: 编译应写入审计"
  printf 'dex2oat_mode=rm -rf\n' >"$T/state/perf-tools.conf"; : >"$FAKE/cmd.calls"
  $shell "$T/module/scripts/perf-tools.sh" dex2oat manual
  grep -q 'compile -m speed-profile com.example.bg' "$FAKE/cmd.calls" || fail "$shell: 非法模式应回落 speed-profile"

  # --- 维护窗口：亮屏不运行；充电息屏且到期才运行 ---
  reset_case
  printf 'dex2oat_auto=1\n' >"$T/state/perf-tools.conf"; echo on >"$FAKE/screen"; : >"$FAKE/detach.calls"
  $shell "$T/module/scripts/perf-tools.sh" check
  [ ! -s "$FAKE/detach.calls" ] || fail "$shell: 亮屏时不应自动编译"
  echo off >"$FAKE/screen"; rm -f "$T/state/perf-maint.env"
  echo "600.00 1.00" >"$FAKE/proc/uptime"
  $shell "$T/module/scripts/perf-tools.sh" check
  [ ! -s "$FAKE/detach.calls" ] || fail "$shell: 开机 15 分钟内不应自动编译"
  echo "5000.00 1.00" >"$FAKE/proc/uptime"
  $shell "$T/module/scripts/perf-tools.sh" check
  grep -q 'maint-run 0 1' "$FAKE/detach.calls" || fail "$shell: 充电息屏应进入维护运行"
  : >"$FAKE/detach.calls"
  $shell "$T/module/scripts/perf-tools.sh" check
  [ ! -s "$FAKE/detach.calls" ] || fail "$shell: 每日最多一次"

  # 状态文件数量有上限：只有固定文件名
  extra=$(ls "$T/state" | grep -v -E '^(perf-tools\.conf|perf-tools\.env|perf-dex2oat\.env|perf-dbopt\.env|perf-maint\.env|perf-frozen\.tsv|perf-tools\.log|history\.tsv|module\.env|logs)$' || true)
  [ -z "$extra" ] || fail "$shell: 出现未登记的状态文件: $extra"
done
echo "perf-tools ok"
