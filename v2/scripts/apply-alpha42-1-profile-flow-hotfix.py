#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Patch target not found in {path}: {old[:100]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


profile = "v2/app/src/main/java/io/github/xgl34222220/baize/ProfileActivity.kt"

replace_once(
    profile,
    '''            renderConnected()
            renderActionState()''',
    '''            renderConnected()
            recoverRemoteOrLatestState()
            renderActionState()'''
)

replace_once(
    profile,
    '''        binding.statusText.text = "正在连接 Root 原生清理引擎"
        renderActionState()
        connect()
    }

    private fun rootIntent(): Intent''',
    '''        binding.statusText.text = "正在连接 Root 原生清理引擎"
        renderActionState()
        connect()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && service != null && !taskRunning) {
            recoverRemoteOrLatestState()
        }
    }

    private fun rootIntent(): Intent'''
)

replace_once(
    profile,
    '''                val json = JSONObject(raw)
                val output = json.optString("output").lineSequence().filter { it.isNotBlank() }.takeLast(6).joinToString("\\n")
                val success = json.optBoolean("success")
                quickCleanReady = success && !json.optBoolean("cancelled")''',
    '''                val json = JSONObject(raw)
                if (json.optString("error") == "busy") {
                    binding.summaryText.text = "检测到后台任务，正在恢复执行状态…"
                    recoverRemoteOrLatestState()
                    return@onSuccess
                }
                val output = json.optString("output").lineSequence().filter { it.isNotBlank() }.takeLast(6).joinToString("\\n")
                val success = json.optBoolean("success")
                val latest = json.optJSONObject("latest") ?: JSONObject()
                val discovered = latest.optLong("files", latest.optLong("regular_files", 0L)).coerceAtLeast(0L)
                quickCleanReady = success && !json.optBoolean("cancelled") && discovered > 0L'''
)

replace_once(
    profile,
    '''                    if (quickCleanReady) append("\\n已完成安全校验，可直接一键清理。")''',
    '''                    if (quickCleanReady) append("\\n发现 $discovered 项安全内容，可直接一键清理。")
                    else if (success && discovered == 0L) append("\\n没有发现可清理的安全项目。")'''
)

insert_before = '''    private fun startPolling() {'''
helpers = '''    private fun recoverRemoteOrLatestState() {
        val root = service ?: return
        if (!requiresModuleAuthorization() || taskRunning) return
        lifecycleScope.launch {
            val task = runCatching {
                withContext(Dispatchers.IO) { JSONObject(root.getTaskState()) }
            }.getOrNull()
            if (task?.optBoolean("running") == true) {
                taskRunning = true
                quickCleanReady = false
                binding.resultSection.visibility = View.GONE
                setTaskUi(true)
                renderRemoteTaskState(task)
                startRecoveryPolling()
            } else {
                restoreAuthorizedScanResult()
            }
        }
    }

    private fun startRecoveryPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && taskRunning) {
                val task = runCatching {
                    withContext(Dispatchers.IO) { service?.getTaskState()?.let(::JSONObject) }
                }.getOrNull()
                if (task?.optBoolean("running") == true) {
                    renderRemoteTaskState(task)
                    delay(500L)
                    continue
                }

                taskRunning = false
                setTaskUi(false)
                restoreAuthorizedScanResult()
                break
            }
        }
    }

    private fun renderRemoteTaskState(json: JSONObject) {
        binding.summaryText.text = buildString {
            append(json.optString("phase", "后台任务正在执行"))
            val current = json.optInt("progress_current", json.optInt("current"))
            val totalState = json.optInt("progress_total", json.optInt("total"))
            if (totalState > 0) append(" · $current/$totalState")
            val path = json.optString("current_path", json.optString("currentPath"))
            if (path.isNotBlank()) append("\\n").append(path.takeLast(92))
            if (json.optBoolean("cancelRequested")) append("\\n正在安全停止…")
        }
    }

    private suspend fun restoreAuthorizedScanResult() {
        if (!requiresModuleAuthorization()) return
        val root = service ?: return
        val state = runCatching {
            withContext(Dispatchers.IO) { JSONObject(root.getModuleState()) }
        }.getOrNull() ?: return
        val latest = state.optJSONObject("latest") ?: return
        if (latest.optString("mode") != scanMode(profile)) {
            renderActionState()
            return
        }

        val files = latest.optLong("files", latest.optLong("regular_files", 0L)).coerceAtLeast(0L)
        val errors = latest.optLong("errors", 0L).coerceAtLeast(0L)
        val result = latest.optString("result").trim()
        quickCleanReady = files > 0L
        binding.resultsList.visibility = View.GONE
        binding.pageText.visibility = View.GONE
        binding.previousButton.visibility = View.GONE
        binding.nextButton.visibility = View.GONE
        binding.resultSection.visibility = if (quickCleanReady) View.VISIBLE else View.GONE
        binding.cleanButton.text = if (quickCleanReady) quickCleanLabel(profile, files.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        else "扫描后可一键清理"
        binding.selectionText.text = when (profile) {
            "deep" -> "已恢复最近一次深度扫描授权；只会清理低风险与允许的中风险项目。"
            "corpses" -> "已恢复最近一次卸载残留扫描授权；删除前会再次核对安装状态。"
            else -> "已恢复最近一次安全扫描结果。"
        }
        binding.summaryText.text = buildString {
            append("已恢复最近一次${profileTitle(profile)}扫描结果")
            if (files > 0L) append("\\n发现 $files 项，可直接一键清理")
            else append("\\n没有发现可清理项目")
            if (errors > 0L) append(" · 异常 $errors")
            if (result.isNotBlank()) append("\\n").append(result)
        }
        renderActionState()
    }

'''
path_obj = Path(profile)
text = path_obj.read_text(encoding="utf-8")
if helpers not in text:
    if insert_before not in text:
        raise SystemExit("Profile helper insertion target not found")
    path_obj.write_text(text.replace(insert_before, helpers + insert_before, 1), encoding="utf-8")

replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ui/logs/LogsRoute.kt",
    '''    dashboard: DashboardUiState,
    dashboardActions: DashboardActions
) {''',
    '''    dashboard: DashboardUiState,
    dashboardActions: DashboardActions,
    onOpenDetails: () -> Unit
) {'''
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ui/logs/LogsRoute.kt",
    '''        onOpenAudit = dashboardActions.audit,''',
    '''        onOpenAudit = onOpenDetails,'''
)

replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ui/settings/SettingsRoute.kt",
    '''    appearance: AppearanceSettings,
    dashboardActions: DashboardActions
) {''',
    '''    appearance: AppearanceSettings,
    dashboardActions: DashboardActions,
    onOpenDetails: () -> Unit
) {'''
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ui/settings/SettingsRoute.kt",
    '''        onOpenAudit = dashboardActions.audit,''',
    '''        onOpenAudit = onOpenDetails,'''
)

app = "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
for style in ("MATERIAL", "MIUIX"):
    replace_once(
        app,
        f'''BaiZePage.Logs -> LogsRoute(UiStyle.{style}, state, actions)''',
        f'''BaiZePage.Logs -> LogsRoute(UiStyle.{style}, state, actions) {{ page = BaiZePage.Records }}'''
    )
    replace_once(
        app,
        f'''BaiZePage.Settings -> SettingsRoute(UiStyle.{style}, state, scheduler, appearance, actions)''',
        f'''BaiZePage.Settings -> SettingsRoute(UiStyle.{style}, state, scheduler, appearance, actions) {{ page = BaiZePage.Records }}'''
    )

replace_once(
    "v2/app/build.gradle.kts",
    '''        versionCode = 22200
        versionName = "2.0.0-alpha42"''',
    '''        versionCode = 22210
        versionName = "2.0.0-alpha42.1"'''
)
replace_once(
    "v2/module/module.prop",
    '''version=v2.0.0-alpha42
versionCode=22200''',
    '''version=v2.0.0-alpha42.1
versionCode=22210'''
)
replace_once(
    "v2/scripts/package-module.sh",
    '''OUTPUT="$OUT/BaiZe-v2-Alpha42-Module.zip"''',
    '''OUTPUT="$OUT/BaiZe-v2-Alpha42.1-Module.zip"'''
)
replace_once(
    "v2/scripts/package-module.sh",
    '''echo "已生成 Alpha 42 全局收口与稳定性模块：$OUTPUT"''',
    '''echo "已生成 Alpha 42.1 深度扫描流程热修复模块：$OUTPUT"'''
)
replace_once(
    "v2/module/customize.sh",
    '''ui_print "- 安装白泽 v2 Alpha 42 全局收口与稳定性版"''',
    '''ui_print "- 安装白泽 v2 Alpha 42.1 深度扫描流程热修复版"'''
)
