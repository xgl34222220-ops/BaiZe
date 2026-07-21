from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "v2/module/native-scan.sh"
text = path.read_text()


def rep(old: str, new: str, label: str) -> None:
    global text
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"missing native wrapper anchor: {label}")
    text = text.replace(old, new, 1)

rep(
    """MAX_MB=$(get_config_uint max_file_mb 256 1 16384)
MAX_FILE_BYTES=$((MAX_MB * 1024 * 1024))
""",
    """MAX_MB=$(get_config_uint max_file_mb 256 1 16384)
MAX_FILE_BYTES=$((MAX_MB * 1024 * 1024))
DEEP_DIR_TIMEOUT_SECONDS=$(get_config_uint deep_dir_timeout_seconds 8 3 60)
DEEP_STAGE_LIMIT_SECONDS=$(get_config_uint deep_stage_limit_seconds 180 30 600)
DEEP_DIR_BUDGET_MS=$((DEEP_DIR_TIMEOUT_SECONDS * 1000))
DEEP_GLOBAL_BUDGET_MS=$((DEEP_STAGE_LIMIT_SECONDS * 1000))
""",
    'budget config',
)
rep(
    """      --max-file-bytes "$MAX_FILE_BYTES" --allow-high-risk "$allow_high" --report "$REPORT_FILE" \\
      --targets "$TARGETS_TMP" --summary "$SUMMARY_FILE" --progress "$RUNNING_FILE" --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1 || code=$?
""",
    """      --max-file-bytes "$MAX_FILE_BYTES" --allow-high-risk "$allow_high" \\
      --dir-budget-ms "$DEEP_DIR_BUDGET_MS" --global-budget-ms "$DEEP_GLOBAL_BUDGET_MS" --report "$REPORT_FILE" \\
      --targets "$TARGETS_TMP" --summary "$SUMMARY_FILE" --progress "$RUNNING_FILE" --stop "$STOP_FILE" >>"$LOG_FILE" 2>&1 || code=$?
""",
    'native budget args',
)
rep(
    """PRUNED_SUBTREES=$(summary_number pruned_subtrees)
TOTAL_ITEMS=$((FILES + EMPTY_DIRS))
""",
    """PRUNED_SUBTREES=$(summary_number pruned_subtrees)
TIMED_OUT_DIRS=$(summary_number timed_out_dirs)
DEEP_PARSE_MS=$(summary_number deep_parse_ms)
DEEP_STAGE_MS=$(summary_number deep_stage_ms)
DEEP_SLOWEST_MS=$(summary_number deep_slowest_ms)
DEEP_SLOWEST_PATH=$(summary_value deep_slowest_path)
TOTAL_ITEMS=$((FILES + EMPTY_DIRS))
""",
    'read native metrics',
)
rep(
    """      RESULT="深度规则原生扫描完成，可清理 $SPACE"
""",
    """      RESULT="深度规则原生扫描完成，可清理 $SPACE"
      [ "$TIMED_OUT_DIRS" -gt 0 ] && RESULT="$RESULT，慢目录跳过 ${TIMED_OUT_DIRS} 项"
      [ "$TRUNCATED" -gt 0 ] && RESULT="$RESULT，已达到 ${DEEP_STAGE_LIMIT_SECONDS} 秒阶段上限"
""",
    'native result metrics',
)
rep(
    """        echo "pruned_subtrees=$PRUNED_SUBTREES"
        echo "engine=native-c-arm64-path-index"
""",
    """        echo "pruned_subtrees=$PRUNED_SUBTREES"
        echo "timed_out_dirs=$TIMED_OUT_DIRS"
        echo "deep_parse_ms=$DEEP_PARSE_MS"
        echo "deep_stage_ms=$DEEP_STAGE_MS"
        echo "deep_slowest_ms=$DEEP_SLOWEST_MS"
        printf 'deep_slowest_path=%s\n' "$DEEP_SLOWEST_PATH" | tr '\r\n' '  '
        echo "engine=native-c-arm64-path-index"
""",
    'snapshot native metrics',
)
rep(
    """  echo "deep_slow_items=0"
  echo "deep_mount_items=$MOUNT_ITEMS"
""",
    """  echo "deep_slow_items=$TIMED_OUT_DIRS"
  echo "deep_mount_items=$MOUNT_ITEMS"
""",
    'latest timeout count',
)
rep(
    """  echo "pruned_subtrees=$PRUNED_SUBTREES"
  echo "elapsed=$ELAPSED"
""",
    """  echo "pruned_subtrees=$PRUNED_SUBTREES"
  echo "deep_rule_parse_seconds=$((DEEP_PARSE_MS / 1000))"
  echo "deep_stage_seconds=$((DEEP_STAGE_MS / 1000))"
  echo "deep_slowest_seconds=$((DEEP_SLOWEST_MS / 1000))"
  printf 'deep_slowest_path=%s\n' "$DEEP_SLOWEST_PATH" | tr '\r\n' '  '
  echo "elapsed=$ELAPSED"
""",
    'latest native timings',
)
rep(
    """  echo "候选: $CANDIDATES | 文件: $FILES | 目录: $DIRS | 访问: $((VISITED_FILES + VISITED_DIRS)) | 吞吐: ${ITEMS_PER_SECOND}/s | 首项: ${FIRST_RESULT_MS}ms | 耗时: ${ENGINE_ELAPSED_MS}ms"
""",
    """  echo "候选: $CANDIDATES | 文件: $FILES | 目录: $DIRS | 访问: $((VISITED_FILES + VISITED_DIRS)) | 吞吐: ${ITEMS_PER_SECOND}/s | 首项: ${FIRST_RESULT_MS}ms | 耗时: ${ENGINE_ELAPSED_MS}ms"
  if [ "$MODE" = "deep-scan" ]; then
    echo "深度阶段: 规则 ${DEEP_PARSE_MS}ms | 目录 ${DEEP_STAGE_MS}ms | 超时目录 $TIMED_OUT_DIRS"
    [ "$DEEP_SLOWEST_MS" -gt 0 ] && echo "最慢目录: ${DEEP_SLOWEST_MS}ms | $DEEP_SLOWEST_PATH"
  fi
""",
    'native timing log',
)
path.write_text(text)

# Show slowest directory in the dashboard after the task finishes.
activity = root / "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
text = activity.read_text()
old = """                if (files > 0L || errors > 0L || elapsed > 0L) {
                    if (isNotEmpty()) append("\\n")
                    append("文件 ").append(files)
                    if (errors > 0L) append(" · 异常 ").append(errors)
                    if (elapsed > 0L) append(" · ").append(formatElapsed(elapsed))
                }
"""
new = """                if (files > 0L || errors > 0L || elapsed > 0L) {
                    if (isNotEmpty()) append("\\n")
                    append("文件 ").append(files)
                    if (errors > 0L) append(" · 异常 ").append(errors)
                    if (elapsed > 0L) append(" · ").append(formatElapsed(elapsed))
                }
                val slowestSeconds = latest.optLong("deep_slowest_seconds", 0L).coerceAtLeast(0L)
                val slowestPath = latest.optString("deep_slowest_path").trim()
                if (slowestSeconds > 0L && slowestPath.isNotBlank()) {
                    if (isNotEmpty()) append("\\n")
                    append("最慢目录 ").append(slowestSeconds).append("秒 · ").append(slowestPath.takeLast(72))
                }
"""
if new not in text:
    if old not in text:
        raise SystemExit("dashboard slowest anchor missing")
    text = text.replace(old, new, 1)
activity.write_text(text)

print("v2.2.5 native wrapper metrics applied")
