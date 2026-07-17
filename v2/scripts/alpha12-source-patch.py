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


dashboard = Path("v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt")
polish = Path("v2/app/src/main/java/io/github/xgl34222220/baize/Alpha7UiPolish.kt")
build_gradle = Path("v2/app/build.gradle.kts")
module_prop = Path("v2/module/module.prop")
customize = Path("v2/module/customize.sh")

replace_once(
    dashboard,
    '        binding.versionText.text = "Alpha 12"\n',
    '        binding.versionText.text = "Alpha 12.1"\n',
    "dashboard version",
)

replace_between(
    dashboard,
    "    private fun showSettingsMenu() {\n",
    "    private fun setupThemePicker() {\n",
    '''    private fun showSettingsMenu() {
        // Do not animate or construct a dialog here. Some OEM ROMs validate hidden Material sliders
        // only when the page becomes visible; normalize every discrete value first, then switch pages
        // synchronously so a bad legacy config cannot crash the render pass.
        runCatching { normalizeDiscreteSliders() }
        runCatching { refreshWhitelist() }
        runCatching { renderThemeSummary() }

        val pages = listOf(binding.homePage, binding.planPage, binding.recordsPage, binding.settingsPage)
        pages.forEach { candidate ->
            candidate.animate().cancel()
            candidate.alpha = 1f
            candidate.translationY = 0f
            candidate.visibility = if (candidate === binding.settingsPage) View.VISIBLE else View.GONE
        }
        binding.settingsPage.scrollTo(0, 0)
    }

    private fun normalizeDiscreteSliders() {
        binding.minBatterySlider.value = snapToStep(binding.minBatterySlider.value.toInt(), 0, 100, 5)
        binding.largeFileSlider.value = snapToStep(binding.largeFileSlider.value.toInt(), 16, 2048, 16)
    }

''',
    "safe settings page switch",
)

replace_once(
    dashboard,
    '''            binding.minBatterySlider.value = json.optInt("min_battery", 25).coerceIn(0, 100).toFloat()
            binding.notificationSwitch.isChecked = json.optInt("notify_on_complete", 1) == 1
            binding.largeFileSlider.value = json.optInt("max_file_mb", 256).coerceIn(16, 2048).toFloat()
''',
    '''            binding.minBatterySlider.value = snapToStep(json.optInt("min_battery", 25), 0, 100, 5)
            binding.notificationSwitch.isChecked = json.optInt("notify_on_complete", 1) == 1
            binding.largeFileSlider.value = snapToStep(json.optInt("max_file_mb", 256), 16, 2048, 16)
''',
    "normalize loaded slider values",
)

replace_once(
    dashboard,
    '''    private fun fragmentRetentionLabel(days: Int): String =
        if (days <= 0) "碎片立即清理" else "碎片保留 $days 天"

    private fun dp(value: Int): Int =
''',
    '''    private fun fragmentRetentionLabel(days: Int): String =
        if (days <= 0) "碎片立即清理" else "碎片保留 $days 天"

    private fun snapToStep(value: Int, minimum: Int, maximum: Int, step: Int): Float {
        val clamped = value.coerceIn(minimum, maximum)
        val offset = clamped - minimum
        val snapped = minimum + ((offset + step / 2) / step) * step
        return snapped.coerceIn(minimum, maximum).toFloat()
    }

    private fun dp(value: Int): Int =
''',
    "slider snap helper",
)

replace_once(
    polish,
    '            text = "Alpha 12"\n',
    '            text = "Alpha 12.1"\n',
    "polish version",
)

replace_once(
    polish,
    '''    private fun applyGlassTree(view: View, activity: Activity) {
        if (view is MaterialCardView) {
''',
    '''    private fun applyGlassTree(view: View, activity: Activity) {
        // Keep the settings subtree on stock Material rendering for this hotfix. A few OEM GPU and
        // Material combinations crash while revealing hidden sliders inside custom clipped drawables.
        if (view.id == R.id.settingsPage) return
        if (view is MaterialCardView) {
''',
    "settings safe rendering",
)

replace_once(
    build_gradle,
    '''        versionCode = 20012
        versionName = "2.0.0-alpha12"
''',
    '''        versionCode = 20013
        versionName = "2.0.0-alpha12.1"
''',
    "app version bump",
)

replace_once(
    module_prop,
    '''version=v2.0.0-alpha12
versionCode=20012
''',
    '''version=v2.0.0-alpha12.1
versionCode=20013
''',
    "module version bump",
)

replace_once(
    module_prop,
    "description=白泽 v2 Alpha 12：进程内稳定设置菜单、单一智能清理入口、更清晰的 MIUI X 液态玻璃层级、真实后台定时与强化规则保护。\n",
    "description=白泽 v2 Alpha 12.1：设置页安全渲染热修复、离散参数兼容迁移、单一智能清理入口、真实后台定时与强化规则保护。\n",
    "module description",
)

replace_once(
    customize,
    'ui_print "- 安装白泽 v2 Alpha 12"\n',
    'ui_print "- 安装白泽 v2 Alpha 12.1 设置热修复版"\n',
    "installer title",
)
