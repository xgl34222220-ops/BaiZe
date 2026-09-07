package io.github.xgl34222220.baize

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Serial disk access keeps a returning screen behind its last saved review. */
internal object ScanReviewStore {
    private val disk = Executors.newSingleThreadExecutor()

    fun save(context: Context, key: String, record: () -> JSONObject) {
        val directory = context.applicationContext.filesDir
        disk.execute {
            val file = AtomicFile(File(directory, "scan-review-$key.json"))
            val data = record().toString().toByteArray(Charsets.UTF_8)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(data)
                file.finishWrite(stream)
            } catch (error: java.io.IOException) {
                file.failWrite(stream)
                android.util.Log.w("ScanReview", "无法保存扫描记录", error)
            }
        }
    }

    /** Call from Dispatchers.IO, never from the UI thread. */
    fun read(context: Context, key: String): JSONObject? {
        val directory = context.applicationContext.filesDir
        return disk.submit<JSONObject?> {
            runCatching {
                JSONObject(AtomicFile(File(directory, "scan-review-$key.json")).readFully().toString(Charsets.UTF_8))
            }.getOrNull()
        }.get()
    }
}
