package io.github.xgl34222220.baize.root

import io.github.xgl34222220.baize.ApkDeletionGuard
import io.github.xgl34222220.baize.ApkFileReadDiagnostics
import org.json.JSONObject
import java.util.UUID

/** Read-only identity proof, scoped to the calling App's user and its explicit indexed APK path. */
internal class ApkFileEvidenceRepository(private val ownAppUid: () -> Int,
    private val epoch: String = UUID.randomUUID().toString(),
    private val stat: (String) -> JSONObject = ApkFileReadDiagnostics::stat,
    private val capture: (String, String) -> io.github.xgl34222220.baize.ApkFileIdentity? = { root, path ->
        ApkDeletionGuard(setOf(root), root).capture(path)
    }) {
    fun read(requestedPath: String, callerUid: Int): String = readScoped(requestedPath, callerUid, true)

    /** Same caller/user/parent safeguards; metadata only for an explicitly indexed shared file. */
    fun readIndexedFile(requestedPath: String, callerUid: Int): String = readScoped(requestedPath, callerUid, false)

    private fun readScoped(requestedPath: String, callerUid: Int, archiveOnly: Boolean): String {
        val owner = ownAppUid()
        val caller = if (callerUid == 0) owner else callerUid
        fun rejected(reason: String) = JSONObject().put("success", false).put("reason", reason).toString()
        if (owner < 10_000 || caller < 10_000 || caller % 100_000 != owner % 100_000) return rejected("caller_mismatch")
        val user = caller / 100_000
        val publicRoot = "/storage/emulated/$user"
        if (!ApkDeletionGuard.validPath(requestedPath) || !requestedPath.startsWith("$publicRoot/") ||
            (archiveOnly && !io.github.xgl34222220.baize.ApkNames.isApk(requestedPath))) return rejected("outside_current_user_storage")
        val backingRoot = "/data/media/$user"
        val backing = backingRoot + requestedPath.removePrefix(publicRoot)
        // This guard rejects links in every component below the trusted storage root.
        val attempts = org.json.JSONArray()
        var identity: io.github.xgl34222220.baize.ApkFileIdentity? = null
        for ((root, path) in listOf(backingRoot to backing, publicRoot to requestedPath)) {
            val rootStat = stat(root)
            attempts.put(JSONObject().put("path", path).put("storageRoot", rootStat).put("stat", stat(path)))
            if (rootStat.optString("kind") != "directory") continue
            identity = capture(root, path)
            if (identity != null) break
        }
        return JSONObject().put("success", identity != null).put("evidenceVersion", 1)
            .put("requesterUid", callerUid).put("requestedPath", requestedPath)
            .put("attempts", attempts).put("reason", if (identity == null) "root_identity_unavailable" else "")
            .put("identity", identity?.json()?.put("backend", "root")?.put("sourceEpoch", epoch) ?: JSONObject.NULL).toString()
    }
}
