from pathlib import Path

ROOT = Path('.')


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected one match, found {count}')
    file.write_text(text.replace(old, new, 1), encoding='utf-8')


replace_once(
    'service.sh',
    '    deep) SPEC_ENABLED=schedule_deep_enabled; SPEC_MINUTES=schedule_deep_minutes; SPEC_HOURS=schedule_deep_hours; SPEC_FALLBACK=10080; SPEC_MODE=deep-clean ;;',
    '    deep) SPEC_ENABLED=schedule_deep_enabled; SPEC_MINUTES=schedule_deep_minutes; SPEC_HOURS=schedule_deep_hours; SPEC_FALLBACK=10080; SPEC_MODE=deep-auto ;;',
    'deep scheduler chain',
)

replace_once(
    'v2/module/task-worker.sh',
    'case "$MODE" in clean|scan|cache-auto|cache-clean|empty-clean|rules-clean|fragment-scan|fragment-clean|deep-scan|deep-clean|corpse-scan|corpse-clean|apk-scan|apk-clean|organize) ;; *) echo "不支持的任务模式：$MODE" >&2; exit 2 ;; esac',
    'case "$MODE" in clean|scan|cache-auto|cache-clean|empty-clean|rules-clean|fragment-scan|fragment-clean|deep-scan|deep-clean|deep-auto|corpse-scan|corpse-clean|apk-scan|apk-clean|organize) ;; *) echo "不支持的任务模式：$MODE" >&2; exit 2 ;; esac',
    'task worker deep-auto mode',
)
replace_once(
    'v2/module/task-worker.sh',
    '''while kill -0 "$pid" 2>/dev/null; do
  sleep 2
done
wait "$pid" 2>/dev/null || true
code=$(sed -n 's/^exit_code=//p' "$RESULT_FILE" 2>/dev/null | tail -n 1)
case "$code" in ''|*[!0-9]*) code=8 ;; esac
exit "$code"''',
    '''wait "$pid" 2>/dev/null
runner_code=$?
code=$(sed -n 's/^exit_code=//p' "$RESULT_FILE" 2>/dev/null | tail -n 1)
case "$code" in ''|*[!0-9]*) code=$runner_code ;; esac
case "$code" in ''|*[!0-9]*) code=8 ;; esac
exit "$code"''',
    'worker child reaping',
)

replace_once(
    'v2/module/worker-runner.sh',
    '''if [ "$MODE" = organize ]; then
  if [ -x "$ORGANIZER" ]; then
    "$ORGANIZER" "$MODE" "$TRIGGER" "$TASK_ID" >>"$LOG_FILE" 2>&1
    code=$?
  else
    echo "文件归类引擎不存在：$ORGANIZER" >>"$LOG_FILE"
  fi
elif [ -x "$CLEANER" ]; then''',
    '''if [ "$MODE" = deep-auto ]; then
  if [ -x "$CLEANER" ]; then
    "$CLEANER" deep-scan "$TRIGGER" >>"$LOG_FILE" 2>&1
    code=$?
    if [ "$code" -eq 0 ]; then
      "$CLEANER" deep-clean "$TRIGGER" >>"$LOG_FILE" 2>&1
      code=$?
    fi
  else
    echo "清理引擎不存在：$CLEANER" >>"$LOG_FILE"
  fi
elif [ "$MODE" = organize ]; then
  if [ -x "$ORGANIZER" ]; then
    "$ORGANIZER" "$MODE" "$TRIGGER" "$TASK_ID" >>"$LOG_FILE" 2>&1
    code=$?
  else
    echo "文件归类引擎不存在：$ORGANIZER" >>"$LOG_FILE"
  fi
elif [ -x "$CLEANER" ]; then''',
    'deep scan clean worker chain',
)

activity = 'v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt'
replace_once(
    activity,
    '''    private var pollJob: Job? = null
    private var recoveryProbeJob: Job? = null
    private var taskCallbackRegistered = false''',
    '''    private var pollJob: Job? = null
    private var recoveryProbeJob: Job? = null
    private var schedulerMonitorJob: Job? = null
    private var taskCallbackRegistered = false''',
    'foreground monitor field',
)
replace_once(
    activity,
    '''    override fun onResume() {
        super.onResume()
        updateStorage()
        if (rootService != null || cacheService != null) {
            recoverRemoteTaskOrRefresh()
        } else {
            connectServices()
        }
    }

    private fun refreshAll() {''',
    '''    override fun onResume() {
        super.onResume()
        updateStorage()
        if (rootService != null || cacheService != null) {
            recoverRemoteTaskOrRefresh()
        } else {
            connectServices()
        }
        startForegroundModuleMonitor()
    }

    override fun onPause() {
        schedulerMonitorJob?.cancel()
        schedulerMonitorJob = null
        super.onPause()
    }

    private fun startForegroundModuleMonitor() {
        schedulerMonitorJob?.cancel()
        schedulerMonitorJob = lifecycleScope.launch {
            var idleConfirmations = 0
            while (isActive) {
                val service = rootService
                if (service == null) {
                    delay(750L)
                    continue
                }
                val snapshots = withContext(Dispatchers.IO) {
                    val schedulerJson = runCatching { JSONObject(service.getSchedulerConfig()) }.getOrNull()
                    val taskJson = runCatching { JSONObject(service.getTaskState()) }.getOrNull()
                    schedulerJson to taskJson
                }
                snapshots.first?.let { schedulerState.value = SchedulerUiState.fromJson(it) }
                val task = snapshots.second
                if (task?.optBoolean("running") == true) {
                    idleConfirmations = 0
                    renderTaskState(task)
                } else if (dashboardState.value.running) {
                    idleConfirmations += 1
                    if (idleConfirmations >= 2) {
                        idleConfirmations = 0
                        dashboardState.value = dashboardState.value.copy(
                            running = false,
                            scanCompleted = false,
                            taskPhase = "后台任务已结束，正在读取结果…"
                        )
                        refreshAll()
                    }
                } else {
                    idleConfirmations = 0
                }
                val fast = dashboardState.value.running ||
                    schedulerState.value.runtimeState == "running" ||
                    schedulerState.value.queueCount > 0
                delay(if (fast) 750L else 3_000L)
            }
        }
    }

    private fun refreshAll() {''',
    'foreground scheduler monitor',
)
replace_once(
    activity,
    '''        pollJob?.cancel()
        recoveryProbeJob?.cancel()
        if (profileBound)''',
    '''        pollJob?.cancel()
        recoveryProbeJob?.cancel()
        schedulerMonitorJob?.cancel()
        if (profileBound)''',
    'monitor cleanup',
)

