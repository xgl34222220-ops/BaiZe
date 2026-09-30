package io.github.xgl34222220.baize

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Only visible rows ask for artwork; the scan result never retains every decoded bitmap. */
internal class ApkPreviewCache(
    private val capacity: Int = 64,
    private val inspect: suspend (ApkScanItem) -> ApkArchiveInfo
) {
    private val gate = Semaphore(2)
    private val entries = LinkedHashMap<String, ApkArchiveInfo>(capacity, .75f, true)
    private var generation = 0L

    suspend fun load(item: ApkScanItem): ApkArchiveInfo {
        currentCoroutineContext().ensureActive()
        val key = item.previewKey
        val epoch = synchronized(entries) {
            entries[key]?.let { return it }
            generation
        }
        return gate.withPermit {
            currentCoroutineContext().ensureActive()
            synchronized(entries) {
                if (epoch != generation) throw CancellationException("APK review replaced")
                entries[key]?.let { return@withPermit it }
            }
            val result = inspect(item)
            currentCoroutineContext().ensureActive()
            synchronized(entries) {
                if (epoch != generation) throw CancellationException("APK review replaced")
                entries[key] = result
                while (entries.size > capacity) entries.remove(entries.keys.first())
            }
            result
        }
    }

    fun clear() = synchronized(entries) { generation++; entries.clear() }
}

internal class ApkPreviewViewModel(application: Application) : AndroidViewModel(application) {
    private val cache = ApkPreviewCache { item ->
        ApkArchiveMetadata.inspect(application, item.uri, item.samplePath, item.bytes, item.modifiedSeconds)
    }
    suspend fun load(item: ApkScanItem) = cache.load(item)
    fun invalidate() = cache.clear()
    override fun onCleared() { cache.clear() }
}

internal val ApkScanItem.previewKey: String
    get() = "$uri\u0000$samplePath\u0000$bytes\u0000$modifiedSeconds"

internal val ApkArchiveInfo.awaitingInspection: Boolean
    get() = parseStatus == ApkArchiveParseStatus.PENDING && appName.isBlank() &&
        packageName.isBlank() && status == ApkInstallStatus.UNKNOWN
