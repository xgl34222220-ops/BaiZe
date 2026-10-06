package io.github.xgl34222220.baize

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class PendingPhotoCopy(val id: String, val filename: String, val record: PhotoCompressionRecord,
    val note: String = "尚未确认完整，请核对目标副本") {
    fun json() = JSONObject().put("id", id).put("filename", filename).put("record", record.json()).put("note", note)
}

/** Write-ahead records, independent of Activity saved state. Never authorizes deletion or replay. */
internal class PhotoCompressionJournal(private val file: File) {
    private val atomic = AtomicFile(file)
    private fun read(): JSONObject = try {
        atomic.openRead().use { input ->
            require(input.available() <= 512 * 1024) { "上次导出记录过大，请核对目标副本" }
            JSONObject(input.bufferedReader().readText()).also { require(it.optInt("schema") == 1) { "上次导出记录格式无法识别" } }
        }
    } catch (_: java.io.FileNotFoundException) { JSONObject().put("schema", 1) }
    private fun write(value: JSONObject) {
        val bytes = value.toString().toByteArray(); require(bytes.size <= 512 * 1024) { "导出记录过大，请分批处理" }
        file.parentFile?.mkdirs(); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    @Synchronized fun pending(): List<PendingPhotoCopy> {
        val rows = read().optJSONArray("pending") ?: JSONArray()
        require(rows.length() <= 100) { "上次导出记录过多" }
        return List(rows.length()) { i -> val row = rows.getJSONObject(i)
            PendingPhotoCopy(row.getString("id"), row.getString("filename"), photoRecord(row.getJSONObject("record")), row.optString("note")) }
    }
    @Synchronized fun requiresReview(): Boolean = read().optBoolean("active") || pending().isNotEmpty()
    @Synchronized fun message(): String = if (read().optBoolean("active"))
        "上次导出已中断；已完成记录保留，未完成副本需核对，未自动重试。"
        else if (pending().isNotEmpty()) "有待核对的导出副本；原图保留，未自动重试。" else ""
    @Synchronized fun begin(count: Int) {
        check(!requiresReview()) { "请先核对上次导出的副本" }
        write(JSONObject().put("schema", 1).put("active", true).put("selectedCount", count)
            .put("pending", JSONArray()).put("cacheNames", JSONArray()))
    }
    @Synchronized fun trackCache(vararg files: File) {
        val root = read(); val names = root.optJSONArray("cacheNames") ?: JSONArray()
        files.forEach { names.put(it.name) }; require(names.length() <= 200)
        write(root.put("cacheNames", names))
    }
    @Synchronized fun prepare(filename: String, record: PhotoCompressionRecord): String {
        val root = read(); val rows = pending().toMutableList(); check(rows.size < 100)
        val id = UUID.randomUUID().toString(); rows += PendingPhotoCopy(id, filename, record)
        write(root.put("pending", JSONArray().apply { rows.forEach { put(it.json()) } })); return id
    }
    @Synchronized fun target(id: String, uri: String, actualName: String) {
        change { rows -> rows.map { if (it.id == id) it.copy(filename = actualName, record = it.record.copy(uri = uri)) else it } }
    }
    @Synchronized fun note(id: String, note: String) {
        change { rows -> rows.map { if (it.id == id) it.copy(note = note.take(500)) else it } }
    }
    @Synchronized fun confirmed(id: String) { change { rows -> rows.filter { it.id != id } } }
    private fun change(transform: (List<PendingPhotoCopy>) -> List<PendingPhotoCopy>) {
        val root = read(); write(root.put("pending", JSONArray().apply { transform(pending()).forEach { put(it.json()) } }))
    }
    @Synchronized fun finish() { val root = read(); write(root.put("active", false)) }
    @Synchronized fun clearReminder() { atomic.delete() } // Only App-owned journal metadata.
    @Synchronized fun discardOwnedCache(cacheDir: File) {
        val root = read(); val names = root.optJSONArray("cacheNames") ?: JSONArray()
        require(names.length() <= 200)
        for (i in 0 until names.length()) {
            val name = names.optString(i)
            // Generated batch intermediates only; never a document URI, source path or preview.
            if (!name.matches(Regex("photo-batch-(source|output)-[0-9a-f-]{36}\\.jpg"))) continue
            val candidate = File(cacheDir, name)
            if (candidate.canonicalFile.parentFile == cacheDir.canonicalFile && candidate.isFile) candidate.delete()
        }
        write(root.put("cacheNames", JSONArray()))
    }
}

internal fun photoRecord(row: JSONObject) = PhotoCompressionRecord(row.getString("name"), row.getString("sourceHash"),
    row.getString("outputHash"), row.getString("uri"), row.getLong("before"), row.getLong("after"), row.getInt("quality"),
    row.getInt("edge"), PhotoMetadataMode.valueOf(row.getString("metadata")), row.getLong("epoch"))
