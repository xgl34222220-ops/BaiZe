package io.github.xgl34222220.baize

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 滑动整理（参考 SD Maid SE Swiper）：逐张卡片，左滑删除、右滑保留。
 * 决定只记录在会话中，可随时撤销；确认后删除项逐个移入已有的普通文件回收站，
 * 批次仍可整体撤销（从回收站恢复）。
 */
internal enum class SwipeDecision { DELETE, KEEP }

internal enum class SwipeFolder(val label: String, val relativePath: String) {
    CAMERA("相机照片", "DCIM/Camera"),
    DOWNLOAD("下载", "Download")
}

internal data class SwipeItem(val path: String, val name: String, val bytes: Long, val modifiedMs: Long) {
    val kind: String get() = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "dng" -> "image"
        "mp4", "mkv", "webm", "3gp", "mov", "avi" -> "video"
        "apk", "apks", "xapk" -> "apk"
        "zip", "rar", "7z", "tar", "gz" -> "archive"
        else -> "file"
    }
}

internal data class SwipeReviewSession(
    val items: List<SwipeItem> = emptyList(),
    val decisions: List<SwipeDecision> = emptyList()
) {
    val position: Int get() = decisions.size
    val current: SwipeItem? get() = items.getOrNull(position)
    val next: SwipeItem? get() = items.getOrNull(position + 1)
    val finished: Boolean get() = items.isNotEmpty() && position >= items.size
    val canUndo: Boolean get() = decisions.isNotEmpty()
    val deletions: List<SwipeItem> get() = items.zip(decisions).filter { it.second == SwipeDecision.DELETE }.map { it.first }
    val keptCount: Int get() = decisions.count { it == SwipeDecision.KEEP }
    val deleteBytes: Long get() = deletions.sumOf { it.bytes }

    fun decide(decision: SwipeDecision): SwipeReviewSession =
        if (current == null) this else copy(decisions = decisions + decision)

    fun undo(): SwipeReviewSession = if (decisions.isEmpty()) this else copy(decisions = decisions.dropLast(1))

    /** 已应用的删除项从会话移除，其余决定保留，可继续整理。 */
    fun withoutApplied(applied: Set<String>): SwipeReviewSession {
        if (applied.isEmpty()) return this
        val reviewed = items.zip(decisions).filterNot { it.first.path in applied }
        val pending = items.drop(decisions.size).filterNot { it.path in applied }
        return SwipeReviewSession(reviewed.map { it.first } + pending, reviewed.map { it.second })
    }
}

internal object SwipeReviewSource {
    const val MAX_ITEMS = 2_000

    /** 只读列出目录第一层的普通文件：不跟随链接、不进入子目录、跳过隐藏文件与回收站自身。 */
    fun list(directory: File, limit: Int = MAX_ITEMS, cancelled: () -> Boolean = { false }): List<SwipeItem> {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return emptyList()
        val files = directory.listFiles().orEmpty()
        val items = ArrayList<SwipeItem>(minOf(files.size, limit))
        for (file in files) {
            if (cancelled()) break
            if (file.name.startsWith(".") || OrdinaryFileTrash.isPayloadPath(file.path)) continue
            val path = file.toPath()
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
            val bytes = file.length()
            if (bytes <= 0L) continue
            items += SwipeItem(file.absolutePath, file.name, bytes, file.lastModified())
        }
        return items.sortedWith(compareByDescending<SwipeItem> { it.modifiedMs }.thenBy { it.name }).take(limit)
    }

    /**
     * 列出尚未看过的文件：先完整列出（只读），清掉该文件夹里失效的「已看过」记录，再排除仍有效的记录。
     * 返回（待整理文件, 该文件夹仍有效的已看过数量）。上限在排除之后再截取，看过的文件不占名额。
     */
    fun listUnseen(directory: File, memory: SwipeReviewMemory, limit: Int = MAX_ITEMS,
        cancelled: () -> Boolean = { false }): Pair<List<SwipeItem>, Int> {
        val all = list(directory, Int.MAX_VALUE, cancelled)
        val seen = memory.prune(directory.absolutePath, all)
        return all.filterNot(memory::isHandled).take(limit) to seen
    }
}

internal data class SwipeApplyResult(val moved: List<TrashEntry>, val skipped: List<Pair<SwipeItem, String>>) {
    val movedPaths: Set<String> get() = moved.map { it.original }.toSet()
    val movedBytes: Long get() = moved.sumOf { it.bytes }
}

