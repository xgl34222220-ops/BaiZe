package io.github.xgl34222220.baize

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object LastCleanupStore {
    /** A scan is an estimate, never evidence of deleted files. Workbench owns its result. */
    fun acceptsModuleDetails(mode: String): Boolean = mode.isNotBlank() && mode != "workbench-clean" &&
        mode != "scan" && !mode.endsWith("-scan")

    /** Replace the whole result together; never mix apps and junk from different runs. */
    fun mergeModuleDetails(
        previous: Pair<List<AppJunkUiItem>, List<GeneralJunkUiItem>>,
        apps: List<AppJunkUiItem>, junk: List<GeneralJunkUiItem>
    ): Pair<List<AppJunkUiItem>, List<GeneralJunkUiItem>> =
        if (apps.isNotEmpty() || junk.isNotEmpty()) apps to junk else previous

    fun save(context: Context, apps: List<AppJunkUiItem>, junk: List<GeneralJunkUiItem>) {
        ScanReviewStore.save(context, "last-clean") { encode(apps, junk) }
    }

    fun read(context: Context): Pair<List<AppJunkUiItem>, List<GeneralJunkUiItem>> =
        decode(ScanReviewStore.read(context, "last-clean") ?: JSONObject())

    fun encode(apps: List<AppJunkUiItem>, junk: List<GeneralJunkUiItem>): JSONObject = JSONObject()
        .put("apps", JSONArray().apply { apps.forEach { app -> put(JSONObject()
            .put("packageName", app.packageName).put("label", app.label).put("category", app.category)
            .put("files", app.files).put("bytes", app.bytes).put("errors", app.errors)
            .put("categories", JSONArray().apply { app.categories.forEach { category -> put(JSONObject()
                .put("name", category.name).put("files", category.files).put("bytes", category.bytes)
                .put("errors", category.errors).put("samplePath", category.samplePath)) } })) } })
        .put("junk", JSONArray().apply { junk.forEach { item -> put(JSONObject()
            .put("name", item.name).put("files", item.files).put("bytes", item.bytes)
            .put("errors", item.errors).put("samplePath", item.samplePath)) } })

    fun decode(record: JSONObject): Pair<List<AppJunkUiItem>, List<GeneralJunkUiItem>> {
        val apps = record.optJSONArray("apps") ?: JSONArray()
        val junk = record.optJSONArray("junk") ?: JSONArray()
        return (0 until apps.length()).mapNotNull { i -> apps.optJSONObject(i)?.let { app ->
            val categories = app.optJSONArray("categories") ?: JSONArray()
            AppJunkUiItem(app.optString("packageName"), app.optString("label"), app.optString("category"),
                app.optLong("files"), app.optLong("bytes"), app.optLong("errors"),
                (0 until categories.length()).mapNotNull { j -> categories.optJSONObject(j)?.let { item ->
                    AppJunkCategoryUiItem(item.optString("name"), item.optLong("files"), item.optLong("bytes"),
                        item.optLong("errors"), item.optString("samplePath"))
                } })
        } } to (0 until junk.length()).mapNotNull { i -> junk.optJSONObject(i)?.let { item ->
            GeneralJunkUiItem(item.optString("name"), item.optLong("files"), item.optLong("bytes"),
                item.optLong("errors"), item.optString("samplePath"))
        } }
    }
}
