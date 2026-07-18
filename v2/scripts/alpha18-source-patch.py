from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch target: {label} in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def hide_section(path: Path, title: str) -> None:
    text = path.read_text(encoding="utf-8")
    marker = f'android:text="{title}"'
    marker_index = text.find(marker)
    if marker_index < 0:
        raise SystemExit(f"missing section title: {title}")
    text_start = text.rfind("<TextView", 0, marker_index)
    text_end = text.find("/>", marker_index)
    if text_start < 0 or text_end < 0:
        raise SystemExit(f"invalid section title block: {title}")
    text_end += 2
    title_block = text[text_start:text_end]
    if 'android:visibility="gone"' not in title_block:
        title_block = title_block.replace(
            f'android:text="{title}"',
            f'android:text="{title}"\n                    android:visibility="gone"',
            1,
        )
        text = text[:text_start] + title_block + text[text_end:]
        text_end = text_start + len(title_block)

    card_start = text.find("<com.google.android.material.card.MaterialCardView", text_end)
    card_end = text.find(">", card_start)
    if card_start < 0 or card_end < 0:
        raise SystemExit(f"missing card after section: {title}")
    card_open = text[card_start:card_end]
    if 'android:visibility="gone"' not in card_open:
        insertion = '\n                    android:visibility="gone"'
        text = text[:card_end] + insertion + text[card_end:]
    path.write_text(text, encoding="utf-8")


root = Path("v2")
theme_manager = root / "app/src/main/java/io/github/xgl34222220/baize/ThemeManager.kt"
theme_activity = root / "app/src/main/java/io/github/xgl34222220/baize/ThemeSettingsActivity.kt"
dashboard = root / "app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt"
layout = root / "app/src/main/res/layout/activity_dashboard.xml"
polish = root / "app/src/main/java/io/github/xgl34222220/baize/Alpha7UiPolish.kt"
build_gradle = root / "app/build.gradle.kts"
module_prop = root / "module/module.prop"
customize = root / "module/customize.sh"
package_script = root / "scripts/package-module.sh"
default_config = Path("config/default.conf")

# Theme revisions make every already-open activity recreate when a palette changes. Previously only
# ThemeSettingsActivity was recreated, leaving the dashboard and detail pages with stale colors.
replace_once(
    theme_manager,
    "import com.google.android.material.color.utilities.Hct\n",
    "import com.google.android.material.color.utilities.Hct\nimport java.util.WeakHashMap\n",
    "theme activity revision import",
)
replace_once(
    theme_manager,
    '''    const val KEY_FOLLOW_EDGE = "theme_follow_edge"
    private const val KEY_ALPHA17_MIGRATED = "theme_alpha17_migrated"
''',
    '''    const val KEY_FOLLOW_EDGE = "theme_follow_edge"
    private const val KEY_REVISION = "theme_revision"
    private const val KEY_ALPHA17_MIGRATED = "theme_alpha17_migrated"
    private val appliedRevision = WeakHashMap<Activity, Int>()
''',
    "theme revision state",
)
replace_once(
    theme_manager,
    '''            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyBeforeCreate(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) applyBeforeCreate(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
''',
    '''            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyBeforeCreate(activity)
                appliedRevision[activity] = themeRevision(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    applyBeforeCreate(activity)
                    appliedRevision[activity] = themeRevision(activity)
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                val current = themeRevision(activity)
                val applied = appliedRevision[activity] ?: current
                if (applied != current && !activity.isFinishing && !activity.isChangingConfigurations) {
                    appliedRevision[activity] = current
                    activity.recreate()
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                appliedRevision.remove(activity)
            }
''',
    "global activity theme refresh",
)
replace_once(
    theme_manager,
    '''        prefs(context).edit()
            .putString(KEY_ACCENT, normalized)
            .putString(KEY_PALETTE, normalized)
            .apply()
''',
    '''        prefs(context).edit()
            .putString(KEY_ACCENT, normalized)
            .putString(KEY_PALETTE, normalized)
            .apply()
        bumpRevision(context)
''',
    "palette revision",
)
replace_once(
    theme_manager,
    '''        prefs(context).edit().putString(KEY_MODE, normalized).apply()
        syncNightMode(context)
''',
    '''        prefs(context).edit().putString(KEY_MODE, normalized).apply()
        bumpRevision(context)
        syncNightMode(context)
''',
    "mode revision",
)
replace_once(
    theme_manager,
    '''        prefs(context).edit()
            .putBoolean(KEY_MONET, enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            .apply()
''',
    '''        prefs(context).edit()
            .putBoolean(KEY_MONET, enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            .apply()
        bumpRevision(context)
''',
    "monet revision",
)
replace_once(
    theme_manager,
    '''        prefs(context).edit().putString(KEY_MONET_STYLE, normalized).apply()
''',
    '''        prefs(context).edit().putString(KEY_MONET_STYLE, normalized).apply()
        bumpRevision(context)
''',
    "monet style revision",
)
replace_once(
    theme_manager,
    '''        prefs(context).edit().putString(KEY_COLOR_STANDARD, normalized).apply()
''',
    '''        prefs(context).edit().putString(KEY_COLOR_STANDARD, normalized).apply()
        bumpRevision(context)
''',
    "color standard revision",
)
replace_once(
    theme_manager,
    '''    private fun putBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }
''',
    '''    private fun putBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
        bumpRevision(context)
    }

    private fun themeRevision(context: Context): Int = prefs(context).getInt(KEY_REVISION, 0)

    private fun bumpRevision(context: Context) {
        val next = if (themeRevision(context) == Int.MAX_VALUE) 1 else themeRevision(context) + 1
        prefs(context).edit().putInt(KEY_REVISION, next).apply()
    }
''',
    "theme revision helpers",
)

