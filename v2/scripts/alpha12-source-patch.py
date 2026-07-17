from pathlib import Path


def replace(path: str, old: str, new: str, count: int = -1) -> None:
    file = Path(path)
    text = file.read_text()
    if new in text and old not in text:
        print(f"[already] {path}: {new[:80]}")
        return
    if old not in text:
        raise SystemExit(f"[missing] {path}: {old[:140]!r}")
    file.write_text(text.replace(old, new, count))
    print(f"[patched] {path}: {old[:80]}")


def optional(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if old in text:
        file.write_text(text.replace(old, new))
        print(f"[patched optional] {path}: {old[:80]}")
    else:
        print(f"[skip optional] {path}: {old[:80]}")


def patch_dashboard() -> None:
    path = "v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt"
    replace(path, "import android.view.View\n", "import android.view.View\nimport android.widget.Toast\n")
    replace(path, 'binding.versionText.text = "Alpha 11"', 'binding.versionText.text = "Alpha 12"')
    replace(
        path,
        '''                R.id.nav_settings -> {
                    // Settings owns a separate Activity and ViewBinding tree. It never exposes the
                    // dashboard's hidden-page state or recreates a running cleaning screen.
                    startActivity(Intent(this, SettingsActivity::class.java))
                    false
                }''',
        '''                R.id.nav_settings -> {
                    // Settings stays inside the already stable dashboard process. No new Activity,
                    // no second RootService binding and no second glass lifecycle pass are created.
                    showSettingsMenu()
                    false
                }''',
    )
    marker = '''    private fun openProfile(profile: String) {
        startActivity(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_PROFILE, profile))
    }

'''
    insert = marker + '''    private fun showSettingsMenu() {
        val palette = ThemeManager.currentPalette(this)
        val notification = binding.notificationSwitch.isChecked
        val maxFileMb = binding.largeFileSlider.value.toInt()
        val packageCount = preferences.getStringSet("package_whitelist", emptySet()).orEmpty().size
        val pathCount = preferences.getStringSet("path_whitelist", emptySet()).orEmpty().size
        val serviceState = if (profileService != null) "已连接" else "未连接"
        val entries = arrayOf(
            "主题与取色\n${palette.label} · ${palette.description}",
            "任务完成通知\n${if (notification) "已开启" else "已关闭"}",
            "单文件保护上限\n${maxFileMb} MB",
            "白名单保护\n$packageCount 个应用 · $pathCount 条路径",
            "Root 清理服务\n$serviceState",
            "崩溃诊断\n${CrashRecorder.summary(this)}"
        )
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(entries) { _, which ->
                when (which) {
                    0 -> showThemeDialog()
                    1 -> {
                        val enabled = !notification
                        binding.notificationSwitch.isChecked = enabled
                        saveSettingsPatch(notification = enabled)
                    }
                    2 -> showLargeFileDialog(maxFileMb)
                    3 -> showWhitelistDialog(packageCount, pathCount)
                    4 -> reconnectProfileService()
                    5 -> showCrashDialog()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showThemeDialog() {
        val current = ThemeManager.currentId(this)
        val labels = ThemeManager.palettes.map { palette ->
            if (palette.monet) {
                "${palette.label}\n${palette.description}（Android 12+）"
            } else {
                "${palette.label}\n${palette.description}"
            }
        }.toTypedArray()
        val checked = ThemeManager.palettes.indexOfFirst { it.id == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("主题与取色")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ThemeManager.setPalette(this, ThemeManager.palettes[which].id)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLargeFileDialog(current: Int) {
        val values = intArrayOf(64, 128, 256, 512, 1024, 2048)
        val labels = values.map { "$it MB" }.toTypedArray()
        val checked = values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 2
        AlertDialog.Builder(this)
            .setTitle("单文件保护上限")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val value = values[which]
                binding.largeFileSlider.value = value.toFloat()
                binding.largeFileText.text = "单文件上限 $value MB"
                saveSettingsPatch(maxFileMb = value)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showWhitelistDialog(packageCount: Int, pathCount: Int) {
        AlertDialog.Builder(this)
            .setTitle("白名单保护")
            .setMessage("当前保护 $packageCount 个应用、$pathCount 条路径。清空后只会重新参与后续扫描，不会立即执行清理。")
            .setNegativeButton("保留", null)
            .setPositiveButton("清空白名单") { _, _ ->
                preferences.edit()
                    .remove("package_whitelist")
                    .remove("path_whitelist")
                    .apply()
                Toast.makeText(this, "白名单已清空", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showCrashDialog() {
        val report = CrashRecorder.read(this)
        AlertDialog.Builder(this)
            .setTitle("崩溃诊断")
            .setMessage(report ?: "暂无 App 崩溃记录")
            .setNegativeButton("关闭", null)
            .setPositiveButton("清除记录") { _, _ -> CrashRecorder.clear(this) }
            .show()
    }

    private fun reconnectProfileService() {
        if (serviceBound) runCatching { RootService.unbind(connection) }
        profileService = null
        serviceBound = false
        connectService()
        Toast.makeText(this, "正在重新连接 Root 服务", Toast.LENGTH_SHORT).show()
    }

    private fun saveSettingsPatch(notification: Boolean? = null, maxFileMb: Int? = null) {
        val rootService = profileService ?: run {
            Toast.makeText(this, "Root 服务尚未连接", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = JSONObject()
        notification?.let { payload.put("notify_on_complete", if (it) 1 else 0) }
        maxFileMb?.let { payload.put("max_file_mb", it.coerceIn(16, 2048)) }
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { rootService.saveSchedulerConfig(payload.toString()) }
            }
            val message = result.fold(
                onSuccess = {
                    val json = runCatching { JSONObject(it) }.getOrDefault(JSONObject())
                    if (json.optBoolean("success")) "设置已保存" else "保存失败：${json.optString("error", "未知错误")}"
                },
                onFailure = { "保存失败：${it.message ?: it.javaClass.simpleName}" }
            )
            Toast.makeText(this@DashboardActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

'''
    replace(path, marker, insert)


def patch_application() -> None:
    path = "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeApplication.kt"
    replace(
        path,
        '''        super.onCreate()
        ThemeManager.install(this)''',
        '''        super.onCreate()
        CrashRecorder.install(this)
        ThemeManager.install(this)''',
    )


def patch_manifest_and_polish() -> None:
    manifest = "v2/app/src/main/AndroidManifest.xml"
    replace(manifest, '        <activity android:name=".SettingsActivity" android:exported="false" />\n', "")
    polish = "v2/app/src/main/java/io/github/xgl34222220/baize/Alpha7UiPolish.kt"
    replace(
        polish,
        'is CacheActivity, is ProfileActivity, is SmartScanActivity, is SettingsActivity -> polishDetail(activity)',
        'is CacheActivity, is ProfileActivity, is SmartScanActivity -> polishDetail(activity)',
    )
    optional(polish, 'text = "Alpha 10"', 'text = "Alpha 12"')


def patch_versions() -> None:
    replace("v2/app/build.gradle.kts", "versionCode = 20011", "versionCode = 20012")
    replace("v2/app/build.gradle.kts", 'versionName = "2.0.0-alpha11"', 'versionName = "2.0.0-alpha12"')
    replace("v2/module/module.prop", "version=v2.0.0-alpha11", "version=v2.0.0-alpha12")
    replace("v2/module/module.prop", "versionCode=20011", "versionCode=20012")
    optional("v2/module/module.prop", "白泽 v2 Alpha 11", "白泽 v2 Alpha 12")
    optional("v2/module/customize.sh", "白泽 v2 Alpha 11", "白泽 v2 Alpha 12")
    optional("v2/module/service.sh", "module_version=2.0.0-alpha11", "module_version=2.0.0-alpha12")
    optional("v2/scripts/package-module.sh", "BaiZe-v2-Alpha11-Module.zip", "BaiZe-v2-Alpha12-Module.zip")


if __name__ == "__main__":
    patch_dashboard()
    patch_application()
    patch_manifest_and_polish()
    patch_versions()
    print("Alpha 12 settings migration complete")
