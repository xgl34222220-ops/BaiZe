package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.RootServiceClients
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Root 只读扫描得到的 QQ / TIM / 微信文件。
 *
 * 这些文件大多位于 Android/data：MediaStore 不索引，App 进程也读不到，因此没有系统索引 URI，
 * 也拿不到文件身份。它们在列表里只能查看（不能勾选、不能批量删除）；
 * 清理走 Root 侧带身份快照的流程（一键扫描里的“聊天收到的安装包”、应用专项规则的聊天媒体）。
 */
internal object ChatStorageRecords {
    const val URI_PREFIX = "baize-root:"
    const val READ_ONLY_LABEL = "应用目录 · 仅查看"

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
            records += StorageFileRecord(
                id = -1L - index, uri = URI_PREFIX + path, path = path, name = name,
                bytes = item.optLong("bytes").coerceAtLeast(0L), modifiedSeconds = item.optLong("modified").coerceAtLeast(0L),
                mime = mimeFor(kind), ownerLabel = "${item.optString("app")} · ${item.optString("area")}", identity = null
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

    /** 合并时以路径去重，系统索引里已有的文件（可勾选）优先。 */
    fun merge(indexed: List<StorageFileRecord>, root: List<StorageFileRecord>): List<StorageFileRecord> {
        val paths = indexed.mapTo(HashSet()) { it.path }
        return indexed + root.filter { it.path !in paths }
    }
}
