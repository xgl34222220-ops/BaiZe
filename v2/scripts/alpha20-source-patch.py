from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch target: {label} in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path: Path, start: str, end: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    start_index = text.find(start)
    if start_index < 0:
        raise SystemExit(f"missing patch start: {label} in {path}")
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise SystemExit(f"missing patch end: {label} in {path}")
    path.write_text(text[:start_index] + replacement + text[end_index:], encoding="utf-8")


root_service = Path("v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt")
dashboard = Path("v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt")
layout = Path("v2/app/src/main/res/layout/activity_dashboard.xml")
themes = Path("v2/app/src/main/res/values/themes.xml")
polish = Path("v2/app/src/main/java/io/github/xgl34222220/baize/Alpha7UiPolish.kt")
build_gradle = Path("v2/app/build.gradle.kts")
module_prop = Path("v2/module/module.prop")
customize = Path("v2/module/customize.sh")
package_script = Path("v2/scripts/package-module.sh")
cleaner = Path("cleaner.sh")

# Persistent history is already written by cleaner.sh; expose it instead of keeping a single App-only summary.
replace_once(
    root_service,
    '''        override fun getModuleState(): String = moduleState()

        override fun getSchedulerConfig(): String = configJson()
''',
    '''        override fun getModuleState(): String = moduleState()

        override fun getTaskHistory(limit: Int): String = taskHistoryJson(limit)

        override fun clearTaskHistory(): String = clearTaskHistoryJson()

        override fun getSchedulerConfig(): String = configJson()
''',
    "history binder methods",
)

history_helpers = r'''    private fun taskHistoryJson(requestedLimit: Int): String {
        val limit = requestedLimit.coerceIn(1, 100)
        val historyFile = File(STATE_DIR, "history.tsv")
        val entries = JSONArray()
        var totalReleased = 0L
        var cleanedRuns = 0

        val lines = runCatching {
            if (historyFile.isFile) historyFile.readLines().takeLast(limit).asReversed() else emptyList()
        }.getOrDefault(emptyList())

        lines.forEach { raw ->
            val columns = raw.split('\t', limit = 8)
            if (columns.size < 7) return@forEach
            val mode = columns[1].trim()
            val bytes = columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val cleaned = mode != "scan" && !mode.endsWith("-scan")
            if (cleaned) {
                totalReleased += bytes
                cleanedRuns += 1
            }
            entries.put(
                JSONObject()
                    .put("time", columns[0].trim())
                    .put("mode", mode)
                    .put("bytes", bytes)
                    .put("files", columns[3].toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    .put("emptyDirs", columns[4].toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    .put("errors", columns[5].toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    .put("result", columns[6].trim())
                    .put("trigger", columns.getOrNull(7)?.trim().orEmpty())
                    .put("cleaned", cleaned)
            )
        }

        return JSONObject()
            .put("success", true)
            .put("count", entries.length())
            .put("cleanedRuns", cleanedRuns)
            .put("totalReleased", totalReleased)
            .put("entries", entries)
            .toString()
    }

    private fun clearTaskHistoryJson(): String = runCatching {
        File(STATE_DIR, "history.tsv").writeText("")
        File(STATE_DIR, "latest.env").delete()
        File(STATE_DIR, "reports/latest.tsv").delete()
        JSONObject().put("success", true).toString()
    }.getOrElse { error ->
        JSONObject()
            .put("success", false)
            .put("error", error.message ?: error.javaClass.simpleName)
            .toString()
    }

'''
replace_once(
    root_service,
    "    private fun configJson(): String = configJsonObject().toString()\n",
    history_helpers + "    private fun configJson(): String = configJsonObject().toString()\n",
    "history helpers",
)

# Preserve trigger information for new records while remaining compatible with the old seven-column file.
replace_once(
    cleaner,
    '''printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$REQUEST_MODE" "$BYTES" "$TOTAL_FILES" "$EMPTY_DIRS" "$ERRORS" "$RESULT" >>"$HISTORY_FILE"
''',
    '''printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$REQUEST_MODE" "$BYTES" "$TOTAL_FILES" "$EMPTY_DIRS" "$ERRORS" "$RESULT" "$TRIGGER" >>"$HISTORY_FILE"
''',
    "history trigger column",
)

