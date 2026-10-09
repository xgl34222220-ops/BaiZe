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
