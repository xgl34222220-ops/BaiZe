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
            var stream: java.io.FileOutputStream? = null
            try {
                val data = record().toString().toByteArray(Charsets.UTF_8)
                stream = file.startWrite()
                stream.write(data)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                android.util.Log.w("ScanReview", "无法保存扫描记录", error)
            }
        }
    }

    /** Call from Dispatchers.IO, never from the UI thread. */
    fun read(context: Context, key: String, strict: Boolean = false): JSONObject? {
        val directory = context.applicationContext.filesDir
        return disk.submit<JSONObject?> {
            val path = File(directory, "scan-review-$key.json")
            try {
                JSONObject(AtomicFile(path).readFully().toString(Charsets.UTF_8))
            } catch (error: Exception) {
                val missing = error is java.io.FileNotFoundException && !path.exists() && !File(path.path + ".bak").exists()
                if (strict && !missing) throw java.io.IOException("已保留原记录，无法读取扫描历史", error)
                null
            }
        }.get()
    }
}
