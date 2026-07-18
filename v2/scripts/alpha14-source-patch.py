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


root = Path("v2")
root_service = root / "app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
dashboard = root / "app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt"
layout = root / "app/src/main/res/layout/activity_dashboard.xml"
themes = root / "app/src/main/res/values/themes.xml"
dock = root / "app/src/main/java/io/github/xgl34222220/baize/ui/FloatingGlassDock.kt"
polish = root / "app/src/main/java/io/github/xgl34222220/baize/Alpha7UiPolish.kt"
build_gradle = root / "app/build.gradle.kts"
module_prop = root / "module/module.prop"
customize = root / "module/customize.sh"
package_script = root / "scripts/package-module.sh"
default_config = Path("config/default.conf")
cleaner = Path("cleaner.sh")

# Root package catalog: bypass Android package visibility filtering by asking package manager as root.
replace_once(
    root_service,
    '''        override fun saveSchedulerConfig(configJson: String?): String = saveConfig(configJson.orEmpty())

        override fun getWhitelistPackages(): String = this@BaiZeProfileRootService.whitelistPackagesJson()
''',
    '''        override fun saveSchedulerConfig(configJson: String?): String = saveConfig(configJson.orEmpty())

        override fun getInstalledPackageCatalog(): String =
            this@BaiZeProfileRootService.installedPackageCatalogJson()

        override fun getWhitelistPackages(): String = this@BaiZeProfileRootService.whitelistPackagesJson()
''',
    "root package catalog binder",
)

catalog_helpers = r'''    private fun installedPackageCatalogJson(): String {
        val systemPackages = queryPackageNames("cmd package list packages -s").toSet()
        val thirdPartyPackages = queryPackageNames("cmd package list packages -3").toSet()
        val allPackages = linkedSetOf<String>()
        allPackages += queryPackageNames("cmd package list packages")
        allPackages += systemPackages
        allPackages += thirdPartyPackages

        if (allPackages.isEmpty()) {
            listOf("/data/user/0", "/data/user_de/0").forEach { rootPath ->
                File(rootPath).listFiles()
                    ?.asSequence()
                    ?.filter { it.isDirectory && PACKAGE_NAME.matches(it.name) }
                    ?.mapTo(allPackages) { it.name }
            }
        }

        val packages = JSONArray()
        allPackages.asSequence()
            .filter { PACKAGE_NAME.matches(it) }
            .sorted()
            .forEach { packageName ->
                packages.put(
                    JSONObject()
                        .put("packageName", packageName)
                        .put("system", packageName in systemPackages && packageName !in thirdPartyPackages)
                )
            }
        return JSONObject()
            .put("success", packages.length() > 0)
            .put("source", if (systemPackages.isNotEmpty() || thirdPartyPackages.isNotEmpty()) "root-cmd" else "data-fallback")
            .put("count", packages.length())
            .put("packages", packages)
            .toString()
    }

    private fun queryPackageNames(command: String): List<String> = runCatching {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly()
        lines.asSequence()
            .map { it.trim().removePrefix("package:").substringBefore(' ') }
            .filter { PACKAGE_NAME.matches(it) }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

'''
replace_once(
    root_service,
    "    private fun whitelistPackagesJson(): String = JSONArray(readWhitelistPackages().sorted()).toString()\n",
    catalog_helpers + "    private fun whitelistPackagesJson(): String = JSONArray(readWhitelistPackages().sorted()).toString()\n",
    "root package catalog helpers",
)
replace_once(
    root_service,
    '''            "clean_custom_rules" to 0..1,
            "notify_on_complete" to 0..1,
''',
    '''            "clean_custom_rules" to 0..1,
            "clean_installer_temp" to 0..1,
            "notify_on_complete" to 0..1,
''',
    "installer cleanup config toggle",
)
replace_once(
    root_service,
    '''            "fragment_days" to 0..365,
            "max_file_mb" to 16..16_384
''',
    '''            "fragment_days" to 0..365,
            "installer_temp_days" to 1..30,
            "max_file_mb" to 16..16_384
''',
    "installer retention config",
)

