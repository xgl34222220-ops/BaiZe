package io.github.xgl34222220.baize

internal data class WorkbenchGroup(
    val key: String,
    val title: String,
    val items: List<WorkbenchItem>,
    val bytes: Long,
    val selectedCount: Int,
    val selectableCount: Int,
    val bulkSelectedCount: Int
)

internal sealed interface WorkbenchRow {
    val key: String
    data class Group(val group: WorkbenchGroup) : WorkbenchRow {
        override val key: String = "group:${group.key}"
    }
    data class Candidate(val item: WorkbenchItem) : WorkbenchRow {
        override val key: String = "item:${item.id}"
    }
}

internal data class WorkbenchPresentation(
    val groups: List<WorkbenchGroup> = emptyList(),
    val rows: List<WorkbenchRow> = emptyList(),
    val selectedBytes: Long = 0L,
    val selectableCount: Int = 0,
    val appCount: Int = 0,
    val profileCount: Int = 0,
    val protectedCount: Int = 0,
    val visibleCount: Int = 0,
    val hiddenSelectedCount: Int = 0
)

internal fun workbenchPresentation(
    items: List<WorkbenchItem>, selectedIds: Set<String>, filter: String,
    expandedGroups: Set<String>, loading: Boolean, query: String = ""
): WorkbenchPresentation {
    val filtered = items.filter { item ->
        val matchesQuery = query.isBlank() || listOf(item.appName, item.packageName, item.title, item.path, item.category)
            .any { it.contains(query.trim(), ignoreCase = true) }
        matchesQuery && when (filter) {
            "low" -> item.risk == "low"
            "selected" -> item.id in selectedIds
            "medium" -> item.risk == "medium"
            "high" -> item.risk == "high"
            "unfinished" -> item.outcome.isNotBlank() && item.outcome != "未勾选，保留" &&
                item.outcome !in setOf("已清理", "已按所选缓存执行清理")
            "unselected" -> item.id !in selectedIds && !item.outcome.startsWith("已清理") && !item.outcome.startsWith("已按所选")
            "blocked" -> !item.selectable
            "deep" -> item.profile == "deep"
            "cache" -> item.source == "cache"
            "empty" -> item.profile == "empty" || item.category.startsWith("empty")
            "rules" -> item.profile == "rules" || item.category.contains("rule") || item.category.contains("trash")
            "fragments" -> item.profile == "fragments" || item.category.contains("fragment")
            else -> true
        }
    }
    val groups = filtered.groupBy { it.groupKey }.map { (key, entries) ->
        WorkbenchGroup(key, entries.first().groupTitle, entries,
            entries.sumOf { it.bytes.coerceAtLeast(0L) },
            entries.count { it.id in selectedIds },
            entries.count { it.selectable && (it.risk == "low" || it.risk == "medium") },
            entries.count { it.selectable && it.id in selectedIds && it.risk in setOf("low", "medium") })
    }.let { if (loading) it else it.sortedByDescending { group -> group.bytes } }
    val rows = buildList<WorkbenchRow> {
        groups.forEach { group ->
            add(WorkbenchRow.Group(group))
            if (group.key in expandedGroups) group.items.forEach { add(WorkbenchRow.Candidate(it)) }
        }
    }
    return WorkbenchPresentation(groups, rows,
        items.sumOf { if (it.id in selectedIds) it.bytes.coerceAtLeast(0L) else 0L },
        items.count { it.selectable },
        items.asSequence().map { it.packageName }.filter { it.isNotBlank() }.distinct().count(),
        items.count { it.source == "profile" }, items.count { !it.selectable },
        filtered.size, items.count { it.id in selectedIds && it.selectable } - filtered.count { it.id in selectedIds && it.selectable })
}
