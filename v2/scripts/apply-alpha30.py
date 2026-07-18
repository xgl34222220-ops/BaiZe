#!/usr/bin/env python3
from pathlib import Path
import re


def replace_once(path: str, old: str, new: str, label: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"missing {label} in {path}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


def regex_once(path: str, pattern: str, replacement: str, label: str, flags: int = 0) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"regex {label} matched {count} times in {path}")
    target.write_text(updated, encoding="utf-8")


# ---------------------------------------------------------------------------
# Cleaner configuration and stale APK package discovery.
# ---------------------------------------------------------------------------
replace_once(
    "config/default.conf",
    "clean_installer_temp=1\nnotify_on_complete=1",
    "clean_installer_temp=1\nclean_apk_packages=1\nnotify_on_complete=1",
    "APK toggle",
)
replace_once(
    "config/default.conf",
    "installer_temp_days=7\nroot_shell_days=14",
    "installer_temp_days=7\napk_package_days=30\napk_package_max_mb=4096\nroot_shell_days=14",
    "APK retention",
)

replace_once(
    "cleaner.sh",
    "  corpse-scan) MODE=scan; PROFILE=corpse ;;\n  corpse-clean) MODE=clean; PROFILE=corpse ;;\n  scan|clean) MODE=$REQUEST_MODE ;;\n  *) echo \"用法: cleaner.sh scan|clean|cache-clean|empty-clean|rules-clean|fragment-scan|fragment-clean|deep-scan|deep-clean|corpse-scan|corpse-clean [trigger]\"; exit 2 ;;",
    "  corpse-scan) MODE=scan; PROFILE=corpse ;;\n  corpse-clean) MODE=clean; PROFILE=corpse ;;\n  apk-scan) MODE=scan; PROFILE=apk ;;\n  apk-clean) MODE=clean; PROFILE=apk ;;\n  scan|clean) MODE=$REQUEST_MODE ;;\n  *) echo \"用法: cleaner.sh scan|clean|cache-clean|empty-clean|rules-clean|fragment-scan|fragment-clean|deep-scan|deep-clean|corpse-scan|corpse-clean|apk-scan|apk-clean [trigger]\"; exit 2 ;;",
    "APK request modes",
)