# Dashboard visual hierarchy: simpler hero, readable metrics and less cramped content.
hero = '''                <com.google.android.material.card.MaterialCardView
                    android:tag="glass:hero"
                    style="@style/Widget.BaiZe.GlassCard"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="18dp">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="18dp">

                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:gravity="center_vertical"
                            android:orientation="horizontal">

                            <LinearLayout
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:orientation="vertical">

                                <TextView
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text="可用空间"
                                    android:textColor="?attr/colorOnSurfaceVariant"
                                    android:textSize="12sp" />

                                <TextView
                                    android:id="@+id/freeSpaceText"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="4dp"
                                    android:text="--"
                                    android:textColor="?attr/colorOnSurface"
                                    android:textSize="31sp"
                                    android:textStyle="bold" />

                                <TextView
                                    android:id="@+id/storageDetailText"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:layout_marginTop="4dp"
                                    android:text="正在读取存储状态"
                                    android:textColor="?attr/colorOnSurfaceVariant"
                                    android:textSize="11sp" />
                            </LinearLayout>

                            <com.google.android.material.progressindicator.CircularProgressIndicator
                                android:id="@+id/storageRing"
                                android:layout_width="72dp"
                                android:layout_height="72dp"
                                android:layout_marginStart="14dp"
                                android:indeterminate="false"
                                app:indicatorColor="?attr/colorPrimary"
                                app:indicatorSize="68dp"
                                app:trackColor="?attr/colorSurfaceVariant"
                                app:trackThickness="6dp" />
                        </LinearLayout>

                        <View
                            android:layout_width="match_parent"
                            android:layout_height="1dp"
                            android:layout_marginTop="15dp"
                            android:background="?attr/colorOutline" />

                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="13dp"
                            android:gravity="center_vertical"
                            android:orientation="horizontal">

                            <TextView
                                android:layout_width="0dp"
                                android:layout_height="wrap_content"
                                android:layout_weight="1"
                                android:text="最近一次释放"
                                android:textColor="?attr/colorOnSurfaceVariant"
                                android:textSize="11sp" />

                            <TextView
                                android:id="@+id/lastFreedText"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="暂无"
                                android:textColor="?attr/colorPrimary"
                                android:textSize="16sp"
                                android:textStyle="bold" />
                        </LinearLayout>
                    </LinearLayout>
                </com.google.android.material.card.MaterialCardView>

'''
replace_between(
    layout,
    '                <com.google.android.material.card.MaterialCardView\n                    android:tag="glass:hero"',
    '                <com.google.android.material.button.MaterialButton\n                    android:id="@+id/cleanNowButton"',
    hero,
    "dashboard storage hero",
)

# Complete cleaning controls: all existing engine switches are now visible and persisted.
engine_controls = '''                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="22dp"
                    android:text="清理引擎范围"
                    android:textAppearance="@style/TextAppearance.BaiZe.SectionTitle" />

                <com.google.android.material.card.MaterialCardView
                    style="@style/Widget.BaiZe.GlassCard"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="9dp">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="16dp">

                        <TextView
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="手动一键清理与后台任务共用以下范围。高风险项目仍不会自动执行。"
                            android:textColor="?attr/colorOnSurfaceVariant"
                            android:textSize="11sp" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanInternalCacheSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="8dp"
                            android:text="应用内部缓存与代码缓存"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanExternalCacheSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="Android/data 外部缓存"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanAppRulesSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="应用规则与 WebView 可再生缓存"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanSystemLogsSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="系统崩溃与诊断日志"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanOemLogsSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="HyperOS / ColorOS 调试日志"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanHiddenJunkSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="缩略图与隐藏临时目录"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanEmptyFilesSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="安全范围内的空文件"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanEmptyDirsSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="安全范围内的空目录"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanFragmentsSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="残留碎片与未完成临时文件"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanInstallerTempSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="过期安装临时文件（仅 .tmp / .part）"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanCustomRulesSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="自定义规则（高级用户）"
                            android:textColor="?attr/colorOnSurface" />
                    </LinearLayout>
                </com.google.android.material.card.MaterialCardView>

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="22dp"
                    android:text="分类保留时间"
                    android:textAppearance="@style/TextAppearance.BaiZe.SectionTitle" />

                <com.google.android.material.card.MaterialCardView
                    style="@style/Widget.BaiZe.GlassCard"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="9dp">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="16dp">

                        <TextView
                            android:id="@+id/cacheDaysText"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="缓存立即清理"
                            android:textColor="?attr/colorOnSurface"
                            android:textStyle="bold" />

                        <com.google.android.material.slider.Slider
                            android:id="@+id/cacheDaysSlider"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:stepSize="1"
                            android:value="0"
                            android:valueFrom="0"
                            android:valueTo="30" />

                        <TextView
                            android:id="@+id/logDaysText"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="6dp"
                            android:text="日志保留 7 天"
                            android:textColor="?attr/colorOnSurface"
                            android:textStyle="bold" />

                        <com.google.android.material.slider.Slider
                            android:id="@+id/logDaysSlider"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:stepSize="1"
                            android:value="7"
                            android:valueFrom="0"
                            android:valueTo="30" />

                        <TextView
                            android:id="@+id/installerDaysText"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="6dp"
                            android:text="安装临时文件保留 7 天"
                            android:textColor="?attr/colorOnSurface"
                            android:textStyle="bold" />

                        <com.google.android.material.slider.Slider
                            android:id="@+id/installerDaysSlider"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:stepSize="1"
                            android:value="7"
                            android:valueFrom="1"
                            android:valueTo="30" />
                    </LinearLayout>
                </com.google.android.material.card.MaterialCardView>

'''
save_anchor = '''                <com.google.android.material.button.MaterialButton
                    android:id="@+id/savePlanButton"'''