presentation = 'v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/HomeTaskPresentation.kt'
replace_once(
    presentation,
    '''    if (task == null || !task.enabled) return "自动任务已关闭"
    if (scheduler.runtimeState == "running" && scheduler.runtimeGroup == task.id) return "正在后台执行"
    if (scheduler.runtimeStale) return "后台调度正在自动恢复"

    val remaining = task.nextEpoch - nowEpoch''',
    '''    if (task == null || !task.enabled) return "自动任务已关闭"
    if (scheduler.runtimeState == "running" && scheduler.runtimeGroup == task.id) return "正在后台执行"
    if (scheduler.runtimeStale) return "后台调度正在自动恢复"

    val remaining = task.nextEpoch - nowEpoch''',
    'presentation anchor',
)
replace_once(
    presentation,
    '''        return when {
            reason.contains("息屏") || reason.contains("充电") || reason.contains("电量") ||
                reason.contains("空闲") || reason.contains("当前任务") || reason.contains("重试") -> reason
            scheduler.queueCount > 0 && scheduler.nextTask == task.id -> "已进入队列，即将执行"
            scheduler.queueCount > 0 -> "等待当前任务完成"
            else -> "即将启动后台任务"
        }''',
    '''        return when {
            scheduler.runtimeState == "running" -> "等待当前后台任务完成"
            reason.contains("息屏") || reason.contains("充电") || reason.contains("电量") ||
                reason.contains("空闲") || reason.contains("当前任务") || reason.contains("手动任务") ||
                reason.contains("队列将在完成") || reason.contains("重试") -> reason
            scheduler.queueCount > 0 && scheduler.nextTask == task.id -> "已进入 Root 队列"
            scheduler.queueCount > 0 -> "等待队列前序任务完成"
            else -> "正在提交 Root Worker"
        }''',
    'truthful queue label',
)

# Add executable regression coverage to the existing scheduler test so the release workflow always runs it.
test_path = ROOT / 'v2/tests/test-scheduler-fairness.sh'
test = test_path.read_text(encoding='utf-8')
marker = '# Worker wait mode must reap the child and deep-auto must run scan before clean.'
if marker not in test:
    test = test.replace(
        "echo 'scheduler fairness: ok'",
        '''# Deep scheduled tasks must request the atomic scan -> clean chain.
cat > "$T/module/task-worker.sh" <<'SH'
#!/bin/sh
printf '%s\\t%s\\t%s\\n' "$1" "$2" "$3" >>"${BAIZE_STATE_DIR}/executed.tsv"
exit 0
SH
chmod +x "$T/module/task-worker.sh"
: > "$T/state/executed.tsv"
sed -i 's/^schedule_cache_enabled=.*/schedule_cache_enabled=0/; s/^schedule_deep_enabled=.*/schedule_deep_enabled=1/' "$T/state/config.conf"
echo 5 >> "$T/state/config.conf"
sed -i 's/^schedule_deep_minutes=.*/schedule_deep_minutes=5/' "$T/state/config.conf" 2>/dev/null || true
printf '%s\\n' $((now-7200)) > "$T/state/last_deep_run.epoch"
run_once
[ "$(sed -n '1s/\\t.*//p' "$T/state/executed.tsv")" = deep-auto ]

# Worker wait mode must reap the child and deep-auto must run scan before clean.
W="$T/worker-lifecycle"
rm -rf "$W"; mkdir -p "$W/module" "$W/state/task-results" "$W/state/logs"
cp "$ROOT/v2/module/task-worker.sh" "$W/module/task-worker.sh"
cp "$ROOT/v2/module/worker-runner.sh" "$W/module/worker-runner.sh"
cat > "$W/module/cleaner.sh" <<'SH'
#!/bin/sh
printf '%s\\n' "$1" >>"${BAIZE_STATE_DIR}/deep-chain.log"
exit 0
SH
chmod +x "$W/module/"*.sh
BAIZE_STATE_DIR="$W/state" BAIZE_SHELL_BIN=/bin/sh timeout 8 sh "$W/module/task-worker.sh" deep-auto test lifecycle wait
[ "$(sed -n '1p' "$W/state/deep-chain.log")" = deep-scan ]
[ "$(sed -n '2p' "$W/state/deep-chain.log")" = deep-clean ]
grep -q '^exit_code=0$' "$W/state/task-results/lifecycle.env"

echo 'scheduler fairness: ok' ''',
        1,
    )
    test_path.write_text(test, encoding='utf-8')

print('queue liveness fix prepared')
