from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "cleaner.sh"
text = path.read_text()


def rep(old: str, new: str, label: str) -> None:
    global text
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"missing anchor: {label}")
    text = text.replace(old, new, 1)

rep(
    """DEEP_TRUNCATED=0
CACHE_SLOW_DIRS=0
CACHE_TRUNCATED=0
COMMAND_TIMEOUT_MODE=""
""",
    """DEEP_TRUNCATED=0
DEEP_RULE_PARSE_SECONDS=0
DEEP_STAGE_SECONDS=0
DEEP_SLOWEST_SECONDS=0
DEEP_SLOWEST_PATH=""
CACHE_SLOW_DIRS=0
CACHE_TRUNCATED=0
COMMAND_TIMEOUT_MODE=""
WATCHDOG_SEQ=0
""",
    "metric variables",
)

rep(
    """run_limited_command() {
  seconds=$1
  shift
  ensure_timeout_runtime
  case "$COMMAND_TIMEOUT_MODE" in
    timeout) timeout "$seconds" "$@" ;;
    toybox) toybox timeout "$seconds" "$@" ;;
    busybox) busybox timeout "$seconds" "$@" ;;
    *) "$@" ;;
  esac
}
""",
    """run_with_watchdog() {
  seconds=$1
  shift
  WATCHDOG_SEQ=$((WATCHDOG_SEQ + 1))
  marker="$TMP_DIR/watchdog.$$.${WATCHDOG_SEQ}"
  rm -f "$marker"
  "$@" &
  command_pid=$!
  (
    sleep "$seconds"
    if kill -0 "$command_pid" 2>/dev/null; then
      : >"$marker"
      kill -TERM "$command_pid" 2>/dev/null || true
      sleep 1
      kill -KILL "$command_pid" 2>/dev/null || true
    fi
  ) &
  watchdog_pid=$!
  wait "$command_pid"
  command_code=$?
  kill "$watchdog_pid" 2>/dev/null || true
  wait "$watchdog_pid" 2>/dev/null || true
  if [ -f "$marker" ]; then
    rm -f "$marker"
    return 124
  fi
  rm -f "$marker"
  return "$command_code"
}

run_limited_command() {
  seconds=$1
  shift
  ensure_timeout_runtime
  case "$COMMAND_TIMEOUT_MODE" in
    timeout) timeout "$seconds" "$@" ;;
    toybox) toybox timeout "$seconds" "$@" ;;
    busybox) busybox timeout "$seconds" "$@" ;;
    *) run_with_watchdog "$seconds" "$@" ;;
  esac
}
""",
    "reliable watchdog",
)

rep(
    """prepare_deep_runtime() {
  DEEP_DIR_TIMEOUT_SECONDS=12
  DEEP_STAGE_LIMIT_SECONDS=300
""",
    """prepare_deep_runtime() {
  DEEP_DIR_TIMEOUT_SECONDS=$(get_uint deep_dir_timeout_seconds 8 3 60)
  DEEP_STAGE_LIMIT_SECONDS=$(get_uint deep_stage_limit_seconds 180 30 600)
""",
    "configurable deep budgets",
)

rep(
    """    busybox) busybox timeout "$seconds" "$@" ;;
    *) "$@" ;;
  esac
}

deep_mount_conflict() {
""",
    """    busybox) busybox timeout "$seconds" "$@" ;;
    *) run_with_watchdog "$seconds" "$@" ;;
  esac
}

deep_mount_conflict() {
""",
    "deep watchdog fallback",
)

rep(
    """deep_keep_root() {
""",
    """deep_record_slowest() {
  slow_target=$1
  slow_seconds=${2:-0}
  case "$slow_seconds" in ''|*[!0-9]*) slow_seconds=0 ;; esac
  if [ "$slow_seconds" -gt "$DEEP_SLOWEST_SECONDS" ]; then
    DEEP_SLOWEST_SECONDS=$slow_seconds
    DEEP_SLOWEST_PATH=$(printf '%s' "$slow_target" | tr '\r\n' '  ')
  fi
}

deep_keep_root() {
""",
    "slowest helper",
)