apk_function = r'''run_apk_packages() {
  [ -d /data/media ] || return 0
  list="$TMP_DIR/apk-packages.nul"
  : >"$list"
  for userdir in /data/media/[0-9]*; do
    [ -d "$userdir" ] || continue
    for root in \
      "$userdir/Download" \
      "$userdir/Documents" \
      "$userdir/Tencent/QQfile_recv" \
      "$userdir/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv" \
      "$userdir/Android/data/com.tencent.mm/MicroMsg/Download" \
      "$userdir/UCDownloads" \
      "$userdir/Quark/Download" \
      "$userdir/BaiduNetdisk"; do
      [ -d "$root" ] || continue
      if [ "$APK_PACKAGE_DAYS" -eq 0 ]; then
        find "$root" -mindepth 1 -maxdepth 5 -type f -size "-${APK_PACKAGE_MAX_BYTES}c" \
          \( -iname '*.apk' -o -iname '*.apks' -o -iname '*.xapk' -o -iname '*.apkm' \) \
          -print0 2>/dev/null >>"$list"
      else
        find "$root" -mindepth 1 -maxdepth 5 -type f -mtime "+$APK_PACKAGE_DAYS" \
          -size "-${APK_PACKAGE_MAX_BYTES}c" \
          \( -iname '*.apk' -o -iname '*.apks' -o -iname '*.xapk' -o -iname '*.apkm' \) \
          -print0 2>/dev/null >>"$list"
      fi
    done
  done

  filter_whitelist_list "$list"
  filter_processed_list "$list"
  count=$(count_nul "$list")
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  [ "$count" -gt 0 ] || { rm -f "$list"; return 0; }
  estimated=$(bytes_from_list "$list")
  case "$estimated" in ''|*[!0-9]*) estimated=0 ;; esac
  sample_path=$(first_nul_path "$list" 2>/dev/null)

  if [ "$MODE" = "clean" ]; then
    err_file="$TMP_DIR/rm-apk-packages.err"
    xargs -0 -n 100 rm -f -- <"$list" 2>"$err_file"
    remaining="$TMP_DIR/apk-packages.remaining.nul"
    existing_files_to_list "$list" "$remaining"
    batch_actuals "$list" "$remaining" "$estimated"
    [ "$REMAINING_COUNT" -gt 0 ] && ERRORS=$((ERRORS + REMAINING_COUNT))
    FILES=$((FILES + ACTUAL_COUNT))
    add_bytes "$ACTUAL_BYTES"
    log_line "[安装包清理] 清理 $ACTUAL_COUNT 个，释放 $ACTUAL_BYTES bytes，未清理 $REMAINING_COUNT 个"
    [ "$ACTUAL_COUNT" -gt 0 ] && report_line cleaned low APK安装包 "$ACTUAL_COUNT" "$ACTUAL_BYTES" "${sample_path:-共享存储安装包}"
    [ "$REMAINING_COUNT" -gt 0 ] && report_line failed low APK安装包 "$REMAINING_COUNT" "$REMAINING_BYTES" "仍存在的安装包"
    rm -f "$remaining" "$err_file"
  else
    FILES=$((FILES + count))
    add_bytes "$estimated"
    log_line "[安装包扫描] 发现 $count 个过期安装包，约 $estimated bytes"
    report_line candidate low APK安装包 "$count" "$estimated" "${sample_path:-共享存储安装包}"
  fi
  rm -f "$list"
  return 0
}

'''
replace_once(
    "cleaner.sh",
    "run_installer_temp() {",
    apk_function + "run_installer_temp() {",
    "APK cleaner function",
)
replace_once(
    "cleaner.sh",
    "INSTALLER_TEMP_DAYS=$(get_uint installer_temp_days 7 1 30)\nROOT_SHELL_DAYS=$(get_uint root_shell_days 14 1 90)",
    "INSTALLER_TEMP_DAYS=$(get_uint installer_temp_days 7 1 30)\nAPK_PACKAGE_DAYS=$(get_uint apk_package_days 30 0 365)\nAPK_PACKAGE_MAX_MB=$(get_uint apk_package_max_mb 4096 16 16384)\nAPK_PACKAGE_MAX_BYTES=$(awk -v m=\"$APK_PACKAGE_MAX_MB\" 'BEGIN {printf \"%.0f\", m * 1048576}')\nROOT_SHELL_DAYS=$(get_uint root_shell_days 14 1 90)",
    "APK config values",
)
replace_once(
    "cleaner.sh",
    "RUN_FRAGMENT=0\ncase \"$PROFILE\" in\n  all) RUN_EMPTY=1; RUN_CACHE=1; RUN_RULES=1; RUN_FRAGMENT=1 ;;\n  empty) RUN_EMPTY=1 ;;\n  cache) RUN_CACHE=1 ;;\n  rules) RUN_RULES=1 ;;\n  fragment) RUN_FRAGMENT=1 ;;\n  corpse) ;;\nesac",
    "RUN_FRAGMENT=0\nRUN_APK=0\ncase \"$PROFILE\" in\n  all) RUN_EMPTY=1; RUN_CACHE=1; RUN_RULES=1; RUN_FRAGMENT=1; RUN_APK=1 ;;\n  empty) RUN_EMPTY=1 ;;\n  cache) RUN_CACHE=1 ;;\n  rules) RUN_RULES=1; RUN_APK=1 ;;\n  fragment) RUN_FRAGMENT=1 ;;\n  apk) RUN_APK=1 ;;\n  corpse) ;;\nesac",
    "APK profile flag",
)
replace_once(
    "cleaner.sh",
    "if [ \"$STOPPED\" = \"0\" ] && [ \"$RUN_RULES\" = \"1\" ] && [ \"$(get_bool clean_installer_temp)\" = \"1\" ]; then\n  set_phase \"扫描过期安装临时文件\"\n  run_installer_temp || STOPPED=1\nfi",
    "if [ \"$STOPPED\" = \"0\" ] && [ \"$RUN_APK\" = \"1\" ] && [ \"$(get_bool clean_apk_packages)\" = \"1\" ]; then\n  set_phase \"扫描过期 APK 安装包（保留 ${APK_PACKAGE_DAYS} 天）\"\n  run_apk_packages || STOPPED=1\nfi\n\nif [ \"$STOPPED\" = \"0\" ] && [ \"$RUN_RULES\" = \"1\" ] && [ \"$(get_bool clean_installer_temp)\" = \"1\" ]; then\n  set_phase \"扫描过期安装临时文件\"\n  run_installer_temp || STOPPED=1\nfi",
    "APK execution stage",
)

# ---------------------------------------------------------------------------
# Root service: expose general/non-app cleanup rows to Compose.
# ---------------------------------------------------------------------------
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt",
    ".put(\"appDetails\", appDetails)\n            .put(\"message\", when (code) {",
    ".put(\"appDetails\", appDetails)\n            .put(\"otherDetails\", otherDetailsJson(latestReport))\n            .put(\"message\", when (code) {",
    "task other details",
)

