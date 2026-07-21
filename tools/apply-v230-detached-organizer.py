from pathlib import Path

root = Path(__file__).resolve().parents[1]

def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"{label} anchor missing")
    return text.replace(old, new, 1)

service = root / "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
text = service.read_text()
text = replace_once(
    text,
    'if (normalized == "clean") startDetachedModuleTask(normalized, started)\n'
    '                else executeModuleTask(normalized, started)',
    'if (normalized == "clean" || normalized == "organize") startDetachedModuleTask(normalized, started)\n'
    '                else executeModuleTask(normalized, started)',
    "detached organizer dispatch",
)
text = replace_once(
    text,
    '            "apk-scan", "apk-clean"\n'
    '        )',
    '            "apk-scan", "apk-clean", "organize"\n'
    '        )',
    "organize module task",
)
text = text.replace(
    '.put("message", "清理任务已交给独立 Root Worker，关闭 App 仍会继续")',
    '.put("message", if (mode == "organize") "文件归类已交给独立 Root Worker，切后台、划掉 App 或重新进入都不会中断" else "清理任务已交给独立 Root Worker，关闭 App 仍会继续")',
)
old_state = '''            return runCatching {
                JSONObject(taskStateJson).put("cancelRequested", cancelled.get()).toString()
            }.getOrDefault(taskStateJson)
'''
new_state = '''            val organizerResult = readEnv(File(STATE_DIR, "organizer-result.env"))
            val completedEpoch = organizerResult.optLong("completed_epoch", 0L)
            if (organizerResult.length() > 0 && completedEpoch > 0L &&
                System.currentTimeMillis() / 1000L - completedEpoch <= ORGANIZER_RESULT_TTL_SECONDS
            ) {
                organizerResult
                    .put("running", false)
                    .put("completed", true)
                    .put("operation", "module-organize")
                    .put("cancelRequested", cancelled.get())
                return organizerResult.toString()
            }
            return runCatching {
                JSONObject(taskStateJson).put("cancelRequested", cancelled.get()).toString()
            }.getOrDefault(taskStateJson)
'''
text = replace_once(text, old_state, new_state, "organizer result recovery")
text = replace_once(
    text,
    '        private const val WHITELIST_PACKAGES_FILE = "$STATE_DIR/whitelist.packages"\n',
    '        private const val WHITELIST_PACKAGES_FILE = "$STATE_DIR/whitelist.packages"\n'
    '        private const val ORGANIZER_RESULT_TTL_SECONDS = 24L * 60L * 60L\n',
    "organizer result ttl",
)
service.write_text(text)

worker = root / "v2/app/src/main/java/io/github/xgl34222220/baize/FileOrganizerWorker.kt"
text = worker.read_text()
start = text.index("    override suspend fun doWork(): Result {")
end = text.index("    private suspend fun bindRootServiceWithRetry()", start)
new_do_work = '''    override suspend fun doWork(): Result {
        val settings = loadSettings(applicationContext)
        if (!settings.enabled) return Result.success()

        val power = applicationContext.getSystemService(PowerManager::class.java)
        if (settings.screenOffOnly && power?.isInteractive == true) {
            writeResult(applicationContext, "等待息屏后执行")
            return Result.retry()
        }

        val session = runCatching { bindRootServiceWithRetry() }.getOrElse {
            writeResult(applicationContext, "Root 服务连接失败：${it.message ?: it.javaClass.simpleName}")
            return Result.retry()
        }

        return try {
            val response = runCatching { JSONObject(session.service.runModuleTask("organize")) }.getOrElse {
                writeResult(applicationContext, "提交 Root 归类任务失败：${it.message ?: it.javaClass.simpleName}")
                return Result.retry()
            }
            if (response.has("error")) {
                val code = response.optString("error")
                writeResult(applicationContext, response.optString("message", "Root 归类任务提交失败"))
                return if (code == "busy") Result.retry() else Result.failure()
            }
            if (!response.optBoolean("accepted")) {
                writeResult(applicationContext, "Root 模块未接管归类任务")
                return Result.retry()
            }
            writeResult(applicationContext, "已交给独立 Root Worker；切后台、划掉 App 或重新进入都不会中断")
            Result.success()
        } finally {
            session.close()
        }
    }

'''
text = text[:start] + new_do_work + text[end:]
worker.write_text(text)

