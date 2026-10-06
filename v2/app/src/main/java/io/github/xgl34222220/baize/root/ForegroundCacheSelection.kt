package io.github.xgl34222220.baize.root

import org.json.JSONObject

/** Only server-owned candidates from this exact App snapshot can authorize deletion. */
internal fun selectForegroundCacheSnapshot(
    snapshot: ForegroundCacheEngine.Snapshot,
    selectionJson: String
): ForegroundCacheEngine.Snapshot {
    val selection = JSONObject(selectionJson)
    val known = snapshot.items.mapTo(hashSetOf()) { it.path }
    val keys = selection.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        require(key == "__all_safe__" || key in known) { "勾选项目不属于当前缓存快照，请重新扫描" }
        require(selection.opt(key) is Boolean) { "缓存勾选格式无效" }
    }
    val all = selection.opt("__all_safe__") == true
    val selected = snapshot.items.filter { all || selection.opt(it.path) == true }
    require(selected.isNotEmpty()) { "没有明确勾选任何应用缓存项目" }
    return snapshot.copy(items = selected, totalBytes = selected.sumOf { it.bytes },
        totalFiles = selected.sumOf { it.files }, visitedDirs = selected.sumOf { it.directories },
        totalRoots = selected.size, scannedRoots = selected.size, incompleteRoots = selected.count { !it.complete })
}
