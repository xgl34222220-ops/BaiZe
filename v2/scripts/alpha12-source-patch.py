from pathlib import Path

path = Path("app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    if old not in text:
        raise SystemExit(f"missing patch target: {label}")
    text = text.replace(old, new, 1)


def replace_between(start: str, end: str, replacement: str, label: str) -> None:
    global text
    start_index = text.find(start)
    if start_index < 0:
        raise SystemExit(f"missing patch start: {label}")
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise SystemExit(f"missing patch end: {label}")
    text = text[:start_index] + replacement + text[end_index:]


replace_once(
    """        setupSettings()\n        updateStorage()\n""",
    """        setupSettings()\n        setupThemePicker()\n        updateStorage()\n""",
    "theme setup call",
)

replace_once(
    """        updateStorage()\n        refreshSavedReport()\n        if (profileService != null) {\n""",
    """        updateStorage()\n        refreshSavedReport()\n        refreshWhitelist()\n        renderThemeSummary()\n        if (profileService != null) {\n""",
    "resume settings refresh",
)

replace_once(
    """                R.id.nav_settings -> {\n                    // Settings stays inside the already stable dashboard process. No new Activity,\n                    // no second RootService binding and no second glass lifecycle pass are created.\n                    showSettingsMenu()\n                    false\n                }\n""",
    """                R.id.nav_settings -> {\n                    // Keep settings in the existing DashboardActivity: no second Activity,\n                    // no duplicate RootService binding and no extra glass lifecycle.\n                    showSettingsMenu()\n                    true\n                }\n""",
    "settings navigation",
)

replace_once(
    """        val pages = listOf(binding.homePage, binding.planPage, binding.recordsPage)\n""",
    """        val pages = listOf(binding.homePage, binding.planPage, binding.recordsPage, binding.settingsPage)\n""",
    "settings page host",
)

replace_between(
    "    private fun showSettingsMenu() {\n",
    "    private fun showThemeDialog() {\n",
    """    private fun showSettingsMenu() {\n        runCatching {\n            refreshWhitelist()\n            renderThemeSummary()\n            show(binding.settingsPage)\n        }.onFailure { error ->\n            Toast.makeText(\n                this,\n                \"设置页打开失败：${error.message ?: error.javaClass.simpleName}\",\n                Toast.LENGTH_LONG\n            ).show()\n        }\n    }\n\n    private fun setupThemePicker() {\n        renderThemeSummary()\n        binding.themeButton.setOnClickListener { showThemeDialog() }\n    }\n\n    private fun renderThemeSummary() {\n        val palette = ThemeManager.currentPalette(this)\n        binding.themeSummaryText.text = buildString {\n            append(palette.label).append(\" · \").append(palette.description)\n            if (palette.monet && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {\n                append(\"（当前系统回退为白泽蓝）\")\n            }\n        }\n    }\n\n""",
    "replace fragile settings dialog",
)

replace_between(
    "    private fun refreshWhitelist() {\n",
    "    private fun connectService() {\n",
    """    private fun refreshWhitelist() {\n        val packageCount = readStringSetCompat(\"package_whitelist\").size\n        val pathCount = readStringSetCompat(\"path_whitelist\").size\n        binding.whitelistText.text = if (pathCount > 0) {\n            \"白名单：$packageCount 个应用 · $pathCount 条路径\"\n        } else {\n            \"白名单：$packageCount 个应用\"\n        }\n    }\n\n    private fun readStringSetCompat(key: String): Set<String> {\n        runCatching {\n            return preferences.getStringSet(key, emptySet()).orEmpty().toSet()\n        }\n\n        val migrated = when (val legacy = preferences.all[key]) {\n            is String -> legacy\n                .trim()\n                .removePrefix(\"[\")\n                .removeSuffix(\"]\")\n                .split('\\n', ',', ';')\n                .asSequence()\n                .map { it.trim().trim('\\\"') }\n                .filter { it.isNotBlank() }\n                .toSet()\n            is Collection<*> -> legacy.filterIsInstance<String>().filter { it.isNotBlank() }.toSet()\n            else -> emptySet()\n        }\n\n        preferences.edit().remove(key).apply()\n        if (migrated.isNotEmpty()) preferences.edit().putStringSet(key, migrated).apply()\n        return migrated\n    }\n\n""",
    "safe whitelist migration",
)

replace_once(
    """    private fun renderServiceState(text: String, ready: Boolean) {\n        binding.serviceStatusText.text = text\n        binding.serviceDot.alpha = if (ready) 1f else 0.35f\n    }\n""",
    """    private fun renderServiceState(text: String, ready: Boolean) {\n        binding.serviceStatusText.text = text\n        binding.settingsStatusText.text = text\n        binding.serviceDot.alpha = if (ready) 1f else 0.35f\n    }\n""",
    "settings service state",
)

path.write_text(text, encoding="utf-8")