activity = root / "v2/app/src/main/java/io/github/xgl34222220/baize/FileOrganizerActivity.kt"
text = activity.read_text()
if "import kotlinx.coroutines.Job" not in text:
    text = text.replace(
        "import kotlinx.coroutines.Dispatchers\n",
        "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.Job\nimport kotlinx.coroutines.delay\n",
        1,
    )
if "private var organizerPollJob: Job?" not in text:
    text = text.replace(
        '    private var scheduleSavedText by mutableStateOf("")\n',
        '    private var scheduleSavedText by mutableStateOf("")\n'
        '    private var organizerPollJob: Job? = null\n',
        1,
    )
text = replace_once(
    text,
    '            state = state.copy(connected = true, status = "文件归类服务已就绪")\n',
    '            state = state.copy(connected = true, status = "文件归类服务已就绪")\n'
    '            startOrganizerPoll(waitForStart = false)\n',
    "activity recovery on connect",
)
old_disconnected = '''            state = state.copy(
                connected = false,
                running = false,
                status = "Root 服务已断开，请重新进入页面"
            )
'''
new_disconnected = '''            state = state.copy(
                connected = false,
                status = if (state.running) {
                    "App 连接已断开，但独立 Root Worker 会继续；重新进入后自动恢复进度"
                } else {
                    "Root 服务已断开，请重新进入页面"
                }
            )
'''
text = replace_once(text, old_disconnected, new_disconnected, "activity disconnect state")
text = replace_once(
    text,
    '''    override fun onResume() {
        super.onResume()
        schedule = FileOrganizerWorker.loadSettings(this)
    }
''',
    '''    override fun onResume() {
        super.onResume()
        schedule = FileOrganizerWorker.loadSettings(this)
        if (bound) startOrganizerPoll(waitForStart = false)
    }
''',
    "activity resume recovery",
)
start = text.index("    private fun oneTapOrganize() {")
end = text.index("    private fun undoLast()", start)
new_one_tap = '''    private fun oneTapOrganize() {
        val root = service ?: return
        if (state.running) return
        state = state.copy(
            running = true,
            status = "正在把扫描与归类任务交给独立 Root Worker…",
            lastTotal = 0,
            lastBytes = 0L
        )

        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.runModuleTask("organize")) }.getOrElse {
                    JSONObject()
                        .put("error", "organizer_submit_failed")
                        .put("message", it.message ?: it.javaClass.simpleName)
                }
            }
            if (response.has("error")) {
                state = state.copy(
                    running = false,
                    status = response.optString("message", "独立 Root 归类任务启动失败")
                )
                return@launch
            }
            if (!response.optBoolean("accepted")) {
                state = state.copy(
                    running = false,
                    status = "Root 模块没有接管归类任务，请重新刷入完整模块"
                )
                return@launch
            }
            state = state.copy(
                running = true,
                status = response.optString(
                    "message",
                    "独立 Root Worker 已接管；切后台、划掉 App 或重新进入都不会中断"
                )
            )
            startOrganizerPoll(waitForStart = true)
        }
    }

    private fun startOrganizerPoll(waitForStart: Boolean) {
        organizerPollJob?.cancel()
        organizerPollJob = lifecycleScope.launch {
            var remainingStartMisses = if (waitForStart) 12 else 0
            while (true) {
                val root = service ?: break
                val task = withContext(Dispatchers.IO) {
                    runCatching { JSONObject(root.getTaskState()) }.getOrNull()
                }
                if (task == null) {
                    if (remainingStartMisses-- > 0) {
                        delay(500)
                        continue
                    }
                    break
                }
                val mode = task.optString("mode")
                val operation = task.optString("operation")
                val isOrganizer = mode == "organize" || operation == "module-organize"
                if (!isOrganizer) {
                    if (remainingStartMisses-- > 0) {
                        delay(500)
                        continue
                    }
                    break
                }

                if (task.optBoolean("running")) {
                    val phase = task.optString("phase", "独立 Root Worker 正在归类文件")
                    val current = task.optInt("progress_current")
                    val total = task.optInt("progress_total")
                    val path = task.optString("current_path")
                    val progressText = when {
                        total > 0 -> " · $current/$total"
                        current > 0 -> " · 已处理 $current"
                        else -> ""
                    }
                    state = state.copy(
                        connected = true,
                        running = true,
                        status = buildString {
                            append(phase)
                            append(progressText)
                            if (path.isNotBlank()) append("\n").append(path)
                        }
                    )
                    remainingStartMisses = 0
                    delay(700)
                    continue
                }

                if (task.optBoolean("completed") || task.has("moved")) {
                    val moved = task.optInt("moved")
                    val requested = task.optInt("requested")
                    val skipped = task.optInt("skipped")
                    val failed = task.optInt("failed")
                    val bytes = task.optLong("bytes")
                    val cancelled = task.optBoolean("cancelled")
                    state = state.copy(
                        connected = true,
                        running = false,
                        status = if (cancelled) {
                            "文件归类已停止：已移动 $moved 个"
                        } else {
                            "Root 后台归类完成：移动 $moved/$requested 个 · 跳过 $skipped 个 · 失败 $failed 个 · ${
                                Formatter.formatFileSize(this@FileOrganizerActivity, bytes)
                            }"
                        },
                        undoAvailable = task.optBoolean("undoAvailable", moved > 0),
                        lastTotal = requested,
                        lastBytes = bytes
                    )
                }
                break
            }
        }
    }

'''
text = text[:start] + new_one_tap + text[end:]
text = replace_once(
    text,
    '''    override fun onDestroy() {
        if (bound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }
''',
    '''    override fun onDestroy() {
        organizerPollJob?.cancel()
        if (bound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }
''',
    "activity poll cleanup",
)
activity.write_text(text)

