package io.github.xgl34222220.baize

import org.json.JSONArray
import org.json.JSONObject

/** 微信 / QQ / TIM 应用私有目录里的一个白名单媒体文件夹（Root 目录级统计）。 */
internal data class ChatPrivateFolder(
    val path: String,
    val app: String,
    val packageName: String,
    val kind: String,
    val folder: String,
    val account: String,
    val device: Long,
    val inode: Long,
    val files: Long,
    val bytes: Long,
    val protectedFiles: Long,
    /** 与隔离区同一文件系统，可移入隔离区恢复；否则只能在确认后永久删除。 */
    val recoverable: Boolean,
    /** 天数 → (文件数, 字节)。 */
    val ages: Map<Int, Pair<Long, Long>>
) {
    fun filesOlder(days: Int): Long = if (days <= 0) files else ages[days]?.first ?: 0L
    fun bytesOlder(days: Int): Long = if (days <= 0) bytes else ages[days]?.second ?: 0L
    val title: String get() = "$kind · $folder" + if (account.isNotBlank()) " · 账号 $account" else ""
}

/** 一次清理的预估：先进隔离区（有上限），其余（超上限或无法同分区移动）需确认永久删除。 */
internal data class ChatPrivatePlan(val recoverableFiles: Long, val recoverableBytes: Long, val permanentFiles: Long, val permanentBytes: Long) {
    val totalFiles: Long get() = recoverableFiles + permanentFiles
    val totalBytes: Long get() = recoverableBytes + permanentBytes
}

internal data class ChatPrivateState(
    val scanning: Boolean = false,
    val cleaning: Boolean = false,
    val scanned: Boolean = false,
    val folders: List<ChatPrivateFolder> = emptyList(),
    val selected: Set<String> = emptySet(),
    val olderThanDays: Int = 30,
    val scannedAt: Long = 0L,
    val truncated: Boolean = false,
    val entriesLeft: Int = 0,
    val bytesLeft: Long = 0L,
    val message: String = "",
    val confirmRequested: Boolean = false,
    val error: Boolean = false
) {
    val busy: Boolean get() = scanning || cleaning
    val apps: List<String> get() = folders.map { it.app }.distinct()
    val selectedFolders: List<ChatPrivateFolder> get() = folders.filter { it.path in selected && it.filesOlder(olderThanDays) > 0 }
    val selectedFiles: Long get() = selectedFolders.sumOf { it.filesOlder(olderThanDays) }
    val selectedBytes: Long get() = selectedFolders.sumOf { it.bytesOlder(olderThanDays) }
    val plan: ChatPrivatePlan get() = ChatPrivateMedia.plan(selectedFolders, olderThanDays, entriesLeft, bytesLeft)

    fun toggle(path: String): ChatPrivateState = copy(selected = if (path in selected) selected - path else selected + path)

    /** 分组勾选：一个应用的全部文件夹；已全选则全部取消。 */
    fun toggleApp(app: String): ChatPrivateState {
        val paths = folders.filter { it.app == app && it.filesOlder(olderThanDays) > 0 }.map { it.path }.toSet()
        if (paths.isEmpty()) return this
        return copy(selected = if (selected.containsAll(paths)) selected - paths else selected + paths)
    }
}

internal object ChatPrivateMedia {
    val AGE_OPTIONS = listOf(7, 30, 90, 180)
    const val KEEP_TEXT_COPY = "保留文字聊天记录，只删除图片/视频/语音/文件"