# The theme page itself must also rebuild after changing Monet variants/standards.
replace_once(
    theme_activity,
    '''            ThemeManager.setMonetStyle(this, option.id)
            renderValues()
''',
    '''            ThemeManager.setMonetStyle(this, option.id)
            recreate()
''',
    "monet style live application",
)
replace_once(
    theme_activity,
    '''            ThemeManager.setColorStandard(this, option.id)
            renderValues()
''',
    '''            ThemeManager.setColorStandard(this, option.id)
            recreate()
''',
    "color standard live application",
)

# Manual clean always uses a safe intelligent profile. Dangerous profiles receive one clear second
# confirmation instead of exposing dozens of engine switches to the user.
replace_once(dashboard, '        binding.versionText.text = "Alpha 17"\n', '        binding.versionText.text = "Alpha 18"\n', "dashboard version")
replace_once(
    dashboard,
    '''        binding.cleanNowButton.setOnClickListener { runModuleTask("clean") }
''',
    '''        binding.cleanNowButton.setOnClickListener { runSmartClean() }
''',
    "smart clean action",
)
replace_once(
    dashboard,
    '''        binding.deepToolButton.setOnClickListener { openProfile("deep") }
        binding.corpsesToolButton.setOnClickListener { openProfile("corpses") }
''',
    '''        binding.deepToolButton.setOnClickListener {
            confirmRiskAction(
                title = "开始深度清理？",
                message = "深度清理会扫描 OEM 调试日志、自定义规则和较高风险候选项。白名单、挂载点、软链接和单文件保护仍然生效。",
                confirmText = "继续扫描"
            ) { openProfile("deep") }
        }
        binding.corpsesToolButton.setOnClickListener {
            confirmRiskAction(
                title = "扫描卸载残留？",
                message = "将检查 Android/data、obb、media 和应用私有目录中的无主数据。进入后仍会先展示候选项，不会直接删除。",
                confirmText = "继续扫描"
            ) { openProfile("corpses") }
        }
''',
    "danger confirmation actions",
)
replace_once(
    dashboard,
    '''    private fun openProfile(profile: String) {
        startActivity(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_PROFILE, profile))
    }
''',
    '''    private fun openProfile(profile: String) {
        startActivity(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_PROFILE, profile))
    }

    private fun confirmRiskAction(
        title: String,
        message: String,
        confirmText: String,
        action: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(confirmText) { _, _ -> action() }
            .show()
    }

    private fun runSmartClean() {
        val service = profileService ?: run {
            binding.taskStatusText.text = "正在连接 Root 服务…"
            connectService()
            return
        }
        if (taskRunning) return
        binding.cleanNowButton.isEnabled = false
        binding.taskStatusText.text = "正在启用智能安全清理范围…"
        lifecycleScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    service.saveSchedulerConfig(smartSchedulerPayload().toString())
                }
            }
            if (saved.isFailure || !runCatching { JSONObject(saved.getOrThrow()).optBoolean("success") }.getOrDefault(false)) {
                binding.cleanNowButton.isEnabled = true
                binding.taskStatusText.text = "智能清理配置写入失败，请重新连接 Root 服务"
                return@launch
            }
            runModuleTask("clean")
        }
    }

    private fun smartSchedulerPayload(): JSONObject {
        val interval = binding.intervalSlider.value.toInt().coerceIn(1, 720)
        return JSONObject()
            .put("enabled", flag(binding.scheduleSwitch.isChecked))
            .put("schedule_cache_enabled", 1)
            .put("schedule_cache_hours", interval)
            .put("schedule_empty_enabled", 1)
            .put("schedule_empty_hours", interval)
            .put("schedule_rules_enabled", 1)
            .put("schedule_rules_hours", interval)
            .put("schedule_fragment_enabled", 1)
            .put("schedule_fragment_hours", interval)
            .put("schedule_deep_enabled", 0)
            .put("schedule_deep_hours", 168)
            .put("daily_schedule_enabled", 0)
            .put("daily_schedule_hour", 3)
            .put("daily_schedule_minute", 0)
            .put("screen_off_only", 1)
            .put("charging_only", 0)
            .put("device_idle_only", 0)
            .put("min_battery", 20)
            .put("notify_on_complete", flag(binding.notificationSwitch.isChecked))
            .put("notify_zero_result", flag(binding.notifyZeroSwitch.isChecked))
            .put("max_file_mb", binding.largeFileSlider.value.toInt())
            .put("clean_app_cache", 1)
            .put("clean_external_cache", 1)
            .put("clean_app_rules", 1)
            .put("clean_system_logs", 1)
            .put("clean_oem_logs", 0)
            .put("clean_hidden_junk", 1)
            .put("clean_empty_files", 1)
            .put("clean_empty_dirs", 1)
            .put("clean_root_shells", 1)
            .put("clean_fragments", 1)
            .put("clean_installer_temp", 1)
            .put("clean_custom_rules", 0)
            .put("deep_high_risk_enabled", 0)
            .put("app_cache_days", 0)
            .put("external_cache_days", 0)
            .put("system_logs_days", 7)
            .put("oem_logs_days", 14)
            .put("hidden_junk_days", 0)
            .put("empty_file_days", 0)
            .put("fragment_days", 7)
            .put("installer_temp_days", 7)
            .put("root_shell_days", 14)
    }
''',
    "smart cleaner helpers",
)