replace_once(layout, save_anchor, engine_controls + save_anchor, "engine scope UI")
replace_once(
    layout,
    '''                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/notificationSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="14dp"
                            android:text="任务完成后发送通知"
                            android:textColor="?attr/colorOnSurface" />
''',
    '''                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/notificationSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="14dp"
                            android:text="任务完成后发送通知"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/notifyZeroSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="没有垃圾时也发送完成通知"
                            android:textColor="?attr/colorOnSurface" />
''',
    "zero result notification UI",
)
replace_once(
    layout,
    '''        android:layout_height="92dp"
        android:layout_gravity="bottom"
        android:layout_marginStart="16dp"
        android:layout_marginEnd="16dp"
        android:layout_marginBottom="24dp" />
''',
    '''        android:layout_height="84dp"
        android:layout_gravity="bottom"
        android:layout_marginStart="16dp"
        android:layout_marginEnd="16dp"
        android:layout_marginBottom="16dp" />
''',
    "floating dock dimensions",
)

# Dashboard code for every new switch/retention option.
replace_once(dashboard, '        binding.versionText.text = "Alpha 13"\n', '        binding.versionText.text = "Alpha 14"\n', "dashboard version")
replace_once(
    dashboard,
    '''            saveSettingsPatch(
                notification = binding.notificationSwitch.isChecked,
                maxFileMb = binding.largeFileSlider.value.toInt()
            )
''',
    '''            saveSettingsPatch(
                notification = binding.notificationSwitch.isChecked,
                notifyZero = binding.notifyZeroSwitch.isChecked,
                maxFileMb = binding.largeFileSlider.value.toInt()
            )
''',
    "save protection args",
)
replace_once(
    dashboard,
    '    private fun saveSettingsPatch(notification: Boolean? = null, maxFileMb: Int? = null) {\n',
    '    private fun saveSettingsPatch(notification: Boolean? = null, notifyZero: Boolean? = null, maxFileMb: Int? = null) {\n',
    "save protection signature",
)
replace_once(
    dashboard,
    '''        notification?.let { payload.put("notify_on_complete", if (it) 1 else 0) }
        maxFileMb?.let { payload.put("max_file_mb", it.coerceIn(16, 2048)) }
''',
    '''        notification?.let { payload.put("notify_on_complete", if (it) 1 else 0) }
        notifyZero?.let { payload.put("notify_zero_result", if (it) 1 else 0) }
        maxFileMb?.let { payload.put("max_file_mb", it.coerceIn(16, 2048)) }
''',
    "save zero notification",
)
replace_once(
    dashboard,
    '''        binding.fragmentDaysSlider.addOnChangeListener { _, value, _ ->
            binding.fragmentDaysText.text = fragmentRetentionLabel(value.toInt())
        }
''',
    '''        binding.fragmentDaysSlider.addOnChangeListener { _, value, _ ->
            binding.fragmentDaysText.text = fragmentRetentionLabel(value.toInt())
        }
        binding.cacheDaysSlider.addOnChangeListener { _, value, _ ->
            binding.cacheDaysText.text = retentionLabel("缓存", value.toInt())
        }
        binding.logDaysSlider.addOnChangeListener { _, value, _ ->
            binding.logDaysText.text = retentionLabel("日志", value.toInt())
        }
        binding.installerDaysSlider.addOnChangeListener { _, value, _ ->
            binding.installerDaysText.text = "安装临时文件保留 ${value.toInt()} 天"
        }
''',
    "retention listeners",
)
replace_once(
    dashboard,
    '''            binding.notificationSwitch.isChecked = json.optInt("notify_on_complete", 1) == 1
            binding.largeFileSlider.value = snapToStep(json.optInt("max_file_mb", 256), 16, 2048, 16)
            binding.fragmentDaysSlider.value = json.optInt("fragment_days", 7).coerceIn(0, 30).toFloat()
''',
    '''            binding.notificationSwitch.isChecked = json.optInt("notify_on_complete", 1) == 1
            binding.notifyZeroSwitch.isChecked = json.optInt("notify_zero_result", 0) == 1
            binding.largeFileSlider.value = snapToStep(json.optInt("max_file_mb", 256), 16, 2048, 16)
            binding.fragmentDaysSlider.value = json.optInt("fragment_days", 7).coerceIn(0, 30).toFloat()
            binding.cacheDaysSlider.value = json.optInt("app_cache_days", 0).coerceIn(0, 30).toFloat()
            binding.logDaysSlider.value = json.optInt("system_logs_days", 7).coerceIn(0, 30).toFloat()
            binding.installerDaysSlider.value = json.optInt("installer_temp_days", 7).coerceIn(1, 30).toFloat()
            binding.cleanInternalCacheSwitch.isChecked = json.optInt("clean_app_cache", 1) == 1
            binding.cleanExternalCacheSwitch.isChecked = json.optInt("clean_external_cache", 1) == 1
            binding.cleanAppRulesSwitch.isChecked = json.optInt("clean_app_rules", 1) == 1
            binding.cleanSystemLogsSwitch.isChecked = json.optInt("clean_system_logs", 1) == 1
            binding.cleanOemLogsSwitch.isChecked = json.optInt("clean_oem_logs", 0) == 1
            binding.cleanHiddenJunkSwitch.isChecked = json.optInt("clean_hidden_junk", 1) == 1
            binding.cleanEmptyFilesSwitch.isChecked = json.optInt("clean_empty_files", 1) == 1
            binding.cleanEmptyDirsSwitch.isChecked = json.optInt("clean_empty_dirs", 1) == 1
            binding.cleanFragmentsSwitch.isChecked = json.optInt("clean_fragments", 1) == 1
            binding.cleanInstallerTempSwitch.isChecked = json.optInt("clean_installer_temp", 0) == 1
            binding.cleanCustomRulesSwitch.isChecked = json.optInt("clean_custom_rules", 0) == 1
''',
    "load cleaner scope",
)
replace_once(
    dashboard,
    '''            binding.largeFileText.text = "单文件上限 ${binding.largeFileSlider.value.toInt()} MB"
            binding.fragmentDaysText.text = fragmentRetentionLabel(binding.fragmentDaysSlider.value.toInt())
            updatePlanPreview()
''',
    '''            binding.largeFileText.text = "单文件上限 ${binding.largeFileSlider.value.toInt()} MB"
            binding.fragmentDaysText.text = fragmentRetentionLabel(binding.fragmentDaysSlider.value.toInt())
            binding.cacheDaysText.text = retentionLabel("缓存", binding.cacheDaysSlider.value.toInt())
            binding.logDaysText.text = retentionLabel("日志", binding.logDaysSlider.value.toInt())
            binding.installerDaysText.text = "安装临时文件保留 ${binding.installerDaysSlider.value.toInt()} 天"
            updatePlanPreview()
''',
    "render retention labels",
)
replace_once(
    dashboard,
    '''            .put("notify_on_complete", flag(binding.notificationSwitch.isChecked))
            .put("max_file_mb", binding.largeFileSlider.value.toInt())
            .put("fragment_days", binding.fragmentDaysSlider.value.toInt())
''',
    '''            .put("notify_on_complete", flag(binding.notificationSwitch.isChecked))
            .put("notify_zero_result", flag(binding.notifyZeroSwitch.isChecked))
            .put("max_file_mb", binding.largeFileSlider.value.toInt())
            .put("clean_app_cache", flag(binding.cleanInternalCacheSwitch.isChecked))
            .put("clean_external_cache", flag(binding.cleanExternalCacheSwitch.isChecked))
            .put("clean_app_rules", flag(binding.cleanAppRulesSwitch.isChecked))
            .put("clean_system_logs", flag(binding.cleanSystemLogsSwitch.isChecked))
            .put("clean_oem_logs", flag(binding.cleanOemLogsSwitch.isChecked))
            .put("clean_hidden_junk", flag(binding.cleanHiddenJunkSwitch.isChecked))
            .put("clean_empty_files", flag(binding.cleanEmptyFilesSwitch.isChecked))
            .put("clean_empty_dirs", flag(binding.cleanEmptyDirsSwitch.isChecked))
            .put("clean_fragments", flag(binding.cleanFragmentsSwitch.isChecked))
            .put("clean_installer_temp", flag(binding.cleanInstallerTempSwitch.isChecked))
            .put("clean_custom_rules", flag(binding.cleanCustomRulesSwitch.isChecked))
            .put("app_cache_days", binding.cacheDaysSlider.value.toInt())
            .put("external_cache_days", binding.cacheDaysSlider.value.toInt())
            .put("system_logs_days", binding.logDaysSlider.value.toInt())
            .put("oem_logs_days", binding.logDaysSlider.value.toInt())
            .put("hidden_junk_days", binding.cacheDaysSlider.value.toInt())
            .put("empty_file_days", binding.cacheDaysSlider.value.toInt())
            .put("fragment_days", binding.fragmentDaysSlider.value.toInt())
            .put("installer_temp_days", binding.installerDaysSlider.value.toInt())
''',
    "save cleaner scope",
)
replace_once(
    dashboard,
    '''    private fun fragmentRetentionLabel(days: Int): String =
        if (days <= 0) "碎片立即清理" else "碎片保留 $days 天"

''',
    '''    private fun fragmentRetentionLabel(days: Int): String =
        if (days <= 0) "碎片立即清理" else "碎片保留 $days 天"

    private fun retentionLabel(name: String, days: Int): String =
        if (days <= 0) "$name 立即清理" else "$name 保留 $days 天"

''',
    "generic retention label",
)