rep(
    """      timeout)
        DEEP_SLOW_ITEMS=$((DEEP_SLOW_ITEMS + 1))
""",
    """      timeout)
        deep_record_slowest "$target" "$DEEP_TARGET_ELAPSED"
        DEEP_SLOW_ITEMS=$((DEEP_SLOW_ITEMS + 1))
""",
    "timeout slowest",
)

rep(
    """  if [ "$DEEP_TARGET_ELAPSED" -ge 3 ]; then
""",
    """  deep_record_slowest "$target" "$DEEP_TARGET_ELAPSED"
  if [ "$DEEP_TARGET_ELAPSED" -ge 3 ]; then
""",
    "successful slowest",
)

rep(
    """run_deep_rules() {
  [ -f "$DEEP_RULES" ] || return 0
  prepare_deep_runtime
""",
    """run_deep_rules() {
  [ -f "$DEEP_RULES" ] || return 0
  prepare_deep_runtime
  deep_rules_started=$(date +%s)
""",
    "deep parse start",
)

rep(
    """  if sort -u "$candidates" >"$sorted" 2>/dev/null; then mv -f "$sorted" "$candidates"; else rm -f "$sorted"; fi
  DEEP_PROGRESS_TOTAL=$(wc -l <"$candidates" 2>/dev/null | tr -d ' ')
""",
    """  if sort -u "$candidates" >"$sorted" 2>/dev/null; then mv -f "$sorted" "$candidates"; else rm -f "$sorted"; fi
  deep_rules_ready=$(date +%s)
  DEEP_RULE_PARSE_SECONDS=$((deep_rules_ready - deep_rules_started))
  DEEP_PROGRESS_TOTAL=$(wc -l <"$candidates" 2>/dev/null | tr -d ' ')
""",
    "deep parse timing",
)

rep(
    """  deep_progress_update "$DEEP_PROGRESS_CURRENT" "$DEEP_PROGRESS_TOTAL" "" 1
  log_line "[深度引擎] 候选 $DEEP_PROGRESS_TOTAL，已处理 $DEEP_PROGRESS_CURRENT，慢目录跳过 $DEEP_SLOW_ITEMS，挂载保护 $DEEP_MOUNT_ITEMS，截断 $DEEP_TRUNCATED"
""",
    """  deep_stage_end=$(date +%s)
  DEEP_STAGE_SECONDS=$((deep_stage_end - deep_stage_start))
  deep_progress_update "$DEEP_PROGRESS_CURRENT" "$DEEP_PROGRESS_TOTAL" "" 1
  log_line "[深度阶段耗时] 规则解析 ${DEEP_RULE_PARSE_SECONDS}s · 目录处理 ${DEEP_STAGE_SECONDS}s"
  if [ "$DEEP_SLOWEST_SECONDS" -gt 0 ]; then
    log_line "[深度最慢目录] ${DEEP_SLOWEST_SECONDS}s · $DEEP_SLOWEST_PATH"
  fi
  log_line "[深度引擎] 候选 $DEEP_PROGRESS_TOTAL，已处理 $DEEP_PROGRESS_CURRENT，慢目录跳过 $DEEP_SLOW_ITEMS，挂载保护 $DEEP_MOUNT_ITEMS，截断 $DEEP_TRUNCATED"
""",
    "deep final timing",
)

rep(
    """MAX_RUN_MINUTES=$(get_uint max_run_minutes 45 5 180)
MAX_RUN_SECONDS=$((MAX_RUN_MINUTES * 60))
""",
    """MAX_RUN_MINUTES=$(get_uint max_run_minutes 45 5 180)
if [ "$TRIGGER" = "app" ] && [ "$REQUEST_MODE" = "clean" ]; then
  APP_TASK_MAX_MINUTES=$(get_uint app_task_max_minutes 20 5 45)
  [ "$MAX_RUN_MINUTES" -le "$APP_TASK_MAX_MINUTES" ] || MAX_RUN_MINUTES=$APP_TASK_MAX_MINUTES
fi
MAX_RUN_SECONDS=$((MAX_RUN_MINUTES * 60))
""",
    "app task cap",
)

