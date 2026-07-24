package io.github.xgl34222220.baize.root

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Derives an immutable cache-clean subset from an existing server-side snapshot.
 *
 * The UI may only authorize candidate roots already present in cache_scan.items.tsv. The manifest
 * is filtered by matching package/category/root relationships; no new deletion path is accepted.
 * The original namespace is replaced atomically because every clean consumes the snapshot anyway.
 */
internal class CacheSelectionRepository(
    private val stateDir: File = File(RootPaths.STATE_DIR)
) {
    private data class CacheCandidate(
        val packageName: String,
        val category: String,
        val files: Long,
        val bytes: Long,
        val directories: Long,
        val path: String
    )

    fun prepare(snapshotId: String?, selectionJson: String?): String = runCatching {
        val requestedId = snapshotId.orEmpty().trim()
        if (requestedId.isBlank()) return error("snapshot_required", "没有可用的缓存扫描快照")

        val stateFile = File(stateDir, "cache_scan.env")
        val targetsFile = File(stateDir, "cache_scan.targets")
        val itemsFile = File(stateDir, "cache_scan.items.tsv")
        val manifestFile = File(stateDir, "cache_scan.manifest0")
        val whitelistFile = File(stateDir, "whitelist.conf")
        val packageWhitelistFile = File(stateDir, "native-cache-packages.conf")
        val required = listOf(stateFile, targetsFile, itemsFile, manifestFile)
        if (required.any { !it.isFile }) return error("snapshot_missing", "缓存扫描快照不完整，请重新扫描")

        val state = readEnv(stateFile)
        if (state["snapshot_id"] != requestedId) return error("snapshot_changed", "缓存扫描快照已变化，请重新打开结果")
        val epoch = state["epoch"]?.toLongOrNull() ?: 0L
        val age = System.currentTimeMillis() / 1_000L - epoch
        if (epoch <= 0L || age !in 0L..SNAPSHOT_TTL_SECONDS) {
            return error("snapshot_expired", "缓存扫描快照已过期，请重新扫描")
        }
        if (state["manifest_format"] != "nul-v2") return error("unsupported_manifest", "缓存快照格式不受支持")

        verifyHash(targetsFile, state["targets_sha"], "缓存目标快照")
        verifyHash(itemsFile, state["items_sha"], "缓存摘要快照")
        verifyHash(manifestFile, state["manifest_sha"], "缓存逐文件快照")
        verifyHash(whitelistFile, state["whitelist_sha"], "路径白名单")
        verifyHash(packageWhitelistFile, state["package_whitelist_sha"], "应用白名单")

        val selection = runCatching { JSONObject(selectionJson.orEmpty()) }.getOrDefault(JSONObject())
        if (selection.optBoolean("__all_safe__", false)) {
            val candidates = parseItems(itemsFile)
            return JSONObject()
                .put("success", true)
                .put("snapshotId", requestedId)
                .put("selectedCandidates", candidates.size)
                .put("selectedFiles", candidates.sumOf { it.files })
                .put("selectedBytes", candidates.sumOf { it.bytes })
                .put("unchanged", true)
                .toString()
        }

        val candidates = parseItems(itemsFile)
        val selected = candidates.filter { selection.optBoolean(it.path, false) }
        if (selected.isEmpty()) return error("empty_selection", "没有勾选任何应用缓存项目")
        if (selected.size > MAX_SELECTED_CANDIDATES) return error("selection_too_large", "勾选项目过多，请减少后重试")

        val roots = selected.groupBy { "${it.packageName}\u0000${it.category}" }
            .mapValues { (_, values) -> values.map { normalizePath(it.path) }.distinct().sortedByDescending(String::length) }

        stateDir.mkdirs()
        val token = "${android.os.Process.myPid()}-${System.nanoTime()}"
        val targetsTmp = File(stateDir, "cache_scan.targets.tmp.$token")
        val itemsTmp = File(stateDir, "cache_scan.items.tsv.tmp.$token")
        val manifestTmp = File(stateDir, "cache_scan.manifest0.tmp.$token")
        val stateTmp = File(stateDir, "cache_scan.env.tmp.$token")
        val tempFiles = listOf(targetsTmp, itemsTmp, manifestTmp, stateTmp)

        try {
            targetsTmp.bufferedWriter().use { writer ->
                selected.forEach { writer.append(it.path).append('\n') }
            }
            itemsTmp.bufferedWriter().use { writer ->
                writer.append("package\tcategory\tfiles\tbytes\tdirectories\tpath\n")
                selected.forEach { item ->
                    writer.append(item.packageName).append('\t')
                        .append(item.category).append('\t')
                        .append(item.files.toString()).append('\t')
                        .append(item.bytes.toString()).append('\t')
                        .append(item.directories.toString()).append('\t')
                        .append(item.path).append('\n')
                }
            }

            var manifestRecords = 0L
            FileInputStream(manifestFile).use { input ->
                FileOutputStream(manifestTmp).buffered().use { output ->
                    while (true) {
                        val record = readManifestRecord(input) ?: break
                        val packageName = record[0]
                        val category = record[1]
                        val path = normalizePath(record[9])
                        val allowedRoots = roots["$packageName\u0000$category"].orEmpty()
                        if (allowedRoots.any { root -> path == root || path.startsWith("$root/") }) {
                            record.forEach { field ->
                                output.write(field.toByteArray(Charsets.UTF_8))
                                output.write(0)
                            }
                            manifestRecords += 1L
                        }
                    }
                }
            }
            if (manifestRecords <= 0L) return error("empty_manifest", "所选缓存项目没有可授权文件，请重新扫描")

            val nextState = LinkedHashMap(state)
            val now = System.currentTimeMillis() / 1_000L
            val manifestSha = sha256(manifestTmp)
            val derivedId = "$now-selected-${manifestSha.take(16)}"
            nextState["epoch"] = now.toString()
            nextState["snapshot_id"] = derivedId
            nextState["targets_sha"] = sha256(targetsTmp)
            nextState["items_sha"] = sha256(itemsTmp)
            nextState["manifest_sha"] = manifestSha
            nextState["manifest_items"] = manifestRecords.toString()
            nextState["files"] = manifestRecords.toString()
            nextState["bytes"] = selected.sumOf { it.bytes.coerceAtLeast(0L) }.toString()
            nextState["items"] = selected.size.toString()
            nextState["targets"] = selected.size.toString()
            nextState["selection_parent_snapshot"] = requestedId
            stateTmp.bufferedWriter().use { writer ->
                nextState.forEach { (key, value) -> writer.append(key).append('=').append(value).append('\n') }
            }

            publish(targetsTmp, targetsFile)
            publish(itemsTmp, itemsFile)
            publish(manifestTmp, manifestFile)
            publish(stateTmp, stateFile)
            listOf(targetsFile, itemsFile, manifestFile, stateFile).forEach {
                it.setReadable(true, true)
                it.setWritable(true, true)
            }

            JSONObject()
                .put("success", true)
                .put("snapshotId", derivedId)
                .put("parentSnapshotId", requestedId)
                .put("selectedCandidates", selected.size)
                .put("selectedFiles", manifestRecords)
                .put("selectedBytes", selected.sumOf { it.bytes.coerceAtLeast(0L) })
                .toString()
        } finally {
            tempFiles.forEach { if (it.exists()) it.delete() }
        }
    }.getOrElse { throwable ->
        error("cache_selection_failed", throwable.message ?: throwable.javaClass.simpleName)
    }

    private fun parseItems(file: File): List<CacheCandidate> = buildList {
        file.forEachLine { raw ->
            val columns = raw.split('\t', limit = 6)
            if (columns.size < 6 || columns[0] == "package") return@forEachLine
            val packageName = columns[0].trim()
            val category = columns[1].trim()
            val path = normalizePath(columns[5])
            if (!RootValidation.packageName.matches(packageName) || !path.startsWith('/')) return@forEachLine
            add(
                CacheCandidate(
                    packageName = packageName,
                    category = category,
                    files = columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                    bytes = columns[3].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                    directories = columns[4].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                    path = path
                )
            )
        }
    }

    private fun readManifestRecord(input: FileInputStream): List<String>? {
        val first = readNulField(input) ?: return null
        val fields = ArrayList<String>(MANIFEST_FIELD_COUNT)
        fields += first
        repeat(MANIFEST_FIELD_COUNT - 1) {
            fields += readNulField(input) ?: error("缓存逐文件快照记录不完整")
        }
        return fields
    }

    private fun readNulField(input: FileInputStream): String? {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (output.size() == 0) null else error("缓存逐文件快照字段未结束")
            if (value == 0) return output.toString(Charsets.UTF_8.name())
            if (output.size() >= MAX_MANIFEST_FIELD_BYTES) error("缓存逐文件快照字段过长")
            output.write(value)
        }
    }

    private fun readEnv(file: File): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith('#') || !line.contains('=')) return@forEachLine
            result[line.substringBefore('=').trim()] = line.substringAfter('=').trim()
        }
        return result
    }

    private fun verifyHash(file: File, expected: String?, label: String) {
        if (expected.isNullOrBlank() || sha256(file) != expected) error("$label 校验失败，请重新扫描")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1_024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun normalizePath(raw: String): String {
        val value = raw.trim().replace(Regex("/+"), "/")
        if (!value.startsWith('/') || value.contains('\u0000') || value.contains('\n') || value.contains('\r')) return ""
        return value.trimEnd('/').ifBlank { "/" }
    }

    private fun publish(source: File, target: File) {
        if (target.exists() && !target.delete()) error("无法替换 ${target.name}")
        if (!source.renameTo(target)) error("无法发布 ${target.name}")
    }

    private fun error(code: String, message: String): String = JSONObject()
        .put("success", false)
        .put("error", code)
        .put("message", message)
        .toString()

    companion object {
        private const val SNAPSHOT_TTL_SECONDS = 30L * 60L
        private const val MANIFEST_FIELD_COUNT = 10
        private const val MAX_MANIFEST_FIELD_BYTES = 8 * 1_024
        private const val MAX_SELECTED_CANDIDATES = 20_000
    }
}