other_details = r'''    private fun otherDetailsJson(file: File): JSONArray {
        data class Aggregate(
            var files: Long = 0,
            var bytes: Long = 0,
            var errors: Long = 0,
            var samplePath: String = ""
        )

        val groups = linkedMapOf<String, Aggregate>()
        runCatching {
            if (!file.isFile) return@runCatching
            file.forEachLine { raw ->
                val columns = raw.split('\t', limit = 6)
                if (columns.size < 6 || columns[0] == "action") return@forEachLine
                val action = columns[0].trim()
                if (action !in setOf("candidate", "cleaned", "failed")) return@forEachLine
                val category = columns[2].trim().take(80)
                if (category.isBlank()) return@forEachLine
                val suffix = category.substringAfterLast(':', "")
                if (suffix.isNotBlank() && PACKAGE_NAME.matches(suffix)) return@forEachLine
                val items = columns[3].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val bytes = columns[4].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val path = columns[5].trim().take(240)
                val aggregate = groups.getOrPut(category) { Aggregate() }
                if (action == "failed") {
                    aggregate.errors += items
                } else {
                    aggregate.files += items
                    aggregate.bytes += bytes
                }
                if (aggregate.samplePath.isBlank() && path.isNotBlank()) aggregate.samplePath = path
            }
        }
        val result = JSONArray()
        groups.entries
            .filter { (_, value) -> value.files > 0 || value.bytes > 0 || value.errors > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Aggregate>> { it.value.bytes }.thenBy { it.key })
            .take(60)
            .forEach { (name, value) ->
                result.put(
                    JSONObject()
                        .put("name", name)
                        .put("files", value.files)
                        .put("bytes", value.bytes)
                        .put("errors", value.errors)
                        .put("samplePath", value.samplePath)
                )
            }
        return result
    }

'''
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt",
    "    private fun moduleState(): String {",
    other_details + "    private fun moduleState(): String {",
    "other details parser",
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt",
    ".put(\n                \"appDetails\",\n                appDetailsJson(\n                    File(stateDir, \"reports/apps-latest.tsv\"),\n                    File(stateDir, \"reports/app-items-latest.tsv\")\n                )\n            )\n            .put(\"running\", running)",
    ".put(\n                \"appDetails\",\n                appDetailsJson(\n                    File(stateDir, \"reports/apps-latest.tsv\"),\n                    File(stateDir, \"reports/app-items-latest.tsv\")\n                )\n            )\n            .put(\"otherDetails\", otherDetailsJson(File(stateDir, \"reports/latest.tsv\")))\n            .put(\"running\", running)",
    "module state other details",
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt",
    '"fragment-clean", "deep-scan", "deep-clean", "corpse-scan", "corpse-clean"',
    '"fragment-clean", "deep-scan", "deep-clean", "corpse-scan", "corpse-clean",\n            "apk-scan", "apk-clean"',
    "APK module tasks",
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt",
    '"clean_installer_temp" to 0..1,\n            "notify_on_complete" to 0..1,',
    '"clean_installer_temp" to 0..1,\n            "clean_apk_packages" to 0..1,\n            "notify_on_complete" to 0..1,',
    "APK allowed toggle",
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt",
    '"installer_temp_days" to 1..30,\n            "root_shell_days" to 1..90,',
    '"installer_temp_days" to 1..30,\n            "apk_package_days" to 0..365,\n            "apk_package_max_mb" to 16..16_384,\n            "root_shell_days" to 1..90,',
    "APK allowed values",
)

# ---------------------------------------------------------------------------
# Theme: make AMOLED a real pure-black mode in Compose and pre-create logic.
# ---------------------------------------------------------------------------
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ThemeManager.kt",
    "    fun isAmoledEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AMOLED, false)\n    fun isBlurEnabled(context: Context): Boolean",
    "    fun isAmoledEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AMOLED, false)\n\n    fun resolvedDark(context: Context): Boolean = when (currentMode(context)) {\n        MODE_LIGHT -> false\n        MODE_DARK -> true\n        else -> isDark(context)\n    }\n\n    fun isAmoledActive(context: Context): Boolean = isAmoledEnabled(context) && resolvedDark(context)\n\n    fun isBlurEnabled(context: Context): Boolean",
    "AMOLED active state",
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ThemeManager.kt",
    "        if (isMonetEnabled(context)) {\n            append(\"Monet \").append(currentMonetStyle(context).label)\n        } else {\n            append(currentPalette(context).label)\n            if (isAmoledEnabled(context) && isDark(context)) append(\" · 纯黑\")\n        }\n        append(if (isGlassEnabled(context)) \" · 液态玻璃\" else \" · 实心底栏\")",
    "        if (isMonetEnabled(context)) {\n            append(\"Monet \").append(currentMonetStyle(context).label)\n        } else {\n            append(currentPalette(context).label)\n        }\n        if (isAmoledActive(context)) append(\" · 纯黑\")\n        append(if (isGlassEnabled(context)) \" · 柔和卡片\" else \" · 实心卡片\")",
    "theme summary",
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ThemeManager.kt",
    "            !monet && isAmoledEnabled(activity) && isDark(activity) -> R.style.Theme_BaiZe_Amoled",
    "            isAmoledActive(activity) -> R.style.Theme_BaiZe_Amoled",
    "AMOLED pre-create theme",
)

replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ThemeSettingsActivity.kt",
    "import android.os.Build\nimport android.os.Bundle",
    "import android.graphics.Color\nimport android.os.Build\nimport android.os.Bundle",
    "theme color import",
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ThemeSettingsActivity.kt",
    "        setContentView(binding.root)\n\n        binding.backButton",
    "        setContentView(binding.root)\n        if (ThemeManager.isAmoledActive(this)) {\n            binding.root.setBackgroundColor(Color.BLACK)\n            window.statusBarColor = Color.BLACK\n            window.navigationBarColor = Color.BLACK\n        }\n\n        binding.backButton",
    "theme settings black background",
)

