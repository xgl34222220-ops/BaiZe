# 白泽 ABI 解析。由各清理脚本 . 引入，不单独执行。
#
# 此前所有脚本把引擎路径硬编码为 bin/arm64-v8a/baize_engine，
# 并对非 arm64 设备直接 exit 8。现在按设备实际架构解析，
# 只有当对应 ABI 的引擎确实不在包里时才报错。

# 输出当前设备优先匹配的 ABI 列表（按优先级换行分隔）。
baize_device_abis() {
  # getprop 在 Android 上可用，给出的顺序即系统优先级
  if command -v getprop >/dev/null 2>&1; then
    _list=$(getprop ro.product.cpu.abilist 2>/dev/null | tr ',' '\n' | sed '/^$/d')
    if [ -n "$_list" ]; then
      printf '%s\n' "$_list"
      return 0
    fi
  fi
  # 退回到 uname
  case "$(uname -m 2>/dev/null)" in
    aarch64|arm64) printf 'arm64-v8a\narmeabi-v7a\n' ;;
    armv7l|armv8l|arm) printf 'armeabi-v7a\n' ;;
    x86_64|amd64) printf 'x86_64\nx86\n' ;;
    i?86) printf 'x86\n' ;;
    *) return 1 ;;
  esac
}

# baize_resolve_engine <模块目录> <引擎名>
# 成功时输出可执行文件路径并返回 0。
baize_resolve_engine() {
  _moddir=$1
  _name=$2
  for _abi in $(baize_device_abis); do
    _candidate="$_moddir/bin/$_abi/$_name"
    if [ -x "$_candidate" ]; then
      printf '%s\n' "$_candidate"
      return 0
    fi
  done
  return 1
}

# baize_require_engine <模块目录> <引擎名> <环境变量覆盖值>
# 打印路径；找不到时输出中文错误并返回 8，与既有退出码语义一致。
baize_require_engine() {
  _moddir=$1
  _name=$2
  _override=${3:-}
  if [ -n "$_override" ]; then
    if [ -x "$_override" ]; then
      printf '%s\n' "$_override"
      return 0
    fi
    echo "指定的引擎不可执行：$_override" >&2
    return 8
  fi
  if _path=$(baize_resolve_engine "$_moddir" "$_name"); then
    printf '%s\n' "$_path"
    return 0
  fi
  _abis=$(baize_device_abis 2>/dev/null | tr '\n' ' ')
  echo "当前架构（$_abis）没有可用的 $_name，请重新刷入完整模块" >&2
  return 8
}