# Replace the old switch-driven scheduler payload with one intelligent preset.
text = dashboard.read_text(encoding="utf-8")
start = text.find('        val interval = binding.intervalSlider.value.toInt()\n        val json = JSONObject()\n', text.find('    private fun saveSchedulerConfig()'))
end_marker = '            .put("root_shell_days", binding.rootShellDaysSlider.value.toInt())\n'
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("missing scheduler payload block")
end += len(end_marker)
text = text[:start] + '        val json = smartSchedulerPayload()\n' + text[end:]
dashboard.write_text(text, encoding="utf-8")
replace_once(
    dashboard,
    '''                    "已写入模块配置，后台调度器将在下一次轮询时直接使用。"
''',
    '''                    "智能自动清理已保存：安全项目全自动，危险项目只允许手动确认后执行。"
''',
    "smart scheduler saved message",
)

# Simplify the visible plan page. Legacy controls remain hidden so old configurations can still be
# read/migrated without breaking ViewBinding or OEM-specific slider handling.
replace_once(
    layout,
    'android:text="定时任务、条件触发与保留策略"',
    'android:text="选择执行频率，其余安全项目由白泽自动处理"',
    "plan subtitle",
)
replace_once(
    layout,
    '''                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/scheduleSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="自动清理已开启"
                            android:textColor="?attr/colorOnPrimaryContainer"
                            android:textSize="16sp"
                            android:textStyle="bold" />
''',
    '''                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="56dp"
                            android:gravity="center_vertical"
                            android:orientation="horizontal">

                            <TextView
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:text="启用自动清理"
                                android:textColor="?attr/colorOnPrimaryContainer"
                                android:textSize="17sp"
                                android:textStyle="bold" />

                            <io.github.xgl34222220.baize.ui.MiuixSwitch
                                android:id="@+id/scheduleSwitch"
                                android:layout_width="64dp"
                                android:layout_height="48dp" />
                        </LinearLayout>
''',
    "single MIUIx schedule switch",
)
for view_id in ("dailySwitch", "dailyHourText", "dailyHourSlider"):
    marker = f'android:id="@+id/{view_id}"'
    text = layout.read_text(encoding="utf-8")
    pos = text.find(marker)
    if pos < 0:
        raise SystemExit(f"missing daily control: {view_id}")
    block_end = text.find("/>", pos)
    if block_end < 0:
        raise SystemExit(f"invalid daily control: {view_id}")
    if 'android:visibility="gone"' not in text[pos:block_end]:
        text = text[:block_end] + '\n                            android:visibility="gone"' + text[block_end:]
        layout.write_text(text, encoding="utf-8")