# ---------------------------------------------------------------------------
# Compose state, actions, AMOLED palette, solid result cards and APK entry.
# ---------------------------------------------------------------------------
app = "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
replace_once(app, "import androidx.compose.material.icons.rounded.Home\n", "import androidx.compose.material.icons.rounded.Home\nimport androidx.compose.material.icons.rounded.InstallMobile\n", "install icon import")
replace_once(app, "import androidx.compose.material3.Switch\n", "import androidx.compose.material3.Surface\nimport androidx.compose.material3.Switch\n", "surface import")
replace_once(
    app,
    "    val whitelistCount: Int = 0,\n    val recentApps: List<AppJunkUiItem> = emptyList(),\n    val history: List<HistoryUiItem> = emptyList()",
    "    val whitelistCount: Int = 0,\n    val recentApps: List<AppJunkUiItem> = emptyList(),\n    val recentJunk: List<GeneralJunkUiItem> = emptyList(),\n    val history: List<HistoryUiItem> = emptyList()",
    "general junk state",
)
replace_once(
    app,
    "data class HistoryUiItem(",
    "data class GeneralJunkUiItem(\n    val name: String,\n    val files: Long,\n    val bytes: Long,\n    val errors: Long,\n    val samplePath: String\n)\n\ndata class HistoryUiItem(",
    "general junk data class",
)
replace_once(
    app,
    "    val maxFileMb: Int = 256,\n    val saving: Boolean = false",
    "    val maxFileMb: Int = 256,\n    val apkPackagesEnabled: Boolean = true,\n    val apkPackageDays: Int = 30,\n    val saving: Boolean = false",
    "APK scheduler fields",
)
replace_once(
    app,
    ".put(\"max_file_mb\", maxFileMb.coerceIn(16, 2048))",
    ".put(\"max_file_mb\", maxFileMb.coerceIn(16, 2048))\n        .put(\"clean_apk_packages\", apkPackagesEnabled.flag())\n        .put(\"apk_package_days\", apkPackageDays.coerceIn(0, 365))",
    "APK scheduler JSON",
)
replace_once(
    app,
    "            maxFileMb = json.optInt(\"max_file_mb\", 256).coerceIn(16, 2048)\n        )",
    "            maxFileMb = json.optInt(\"max_file_mb\", 256).coerceIn(16, 2048),\n            apkPackagesEnabled = json.optInt(\"clean_apk_packages\", 1) == 1,\n            apkPackageDays = json.optInt(\"apk_package_days\", 30).coerceIn(0, 365)\n        )",
    "APK scheduler parser",
)
replace_once(
    app,
    "    val scan: () -> Unit,\n    val cleanScan: () -> Unit,",
    "    val scan: () -> Unit,\n    val apkScan: () -> Unit,\n    val cleanScan: () -> Unit,",
    "APK dashboard action",
)
replace_once(
    app,
    "    val resolvedPrimary = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF3975F4.toInt()))",
    "    val amoled = dark && ThemeManager.isAmoledEnabled(context)\n    val resolvedPrimary = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF3975F4.toInt()))",
    "AMOLED compose state",
)
replace_once(
    app,
    "    val resolvedSurface = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, if (dark) 0xFF191B24.toInt() else 0xFFFFFFFF.toInt()))",
    "    val resolvedSurface = if (amoled) Color(0xFF080808) else Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, if (dark) 0xFF191B24.toInt() else 0xFFFFFFFF.toInt()))",
    "AMOLED resolved surface",
)
replace_once(
    app,
    "            background = if (ThemeManager.isAmoledEnabled(context)) Color.Black else Color(0xFF101117),\n            surface = resolvedSurface,",
    "            background = if (amoled) Color.Black else Color(0xFF101117),\n            surface = resolvedSurface,\n            surfaceVariant = if (amoled) Color(0xFF101010) else Color(0xFF20232D),",
    "AMOLED color scheme",
)
replace_once(app, "            MiuiXBackdrop(dark)", "            MiuiXBackdrop(dark, amoled)", "AMOLED backdrop call")
replace_once(app, "private fun MiuiXBackdrop(dark: Boolean) {", "private fun MiuiXBackdrop(dark: Boolean, amoled: Boolean) {", "AMOLED backdrop signature")
replace_once(
    app,
    "    val base = if (dark) listOf(Color(0xFF101117), Color(0xFF151827), Color(0xFF101117))\n    else listOf(Color(0xFFF8F7FF), Color(0xFFF0F5FF), Color(0xFFF8F8FC))",
    "    val base = when {\n        amoled -> listOf(Color.Black, Color.Black, Color.Black)\n        dark -> listOf(Color(0xFF101117), Color(0xFF151827), Color(0xFF101117))\n        else -> listOf(Color(0xFFF8F7FF), Color(0xFFF0F5FF), Color(0xFFF8F8FC))\n    }",
    "AMOLED backdrop base",
)
replace_once(
    app,
    "            .drawBehind {\n                drawRect(\n                    Brush.radialGradient(",
    "            .drawBehind {\n                if (amoled) return@drawBehind\n                drawRect(\n                    Brush.radialGradient(",
    "disable AMOLED gradients",
)
replace_once(
    app,
    "    val dark = MaterialTheme.colorScheme.background.luminance() < .5f\n    val fill = if (dark) Color(0xFF252733).copy(alpha = .86f) else Color.White.copy(alpha = .80f)\n    val border = if (dark) Color.White.copy(alpha = .09f) else Color.White.copy(alpha = .82f)",
    "    val context = LocalContext.current\n    val dark = MaterialTheme.colorScheme.background.luminance() < .5f\n    val amoled = dark && ThemeManager.isAmoledEnabled(context)\n    val glass = ThemeManager.isGlassEnabled(context)\n    val fill = when {\n        amoled -> Color(0xFF080808)\n        dark && glass -> Color(0xFF1B1D25)\n        dark -> MaterialTheme.colorScheme.surface\n        glass -> Color(0xFFF9F9FD)\n        else -> MaterialTheme.colorScheme.surface\n    }\n    val border = if (dark) Color.White.copy(alpha = .08f) else MaterialTheme.colorScheme.primary.copy(alpha = .08f)",
    "solid glass surface",
)

