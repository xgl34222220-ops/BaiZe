#!/usr/bin/env python3
from pathlib import Path


def required(path: str, old: str, new: str, count: int = 1) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if new in text and old not in text:
        print(f"[already] {path}: {new[:80]}")
        return
    if old not in text:
        raise SystemExit(f"[missing] {path}: {old[:140]!r}")
    file.write_text(text.replace(old, new, count), encoding="utf-8")
    print(f"[patched] {path}: {old[:80]}")


def optional(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if old in text:
        file.write_text(text.replace(old, new), encoding="utf-8")
        print(f"[patched optional] {path}: {old[:80]}")
    else:
        print(f"[skip optional] {path}: {old[:80]}")


def patch_dashboard() -> None:
    path = "v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt"
    required(path, 'binding.versionText.text = "Alpha 10"', 'binding.versionText.text = "Alpha 11"')
    required(
        path,
        '''        setupNavigation()
        setupActions()
        setupSettings()
        setupThemePicker()
        updateStorage()''',
        '''        setupNavigation()
        setupActions()
        setupSettings()
        updateStorage()''',
    )
    required(
        path,
        '''        updateStorage()
        refreshSavedReport()
        refreshWhitelist()
        renderThemeSummary()
        if (profileService != null) {''',
        '''        updateStorage()
        refreshSavedReport()
        if (profileService != null) {''',
    )
    required(
        path,
        '''    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            runCatching {
                when (item.itemId) {
                    R.id.nav_home -> show(binding.homePage)
                    R.id.nav_plan -> show(binding.planPage)
                    R.id.nav_records -> show(binding.recordsPage)
                    R.id.nav_settings -> show(binding.settingsPage)
                    else -> return@setOnItemSelectedListener false
                }
                true
            }.getOrElse {
                show(binding.homePage)
                false
            }
        }
        binding.bottomNavigation.selectedItemId = R.id.nav_home
    }

    private fun show(page: View) {
        val pages = listOf(binding.homePage, binding.planPage, binding.recordsPage, binding.settingsPage)
        pages.forEach { candidate ->
            candidate.animate().cancel()
            candidate.visibility = if (candidate === page) View.VISIBLE else View.GONE
        }
        page.alpha = 0f
        page.translationY = dp(8).toFloat()
        page.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .start()
        page.post { page.scrollTo(0, 0) }
    }''',
        '''    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    show(binding.homePage)
                    true
                }
                R.id.nav_plan -> {
                    show(binding.planPage)
                    true
                }
                R.id.nav_records -> {
                    show(binding.recordsPage)
                    true
                }
                R.id.nav_settings -> {
                    // Settings owns a separate Activity and ViewBinding tree. It never exposes the
                    // dashboard's hidden-page state or recreates a running cleaning screen.
                    startActivity(Intent(this, SettingsActivity::class.java))
                    false
                }
                else -> false
            }
        }
        binding.bottomNavigation.selectedItemId = R.id.nav_home
    }

    private fun show(page: View) {
        val pages = listOf(binding.homePage, binding.planPage, binding.recordsPage)
        pages.forEach { candidate ->
            candidate.animate().cancel()
            candidate.visibility = if (candidate === page) View.VISIBLE else View.GONE
        }
        page.alpha = 0f
        page.translationY = dp(6).toFloat()
        page.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(190L)
            .start()
        page.post { page.scrollTo(0, 0) }
    }''',
    )
    required(
        path,
        '''        binding.cleanNowButton.setOnClickListener { runModuleTask("clean") }
        binding.scanOnlyButton.setOnClickListener { startActivity(Intent(this, SmartScanActivity::class.java)) }''',
        '''        binding.cleanNowButton.setOnClickListener { runModuleTask("clean") }''',
    )
    required(
        path,
        '''    private fun setupThemePicker() {
        renderThemeSummary()
        binding.themeButton.setOnClickListener { showThemeDialog() }
    }

    private fun renderThemeSummary() {
        val palette = ThemeManager.currentPalette(this)
        binding.themeSummaryText.text = buildString {
            append(palette.label).append(" · ").append(palette.description)
            if (palette.monet && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                append("（当前系统回退为白泽蓝）")
            }
        }
    }

    private fun showThemeDialog() {
        val current = ThemeManager.currentId(this)
        val labels = ThemeManager.palettes.map { palette ->
            if (palette.monet) {
                "${palette.label}\\n${palette.description}（Android 12+）"
            } else {
                "${palette.label}\\n${palette.description}"
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

''',
        '',
    )
    required(
        path,
        '''        binding.cleanNowButton.isEnabled = !running && profileService != null
        binding.scanOnlyButton.isEnabled = !running && profileService != null
        binding.stopTaskButton.visibility = if (running) View.VISIBLE else View.GONE''',
        '''        binding.cleanNowButton.isEnabled = !running && profileService != null
        binding.stopTaskButton.visibility = if (running) View.VISIBLE else View.GONE''',
    )
    required(
        path,
        '''        preferences.edit().apply {
            putString("last_report_text", summary)
            if (latestBytes > 0L) putLong("last_clean_bytes", latestBytes)
        }.apply()
        if (latestBytes > 0L) binding.lastFreedText.text = Formatter.formatFileSize(this, latestBytes)''',
        '''        preferences.edit()
            .putString("last_report_text", summary)
            .putLong("last_clean_bytes", latestBytes.coerceAtLeast(0L))
            .apply()
        binding.lastFreedText.text = Formatter.formatFileSize(this, latestBytes.coerceAtLeast(0L))''',
    )
    required(
        path,
        '''        val bytes = preferences.getLong("last_clean_bytes", 0L)
        binding.lastFreedText.text = if (bytes > 0L) Formatter.formatFileSize(this, bytes) else "--"''',
        '''        val bytes = preferences.getLong("last_clean_bytes", -1L)
        binding.lastFreedText.text = if (bytes >= 0L) Formatter.formatFileSize(this, bytes) else "暂无"''',
    )
    required(
        path,
        '''            val latestBytes = json.optJSONObject("latest")?.optLong("bytes", 0L) ?: 0L
            if (latestBytes > 0L) {
                preferences.edit().putLong("last_clean_bytes", latestBytes).apply()
                binding.lastFreedText.text = Formatter.formatFileSize(this@DashboardActivity, latestBytes)
            }''',
        '''            val latest = json.optJSONObject("latest")
            if (latest != null && latest.length() > 0) {
                val latestBytes = latest.optLong("bytes", 0L).coerceAtLeast(0L)
                preferences.edit().putLong("last_clean_bytes", latestBytes).apply()
                binding.lastFreedText.text = Formatter.formatFileSize(this@DashboardActivity, latestBytes)
            }''',
    )
    required(
        path,
        '''        binding.serviceStatusText.text = text
        binding.settingsStatusText.text = text
        binding.serviceDot.alpha = if (ready) 1f else 0.35f''',
        '''        binding.serviceStatusText.text = text
        binding.serviceDot.alpha = if (ready) 1f else 0.35f''',
    )
    optional(path, 'import androidx.appcompat.app.AlertDialog\n', '')


def patch_dashboard_layout() -> None:
    path = "v2/app/src/main/res/layout/activity_dashboard.xml"
    required(
        path,
        '''                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:orientation="horizontal">

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/cleanNowButton"
                        android:layout_width="0dp"
                        android:layout_height="62dp"
                        android:layout_marginEnd="6dp"
                        android:layout_weight="1.45"
                        android:text="立即清理"
                        android:textSize="16sp" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/scanOnlyButton"
                        style="@style/Widget.BaiZe.Button.Outlined"
                        android:layout_width="0dp"
                        android:layout_height="62dp"
                        android:layout_marginStart="6dp"
                        android:layout_weight="1"
                        android:text="仅扫描"
                        android:textSize="14sp" />
                </LinearLayout>''',
        '''                <com.google.android.material.button.MaterialButton
                    android:id="@+id/cleanNowButton"
                    android:layout_width="match_parent"
                    android:layout_height="64dp"
                    android:layout_marginTop="13dp"
                    android:text="立即智能清理"
                    android:textSize="17sp"
                    android:textStyle="bold" />''',
    )
    optional(path, 'android:text="--"\n                                android:textColor="?attr/colorPrimary"', 'android:text="暂无"\n                                android:textColor="?attr/colorPrimary"')
    required(path, 'android:layout_height="84dp"\n        android:layout_gravity="bottom"', 'android:layout_height="92dp"\n        android:layout_gravity="bottom"')
    required(path, 'android:layout_marginStart="20dp"\n        android:layout_marginEnd="20dp"\n        android:layout_marginBottom="22dp"', 'android:layout_marginStart="16dp"\n        android:layout_marginEnd="16dp"\n        android:layout_marginBottom="24dp"')


def patch_polish_and_versions() -> None:
    path = "v2/app/src/main/java/io/github/xgl34222220/baize/Alpha7UiPolish.kt"
    optional(path, 'Runtime visual pass for the Alpha 9 MIUI X redesign.', 'Runtime visual pass for the Alpha 11 MIUI X redesign.')
    optional(path, 'text = "Alpha 9"', 'text = "Alpha 11"')
    optional(path, 'is CacheActivity, is ProfileActivity, is SmartScanActivity -> polishDetail(activity)', 'is CacheActivity, is ProfileActivity, is SmartScanActivity, is SettingsActivity -> polishDetail(activity)')
    optional(path, 'activity.findViewById<MaterialButton>(R.id.scanOnlyButton)?.apply {\n            setTextColor(primary)\n            strokeColor = ColorStateList.valueOf(alpha(primary, 170))\n        }\n', '')

    optional("v2/app/build.gradle.kts", "versionCode = 20010", "versionCode = 20011")
    optional("v2/app/build.gradle.kts", 'versionName = "2.0.0-alpha10"', 'versionName = "2.0.0-alpha11"')
    optional("v2/module/module.prop", "version=v2.0.0-alpha10", "version=v2.0.0-alpha11")
    optional("v2/module/module.prop", "versionCode=20010", "versionCode=20011")
    optional("v2/module/module.prop", "白泽 v2 Alpha 10", "白泽 v2 Alpha 11")
    optional("v2/module/module.prop", "稳定设置页、精致 MIUI X 液态玻璃界面、同快照一键清理、真实后台定时与强化规则安全边界。", "独立稳定设置页、单一智能清理入口、更清晰的 MIUI X 液态玻璃层级、真实后台定时与强化规则保护。")
    optional("v2/module/customize.sh", "安装白泽 v2 Alpha 10", "安装白泽 v2 Alpha 11")
    optional("v2/module/service.sh", "module_version=2.0.0-alpha10", "module_version=2.0.0-alpha11")
    optional("v2/scripts/package-module.sh", "BaiZe-v2-Alpha10-Module.zip", "BaiZe-v2-Alpha11-Module.zip")
    optional("v2/scripts/package-module.sh", "白泽 v2 Alpha 10", "白泽 v2 Alpha 11")


if __name__ == "__main__":
    patch_dashboard()
    patch_dashboard_layout()
    patch_polish_and_versions()
    print("Alpha 11 source migration complete")
