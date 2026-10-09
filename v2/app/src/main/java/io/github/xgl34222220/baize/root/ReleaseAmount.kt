package io.github.xgl34222220.baize.root

import org.json.JSONObject

/** Deleted file content is evidence; a scan estimate or a successful request is not. */
internal data class ReleaseAmount(val state: State, val bytes: Long? = null, val retainedBytes: Long? = null) {
    enum class State(val wire: String) { MEASURED("measured"), PARTIAL("partial"), UNKNOWN("unknown"), RETAINED("retained"), NOT_APPLICABLE("not_applicable") }

    fun writeTo(json: JSONObject): JSONObject = json
        .put("releaseState", state.wire)
        .put("releasedBytes", if (state in setOf(State.MEASURED, State.PARTIAL)) bytes ?: JSONObject.NULL else JSONObject.NULL)
        .put("retainedBytes", retainedBytes ?: JSONObject.NULL)

    fun description(format: (Long) -> String): String = when (state) {
        State.MEASURED -> "已确认删除 ${format(requireNotNull(bytes))} 内容"
        State.PARTIAL -> "已确认删除 ${format(requireNotNull(bytes))} 内容 · 部分释放量无法测量"
        State.UNKNOWN -> "释放量无法测量"
        State.RETAINED -> "回收站或隔离区保留 · 尚未释放空间"
        State.NOT_APPLICABLE -> "本次操作不计释放空间"
    }

    companion object {
        fun fromResult(operation: String, result: JSONObject): ReleaseAmount {
            val latest = result.optJSONObject("latest") ?: JSONObject()
            val declared = result.optString("releaseState").ifBlank { latest.optString("releaseState") }
                .ifBlank { result.optString("release_state", latest.optString("release_state")) }
            if (declared == State.UNKNOWN.wire) {
                // "Unknown" describes the remainder of a mixed run. Content the same result reports as
                // actually deleted is still evidence; a generic "bytes" field (scan/move) is not.
                val deleted = firstNumber(result, latest, "releasedBytes", "deletedBytes")
                return if (deleted != null && deleted > 0L) ReleaseAmount(State.PARTIAL, deleted) else ReleaseAmount(State.UNKNOWN)
            }
            val retained = result.optBoolean("trashed") || operation.contains("trash") ||
                (operation.contains("quarantine") && !operation.contains("purge") &&
                    !operation.contains("expire") && !operation.contains("restore"))
            val retainedBytes = firstNumber(result, latest, "retainedBytes", "trashedBytes", "quarantinedBytes", "movedBytes", "bytes")
            val retainedEvidence = result.optBoolean("trashed") || (retainedBytes ?: 0L) > 0L ||
                result.optLong("quarantinedCandidates") > 0L || result.optLong("trashedFiles") > 0L ||
                (result.optBoolean("success") && retainedBytes != null)
            if (declared == State.RETAINED.wire || (retained && retainedEvidence)) return ReleaseAmount(State.RETAINED,
                retainedBytes = retainedBytes)
            if (retained) return ReleaseAmount(State.UNKNOWN)
            if (operation.contains("scan") || operation.contains("organize") || operation.contains("restore") ||
                operation.contains("rule-review") || declared == State.NOT_APPLICABLE.wire) return ReleaseAmount(State.NOT_APPLICABLE)
            // PackageManager reports request completion, never a measured byte count.
            if (operation == "instant-cache" && declared != State.MEASURED.wire) return ReleaseAmount(State.UNKNOWN)
            val bytes = firstNumber(result, latest, "releasedBytes", "deletedBytes", "bytes")
            return if (bytes == null) ReleaseAmount(State.UNKNOWN) else ReleaseAmount(
                if (declared == State.PARTIAL.wire) State.PARTIAL else State.MEASURED, bytes)
        }

        fun fromEvent(event: JSONObject): ReleaseAmount {
            val declared = event.optString("releaseState")
            if (declared.isNotBlank()) return when (declared) {
                State.MEASURED.wire -> nonNegativeLong(event, "releasedBytes")?.let { ReleaseAmount(State.MEASURED, it) }
                    ?: ReleaseAmount(State.UNKNOWN)
                State.PARTIAL.wire -> nonNegativeLong(event, "releasedBytes")?.let { ReleaseAmount(State.PARTIAL, it) }
                    ?: ReleaseAmount(State.UNKNOWN)
                State.RETAINED.wire -> ReleaseAmount(State.RETAINED, retainedBytes = nonNegativeLong(event, "retainedBytes"))
                State.NOT_APPLICABLE.wire -> ReleaseAmount(State.NOT_APPLICABLE)
                else -> ReleaseAmount(State.UNKNOWN)
            }
            val operation = event.optString("operation")
            val amount = fromResult(operation, event)
            // Old JSONL/TSV zeroes do not say whether a byte count was ever returned.
            return if (amount.state == State.MEASURED && amount.bytes == 0L) ReleaseAmount(State.UNKNOWN) else amount
        }

        fun nonNegativeLong(json: JSONObject, key: String): Long? {
            if (!json.has(key) || json.isNull(key)) return null
            return when (val value = json.opt(key)) {
                is Byte, is Short, is Int, is Long -> (value as Number).toLong().takeIf { it >= 0L }
                is String -> value.toLongOrNull()?.takeIf { it >= 0L }
                else -> null
            }
        }

        private fun firstNumber(primary: JSONObject, fallback: JSONObject, vararg keys: String): Long? {
            keys.forEach { if (primary.has(it)) return nonNegativeLong(primary, it) }
            keys.forEach { if (fallback.has(it)) return nonNegativeLong(fallback, it) }
            return null
        }

        fun addSaturated(total: Long, bytes: Long): Long = if (Long.MAX_VALUE - total < bytes) Long.MAX_VALUE else total + bytes

        fun countable(event: JSONObject): Boolean = event.optBoolean("releaseCounted", true) &&
            event.optString("status") !in setOf("accepted", "scanned") &&
            fromEvent(event).state in setOf(State.MEASURED, State.PARTIAL)
    }
}