result_surface = r'''@Composable
private fun ResultSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(26.dp),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)
        )
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

'''
replace_once(app, "@Composable\nprivate fun PageHeader", result_surface + "@Composable\nprivate fun PageHeader", "result surface")
replace_once(
    app,
    "                    ToolRow(Icons.Rounded.Search, \"垃圾扫描\", \"只查找并统计垃圾，不删除；完成后可一键清理\", actions.scan)\n                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.12f))\n                    ToolRow(Icons.Rounded.DeleteSweep, \"深度清理\", \"高风险规则先展示，再由你确认\", actions.deep)",
    "                    ToolRow(Icons.Rounded.Search, \"垃圾扫描\", \"只查找并统计垃圾，不删除；完成后可一键清理\", actions.scan)\n                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.12f))\n                    ToolRow(Icons.Rounded.InstallMobile, \"安装包扫描\", \"查找 Download、QQ、微信等目录中的 APK/APKS/XAPK\", actions.apkScan)\n                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.12f))\n                    ToolRow(Icons.Rounded.DeleteSweep, \"深度清理\", \"扫描日志、临时文件与常见残留\", actions.deep)",
    "APK home tool",
)
replace_once(
    app,
    "        item { PageHeader(\"SMART CLEAN\", \"白泽\", \"原生清理引擎 · Alpha 29\", actions.refresh) }",
    "        item { PageHeader(\"SMART CLEAN\", \"白泽\", \"原生清理引擎 · Alpha 30\", actions.refresh) }",
    "Alpha 30 header",
)
replace_once(
    app,
    "        if (state.recentApps.isNotEmpty()) {\n            item {\n                GlassSurface(\n                    Modifier.padding(horizontal = 18.dp).fillMaxWidth(),\n                    shape = RoundedCornerShape(28.dp),\n                    shadow = 8,\n                    contentPadding = PaddingValues(20.dp)\n                ) {\n                    CurrentCleanupSummaryContent(state.recentApps)\n                }\n            }\n        }",
    "        if (state.recentApps.isNotEmpty() || state.recentJunk.isNotEmpty()) {\n            item {\n                ResultSurface(\n                    Modifier.padding(horizontal = 18.dp).fillMaxWidth(),\n                    shape = RoundedCornerShape(28.dp),\n                    contentPadding = PaddingValues(20.dp)\n                ) {\n                    CurrentCleanupSummaryContent(state.recentApps, state.recentJunk)\n                }\n            }\n        }",
    "records summary surface",
)
replace_once(
    app,
    "        item {\n            GlassSurface(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), contentPadding = PaddingValues(22.dp)) {",
    "        item {\n            ResultSurface(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), contentPadding = PaddingValues(22.dp)) {",
    "records cumulative surface",
)
replace_once(
    app,
    "        if (state.recentApps.isNotEmpty()) {\n            item { SectionTitle(\"本次应用垃圾\", \"按实际清理结果从大到小排列\") }\n            items(state.recentApps.indices.toList(), key = { index -> \"app-$index-${state.recentApps[index].packageName}\" }) { index ->\n                AppJunkCard(state.recentApps[index])\n            }\n        }\n        item {",
    "        if (state.recentApps.isNotEmpty()) {\n            item { SectionTitle(\"应用垃圾\", \"按实际结果从大到小排列，点击卡片查看分类\") }\n            items(state.recentApps.indices.toList(), key = { index -> \"app-$index-${state.recentApps[index].packageName}\" }) { index ->\n                AppJunkCard(state.recentApps[index])\n            }\n        }\n        if (state.recentJunk.isNotEmpty()) {\n            item { SectionTitle(\"其他垃圾\", \"安装包、日志、临时文件与碎片\") }\n            items(state.recentJunk.indices.toList(), key = { index -> \"junk-$index-${state.recentJunk[index].name}\" }) { index ->\n                GeneralJunkCard(state.recentJunk[index])\n            }\n        }\n        item {",
    "general junk records section",
)
replace_once(
    app,
    "private fun AppJunkCard(item: AppJunkUiItem) {\n    GlassSurface(\n        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),\n        shape = RoundedCornerShape(24.dp),\n        shadow = 5,\n        contentPadding = PaddingValues(16.dp)\n    ) {\n        AppJunkCardContent(item)\n    }\n}",
    "private fun AppJunkCard(item: AppJunkUiItem) {\n    ResultSurface(\n        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),\n        shape = RoundedCornerShape(24.dp),\n        contentPadding = PaddingValues(17.dp)\n    ) {\n        AppJunkCardContent(item)\n    }\n}\n\n@Composable\nprivate fun GeneralJunkCard(item: GeneralJunkUiItem) {\n    ResultSurface(\n        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),\n        shape = RoundedCornerShape(24.dp),\n        contentPadding = PaddingValues(17.dp)\n    ) {\n        GeneralJunkCardContent(item)\n    }\n}",
    "solid result cards",
)
# Replace only the history card's surface occurrence after its function declaration.
regex_once(
    app,
    r"(@Composable\nprivate fun HistoryCard\(item: HistoryUiItem\) \{\n    val context = LocalContext.current\n)    GlassSurface\(\n        Modifier.padding\(horizontal = 18.dp\).fillMaxWidth\(\),\n        shape = RoundedCornerShape\(26.dp\),\n        shadow = 5,\n        contentPadding = PaddingValues\(17.dp\)\n    \)",
    r"\1    ResultSurface(\n        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),\n        shape = RoundedCornerShape(26.dp),\n        contentPadding = PaddingValues(17.dp)\n    )",
    "history result surface",
)
replace_once(
    app,
    "                    Text(\"清理保护\", fontSize = 22.sp, fontWeight = FontWeight.Black)",
    "                    Text(\"清理范围\", fontSize = 22.sp, fontWeight = FontWeight.Black)",
    "settings section title",
)
replace_once(
    app,
    "                    Text(\"单文件上限 ${config.maxFileMb} MB\", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))",
    "                    SettingSwitch(\"清理过期 APK 安装包\", config.apkPackagesEnabled) { actions.updateScheduler(config.copy(apkPackagesEnabled = it)) }\n                    Text(\"APK 安装包保留 ${config.apkPackageDays} 天\", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))\n                    Text(\"扫描 Download、QQ、微信及常见浏览器下载目录中的 APK/APKS/XAPK。\", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)\n                    Slider(value = config.apkPackageDays.toFloat(), onValueChange = { actions.updateScheduler(config.copy(apkPackageDays = it.roundToInt().coerceIn(0, 365))) }, valueRange = 0f..365f)\n                    Text(\"单文件上限 ${config.maxFileMb} MB\", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))",
    "APK settings controls",
)

