package io.github.xgl34222220.baize

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class PhotoCompressionRecord(val name: String, val sourceHash: String, val outputHash: String,
    val uri: String, val before: Long, val after: Long, val quality: Int, val edge: Int,
    val metadata: PhotoMetadataMode, val epoch: Long = System.currentTimeMillis()) {
    fun json() = JSONObject().put("name", name).put("sourceHash", sourceHash).put("outputHash", outputHash)
        .put("uri", uri).put("before", before).put("after", after).put("quality", quality).put("edge", edge)
        .put("metadata", metadata.name).put("epoch", epoch)
}
internal class PhotoCompressionHistory(private val file: File) {
    @Synchronized fun records(): List<PhotoCompressionRecord> = if (!file.exists()) emptyList() else {
        require(file.length() <= 256 * 1024) { "压缩记录过大" }
        val rows = JSONArray(file.readText())
        List(minOf(rows.length(), 100)) { i -> photoRecord(rows.getJSONObject(i)) }
    }
    @Synchronized fun alreadyProcessed(hash: String, quality: Int, edge: Int, metadata: PhotoMetadataMode): Boolean = records().any {
        it.outputHash == hash || (it.sourceHash == hash && it.quality == quality && it.edge == edge && it.metadata == metadata)
    }
    @Synchronized fun add(record: PhotoCompressionRecord) {
        val rows = listOf(record) + records().take(99)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { it.write(JSONArray().apply { rows.forEach { row -> put(row.json()) } }.toString().toByteArray()); it.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
internal fun photoDigest(input: java.io.InputStream, check: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
    var bytes = 0L
    while (true) { check(); val count = input.read(buffer); if (count < 0) break
        bytes += count; require(bytes <= PhotoCompressionPolicy.MAX_INPUT_BYTES) { "副本超出核对上限，已保留" }
        digest.update(buffer, 0, count) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
