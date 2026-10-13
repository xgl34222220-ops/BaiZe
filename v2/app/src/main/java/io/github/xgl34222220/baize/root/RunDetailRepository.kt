package io.github.xgl34222220.baize.root

import io.github.xgl34222220.baize.OrdinaryFileTrash
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 记录详情页的 Root 端：读取模块写下的逐项明细（state/run-details/<id>.tsv），并对用户勾选的
 * 项目执行「删除」（优先移入回收站 / 隔离区）。
 *
 * - UI 只传明细编号与行号，路径一律从 Root 端的明细文件读取并重新核对（存在、非符号链接、不在系统/模块/回收站目录）。
 * - 用户保护名单命中的项目默认不处理；用户在确认框里明确「本次临时允许」后才处理，保护名单本身不变。
 * - 无法移入回收站（不同分区等）的项目原样保留并返回 needsPermanent，只有用户再次确认「永久删除」才删除。
 * - 处理结果写回明细文件第 8 列（state），文件大小与行数仍受模块写入时的上限约束。
 */
internal class RunDetailRepository(
    private val stateDir: File = File(RootPaths.STATE_DIR),
    private val quarantine: QuarantineRepository = QuarantineRepository(),
    private val whitelist: WhitelistRepository = WhitelistRepository()
) {
    private val dir get() = File(stateDir, "run-details")

    fun readJson(id: String): String = runCatching {
        val file = detailFile(id) ?: return@runCatching missing()
        val (header, rows) = parse(file)
        val items = JSONArray()
        rows.forEachIndexed { index, row ->
            val path = row.path
            val actionable = row.state.isEmpty() && isAbsolutePath(path)
            val attributes = if (isAbsolutePath(path)) lstat(path) else null
            val protection = if (actionable) whitelist.protectionFor(path) else null
            items.put(JSONObject()
                .put("index", index)
                .put("kind", row.kind)
                .put("reason", row.reason)
                .put("reasonText", RunDetailReasons.text(row.reason, row.note))
                .put("category", row.category)
                .put("count", row.count)
                .put("bytes", if (attributes != null && attributes.isRegularFile) attributes.size() else row.bytes)
                .put("path", path)
                .put("note", row.note)
                .put("state", row.state)
                .put("exists", attributes != null)
                .put("directory", attributes?.isDirectory == true)
                .put("symlink", attributes?.isSymbolicLink == true)
                .put("actionable", actionable && attributes != null && !attributes.isSymbolicLink && blockedReason(path) == null)
                .put("blockedReason", if (isAbsolutePath(path)) blockedReason(path).orEmpty() else "")
                .apply { if (protection != null) put("protectionKind", protection.kind).put("protectionRule", protection.rule) })
        }
        JSONObject().put("success", true).put("id", id)
            .put("failed", header["failed"] ?: 0L).put("skipped", header["skipped"] ?: 0L)
            .put("listed", header["listed"] ?: rows.size.toLong())
            .put("items", items).toString()
    }.getOrElse { JSONObject().put("success", false).put("message", it.message ?: it.javaClass.simpleName).toString() }

    /** [indices] 来自 [readJson] 的 index；allowProtected = 用户确认本次临时允许受保护项；permanent = 用户确认永久删除。 */
    fun deleteJson(id: String, indicesJson: String, allowProtected: Boolean, permanent: Boolean): String = runCatching {
        val file = detailFile(id) ?: return@runCatching missing()
        val indices = JSONArray(indicesJson).let { array -> (0 until array.length()).map { array.getInt(it) } }.distinct()
        require(indices.size in 1..MAX_ROWS) { "未选择项目" }
        synchronized(LOCK) {
            val (_, rows) = parse(file)
            val results = JSONArray()
            var removed = 0; var retained = 0; var needsPermanent = 0; var protectedCount = 0
            var releasedBytes = 0L
            for (index in indices) {
                val row = rows.getOrNull(index) ?: continue
                val outcome = deleteOne(row, allowProtected, permanent)
                when (outcome.status) {
                    "quarantined", "deleted", "gone" -> { removed++; releasedBytes += outcome.bytes; row.state = outcome.status }
                    "needs_permanent" -> needsPermanent++
                    "protected" -> protectedCount++
                    else -> retained++
                }
                results.put(JSONObject().put("index", index).put("path", row.path).put("status", outcome.status)
                    .put("message", outcome.message).put("bytes", outcome.bytes))
            }
            write(file, rows)
            JSONObject().put("success", true).put("removed", removed).put("retained", retained)
                .put("needsPermanent", needsPermanent).put("protected", protectedCount)
                .put("bytes", releasedBytes).put("results", results)
                .put("message", summary(removed, retained, needsPermanent, protectedCount, permanent)).toString()
        }
    }.getOrElse { JSONObject().put("success", false).put("message", it.message ?: it.javaClass.simpleName).toString() }

    private data class Outcome(val status: String, val message: String, val bytes: Long = 0L)

    private fun deleteOne(row: Row, allowProtected: Boolean, permanent: Boolean): Outcome {
        val path = row.path
        if (row.state.isNotEmpty()) return Outcome("done", "已处理过")
        if (!isAbsolutePath(path)) return Outcome("retained", "这一行是汇总，没有具体路径")
        blockedReason(path)?.let { return Outcome("retained", it) }
        val attributes = lstat(path) ?: return Outcome("gone", "已不存在")
        if (attributes.isSymbolicLink) return Outcome("retained", "这是符号链接，为安全起见不处理")
        whitelist.protectionFor(path)?.let { protection ->
            if (!allowProtected) return Outcome("protected", "受保护：" + RunDetailReasons.protectionText(protection.kind, protection.rule))
        }
        val result = quarantine.quarantineUserSelected(path, row.category.ifBlank { "任务记录" }, File(path).name)
        if (result.success) return Outcome("quarantined", result.message, result.bytes)
        if (lstat(path) == null) return Outcome("gone", "已不存在")
        if (!permanent) return Outcome("needs_permanent", "无法移入回收站：${result.message}")
        return if (deleteTree(File(path))) Outcome("deleted", "已永久删除", if (attributes.isRegularFile) attributes.size() else 0L)
        else Outcome("retained", "永久删除未完成，系统拒绝删除部分内容")
    }

    private fun blockedReason(path: String): String? = when {
        OrdinaryFileTrash.isPayloadPath(path) -> "这是白泽回收站内部的恢复副本，请到回收站处理"
        path == RootPaths.STATE_DIR || path.startsWith(RootPaths.STATE_DIR + "/") ||
            path.startsWith("/data/adb/") -> "这是白泽模块自身的数据，不能从记录页删除"
        path.contains("/.baize-quarantine") -> "这是隔离区内部文件，请到回收站处理"
        !quarantine.allowedOriginalPath(path) -> "系统目录或路径已变化，为安全起见不处理"
        else -> null
    }

    private fun deleteTree(root: File): Boolean = runCatching {
        val start = root.toPath()
        val deadline = System.currentTimeMillis() + 20_000L
        var visited = 0
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(++visited <= 200_000 && System.currentTimeMillis() < deadline) { "too_large" }
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
        !Files.exists(start, LinkOption.NOFOLLOW_LINKS)
    }.getOrDefault(false)

    private fun summary(removed: Int, retained: Int, needsPermanent: Int, protectedCount: Int, permanent: Boolean): String = buildString {
        append(if (removed > 0) (if (permanent) "已处理 $removed 项" else "已处理 $removed 项，可在回收站恢复") else "没有项目被删除")
        if (protectedCount > 0) append(" · $protectedCount 项受保护未处理")
        if (needsPermanent > 0) append(" · $needsPermanent 项无法移入回收站")
        if (retained > 0) append(" · $retained 项保留")
    }

    private class Row(val kind: String, val reason: String, val category: String, val count: Long, val bytes: Long,
        val path: String, val note: String, var state: String) {
        fun line() = listOf(kind, reason, category, count.toString(), bytes.toString(), path, note, state)
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }
    }

    private fun parse(file: File): Pair<Map<String, Long>, MutableList<Row>> {
        check(file.length() in 1..MAX_FILE_BYTES) { "明细文件无效" }
        val header = linkedMapOf<String, Long>()
        val rows = ArrayList<Row>()
        file.useLines { lines ->
            lines.take(MAX_ROWS + 4).forEach { line ->
                if (line.startsWith("#counts")) {
                    line.split('\t').drop(1).forEach { token ->
                        val key = token.substringBefore('='); val value = token.substringAfter('=', "").toLongOrNull()
                        if (value != null) header[key] = value.coerceAtLeast(0L)
                    }
                    return@forEach
                }
                if (line.startsWith("#") || line.isBlank()) return@forEach
                val c = line.split('\t')
                if (c.size < 6 || c[0] !in setOf("failed", "skipped")) return@forEach
                rows += Row(c[0], c[1].take(40), c[2].take(120), c[3].toLongOrNull()?.coerceAtLeast(0L) ?: 1L,
                    c[4].toLongOrNull()?.coerceAtLeast(0L) ?: 0L, c[5].take(4096), c.getOrNull(6).orEmpty().take(300),
                    c.getOrNull(7).orEmpty().take(40))
            }
        }
        return header to rows
    }

    private fun write(file: File, rows: List<Row>) {
        val keep = file.readLines().takeWhile { it.startsWith("#") }
        RootFileStore.writeAtomic(file, (keep + rows.map { it.line() }).joinToString("\n", postfix = "\n"))
    }

    private fun detailFile(id: String): File? {
        if (!ID_PATTERN.matches(id)) return null
        val file = File(dir, "$id.tsv")
        val attributes = runCatching { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
        return file.takeIf { attributes?.isRegularFile == true }
    }

    private fun missing(): String = JSONObject().put("success", false).put("expired", true)
        .put("message", "这条记录的逐项明细已过期（只保留最近 30 次任务）").toString()

    private fun lstat(path: String): BasicFileAttributes? = runCatching {
        Files.readAttributes(File(path).toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull()

    private fun isAbsolutePath(path: String): Boolean = path.startsWith('/') && path.length in 2..4096 &&
        path.none { it == '\u0000' || it == '\n' || it == '\r' } && path.split('/').none { it == ".." }

    companion object {
        private val LOCK = Any()
        const val MAX_ROWS = 300
        private const val MAX_FILE_BYTES = 1L * 1024 * 1024
        val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")
    }
}

/** 跳过 / 失败原因码 → 用户能看懂的中文。模块与 App 共用同一套原因码（见 run-details.sh）。 */
internal object RunDetailReasons {
    fun text(code: String, note: String = ""): String = when (code) {
        "delete_failed" -> "删除失败" + if (note.isNotBlank()) "：" + errnoText(note) else ""
        else -> skipText(code)
    }

    fun skipText(code: String): String = when (code) {
        "protected" -> "在你的保护名单中"
        "review" -> "高风险，需要你手动确认"
        "changed" -> "扫描后内容有变化，为安全起见未删除"
        "missing" -> "文件已不存在（可能已被应用自己删除）"
        "slow" -> "目录太大或太慢，本次跳过"
        "unreadable" -> "目录无法完整读取，本次未处理"
        "mount" -> "位于独立挂载点，已保护"
        "size" -> "超过单个文件大小上限"
        "reinstalled" -> "应用已重新安装，残留不再处理"
        "unverified" -> "无法复核应用安装状态，暂不处理"
        "recent" -> "最近使用或仍在保留期内"
        "safety" -> "安全规则保护"
        "scan_unreadable" -> "扫描时部分目录无法读取"
        "legacy" -> "旧版本记录，未区分原因"
        else -> "已跳过"
    }

    fun protectionText(kind: String, rule: String): String = when (kind) {
        "app" -> "应用保护 $rule"
        else -> "路径保护 $rule"
    }

    /** strerror 英文 → 中文；未知原因原样保留。 */
    fun errnoText(raw: String): String {
        val value = raw.trim()
        val known = mapOf(
            "Permission denied" to "没有权限（Permission denied）",
            "Operation not permitted" to "系统不允许（Operation not permitted）",
            "Device or resource busy" to "文件正被占用（Device or resource busy）",
            "Input/output error" to "存储读写错误（I/O error）",
            "I/O error" to "存储读写错误（I/O error）",
            "Read-only file system" to "只读分区（Read-only file system）",
            "Directory not empty" to "目录不为空",
            "Connection timed out" to "校验超时",
            "Text file busy" to "文件正被占用（Text file busy）"
        )
        return known[value] ?: value
    }
}