    fun parse(raw: String): ChatPrivateState {
        val json = JSONObject(raw)
        if (!json.optBoolean("success")) return ChatPrivateState(scanned = true, error = true,
            message = "应用私有数据读取失败：" + json.optString("message", json.optString("error", "未知原因")))
        val items = json.optJSONArray("folders") ?: JSONArray()
        val folders = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val path = item.optString("path")
            if (!path.startsWith("/data/")) return@mapNotNull null
            val agesJson = item.optJSONObject("ages") ?: JSONObject()
            val ages = AGE_OPTIONS.associateWith { days ->
                agesJson.optJSONObject(days.toString())?.let { it.optLong("files") to it.optLong("bytes") } ?: (0L to 0L)
            }
            ChatPrivateFolder(path, item.optString("app"), item.optString("package"), item.optString("kind"), item.optString("folder"),
                item.optString("account"), item.optLong("device", -1L), item.optLong("inode", -1L), item.optLong("files"),
                item.optLong("bytes"), item.optLong("protectedFiles"), item.optBoolean("recoverable"), ages)
        }
        return ChatPrivateState(scanned = true, folders = folders, scannedAt = json.optLong("now"), truncated = json.optBoolean("truncated"),
            entriesLeft = json.optInt("quarantineEntriesLeft"), bytesLeft = json.optLong("quarantineBytesLeft"))
    }

    /** 预估：可恢复文件夹按顺序占用隔离区剩余额度（按平均大小估算字节），其余计为永久删除。 */
    fun plan(folders: List<ChatPrivateFolder>, days: Int, entriesLeft: Int, bytesLeft: Long): ChatPrivatePlan {
        var entries = entriesLeft.toLong().coerceAtLeast(0L)
        var bytes = bytesLeft.coerceAtLeast(0L)
        var recoverableFiles = 0L; var recoverableBytes = 0L; var permanentFiles = 0L; var permanentBytes = 0L
        for (folder in folders) {
            val files = folder.filesOlder(days)
            val size = folder.bytesOlder(days)
            if (files <= 0L) continue
            var fit = 0L
            if (folder.recoverable && entries > 0L) {
                val average = (size / files).coerceAtLeast(1L)
                fit = minOf(files, entries, bytes / average)
            }
            val fitBytes = if (fit >= files) size else (size / files) * fit
            recoverableFiles += fit; recoverableBytes += fitBytes
            permanentFiles += files - fit; permanentBytes += size - fitBytes
            entries -= fit; bytes = (bytes - fitBytes).coerceAtLeast(0L)
        }
        return ChatPrivatePlan(recoverableFiles, recoverableBytes, permanentFiles, permanentBytes)
    }

    fun cleanRequest(state: ChatPrivateState, allowPermanent: Boolean): JSONObject = JSONObject()
        .put("folders", JSONArray().apply {
            state.selectedFolders.forEach { put(JSONObject().put("path", it.path).put("device", it.device).put("inode", it.inode)) }
        })
        .put("scannedAt", state.scannedAt)
        .put("olderThanDays", state.olderThanDays)
        .put("allowPermanent", allowPermanent)

    data class CleanTotals(var quarantined: Long = 0L, var quarantinedBytes: Long = 0L, var deleted: Long = 0L, var deletedBytes: Long = 0L,
                           var kept: Long = 0L, var keptBytes: Long = 0L, val reasons: LinkedHashMap<String, Int> = LinkedHashMap(),
                           val folderErrors: MutableList<String> = mutableListOf())

    /** 合并一次 Root 返回；返回本次是否因时间预算截断（需要继续）。 */
    fun accumulate(totals: CleanTotals, raw: String): Boolean {
        val json = JSONObject(raw)
        if (!json.optBoolean("success")) {
            totals.folderErrors += json.optString("message", json.optString("error", "Root 处理失败"))
            return false
        }
        totals.quarantined += json.optLong("quarantined"); totals.quarantinedBytes += json.optLong("quarantinedBytes")
        totals.deleted += json.optLong("deleted"); totals.deletedBytes += json.optLong("deletedBytes")
        // 每轮都会重新遇到仍保留的文件，保留数取最近一轮，避免重复累计。
        totals.kept = json.optLong("kept"); totals.keptBytes = json.optLong("keptBytes")
        totals.reasons.clear()
        json.optJSONArray("reasons")?.let { reasons ->
            for (index in 0 until reasons.length()) reasons.optJSONObject(index)?.let {
                val key = it.optString("reason"); totals.reasons[key] = (totals.reasons[key] ?: 0) + it.optInt("count")
            }
        }
        json.optJSONArray("folderErrors")?.let { errors ->
            for (index in 0 until errors.length()) errors.optJSONObject(index)?.let { totals.folderErrors += it.optString("reason") }
        }
        return json.optBoolean("truncated")
    }

    fun summary(totals: CleanTotals, format: (Long) -> String): String = buildString {
        append("已移入隔离区 ${totals.quarantined} 个（${format(totals.quarantinedBytes)}，可在回收站恢复）")
        if (totals.deleted > 0) append("；永久删除 ${totals.deleted} 个（${format(totals.deletedBytes)}）")
        if (totals.kept > 0) {
            append("；保留 ${totals.kept} 个（${format(totals.keptBytes)}）")
            totals.reasons.entries.maxByOrNull { it.value }?.let { append("，主要原因：${it.key}") }
        }
        if (totals.folderErrors.isNotEmpty()) append("。${totals.folderErrors.distinct().take(2).joinToString("；")}")
        append("。文字聊天记录未改动。")
    }
}