# ---------------------------------------------------------------------------
# Dashboard activity: APK task action and parsing general cleanup rows.
# ---------------------------------------------------------------------------
activity = "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
replace_once(activity, "    private var pendingClean = false\n", "    private var pendingClean = false\n    private var pendingModuleTask: String? = null\n", "pending APK task")
replace_once(activity, "            runPendingCleanIfReady()\n", "            runPendingCleanIfReady()\n            runPendingModuleTaskIfReady()\n", "pending task connection callback")
replace_once(
    activity,
    "                    scan = { runNativeScan(cleanAfterScan = false) },\n                    cleanScan = { cleanNativeSnapshots() },",
    "                    scan = { runNativeScan(cleanAfterScan = false) },\n                    apkScan = { runApkScan() },\n                    cleanScan = { cleanNativeSnapshots() },",
    "APK action wiring",
)

apk_activity_methods = r'''    private fun runPendingModuleTaskIfReady() {
        val mode = pendingModuleTask ?: return
        val service = rootService ?: return
        pendingModuleTask = null
        runModuleUtilityTask(service, mode)
    }

    private fun runApkScan() {
        val service = rootService
        if (service == null) {
            pendingModuleTask = "apk-scan"
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                taskPhase = "正在连接 Root 服务，连接后自动扫描安装包"
            )
            connectPrimaryService()
            return
        }
        runModuleUtilityTask(service, "apk-scan")
    }

    private fun runModuleUtilityTask(service: IProfileRootService, mode: String) {
        if (dashboardState.value.running) return
        dashboardState.value = dashboardState.value.copy(
            running = true,
            taskPhase = if (mode == "apk-scan") "正在扫描 APK 安装包…" else "正在执行清理任务…"
        )
        startNativePoll()
        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.runModuleTask(mode)) }
            }
            pollJob?.cancel()
            if (response.isFailure) {
                rootService = null
                profileBound = false
                dashboardState.value = dashboardState.value.copy(
                    connected = false,
                    ready = false,
                    running = false,
                    serviceText = "Root 服务已断开，正在重新连接…",
                    taskPhase = "安装包扫描失败：${response.exceptionOrNull()?.message ?: "Root 服务异常"}"
                )
                connectPrimaryService()
                return@launch
            }
            val json = response.getOrThrow()
            val latest = json.optJSONObject("latest") ?: JSONObject()
            val success = json.optBoolean("success")
            val cancelled = json.optBoolean("cancelled")
            val junk = parseGeneralJunk(json.optJSONArray("otherDetails"))
            val result = latest.optString("result").ifBlank {
                when {
                    cancelled -> "安装包扫描已停止"
                    success && junk.isEmpty() -> "没有发现超过保留期的安装包"
                    success -> "安装包扫描完成，发现 ${junk.sumOf { it.files }} 项"
                    else -> json.optString("message", "安装包扫描失败")
                }
            }
            dashboardState.value = dashboardState.value.copy(
                running = false,
                recentJunk = junk,
                taskPhase = result
            )
            refreshHistory()
            refreshModuleState()
            readServiceStatus()
        }
    }

'''
replace_once(activity, "    private fun runSmartClean() {", apk_activity_methods + "    private fun runSmartClean() {", "APK activity methods")
replace_once(
    activity,
    "            val appDetails = parseAppDetails(json.optJSONArray(\"appDetails\"))\n            val detailLine =",
    "            val appDetails = parseAppDetails(json.optJSONArray(\"appDetails\"))\n            val otherDetails = parseGeneralJunk(json.optJSONArray(\"otherDetails\"))\n            val detailLine =",
    "clean other details parse",
)
replace_once(
    activity,
    "                running = false,\n                lastReleased = bytes,\n                taskPhase = \"$resultLine\\n$detailLine\"",
    "                running = false,\n                lastReleased = bytes,\n                recentApps = appDetails,\n                recentJunk = otherDetails,\n                taskPhase = \"$resultLine\\n$detailLine\"",
    "clean result state",
)
replace_once(
    activity,
    "            val appDetails = parseAppDetails(json.optJSONArray(\"appDetails\"))\n            val latestMode = latest.optString(\"mode\")",
    "            val appDetails = parseAppDetails(json.optJSONArray(\"appDetails\"))\n            val otherDetails = parseGeneralJunk(json.optJSONArray(\"otherDetails\"))\n            val latestMode = latest.optString(\"mode\")",
    "module general details parse",
)
replace_once(
    activity,
    "                recentApps = if (appDetails.isNotEmpty()) appDetails else dashboardState.value.recentApps,\n                schedulerText =",
    "                recentApps = if (appDetails.isNotEmpty()) appDetails else dashboardState.value.recentApps,\n                recentJunk = if (otherDetails.isNotEmpty()) otherDetails else dashboardState.value.recentJunk,\n                schedulerText =",
    "module general details state",
)
parse_general = r'''    private fun parseGeneralJunk(array: JSONArray?): List<GeneralJunkUiItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            if (name.isBlank()) continue
            add(
                GeneralJunkUiItem(
                    name = name,
                    files = item.optLong("files", 0L).coerceAtLeast(0L),
                    bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                    errors = item.optLong("errors", 0L).coerceAtLeast(0L),
                    samplePath = item.optString("samplePath").trim()
                )
            )
        }
    }.sortedWith(compareByDescending<GeneralJunkUiItem> { it.bytes }.thenByDescending { it.files })

'''
replace_once(activity, "    private fun looksLikePackageName(value: String): Boolean =", parse_general + "    private fun looksLikePackageName(value: String): Boolean =", "general junk parser")

