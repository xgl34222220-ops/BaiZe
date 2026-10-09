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

    /**
     * One run's per-app/per-category result. [recordId] links it to the history row of the same run;
     * [deletedEvidence] is true only when every byte in it was reported as actually deleted content
     * (never a scan estimate), so the history card may quote it when the run total is unmeasured.
     */
    data class Run(
        val apps: List<AppJunkUiItem>,
        val junk: List<GeneralJunkUiItem>,
        val recordId: String = "",
        val deletedEvidence: Boolean = false
    )

    fun save(
        context: Context,
        apps: List<AppJunkUiItem>,
        junk: List<GeneralJunkUiItem>,
        recordId: String = "",
        deletedEvidence: Boolean = false
    ) {
        ScanReviewStore.save(context, "last-clean") { encode(apps, junk, recordId, deletedEvidence) }
    }

    fun read(context: Context): Pair<List<AppJunkUiItem>, List<GeneralJunkUiItem>> =
        decode(ScanReviewStore.read(context, "last-clean") ?: JSONObject())

    fun readRun(context: Context): Run = decodeRun(ScanReviewStore.read(context, "last-clean") ?: JSONObject())

    fun decodeRun(record: JSONObject): Run = decode(record).let { (apps, junk) ->
        Run(apps, junk, record.optString("recordId").trim().take(100),
            record.optBoolean("deletedEvidence", false))
    }

    fun encode(
        apps: List<AppJunkUiItem>,
        junk: List<GeneralJunkUiItem>,
        recordId: String = "",
        deletedEvidence: Boolean = false
    ): JSONObject = JSONObject()
        .put("recordId", recordId.trim().take(100))
        .put("deletedEvidence", deletedEvidence)
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
