package io.github.xgl34222220.baize

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class StorageDirectory(val path: String, val files: Int, val bytes: Long)
internal data class StorageGrowth(val label: String, val delta: Long)
internal data class StorageGrowthResult(val changes: List<StorageGrowth>, val description: String)
internal fun storageVolume(path: String): String? = Regex("^(/storage/(?:emulated/[0-9]+|[^/]+)|/sdcard|/mnt/media_rw/[^/]+)/").find(path)?.groupValues?.get(1)
internal fun storageDirectories(records: List<StorageFileRecord>, parent: String?): List<StorageDirectory> {
    return records.mapNotNull { record ->
        val volume = storageVolume(record.path) ?: return@mapNotNull null
        val directory = if (parent == null) volume else {
            if (!record.path.startsWith("${parent.trimEnd('/')}/")) return@mapNotNull null
            val relative = record.path.removePrefix("${parent.trimEnd('/')}/")
            if (!relative.contains('/')) return@mapNotNull null
            "${parent.trimEnd('/')}/${relative.substringBefore('/')}"
        }
        directory to record
    }.groupBy({ it.first }, { it.second }).map { (path, files) -> StorageDirectory(path, files.size, files.sumOf { it.verifiedBytes }) }
        .sortedByDescending { it.bytes }
}

/** Same complete, identity-verified scope only. Unknown/truncated scans never replace the baseline. */
internal class StorageGrowthStore(private val file: File) {
    fun record(index: StorageIndexResult, now: Long = System.currentTimeMillis()): StorageGrowthResult {
        if (index.truncated || index.presenceIncomplete || index.records.any { it.verifiedBytes == 0L })
            return StorageGrowthResult(emptyList(), "本轮范围或身份核对不完整，未更新增长基线")
        val scope = index.records.mapNotNull { storageVolume(it.path) }.distinct().sorted().joinToString("|")
        if (scope.isBlank()) return StorageGrowthResult(emptyList(), "没有可比较的存储卷，未更新增长基线")
        val totals = linkedMapOf<String, Long>()
        index.records.forEach { record ->
            val root = storageVolume(record.path) ?: return@forEach
            var parent = File(record.path).parentFile
            while (parent != null && (parent.path == root || parent.path.startsWith("$root/"))) {
                totals[parent.path] = (totals[parent.path] ?: 0) + record.bytes
                parent = parent.parentFile
            }
            storageOwnerPackage(record.path)?.let { pkg -> totals["应用：$pkg"] = (totals["应用：$pkg"] ?: 0) + record.bytes }
        }
        val previous = runCatching { JSONObject(file.readText()) }.getOrNull()
        val comparable = previous?.optInt("schema") == 1 && previous.optString("scope") == scope && previous.optLong("epoch") <= now
        val old = if (comparable) previous!!.optJSONObject("totals") ?: JSONObject() else JSONObject()
        val keys = totals.keys + old.keys().asSequence().toSet()
        val changes = if (comparable) keys.map { StorageGrowth(it, (totals[it] ?: 0) - old.optLong(it)) }
            .filter { it.delta != 0L }.sortedByDescending { it.delta }.take(30) else emptyList()
        file.parentFile?.mkdirs()
        val next = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(next).use { output ->
            output.write(JSONObject().put("schema", 1).put("scope", scope).put("epoch", now).put("totals", JSONObject(totals as Map<*, *>)).toString().toByteArray())
            output.fd.sync()
        }
        Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        val description = if (comparable) "与 ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(previous!!.optLong("epoch")))} 的完整索引比较；目录数值包含子目录，应用仅统计 Android 归属路径"
            else "已建立完整索引基线；下次在相同存储卷完成扫描后显示增长"
        return StorageGrowthResult(changes, description)
    }
}