rep(
    """log_line "文件总计: $FILES，其中碎片: $FRAGMENT_FILES，空文件: $EMPTY_FILES，空目录: $EMPTY_DIRS，隐藏垃圾: $HIDDEN_ITEMS，受保护: $PROTECTED_ITEMS，深度慢目录: $DEEP_SLOW_ITEMS，缓存慢目录: $CACHE_SLOW_DIRS，缓存截断: $CACHE_TRUNCATED，挂载保护: $DEEP_MOUNT_ITEMS，跳过: $SKIPPED，未清理: $ERRORS，耗时: ${ELAPSED}s"
""",
    """log_line "文件总计: $FILES，其中碎片: $FRAGMENT_FILES，空文件: $EMPTY_FILES，空目录: $EMPTY_DIRS，隐藏垃圾: $HIDDEN_ITEMS，受保护: $PROTECTED_ITEMS，深度慢目录: $DEEP_SLOW_ITEMS，缓存慢目录: $CACHE_SLOW_DIRS，缓存截断: $CACHE_TRUNCATED，挂载保护: $DEEP_MOUNT_ITEMS，跳过: $SKIPPED，未清理: $ERRORS，耗时: ${ELAPSED}s"
[ "$DEEP_RULE_PARSE_SECONDS" -gt 0 ] && log_line "深度规则解析: ${DEEP_RULE_PARSE_SECONDS}s"
[ "$DEEP_STAGE_SECONDS" -gt 0 ] && log_line "深度目录处理: ${DEEP_STAGE_SECONDS}s"
[ "$DEEP_SLOWEST_SECONDS" -gt 0 ] && log_line "最慢目录: ${DEEP_SLOWEST_SECONDS}s · $DEEP_SLOWEST_PATH"
""",
    "final log metrics",
)

rep(
    """  echo "deep_truncated=$DEEP_TRUNCATED"
  echo "cache_slow_dirs=$CACHE_SLOW_DIRS"
""",
    """  echo "deep_truncated=$DEEP_TRUNCATED"
  echo "deep_rule_parse_seconds=$DEEP_RULE_PARSE_SECONDS"
  echo "deep_stage_seconds=$DEEP_STAGE_SECONDS"
  echo "deep_slowest_seconds=$DEEP_SLOWEST_SECONDS"
  printf 'deep_slowest_path=%s\n' "$DEEP_SLOWEST_PATH" | tr '\r\n' '  '
  echo "cache_slow_dirs=$CACHE_SLOW_DIRS"
""",
    "latest deep metrics",
)

rep(
    """    echo "targets=$DEEP_PROGRESS_TOTAL"
  } >"$DEEP_SCAN_STATE"
""",
    """    echo "targets=$DEEP_PROGRESS_TOTAL"
    echo "rule_parse_seconds=$DEEP_RULE_PARSE_SECONDS"
    echo "stage_seconds=$DEEP_STAGE_SECONDS"
    echo "slowest_seconds=$DEEP_SLOWEST_SECONDS"
    printf 'slowest_path=%s\n' "$DEEP_SLOWEST_PATH" | tr '\r\n' '  '
  } >"$DEEP_SCAN_STATE"
""",
    "snapshot deep metrics",
)

path.write_text(text)

config = root / "config/default.conf"
config_text = config.read_text()
for line in ("app_task_max_minutes=20", "deep_dir_timeout_seconds=8", "deep_stage_limit_seconds=180"):
    if line not in config_text:
        config_text = config_text.rstrip() + "\n" + line + "\n"
config.write_text(config_text)

print("v2.2.5 cleaner performance patch applied")
