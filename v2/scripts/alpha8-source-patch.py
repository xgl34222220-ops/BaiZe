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


def patch_dashboard() -> None:
    print("== dashboard ==")
    path = "v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt"
    required(path, 'binding.versionText.text = "Alpha 6"', 'binding.versionText.text = "Alpha 8"')
    required(
        path,
        '''        binding.fragmentDaysSlider.addOnChangeListener { _, value, _ ->
            binding.fragmentDaysText.text = "碎片至少保留 ${value.toInt()} 天"
        }''',
        '''        binding.fragmentDaysSlider.addOnChangeListener { _, value, _ ->
            binding.fragmentDaysText.text = fragmentRetentionLabel(value.toInt())
        }''',
    )
    required(
        path,
        'json.optInt("fragment_days", 7).coerceIn(1, 30).toFloat()',
        'json.optInt("fragment_days", 7).coerceIn(0, 30).toFloat()',
    )
    required(
        path,
        'binding.fragmentDaysText.text = "碎片至少保留 ${binding.fragmentDaysSlider.value.toInt()} 天"',
        'binding.fragmentDaysText.text = fragmentRetentionLabel(binding.fragmentDaysSlider.value.toInt())',
    )
    required(
        path,
        'binding.lastFreedText.text = if (bytes > 0L) "最近释放 ${Formatter.formatFileSize(this, bytes)}" else "最近释放 --"',
        'binding.lastFreedText.text = if (bytes > 0L) Formatter.formatFileSize(this, bytes) else "--"',
    )
    required(
        path,
        '        preferences.edit().putString("last_report_text", summary).apply()',
        '''        val latestBytes = json.optJSONObject("latest")?.optLong("bytes", 0L) ?: 0L
        preferences.edit().apply {
            putString("last_report_text", summary)
            if (latestBytes > 0L) putLong("last_clean_bytes", latestBytes)
        }.apply()
        if (latestBytes > 0L) binding.lastFreedText.text = Formatter.formatFileSize(this, latestBytes)''',
    )
    required(
        path,
        '''            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
            val scheduler = json.optJSONObject("scheduler") ?: JSONObject()''',
        '''            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
            val latestBytes = json.optJSONObject("latest")?.optLong("bytes", 0L) ?: 0L
            if (latestBytes > 0L) {
                preferences.edit().putLong("last_clean_bytes", latestBytes).apply()
                binding.lastFreedText.text = Formatter.formatFileSize(this@DashboardActivity, latestBytes)
            }
            val scheduler = json.optJSONObject("scheduler") ?: JSONObject()''',
    )
    required(
        path,
        '    private fun flag(value: Boolean): Int = if (value) 1 else 0',
        '''    private fun fragmentRetentionLabel(days: Int): String =
        if (days <= 0) "碎片立即清理" else "碎片保留 $days 天"

    private fun flag(value: Boolean): Int = if (value) 1 else 0''',
    )


def patch_root_service() -> None:
    print("== root service ==")
    path = "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
    required(path, "module-auto-cleaner-v6+native-audit", "module-auto-cleaner-v8+native-audit")
    required(
        path,
        '''        val totals = readEnv(File(stateDir, "totals.env"))
        val latestReport = File(stateDir, "reports/latest.tsv")''',
        '''        val totals = readEnv(File(stateDir, "totals.env"))
        val latest = readEnv(File(stateDir, "latest.env"))
        val latestReport = File(stateDir, "reports/latest.tsv")''',
    )
    required(
        path,
        '.put("totals", totals)\n            .put("latestReport"',
        '.put("totals", totals)\n            .put("latest", latest)\n            .put("latestReport"',
    )
    required(
        path,
        '''        val totals = readEnv(File(stateDir, "totals.env"))
        val running = readEnv(File(stateDir, "running.env"))''',
        '''        val totals = readEnv(File(stateDir, "totals.env"))
        val latest = readEnv(File(stateDir, "latest.env"))
        val running = readEnv(File(stateDir, "running.env"))''',
    )
    required(
        path,
        '.put("totals", totals)\n            .put("running", running)',
        '.put("totals", totals)\n            .put("latest", latest)\n            .put("running", running)',
    )
    required(path, '"fragment_days" to 1..365', '"fragment_days" to 0..365')


def patch_cleaner() -> None:
    print("== cleaner ==")
    cleaner = Path("cleaner.sh")
    text = cleaner.read_text()

    old_assignment = 'FRAGMENT_DAYS=$(get_uint fragment_days 7 1 365)'
    new_assignment = '''FRAGMENT_DAYS=$(get_uint fragment_days 7 0 365)
if [ "$FRAGMENT_DAYS" -eq 0 ]; then
  FRAGMENT_POLICY="立即清理"
  FRAGMENT_MTIME_ARGS=""
else
  FRAGMENT_POLICY="保留 ${FRAGMENT_DAYS} 天"
  FRAGMENT_MTIME_ARGS="-mtime +$FRAGMENT_DAYS"
fi'''
    if old_assignment in text:
        text = text.replace(old_assignment, new_assignment, 1)
    elif new_assignment not in text:
        raise SystemExit("[missing] cleaner fragment day assignment")

    start = text.find("run_fragment_cleanup() {")
    end = text.find("\nrun_custom_rules() {", start)
    if start < 0 or end < 0:
        raise SystemExit("[missing] cleaner fragment function boundaries")
    fragment = text[start:end]
    fragment = fragment.replace('-mtime "+$FRAGMENT_DAYS"', '$FRAGMENT_MTIME_ARGS')
    fragment = fragment.replace('保留 ${FRAGMENT_DAYS} 天', '${FRAGMENT_POLICY}')
    if '$FRAGMENT_MTIME_ARGS' not in fragment or '${FRAGMENT_POLICY}' not in fragment:
        raise SystemExit("[failed] cleaner fragment policy migration")
    text = text[:start] + fragment + text[end:]
    cleaner.write_text(text)
    print("[patched] cleaner.sh: zero-day fragment policy")


def patch_versions() -> None:
    print("== versions ==")
    optional("v2/app/build.gradle.kts", "versionCode = 20007", "versionCode = 20008")
    optional("v2/app/build.gradle.kts", 'versionName = "2.0.0-alpha07"', 'versionName = "2.0.0-alpha08"')
    optional("v2/module/module.prop", "version=v2.0.0-alpha07", "version=v2.0.0-alpha08")
    optional("v2/module/module.prop", "versionCode=20007", "versionCode=20008")
    optional("v2/module/module.prop", "白泽 v2 Alpha 7", "白泽 v2 Alpha 8")
    optional("v2/module/customize.sh", "白泽 v2 Alpha 7", "白泽 v2 Alpha 8")
    optional("v2/module/customize.sh", "白泽 v2 Alpha 6", "白泽 v2 Alpha 8")
    optional("v2/module/service.sh", "module_version=2.0.0-alpha07", "module_version=2.0.0-alpha08")
    optional("v2/module/service.sh", "module_version=2.0.0-alpha06", "module_version=2.0.0-alpha08")
    optional("v2/scripts/package-module.sh", "BaiZe-v2-Alpha7-Module.zip", "BaiZe-v2-Alpha8-Module.zip")


if __name__ == "__main__":
    patch_dashboard()
    patch_root_service()
    patch_cleaner()
    patch_versions()
    print("Alpha 8 source migration complete")
