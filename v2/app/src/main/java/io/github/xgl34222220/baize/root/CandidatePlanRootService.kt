package io.github.xgl34222220.baize.root

import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Converts broad scan snapshots into the exact immutable candidate set selected by the user.
 *
 * The service never discovers new paths. It only removes unselected candidates from the two
 * already-authorized snapshots and commits both snapshot changes as one rollback-capable operation.
 */
class CandidatePlanRootService : RootService() {
    private val running = AtomicBoolean(false)

    private val binder = object : ICandidatePlanService.Stub() {
        override fun ping(): String = JSONObject()
            .put("uid", Process.myUid())
            .put("root", Process.myUid() == 0)
            .put("engine", "candidate-plan-v1")
            .put("running", running.get())
            .toString()

        override fun finalizePlan(
            cacheSnapshotId: String?,
            safeSnapshotId: String?,
            selectionJson: String?
        ): String {
            if (!running.compareAndSet(false, true)) {
                return error("busy", "正在固化另一份清理计划")
            }
            return try {
                finalizePlanInternal(
                    cacheSnapshotId.orEmpty().trim(),
                    safeSnapshotId.orEmpty().trim(),
                    selectionJson.orEmpty()
                )
            } catch (throwable: Throwable) {
                error("selection_finalize_failed", throwable.message ?: throwable.javaClass.simpleName)
            } finally {
                running.set(false)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private data class CacheItem(
        val packageName: String,
        val category: String,
        val files: Long,
        val bytes: Long,
        val directories: Long,
        val path: String
    ) {
        val id: String get() = "cache:$path"

        fun row(): String = listOf(
            packageName,
            category,
            files.toString(),
            bytes.toString(),
            directories.toString(),
            path
        ).joinToString("\t")
    }

    private data class CacheStage(
        val directory: File,
        val oldSnapshotId: String,
        val newSnapshotId: String,
        val selectedCount: Int,
        val selectedBytes: Long,
        val removeSnapshot: Boolean,
        val originals: List<File>,
        var committed: Boolean = false
    )

    private data class SafeStage(
        val directory: File,
        val oldFile: File?,
        val newFile: File?,
        val oldSnapshotId: String,
        val newSnapshotId: String,
        val selectedCount: Int,
        val selectedBytes: Long,
        val categoryCounts: JSONObject,
        val riskCounts: JSONObject,
        var committed: Boolean = false
    )

    private fun finalizePlanInternal(cacheId: String, safeId: String, rawSelection: String): String {
        val selection = runCatching { JSONObject(rawSelection) }.getOrElse {
            return error("invalid_selection", "候选选择数据无效")
        }
        val selectAllSafe = selection.optBoolean("__all_safe__", false)
        if (!selectAllSafe) return error("selection_required", "必须明确授权候选项目")
        val excluded = jsonStrings(selection.optJSONArray("__exclude__"))

        val token = UUID.randomUUID().toString()
        val root = File(RootPaths.STATE_DIR, "candidate-plan-stage/$token").apply { mkdirs() }
        var cacheStage: CacheStage? = null
        var safeStage: SafeStage? = null
        try {
            cacheStage = prepareCache(root, cacheId, excluded)
            safeStage = prepareSafe(root, safeId, excluded)
            val selected = (cacheStage?.selectedCount ?: 0) + (safeStage?.selectedCount ?: 0)
            if (selected <= 0) return error("empty_selection", "当前没有选中任何可清理项目")

            cacheStage?.let(::commitCache)
            try {
                safeStage?.let(::commitSafe)
            } catch (throwable: Throwable) {
                cacheStage?.let(::rollbackCache)
                throw throwable
            }

            return JSONObject()
                .put("success", true)
                .put("schema", "candidate-plan-v1")
                .put("cacheSnapshotId", cacheStage?.newSnapshotId.orEmpty())
                .put("cacheCount", cacheStage?.selectedCount ?: 0)
                .put("cacheBytes", cacheStage?.selectedBytes ?: 0L)
                .put("safeSnapshotId", safeStage?.newSnapshotId.orEmpty())
                .put("safeCount", safeStage?.selectedCount ?: 0)
                .put("safeBytes", safeStage?.selectedBytes ?: 0L)
                .put("selectedCount", selected)
                .put("selectedBytes", (cacheStage?.selectedBytes ?: 0L) + (safeStage?.selectedBytes ?: 0L))
                .put("categoryCounts", safeStage?.categoryCounts ?: JSONObject())
                .put("riskCounts", safeStage?.riskCounts ?: JSONObject())
                .toString()
        } finally {
            if (safeStage?.committed != true) safeStage?.let(::rollbackSafe)
            if (cacheStage?.committed != true) cacheStage?.let(::rollbackCache)
            root.deleteRecursively()
        }
    }

    private fun prepareCache(root: File, snapshotId: String, excluded: Set<String>): CacheStage? {
        if (snapshotId.isBlank()) return null
        val stateDir = File(RootPaths.STATE_DIR)
        val state = File(stateDir, "cache_scan.env")
        val targets = File(stateDir, "cache_scan.targets")
        val items = File(stateDir, "cache_scan.items.tsv")
        val manifest = File(stateDir, "cache_scan.manifest0")
        val originals = listOf(state, targets, items, manifest)
        if (originals.any { !it.isFile }) throw IllegalStateException("缓存快照文件不完整")

        val env = readEnv(state)
        val epoch = env["epoch"]?.toLongOrNull() ?: 0L
        if (env["snapshot_id"].orEmpty() != snapshotId ||
            epoch <= 0L ||
            System.currentTimeMillis() / 1000L - epoch !in 0L..SNAPSHOT_TTL_SECONDS
        ) {
            throw IllegalStateException("缓存快照已过期或发生变化")
        }
        requireHash(targets, env["targets_sha"], "缓存目标")
        requireHash(items, env["items_sha"], "缓存摘要")
        requireHash(manifest, env["manifest_sha"], "缓存逐文件清单")

        val parsed = parseCacheItems(items)
        val selected = parsed.filterNot { it.id in excluded || it.path in excluded }
        val selectedRoots = selected.mapTo(HashSet()) { it.path.trimEnd('/') }
        val stage = File(root, "cache").apply { mkdirs() }
        val stageTargets = File(stage, targets.name)
        val stageItems = File(stage, items.name)
        val stageManifest = File(stage, manifest.name)
        val stageState = File(stage, state.name)

        if (selected.isEmpty()) {
            return CacheStage(
                directory = stage,
                oldSnapshotId = snapshotId,
                newSnapshotId = "",
                selectedCount = 0,
                selectedBytes = 0L,
                removeSnapshot = true,
                originals = originals
            )
        }

        stageTargets.bufferedWriter().use { writer ->
            selected.forEach { writer.appendLine(it.path) }
        }
        stageItems.bufferedWriter().use { writer ->
            writer.appendLine("package\tcategory\tfiles\tbytes\tdirectories\tpath")
            selected.forEach { writer.appendLine(it.row()) }
        }

        var manifestItems = 0L
        BufferedInputStream(FileInputStream(manifest), 64 * 1024).use { input ->
            BufferedOutputStream(FileOutputStream(stageManifest), 64 * 1024).use { output ->
                while (true) {
                    val record = readManifestRecord(input) ?: break
                    val path = record[MANIFEST_PATH_INDEX].toString(Charsets.UTF_8)
                    if (underSelectedRoot(path, selectedRoots)) {
                        record.forEach { field ->
                            output.write(field)
                            output.write(0)
                        }
                        manifestItems += 1L
                    }
                }
            }
        }

        val expectedFiles = selected.sumOf { it.files }
        if (manifestItems <= 0L || manifestItems != expectedFiles) {
            throw IllegalStateException("缓存候选与逐文件清单不一致：候选 $expectedFiles，清单 $manifestItems")
        }

        val manifestSha = sha256(stageManifest)
        val newSnapshotId = "$epoch-${manifestSha.take(16)}"
        env["snapshot_id"] = newSnapshotId
        env["targets_sha"] = sha256(stageTargets)
        env["items_sha"] = sha256(stageItems)
        env["manifest_sha"] = manifestSha
        env["manifest_format"] = "nul-v2"
        env["manifest_items"] = manifestItems.toString()
        env["bytes"] = selected.sumOf { it.bytes }.toString()
        env["files"] = manifestItems.toString()
        env["items"] = selected.size.toString()
        env["targets"] = selected.size.toString()
        writeEnv(stageState, env)
        listOf(stageState, stageTargets, stageItems, stageManifest).forEach(::ownerOnly)

        return CacheStage(
            directory = stage,
            oldSnapshotId = snapshotId,
            newSnapshotId = newSnapshotId,
            selectedCount = selected.size,
            selectedBytes = selected.sumOf { it.bytes },
            removeSnapshot = false,
            originals = originals
        )
    }

    private fun prepareSafe(root: File, snapshotId: String, excluded: Set<String>): SafeStage? {
        if (snapshotId.isBlank()) return null
        val normalized = runCatching { UUID.fromString(snapshotId).toString() }.getOrElse {
            throw IllegalStateException("安全项目快照标识无效")
        }
        val snapshots = File(RootPaths.STATE_DIR, "profile-snapshots").apply { mkdirs() }
        val oldFile = File(snapshots, "$normalized.json")
        if (!oldFile.isFile) throw IllegalStateException("安全项目快照不存在或已过期")
        val source = runCatching { JSONObject(oldFile.readText()) }.getOrElse {
            throw IllegalStateException("安全项目快照损坏")
        }
        val createdAt = source.optLong("createdAt", 0L)
        if (source.optInt("version", 0) != SAFE_SNAPSHOT_VERSION ||
            source.optString("snapshotId") != normalized ||
            createdAt <= 0L ||
            System.currentTimeMillis() - createdAt !in 0L..SAFE_SNAPSHOT_TTL_MS
        ) {
            throw IllegalStateException("安全项目快照已过期")
        }

        val sourceItems = source.optJSONArray("items") ?: JSONArray()
        val selectedItems = JSONArray()
        var selectedBytes = 0L
        val categoryCounts = JSONObject()
        val riskCounts = JSONObject()
        for (index in 0 until sourceItems.length()) {
            val item = sourceItems.optJSONObject(index) ?: continue
            val risk = item.optString("risk")
            val id = item.optString("id")
            val path = item.optString("path")
            val allowed = risk == "low" || risk == "medium"
            val rejected = id in excluded || path in excluded || "safe:$id" in excluded
            if (!allowed || rejected) continue
            selectedItems.put(item)
            selectedBytes += item.optLong("bytes", 0L).coerceAtLeast(0L)
            increment(categoryCounts, item.optString("category", "other"))
            increment(riskCounts, risk.ifBlank { "low" })
        }

        val stage = File(root, "safe").apply { mkdirs() }
        val backup = File(stage, "original.json")
        oldFile.copyTo(backup, overwrite = true)
        if (selectedItems.length() == 0) {
            return SafeStage(
                directory = stage,
                oldFile = oldFile,
                newFile = null,
                oldSnapshotId = snapshotId,
                newSnapshotId = "",
                selectedCount = 0,
                selectedBytes = 0L,
                categoryCounts = categoryCounts,
                riskCounts = riskCounts
            )
        }

        val newId = UUID.randomUUID().toString()
        val payload = JSONObject(source.toString())
            .put("snapshotId", newId)
            .put("items", selectedItems)
            .put(
                "summary",
                JSONObject(source.optJSONObject("summary")?.toString() ?: "{}")
                    .put("snapshotId", newId)
                    .put("totalCandidates", selectedItems.length())
                    .put("low", riskCounts.optInt("low"))
                    .put("medium", riskCounts.optInt("medium"))
                    .put("high", 0)
                    .put("critical", 0)
                    .put("knownBytes", selectedBytes)
            )
        val staged = File(stage, "$newId.json")
        staged.writeText(payload.toString())
        ownerOnly(staged)
        return SafeStage(
            directory = stage,
            oldFile = oldFile,
            newFile = staged,
            oldSnapshotId = snapshotId,
            newSnapshotId = newId,
            selectedCount = selectedItems.length(),
            selectedBytes = selectedBytes,
            categoryCounts = categoryCounts,
            riskCounts = riskCounts
        )
    }

    private fun commitCache(stage: CacheStage) {
        val backup = File(stage.directory, "backup").apply { mkdirs() }
        stage.originals.forEach { source ->
            source.copyTo(File(backup, source.name), overwrite = true)
        }
        try {
            if (stage.removeSnapshot) {
                stage.originals.forEach { it.delete() }
            } else {
                val ordered = stage.originals.filter { it.name != "cache_scan.env" } +
                    stage.originals.first { it.name == "cache_scan.env" }
                ordered.forEach { destination ->
                    val source = File(stage.directory, destination.name)
                    replaceFile(source, destination)
                }
            }
            stage.committed = true
        } catch (throwable: Throwable) {
            rollbackCache(stage)
            throw throwable
        }
    }

    private fun rollbackCache(stage: CacheStage) {
        val backup = File(stage.directory, "backup")
        if (!backup.isDirectory) return
        stage.originals.forEach { destination ->
            val source = File(backup, destination.name)
            if (source.isFile) replaceFile(source, destination) else destination.delete()
        }
        stage.committed = false
    }

    private fun commitSafe(stage: SafeStage) {
        try {
            val old = stage.oldFile
            val staged = stage.newFile
            if (staged == null) {
                old?.delete()
            } else {
                val destination = File(old?.parentFile ?: File(RootPaths.STATE_DIR, "profile-snapshots"), staged.name)
                replaceFile(staged, destination)
                if (old != null && old != destination) old.delete()
            }
            stage.committed = true
        } catch (throwable: Throwable) {
            rollbackSafe(stage)
            throw throwable
        }
    }

    private fun rollbackSafe(stage: SafeStage) {
        val backup = File(stage.directory, "original.json")
        val old = stage.oldFile
        if (old != null && backup.isFile) replaceFile(backup, old)
        if (stage.newSnapshotId.isNotBlank()) {
            File(old?.parentFile ?: File(RootPaths.STATE_DIR, "profile-snapshots"), "${stage.newSnapshotId}.json").delete()
        }
        stage.committed = false
    }

    private fun parseCacheItems(file: File): List<CacheItem> = buildList {
        file.forEachLine { raw ->
            val columns = raw.split('\t', limit = 6)
            if (columns.size < 6 || columns[0] == "package") return@forEachLine
            val packageName = columns[0].trim()
            val path = columns[5].trim()
            if (packageName.isBlank() || !path.startsWith("/")) return@forEachLine
            add(
                CacheItem(
                    packageName = packageName,
                    category = columns[1].trim(),
                    files = columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                    bytes = columns[3].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                    directories = columns[4].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                    path = path
                )
            )
        }
    }

    private fun readManifestRecord(input: BufferedInputStream): List<ByteArray>? {
        val first = readNulField(input) ?: return null
        val fields = ArrayList<ByteArray>(MANIFEST_FIELDS)
        fields += first
        repeat(MANIFEST_FIELDS - 1) {
            fields += readNulField(input) ?: throw IllegalStateException("缓存清单记录不完整")
        }
        return fields
    }

    private fun readNulField(input: BufferedInputStream): ByteArray? {
        val output = ByteArrayOutputStream(128)
        while (true) {
            val value = input.read()
            if (value < 0) return if (output.size() == 0) null else throw IllegalStateException("缓存清单字段未结束")
            if (value == 0) return output.toByteArray()
            if (output.size() >= MAX_MANIFEST_FIELD_BYTES) throw IllegalStateException("缓存清单字段过长")
            output.write(value)
        }
    }

    private fun underSelectedRoot(path: String, roots: Set<String>): Boolean {
        var current = path.trimEnd('/')
        while (current.isNotEmpty()) {
            if (current in roots) return true
            val slash = current.lastIndexOf('/')
            if (slash <= 0) return "/" in roots
            current = current.substring(0, slash)
        }
        return false
    }

    private fun readEnv(file: File): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        file.forEachLine { raw ->
            val index = raw.indexOf('=')
            if (index > 0) result[raw.substring(0, index)] = raw.substring(index + 1)
        }
        return result
    }

    private fun writeEnv(file: File, values: Map<String, String>) {
        file.bufferedWriter().use { writer ->
            values.forEach { (key, value) ->
                writer.append(key).append('=').append(value.replace('\n', ' ').replace('\r', ' ')).append('\n')
            }
        }
    }

    private fun requireHash(file: File, expected: String?, label: String) {
        if (expected.isNullOrBlank() || sha256(file) != expected) {
            throw IllegalStateException("$label 快照校验失败")
        }
    }

    private fun replaceFile(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        source.copyTo(temporary, overwrite = true)
        ownerOnly(temporary)
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IllegalStateException("无法替换 ${destination.name}")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("无法提交 ${destination.name}")
        }
    }

    private fun ownerOnly(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun jsonStrings(array: JSONArray?): Set<String> {
        val result = LinkedHashSet<String>()
        if (array == null) return result
        for (index in 0 until array.length()) {
            array.optString(index).trim().takeIf { it.isNotBlank() }?.let(result::add)
        }
        return result
    }

    private fun increment(target: JSONObject, key: String) {
        target.put(key, target.optInt(key, 0) + 1)
    }

    private fun error(code: String, message: String): String = JSONObject()
        .put("error", code)
        .put("message", message)
        .toString()

    companion object {
        private const val SNAPSHOT_TTL_SECONDS = 30L * 60L
        private const val SAFE_SNAPSHOT_TTL_MS = 30L * 60_000L
        private const val SAFE_SNAPSHOT_VERSION = 1
        private const val MANIFEST_FIELDS = 10
        private const val MANIFEST_PATH_INDEX = 9
        private const val MAX_MANIFEST_FIELD_BYTES = 16 * 1024
    }
}