# ---------------------------------------------------------------------------
# Rewrite cleanup result components with a cleaner, flatter MIUIx hierarchy.
# ---------------------------------------------------------------------------
Path("v2/app/src/main/java/io/github/xgl34222220/baize/AppJunkResultComponents.kt").write_text(r'''package io.github.xgl34222220.baize

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AppJunkCardContent(item: AppJunkUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(item.packageName) { mutableStateOf(false) }
    val categories = item.categories
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = categories.isNotEmpty()) { expanded = !expanded }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ApplicationIcon(item.packageName, item.label, Modifier.size(50.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.label,
                        modifier = Modifier.weight(1f),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        Formatter.formatFileSize(context, item.bytes),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp
                    )
                }
                Text(
                    item.packageName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                CategoryChipRow(categories, item.category)
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${item.files} 个文件${if (item.errors > 0) " · ${item.errors} 个未清理" else ""}",
                        modifier = Modifier.weight(1f),
                        color = if (item.errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    if (categories.isNotEmpty()) {
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            if (expanded) "收起明细" else "展开明细",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = expanded && categories.isNotEmpty()) {
            Column {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 14.dp, bottom = 7.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)
                )
                categories.forEach { CategoryDetailRow(it) }
            }
        }
    }
}

@Composable
private fun CategoryChipRow(categories: List<AppJunkCategoryUiItem>, fallback: String) {
    val labels = if (categories.isNotEmpty()) {
        categories.map { friendlyCategory(it.name) }.distinct()
    } else {
        fallback.split('、', ',', '，').map { friendlyCategory(it) }.filter { it.isNotBlank() }.distinct()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.take(3).forEach { CategoryChip(it) }
        if (labels.size > 3) CategoryChip("+${labels.size - 3}")
    }
}

@Composable
private fun CategoryChip(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .09f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CategoryDetailRow(category: AppJunkCategoryUiItem) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(friendlyCategory(category.name), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${category.files} 个文件${if (category.errors > 0) " · ${category.errors} 个未清理" else ""}",
                color = if (category.errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            if (category.samplePath.isNotBlank()) {
                Text(
                    category.samplePath,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            Formatter.formatFileSize(context, category.bytes),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun friendlyCategory(raw: String): String {
    val value = raw.substringBeforeLast(':', raw).trim()
    return when {
        value.contains("WebView", true) -> "WebView"
        value.contains("外部") && value.contains("缓存") -> "外部缓存"
        value.contains("code", true) -> "代码缓存"
        value.contains("内部") && value.contains("缓存") -> "内部缓存"
        value.contains("空文件") -> "空文件"
        value.contains("空目录") -> "空目录"
        value.contains("日志") -> "应用日志"
        value.contains("扩展规则") -> "扩展缓存"
        value.contains("缓存") -> "应用缓存"
        value.isBlank() -> "应用垃圾"
        else -> value.take(12)
    }
}
''', encoding="utf-8")

