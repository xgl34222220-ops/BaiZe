from pathlib import Path
import re


def replace(path: str, old: str, new: str, count: int = -1) -> None:
    file = Path(path)
    text = file.read_text()
    if old not in text:
        raise SystemExit(f"missing pattern in {path}: {old[:120]!r}")
    file.write_text(text.replace(old, new, count))


def patch_dashboard() -> None:
    path = "v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt"
    replace(path, 'binding.versionText.text = "Alpha 6"', 'binding.versionText.text = "Alpha 8"')
    replace(
        path,
        '''        binding.fragmentDaysSlider.addOnChangeListener { _, value, _ ->
            binding.fragmentDaysText.text = "碎片至少保留 ${value.toInt()} 天"
        }''',
        '''        binding.fragmentDaysSlider.addOnChangeListener { _, value, _ ->
            binding.fragmentDaysText.text = fragmentRetentionLabel(value.toInt())
        }''',
    )
    replace(
        path,
        'json.optInt("fragment_days", 7).coerceIn(1, 30).toFloat()',
        'json.optInt("fragment_days", 7).coerceIn(0, 30).toFloat()',
    )
    replace(
        path,
        'binding.fragmentDaysText.text = "碎片至少保留 ${binding.fragmentDaysSlider.value.toInt()} 天"',
        'binding.fragmentDaysText.text = fragmentRetentionLabel(binding.fragmentDaysSlider.value.toInt())',
    )
    replace(
        path,
        'binding.lastFreedText.text = if (bytes > 0L) "最近释放 ${Formatter.formatFileSize(this, bytes)}" else "最近释放 --"',
        'binding.lastFreedText.text = if (bytes > 0L) Formatter.formatFileSize(this, bytes) else "--"',
    )
    replace(
        path,
        '        preferences.edit().putString("last_report_text", summary).apply()',
        '''        val latestBytes = json.optJSONObject("latest")?.optLong("bytes", 0L) ?: 0L
        preferences.edit().apply {
            putString("last_report_text", summary)
            if (latestBytes > 0L) putLong("last_clean_bytes", latestBytes)
        }.apply()
        if (latestBytes > 0L) binding.lastFreedText.text = Formatter.formatFileSize(this, latestBytes)''',
    )
    replace(
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
    replace(
        path,
        '    private fun flag(value: Boolean): Int = if (value) 1 else 0',
        '''    private fun fragmentRetentionLabel(days: Int): String =
        if (days <= 0) "碎片立即清理" else "碎片保留 $days 天"

    private fun flag(value: Boolean): Int = if (value) 1 else 0''',
    )


def patch_root_service() -> None:
    path = "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
    replace(path, "module-auto-cleaner-v6+native-audit", "module-auto-cleaner-v8+native-audit")
    replace(
        path,
        '''        val totals = readEnv(File(stateDir, "totals.env"))
        val latestReport = File(stateDir, "reports/latest.tsv")''',
        '''        val totals = readEnv(File(stateDir, "totals.env"))
        val latest = readEnv(File(stateDir, "latest.env"))
        val latestReport = File(stateDir, "reports/latest.tsv")''',
    )
    replace(
        path,
        '.put("totals", totals)\n            .put("latestReport"',
        '.put("totals", totals)\n            .put("latest", latest)\n            .put("latestReport"',
    )
    replace(
        path,
        '''        val totals = readEnv(File(stateDir, "totals.env"))
        val running = readEnv(File(stateDir, "running.env"))''',
        '''        val totals = readEnv(File(stateDir, "totals.env"))
        val latest = readEnv(File(stateDir, "latest.env"))
        val running = readEnv(File(stateDir, "running.env"))''',
    )
    replace(
        path,
        '.put("totals", totals)\n            .put("running", running)',
        '.put("totals", totals)\n            .put("latest", latest)\n            .put("running", running)',
    )
    replace(path, '"fragment_days" to 1..365', '"fragment_days" to 0..365')


def patch_cleaner() -> None:
    cleaner = Path("cleaner.sh")
    text = cleaner.read_text()
    text = text.replace('保留 ${FRAGMENT_DAYS} 天', '${FRAGMENT_POLICY}')
    old_days = 'FRAGMENT_DAYS=$(get_uint fragment_days 7 1 365)'
    if old_days not in text:
        raise SystemExit("fragment day range not found")
    text = text.replace(
        old_days,
        'FRAGMENT_DAYS=$(get_uint fragment_days 7 0 365)\n'
        'if [ "$FRAGMENT_DAYS" -eq 0 ]; then FRAGMENT_POLICY="立即清理"; '
        'else FRAGMENT_POLICY="保留 ${FRAGMENT_DAYS} 天"; fi',
    )

    public_pattern = re.compile(
        r'''    # 非媒体公共区域：日志、崩溃转储与临时文件，至少保留指定天数。\n'''
        r'''    find "\$userdir".*?      -print0 2>/dev/null >>"\$list"\n\n'''
        r'''    # 下载目录只匹配明确的中断下载后缀，避免把普通日志或用户临时文档误删。''',
        re.S,
    )
    public_replacement = '''    # 非媒体公共区域：日志、崩溃转储与临时文件。0 天表示不增加时间门槛。
    if [ "$FRAGMENT_DAYS" -eq 0 ]; then
      find "$userdir" -mindepth 1 -maxdepth 4 \\
        \( -path "$userdir/Android" -o -path "$userdir/DCIM" -o -path "$userdir/Pictures" \\
           -o -path "$userdir/Movies" -o -path "$userdir/Music" -o -path "$userdir/Documents" \\
           -o -path "$userdir/Download" -o -path "$userdir/Podcasts" -o -path "$userdir/Audiobooks" \\
           -o -path "$userdir/Recordings" -o -path "$userdir/Fonts" -o -path "$userdir/Ringtones" \\
           -o -path "$userdir/Alarms" -o -path "$userdir/Notifications" \) -prune -o \\
        -type f -size "-${MAX_FILE_BYTES}c" \\
        \( -iname '*.tmp' -o -iname '*.temp' -o -iname '*.tmf' \\
           -o -iname '*.log' -o -iname '*.xlog' -o -iname '*.tlog' -o -iname '*.ulog' -o -iname '*.plog' \\
           -o -iname '*.hprof' -o -iname '*.dmp' -o -iname '*.dump' -o -iname '*.trace' \\
           -o -iname '*.traces' -o -iname '*.stacktrace' -o -iname 'hs_err_pid*.log' \) \\
        -print0 2>/dev/null >>"$list"
    else
      find "$userdir" -mindepth 1 -maxdepth 4 \\
        \( -path "$userdir/Android" -o -path "$userdir/DCIM" -o -path "$userdir/Pictures" \\
           -o -path "$userdir/Movies" -o -path "$userdir/Music" -o -path "$userdir/Documents" \\
           -o -path "$userdir/Download" -o -path "$userdir/Podcasts" -o -path "$userdir/Audiobooks" \\
           -o -path "$userdir/Recordings" -o -path "$userdir/Fonts" -o -path "$userdir/Ringtones" \\
           -o -path "$userdir/Alarms" -o -path "$userdir/Notifications" \) -prune -o \\
        -type f -size "-${MAX_FILE_BYTES}c" -mtime "+$FRAGMENT_DAYS" \\
        \( -iname '*.tmp' -o -iname '*.temp' -o -iname '*.tmf' \\
           -o -iname '*.log' -o -iname '*.xlog' -o -iname '*.tlog' -o -iname '*.ulog' -o -iname '*.plog' \\
           -o -iname '*.hprof' -o -iname '*.dmp' -o -iname '*.dump' -o -iname '*.trace' \\
           -o -iname '*.traces' -o -iname '*.stacktrace' -o -iname 'hs_err_pid*.log' \) \\
        -print0 2>/dev/null >>"$list"
    fi

    # 下载目录只匹配明确的中断下载后缀，避免把普通日志或用户临时文档误删。'''
    text, count = public_pattern.subn(public_replacement, text, count=1)
    if count != 1:
        raise SystemExit("fragment public-area find block not found")

    download_pattern = re.compile(
        r'''    # 下载目录只匹配明确的中断下载后缀，避免把普通日志或用户临时文档误删。\n'''
        r'''    if \[ -d "\$userdir/Download" \]; then\n'''
        r'''      find "\$userdir/Download".*?    fi\n''',
        re.S,
    )
    download_replacement = '''    # 下载目录只匹配明确的中断下载后缀，避免把普通日志或用户临时文档误删。
    if [ -d "$userdir/Download" ]; then
      if [ "$FRAGMENT_DAYS" -eq 0 ]; then
        find "$userdir/Download" -mindepth 1 -maxdepth 4 -type f \\
          -size "-${MAX_FILE_BYTES}c" \\
          \( -iname '*.part' -o -iname '*.partial' -o -iname '*.crdownload' \\
             -o -iname '*.filepart' -o -iname '*.download' -o -iname '*.opdownload' \) \\
          -print0 2>/dev/null >>"$list"
      else
        find "$userdir/Download" -mindepth 1 -maxdepth 4 -type f \\
          -size "-${MAX_FILE_BYTES}c" -mtime "+$FRAGMENT_DAYS" \\
          \( -iname '*.part' -o -iname '*.partial' -o -iname '*.crdownload' \\
             -o -iname '*.filepart' -o -iname '*.download' -o -iname '*.opdownload' \) \\
          -print0 2>/dev/null >>"$list"
      fi
    fi
'''
    text, count = download_pattern.subn(download_replacement, text, count=1)
    if count != 1:
        raise SystemExit("fragment download find block not found")
    cleaner.write_text(text)


def patch_versions() -> None:
    replace("v2/app/build.gradle.kts", "versionCode = 20007", "versionCode = 20008")
    replace("v2/app/build.gradle.kts", 'versionName = "2.0.0-alpha07"', 'versionName = "2.0.0-alpha08"')
    replace("v2/module/module.prop", "version=v2.0.0-alpha07", "version=v2.0.0-alpha08")
    replace("v2/module/module.prop", "versionCode=20007", "versionCode=20008")
    replace("v2/module/module.prop", "白泽 v2 Alpha 7", "白泽 v2 Alpha 8")
    replace("v2/module/customize.sh", "白泽 v2 Alpha 6", "白泽 v2 Alpha 8")
    replace("v2/module/service.sh", "module_version=2.0.0-alpha06", "module_version=2.0.0-alpha08")
    replace("v2/scripts/package-module.sh", "BaiZe-v2-Alpha7-Module.zip", "BaiZe-v2-Alpha8-Module.zip")

    workflow = Path(".github/workflows/v2-alpha.yml")
    text = workflow.read_text()
    text = text.replace("branches: [v2-alpha7]", "branches: [v2-alpha8]")
    text = text.replace("build-alpha7.log", "build-alpha8.log")
    text = text.replace("BaiZe-v2-Alpha7-Module.zip", "BaiZe-v2-Alpha8-Module.zip")
    text = text.replace("BaiZe-v2-Alpha7", "BaiZe-v2-Alpha8")
    workflow.write_text(text)


if __name__ == "__main__":
    patch_dashboard()
    patch_root_service()
    patch_cleaner()
    patch_versions()