# Dashboard history UI and rendering.
replace_once(
    dashboard,
    "import io.github.xgl34222220.baize.databinding.ActivityDashboardBinding\n",
    "import io.github.xgl34222220.baize.databinding.ActivityDashboardBinding\nimport io.github.xgl34222220.baize.databinding.ItemTaskHistoryBinding\n",
    "history binding import",
)
replace_once(
    dashboard,
    "    private var pendingSmartClean = false\n",
    "    private var pendingSmartClean = false\n    private var historyLoading = false\n",
    "history loading state",
)
replace_once(
    dashboard,
    '''            refreshModuleState()
            refreshWhitelist()
            consumePendingSmartClean()
''',
    '''            refreshModuleState()
            refreshWhitelist()
            refreshTaskHistory()
            consumePendingSmartClean()
''',
    "history on service connect",
)
replace_once(dashboard, '        binding.versionText.text = "Alpha 19"\n', '        binding.versionText.text = "Alpha 20"\n', "dashboard version")
replace_once(
    dashboard,
    '''        refreshWhitelist()
        renderThemeSummary()
''',
    '''        refreshWhitelist()
        refreshTaskHistory()
        renderThemeSummary()
''',
    "history on resume",
)
replace_once(
    dashboard,
    '''                R.id.nav_records -> {
                    show(binding.recordsPage)
                    true
                }
''',
    '''                R.id.nav_records -> {
                    show(binding.recordsPage)
                    refreshTaskHistory()
                    true
                }
''',
    "history tab refresh",
)
replace_once(
    dashboard,
    '''        binding.savePlanButton.setOnClickListener { saveSchedulerConfig() }
        binding.refreshRecordsButton.setOnClickListener { refreshModuleState() }
''',
    '''        binding.savePlanButton.setOnClickListener { saveSchedulerConfig() }
        binding.refreshRecordsButton.setOnClickListener {
            refreshModuleState()
            refreshTaskHistory()
        }
        binding.clearHistoryButton.setOnClickListener { confirmClearHistory() }
''',
    "history actions",
)
replace_once(
    dashboard,
    '''        binding.lastFreedText.text = Formatter.formatFileSize(this, latestBytes.coerceAtLeast(0L))
    }
''',
    '''        binding.lastFreedText.text = Formatter.formatFileSize(this, latestBytes.coerceAtLeast(0L))
        refreshTaskHistory()
    }
''',
    "refresh history after task",
)

