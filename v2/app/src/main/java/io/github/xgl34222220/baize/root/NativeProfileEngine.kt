package io.github.xgl34222220.baize.root

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/**
 * Persistent native scanner for every non-cache cleaning profile.
 *
 * The client can only clean candidates stored in an unexpired server-side snapshot. Every target is
 * checked again immediately before mutation. The engine never accepts a new deletion path from UI.
 */
internal class NativeProfileEngine(
    private val context: Context,
    private val cancelled: AtomicBoolean
) {
    data class Progress(
        val phase: String,
        val current: Int,
        val total: Int,
        val path: String = "",
        val bytes: Long = 0L,
        val files: Long = 0L,
        val failures: Int = 0
    )

    private data class Options(
        val whitelistPackages: Set<String>,
        val whitelistPaths: Set<String>,
        val maxFileBytes: Long,
        val fragmentDays: Int,
        val allowHighRisk: Boolean
    )

    private data class Candidate(
        val id: String,
        val profile: String,
        val category: String,
        val label: String,
        val risk: String,
        val path: String,
        val packageName: String = "",
        val appName: String = "",
        val deleteRoot: Boolean = false,
        var bytes: Long = -1L,
        var files: Long = -1L,
        var directories: Long = -1L,
        var measured: Boolean = false,
        var complete: Boolean = false,
        val note: String = ""
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id)
            .put("profile", profile)
            .put("category", category)
            .put("categoryLabel", label)
            .put("risk", risk)
            .put("path", path)
            .put("packageName", packageName)
            .put("appName", appName.ifBlank { packageName.ifBlank { label } })
            .put("deleteRoot", deleteRoot)
            .put("bytes", bytes)
            .put("files", files)
            .put("directories", directories)
            .put("measured", measured)
            .put("complete", complete)
            .put("note", note)
    }

    private data class Snapshot(
        val id: String,
        val profile: String,
        val createdAt: Long,
        val ruleSha: String,
        val options: Options,
        val candidates: MutableList<Candidate>
    )

    private data class Stats(
        val bytes: Long,
        val files: Long,
        val directories: Long,
        val complete: Boolean,
        val failures: Int = 0
    )

    private data class Node(val file: File, val depth: Int, val post: Boolean = false)

    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    fun catalog(): String = JSONObject()
        .put("profiles", JSONArray().apply {
            put(profile("empty", "空项目", "空文件与空目录", "low"))
            put(profile("rules", "规则垃圾", "隐藏垃圾、系统/OEM 日志与扩展规则", "medium"))
            put(profile("fragments", "残留碎片", "过期临时文件、旋转日志、崩溃转储与中断下载", "medium"))
            put(profile("deep", "深度规则", "4,746 条规则分级审计与清理", "high"))
            put(profile("corpses", "卸载残留", "Android/data 与 Android/obb 残留", "high"))
        })
        .toString()

    fun scan(profile: String, optionsJson: String, progress: (Progress) -> Unit): String {
        pruneSnapshots()
        val id = profile.trim().lowercase()
        val options = parseOptions(optionsJson)
        val started = SystemClock.elapsedRealtime()
        val candidates = LinkedHashMap<String, Candidate>()
        val ruleSha = if (id == "deep") sha256(deepRules()) else ""

        progress(Progress("准备${label(id)}扫描", 0, 0))
        when (id) {
            "empty" -> scanEmpty(options, candidates, progress, started)
            "rules" -> scanRules(options, candidates, progress, started)
            "fragments" -> scanFragments(options, candidates, progress, started)
            "deep" -> scanDeep(options, candidates, progress, started)
            "corpses" -> scanCorpses(options, candidates, progress, started)
            else -> return JSONObject().put("error", "unsupported_profile").put("profile", id).toString()
        }

        if (cancelled.get()) {
            return JSONObject()
                .put("cancelled", true)
                .put("profile", id)
                .put("totalCandidates", candidates.size)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .toString()
        }

        val snapshotId = UUID.randomUUID().toString()
        val list = candidates.values.toMutableList()
        snapshots[snapshotId] = Snapshot(snapshotId, id, System.currentTimeMillis(), ruleSha, options, list)
        progress(Progress("${label(id)}扫描完成", list.size, list.size))
        return JSONObject()
            .put("success", true)
            .put("profile", id)
            .put("profileLabel", label(id))
            .put("snapshotId", snapshotId)
            .put("snapshotExpiresInMs", SNAPSHOT_TTL_MS)
            .put("ruleSha", ruleSha)
            .put("totalCandidates", list.size)
            .put("low", list.count { it.risk == "low" })
            .put("medium", list.count { it.risk == "medium" })
            .put("high", list.count { it.risk == "high" })
            .put("critical", list.count { it.risk == "critical" })
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .toString()
    }

    fun page(snapshotId: String, offset: Int, limit: Int): String {
        val snapshot = validSnapshot(snapshotId)
            ?: return JSONObject().put("error", "snapshot_expired").put("message", "扫描快照不存在或已过期").put("items", JSONArray()).toString()
        val start = max(0, offset)
        val count = min(MAX_PAGE_SIZE, max(1, limit))
        if (start >= snapshot.candidates.size) {
            return JSONObject().put("success", true).put("snapshotId", snapshotId)
                .put("total", snapshot.candidates.size).put("items", JSONArray()).toString()
        }
        val end = min(snapshot.candidates.size, start + count)
        val pageDeadline = SystemClock.elapsedRealtime() + PAGE_BUDGET_MS
        val array = JSONArray()
        for (index in start until end) {
            if (cancelled.get()) break
            val item = snapshot.candidates[index]
            if (!item.measured && SystemClock.elapsedRealtime() < pageDeadline) {
                val stat = measure(File(item.path), min(pageDeadline, SystemClock.elapsedRealtime() + ITEM_MEASURE_MS))
                item.bytes = stat.bytes
                item.files = stat.files
                item.directories = stat.directories
                item.measured = true
                item.complete = stat.complete
            }
            array.put(item.json())
        }
        return JSONObject()
            .put("success", true)
            .put("snapshotId", snapshotId)
            .put("profile", snapshot.profile)
            .put("offset", start)
            .put("limit", count)
            .put("total", snapshot.candidates.size)
            .put("items", array)
            .toString()
    }

    fun clean(
        snapshotId: String,
        selectionJson: String,
        optionsJson: String,
        progress: (Progress) -> Unit
    ): String {
        val snapshot = validSnapshot(snapshotId)
            ?: return JSONObject().put("error", "snapshot_expired").put("message", "扫描快照不存在或已过期").toString()
        if (snapshot.profile == "deep") {
            val current = sha256(deepRules())
            if (snapshot.ruleSha.isBlank() || current != snapshot.ruleSha) {
                snapshots.remove(snapshotId)
                return JSONObject().put("error", "rules_changed").put("message", "深度规则已变化，请重新扫描").toString()
            }
        }

        val options = parseOptions(optionsJson)
        val selection = parseSelection(selectionJson)
        val selectAllSafe = selection["__all_safe__"] == true
        val selected = snapshot.candidates.filter { candidate ->
            val explicit = selection[candidate.id] == true || selection[candidate.path] == true
            explicit || (selectAllSafe && (candidate.risk == "low" || candidate.risk == "medium"))
        }
        if (selected.isEmpty()) {
            return JSONObject().put("error", "empty_selection").put("message", "没有明确勾选任何项目").toString()
        }

        val started = SystemClock.elapsedRealtime()
        val deadline = started + CLEAN_TOTAL_MS
        val mounts = mountPoints()
        val details = JSONArray()
        var deletedBytes = 0L
        var deletedFiles = 0L
        var deletedDirectories = 0L
        var failures = 0
        var cleaned = 0
        var skipped = 0

        for ((index, candidate) in selected.withIndex()) {
            if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) break
            progress(Progress("正在清理${candidate.label}", index, selected.size, candidate.path, deletedBytes, deletedFiles, failures))
            val reason = validate(candidate, options, mounts)
            if (reason != null) {
                skipped += 1
                details.put(detail(candidate, "protected", reason, 0L, 0L, 0L))
                continue
            }

            val target = File(candidate.path)
            val before = measure(target, min(deadline, SystemClock.elapsedRealtime() + ITEM_CLEAN_MS))
            val result = deleteCandidate(candidate, target, options.maxFileBytes, mounts, min(deadline, SystemClock.elapsedRealtime() + ITEM_CLEAN_MS))
            val after = if (target.exists()) measure(target, min(deadline, SystemClock.elapsedRealtime() + 1_000L)) else Stats(0L, 0L, 0L, true)
            val actualBytes = max(0L, before.bytes - after.bytes)
            val actualFiles = max(0L, before.files - after.files)
            val actualDirs = max(0L, before.directories - after.directories)
            deletedBytes += actualBytes
            deletedFiles += actualFiles
            deletedDirectories += actualDirs
            failures += result.failures

            val complete = if (candidate.deleteRoot) !target.exists() else after.files == 0L && after.directories == 0L
            if (complete || actualBytes > 0L || actualFiles > 0L || actualDirs > 0L) cleaned += 1 else skipped += 1
            details.put(detail(candidate, if (complete) "cleaned" else "partial", if (complete) "" else "仍有受保护或未删除项目", actualBytes, actualFiles, actualDirs))
        }

        snapshots.remove(snapshotId)
        val timedOut = SystemClock.elapsedRealtime() >= deadline
        val wasCancelled = cancelled.get()
        progress(Progress(if (wasCancelled) "清理已停止" else "清理完成", selected.size, selected.size, bytes = deletedBytes, files = deletedFiles, failures = failures))
        return JSONObject()
            .put("success", true)
            .put("profile", snapshot.profile)
            .put("selected", selected.size)
            .put("cleanedCandidates", cleaned)
            .put("skippedCandidates", skipped)
            .put("failures", failures)
            .put("deletedBytes", deletedBytes)
            .put("deletedFiles", deletedFiles)
            .put("deletedDirectories", deletedDirectories)
            .put("cancelled", wasCancelled)
            .put("timedOut", timedOut)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("details", details)
            .toString()
    }

    private fun scanEmpty(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val roots = storageRoots()
        for ((index, root) in roots.withIndex()) {
            if (stop(started, SCAN_TOTAL_MS)) return
            progress(Progress("扫描空文件与空目录", index, roots.size, root.path))
            walk(root, 8, started + SCAN_TOTAL_MS, true) { file, post ->
                if (post) {
                    if (file != root && isEmptyDirectory(file) && !protectedDirectoryName(file.name)) {
                        add(out, candidate("empty", "empty_dir", "空目录", "low", file, deleteRoot = true), options)
                    }
                } else if (file.isFile && file.length() == 0L && !placeholder(file.name)) {
                    val item = candidate("empty", "empty_file", "空文件", "low", file, deleteRoot = true)
                    item.bytes = 0L
                    item.files = 1L
                    item.directories = 0L
                    item.measured = true
                    item.complete = true
                    add(out, item, options)
                }
            }
        }
    }

    private fun scanRules(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val rules = ArrayList<Pair<String, String>>()
        rules.add("/data/anr" to "系统 ANR")
        rules.add("/data/tombstones" to "Tombstone")
        rules.add("/data/system/dropbox" to "系统 Dropbox")
        rules.add("/data/system/heapdump" to "系统 Heapdump")
        rules.add("/data/misc/logd" to "系统日志")
        rules.add("/data/vendor/log" to "厂商日志")
        rules.add("/data/log" to "系统日志")
        val directory = rulesDirectory()
        if (directory != null) {
            for (name in listOf("app.rules", "external.rules", "hidden.rules", "custom.rules")) {
                val source = File(directory, name)
                if (!source.isFile) continue
                source.useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("#") }
                        .take(MAX_RULE_LINES)
                        .forEach { rules.add(it to "扩展规则") }
                }
            }
        }

        for ((index, rule) in rules.withIndex()) {
            if (stop(started, SCAN_TOTAL_MS)) return
            if (index % 32 == 0) progress(Progress("解析规则垃圾", index, rules.size, rule.first))
            for (target in expand(rule.first)) {
                if (target.exists() && !isSymlink(target)) {
                    add(out, candidate("rules", "rule_trash", rule.second, risk(target.path), target, deleteRoot = target.isFile), options)
                }
            }
        }

        val hidden = setOf(".cache", ".thumbnails", ".tmp", ".temp", ".logs", ".debug")
        for (root in storageRoots()) {
            walk(root, 6, started + SCAN_TOTAL_MS, true) { file, post ->
                if (!post && file.isDirectory && hidden.contains(file.name.lowercase())) {
                    add(out, candidate("rules", "hidden_trash", "隐藏垃圾", "low", file, deleteRoot = false), options)
                }
            }
        }
    }

    private fun scanFragments(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val cutoff = System.currentTimeMillis() - options.fragmentDays * 86_400_000L
        val patterns = listOf(
            Pattern.compile(".*\\.(tmp|temp|part|partial|download|crdownload)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*\\.(log\\.[0-9]+|old|bak~)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*(tombstone|minidump|heapdump|crash|trace|dump).*", Pattern.CASE_INSENSITIVE)
        )
        val roots = ArrayList<File>()
        roots.addAll(storageRoots())
        roots.addAll(logRoots())
        val distinct = roots.distinctBy { canonical(it) }
        for ((index, root) in distinct.withIndex()) {
            if (stop(started, SCAN_TOTAL_MS)) return
            progress(Progress("扫描残留碎片", index, distinct.size, root.path))
            walk(root, 9, started + SCAN_TOTAL_MS, root.path.startsWith("/storage") || root.path.startsWith("/sdcard")) { file, post ->
                if (!post && file.isFile && file.lastModified() <= cutoff && patterns.any { it.matcher(file.name).matches() }) {
                    val item = candidate("fragments", "fragment", "残留碎片", risk(file.path), file, deleteRoot = true, note = "保留 ${options.fragmentDays} 天")
                    item.bytes = file.length()
                    item.files = 1L
                    item.directories = 0L
                    item.measured = true
                    item.complete = true
                    add(out, item, options)
                }
            }
        }
    }

    private fun scanDeep(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val source = deepRules() ?: return
        val rules = source.useLines { lines ->
            lines.map { it.trim() }
                .filter { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("#") }
                .take(MAX_RULE_LINES)
                .toList()
        }
        for ((index, raw) in rules.withIndex()) {
            if (stop(started, DEEP_SCAN_TOTAL_MS)) return
            if (index % 16 == 0) progress(Progress("解析深度规则", index, rules.size, raw))
            for (target in expand(raw)) {
                if (target.exists() && !isSymlink(target)) {
                    add(out, candidate("deep", "deep_rule", "深度规则", risk(target.path), target, deleteRoot = target.isFile, note = raw), options, true)
                }
            }
        }
    }

    private fun scanCorpses(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val installed = installedPackages()
        val roots = ArrayList<File>()
        for (storage in storageRoots()) {
            roots.add(File(storage, "Android/data"))
            roots.add(File(storage, "Android/obb"))
        }
        val existing = roots.filter { it.isDirectory && !isSymlink(it) }
        for ((index, root) in existing.withIndex()) {
            if (stop(started, SCAN_TOTAL_MS)) return
            progress(Progress("扫描卸载残留", index, existing.size, root.path))
            val children = root.listFiles() ?: emptyArray()
            for (child in children) {
                if (cancelled.get()) return
                val packageName = child.name
                if (!child.isDirectory || isSymlink(child) || !packageName(packageName) || installed.containsKey(packageName)) continue
                val item = candidate(
                    "corpses",
                    "uninstalled_leftover",
                    if (root.name == "obb") "卸载残留 · OBB" else "卸载残留 · Data",
                    "high",
                    child,
                    packageName,
                    packageName,
                    true
                )
                add(out, item, options, true)
            }
        }
    }

    private fun add(
        out: MutableMap<String, Candidate>,
        candidate: Candidate,
        options: Options,
        includeHighInScan: Boolean = false
    ) {
        if (out.size >= MAX_CANDIDATES) return
        val path = canonical(File(candidate.path))
        if (!path.startsWith("/") || hardProtected(path) || whitelisted(candidate.copy(path = path), options)) return
        if (!includeHighInScan && (candidate.risk == "high" || candidate.risk == "critical")) return
        out.putIfAbsent(path, candidate.copy(id = "${candidate.profile}:$path", path = path))
    }

    private fun validate(candidate: Candidate, options: Options, mounts: Set<String>): String? {
        val target = File(candidate.path)
        val path = canonical(target)
        if (path != candidate.path || hardProtected(path)) return "路径超出安全边界"
        if (!target.exists()) return "目标已不存在"
        if (isSymlink(target)) return "符号链接受保护"
        if (mounts.contains(path)) return "挂载点受保护"
        if (whitelisted(candidate, options)) return "白名单保护"
        if (candidate.risk == "critical") return "关键风险只允许审计"
        if (candidate.risk == "high" && !options.allowHighRisk) return "高风险清理未启用"
        if (candidate.profile == "corpses" && installedPackages().containsKey(candidate.packageName)) return "应用已重新安装"
        if (!stillMatches(candidate, target, options)) return "目标不再符合扫描条件"
        return null
    }

    private fun stillMatches(candidate: Candidate, target: File, options: Options): Boolean = when (candidate.profile) {
        "empty" -> if (candidate.category == "empty_file") target.isFile && target.length() == 0L && !placeholder(target.name) else target.isDirectory && isEmptyDirectory(target)
        "fragments" -> target.isFile && target.lastModified() <= System.currentTimeMillis() - options.fragmentDays * 86_400_000L
        "corpses" -> corpsePath(canonical(target)) && !installedPackages().containsKey(candidate.packageName)
        "rules", "deep" -> mutationRoot(canonical(target))
        else -> false
    }

    private fun deleteCandidate(candidate: Candidate, target: File, maxFileBytes: Long, mounts: Set<String>, deadline: Long): Stats {
        if (target.isFile) {
            if (target.length() > maxFileBytes) return Stats(0L, 0L, 0L, true)
            val size = target.length()
            val ok = runCatching { target.delete() }.getOrDefault(false)
            return Stats(if (ok) size else 0L, if (ok) 1L else 0L, 0L, true, if (ok) 0 else 1)
        }
        if (candidate.category == "empty_dir") {
            val ok = runCatching { target.delete() }.getOrDefault(false)
            return Stats(0L, 0L, if (ok) 1L else 0L, true, if (ok) 0 else 1)
        }

        val stack = ArrayDeque<Node>()
        stack.add(Node(target, 0, false))
        var bytes = 0L
        var files = 0L
        var directories = 0L
        var failures = 0
        var complete = true
        while (stack.isNotEmpty()) {
            if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) {
                complete = false
                break
            }
            val node = stack.removeLast()
            val file = node.file
            if (node.post) {
                if ((file != target || candidate.deleteRoot) && runCatching { file.delete() }.getOrDefault(false)) directories += 1L
                continue
            }
            if (!file.exists() || isSymlink(file)) continue
            val path = canonical(file)
            if (file != target && mounts.contains(path)) continue
            if (file.isFile) {
                val size = file.length()
                if (size > maxFileBytes) continue
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    bytes += size
                    files += 1L
                } else failures += 1
                continue
            }
            if (file.isDirectory) {
                stack.add(Node(file, node.depth, true))
                val children = file.listFiles()
                if (children == null) {
                    failures += 1
                } else {
                    for (child in children) stack.add(Node(child, node.depth + 1, false))
                }
            }
        }
        return Stats(bytes, files, directories, complete, failures)
    }

    private fun measure(root: File, deadline: Long): Stats {
        if (!root.exists() || isSymlink(root)) return Stats(0L, 0L, 0L, true)
        if (root.isFile) return Stats(root.length(), 1L, 0L, true)
        val stack = ArrayDeque<File>()
        stack.add(root)
        var bytes = 0L
        var files = 0L
        var dirs = 0L
        var complete = true
        while (stack.isNotEmpty()) {
            if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) {
                complete = false
                break
            }
            val file = stack.removeLast()
            if (!file.exists() || isSymlink(file)) continue
            if (file.isFile) {
                files += 1L
                bytes += file.length()
            } else if (file.isDirectory) {
                if (file != root) dirs += 1L
                val children = file.listFiles()
                if (children == null) complete = false else for (child in children) stack.add(child)
            }
        }
        return Stats(bytes, files, dirs, complete)
    }

    private fun walk(root: File, maxDepth: Int, deadline: Long, pruneShared: Boolean, visitor: (File, Boolean) -> Unit) {
        if (!root.isDirectory || isSymlink(root)) return
        val stack = ArrayDeque<Node>()
        stack.add(Node(root, 0, false))
        while (stack.isNotEmpty()) {
            if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) return
            val node = stack.removeLast()
            val file = node.file
            if (!file.exists() || isSymlink(file)) continue
            if (node.post) {
                visitor(file, true)
                continue
            }
            visitor(file, false)
            if (!file.isDirectory || node.depth >= maxDepth) continue
            if (file != root && pruneShared && prune(file)) continue
            stack.add(Node(file, node.depth, true))
            val children = file.listFiles() ?: continue
            for (child in children) stack.add(Node(child, node.depth + 1, false))
        }
    }

    private fun expand(rawRule: String): List<File> {
        val raw = rawRule.substringBefore('|').substringBefore('#').trim()
        if (!raw.startsWith("/") || raw.length > 4096) return emptyList()
        if (!raw.contains('*') && !raw.contains('?') && !raw.contains('[')) return listOf(File(raw))
        val segments = raw.split('/').filter { it.isNotEmpty() }
        var current: List<File> = listOf(File("/"))
        for (segment in segments) {
            val next = ArrayList<File>()
            val wildcard = segment.contains('*') || segment.contains('?') || segment.contains('[')
            for (base in current) {
                if (next.size >= MAX_EXPANSIONS) break
                if (!wildcard) {
                    next.add(File(base, segment))
                } else if (base.isDirectory && !isSymlink(base)) {
                    val regex = glob(segment)
                    val children = base.listFiles() ?: emptyArray()
                    for (child in children) {
                        if (next.size >= MAX_EXPANSIONS) break
                        if (regex.matches(child.name)) next.add(child)
                    }
                }
            }
            current = next
            if (current.isEmpty()) break
        }
        return current
    }

    private fun glob(segment: String): Regex {
        val result = StringBuilder("^")
        for (char in segment) {
            when (char) {
                '*' -> result.append(".*")
                '?' -> result.append('.')
                '.', '(', ')', '+', '|', '^', '$', '{', '}', '\\' -> result.append('\\').append(char)
                else -> result.append(char)
            }
        }
        result.append('$')
        return Regex(result.toString())
    }

    private fun parseOptions(raw: String): Options {
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        return Options(
            strings(json.optJSONArray("whitelistPackages")),
            strings(json.optJSONArray("whitelistPaths")).filter { it.startsWith("/") }.toSet(),
            json.optLong("maxFileBytes", DEFAULT_MAX_FILE_BYTES).coerceIn(0L, 16L * 1024 * 1024 * 1024),
            json.optInt("fragmentDays", 7).coerceIn(0, 365),
            json.optBoolean("allowHighRisk", false)
        )
    }

    private fun parseSelection(raw: String): Map<String, Boolean> {
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val result = HashMap<String, Boolean>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.length <= 4096) result[key] = json.optBoolean(key, false)
        }
        return result
    }

    private fun strings(array: JSONArray?): Set<String> {
        val result = LinkedHashSet<String>()
        if (array == null) return result
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotBlank()) result.add(value)
        }
        return result
    }

    private fun candidate(
        profile: String,
        category: String,
        label: String,
        risk: String,
        file: File,
        packageName: String = "",
        appName: String = "",
        deleteRoot: Boolean = false,
        note: String = ""
    ): Candidate {
        val path = canonical(file)
        return Candidate("$profile:$path", profile, category, label, risk, path, packageName, appName, deleteRoot, note = note)
    }

    private fun profile(id: String, title: String, subtitle: String, risk: String): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("subtitle", subtitle).put("risk", risk)

    private fun label(id: String): String = when (id) {
        "empty" -> "空项目"
        "rules" -> "规则垃圾"
        "fragments" -> "残留碎片"
        "deep" -> "深度规则"
        "corpses" -> "卸载残留"
        else -> id
    }

    private fun storageRoots(): List<File> {
        val result = ArrayList<File>()
        val emulated = File("/storage/emulated")
        val users = emulated.listFiles()
        if (users != null) {
            for (file in users) if (file.isDirectory && file.name.all { it.isDigit() }) result.add(file)
        }
        if (result.isEmpty() && File("/sdcard").isDirectory) result.add(File("/sdcard"))
        return result.distinctBy { canonical(it) }
    }

    private fun logRoots(): List<File> = listOf(
        File("/data/anr"), File("/data/tombstones"), File("/data/system/dropbox"),
        File("/data/system/heapdump"), File("/data/misc/logd"), File("/data/vendor/log"), File("/data/log")
    ).filter { it.isDirectory && !isSymlink(it) }

    private fun rulesDirectory(): File? = listOf(
        File("/data/adb/modules/baize_v2/config"),
        File("/data/adb/modules/safesweep/config")
    ).firstOrNull { it.isDirectory }

    private fun deepRules(): File? = rulesDirectory()?.resolve("deep.rules")?.takeIf { it.isFile }

    private fun installedPackages(): Map<String, String> = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA).associate { info ->
            info.packageName to context.packageManager.getApplicationLabel(info).toString().ifBlank { info.packageName }
        }
    }.getOrDefault(emptyMap())

    private fun risk(path: String): String {
        val value = path.lowercase()
        return when {
            CRITICAL.any { value.contains(it) } -> "critical"
            HIGH.any { value.contains(it) } -> "high"
            MEDIUM.any { value.contains(it) } -> "medium"
            else -> "low"
        }
    }

    private fun hardProtected(path: String): Boolean {
        val normalized = path.trimEnd('/').ifBlank { "/" }
        if (HARD_EXACT.contains(normalized)) return true
        if (READ_ONLY.any { normalized == it || normalized.startsWith("$it/") }) return true
        return normalized == "/data/adb" || normalized.startsWith("/data/adb/") ||
            normalized.contains("/.ssh/") || normalized.contains("/.gnupg/")
    }

    private fun mutationRoot(path: String): Boolean = path.startsWith("/data/user/") ||
        path.startsWith("/data/data/") || path.startsWith("/data/anr/") ||
        path.startsWith("/data/tombstones/") || path.startsWith("/data/system/dropbox/") ||
        path.startsWith("/data/system/heapdump/") || path.startsWith("/data/misc/logd/") ||
        path.startsWith("/data/vendor/log/") || path.startsWith("/data/log/") ||
        path.startsWith("/storage/emulated/") || path.startsWith("/sdcard/")

    private fun whitelisted(candidate: Candidate, options: Options): Boolean {
        if (candidate.packageName.isNotBlank() && options.whitelistPackages.contains(candidate.packageName)) return true
        val path = candidate.path.trimEnd('/')
        return options.whitelistPaths.any { raw ->
            val protected = raw.trimEnd('/')
            path == protected || path.startsWith("$protected/") || protected.startsWith("$path/")
        }
    }

    private fun prune(file: File): Boolean {
        val name = file.name.lowercase()
        return SHARED_PROTECTED.contains(name) || HIDDEN_PROTECTED.contains(name) || canonical(file).contains("/Android/media/")
    }

    private fun protectedDirectoryName(name: String): Boolean = HIDDEN_PROTECTED.contains(name.lowercase())

    private fun placeholder(name: String): Boolean {
        val lower = name.lowercase()
        return lower == ".nomedia" || lower == ".keep" || lower == ".gitkeep" || lower == ".placeholder" || lower.endsWith(".lock")
    }

    private fun isEmptyDirectory(file: File): Boolean = file.isDirectory && (file.list()?.isEmpty() == true)

    private fun packageName(value: String): Boolean = value.contains('.') && value.length <= 255 && value.all { it.isLetterOrDigit() || it == '.' || it == '_' }

    private fun corpsePath(path: String): Boolean = Regex("^/storage/emulated/[0-9]+/Android/(data|obb)/[^/]+$").matches(path) ||
        Regex("^/sdcard/Android/(data|obb)/[^/]+$").matches(path)

    private fun mountPoints(): Set<String> = runCatching {
        File("/proc/self/mountinfo").useLines { lines ->
            lines.mapNotNull { line -> line.substringBefore(" - ").split(' ').getOrNull(4) }
                .map { it.replace("\\040", " ") }
                .toSet()
        }
    }.getOrDefault(emptySet())

    private fun isSymlink(file: File): Boolean = runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    private fun canonical(file: File): String = runCatching { file.canonicalFile.path }.getOrDefault(file.absoluteFile.normalize().path)

    private fun sha256(file: File?): String {
        if (file == null || !file.isFile) return ""
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }.getOrDefault("")
    }

    private fun detail(candidate: Candidate, action: String, reason: String, bytes: Long, files: Long, directories: Long): JSONObject = JSONObject()
        .put("id", candidate.id)
        .put("action", action)
        .put("reason", reason)
        .put("profile", candidate.profile)
        .put("risk", candidate.risk)
        .put("category", candidate.label)
        .put("path", candidate.path)
        .put("bytes", bytes)
        .put("files", files)
        .put("directories", directories)

    private fun validSnapshot(id: String): Snapshot? {
        val snapshot = snapshots[id] ?: return null
        if (System.currentTimeMillis() - snapshot.createdAt > SNAPSHOT_TTL_MS) {
            snapshots.remove(id)
            return null
        }
        return snapshot
    }

    private fun pruneSnapshots() {
        val now = System.currentTimeMillis()
        for ((key, value) in snapshots) if (now - value.createdAt > SNAPSHOT_TTL_MS) snapshots.remove(key)
    }

    private fun stop(started: Long, budget: Long): Boolean = cancelled.get() || SystemClock.elapsedRealtime() - started >= budget

    companion object {
        private const val SNAPSHOT_TTL_MS = 30L * 60_000L
        private const val SCAN_TOTAL_MS = 90_000L
        private const val DEEP_SCAN_TOTAL_MS = 5L * 60_000L
        private const val PAGE_BUDGET_MS = 8_000L
        private const val ITEM_MEASURE_MS = 1_500L
        private const val ITEM_CLEAN_MS = 20_000L
        private const val CLEAN_TOTAL_MS = 5L * 60_000L
        private const val DEFAULT_MAX_FILE_BYTES = 512L * 1024 * 1024
        private const val MAX_PAGE_SIZE = 60
        private const val MAX_CANDIDATES = 20_000
        private const val MAX_RULE_LINES = 12_000
        private const val MAX_EXPANSIONS = 256

        private val HARD_EXACT = setOf(
            "/", "/data", "/data/adb", "/data/system", "/data/misc", "/storage", "/storage/emulated", "/sdcard"
        )
        private val READ_ONLY = setOf(
            "/system", "/vendor", "/product", "/odm", "/apex", "/proc", "/sys", "/dev", "/metadata"
        )
        private val SHARED_PROTECTED = setOf(
            "android", "dcim", "pictures", "movies", "music", "download", "downloads", "documents",
            "audiobooks", "podcasts", "ringtones", "notifications", "alarms"
        )
        private val HIDDEN_PROTECTED = setOf(
            ".git", ".ssh", ".termux", ".config", ".local", ".obsidian", ".android", ".vscode", ".gnupg"
        )
        private val CRITICAL = setOf(
            "/download", "/documents", "/dcim", "/pictures", "/movies", "/music", "/android/obb",
            "/databases", "/shared_prefs", "backup", "draft", ".db"
        )
        private val HIGH = setOf("/files", "/app_webview", "/webview", "/user_data", "/profile")
        private val MEDIUM = setOf("tombstone", "minidump", "heapdump", "crash", "trace", "dump", "debug")
    }
}