engine = root / "v2/app/src/main/java/io/github/xgl34222220/baize/root/FileOrganizerEngine.kt"
text = engine.read_text()
if "import android.util.Base64" not in text:
    text = text.replace("import android.system.OsConstants\n", "import android.system.OsConstants\nimport android.util.Base64\n", 1)
old_read_undo = '''    private fun readUndo(): JSONObject? = runCatching {
        val file = undoFile()
        if (!file.isFile) null else JSONObject(file.readText())
    }.getOrNull()
'''
new_read_undo = '''    private fun readUndo(): JSONObject? = runCatching {
        val file = undoFile()
        if (!file.isFile) return@runCatching null
        val record = JSONObject(file.readText())
        val moves = record.optJSONArray("moves") ?: return@runCatching record
        for (index in 0 until moves.length()) {
            val move = moves.optJSONObject(index) ?: continue
            if (move.optString("source").isBlank() && move.optString("sourceB64").isNotBlank()) {
                move.put(
                    "source",
                    String(Base64.decode(move.optString("sourceB64"), Base64.DEFAULT), Charsets.UTF_8)
                )
            }
            if (move.optString("destination").isBlank() && move.optString("destinationB64").isNotBlank()) {
                move.put(
                    "destination",
                    String(Base64.decode(move.optString("destinationB64"), Base64.DEFAULT), Charsets.UTF_8)
                )
            }
        }
        record
    }.getOrNull()
'''
text = replace_once(text, old_read_undo, new_read_undo, "detached organizer undo")
engine.write_text(text)

print("detached Root organizer takeover applied")