history_ui_methods = r'''    private fun refreshTaskHistory() {
        val service = profileService ?: run {
            binding.historyEmptyText.visibility = View.VISIBLE
            binding.historyEmptyText.text = "等待 Root 服务连接后读取记录"
            return
        }
        if (historyLoading) return
        historyLoading = true
        binding.historyEmptyText.visibility = View.VISIBLE
        binding.historyEmptyText.text = "正在读取模块清理记录…"

        lifecycleScope.launch {
            val raw = runCatching {
                withContext(Dispatchers.IO) { service.getTaskHistory(100) }
            }.getOrNull()
            historyLoading = false
            val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (json == null || !json.optBoolean("success", false)) {
                binding.historyList.removeAllViews()
                binding.historyEmptyText.visibility = View.VISIBLE
                binding.historyEmptyText.text = "记录读取失败，请重新连接 Root 服务"
                return@launch
            }
            renderTaskHistory(json)
        }
    }

    private fun renderTaskHistory(json: JSONObject) {
        val entries = json.optJSONArray("entries")
        val count = entries?.length() ?: 0
        binding.historyList.removeAllViews()
        binding.historyCountText.text = "$count 次"
        binding.historyReleasedText.text = Formatter.formatFileSize(this, json.optLong("totalReleased", 0L))

        if (count <= 0) {
            binding.historyLastText.text = "暂无"
            binding.recordSummaryText.text = "还没有清理记录"
            binding.historyEmptyText.visibility = View.VISIBLE
            binding.historyEmptyText.text = "完成一次手动或自动清理后，记录会永久保存在模块目录中。"
            binding.taskStatusText.text = "还没有清理记录"
            binding.recentTaskText.text = "记录由模块保存，重启 App 或手机后仍会保留"
            return
        }

        binding.historyEmptyText.visibility = View.GONE
        for (index in 0 until count) {
            val item = entries?.optJSONObject(index) ?: continue
            val row = ItemTaskHistoryBinding.inflate(layoutInflater, binding.historyList, false)
            val mode = item.optString("mode")
            val cleaned = item.optBoolean("cleaned")
            val bytes = item.optLong("bytes", 0L).coerceAtLeast(0L)
            val files = item.optInt("files", 0).coerceAtLeast(0)
            val emptyDirs = item.optInt("emptyDirs", 0).coerceAtLeast(0)
            val errors = item.optInt("errors", 0).coerceAtLeast(0)
            val result = item.optString("result", if (cleaned) "清理完成" else "扫描完成")
            val trigger = historyTriggerLabel(item.optString("trigger"))

            row.historyTitle.text = historyModeTitle(mode)
            row.historySubtitle.text = "${item.optString("time")} · $trigger"
            row.historyDetail.text = "$result\n$files 个项目 · 空目录 $emptyDirs · 异常 $errors"
            row.historyBytes.text = if (cleaned) {
                Formatter.formatFileSize(this, bytes)
            } else {
                "可清理 ${Formatter.formatFileSize(this, bytes)}"
            }
            row.historyStatus.text = when {
                errors > 0 -> "有异常"
                cleaned -> "已清理"
                else -> "仅扫描"
            }
            binding.historyList.addView(row.root)
        }

        val latest = entries?.optJSONObject(0) ?: return
        val latestTime = latest.optString("time")
        val latestResult = latest.optString("result", "任务完成")
        val latestCleaned = latest.optBoolean("cleaned")
        val latestBytes = latest.optLong("bytes", 0L).coerceAtLeast(0L)
        val latestFiles = latest.optInt("files", 0).coerceAtLeast(0)
        binding.historyLastText.text = latestTime.substringAfter(' ', latestTime)
        binding.recordSummaryText.text = latestResult
        binding.taskStatusText.text = latestResult
        binding.recentTaskText.visibility = View.VISIBLE
        binding.recentTaskText.text = "$latestTime · $latestFiles 个项目"
        if (latestCleaned) binding.lastFreedText.text = Formatter.formatFileSize(this, latestBytes)
    }

    private fun confirmClearHistory() {
        val service = profileService ?: return
        AlertDialog.Builder(this)
            .setTitle("清空清理记录？")
            .setMessage("只会删除历史任务摘要，不会修改累计统计、白名单、清理规则或用户文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val raw = runCatching { withContext(Dispatchers.IO) { service.clearTaskHistory() } }.getOrNull()
                    val success = raw?.let { runCatching { JSONObject(it).optBoolean("success") }.getOrDefault(false) } == true
                    if (success) {
                        preferences.edit().remove("last_report_text").remove("last_clean_bytes").apply()
                        refreshTaskHistory()
                        Toast.makeText(this@DashboardActivity, "清理记录已清空", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@DashboardActivity, "清空失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun historyModeTitle(mode: String): String = when (mode) {
        "scan" -> "智能安全扫描"
        "clean" -> "智能自动清理"
        "cache-clean" -> "应用缓存清理"
        "empty-clean" -> "空文件与空目录清理"
        "rules-clean" -> "规则垃圾清理"
        "fragment-scan" -> "残留碎片扫描"
        "fragment-clean" -> "残留碎片清理"
        "deep-scan" -> "完整深度扫描"
        "deep-clean" -> "完整深度清理"
        "corpse-scan" -> "卸载残留扫描"
        "corpse-clean" -> "卸载残留清理"
        else -> "清理任务"
    }

    private fun historyTriggerLabel(trigger: String): String = when {
        trigger.startsWith("scheduled:") -> "自动定时"
        trigger.startsWith("daily:") -> "每日定时"
        trigger == "app" -> "App 手动"
        trigger == "manual" -> "手动执行"
        trigger.isBlank() -> "历史任务"
        else -> trigger
    }

'''
replace_once(
    dashboard,
    "    private fun refreshSavedReport() {\n",
    history_ui_methods + "    private fun refreshSavedReport() {\n",
    "history UI methods",
)

