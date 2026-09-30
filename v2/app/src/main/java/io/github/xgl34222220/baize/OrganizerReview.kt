package io.github.xgl34222220.baize

import org.json.JSONArray
import org.json.JSONObject

internal data class OrganizerPreviewItem(
    val id: String,
    val name: String,
    val category: String,
    val bytes: Long,
    val sourceGroup: String,
    val source: String,
    val destination: String
)

internal data class FileOrganizerUiState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val status: String = "等待连接",
    val undoAvailable: Boolean = true,
    val lastTotal: Int = 0,
    val lastBytes: Long = 0L,
    val snapshotId: String = "",
    val previewReady: Boolean = false,
    val expiresAtRealtime: Long = 0L,
    val items: List<OrganizerPreviewItem> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val totalFound: Int = 0,
    val truncated: Boolean = false
) {
    fun canEdit(now: Long) = connected && !running && previewReady && snapshotId.isNotBlank() &&
        now < expiresAtRealtime && items.size == totalFound
}

internal fun parseOrganizerPreview(array: JSONArray): List<OrganizerPreviewItem> =
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id")
        val source = item.optString("sourceDisplay")
        val destination = item.optString("destinationDisplay")
        if (id.isBlank() || source.isBlank() || destination.isBlank()) return@mapNotNull null
        OrganizerPreviewItem(id, item.optString("name"), item.optString("category").ifBlank { "其他" },
            item.optLong("bytes").coerceAtLeast(0L), item.optString("sourceGroup"), source, destination)
    }

internal fun organizerReviewJson(state: FileOrganizerUiState, wallTime: Long, realtime: Long): JSONObject =
    JSONObject().put("snapshotId", state.snapshotId)
        .put("ready", state.previewReady && !state.running)
        .put("expiresAt", wallTime + (state.expiresAtRealtime - realtime).coerceAtLeast(0L))
        .put("status", state.status).put("totalFound", state.totalFound).put("truncated", state.truncated)
        .put("lastTotal", state.lastTotal).put("lastBytes", state.lastBytes).put("undoAvailable", state.undoAvailable)
        .put("selected", JSONArray(state.selectedIds.toList()))
        .put("items", JSONArray().apply {
            state.items.forEach { item -> put(JSONObject().put("id", item.id).put("name", item.name)
                .put("category", item.category).put("bytes", item.bytes).put("sourceGroup", item.sourceGroup)
                .put("sourceDisplay", item.source).put("destinationDisplay", item.destination)) }
        })

internal fun restoreOrganizerReview(json: JSONObject, wallTime: Long, realtime: Long): FileOrganizerUiState {
    val items = parseOrganizerPreview(json.optJSONArray("items") ?: JSONArray())
    val availableIds = items.mapTo(hashSetOf()) { it.id }
    val selected = json.optJSONArray("selected") ?: JSONArray()
    val remaining = (json.optLong("expiresAt") - wallTime).coerceIn(0L, 30 * 60 * 1_000L)
    val ready = json.optBoolean("ready") && remaining > 0L && items.size == json.optInt("totalFound") &&
        availableIds.size == items.size && json.optString("snapshotId").isNotBlank()
    return FileOrganizerUiState(
        status = if (items.isEmpty()) json.optString("status", "等待连接") else if (ready) "已恢复归类预览，请核对后继续选择"
            else "上次归类记录已保留；重新扫描后可选择文件",
        undoAvailable = json.optBoolean("undoAvailable", true), lastTotal = json.optInt("lastTotal"),
        lastBytes = json.optLong("lastBytes"), snapshotId = json.optString("snapshotId"), previewReady = ready,
        expiresAtRealtime = realtime + remaining, items = items,
        selectedIds = (0 until selected.length()).map { selected.optString(it) }.filterTo(linkedSetOf()) { it in availableIds },
        totalFound = json.optInt("totalFound"), truncated = json.optBoolean("truncated")
    )
}
