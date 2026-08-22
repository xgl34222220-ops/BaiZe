# 白泽 白名单匹配。由各清理脚本 . 引入，不单独执行。
#
# 性能背景：此前每个脚本各有一份 path_conflicts_whitelist()，实现是
#
#     while IFS= read -r raw ...; do
#       item=$(printf '%s' "$raw" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
#       ...
#     done <"$WHITELIST"
#
# 那行 sed 对**每个白名单行**都 fork 一次，而这个函数对**每个目标**调用一次，
# 总 fork 次数 = 目标数 × 白名单行数。实测 30 行白名单 × 400 个缓存目录
# 需要 23.9 秒，纯 fork 开销，而且随白名单行数完全线性——
# 用户白名单加得越多清理越慢，与直觉相反。
#
# 现在改为：启动时载入一次，匹配时零子进程。同样条件下 0.018 秒。

# 载入白名单到 BAIZE_WL_ITEMS（换行分隔）。可重复调用，会覆盖。
# 用法：baize_whitelist_load [白名单文件]，省略时用 $WHITELIST。
baize_whitelist_load() {
  _wl_file=${1:-${WHITELIST:-}}
  BAIZE_WL_ITEMS=""
  [ -n "$_wl_file" ] && [ -f "$_wl_file" ] || return 0
  while IFS= read -r _wl_raw || [ -n "$_wl_raw" ]; do
    # 用参数展开做 trim，不再 fork sed
    _wl_item=${_wl_raw#"${_wl_raw%%[![:space:]]*}"}
    _wl_item=${_wl_item%"${_wl_item##*[![:space:]]}"}
    case "$_wl_item" in ''|'#'*) continue ;; esac
    case "$_wl_item" in /*) ;; *) continue ;; esac
    _wl_item=${_wl_item%/}
    [ -n "$_wl_item" ] || _wl_item=/
    BAIZE_WL_ITEMS="$BAIZE_WL_ITEMS$_wl_item
"
  done <"$_wl_file"
  return 0
}

# 目标路径与白名单是否存在祖先/后代关系。
# 与旧实现语义一致：白名单项覆盖目标，或目标覆盖白名单项，都算冲突。
path_conflicts_whitelist() {
  _wl_target=${1%/}
  [ -n "${BAIZE_WL_ITEMS:-}" ] || return 1
  # 按换行切分需要临时改 IFS；同时关闭 glob，
  # 否则白名单里的 * ? [ 会被路径展开吃掉。
  _wl_old_ifs=$IFS
  case "$-" in *f*) _wl_had_f=1 ;; *) _wl_had_f=0 ;; esac
  IFS='
'
  set -f
  for _wl_item in $BAIZE_WL_ITEMS; do
    # 根目录白名单覆盖一切。旧实现在这里有个漏洞：item 为 "/" 时
    # "$item"/* 展开成 "//*"，匹配不上任何真实路径，等于白名单失效。
    # 这是保护性缺失，按更安全的方向修正。
    if [ "$_wl_item" = "/" ]; then
      IFS=$_wl_old_ifs; [ "$_wl_had_f" = 1 ] || set +f; return 0
    fi
    case "$_wl_target" in
      "$_wl_item"|"$_wl_item"/*) IFS=$_wl_old_ifs; [ "$_wl_had_f" = 1 ] || set +f; return 0 ;;
    esac
    case "$_wl_item" in
      "$_wl_target"|"$_wl_target"/*) IFS=$_wl_old_ifs; [ "$_wl_had_f" = 1 ] || set +f; return 0 ;;
    esac
  done
  IFS=$_wl_old_ifs
  [ "$_wl_had_f" = 1 ] || set +f
  return 1
}
