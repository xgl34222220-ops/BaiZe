#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Alpha 29 step 3 target not found: {label}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


ui = Path("v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt")
replace_once(
    ui,
    '''data class AppJunkUiItem(
    val packageName: String,
    val label: String,
    val category: String,
    val files: Long,
    val bytes: Long
)
''',
    '''data class AppJunkUiItem(
    val packageName: String,
    val label: String,
    val category: String,
    val files: Long,
    val bytes: Long,
    val errors: Long = 0,
    val categories: List<AppJunkCategoryUiItem> = emptyList()
)

data class AppJunkCategoryUiItem(
    val name: String,
    val files: Long,
    val bytes: Long,
    val errors: Long,
    val samplePath: String
)
''',
    "App junk data classes",
)
replace_once(
    ui,
    '''@Composable
private fun AppJunkCard(item: AppJunkUiItem) {
    val context = LocalContext.current
    GlassSurface(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        shadow = 5,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ApplicationIcon(
                packageName = item.packageName,
                label = item.label,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(item.label, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${item.category} · ${item.files} 个文件", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(Formatter.formatFileSize(context, item.bytes), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
        }
    }
}
''',
    '''@Composable
private fun AppJunkCard(item: AppJunkUiItem) {
    GlassSurface(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        shadow = 5,
        contentPadding = PaddingValues(16.dp)
    ) {
        AppJunkCardContent(item)
    }
}
''',
    "App junk card content",
)

activity = Path("v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt")
replace_once(
    activity,
    '''    private fun parseAppDetails(array: JSONArray?): List<AppJunkUiItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val packageName = item.optString("packageName").trim()
            if (!looksLikePackageName(packageName)) continue
            add(
                AppJunkUiItem(
                    packageName = packageName,
                    label = appLabel(packageName),
                    category = item.optString("category").ifBlank { "应用缓存" },
                    files = item.optLong("files", 0L).coerceAtLeast(0L),
                    bytes = item.optLong("bytes", 0L).coerceAtLeast(0L)
                )
            )
        }
    }.sortedByDescending { it.bytes }
''',
    '''    private fun parseAppDetails(array: JSONArray?): List<AppJunkUiItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val packageName = item.optString("packageName").trim()
            if (!looksLikePackageName(packageName)) continue
            val categoryArray = item.optJSONArray("categories")
            val categories = buildList {
                if (categoryArray != null) for (categoryIndex in 0 until categoryArray.length()) {
                    val category = categoryArray.optJSONObject(categoryIndex) ?: continue
                    add(
                        AppJunkCategoryUiItem(
                            name = category.optString("name").ifBlank { "应用缓存" },
                            files = category.optLong("files", 0L).coerceAtLeast(0L),
                            bytes = category.optLong("bytes", 0L).coerceAtLeast(0L),
                            errors = category.optLong("errors", 0L).coerceAtLeast(0L),
                            samplePath = category.optString("samplePath").trim()
                        )
                    )
                }
            }.sortedByDescending { it.bytes }
            add(
                AppJunkUiItem(
                    packageName = packageName,
                    label = appLabel(packageName),
                    category = item.optString("category").ifBlank { "应用缓存" },
                    files = item.optLong("files", 0L).coerceAtLeast(0L),
                    bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                    errors = item.optLong("errors", 0L).coerceAtLeast(0L),
                    categories = categories
                )
            )
        }
    }.sortedWith(compareByDescending<AppJunkUiItem> { it.bytes }.thenByDescending { it.files })
''',
    "nested app detail parsing",
)

print("Alpha 29 step 3 detail UI migration applied")
