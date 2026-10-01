package io.github.xgl34222220.baize

import org.json.JSONObject

/** A missing stat alone is not proof: scoped-storage and disconnected services remain unknown. */
internal object ApkMissingIndexPolicy {
    fun confirmedMissing(report: JSONObject, path: String, uri: String, bytes: Long, modified: Long, appUid: Int): Boolean = runCatching {
        val user = appUid / 100_000
        val publicRoot = "/storage/emulated/$user"
        if (appUid < 10_000 || !path.startsWith("$publicRoot/") || !ApkDeletionGuard.validPath(path)) return false
        if (report.optString("path") != path || report.optString("uri") != uri || report.optInt("appUid", -1) != appUid ||
            !report.optBoolean("allFilesAccess") || !missing(report.optJSONObject("appPathStat")) ||
            !directory(report.optJSONObject("appParentStat")) || !report.isNull("currentAppIdentity")) return false
        val index = report.optJSONObject("index") ?: return false
        if (!index.has("exists")) return false
        if (index.optBoolean("exists") && (index.optString("path") != path || index.optLong("bytes", -1) != bytes ||
            index.optLong("modified", -1) != modified)) return false
        val fd = report.optJSONObject("mediaStoreFd") ?: return false
        // Binder can preserve FileNotFoundException text while dropping the ErrnoException cause.
        // This narrow ENOENT form is only corroboration; both actual Root stats must still be 2.
        val descriptorMissing = missing(fd) || (fd.isNull("errno") && fd.optString("error") == "FileNotFoundException" &&
            (fd.optString("message").startsWith("open failed: ENOENT (") || !index.optBoolean("exists")))
        if (fd.optBoolean("ok", true) || !descriptorMissing) return false
        val root = report.optJSONObject("root") ?: return false
        if (!root.optBoolean("connected") || root.optInt("uid", -1) != 0 || !root.optBoolean("fileEvidenceSupported")) return false
        val file = root.optJSONObject("file") ?: return false
        if (file.optInt("uid", -1) != 0 || !file.optBoolean("root") || file.optInt("evidenceVersion") != 1 ||
            file.optInt("requesterUid", -1) != appUid || file.optString("requestedPath") != path ||
            file.optBoolean("success", true) || !file.isNull("identity") ||
            file.optString("reason") != "root_identity_unavailable") return false
        val backingRoot = "/data/media/$user"
        val expected = setOf(path, backingRoot + path.removePrefix(publicRoot))
        val attempts = file.optJSONArray("attempts") ?: return false
        if (attempts.length() != expected.size) return false
        val seen = HashSet<String>()
        for (i in 0 until attempts.length()) {
            val attempt = attempts.optJSONObject(i) ?: return false
            val target = attempt.optString("path")
            if (target !in expected || !seen.add(target) || !directory(attempt.optJSONObject("storageRoot")) ||
                !missing(attempt.optJSONObject("stat"))) return false
        }
        seen == expected
    }.getOrDefault(false)

    private fun missing(value: JSONObject?): Boolean = value != null && value.has("ok") &&
        !value.optBoolean("ok", true) && value.optInt("errno", -1) == 2
    private fun directory(value: JSONObject?): Boolean = value != null && value.optBoolean("ok") && value.optString("kind") == "directory"
}