# Replace the placeholder records page with a real persistent MIUIx history timeline.
records_page = '''        <androidx.core.widget.NestedScrollView
            android:id="@+id/recordsPage"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:clipToPadding="false"
            android:fillViewport="true"
            android:overScrollMode="never"
            android:visibility="gone">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:paddingStart="18dp"
                android:paddingTop="20dp"
                android:paddingEnd="18dp"
                android:paddingBottom="112dp">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:fontFamily="sans-serif-medium"
                    android:letterSpacing="0.15"
                    android:text="CLEAN HISTORY"
                    android:textColor="?attr/colorPrimary"
                    android:textSize="9sp"
                    android:textStyle="bold" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="4dp"
                    android:text="清理记录"
                    android:textAppearance="@style/TextAppearance.BaiZe.PageTitle" />

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="4dp"
                    android:text="记录由模块持久保存，重启 App 或手机后仍然存在"
                    android:textColor="?attr/colorOnSurfaceVariant"
                    android:textSize="12sp" />

                <com.google.android.material.card.MaterialCardView
                    style="@style/Widget.BaiZe.SimpleCard"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="18dp"
                    app:cardCornerRadius="28dp">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="18dp">

                        <TextView
                            android:id="@+id/recordSummaryText"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:fontFamily="sans-serif-medium"
                            android:text="还没有清理记录"
                            android:textColor="?attr/colorOnSurface"
                            android:textSize="17sp"
                            android:textStyle="bold" />

                        <TextView
                            android:id="@+id/recordSchedulerText"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="6dp"
                            android:text="正在读取自动清理状态"
                            android:textColor="?attr/colorOnSurfaceVariant"
                            android:textSize="11sp" />

                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="16dp"
                            android:orientation="horizontal">

                            <LinearLayout
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:gravity="center"
                                android:orientation="vertical">
                                <TextView android:id="@+id/historyCountText" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="0 次" android:textColor="?attr/colorPrimary" android:textSize="18sp" android:textStyle="bold" />
                                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginTop="4dp" android:text="记录数量" android:textColor="?attr/colorOnSurfaceVariant" android:textSize="10sp" />
                            </LinearLayout>

                            <View android:layout_width="1dp" android:layout_height="42dp" android:background="?attr/colorOutlineVariant" />

                            <LinearLayout
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:gravity="center"
                                android:orientation="vertical">
                                <TextView android:id="@+id/historyReleasedText" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="0 B" android:textColor="?attr/colorPrimary" android:textSize="18sp" android:textStyle="bold" />
                                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginTop="4dp" android:text="累计释放" android:textColor="?attr/colorOnSurfaceVariant" android:textSize="10sp" />
                            </LinearLayout>

                            <View android:layout_width="1dp" android:layout_height="42dp" android:background="?attr/colorOutlineVariant" />

                            <LinearLayout
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:gravity="center"
                                android:orientation="vertical">
                                <TextView android:id="@+id/historyLastText" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="暂无" android:textColor="?attr/colorPrimary" android:textSize="15sp" android:textStyle="bold" />
                                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginTop="4dp" android:text="最近任务" android:textColor="?attr/colorOnSurfaceVariant" android:textSize="10sp" />
                            </LinearLayout>
                        </LinearLayout>
                    </LinearLayout>
                </com.google.android.material.card.MaterialCardView>

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="22dp"
                    android:gravity="center_vertical"
                    android:orientation="horizontal">

                    <TextView
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:fontFamily="sans-serif-medium"
                        android:text="最近任务"
                        android:textColor="?attr/colorOnSurface"
                        android:textSize="19sp"
                        android:textStyle="bold" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/clearHistoryButton"
                        style="@style/Widget.Material3.Button.TextButton"
                        android:layout_width="wrap_content"
                        android:layout_height="42dp"
                        android:text="清空"
                        android:textColor="?attr/colorError" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/refreshRecordsButton"
                        style="@style/Widget.Material3.Button.FilledTonalButton"
                        android:layout_width="wrap_content"
                        android:layout_height="42dp"
                        android:layout_marginStart="4dp"
                        android:text="刷新" />
                </LinearLayout>

                <TextView
                    android:id="@+id/historyEmptyText"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:background="?attr/colorSurface"
                    android:gravity="center"
                    android:padding="24dp"
                    android:text="正在读取模块清理记录…"
                    android:textColor="?attr/colorOnSurfaceVariant"
                    android:textSize="12sp" />

                <LinearLayout
                    android:id="@+id/historyList"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:orientation="vertical" />
            </LinearLayout>
        </androidx.core.widget.NestedScrollView>

'''
replace_between(
    layout,
    '        <androidx.core.widget.NestedScrollView\n            android:id="@+id/recordsPage"',
    '        <androidx.core.widget.NestedScrollView\n            android:id="@+id/settingsPage"',
    records_page,
    "records page redesign",
)
replace_once(layout, '    android:background="@drawable/bg_app_gradient">\n', '    android:background="?android:attr/colorBackground">\n', "dashboard background")
replace_once(layout, '                        android:text="Alpha 18"\n', '                        android:text="Alpha 20"\n', "layout version")
replace_once(layout, '                    android:text="运行概览"\n', '                    android:text="最近清理"\n', "home overview title")
replace_once(
    layout,
    '''                            android:id="@+id/recentTaskText"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="7dp"
                            android:text="暂无清理记录"
                            android:textColor="?attr/colorOnSurfaceVariant"
                            android:textSize="11sp"
                            android:visibility="gone" />
''',
    '''                            android:id="@+id/recentTaskText"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="7dp"
                            android:text="记录由模块保存，重启后仍会保留"
                            android:textColor="?attr/colorOnSurfaceVariant"
                            android:textSize="11sp" />
''',
    "home latest detail visibility",
)