# New optional, conservative installer temporary-file cleanup. It never deletes complete APK files.
replace_once(
    default_config,
    '''clean_fragments=1
clean_custom_rules=0
notify_on_complete=1
''',
    '''clean_fragments=1
clean_custom_rules=0
clean_installer_temp=0
notify_on_complete=1
''',
    "default installer cleanup toggle",
)
replace_once(
    default_config,
    '''fragment_days=7
max_file_mb=256
''',
    '''fragment_days=7
installer_temp_days=7
max_file_mb=256
''',
    "default installer retention",
)

installer_function = r'''run_installer_temp() {
  [ -d /data/local/tmp ] || return 0
  list="$TMP_DIR/installer-temp.nul"
  find /data/local/tmp -mindepth 1 -maxdepth 2 -type f -mtime "+$INSTALLER_TEMP_DAYS" \
    \( -name '*.apk.tmp' -o -name '*.apks.tmp' -o -name '*.xapk.tmp' -o -name '*.zip.tmp' \
       -o -name '*.part' -o -name '*.download' -o -name '*.crdownload' \) \
    -size "-${MAX_FILE_BYTES}c" -print0 2>/dev/null >"$list"
  filter_whitelist_list "$list"
  while IFS= read -r -d '' file; do
    CATEGORY="过期安装临时文件"
    handle_file "$file" regular || { rm -f "$list"; return $?; }
  done <"$list"
  rm -f "$list"
  return 0
}

'''
replace_once(cleaner, "run_custom_rules() {\n", installer_function + "run_custom_rules() {\n", "installer cleanup function")
replace_once(
    cleaner,
    '''FRAGMENT_DAYS=$(get_uint fragment_days 7 0 365)
if [ "$FRAGMENT_DAYS" -eq 0 ]; then
''',
    '''FRAGMENT_DAYS=$(get_uint fragment_days 7 0 365)
INSTALLER_TEMP_DAYS=$(get_uint installer_temp_days 7 1 30)
if [ "$FRAGMENT_DAYS" -eq 0 ]; then
''',
    "installer retention runtime",
)
replace_once(
    cleaner,
    '''if [ "$STOPPED" = "0" ] && [ "$RUN_RULES" = "1" ] && [ "$(get_bool clean_custom_rules)" = "1" ]; then
  set_phase "执行自定义规则"
  run_custom_rules || STOPPED=1
fi
''',
    '''if [ "$STOPPED" = "0" ] && [ "$RUN_RULES" = "1" ] && [ "$(get_bool clean_installer_temp)" = "1" ]; then
  set_phase "扫描过期安装临时文件"
  run_installer_temp || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_RULES" = "1" ] && [ "$(get_bool clean_custom_rules)" = "1" ]; then
  set_phase "执行自定义规则"
  run_custom_rules || STOPPED=1
fi
''',
    "installer cleanup invocation",
)