for title in ("执行条件", "保留策略", "一键自动清理范围", "清理引擎范围", "分类保留时间"):
    hide_section(layout, title)

smart_card = '''                <com.google.android.material.card.MaterialCardView
                    style="@style/Widget.BaiZe.SimpleCard"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="14dp">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="18dp">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="智能安全模式"
                            android:textColor="?attr/colorOnSurface"
                            android:textSize="17sp"
                            android:textStyle="bold" />

                        <TextView
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="8dp"
                            android:text="自动处理应用缓存、外部缓存、规则垃圾、系统日志、隐藏临时文件、空文件与空目录、严格判定的根目录空壳、残留碎片和过期安装临时文件。"
                            android:textColor="?attr/colorOnSurfaceVariant"
                            android:textSize="12sp"
                            android:lineSpacingExtra="3dp" />

                        <TextView
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="10dp"
                            android:text="卸载残留、OEM 调试日志、自定义规则与高风险深度候选项不会后台自动删除，只能手动进入并二次确认。"
                            android:textColor="?attr/colorPrimary"
                            android:textSize="12sp"
                            android:textStyle="bold"
                            android:lineSpacingExtra="3dp" />
                    </LinearLayout>
                </com.google.android.material.card.MaterialCardView>

'''
replace_once(
    layout,
    '''                <com.google.android.material.button.MaterialButton
                    android:id="@+id/savePlanButton"''',
    smart_card + '''                <com.google.android.material.button.MaterialButton
                    android:id="@+id/savePlanButton"''',
    "smart mode explanation card",
)
replace_once(layout, 'android:text="保存并启用自动清理计划"', 'android:text="保存自动清理"', "save plan label")

# Default module configuration follows the same automatic-safe profile.
replace_once(default_config, "clean_installer_temp=0\n", "clean_installer_temp=1\n", "installer temp smart default")

# Versioning and packaging.
replace_once(build_gradle, '        versionCode = 20060\n        versionName = "2.0.0-alpha17"\n', '        versionCode = 20070\n        versionName = "2.0.0-alpha18"\n', "app version")
replace_once(module_prop, 'version=v2.0.0-alpha17\nversionCode=20060\n', 'version=v2.0.0-alpha18\nversionCode=20070\n', "module version")
replace_once(
    module_prop,
    'description=白泽 v2 Alpha 17：真正重做 BOX 风格主题系统，加入 MIUIx 自绘开关、锚点悬浮菜单、Monet 风格、色彩标准、强调色和完整视觉效果开关。\n',
    'description=白泽 v2 Alpha 18：修复 Monet 只刷新主题页的问题，所有页面统一动态配色；自动清理改为智能安全模式，危险项目仅手动二次确认。\n',
    "module description",
)
replace_once(customize, 'ui_print "- 安装白泽 v2 Alpha 17 BOX 主题重构版"\n', 'ui_print "- 安装白泽 v2 Alpha 18 全局 Monet 智能清理版"\n', "installer title")
replace_once(package_script, 'OUTPUT="$OUT/BaiZe-v2-Alpha17-Module.zip"\n', 'OUTPUT="$OUT/BaiZe-v2-Alpha18-Module.zip"\n', "package output")
replace_once(
    package_script,
    'echo "已生成 Alpha 17 BOX 风格主题、完整清理引擎、白名单、Monet 与规则库一体化模块：$OUTPUT"\n',
    'echo "已生成 Alpha 18 全局 Monet、智能自动清理、危险项二次确认与完整规则库模块：$OUTPUT"\n',
    "package message",
)
replace_once(polish, '            text = "Alpha 17"\n', '            text = "Alpha 18"\n', "polish version")
replace_once(layout, 'android:text="Alpha 17"', 'android:text="Alpha 18"', "layout version")
