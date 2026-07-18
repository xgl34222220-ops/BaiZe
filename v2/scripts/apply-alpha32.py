#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Patch target not found: {target}\n--- expected ---\n{old}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


cleaner = "cleaner.sh"
replace_once(
    cleaner,
    '''cp -f "$REPORT_FILE" "$LATEST_REPORT"
printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$REQUEST_MODE" "$BYTES" "$TOTAL_FILES" "$EMPTY_DIRS" "$ERRORS" "$RESULT" "$TRIGGER" >>"$HISTORY_FILE"''',
    '''cp -f "$REPORT_FILE" "$LATEST_REPORT"

# Persist compact category/application details with each history row. Old eight-column rows remain compatible.
HISTORY_ACTION=candidate
[ "$MODE" = "clean" ] && HISTORY_ACTION=cleaned
HISTORY_CATEGORIES=$(awk -F '\\t' -v action="$HISTORY_ACTION" '
  NR > 1 && $1 == action {
    name=$3; gsub(/[|;\\t\\r\\n]/, " ", name)
    if (name == "") next
    files[name]+=$4+0; bytes[name]+=$5+0
  }
  END { for (name in bytes) printf "%d\\t%d\\t%s\\n", bytes[name], files[name], name }
' "$REPORT_FILE" 2>/dev/null | sort -t "$(printf '\\t')" -k1,1nr | head -n 8 | awk -F '\\t' '
  BEGIN { first=1 }
  { if (!first) printf ";"; printf "%s|%s|%s", $3, $1, $2; first=0 }
')
HISTORY_APPS=$(awk -F '\\t' '
  NR > 1 {
    package=$1; category=$2
    gsub(/[|;\\t\\r\\n]/, " ", package); gsub(/[|;\\t\\r\\n]/, " ", category)
    if (package == "") next
    files[package]+=$3+0; bytes[package]+=$4+0
    if (topcat[package] == "" || ($4+0) > topbytes[package]) { topcat[package]=category; topbytes[package]=$4+0 }
  }
  END { for (package in bytes) printf "%d\\t%d\\t%s\\t%s\\n", bytes[package], files[package], package, topcat[package] }
' "$APP_ITEMS" 2>/dev/null | sort -t "$(printf '\\t')" -k1,1nr | head -n 8 | awk -F '\\t' '
  BEGIN { first=1 }
  { if (!first) printf ";"; printf "%s|%s|%s|%s", $3, $1, $2, $4; first=0 }
')
HISTORY_CATEGORIES=$(sanitize_report_field "$HISTORY_CATEGORIES")
HISTORY_APPS=$(sanitize_report_field "$HISTORY_APPS")
printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$REQUEST_MODE" "$BYTES" "$TOTAL_FILES" "$EMPTY_DIRS" "$ERRORS" "$RESULT" "$TRIGGER" "$HISTORY_CATEGORIES" "$HISTORY_APPS" >>"$HISTORY_FILE"''',
)

service = "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
replace_once(
    service,
    '''        lines.forEach { raw ->
            val columns = raw.split('\\t', limit = 8)
            if (columns.size < 7) return@forEach''',
    '''        lines.forEach { raw ->
            val columns = raw.split('\\t', limit = 10)
            if (columns.size < 7) return@forEach''',
)
replace_once(
    service,
    '''                    .put("result", columns[6].trim())
                    .put("trigger", columns.getOrNull(7)?.trim().orEmpty())
                    .put("cleaned", cleaned)''',
    '''                    .put("result", columns[6].trim())
                    .put("trigger", columns.getOrNull(7)?.trim().orEmpty())
                    .put("categoryDetails", parseHistoryCategoryDetails(columns.getOrNull(8).orEmpty()))
                    .put("appDetails", parseHistoryAppDetails(columns.getOrNull(9).orEmpty()))
                    .put("cleaned", cleaned)''',
)
replace_once(
    service,
    '''    private fun clearTaskHistoryJson(): String = runCatching {''',
    '''    private fun parseHistoryCategoryDetails(raw: String): JSONArray {
        val result = JSONArray()
        raw.split(';').asSequence().map { it.trim() }.filter { it.isNotBlank() }.take(12).forEach { token ->
            val columns = token.split('|', limit = 3)
            if (columns.size < 3) return@forEach
            val name = columns[0].trim().take(80)
            if (name.isBlank()) return@forEach
            result.put(
                JSONObject()
                    .put("name", name)
                    .put("bytes", columns[1].toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
                    .put("files", columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
            )
        }
        return result
    }

    private fun parseHistoryAppDetails(raw: String): JSONArray {
        val result = JSONArray()
        raw.split(';').asSequence().map { it.trim() }.filter { it.isNotBlank() }.take(12).forEach { token ->
            val columns = token.split('|', limit = 4)
            if (columns.size < 3) return@forEach
            val packageName = columns[0].trim()
            if (!PACKAGE_NAME.matches(packageName)) return@forEach
            result.put(
                JSONObject()
                    .put("packageName", packageName)
                    .put("bytes", columns[1].toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
                    .put("files", columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
                    .put("category", columns.getOrNull(3)?.trim().orEmpty().take(80))
            )
        }
        return result
    }

    private fun clearTaskHistoryJson(): String = runCatching {''',
)
replace_once(
    service,
    '''        history.appendText("$timestamp\\t$mode\\t$bytes\\t$files\\t$emptyDirs\\t$errors\\t$result\\tapp-native\\n")''',
    '''        val categorySummary = input.optString("categorySummary")
            .replace('\\t', ' ').replace('\\n', ' ').replace('\\r', ' ').take(1000)
        val appSummary = input.optString("appSummary")
            .replace('\\t', ' ').replace('\\n', ' ').replace('\\r', ' ').take(1000)
        history.appendText("$timestamp\\t$mode\\t$bytes\\t$files\\t$emptyDirs\\t$errors\\t$result\\tapp-native\\t$categorySummary\\t$appSummary\\n")''',
)

