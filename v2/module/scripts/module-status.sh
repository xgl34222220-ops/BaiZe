#!/system/bin/sh
# 模块状态：只读固定文件，O(1)，不遍历目录。
#   description <module.prop> <app_install_result>  在模块列表描述前加/去掉需要处理的提示
#   summary <state_dir> <module.prop>                 为 action.sh 打印简短状态
# 参考 KernelSU / Magisk 常见模块在描述里展示运行状态的做法；累计清理统计仍由清理任务写入。
set -u

WARN_MARK='⚠ '

status_warning() {
  case "$1" in
    ready) printf '' ;;
    signature_mismatch) printf '%s' 'App 签名与模块不一致，请卸载旧版 App 后重新刷入' ;;
    failed) printf '%s' 'App 安装未完成，请打开模块操作按钮或重新刷入' ;;
    missing) printf '%s' '未找到白泽 App，请重新刷入模块' ;;
    *) printf '%s' '模块状态未知，请打开白泽 App 查看' ;;
  esac
}

update_description() {
  prop=$1; result=$2
  [ -f "$prop" ] && [ ! -L "$prop" ] || return 0
  warning=$(status_warning "$result")
  tmp="$prop.tmp.$$"
  # 先去掉旧提示，再按本次结果决定是否加新提示；其余行与累计统计原样保留。
  awk -v mark="$WARN_MARK" -v warning="$warning" '
    BEGIN { found=0 }
    /^description=/ {
      d=substr($0, 13)
      if (index(d, mark) == 1) { cut=index(d, " | "); d=(cut > 0) ? substr(d, cut + 3) : "" }
      if (warning != "") d=(d == "") ? mark warning : mark warning " | " d
      print "description=" d; found=1; next
    }
    { print }
    END { if (!found && warning != "") print "description=" mark warning }
  ' "$prop" >"$tmp" 2>/dev/null || { rm -f "$tmp"; return 0; }
  if cmp -s "$tmp" "$prop" 2>/dev/null; then rm -f "$tmp"; return 0; fi
  chmod 0644 "$tmp" 2>/dev/null
  mv -f "$tmp" "$prop" 2>/dev/null || rm -f "$tmp"
}

env_value() { sed -n "s/^$2=//p" "$1" 2>/dev/null | tail -n 1; }

summary() {
  state_dir=$1; prop=$2
  version=$(env_value "$prop" version)
  installed=$(env_value "$state_dir/module.env" app_installed)
  result=$(env_value "$state_dir/module.env" app_install_result)
  enabled=$(env_value "$state_dir/config.conf" enabled)
  description=$(env_value "$prop" description)
  echo "白泽模块 ${version:-未知版本}"
  case "$installed" in
    1) echo "· App：已安装" ;;
    0) echo "· App：未安装（$(status_warning "${result:-missing}")）" ;;
    *) echo "· App：尚未完成开机检查" ;;
  esac
  case "$enabled" in 0) echo "· 自动清理：已暂停" ;; 1) echo "· 自动清理：已开启（开机 2 分钟后、满足亮灭屏/电量/温度条件才会运行）" ;; *) echo "· 自动清理：使用默认设置" ;; esac
  if [ -f "$state_dir/running.env" ]; then echo "· 当前有清理任务在运行"; fi
  case "$description" in
    *累计清理*) echo "· ${description#"$WARN_MARK"*" | "}" ;;
    *) echo "· 尚无清理记录" ;;
  esac
}

case "${1:-}" in
  description) [ "$#" -eq 3 ] || exit 2; update_description "$2" "$3" ;;
  summary) [ "$#" -eq 3 ] || exit 2; summary "$2" "$3" ;;
  *) echo "usage: module-status.sh description <module.prop> <result> | summary <state_dir> <module.prop>" >&2; exit 2 ;;
esac
