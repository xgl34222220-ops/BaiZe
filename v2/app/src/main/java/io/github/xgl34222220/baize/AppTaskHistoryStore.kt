package io.github.xgl34222220.baize

import android.content.Context
import android.util.AtomicFile
import io.github.xgl34222220.baize.root.ReleaseAmount
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal data class AppHistorySnapshot(
    val entries: List<HistoryUiItem>,
    val lifetimeRuns: Long,
    val lifetimeReleased: Long,
    val lifetimeFiles: Long,
    val lifetimeElapsed: Long
)

/** Persistent manual-clean history owned by the App, independent from the automation module. */
internal object AppTaskHistoryStore {
    private const val MAX_ENTRIES = 100
    private const val FILE_NAME = "app-clean-history.json"

    @Synchronized
    fun append(
        context: Context,
        title: String,
        result: String,
        bytes: Long,
        files: Int,
        elapsedMs: Long,
        categories: List<HistoryCategoryUiItem> = emptyList(),
        apps: List<HistoryAppUiItem> = emptyList(),
        cleaned: Boolean = true,
        release: ReleaseAmount = ReleaseAmount.fromEvent(JSONObject().put("operation", if (cleaned) "app-clean" else "scan").put("bytes", bytes))
    ) {
        val root = readRoot(context)
        val entries = root.optJSONArray("entries") ?: JSONArray()
        val next = JSONArray()
        next.put(
            JSONObject()
                .put("recordId", "app-${UUID.randomUUID()}")
                .put("title", title)
                .put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                .put("trigger", "App 手动")
                .put("result", result.take(1200))
                .put("bytes", release.bytes ?: JSONObject.NULL)
                .put("releaseState", release.state.wire)
                .put("releasedBytes", release.bytes ?: JSONObject.NULL)
                .put("retainedBytes", release.retainedBytes ?: JSONObject.NULL)
                .put("files", files.coerceAtLeast(0))
                .put("emptyDirs", 0)
                .put("errors", 0)
                .put("cleaned", cleaned)
                .put("elapsedMs", elapsedMs.coerceAtLeast(0L))
                .put("categories", JSONArray().apply {
                    categories.forEach { item ->
                        put(JSONObject().put("name", item.name).put("bytes", item.bytes).put("files", item.files))
                    }
                })
                .put("apps", JSONArray().apply {
                    apps.forEach { item ->
                        put(JSONObject()
                            .put("packageName", item.packageName)
                            .put("label", item.label)
                            .put("category", item.category)
                            .put("bytes", item.bytes)
                            .put("files", item.files))
                    }
                })
        )
        for (index in 0 until minOf(entries.length(), MAX_ENTRIES - 1)) next.put(entries.opt(index))
        root.put("entries", next)
            .put("lifetimeRuns", root.optLong("lifetimeRuns", 0L) + if (cleaned) 1L else 0L)
            .put("lifetimeReleased", ReleaseAmount.addSaturated(root.optLong("lifetimeReleased", 0L).coerceAtLeast(0L), release.bytes ?: 0L))
            .put("lifetimeFiles", root.optLong("lifetimeFiles", 0L) + files.coerceAtLeast(0))
            .put("lifetimeElapsed", root.optLong("lifetimeElapsed", 0L) + elapsedMs.coerceAtLeast(0L) / 1000L)
        writeRoot(context, root)
    }

    @Synchronized
    fun read(context: Context): AppHistorySnapshot {
        val root = readRoot(context)
        val array = root.optJSONArray("entries") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val release = ReleaseAmount.fromEvent(JSONObject(item.toString())
                    .put("operation", if (item.optBoolean("cleaned", true)) "app-clean" else "scan"))
                add(
                    HistoryUiItem(
                        title = item.optString("title", "一键清理"),
                        time = item.optString("time"),
                        trigger = item.optString("trigger", "App 手动"),
                        result = item.optString("result"),
                        bytes = release.bytes ?: 0L,
                        releaseState = release.state.wire,
                        // Identical old rows are separate occurrences, even at the same second.
                        recordId = item.optString("recordId").ifBlank {
                            "legacy-app-${UUID.nameUUIDFromBytes("$index:$item".toByteArray())}"
                        },
                        files = item.optInt("files", 0).coerceAtLeast(0),
                        emptyDirs = item.optInt("emptyDirs", 0).coerceAtLeast(0),
                        errors = item.optInt("errors", 0).coerceAtLeast(0),
                        cleaned = item.optBoolean("cleaned", true),
                        categories = decodeCategories(item.optJSONArray("categories")),
                        apps = decodeApps(item.optJSONArray("apps"))
                    )
                )
            }
        }
        return AppHistorySnapshot(
            entries = entries,
            lifetimeRuns = root.optLong("lifetimeRuns", 0L).coerceAtLeast(0L),
            lifetimeReleased = root.optLong("lifetimeReleased", 0L).coerceAtLeast(0L),
            lifetimeFiles = root.optLong("lifetimeFiles", 0L).coerceAtLeast(0L),
            lifetimeElapsed = root.optLong("lifetimeElapsed", 0L).coerceAtLeast(0L)
        )
    }

    @Synchronized
    fun clearRecent(context: Context) {
        val root = readRoot(context)
        root.put("entries", JSONArray())
        writeRoot(context, root)
    }

    private fun decodeCategories(array: JSONArray?): List<HistoryCategoryUiItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            if (name.isBlank()) continue
            add(HistoryCategoryUiItem(name, item.optLong("bytes", 0L).coerceAtLeast(0L), item.optLong("files", 0L).coerceAtLeast(0L)))
        }
    }

    private fun decodeApps(array: JSONArray?): List<HistoryAppUiItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val pkg = item.optString("packageName").trim()
            if (pkg.isBlank()) continue
            add(HistoryAppUiItem(
                packageName = pkg,
                label = item.optString("label").ifBlank { pkg },
                category = item.optString("category"),
                bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                files = item.optLong("files", 0L).coerceAtLeast(0L)
            ))
        }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun readRoot(context: Context): JSONObject = runCatching {
        AtomicFile(file(context)).openRead().bufferedReader().use { JSONObject(it.readText()) }
    }.getOrDefault(JSONObject())

    private fun writeRoot(context: Context, root: JSONObject) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try { stream.write(root.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
}