ui = "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
replace_once(
    ui,
    '''data class HistoryUiItem(
    val title: String,
    val time: String,
    val trigger: String,
    val result: String,
    val bytes: Long,
    val files: Int,
    val emptyDirs: Int,
    val errors: Int,
    val cleaned: Boolean
)''',
    '''data class HistoryUiItem(
    val title: String,
    val time: String,
    val trigger: String,
    val result: String,
    val bytes: Long,
    val files: Int,
    val emptyDirs: Int,
    val errors: Int,
    val cleaned: Boolean,
    val categories: List<HistoryCategoryUiItem> = emptyList(),
    val apps: List<HistoryAppUiItem> = emptyList()
)

data class HistoryCategoryUiItem(
    val name: String,
    val bytes: Long,
    val files: Long
)

data class HistoryAppUiItem(
    val packageName: String,
    val label: String,
    val category: String,
    val bytes: Long,
    val files: Long
)''',
)
replace_once(
    ui,
    '''@Composable
private fun HistoryCard(item: HistoryUiItem) {
    val context = LocalContext.current
    ResultSurface(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        contentPadding = PaddingValues(17.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(50.dp).clip(RoundedCornerShape(16.dp)).background(if (item.errors > 0) Color(0xFFFFC8C8).copy(.45f) else MaterialTheme.colorScheme.primary.copy(.13f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (item.errors > 0) Icons.Rounded.ErrorOutline else Icons.Rounded.CleaningServices, null, tint = if (item.errors > 0) Color(0xFFC43743) else MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("${item.time} · ${item.trigger}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                Text("${item.files} 个项目 · 空目录 ${item.emptyDirs} · 异常 ${item.errors}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Formatter.formatFileSize(context, item.bytes), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(if (item.cleaned) "已清理" else "仅扫描", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}''',
    '''@Composable
private fun HistoryCard(item: HistoryUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(item.time, item.title) { mutableStateOf(false) }
    val hasDetails = item.categories.isNotEmpty() || item.apps.isNotEmpty()
    val categorySummary = item.categories.take(3).joinToString(" · ") {
        "${it.name} ${Formatter.formatFileSize(context, it.bytes)}"
    }
    val appSummary = item.apps.take(2).joinToString(" · ") {
        "${it.label} ${Formatter.formatFileSize(context, it.bytes)}"
    }
    ResultSurface(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth()
            .clickable(enabled = hasDetails) { expanded = !expanded },
        shape = RoundedCornerShape(26.dp),
        contentPadding = PaddingValues(17.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(50.dp).clip(RoundedCornerShape(16.dp)).background(if (item.errors > 0) Color(0xFFFFC8C8).copy(.45f) else MaterialTheme.colorScheme.primary.copy(.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (item.errors > 0) Icons.Rounded.ErrorOutline else Icons.Rounded.CleaningServices, null, tint = if (item.errors > 0) Color(0xFFC43743) else MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${item.time} · ${item.trigger}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    Text(
                        when {
                            categorySummary.isNotBlank() -> categorySummary
                            item.bytes == 0L && item.files == 0 -> if (item.cleaned) "未发现可清理内容" else "扫描未发现垃圾"
                            else -> item.result
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (appSummary.isNotBlank()) {
                        Text(appSummary, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(Formatter.formatFileSize(context, item.bytes), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            item.errors > 0 -> "异常 ${item.errors}"
                            item.cleaned && item.bytes > 0 -> "已清理"
                            item.cleaned -> "无垃圾"
                            item.files > 0 -> "发现 ${item.files} 项"
                            else -> "未发现"
                        },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (expanded && hasDetails) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(.08f))
                if (item.categories.isNotEmpty()) {
                    Text("垃圾分类", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    item.categories.forEach { detail ->
                        Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(detail.name, modifier = Modifier.weight(1f), fontSize = 11.sp)
                            Text("${detail.files} 项 · ${Formatter.formatFileSize(context, detail.bytes)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (item.apps.isNotEmpty()) {
                    Text("涉及应用", modifier = Modifier.padding(top = 12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    item.apps.forEach { app ->
                        Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(app.category.ifBlank { app.packageName }, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${app.files} 项 · ${Formatter.formatFileSize(context, app.bytes)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}''',
)
replace_once(ui, '原生清理引擎 · Alpha 31', '原生清理引擎 · Alpha 32')