Path("v2/app/src/main/java/io/github/xgl34222220/baize/CleanupSummaryComponents.kt").write_text(r'''package io.github.xgl34222220.baize

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CurrentCleanupSummaryContent(apps: List<AppJunkUiItem>, junk: List<GeneralJunkUiItem>) {
    val context = LocalContext.current
    val totalBytes = apps.sumOf { it.bytes.coerceAtLeast(0L) } + junk.sumOf { it.bytes.coerceAtLeast(0L) }
    val totalFiles = apps.sumOf { it.files.coerceAtLeast(0L) } + junk.sumOf { it.files.coerceAtLeast(0L) }
    val totalErrors = apps.sumOf { it.errors.coerceAtLeast(0L) } + junk.sumOf { it.errors.coerceAtLeast(0L) }
    val largestApp = apps.maxByOrNull { it.bytes }
    val largestJunk = junk.maxByOrNull { it.bytes }
    val appWins = (largestApp?.bytes ?: -1L) >= (largestJunk?.bytes ?: -1L)

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            }
            Column(Modifier.padding(start = 13.dp).weight(1f)) {
                Text("本次结果", fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("全部按实际扫描或删除结果统计", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
            Text(
                Formatter.formatFileSize(context, totalBytes),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Black
            )
        }

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SummaryStat("${apps.size + junk.size}", "分类来源")
            SummaryStat("$totalFiles", "文件项目")
            SummaryStat("$totalErrors", "未处理")
        }

        if (largestApp != null || largestJunk != null) {
            Spacer(Modifier.height(16.dp))
            Text("最大来源", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (appWins && largestApp != null) {
                    ApplicationIcon(largestApp.packageName, largestApp.label, Modifier.size(30.dp))
                    Text(
                        largestApp.label,
                        modifier = Modifier.padding(start = 9.dp).weight(1f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(Formatter.formatFileSize(context, largestApp.bytes), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else if (largestJunk != null) {
                    Icon(
                        if (largestJunk.name.contains("APK", true) || largestJunk.name.contains("安装包")) Icons.Rounded.InstallMobile else Icons.Rounded.DeleteSweep,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        friendlyGeneralName(largestJunk.name),
                        modifier = Modifier.padding(start = 9.dp).weight(1f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(Formatter.formatFileSize(context, largestJunk.bytes), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun GeneralJunkCardContent(item: GeneralJunkUiItem) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .09f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (item.name.contains("APK", true) || item.name.contains("安装包")) Icons.Rounded.InstallMobile else Icons.Rounded.DeleteSweep,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(25.dp)
            )
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(friendlyGeneralName(item.name), modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(Formatter.formatFileSize(context, item.bytes), color = MaterialTheme.colorScheme.primary, fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
            Text(
                "${item.files} 个文件${if (item.errors > 0) " · ${item.errors} 个未处理" else ""}",
                color = if (item.errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            if (item.samplePath.isNotBlank()) {
                Text(item.samplePath, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    }
}

private fun friendlyGeneralName(raw: String): String = when {
    raw.contains("APK", true) || raw.contains("安装包") -> "APK 安装包"
    raw.contains("安装临时") -> "安装临时文件"
    raw.contains("碎片") -> "残留碎片"
    raw.contains("DropBox") -> "系统诊断日志"
    raw.contains("日志") -> raw.take(16)
    else -> raw.take(18)
}
''', encoding="utf-8")

# ---------------------------------------------------------------------------
# Versioning and packaging.
# ---------------------------------------------------------------------------
replace_once("v2/app/build.gradle.kts", 'versionCode = 20900\n        versionName = "2.0.0-alpha29"', 'versionCode = 21000\n        versionName = "2.0.0-alpha30"', "App version")
replace_once("v2/module/module.prop", "version=v2.0.0-alpha29\nversionCode=20900", "version=v2.0.0-alpha30\nversionCode=21000", "module version")
replace_once("v2/module/customize.sh", "安装白泽 v2 Alpha 29 应用图标与清理明细版", "安装白泽 v2 Alpha 30 安装包扫描与纯黑主题版", "installer title")
replace_once("v2/scripts/package-module.sh", "BaiZe-v2-Alpha29-Module.zip", "BaiZe-v2-Alpha30-Module.zip", "package filename")
replace_once("v2/scripts/package-module.sh", "已生成 Alpha 29 应用图标、分类清理明细与完整清理引擎模块", "已生成 Alpha 30 APK 安装包扫描、纯黑主题与精简结果卡片模块", "package message")

readme = Path("v2/README.md")
text = readme.read_text(encoding="utf-8")
text = text.replace("# 白泽 v2 Alpha 29", "# 白泽 v2 Alpha 30", 1).replace("当前开发分支：`v2-alpha29`。", "当前开发分支：`v2-alpha30`。", 1)
marker = "## Alpha 29\n"
section = "## Alpha 30\n\n- 新增 APK/APKS/XAPK/APKM 安装包扫描，覆盖 Download、QQ、微信和常见浏览器下载目录，并支持保留天数设置。\n- 记录页增加其他垃圾分类，安装包、日志、临时文件和碎片不再只混在总数中。\n- 结果卡片改用实心 Surface，移除透明叠层造成的白色矩形渲染异常。\n- AMOLED 开关真正启用纯黑背景、纯黑卡片和纯黑底栏，并关闭背景彩色光晕。\n- 保持 Alpha 29 的单 RootService 一键清理与按应用明细链路。\n\n"
if section not in text:
    if marker not in text:
        raise SystemExit("missing README Alpha 29 marker")
    text = text.replace(marker, section + marker, 1)
readme.write_text(text, encoding="utf-8")

print("Alpha 30 migration applied")