# Refined palette: neutral surfaces, softer accents and a real night palette for fixed themes.
color_replacements = {
    "#356DF3": "#4D6FAD",
    "#DDE6FF": "#E3EAF6",
    "#177A83": "#4D7C7A",
    "#C8EEF1": "#DDECEA",
    "#7259C8": "#7A7192",
    "#F8F9FD": "#F7F8FA",
    "#ECEFF6": "#EEF1F5",
    "#7658D6": "#756A9C",
    "#E9E0FF": "#ECE8F4",
    "#3D739D": "#5D788E",
    "#D4E9F8": "#E1EAF0",
    "#A34E86": "#8A667A",
    "#167C68": "#4C7B70",
    "#C6F0E4": "#DDECE7",
    "#3B718E": "#5A7886",
    "#D2E9F4": "#DFE9ED",
    "#A95B25": "#8D684F",
    "#FFE2CC": "#F2E7DF",
    "#846417": "#817357",
    "#F5E5B8": "#EEE8D9",
}
text = themes.read_text(encoding="utf-8")
for old, new in color_replacements.items():
    text = text.replace(old, new)
themes.write_text(text, encoding="utf-8")

night_dir = root / "app/src/main/res/values-night"
night_dir.mkdir(parents=True, exist_ok=True)
(night_dir / "themes.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.BaiZe.Base" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorOnPrimary">#172033</item>
        <item name="colorOnPrimaryContainer">#DCE7FF</item>
        <item name="colorOnSecondary">#102322</item>
        <item name="colorOnSecondaryContainer">#D2E8E5</item>
        <item name="colorError">#FFB4AB</item>
        <item name="colorOnError">#690005</item>
        <item name="colorOnSurface">#E7E9EE</item>
        <item name="colorOnSurfaceVariant">#AEB4BF</item>
        <item name="colorOutline">#454A54</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowLightNavigationBar">false</item>
        <item name="android:windowBackground">#101217</item>
        <item name="android:statusBarColor">#101217</item>
        <item name="android:navigationBarColor">#101217</item>
        <item name="materialCardViewStyle">@style/Widget.BaiZe.GlassCard</item>
        <item name="materialButtonStyle">@style/Widget.BaiZe.Button</item>
    </style>

    <style name="Theme.BaiZe.Blue" parent="Theme.BaiZe.Base">
        <item name="colorPrimary">#B4C9F2</item>
        <item name="colorPrimaryContainer">#253552</item>
        <item name="colorSecondary">#A9CDCA</item>
        <item name="colorSecondaryContainer">#233C3A</item>
        <item name="colorTertiary">#C8BED9</item>
        <item name="colorSurface">#12151A</item>
        <item name="colorSurfaceVariant">#1B1F26</item>
    </style>

    <style name="Theme.BaiZe.Aurora" parent="Theme.BaiZe.Base">
        <item name="colorPrimary">#CBC0EB</item>
        <item name="colorPrimaryContainer">#39324E</item>
        <item name="colorSecondary">#B4CAD8</item>
        <item name="colorSecondaryContainer">#293A45</item>
        <item name="colorTertiary">#D5BACA</item>
        <item name="colorSurface">#151319</item>
        <item name="colorSurfaceVariant">#211E27</item>
    </style>

    <style name="Theme.BaiZe.Jade" parent="Theme.BaiZe.Base">
        <item name="colorPrimary">#ABD2C5</item>
        <item name="colorPrimaryContainer">#28423A</item>
        <item name="colorSecondary">#B2CAD5</item>
        <item name="colorSecondaryContainer">#293B43</item>
        <item name="colorTertiary">#C8CBA7</item>
        <item name="colorSurface">#111715</item>
        <item name="colorSurfaceVariant">#1A2420</item>
    </style>

    <style name="Theme.BaiZe.Sunset" parent="Theme.BaiZe.Base">
        <item name="colorPrimary">#E2C1AA</item>
        <item name="colorPrimaryContainer">#4A3529</item>
        <item name="colorSecondary">#D7C9A8</item>
        <item name="colorSecondaryContainer">#403A28</item>
        <item name="colorTertiary">#E0B8C1</item>
        <item name="colorSurface">#181411</item>
        <item name="colorSurfaceVariant">#25201C</item>
    </style>
</resources>
''', encoding="utf-8")

# Floating dock: lighter, shorter and better spaced with large custom fonts.
for old, new in [
    ("minimumHeight = dp(92)", "minimumHeight = dp(84)"),
    ("setPadding(dp(8), dp(8), dp(8), dp(8))", "setPadding(dp(7), dp(7), dp(7), dp(7))"),
    ("elevation = dp(24).toFloat()", "elevation = dp(16).toFloat()"),
    ("layoutParams = LayoutParams(0, dp(74), 1f)", "layoutParams = LayoutParams(0, dp(66), 1f)"),
    ("icon.layoutParams = LayoutParams(dp(24), dp(24))", "icon.layoutParams = LayoutParams(dp(22), dp(22))"),
    ("topMargin = dp(7)", "topMargin = dp(5)"),
    ("elevation = if (active) dp(13).toFloat() else 0f", "elevation = if (active) dp(8).toFloat() else 0f"),
    (".setDuration(280L)", ".setDuration(220L)"),
]:
    replace_once(dock, old, new, f"dock {old}")

replace_once(polish, '            text = "Alpha 13"\n', '            text = "Alpha 14"\n', "polish version")
replace_once(
    polish,
    '''            is DashboardActivity -> polishDashboard(activity)
            is CleanCenterActivity -> polishCleanCenter(activity)
            is CacheActivity, is ProfileActivity, is SmartScanActivity -> polishDetail(activity)
''',
    '''            is DashboardActivity -> polishDashboard(activity)
            is CleanCenterActivity -> polishCleanCenter(activity)
            is WhitelistActivity -> Unit
            is CacheActivity, is ProfileActivity, is SmartScanActivity -> polishDetail(activity)
''',
    "whitelist polish handling",
)

# Version and packaging.
replace_once(build_gradle, '        versionCode = 20020\n        versionName = "2.0.0-alpha13"\n', '        versionCode = 20030\n        versionName = "2.0.0-alpha14"\n', "app version")
replace_once(module_prop, 'version=v2.0.0-alpha13\nversionCode=20020\n', 'version=v2.0.0-alpha14\nversionCode=20030\n', "module version")
replace_once(
    module_prop,
    'description=白泽 v2 Alpha 13：重做系统明暗主题与低饱和玻璃 UI，新增真正写入清理引擎的应用白名单管理，并完善保护设置与诊断入口。\n',
    'description=白泽 v2 Alpha 14：Root 完整应用白名单、完整清理范围与保留策略、过期安装临时文件、安全明暗主题和精简 MIUI X 玻璃界面。\n',
    "module description",
)
replace_once(customize, 'ui_print "- 安装白泽 v2 Alpha 13"\n', 'ui_print "- 安装白泽 v2 Alpha 14"\n', "installer title")
replace_once(package_script, 'OUTPUT="$OUT/BaiZe-v2-Alpha13-Module.zip"\n', 'OUTPUT="$OUT/BaiZe-v2-Alpha14-Module.zip"\n', "package output")
replace_once(
    package_script,
    'echo "已生成液态玻璃 UI、一键清理、真实调度器、内置 App 与完整规则库的一体化模块：$OUTPUT"\n',
    'echo "已生成 Alpha 14 完整白名单、可配置清理引擎、明暗 MIUI X UI 与规则库一体化模块：$OUTPUT"\n',
    "package message",
)
