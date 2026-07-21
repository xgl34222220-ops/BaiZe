from pathlib import Path

root = Path(__file__).resolve().parents[1]


def rep(path: str, old: str, new: str, count: int = 1) -> None:
    target = root / path
    text = target.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"missing anchor: {path}: {old[:100]!r}")
    target.write_text(text.replace(old, new, count))


activity = "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
rep(activity, "import org.json.JSONObject\n", "import org.json.JSONObject\nimport java.text.SimpleDateFormat\nimport java.util.Date\nimport java.util.Locale\n")
rep(
    activity,
    '''        updateStorage()

        setContent {
''',
    '''        updateStorage()
        dashboardState.value = dashboardState.value.copy(
            lastTaskTime = preferences.getString("last_task_time", "").orEmpty(),
            protectedItems = loadProtectedItems()
        )

        setContent {
'''
)
rep(
    activity,
    '''                    clearRawLog = { confirmClearRawLogs() },
                    whitelist = { startActivity(Intent(this, WhitelistActivity::class.java)) },
''',
    '''                    clearRawLog = { confirmClearRawLogs() },
                    reviewProtected = { startActivity(Intent(this, ProtectedReviewActivity::class.java)) },
                    whitelist = { startActivity(Intent(this, WhitelistActivity::class.java)) },
'''
)
rep(
    activity,
    '''            dashboardState.value = dashboardState.value.copy(
                running = false,
                recentJunk = junk,
                taskPhase = result
            )
''',
    '''            val taskTime = markTaskTime()
            val protected = protectedFromModule(emptyList(), junk)
            saveProtectedItems(protected)
            dashboardState.value = dashboardState.value.copy(
                running = false,
                recentJunk = junk,
                lastTaskTime = taskTime,
                protectedItems = protected,
                taskPhase = result
            )
'''
)
rep(
    activity,
    '''            dashboardState.value = dashboardState.value.copy(
                running = false,
                lastReleased = bytes,
                recentApps = appDetails,
                recentJunk = otherDetails,
                taskPhase = "$resultLine\n$detailLine"
            )
''',
    '''            val taskTime = markTaskTime()
            val protected = protectedFromModule(appDetails, otherDetails)
            saveProtectedItems(protected)
            dashboardState.value = dashboardState.value.copy(
                running = false,
                lastReleased = bytes,
                recentApps = appDetails,
                recentJunk = otherDetails,
                lastTaskTime = taskTime,
                protectedItems = protected,
                taskPhase = "$resultLine\n$detailLine"
            )
'''
)
rep(
    activity,
    '''            dashboardState.value = dashboardState.value.copy(
                running = false,
                scanCompleted = successfulScan,
''',
    '''            val taskTime = markTaskTime()
            dashboardState.value = dashboardState.value.copy(
                running = false,
                scanCompleted = successfulScan,
                lastTaskTime = taskTime,
'''
)
rep(
    activity,
    '''            var failures = 0
            var cancelled = false
            var stale = false
''',
    '''            var failures = 0
            var cancelled = false
            var stale = false
            val protectedItems = ArrayList<ProtectedUiItem>()
'''
)
rep(
    activity,
    '''                        when (item.optString("profile")) {
                            "empty" -> {
''',
    '''                        val action = item.optString("action")
                        if (action == "protected" || action == "partial") {
                            val reason = item.optString("reason").ifBlank {
                                if (action == "partial") "部分内容未删除" else "安全策略保护"
                            }
                            protectedItems += ProtectedUiItem(
                                id = item.optString("id").ifBlank { item.optString("path") },
                                category = item.optString("category").ifBlank { "受保护项目" },
                                path = item.optString("path"),
                                reason = reason,
                                risk = item.optString("risk", "high"),
                                selectable = reason == "高风险清理未启用" ||
                                    reason == "仍有受保护或未删除项目" ||
                                    reason.contains("大小限制")
                            )
                        }
                        when (item.optString("profile")) {
                            "empty" -> {
'''
)
rep(
    activity,
    '''            dashboardState.value = dashboardState.value.copy(
                running = false,
                scanCompleted = false,
                lastReleased = deletedBytes,
                taskPhase = "$resultLine\n$detailLine"
            )
''',
    '''            val taskTime = markTaskTime()
            saveProtectedItems(protectedItems)
            dashboardState.value = dashboardState.value.copy(
                running = false,
                scanCompleted = false,
                lastReleased = deletedBytes,
                lastTaskTime = taskTime,
                protectedItems = protectedItems,
                taskPhase = "$resultLine\n$detailLine"
            )
'''
)
rep(
    activity,
    '''    private fun packageWhitelist(): Set<String> =
''',
    r'''    private fun markTaskTime(): String {
        val value = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        preferences.edit().putString("last_task_time", value).apply()
        return value
    }

    private fun protectedFromModule(
        apps: List<AppJunkUiItem>,
        other: List<GeneralJunkUiItem>
    ): List<ProtectedUiItem> = buildList {
        apps.forEach { app ->
            app.categories.filter { it.errors > 0 && it.samplePath.isNotBlank() }.forEach { category ->
                add(
                    ProtectedUiItem(
                        id = "${app.packageName}:${category.samplePath}",
                        category = "${app.label} · ${category.name}",
                        path = category.samplePath,
                        reason = "模块报告 ${category.errors} 个异常或受保护项目",
                        risk = "high",
                        selectable = true
                    )
                )
            }
        }
        other.filter { it.errors > 0 && it.samplePath.isNotBlank() }.forEach { item ->
            add(
                ProtectedUiItem(
                    id = "${item.name}:${item.samplePath}",
                    category = item.name,
                    path = item.samplePath,
                    reason = "模块报告 ${item.errors} 个异常或受保护项目",
                    risk = "high",
                    selectable = true
                )
            )
        }
    }.distinctBy { "${it.path}|${it.reason}" }.take(120)

    private fun saveProtectedItems(items: List<ProtectedUiItem>) {
        val array = JSONArray()
        items.take(120).forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("category", item.category)
                    .put("path", item.path)
                    .put("reason", item.reason)
                    .put("risk", item.risk)
                    .put("selectable", item.selectable)
            )
        }
        preferences.edit().putString("last_protected_items", array.toString()).apply()
    }

    private fun loadProtectedItems(): List<ProtectedUiItem> = runCatching {
        val array = JSONArray(preferences.getString("last_protected_items", "[]").orEmpty())
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val path = item.optString("path").trim()
                if (path.isBlank()) continue
                add(
                    ProtectedUiItem(
                        id = item.optString("id").ifBlank { path },
                        category = item.optString("category", "受保护项目"),
                        path = path,
                        reason = item.optString("reason", "安全策略保护"),
                        risk = item.optString("risk", "high"),
                        selectable = item.optBoolean("selectable", false)
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun packageWhitelist(): Set<String> =
'''
)

print("v2.2.3 dashboard record persistence applied")