internal object SwipeReviewApplier {
    /**
     * 每个文件在移动前重新核对大小与修改时间；变化的文件保留并跳过。
     * 不做永久删除，所有移出都经由回收站。
     */
    fun apply(
        items: List<SwipeItem>,
        digest: (File) -> String,
        move: (File, SwipeItem, String, () -> Boolean) -> TrashEntry,
        cancelled: () -> Boolean = { false },
        progress: (Int, SwipeItem) -> Unit = { _, _ -> }
    ): SwipeApplyResult {
        val moved = mutableListOf<TrashEntry>()
        val skipped = mutableListOf<Pair<SwipeItem, String>>()
        items.forEachIndexed { index, item ->
            if (cancelled()) { skipped += item to "已停止，文件保留"; return@forEachIndexed }
            progress(index, item)
            val file = File(item.path)
            fun unchanged() = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                file.length() == item.bytes && file.lastModified() == item.modifiedMs
            if (!unchanged()) { skipped += item to "文件已变化或不存在，已保留"; return@forEachIndexed }
            try {
                val hash = digest(file)
                moved += move(file, item, hash) { !cancelled() && unchanged() }
            } catch (error: Exception) {
                skipped += item to (error.message ?: "移动失败，文件保留")
            }
        }
        return SwipeApplyResult(moved, skipped)
    }
}

/**
 * 滑动整理「已看过」记录：跨会话保存保留（KEEP）与已移入回收站（DELETE）的决定，下次进入时排除。
 *
 * - 以文件身份（路径 + 大小 + 修改时间）判断：文件被改写后视为新文件，会重新出现。
 * - 只记录决定，不移动、不删除任何文件；删除仍只经由「移入回收站」确认流程。
 * - 有上限（[maxEntries]，超出时丢弃最早的记录）；读取文件夹时清掉该文件夹里已不存在或已变化的记录。
 * - 未确认的「待删除」标记不写入：未移入回收站的文件下次仍会出现，避免把没处理的文件藏起来。
 */
internal class SwipeReviewMemory(private val file: File, private val maxEntries: Int = MAX_ENTRIES) {
    internal data class Seen(val path: String, val bytes: Long, val modifiedMs: Long, val decision: SwipeDecision, val atMs: Long)

    private val entries = LinkedHashMap<String, Seen>()
    private var loaded = false

    @Synchronized fun isHandled(item: SwipeItem): Boolean {
        ensureLoaded()
        val seen = entries[item.path] ?: return false
        return seen.bytes == item.bytes && seen.modifiedMs == item.modifiedMs
    }

    @Synchronized fun remember(items: Collection<SwipeItem>, decision: SwipeDecision, now: Long = System.currentTimeMillis()) {
        ensureLoaded()
        var changed = false
        for (item in items) {
            if (!storable(item.path)) continue
            entries.remove(item.path)
            entries[item.path] = Seen(item.path, item.bytes, item.modifiedMs, decision, now)
            changed = true
        }
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
        if (changed) persist()
    }

    @Synchronized fun forget(paths: Collection<String>) {
        ensureLoaded()
        if (paths.count { entries.remove(it) != null } > 0) persist()
    }

    /** 读取文件夹后调用：清掉该文件夹（第一层）中已不存在或已变化的记录，返回仍有效的记录数。 */
    @Synchronized fun prune(directory: String, listed: Collection<SwipeItem>): Int {
        ensureLoaded()
        val present = listed.associateBy { it.path }
        val stale = entries.values.filter { seen ->
            parentOf(seen.path) == directory &&
                present[seen.path].let { it == null || it.bytes != seen.bytes || it.modifiedMs != seen.modifiedMs }
        }.map { it.path }
        stale.forEach { entries.remove(it) }
        if (stale.isNotEmpty()) persist()
        return count(directory)
    }

    @Synchronized fun count(directory: String): Int {
        ensureLoaded()
        return entries.values.count { parentOf(it.path) == directory }
    }

    /** 重置某个文件夹的「已看过」：只清记录，不改动任何文件。 */
    @Synchronized fun reset(directory: String): Int {
        ensureLoaded()
        val removed = entries.values.filter { parentOf(it.path) == directory }.map { it.path }
        removed.forEach { entries.remove(it) }
        if (removed.isNotEmpty()) persist()
        return removed.size
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        runCatching {
            file.readLines().forEach { line ->
                val parts = line.split('\t', limit = 5)
                if (parts.size != 5) return@forEach
                val decision = runCatching { SwipeDecision.valueOf(parts[0]) }.getOrNull() ?: return@forEach
                val bytes = parts[1].toLongOrNull() ?: return@forEach
                val modified = parts[2].toLongOrNull() ?: return@forEach
                val at = parts[3].toLongOrNull() ?: return@forEach
                if (!storable(parts[4])) return@forEach
                entries.remove(parts[4])
                entries[parts[4]] = Seen(parts[4], bytes, modified, decision, at)
            }
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
        }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(entries.values.joinToString("") { "${it.decision.name}\t${it.bytes}\t${it.modifiedMs}\t${it.atMs}\t${it.path}\n" })
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        }
    }

    private fun storable(path: String) = path.isNotBlank() && '\n' !in path && '\t' !in path && '\r' !in path

    companion object {
        const val MAX_ENTRIES = 5_000
        const val FILE_NAME = "swipe-review-seen.tsv"
        fun parentOf(path: String): String = path.substringBeforeLast('/', "")
    }
}