# True MIUIx foundation: neutral background, opaque readable surfaces and only the dock keeps glass.
replace_once(
    polish,
    "import io.github.xgl34222220.baize.ui.LiquidBackdropDrawable\n",
    "",
    "remove page backdrop import",
)
replace_once(
    polish,
    '''        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content?.getChildAt(0)?.background = if (ThemeManager.isBlurEnabled(activity)) {
            LiquidBackdropDrawable(activity)
        } else {
            ColorDrawable(MaterialColors.getColor(activity, android.R.attr.colorBackground, Color.rgb(238, 239, 249)))
        }
''',
    '''        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content?.getChildAt(0)?.background = ColorDrawable(
            MaterialColors.getColor(activity, android.R.attr.colorBackground, Color.rgb(242, 245, 250))
        )
''',
    "solid readable activity background",
)
replace_once(polish, '            text = "Alpha 19"\n', '            text = "Alpha 20"\n', "polish version")

# Light palette and typography closer to the WebUI/MIUIx reference.
for old, new in {
    '<item name="colorSurface">#F9F8FE</item>': '<item name="colorSurface">#FFFFFF</item>',
    '<item name="colorSurfaceVariant">#EFEEF5</item>': '<item name="colorSurfaceVariant">#F4F6FA</item>',
    '<item name="colorOnSurface">#1B1D27</item>': '<item name="colorOnSurface">#17202D</item>',
    '<item name="colorOnSurfaceVariant">#555A67</item>': '<item name="colorOnSurfaceVariant">#657184</item>',
    '<item name="colorOutline">#D8DAE4</item>': '<item name="colorOutline">#D7DEE8</item>',
    '<item name="colorOutlineVariant">#DFE1E9</item>': '<item name="colorOutlineVariant">#E6EAF0</item>',
    '<item name="android:colorBackground">#EEEFF9</item>': '<item name="android:colorBackground">#F2F5FA</item>',
    '<item name="android:windowBackground">#EEEFF9</item>': '<item name="android:windowBackground">#F2F5FA</item>',
    '<item name="android:statusBarColor">#EEEFF9</item>': '<item name="android:statusBarColor">#F2F5FA</item>',
    '<item name="android:navigationBarColor">#EEEFF9</item>': '<item name="android:navigationBarColor">#F2F5FA</item>',
    '<item name="android:textSize">36sp</item>': '<item name="android:textSize">30sp</item>',
}.items():
    replace_once(themes, old, new, f"theme token {old}")

# Versioning and packaging.
replace_once(build_gradle, '        versionCode = 20080\n        versionName = "2.0.0-alpha19"\n', '        versionCode = 20090\n        versionName = "2.0.0-alpha20"\n', "app version")
replace_once(module_prop, 'version=v2.0.0-alpha19\nversionCode=20080\n', 'version=v2.0.0-alpha20\nversionCode=20090\n', "module version")
replace_once(
    module_prop,
    'description=白泽 v2 Alpha 19：按 WebUI 设计系统重做清理明细，统一动态主题、分组卡片、安全区、立即清理与危险项二次确认。\n',
    'description=白泽 v2 Alpha 20：恢复模块持久清理历史，重做 MIUIx 记录页与最近清理信息，并修复浅色模式旧页面文字对比度。\n',
    "module description",
)
replace_once(customize, 'ui_print "- 安装白泽 v2 Alpha 19 WebUI 原生重构版"\n', 'ui_print "- 安装白泽 v2 Alpha 20 MIUIx 持久记录版"\n', "installer title")
replace_once(package_script, 'OUTPUT="$OUT/BaiZe-v2-Alpha19-Module.zip"\n', 'OUTPUT="$OUT/BaiZe-v2-Alpha20-Module.zip"\n', "package output")
replace_once(
    package_script,
    'echo "已生成 Alpha 19 WebUI 原生清理中心、全局 Monet、智能自动清理与完整规则库模块：$OUTPUT"\n',
    'echo "已生成 Alpha 20 MIUIx 持久记录、高对比主题、智能自动清理与完整规则库模块：$OUTPUT"\n',
    "package message",
)
