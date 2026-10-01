package io.github.xgl34222220.baize

import android.content.Context
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.Process
import android.os.SystemClock
import io.github.xgl34222220.baize.root.IProfileRootService
import org.json.JSONObject

/** Removes proven stale records from this review only; never deletes an index URI or a file. */
internal object ApkIndexPresenceReview {
    fun query(context: Context, cancellation: CancellationSignal = CancellationSignal(), remote: IProfileRootService?): ApkMediaStoreResult {
        val result = ApkMediaStoreIndex.query(context, cancellation)
        if (result.error != null || result.cancelled || remote == null) return result
        return review(result, cancellation, inspect = { item ->
            JSONObject(ApkFileReadDiagnostics.collect(context, item.uri, item.path, remote, item.identity, cancellation))
        }, appUid = Process.myUid())
    }

    internal fun review(result: ApkMediaStoreResult, cancellation: CancellationSignal,
        inspect: (IndexedApkCandidate) -> JSONObject, appUid: Int,
        now: () -> Long = SystemClock::elapsedRealtime): ApkMediaStoreResult {
        val started = now()
        val kept = ArrayList<IndexedApkCandidate>(result.candidates.size)
        var removed = 0
        var inspected = 0
        var incomplete = false
        try {
            for (item in result.candidates) {
                cancellation.throwIfCanceled()
                if (item.identity != null) { kept += item; continue }
                if (inspected >= 1_000 || now() - started >= 5_000L) {
                    incomplete = true; kept += item; continue
                }
                inspected++
                val report = try { inspect(item) } catch (cancelled: OperationCanceledException) { throw cancelled }
                    catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                cancellation.throwIfCanceled()
                if (report != null && ApkMissingIndexPolicy.confirmedMissing(report, item.path, item.uri,
                        item.bytes, item.modifiedSeconds, appUid)) removed++ else kept += item
            }
        } catch (_: OperationCanceledException) {
            return result.copy(candidates = emptyList(), cancelled = true, confirmedMissingRecords = 0)
        }
        return result.copy(candidates = kept, confirmedMissingRecords = removed, missingCheckIncomplete = incomplete,
            elapsedMs = result.elapsedMs + (now() - started).coerceAtLeast(0L))
    }
}
