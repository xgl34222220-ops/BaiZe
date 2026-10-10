package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.RootServiceClients
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Root 只读扫描得到的 QQ / TIM / 微信文件。
 *
 * 这些文件大多位于 Android/data：MediaStore 不索引，App 进程也读不到，因此没有系统索引 URI。
 * 文件身份（设备号 / inode / 大小 / 修改时间）由 Root 读取；取得身份的行可以勾选，
 * 确认后由 Root 逐项再核对身份并移入回收站（隔离区，同分区移动、有数量与容量上限），不做永久删除。
 * `Cache_*` 缓存副本按低风险处理，可进入“全选”；其余文件只能逐项勾选。
 */
internal object ChatStorageRecords {
    const val URI_PREFIX = "baize-root:"
    const val ROOT_LABEL = "Root"
    const val IDENTITY_MISSING_LABEL = "Root · 未取得文件身份，请重新扫描"
    /** 单次发给 Root 的文件数；Root 侧上限为 2000。 */
    private const val TRASH_CHUNK = 500

    data class Scan(val records: List<StorageFileRecord>, val ageBuckets: Map<Int, Int>, val truncated: Boolean,
                    val error: String = "")

    fun isRootRecord(record: StorageFileRecord): Boolean = record.uri.startsWith(URI_PREFIX)

    fun fetch(remote: IProfileRootService?, cacheDir: File, apksOnly: Boolean): Scan {
        if (remote == null) return Scan(emptyList(), emptyMap(), false, "root_unavailable")
        return runCatching {
            parse(RootServiceClients.profileExchange(remote, cacheDir, "scanChatStorage", JSONArray().put(if (apksOnly) "apk" else "all")))
        }.getOrElse { Scan(emptyList(), emptyMap(), false, it.javaClass.simpleName) }
    }

    fun parse(raw: String): Scan {
        val json = JSONObject(raw)
        if (!json.optBoolean("success")) return Scan(emptyList(), emptyMap(), false, json.optString("message", "scan_failed"))
        val items = json.optJSONArray("items") ?: JSONArray()
        val records = ArrayList<StorageFileRecord>(items.length())
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val path = item.optString("path")
            if (!StorageMediaRepository.safeSharedFile(path) && !path.startsWith("/data/media/")) continue
            val name = item.optString("name").ifBlank { path.substringAfterLast('/') }
            val kind = item.optString("kind")
            val bytes = item.optLong("bytes").coerceAtLeast(0L)
            val modified = item.optLong("modified").coerceAtLeast(0L)
            val device = item.optLong("device", -1L)
            val inode = item.optLong("inode", -1L)
            val identity = if (device >= 0L && inode >= 0L && bytes > 0L && modified > 0L)
                ApkFileIdentity(path, device, inode, bytes, modified, item.optLong("changed", 0L).coerceAtLeast(0L), 0L, 0L) else null
            records += StorageFileRecord(
                id = -1L - index, uri = URI_PREFIX + path, path = path, name = name,
                bytes = bytes, modifiedSeconds = modified,
                mime = mimeFor(kind), ownerLabel = "${item.optString("app")} · ${item.optString("area")}", identity = identity
            )
        }
        val ages = LinkedHashMap<Int, Int>()
        json.optJSONObject("ageBuckets")?.let { buckets -> buckets.keys().forEach { key -> key.toIntOrNull()?.let { ages[it] = buckets.optInt(key) } } }
        return Scan(records, ages, json.optBoolean("truncated"))
    }

    /** 微信 image2 / video / voice2 的文件常无扩展名，按 Root 侧给出的类型补一个 MIME，分类才正确。 */
    private fun mimeFor(kind: String): String = when (kind) {
        "image" -> "image/*"; "video" -> "video/*"; "audio" -> "audio/*"
        "apk" -> "application/vnd.android.package-archive"
        else -> ""
    }

    fun isCache(record: StorageFileRecord): Boolean = isRootRecord(record) &&
        io.github.xgl34222220.baize.root.ChatAppPaths.isCacheName(record.name)

    /**
     * 把已勾选的 Root 记录交给 Root 移入回收站（隔离区）。返回 uri → 结果；Root 不可用时全部保留。
     * 每个文件在 Root 侧重新核对路径、聊天目录、文件类型与身份，变化的文件保留。
     */
    fun trash(remote: IProfileRootService?, cacheDir: File, records: List<StorageFileRecord>, cancelled: () -> Boolean = { false },
              progress: (Int) -> Unit = {}): Map<String, StorageDeleteOutcome> {
        val results = LinkedHashMap<String, StorageDeleteOutcome>()
        val byPath = records.filter(::isRootRecord).associateBy { it.path }
        if (remote == null) {
            byPath.values.forEach { results[it.uri] = StorageDeleteOutcome(ApkIndexedDeleteResult.PROTECTION_UNAVAILABLE, "Root 服务未连接，文件未处理") }
            return results
        }
        var done = 0
        for (chunk in byPath.values.chunked(TRASH_CHUNK)) {
            if (cancelled()) break
            val request = JSONArray()
            chunk.forEach { record ->
                val identity = record.identity
                request.put(JSONObject().put("path", record.path).put("bytes", record.bytes).put("modified", record.modifiedSeconds)
                    .put("device", identity?.device ?: -1L).put("inode", identity?.inode ?: -1L))
            }
            val raw = runCatching { RootServiceClients.profileExchange(remote, cacheDir, "trashChatFiles", JSONArray().put(request)) }
                .getOrElse { error ->
                    chunk.forEach { results[it.uri] = StorageDeleteOutcome(ApkIndexedDeleteResult.FAILED, "Root 处理失败：${error.javaClass.simpleName}") }
                    null
                } ?: continue
            parseTrash(raw, byPath).forEach { (uri, outcome) -> results[uri] = outcome }
            chunk.forEach { results.putIfAbsent(it.uri, StorageDeleteOutcome(ApkIndexedDeleteResult.FAILED, "Root 未返回该文件的结果")) }
            done += chunk.size
            progress(done)
        }
        return results
    }

    fun parseTrash(raw: String, byPath: Map<String, StorageFileRecord>): Map<String, StorageDeleteOutcome> {
        val json = JSONObject(raw)
        val out = LinkedHashMap<String, StorageDeleteOutcome>()
        val details = json.optJSONArray("details") ?: JSONArray()
        for (index in 0 until details.length()) {
            val detail = details.optJSONObject(index) ?: continue
            val record = byPath[detail.optString("path")] ?: continue
            out[record.uri] = when (detail.optString("action")) {
                "quarantined" -> StorageDeleteOutcome(ApkIndexedDeleteResult.DELETED, trashed = true)
                "failed" -> StorageDeleteOutcome(ApkIndexedDeleteResult.FAILED, detail.optString("reason"))
                else -> StorageDeleteOutcome(ApkIndexedDeleteResult.CHANGED, detail.optString("reason"))
            }
        }
        return out
    }

    /** 合并时以路径去重，系统索引里已有的文件（可勾选）优先。 */
    fun merge(indexed: List<StorageFileRecord>, root: List<StorageFileRecord>): List<StorageFileRecord> {
        val paths = indexed.mapTo(HashSet()) { it.path }
        return indexed + root.filter { it.path !in paths }
    }
}
