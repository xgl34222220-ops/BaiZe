#!/system/bin/sh

title=${1:-白泽}
body=${2:-清理任务已完成}
short=${3:-$body}
tag=${4:-baize}

posted_ok() {
  case "$1" in
    *'posted:'*) return 0 ;;
  esac
  return 1
}

last_output=""

# 部分 ROM 只允许 shell UID 的通知通道真正显示，优先尝试该通道。
if command -v su >/dev/null 2>&1; then
  output=$(su 2000 -c "cmd notification post -v -S bigtext --bigtext '$body' -t '$title' '$tag' '$short'" 2>&1)
  if posted_ok "$output"; then
    echo "ok:shell-bigtext"
    exit 0
  fi
  last_output=$output
fi

if ! command -v cmd >/dev/null 2>&1; then
  echo "系统缺少 cmd 命令"
  exit 1
fi

output=$(cmd notification post -v -S bigtext --bigtext "$body" -t "$title" "$tag" "$short" 2>&1)
if posted_ok "$output"; then
  echo "ok:root-bigtext"
  exit 0
fi
last_output=$output

# 厂商裁剪大文本样式时回退为基础通知。
output=$(cmd notification post -v -t "$title" "$tag" "$body" 2>&1)
if posted_ok "$output"; then
  echo "ok:root-basic"
  exit 0
fi
last_output=$output

reason=$(printf '%s\n' "$last_output" | grep -Ei 'error|warning|denied|exception|unknown' | tail -n 1)
[ -n "$reason" ] || reason=$(printf '%s\n' "$last_output" | tail -n 1)
echo "${reason:-系统通知服务未确认投递}"
exit 1
