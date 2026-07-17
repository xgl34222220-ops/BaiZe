from pathlib import Path


def required(path: str, old: str, new: str, count: int = -1) -> None:
    file = Path(path)
    text = file.read_text()
    if new in text and old not in text:
        print(f"[already] {path}: {new[:72]}")
        return
    if old not in text:
        raise SystemExit(f"[missing] {path}: {old[:120]!r}")
    file.write_text(text.replace(old, new, count))
    print(f"[patched] {path}: {old[:72]}")


def optional(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if old in text:
        file.write_text(text.replace(old, new))
        print(f"[patched optional] {path}: {old[:72]}")
    else:
        print(f"[skip optional] {path}: {old[:72]}")


def patch_dashboard_layout() -> None:
    path = "v2/app/src/main/res/layout/activity_dashboard.xml"
    title = '<TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="设置" android:textAppearance="@style/TextAppearance.BaiZe.PageTitle" />'
    block = '''<TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="设置" android:textAppearance="@style/TextAppearance.BaiZe.PageTitle" />

                <com.google.android.material.card.MaterialCardView
                    style="@style/Widget.BaiZe.GlassCard"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="18dp"
                    android:tag="glass:card">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="17dp">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="主题与取色"
                            android:textColor="?attr/colorOnSurface"
                            android:textSize="15sp"
                            android:textStyle="bold" />

                        <TextView
                            android:id="@+id/themeSummaryText"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="5dp"
                            android:text="正在读取当前主题"
                            android:textColor="?attr/colorOnSurfaceVariant"
                            android:textSize="11sp" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/themeButton"
                            style="@style/Widget.BaiZe.Button.Outlined"
                            android:layout_width="match_parent"
                            android:layout_height="50dp"
                            android:layout_marginTop="12dp"
                            android:text="切换主题" />
                    </LinearLayout>
                </com.google.android.material.card.MaterialCardView>'''
    required(path, title, block, 1)

    # Key semantic surfaces use purpose-specific glass variants instead of identical flat cards.
    optional(path, 'android:id="@+id/freeSpaceText"', 'android:id="@+id/freeSpaceText"')


def patch_dashboard_activity() -> None:
    path = "v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt"
    required(path, 'import androidx.appcompat.app.AppCompatActivity', 'import androidx.appcompat.app.AlertDialog\nimport androidx.appcompat.app.AppCompatActivity', 1)
    required(
        path,
        '''        setupNavigation()
        setupActions()
        setupSettings()
        updateStorage()''',
        '''        setupNavigation()
        setupActions()
        setupSettings()
        setupThemePicker()
        updateStorage()''',
        1,
    )
    required(
        path,
        '''        refreshSavedReport()
        refreshWhitelist()
        if (profileService != null) {''',
        '''        refreshSavedReport()
        refreshWhitelist()
        renderThemeSummary()
        if (profileService != null) {''',
        1,
    )
    required(
        path,
        '''    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> show(binding.homePage)
                R.id.nav_plan -> show(binding.planPage)
                R.id.nav_records -> show(binding.recordsPage)
                R.id.nav_settings -> show(binding.settingsPage)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        binding.bottomNavigation.selectedItemId = R.id.nav_home
    }

    private fun show(page: View) {
        val pages = listOf(binding.homePage, binding.planPage, binding.recordsPage, binding.settingsPage)
        pages.forEach { it.visibility = if (it === page) View.VISIBLE else View.GONE }
        page.scrollTo(0, 0)
    }''',
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
        1,
    )

    anchor = '''    private fun setupSettings() {
'''
    theme_methods = '''    private fun setupThemePicker() {
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

    private fun setupSettings() {
'''
    required(path, anchor, theme_methods, 1)
    required(
        path,
        '''    private fun flag(value: Boolean): Int = if (value) 1 else 0''',
        '''    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun flag(value: Boolean): Int = if (value) 1 else 0''',
        1,
    )
    optional(path, 'binding.versionText.text = "Alpha 9"', 'binding.versionText.text = "Alpha 10"')


def patch_engine() -> None:
    path = "v2/app/src/main/java/io/github/xgl34222220/baize/root/NativeProfileEngine.kt"
    required(
        path,
        '''    private fun rulesDirectory(): File? = listOf(
        File("/data/adb/modules/baize_v2/config"),
        File("/data/adb/modules/safesweep/config")
    ).firstOrNull { it.isDirectory }''',
        '''    private fun rulesDirectory(): File? =
        File("/data/adb/modules/baize_v2/config").takeIf { it.isDirectory }''',
        1,
    )
    required(
        path,
        '''        val raw = rawRule.substringBefore('|').substringBefore('#').trim()
        if (!raw.startsWith("/") || raw.length > 4096) return emptyList()''',
        '''        val raw = rawRule.substringBefore('|').substringBefore('#').trim()
        if (!safeRuleSyntax(raw)) return emptyList()''',
        1,
    )
    required(
        path,
        '''    private fun glob(segment: String): Regex {''',
        '''    private fun safeRuleSyntax(raw: String): Boolean {
        if (!raw.startsWith("/") || raw.length !in 2..4096) return false
        if (raw.contains('\u0000') || raw.contains("\\n") || raw.contains("\\r")) return false
        if (raw.split('/').any { it == ".." }) return false
        if (raw.startsWith("/data/adb") || raw.startsWith("/metadata") || raw.startsWith("/proc") || raw.startsWith("/sys") || raw.startsWith("/dev")) return false
        val segments = raw.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        // Reject rules that wildcard an entire top-level filesystem tree.
        if (segments.take(2).any { it == "*" || it == "**" || it == "?" }) return false
        return true
    }

    private fun glob(segment: String): Regex {''',
        1,
    )
    required(
        path,
        '''        "fragments" -> target.isFile && target.lastModified() <= System.currentTimeMillis() - options.fragmentDays * 86_400_000L''',
        '''        "fragments" -> target.isFile &&
            target.lastModified() <= System.currentTimeMillis() - options.fragmentDays * 86_400_000L &&
            fragmentNameMatches(target.name)''',
        1,
    )
    required(
        path,
        '''    private fun placeholder(name: String): Boolean {''',
        '''    private fun fragmentNameMatches(name: String): Boolean {
        val value = name.lowercase()
        return value.endsWith(".tmp") || value.endsWith(".temp") || value.endsWith(".part") ||
            value.endsWith(".partial") || value.endsWith(".download") || value.endsWith(".crdownload") ||
            Regex(".*\\.log\\.[0-9]+$").matches(value) || value.endsWith(".old") || value.endsWith(".bak~") ||
            value.contains("tombstone") || value.contains("minidump") || value.contains("heapdump") ||
            value.contains("crash") || value.contains("trace") || value.contains("dump")
    }

    private fun placeholder(name: String): Boolean {''',
        1,
    )
    optional(path, 'Persistent native scanner for every non-cache cleaning profile.', 'Persistent native scanner for every non-cache cleaning profile with Alpha 10 rule hardening.')


def patch_versions_and_workflow() -> None:
    optional("v2/app/build.gradle.kts", "versionCode = 20009", "versionCode = 20010")
    optional("v2/app/build.gradle.kts", 'versionName = "2.0.0-alpha09"', 'versionName = "2.0.0-alpha10"')
    optional("v2/module/module.prop", "version=v2.0.0-alpha09", "version=v2.0.0-alpha10")
    optional("v2/module/module.prop", "versionCode=20009", "versionCode=20010")
    optional("v2/module/module.prop", "白泽 v2 Alpha 9", "白泽 v2 Alpha 10")
    optional("v2/module/module.prop", "精致液态玻璃原生界面、清晰高对比底栏、扫描后自动选择安全项并支持一键清理，保留真实后台定时与完整规则库。", "稳定设置页、精致 MIUI X 液态玻璃界面、同快照一键清理、真实后台定时与强化规则安全边界。")
    optional("v2/module/service.sh", "module_version=2.0.0-alpha09", "module_version=2.0.0-alpha10")
    optional("v2/scripts/package-module.sh", "BaiZe-v2-Alpha9-Module.zip", "BaiZe-v2-Alpha10-Module.zip")


if __name__ == "__main__":
    patch_dashboard_layout()
    patch_dashboard_activity()
    patch_engine()
    patch_versions_and_workflow()
    print("Alpha 10 source migration complete")
