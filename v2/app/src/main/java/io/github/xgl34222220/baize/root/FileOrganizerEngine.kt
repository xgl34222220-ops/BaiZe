package io.github.xgl34222220.baize.root

import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root-side immutable file organizer plan.
 *
 * Binder responses are bounded. Alpha 12 discovers user files from public downloads plus every
 * application's external media and external files roots, so Telegram forks, browsers and download
 * managers do not need package-specific path rules.
 */
class FileOrganizerEngine(
    private val cancelled: AtomicBoolean,
    private val stateDir: File = File("/data/adb/baize-v2")
) {
    data class Progress(
        val phase: String,
        val current: Int = 0,
        val total: Int = 0,
        val path: String = ""
    )

    private enum class SourcePolicy {
        TOP_LEVEL_ONLY,
        FULL_DOWNLOAD_TREE,
        APP_USER_FILES
    }

    private enum class ConflictPolicy {
        SKIP,
        RENAME,
        DEDUPE;

        companion object {
            fun fromJson(json: JSONObject): ConflictPolicy = when {
                json.has("conflictPolicy") -> when (json.optString("conflictPolicy").lowercase()) {
                    "skip", "0" -> SKIP
                    "dedupe", "2" -> DEDUPE
                    else -> RENAME
                }
                else -> RENAME
            }
        }
    }

    private data class SourceRoot(
        val directory: File,
        val group: String,
        val policy: SourcePolicy
    )

    private data class PlannedMove(
        val id: String,
        val source: String,
        val sourceRoot: String,
        val sourceGroup: String,
        val destination: String,
        val category: String,
        val name: String,
        val bytes: Long,
        val fingerprint: String,
        val sourceUid: Int,
        val sourceGid: Int,
        val sourceMode: Int
    )

    private data class Snapshot(
        val id: String,
        val createdAt: Long,
        val roots: Int,
        val truncated: Boolean,
        val items: List<PlannedMove>
    )

    private data class DestinationResolution(
        val destination: File?,
        val reason: String? = null,
        val collisionAction: String = "none"
    )

    private data class UndoRecord(val file: File, val json: JSONObject, val legacy: Boolean = false)

    private data class Selection(
        val all: Boolean,
        val ids: Set<String>,
        val excludedIds: Set<String>,
        val conflictPolicy: ConflictPolicy
    )

    @Volatile
    private var snapshot: Snapshot? = null

    fun scan(progress: (Progress) -> Unit): String {
        cancelled.set(false)
        val started = SystemClock.elapsedRealtime()
        val items = LinkedHashMap<String, PlannedMove>()
        val indexed = collectSharedIndex(started, items, progress)
        val sourceCount: Int
        val coverage: JSONArray
        if (indexed != null) {
            sourceCount = indexed.first
            coverage = indexed.second
        } else {
            val sources = discoverSourceRoots(started, progress)
            if (cancelled.get()) {
                return JSONObject().put("cancelled", true).put("message", "文件归类扫描已停止").toString()
            }
            sourceCount = sources.size
            coverage = JSONArray()
            sources.forEachIndexed { index, source ->
                if (cancelled.get() || items.size >= MAX_ITEMS || elapsed(started) >= SCAN_BUDGET_MS) return@forEachIndexed
                progress(Progress("正在读取兼容来源目录", index + 1, sources.size, displayPath(source.directory.path)))
                collectSource(source, started, items, progress)
            }
        }

        val immutable = items.values.sortedWith(
            compareBy<PlannedMove> { it.category }
                .thenBy { it.sourceGroup }
                .thenBy { it.name.lowercase() }
        )
        val id = UUID.randomUUID().toString()
        val truncated = immutable.size >= MAX_ITEMS || elapsed(started) >= SCAN_BUDGET_MS
        val createdSnapshot = Snapshot(id, System.currentTimeMillis(), sourceCount, truncated, immutable)
        snapshot = createdSnapshot
        persistSnapshot(createdSnapshot)

        val categoryCounts = JSONObject()
        val sourceCounts = JSONObject()
        var totalBytes = 0L
        immutable.forEach { item ->
            categoryCounts.put(item.category, categoryCounts.optInt(item.category) + 1)
            sourceCounts.put(item.sourceGroup, sourceCounts.optInt(item.sourceGroup) + 1)
            totalBytes += item.bytes
        }
        val preview = JSONArray()
        immutable.take(DEFAULT_PAGE_SIZE).forEach { preview.put(itemJson(it)) }

        return JSONObject()
            .put("success", true)
            .put("snapshotId", id)
            .put("expiresInMs", SNAPSHOT_TTL_MS)
            .put("roots", sourceCount)
            .put("coverage", coverage)
            .put("total", immutable.size)
            .put("totalBytes", totalBytes)
            .put("truncated", truncated)
            .put("elapsedMs", elapsed(started))
            .put("pageSize", DEFAULT_PAGE_SIZE)
            .put("categoryCounts", categoryCounts)
            .put("sourceCounts", sourceCounts)
            .put("previewCount", preview.length())
            .put("items", preview)
            .toString()
    }

    fun page(snapshotId: String, offset: Int, limit: Int): String {
        val current = validSnapshot(snapshotId)
            ?: return error("snapshot_expired", "文件归类计划不存在或已过期，请重新执行一键归类")
        val safeOffset = offset.coerceIn(0, current.items.size)
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val end = (safeOffset + safeLimit).coerceAtMost(current.items.size)
        val array = JSONArray()
        current.items.subList(safeOffset, end).forEach { array.put(itemJson(it)) }
        return JSONObject()
            .put("success", true)
            .put("snapshotId", current.id)
            .put("offset", safeOffset)
            .put("nextOffset", end)
            .put("limit", safeLimit)
            .put("total", current.items.size)
            .put("hasMore", end < current.items.size)
            .put("items", array)
            .toString()
    }

    fun apply(snapshotId: String, selectionJson: String, progress: (Progress) -> Unit): String {
        val current = validSnapshot(snapshotId)
            ?: return error("snapshot_expired", "文件归类计划不存在或已过期，请重新执行一键归类")
        val selection = parseSelection(selectionJson)
        val selected = if (selection.all) {
            current.items.filterNot { it.id in selection.excludedIds }
        } else {
            current.items.filter { it.id in selection.ids }
        }
        if (selected.isEmpty()) return error("empty_selection", "没有需要归类的文件")

        val moves = JSONArray()
        val details = JSONArray()
        var detailTruncated = false
        var moved = 0
        var skipped = 0
        var failed = 0
        var deduplicated = 0
        var renamed = 0
        var bytes = 0L

        fun appendDetail(item: PlannedMove, action: String, reason: String) {
            if (details.length() < MAX_RESULT_DETAILS) details.put(detail(item, action, reason))
            else detailTruncated = true
        }

        for ((index, item) in selected.withIndex()) {
            if (cancelled.get()) break
            progress(Progress("正在归类 ${item.category}", index + 1, selected.size, displayPath(item.source)))
            val source = File(item.source)
            val plannedDestination = File(item.destination)
            val reason = validatePlannedMove(item, source, plannedDestination)
            if (reason != null) {
                skipped += 1
                appendDetail(item, "skipped", reason)
                continue
            }
            val resolution = resolveDestination(source, plannedDestination, selection.conflictPolicy)
            val destination = resolution.destination
            if (destination == null) {
                skipped += 1
                if (resolution.collisionAction == "deduplicated") deduplicated += 1
                appendDetail(item, resolution.collisionAction.ifBlank { "skipped" }, resolution.reason ?: "目标文件冲突")
                continue
            }
            if (resolution.collisionAction == "renamed") renamed += 1

            val result = runCatching {
                destination.parentFile?.mkdirs()
                if (!moveVerified(source, destination)) {
                    false
                } else if (!normalizeSharedOwnership(destination)) {
                    moveVerified(destination, source)
                    false
                } else {
                    notifyMediaStore(item.source, destination.path)
                    true
                }
            }
            if (result.getOrDefault(false)) {
                moved += 1
                bytes += item.bytes
                moves.put(
                    JSONObject()
                        .put("source", item.source)
                        .put("destination", destination.path)
                        .put("destinationFingerprint", fingerprint(destination))
                        .put("sourceUid", item.sourceUid)
                        .put("sourceGid", item.sourceGid)
                        .put("sourceMode", item.sourceMode)
                        .put("collisionAction", resolution.collisionAction)
                )
            } else {
                failed += 1
                appendDetail(item, "failed", result.exceptionOrNull()?.message ?: "移动失败")
            }
        }

        if (moves.length() > 0) persistUndo(moves)
        snapshot = null
        deleteSnapshot(current.id)
        return JSONObject()
            .put("success", failed == 0)
            .put("cancelled", cancelled.get())
            .put("requested", selected.size)
            .put("moved", moved)
            .put("skipped", skipped)
            .put("failed", failed)
            .put("deduplicated", deduplicated)
            .put("renamed", renamed)
            .put("bytes", bytes)
            .put("conflictPolicy", selection.conflictPolicy.name.lowercase())
            .put("undoAvailable", undoRecordCount() > 0)
            .put("undoCount", undoRecordCount())
            .put("detailTruncated", detailTruncated)
            .put("details", details)
            .toString()
    }

    fun undo(progress: (Progress) -> Unit): String {
        val record = readUndoRecord()
            ?: return error("undo_missing", "没有可撤销的文件归类记录")
        val moves = record.json.optJSONArray("moves") ?: JSONArray()
        if (moves.length() == 0) return error("undo_missing", "没有可撤销的文件归类记录")

        val remaining = JSONArray()
        val details = JSONArray()
        var detailTruncated = false
        var restored = 0
        var skipped = 0
        var failed = 0
        fun appendDetail(value: JSONObject) {
            if (details.length() < MAX_RESULT_DETAILS) details.put(value) else detailTruncated = true
        }

        for (index in moves.length() - 1 downTo 0) {
            if (cancelled.get()) {
                for (rest in 0..index) moves.optJSONObject(rest)?.let(remaining::put)
                break
            }
            val move = moves.optJSONObject(index) ?: continue
            val sourcePath = pathValue(move, "source")
            val destinationPath = pathValue(move, "destination")
            if (sourcePath.isBlank() || destinationPath.isBlank()) {
                failed += 1
                appendDetail(JSONObject().put("action", "failed").put("reason", "撤销记录路径无效"))
                continue
            }
            val source = File(sourcePath)
            val destination = File(destinationPath)
            progress(Progress("正在撤销文件归类", moves.length() - index, moves.length(), displayPath(destination.path)))

            val expected = move.optString("destinationFingerprint")
            val reason = when {
                source.exists() -> "原位置已存在同名文件"
                !destination.isFile -> "归类后的文件已不存在"
                isSymlink(destination) -> "符号链接不允许撤销"
                expected.isNotBlank() && fingerprint(destination) != expected -> "归类后的文件已发生变化"
                !allowedOrganizerSource(source.path) -> "原路径不再属于允许的归类来源"
                else -> null
            }
            if (reason != null) {
                skipped += 1
                remaining.put(move)
                appendDetail(JSONObject().put("destination", displayPath(destination.path)).put("action", "skipped").put("reason", reason))
                continue
            }

            val sourceParent = source.parentFile
            if (sourceParent?.isDirectory != true) {
                skipped += 1
                remaining.put(move)
                appendDetail(JSONObject().put("destination", displayPath(destination.path)).put("action", "skipped").put("reason", "原目录已不存在"))
                continue
            }
            val ok = runCatching {
                if (!moveVerified(destination, source)) {
                    false
                } else if (!restoreOriginalMetadata(source, move)) {
                    moveVerified(source, destination)
                    normalizeSharedOwnership(destination)
                    false
                } else {
                    notifyMediaStore(destination.path, source.path)
                    true
                }
            }.getOrDefault(false)
            if (ok) restored += 1 else {
                failed += 1
                remaining.put(move)
                appendDetail(JSONObject().put("destination", displayPath(destination.path)).put("action", "failed").put("reason", "恢复失败"))
            }
        }

        if (remaining.length() == 0) {
            record.file.delete()
            if (record.legacy) undoFile().delete()
        } else {
            persistUndoRecord(record.file, remaining)
        }
        refreshLegacyUndoPointer()
        return JSONObject()
            .put("success", failed == 0)
            .put("cancelled", cancelled.get())
            .put("restored", restored)
            .put("skipped", skipped)
            .put("failed", failed)
            .put("undoAvailable", undoRecordCount() > 0)
            .put("undoCount", undoRecordCount())
            .put("detailTruncated", detailTruncated)
            .put("details", details)
            .toString()
    }

    private fun collectSharedIndex(
        started: Long,
        out: MutableMap<String, PlannedMove>,
        progress: (Progress) -> Unit
    ): Pair<Int, JSONArray>? {
        val script = File("/data/adb/modules/baize_v2/storage-index.sh")
        if (!script.isFile) return null
        progress(Progress("正在建立全应用共享存储索引", 0, 0, displayPath(script.path)))
        val process = runCatching {
            ProcessBuilder("/system/bin/sh", script.path, "ensure", "organizer")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null
        while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
            if (cancelled.get() || elapsed(started) >= SCAN_BUDGET_MS) {
                process.destroy()
                return null
            }
        }
        if (process.exitValue() != 0) return null
        val index = File(stateDir, "index/organizer-files.nul").takeIf { it.isFile }
            ?: File(stateDir, "index/storage-files.nul")
        if (!index.isFile) return null
        val roots = LinkedHashSet<String>()
        var visited = 0
        forEachNulPath(index) { rawPath ->
            if (cancelled.get() || out.size >= MAX_ITEMS || elapsed(started) >= SCAN_BUDGET_MS) return@forEachNulPath false
            val file = File(rawPath)
            if (!file.isFile || isSymlink(file) || skipFile(file)) return@forEachNulPath true
            val descriptor = indexedSource(file.path) ?: return@forEachNulPath true
            if (descriptor.policy == SourcePolicy.APP_USER_FILES && !isAppUserFile(file, canonical(file))) return@forEachNulPath true
            roots += canonical(descriptor.directory)
            val userId = userIdForPath(file.path) ?: return@forEachNulPath true
            addPlannedMove(file, canonical(descriptor.directory), descriptor.group, userId, out)
            visited += 1
            if (visited % 250 == 0) progress(Progress("正在复用共享索引生成归类计划", visited, 0, displayPath(file.path)))
            true
        }
        return roots.size to coverageJson()
    }

    private fun indexedSource(path: String): SourceRoot? {
        val mediaRoot = mediaUserRoot(path) ?: return null
        val relative = canonical(File(path)).removePrefix(canonical(mediaRoot) + "/")
        if (!relative.contains('/')) return SourceRoot(mediaRoot, "内部存储根目录", SourcePolicy.TOP_LEVEL_ONLY)
        val parts = relative.split('/')
        if (parts.size >= 3 && parts[0] == "Android" && parts[1] == "media") {
            val root = File(mediaRoot, "Android/media/${parts[2]}")
            return SourceRoot(root, "${parts[2]} · 应用媒体", SourcePolicy.APP_USER_FILES)
        }
        if (parts.size >= 4 && parts[0] == "Android" && parts[1] == "data") {
            val packageName = parts[2]
            val filesIndex = parts.indexOf("files")
            val root = if (filesIndex == 3) File(mediaRoot, "Android/data/$packageName/files")
            else File(mediaRoot, "Android/data/$packageName")
            return SourceRoot(root, "$packageName · 应用文件", SourcePolicy.APP_USER_FILES)
        }
        if (!allowedIndexedPublicPath(relative)) return null
        val root = File(mediaRoot, parts.first())
        return SourceRoot(root, sourceGroup(root.path), SourcePolicy.FULL_DOWNLOAD_TREE)
    }


    private fun allowedIndexedPublicPath(relative: String): Boolean {
        val normalized = relative.replace('\\', '/').lowercase()
        return when {
            normalized.startsWith("download/") || normalized.startsWith("downloads/") -> true
            normalized.startsWith("documents/") || normalized.startsWith("bluetooth/") -> true
            normalized.startsWith("tencent/qqfile_recv/") || normalized.startsWith("tencent/timfile_recv/") -> true
            normalized.startsWith("telegram/telegram documents/") ||
                normalized.startsWith("telegram/telegram images/") ||
                normalized.startsWith("telegram/telegram video/") ||
                normalized.startsWith("telegram/telegram audio/") ||
                normalized.startsWith("telegram/telegram files/") -> true
            normalized.startsWith("nagram/nagram documents/") ||
                normalized.startsWith("nagram/nagram images/") ||
                normalized.startsWith("nagram/nagram video/") ||
                normalized.startsWith("nagram/nagram audio/") -> true
            normalized.startsWith("nagramx/nagramx documents/") ||
                normalized.startsWith("nagramx/nagramx images/") ||
                normalized.startsWith("nagramx/nagramx video/") ||
                normalized.startsWith("nagramx/nagramx audio/") -> true
            else -> false
        }
    }

    private fun forEachNulPath(file: File, block: (String) -> Boolean) {
        FileInputStream(file).use { input ->
            val buffer = ByteArrayOutputStream(256)
            while (true) {
                val value = input.read()
                if (value < 0) {
                    if (buffer.size() > 0) block(buffer.toString(Charsets.UTF_8.name()))
                    break
                }
                if (value == 0) {
                    val keepGoing = block(buffer.toString(Charsets.UTF_8.name()))
                    buffer.reset()
                    if (!keepGoing) break
                } else if (buffer.size() < 16_384) {
                    buffer.write(value)
                }
            }
        }
    }

    private fun coverageJson(): JSONArray {
        val result = JSONArray()
        val file = File(stateDir, "index/coverage.tsv")
        if (!file.isFile) return result
        file.useLines { lines ->
            lines.drop(1).take(300).forEach { raw ->
                val columns = raw.split('\t', limit = 6)
                if (columns.size < 5) return@forEach
                result.put(JSONObject()
                    .put("status", columns[0])
                    .put("group", columns[1])
                    .put("files", columns[2].toLongOrNull() ?: 0L)
                    .put("bytes", columns[3].toLongOrNull() ?: 0L)
                    .put("path", columns[4])
                    .put("reason", columns.getOrNull(5).orEmpty()))
            }
        }
        return result
    }

    private fun discoverSourceRoots(started: Long, progress: (Progress) -> Unit): List<SourceRoot> {
        val roots = LinkedHashMap<String, SourceRoot>()

        fun add(directory: File, group: String, policy: SourcePolicy) {
            if (!directory.isDirectory || isSymlink(directory)) return
            val path = canonical(directory)
            if (path.contains("/BaiZe归类/") || path.endsWith("/BaiZe归类")) return
            val existing = roots[path]
            if (existing == null || policyPriority(policy) > policyPriority(existing.policy)) {
                roots[path] = SourceRoot(directory, group, policy)
            }
        }

        val mediaRoots = mediaUserRoots()
        mediaRoots.forEach { mediaRoot ->
            add(mediaRoot, "内部存储根目录", SourcePolicy.TOP_LEVEL_ONLY)

            val androidMedia = File(mediaRoot, "Android/media")
            androidMedia.listFiles()
                ?.filter { it.isDirectory && !isSymlink(it) }
                ?.forEach { packageRoot ->
                    add(
                        packageRoot,
                        "${packageRoot.name} · 应用媒体",
                        SourcePolicy.APP_USER_FILES
                    )
                }

            val androidData = File(mediaRoot, "Android/data")
            androidData.listFiles()
                ?.filter { it.isDirectory && !isSymlink(it) }
                ?.forEach { packageRoot ->
                    val filesRoot = File(packageRoot, "files")
                    if (filesRoot.isDirectory) {
                        add(
                            filesRoot,
                            "${packageRoot.name} · 应用文件",
                            SourcePolicy.APP_USER_FILES
                        )
                    }
                }
        }

        discoverNamedDownloadRoots(started, progress).forEach { root ->
            add(root, sourceGroup(root.path), SourcePolicy.FULL_DOWNLOAD_TREE)
        }

        return roots.values.sortedWith(
            compareBy<SourceRoot> { policyPriority(it.policy) }
                .thenBy { canonical(it.directory) }
        )
    }

    private fun discoverNamedDownloadRoots(started: Long, progress: (Progress) -> Unit): List<File> {
        val roots = LinkedHashMap<String, File>()
        val scanBases = mutableListOf<Pair<File, Int>>()
        mediaUserRoots().forEach { scanBases += it to 8 }

        listOf(File("/data/user"), File("/data/user_de")).forEach { base ->
            base.listFiles()
                ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
                ?.forEach { user ->
                    user.listFiles()?.filter(File::isDirectory)?.forEach { packageDir ->
                        scanBases += packageDir to 7
                    }
                }
        }

        var visited = 0
        for ((base, depthLimit) in scanBases) {
            if (cancelled.get() || elapsed(started) >= DISCOVERY_BUDGET_MS) break
            val stack = ArrayDeque<Pair<File, Int>>()
            stack.add(base to 0)
            while (stack.isNotEmpty()) {
                if (cancelled.get() || elapsed(started) >= DISCOVERY_BUDGET_MS) break
                val (dir, depth) = stack.removeLast()
                if (!dir.isDirectory || isSymlink(dir)) continue
                val path = canonical(dir)
                if (path.contains("/BaiZe归类/") || path.endsWith("/BaiZe归类")) continue
                visited += 1
                if (visited % 500 == 0) {
                    progress(Progress("正在发现下载与接收目录", visited, 0, displayPath(path)))
                }
                if (isDownloadDirectoryName(dir.name) && allowedDownloadRoot(path)) {
                    roots.putIfAbsent(path, dir)
                    continue
                }
                if (depth >= depthLimit || shouldPruneDiscovery(path, dir.name)) continue
                dir.listFiles()
                    ?.filter { it.isDirectory && !isSymlink(it) }
                    ?.forEach { stack.add(it to depth + 1) }
            }
        }
        return roots.values.sortedBy { it.path }
    }

    private fun collectSource(
        source: SourceRoot,
        started: Long,
        out: MutableMap<String, PlannedMove>,
        progress: (Progress) -> Unit
    ) {
        when (source.policy) {
            SourcePolicy.TOP_LEVEL_ONLY -> collectTopLevelFiles(source, started, out, progress)
            SourcePolicy.FULL_DOWNLOAD_TREE,
            SourcePolicy.APP_USER_FILES -> collectTreeFiles(source, started, out, progress)
        }
    }

    private fun collectTopLevelFiles(
        source: SourceRoot,
        started: Long,
        out: MutableMap<String, PlannedMove>,
        progress: (Progress) -> Unit
    ) {
        val rootPath = canonical(source.directory)
        val userId = userIdForPath("$rootPath/") ?: return
        val files = source.directory.listFiles()
            ?.filter { it.isFile && !isSymlink(it) }
            .orEmpty()

        files.forEachIndexed { index, file ->
            if (cancelled.get() || out.size >= MAX_ITEMS || elapsed(started) >= SCAN_BUDGET_MS) return
            if (skipFile(file)) return@forEachIndexed
            if (index % 100 == 0) {
                progress(Progress("正在读取内部存储根目录文件", index + 1, files.size, displayPath(file.path)))
            }
            addPlannedMove(file, rootPath, source.group, userId, out)
        }
    }

    private fun collectTreeFiles(
        source: SourceRoot,
        started: Long,
        out: MutableMap<String, PlannedMove>,
        progress: (Progress) -> Unit
    ) {
        val rootPath = canonical(source.directory)
        val userId = userIdForPath(rootPath) ?: return
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.add(source.directory to 0)
        var visited = 0

        while (stack.isNotEmpty() && out.size < MAX_ITEMS) {
            if (cancelled.get() || elapsed(started) >= SCAN_BUDGET_MS) return
            val (file, depth) = stack.removeLast()
            if (!file.exists() || isSymlink(file)) continue
            val path = canonical(file)
            if (!path.startsWith("$rootPath/") && path != rootPath) continue

            if (file.isDirectory) {
                if (depth >= MAX_FILE_DEPTH || shouldPruneSourceDirectory(path, file.name)) continue
                file.listFiles()?.forEach { child -> stack.add(child to depth + 1) }
                continue
            }
            if (!file.isFile || skipFile(file)) continue
            if (source.policy == SourcePolicy.APP_USER_FILES && !isAppUserFile(file, path)) continue

            visited += 1
            if (visited % 200 == 0) {
                progress(Progress("正在建立应用文件归类计划", out.size, MAX_ITEMS, displayPath(path)))
            }
            addPlannedMove(file, rootPath, source.group, userId, out)
        }
    }

    private fun addPlannedMove(
        file: File,
        sourceRoot: String,
        sourceGroup: String,
        userId: Int,
        out: MutableMap<String, PlannedMove>
    ) {
        val path = canonical(file)
        val sourceStat = runCatching { Os.lstat(file.path) }.getOrNull() ?: return
        val statFingerprint = fingerprint(sourceStat)
        val category = category(file.name)
        if (category.isBlank()) return
        val destination = File(File("/data/media/$userId/BaiZe归类"), "$category/${file.name}")
        val id = sha256Text("$path\u0000$statFingerprint")
        out.putIfAbsent(
            id,
            PlannedMove(
                id = id,
                source = path,
                sourceRoot = sourceRoot,
                sourceGroup = sourceGroup,
                destination = destination.path,
                category = category,
                name = file.name,
                bytes = file.length().coerceAtLeast(0L),
                fingerprint = statFingerprint,
                sourceUid = sourceStat.st_uid,
                sourceGid = sourceStat.st_gid,
                sourceMode = sourceStat.st_mode
            )
        )
    }

    private fun resolveDestination(source: File, planned: File, policy: ConflictPolicy): DestinationResolution {
        if (!planned.exists()) return DestinationResolution(planned)
        return when (policy) {
            ConflictPolicy.SKIP -> DestinationResolution(null, "归类目录已有同名文件", "skipped")
            ConflictPolicy.RENAME -> uniqueDestination(planned)?.let { DestinationResolution(it, collisionAction = "renamed") }
                ?: DestinationResolution(null, "无法生成可用的重命名目标", "skipped")
            ConflictPolicy.DEDUPE -> {
                val identical = runCatching {
                    source.length() == planned.length() && sha256(source) == sha256(planned)
                }.getOrDefault(false)
                if (identical) DestinationResolution(null, "目标目录已有内容相同的文件，保留原文件", "deduplicated")
                else uniqueDestination(planned)?.let { DestinationResolution(it, collisionAction = "renamed") }
                    ?: DestinationResolution(null, "同名文件内容不同且无法生成重命名目标", "skipped")
            }
        }
    }

    private fun uniqueDestination(planned: File): File? {
        val parent = planned.parentFile ?: return null
        val name = planned.name
        val extension = name.substringAfterLast('.', "").takeIf { name.contains('.') }
        val stem = if (extension == null) name else name.removeSuffix(".$extension")
        for (index in 1..MAX_COLLISION_RENAMES) {
            val candidateName = if (extension == null) "$stem ($index)" else "$stem ($index).$extension"
            val candidate = File(parent, candidateName)
            if (!candidate.exists()) return candidate
        }
        return null
    }

    private fun validatePlannedMove(item: PlannedMove, source: File, destination: File): String? {
        val sourcePath = canonical(source)
        if (sourcePath != item.source) return "源路径已变化"
        if (!sourcePath.startsWith("${item.sourceRoot}/") && sourcePath != item.sourceRoot) return "源目录已变化"
        if (!source.isFile) return "文件已不存在"
        if (isSymlink(source)) return "符号链接受保护"
        if (!allowedOrganizerSource(sourcePath)) return "源文件不再属于允许的归类来源"
        if (fingerprint(source) != item.fingerprint) return "文件在扫描后发生变化"
        if (!destination.path.startsWith("/data/media/")) return "目标路径超出公共归类目录"
        return null
    }

    private fun moveVerified(source: File, destination: File): Boolean {
        if (destination.exists()) return false
        return try {
            Os.rename(source.path, destination.path)
            true
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EXDEV) throw error
            copyAcrossFilesystems(source, destination)
        }
    }

    private fun copyAcrossFilesystems(source: File, destination: File): Boolean {
        val parent = destination.parentFile ?: return false
        parent.mkdirs()
        val temp = File(parent, ".${destination.name}.baize-${UUID.randomUUID()}.tmp")
        if (temp.exists()) temp.delete()
        try {
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            FileInputStream(source).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        sourceDigest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (source.length() != temp.length()) return false
            val copiedDigest = sha256(temp)
            val originalDigest = sourceDigest.digest().joinToString("") { "%02x".format(it) }
            if (originalDigest != copiedDigest) return false
            if (destination.exists()) return false
            Os.rename(temp.path, destination.path)
            if (!source.delete()) {
                destination.delete()
                return false
            }
            return true
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun validSnapshot(id: String): Snapshot? {
        var current = snapshot
        if (current == null || current.id != id) {
            current = readSnapshot(id)
            snapshot = current
        }
        if (current == null || current.id != id || System.currentTimeMillis() - current.createdAt > SNAPSHOT_TTL_MS) {
            snapshot = null
            deleteSnapshot(id)
            return null
        }
        return current
    }

    private fun parseSelection(raw: String): Selection {
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val configured = configInt("organizer_conflict_policy", 1).coerceIn(0, 2)
        val withDefault = if (json.has("conflictPolicy")) json else JSONObject(json.toString()).put("conflictPolicy", configured)
        return Selection(
            all = json.optBoolean("all", false),
            ids = parseIds(json.optJSONArray("ids")),
            excludedIds = parseIds(json.optJSONArray("excludeIds")),
            conflictPolicy = ConflictPolicy.fromJson(withDefault)
        )
    }

    private fun parseIds(array: JSONArray?): Set<String> {
        val result = LinkedHashSet<String>()
        if (array == null) return result
        for (index in 0 until array.length()) {
            val id = array.optString(index)
            if (id.matches(ID_PATTERN)) result += id
            if (result.size >= MAX_SELECTION_IDS) break
        }
        return result
    }

    private fun persistSnapshot(value: Snapshot) {
        runCatching {
            val directory = snapshotDir().apply { mkdirs() }
            val items = JSONArray()
            value.items.forEach { item ->
                items.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("source", item.source)
                        .put("sourceRoot", item.sourceRoot)
                        .put("sourceGroup", item.sourceGroup)
                        .put("destination", item.destination)
                        .put("category", item.category)
                        .put("name", item.name)
                        .put("bytes", item.bytes)
                        .put("fingerprint", item.fingerprint)
                        .put("sourceUid", item.sourceUid)
                        .put("sourceGid", item.sourceGid)
                        .put("sourceMode", item.sourceMode)
                )
            }
            val json = JSONObject()
                .put("id", value.id)
                .put("createdAt", value.createdAt)
                .put("roots", value.roots)
                .put("truncated", value.truncated)
                .put("items", items)
            RootFileStore.writeAtomic(File(directory, "${value.id}.json"), json.toString())
            pruneFiles(directory, SNAPSHOT_RETENTION)
        }
    }

    private fun readSnapshot(id: String): Snapshot? = runCatching {
        if (!id.matches(Regex("^[A-Za-z0-9-]{16,80}$"))) return@runCatching null
        val json = JSONObject(File(snapshotDir(), "$id.json").readText())
        val array = json.optJSONArray("items") ?: return@runCatching null
        val items = ArrayList<PlannedMove>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            items += PlannedMove(
                id = item.optString("id"),
                source = item.optString("source"),
                sourceRoot = item.optString("sourceRoot"),
                sourceGroup = item.optString("sourceGroup"),
                destination = item.optString("destination"),
                category = item.optString("category"),
                name = item.optString("name"),
                bytes = item.optLong("bytes", 0L),
                fingerprint = item.optString("fingerprint"),
                sourceUid = item.optInt("sourceUid", -1),
                sourceGid = item.optInt("sourceGid", -1),
                sourceMode = item.optInt("sourceMode", -1)
            )
        }
        Snapshot(
            id = json.optString("id"),
            createdAt = json.optLong("createdAt", 0L),
            roots = json.optInt("roots", 0),
            truncated = json.optBoolean("truncated", false),
            items = items
        )
    }.getOrNull()

    private fun deleteSnapshot(id: String) {
        runCatching { File(snapshotDir(), "$id.json").delete() }
    }

    private fun snapshotDir() = File(stateDir, "snapshots/file-organizer")

    private fun persistUndo(moves: JSONArray) {
        stateDir.mkdirs()
        val directory = undoDir().apply { mkdirs() }
        val file = File(directory, "%013d-%s.json".format(System.currentTimeMillis(), UUID.randomUUID().toString().take(8)))
        val json = JSONObject().put("createdAt", System.currentTimeMillis()).put("moves", moves)
        RootFileStore.writeAtomic(file, json.toString())
        RootFileStore.writeAtomic(undoFile(), json.toString())
        pruneFiles(directory, configInt("organizer_undo_retention", DEFAULT_UNDO_RETENTION).coerceIn(1, 20))
    }

    private fun persistUndoRecord(file: File, moves: JSONArray) {
        val json = JSONObject().put("createdAt", System.currentTimeMillis()).put("moves", moves)
        RootFileStore.writeAtomic(file, json.toString())
        RootFileStore.writeAtomic(undoFile(), json.toString())
    }

    private fun readUndoRecord(): UndoRecord? {
        val newest = undoDir().listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.maxByOrNull { it.name }
        if (newest != null) {
            val json = runCatching { JSONObject(newest.readText()) }.getOrNull()
            if (json != null) return UndoRecord(newest, json)
        }
        val legacy = undoFile()
        val json = runCatching { if (legacy.isFile) JSONObject(legacy.readText()) else null }.getOrNull()
        return json?.let { UndoRecord(legacy, it, legacy = true) }
    }

    private fun refreshLegacyUndoPointer() {
        val newest = undoDir().listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.maxByOrNull { it.name }
        if (newest == null) undoFile().delete()
        else runCatching { RootFileStore.writeAtomic(undoFile(), newest.readText()) }
    }

    private fun undoRecordCount(): Int = undoDir().listFiles()?.count { it.isFile && it.extension == "json" }
        ?: if (undoFile().isFile) 1 else 0

    private fun undoDir() = File(stateDir, "organizer-undo")
    private fun undoFile() = File(stateDir, "organizer-last.json")

    private fun pathValue(json: JSONObject, key: String): String {
        val plain = json.optString(key)
        if (plain.isNotBlank()) return plain
        val encoded = json.optString("${key}B64")
        if (encoded.isBlank()) return ""
        return runCatching { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
    }

    private fun pruneFiles(directory: File, keep: Int) {
        directory.listFiles()
            ?.filter(File::isFile)
            ?.sortedByDescending { it.name }
            ?.drop(keep)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun configInt(key: String, fallback: Int): Int = runCatching {
        File(stateDir, "config.conf").useLines { lines ->
            lines.firstOrNull { it.startsWith("$key=") }
                ?.substringAfter('=')
                ?.trim()
                ?.toIntOrNull()
                ?: fallback
        }
    }.getOrDefault(fallback)

    private fun notifyMediaStore(oldPath: String, newPath: String) {
        if (configInt("organizer_media_scan", 1) != 1) return
        listOf(oldPath, newPath).forEach { path ->
            runCatching {
                val userId = userIdForPath(path) ?: 0
                ProcessBuilder(
                    "/system/bin/am", "broadcast", "--user", userId.toString(),
                    "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                    "-d", Uri.fromFile(File(path)).toString()
                ).redirectErrorStream(true).start().apply { waitFor(4, TimeUnit.SECONDS) }
            }
        }
    }

    private fun normalizeSharedOwnership(file: File): Boolean = runCatching {
        val root = mediaUserRoot(file.path) ?: return@runCatching false
        val owner = Os.lstat(root.path)
        var parent = file.parentFile
        while (parent != null && canonical(parent).startsWith(root.path + "/")) {
            Os.chown(parent.path, owner.st_uid, owner.st_gid)
            Os.chmod(parent.path, 0x1F8)
            if (canonical(parent) == root.path) break
            parent = parent.parentFile
        }
        Os.chown(file.path, owner.st_uid, owner.st_gid)
        Os.chmod(file.path, 0x1B0)
        true
    }.getOrDefault(false)

    private fun restoreOriginalMetadata(file: File, move: JSONObject): Boolean = runCatching {
        val uid = move.optInt("sourceUid", -1)
        val gid = move.optInt("sourceGid", -1)
        val mode = move.optInt("sourceMode", -1)
        if (uid < 0 || gid < 0 || mode < 0) return@runCatching false
        Os.chown(file.path, uid, gid)
        Os.chmod(file.path, mode and 0x1FF)
        true
    }.getOrDefault(false)

    private fun mediaUserRoots(): List<File> =
        File("/data/media").listFiles()
            ?.filter { it.isDirectory && it.name.all(Char::isDigit) && !isSymlink(it) }
            ?.sortedBy { it.path }
            .orEmpty()

    private fun mediaUserRoot(path: String): File? {
        val user = Regex("^/data/media/(\\d+)(?:/|$)").find(path)?.groupValues?.getOrNull(1) ?: return null
        return File("/data/media/$user").takeIf { it.isDirectory }
    }

    private fun allowedDownloadRoot(path: String): Boolean {
        if (!path.startsWith("/data/media/") && !path.startsWith("/data/user/") && !path.startsWith("/data/user_de/")) {
            return false
        }
        if (path.contains("/BaiZe归类/") || path.endsWith("/BaiZe归类")) return false
        return path.split('/').any(::isDownloadDirectoryName)
    }

    private fun allowedOrganizerSource(path: String): Boolean {
        if (MEDIA_ROOT_FILE.matches(path)) return true
        if (APP_MEDIA_FILE.matches(path)) return true
        if (APP_EXTERNAL_FILES_FILE.matches(path)) return true
        return allowedDownloadRoot(path.substringBeforeLast('/', path))
    }

    private fun isAppUserFile(file: File, path: String): Boolean {
        if (category(file.name).isBlank()) return false
        return path.split('/').any { segment ->
            normalizeDirectoryName(segment) in APP_USER_DIRECTORY_NAMES
        }
    }

    private fun shouldPruneDiscovery(path: String, name: String): Boolean {
        val normalized = normalizeDirectoryName(name)
        if (normalized in DISCOVERY_PRUNE_NAMES) return true
        if (path.contains("/Android/obb/")) return true
        return false
    }

    private fun shouldPruneSourceDirectory(path: String, name: String): Boolean {
        if (path.contains("/BaiZe归类/") || path.endsWith("/BaiZe归类")) return true
        val normalized = normalizeDirectoryName(name)
        if (normalized in SOURCE_PRUNE_NAMES) return true
        return false
    }

    private fun isDownloadDirectoryName(name: String): Boolean =
        normalizeDirectoryName(name) in DOWNLOAD_DIRECTORY_NAMES

    private fun normalizeDirectoryName(name: String): String =
        name.trim().lowercase()
            .replace('-', '_')
            .replace(' ', '_')
            .replace('.', '_')

    private fun skipFile(file: File): Boolean {
        val name = file.name.lowercase()
        if (name.startsWith(".") && name !in setOf(".epub", ".pdf")) return true
        return name == ".nomedia" || name.endsWith(".lock") || name.endsWith(".lck") ||
            name.endsWith(".db") || name.endsWith(".sqlite") || name.endsWith(".sqlite3") ||
            name.endsWith("-wal") || name.endsWith("-shm") || name.endsWith(".journal") ||
            name.endsWith(".part") || name.endsWith(".partial") || name.endsWith(".crdownload") ||
            name.endsWith(".download") || name.endsWith(".tmp") || name.endsWith(".temp") ||
            name.endsWith(".bytes") || name.endsWith(".vfs") || name.endsWith(".blob") ||
            name.endsWith(".bin") || name.endsWith(".dat") || name.endsWith(".pak") ||
            name.endsWith(".obb") || name.endsWith(".bundle") || name.endsWith(".asset") ||
            name.endsWith(".cache") || name.endsWith(".idx") || name.endsWith(".index") ||
            name.endsWith(".dex") || name.endsWith(".odex") || name.endsWith(".vdex") ||
            name.endsWith(".so") || opaqueResourceName(name)
    }


    private fun opaqueResourceName(name: String): Boolean {
        val stem = name.substringBeforeLast('.', name)
        return stem.length >= 24 && stem.all { it in '0'..'9' || it in 'a'..'f' }
    }

    private fun category(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "dng", "raw" -> "图片"
            "mp4", "mkv", "mov", "avi", "webm", "flv", "wmv", "m4v", "3gp", "ts" -> "视频"
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "ape", "wma", "amr" -> "音频"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "md",
            "odt", "ods", "odp" -> "文档"
            "apk", "apks", "xapk", "apkm", "aab" -> "安装包"
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tgz", "tbz2" -> "压缩包"
            "epub", "mobi", "azw", "azw3", "fb2", "cbz", "cbr", "djvu" -> "电子书"
            else -> ""
        }
    }

    private fun sourceGroup(path: String): String {
        if (MEDIA_ROOT_FILE.matches(path)) return "内部存储根目录"
        val lower = path.lowercase()
        if (lower.contains("/qqfile_recv/") || lower.endsWith("/qqfile_recv")) return "QQ 接收文件"
        if (lower.contains("/timfile_recv/") || lower.endsWith("/timfile_recv")) return "TIM 接收文件"
        val packageName = Regex("/Android/(?:data|media)/([^/]+)/").find("$path/")?.groupValues?.getOrNull(1)
            ?: Regex("/data/(?:user|user_de)/\\d+/([^/]+)/").find("$path/")?.groupValues?.getOrNull(1)
        return packageName ?: "公共下载"
    }

    private fun userIdForPath(path: String): Int? {
        val media = Regex("^/data/media/(\\d+)(?:/|$)").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (media != null) return media
        return Regex("^/data/(?:user|user_de)/(\\d+)(?:/|$)")
            .find(path)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun policyPriority(policy: SourcePolicy): Int = when (policy) {
        SourcePolicy.TOP_LEVEL_ONLY -> 1
        SourcePolicy.APP_USER_FILES -> 2
        SourcePolicy.FULL_DOWNLOAD_TREE -> 3
    }

    private fun elapsed(started: Long): Long = SystemClock.elapsedRealtime() - started

    private fun fingerprint(file: File): String = runCatching {
        fingerprint(Os.lstat(file.path))
    }.getOrDefault("")

    private fun fingerprint(stat: android.system.StructStat): String =
        "${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtime}"

    private fun isSymlink(file: File): Boolean = runCatching {
        OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)
    }.getOrDefault(true)

    private fun canonical(file: File): String =
        runCatching { file.canonicalFile.path }.getOrDefault(file.absoluteFile.normalize().path)

    private fun displayPath(path: String): String = path
        .replace(Regex("^/data/media/\\d+"), "内部存储")
        .replace(Regex("^/data/(?:user|user_de)/\\d+/([^/]+)"), "应用私有/$1")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Text(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun itemJson(item: PlannedMove): JSONObject =
        JSONObject()
            .put("id", item.id)
            .put("name", item.name.take(MAX_NAME_CHARS))
            .put("category", item.category)
            .put("bytes", item.bytes)
            .put("sourceGroup", item.sourceGroup.take(MAX_GROUP_CHARS))
            .put("sourceDisplay", displayPath(item.source).take(MAX_PATH_CHARS))
            .put("destinationDisplay", displayPath(item.destination).take(MAX_PATH_CHARS))
            .put("destinationName", File(item.destination).name.take(MAX_NAME_CHARS))

    private fun detail(item: PlannedMove, action: String, reason: String): JSONObject =
        JSONObject()
            .put("id", item.id)
            .put("name", item.name.take(MAX_NAME_CHARS))
            .put("category", item.category)
            .put("sourceGroup", item.sourceGroup.take(MAX_GROUP_CHARS))
            .put("action", action)
            .put("reason", reason.take(160))

    private fun error(code: String, message: String): String =
        JSONObject().put("success", false).put("error", code).put("message", message).toString()

    companion object {
        private val ID_PATTERN = Regex("^[a-f0-9]{64}$")
        private val MEDIA_ROOT_FILE = Regex("^/data/media/\\d+/[^/]+$")
        private val APP_MEDIA_FILE = Regex("^/data/media/\\d+/Android/media/[^/]+/.+")
        private val APP_EXTERNAL_FILES_FILE = Regex("^/data/media/\\d+/Android/data/[^/]+/files/.+")
        private val DOWNLOAD_DIRECTORY_NAMES = setOf(
            "download", "downloads", "downloaded", "下载",
            "received", "receive", "recv", "file_recv",
            "qqfile_recv", "qqmy_file_recv", "qqfile_receive",
            "timfile_recv", "tim_file_recv",
            "attachment", "attachments",
            "export", "exports", "saved", "shared",
            "document", "documents", "transfer", "transfers", "offline"
        )
        private val APP_USER_DIRECTORY_NAMES = setOf(
            "download", "downloads", "downloaded", "下载",
            "document", "documents", "received", "receive", "recv", "file_recv",
            "qqfile_recv", "qqmy_file_recv", "timfile_recv", "tim_file_recv",
            "export", "exports", "attachment", "attachments",
            "transfer", "transfers", "offline", "saved", "shared",
            "telegram_documents", "telegram_images", "telegram_video", "telegram_audio", "telegram_files",
            "nagram_documents", "nagram_images", "nagram_video", "nagram_audio", "nagram_files",
            "nagramx_documents", "nagramx_images", "nagramx_video", "nagramx_audio", "nagramx_files"
        )
        private val DISCOVERY_PRUNE_NAMES = setOf(
            "cache", "code_cache", "databases", "shared_prefs", "lib", "oat", "no_backup",
            "tmp", "temp", "thumbnail", "thumbnails", "_thumbnails"
        )
        private val SOURCE_PRUNE_NAMES = DISCOVERY_PRUNE_NAMES + setOf(
            "stickers", "emoji", "emojis", "crash", "crashes", "crashlytics",
            "logs", "log", "sessions", "accounts"
        )
        private const val SNAPSHOT_TTL_MS = 30L * 60_000L
        private const val DISCOVERY_BUDGET_MS = 75_000L
        private const val SCAN_BUDGET_MS = 4L * 60_000L
        private const val MAX_ITEMS = 30_000
        private const val MAX_FILE_DEPTH = 14
        private const val DEFAULT_PAGE_SIZE = 60
        private const val MAX_PAGE_SIZE = 100
        private const val MAX_SELECTION_IDS = 30_000
        private const val MAX_RESULT_DETAILS = 40
        private const val MAX_PATH_CHARS = 360
        private const val MAX_NAME_CHARS = 160
        private const val MAX_GROUP_CHARS = 100
        private const val MAX_COLLISION_RENAMES = 999
        private const val SNAPSHOT_RETENTION = 3
        private const val DEFAULT_UNDO_RETENTION = 10
    }
}