activity = "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
replace_once(
    activity,
    '''                            cleaned = item.optBoolean("cleaned")
                        )''',
    '''                            cleaned = item.optBoolean("cleaned"),
                            categories = parseHistoryCategories(item.optJSONArray("categoryDetails")),
                            apps = parseHistoryApps(item.optJSONArray("appDetails"))
                        )''',
)
replace_once(
    activity,
    '''    private fun refreshWhitelist() {''',
    '''    private fun parseHistoryCategories(array: JSONArray?): List<HistoryCategoryUiItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            if (name.isBlank()) continue
            add(HistoryCategoryUiItem(name, item.optLong("bytes", 0L).coerceAtLeast(0L), item.optLong("files", 0L).coerceAtLeast(0L)))
        }
    }.sortedByDescending { it.bytes }

    private fun parseHistoryApps(array: JSONArray?): List<HistoryAppUiItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val packageName = item.optString("packageName").trim()
            if (!looksLikePackageName(packageName)) continue
            add(
                HistoryAppUiItem(
                    packageName = packageName,
                    label = appLabel(packageName),
                    category = item.optString("category").trim(),
                    bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                    files = item.optLong("files", 0L).coerceAtLeast(0L)
                )
            )
        }
    }.sortedByDescending { it.bytes }

    private fun refreshWhitelist() {''',
)
replace_once(
    activity,
    '''        "corpse-scan", "corpse-clean" -> "卸载残留清理"
        else -> "白泽清理任务"''',
    '''        "corpse-scan" -> "卸载残留扫描"
        "corpse-clean" -> "卸载残留清理"
        "apk-scan" -> "安装包扫描"
        "apk-clean" -> "安装包清理"
        "profile-scan" -> "分类垃圾扫描"
        "profile-clean" -> "分类垃圾清理"
        else -> if (mode.isBlank()) "未知清理任务" else mode.replace('-', ' ').replaceFirstChar { it.uppercase() }''',
)
replace_once(
    activity,
    '''                                .put("result", resultLine)
                                .toString()''',
    '''                                .put("result", resultLine)
                                .put(
                                    "categorySummary",
                                    buildList {
                                        if (deletedFiles > 0) add("扫描快照|$deletedBytes|$deletedFiles")
                                        if (emptyFiles > 0) add("空文件|0|$emptyFiles")
                                        if (emptyDirs > 0) add("空目录|0|$emptyDirs")
                                        if (fragments > 0) add("残留碎片|0|$fragments")
                                    }.joinToString(";")
                                )
                                .toString()''',
)

for path, pairs in {
    "v2/app/build.gradle.kts": [("versionCode = 21100", "versionCode = 21200"), ('versionName = "2.0.0-alpha31"', 'versionName = "2.0.0-alpha32"')],
    "v2/module/module.prop": [("version=v2.0.0-alpha31", "version=v2.0.0-alpha32"), ("versionCode=21100", "versionCode=21200")],
    "v2/module/customize.sh": [("白泽 v2 Alpha 31", "白泽 v2 Alpha 32")],
    "v2/scripts/package-module.sh": [("BaiZe-v2-Alpha31-Module.zip", "BaiZe-v2-Alpha32-Module.zip"), ("Alpha 31", "Alpha 32")],
}.items():
    for old, new in pairs:
        replace_once(path, old, new)
