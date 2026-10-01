package io.github.xgl34222220.baize

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only observations of a single run-owned fixture after a real deletion attempt. */
internal fun recordIndexedDeletionProbe(context: Context, output: File, name: String,
    uri: String, path: String, identity: ApkFileIdentity?, outcome: ApkIndexedDeleteResult,
    before: JSONObject, mutation: JSONObject = JSONObject()): JSONObject {
    val started = SystemClock.elapsedRealtime()
    val observations = JSONArray()
    val intervals = if (outcome == ApkIndexedDeleteResult.FAILED) listOf(0L, 25L, 100L, 250L, 500L) else listOf(0L)
    for (at in intervals) {
        val delay = started + at - SystemClock.elapsedRealtime()
        if (delay > 0) Thread.sleep(delay)
        observations.put(JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("pathStat", ApkFileReadDiagnostics.stat(path)))
    }
    val evidence = JSONObject().put("outcome", outcome.name).put("mutation", mutation)
        .put("before", before).put("observations", observations)
        .put("after", JSONObject(ApkFileReadDiagnostics.collect(context, uri, path, null, identity, indexedFile = true)))
    File(output, name).writeText(evidence.toString(2))
    return evidence
}
